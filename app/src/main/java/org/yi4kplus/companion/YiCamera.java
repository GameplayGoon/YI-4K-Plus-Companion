package org.yi4kplus.companion;

import android.net.Network;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.SocketFactory;

/** Persistent, serialized YI command channel. Call blocking methods off the UI thread. */
public final class YiCamera implements Closeable {
    private static final int MAX_FRAME = 262144;
    private static final int MAX_REQUEST = 65536;
    private static final int MAX_EVENTS = 256;
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(work -> {
        Thread thread = new Thread(work, "yi-control-deadline");
        thread.setDaemon(true);
        return thread;
    });

    /** An explicit rejection is not a broken connection or uncertain command outcome. */
    public static final class CameraRejectedException extends IOException {
        public final int messageId;
        public final int rval;
        public final JSONObject response;
        CameraRejectedException(int messageId, JSONObject response) {
            super("Camera rejected command " + messageId + " (code " + response.optInt("rval", -1) + ")");
            this.messageId = messageId;
            this.rval = response.optInt("rval", -1);
            this.response = response;
        }
    }

    /** The session was discarded. Never automatically replay a mutation after this exception. */
    public static final class TransportException extends IOException {
        TransportException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class Session {
        final Socket socket;
        final InputStreamReader in;
        final OutputStreamWriter out;
        final AtomicInteger videoCompleteEvents = new AtomicInteger();
        volatile int token;
        volatile boolean ready;
        volatile boolean closed;
        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
            this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        }
    }

    private final SocketFactory socketFactory;
    private final String host;
    private final int port;
    private final int commandTimeoutMs;
    private final int photoTimeoutMs;
    private final Object wireLock = new Object();
    private final Object lifecycle = new Object();
    private volatile Session session;
    private Socket connectingSocket;
    private int generation;

    public YiCamera(Network network, String host) {
        this(network.getSocketFactory(), host, 7878, 10000, 20000);
    }

    /** Package-local injection tests the actual production transport with a local fake camera. */
    YiCamera(SocketFactory factory, String host, int port, int commandTimeoutMs, int photoTimeoutMs) {
        if (factory == null || host == null || port < 1 || port > 65535 || commandTimeoutMs <= 0 || photoTimeoutMs <= 0)
            throw new IllegalArgumentException("Camera endpoint/timeouts");
        this.socketFactory = factory;
        this.host = host;
        this.port = port;
        this.commandTimeoutMs = commandTimeoutMs;
        this.photoTimeoutMs = photoTimeoutMs;
    }

    public void connect() throws Exception {
        connect(() -> true);
    }

    /** External intent guard prevents a cancelled queued connection from opening a new socket.
     * The predicate must be quick and nonblocking; it is also evaluated under the lifecycle lock.
     */
    public void connect(BooleanSupplier stillWanted) throws Exception {
        if (stillWanted == null) throw new IllegalArgumentException("Connection intent guard");
        final int attempt;
        final Session old;
        final Socket oldConnecting;
        synchronized (lifecycle) {
            if (!stillWanted.getAsBoolean()) throw new IOException("Camera connection cancelled");
            attempt = ++generation;
            old = session;
            oldConnecting = connectingSocket;
            session = null;
            connectingSocket = null;
            if (old != null) old.closed = true;
        }
        closeSocket(old == null ? null : old.socket);
        closeSocket(oldConnecting);
        Socket candidate = null;
        Session active = null;
        try {
            if (!stillWanted.getAsBoolean()) throw new IOException("Camera connection cancelled");
            candidate = socketFactory.createSocket();
            synchronized (lifecycle) {
                if (generation != attempt || !stillWanted.getAsBoolean()) throw new IOException("Camera connection cancelled");
                connectingSocket = candidate;
            }
            synchronized (wireLock) {
                ensureAttempt(attempt, candidate, stillWanted);
                DebugLog.info("Opening Yi protocol socket on " + host + ":" + port);
                candidate.connect(new InetSocketAddress(host, port), 6000);
                candidate.setSoTimeout(commandTimeoutMs);
                candidate.setKeepAlive(true);
                candidate.setTcpNoDelay(true);
                active = new Session(candidate);
                synchronized (lifecycle) {
                    if (generation != attempt || connectingSocket != candidate || !stillWanted.getAsBoolean())
                        throw new IOException("Camera connection cancelled");
                    session = active;
                    connectingSocket = null;
                }
                JSONObject hello = new JSONObject().put("param", 0).put("heartbeat", 1);
                JSONObject reply = exchange(active, 257, hello);
                int token = reply.optInt("param", 0);
                if (token <= 0) throw new IOException("No valid camera session token");
                synchronized (lifecycle) {
                    if (session != active || active.closed || generation != attempt || !stillWanted.getAsBoolean())
                        throw new IOException("Camera connection cancelled");
                    active.token = token;
                    active.ready = true;
                }
                DebugLog.info("Yi handshake accepted (token contents not logged)");
            }
        } catch (Exception failure) {
            if (active != null) invalidate(active);
            closeSocket(candidate);
            synchronized (lifecycle) {
                if (connectingSocket == candidate) connectingSocket = null;
            }
            throw failure;
        }
    }

    private void ensureAttempt(int attempt, Socket socket, BooleanSupplier stillWanted) throws IOException {
        synchronized (lifecycle) {
            if (generation != attempt || connectingSocket != socket || !stillWanted.getAsBoolean())
                throw new IOException("Camera connection cancelled");
        }
    }

    public boolean isOpen() {
        Session active = session;
        return active != null && active.ready && !active.closed && !active.socket.isClosed();
    }

    public JSONObject request(int id, JSONObject fields) throws Exception {
        synchronized (wireLock) {
            Session active = session;
            if (active == null || !active.ready || active.closed) throw new IOException("Camera not connected");
            if (id == 257) throw new IOException("Use Connect to negotiate a new camera session");
            return exchange(active, id, fields);
        }
    }

    private final class Deadline {
        final Session active;
        final AtomicBoolean finished = new AtomicBoolean();
        final long expiresNanos;
        final ScheduledFuture<?> task;
        volatile boolean timedOut;
        Deadline(Session active, int timeoutMs) {
            this.active = active;
            expiresNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            task = DEADLINES.schedule(() -> {
                if (finished.compareAndSet(false, true)) {
                    timedOut = true;
                    invalidate(active); // Also unblocks a stalled socket write.
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
        }
        void beforeRead() throws IOException {
            long remaining = expiresNanos - System.nanoTime();
            if (remaining <= 0 || timedOut) throw timeout();
            int milliseconds = (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                TimeUnit.NANOSECONDS.toMillis(remaining) + 1L));
            active.socket.setSoTimeout(milliseconds);
        }
        void accept() throws IOException {
            if (System.nanoTime() >= expiresNanos || !finished.compareAndSet(false, true)) throw timeout();
            task.cancel(false);
            if (active.closed || session != active) throw new IOException("Camera connection was closed during command");
        }
        void cancel() { finished.set(true); task.cancel(false); }
        SocketTimeoutException timeout() {
            return new SocketTimeoutException("Camera protocol reply timed out; command outcome may be unknown");
        }
    }

    private JSONObject exchange(Session active, int id, JSONObject fields) throws Exception {
        if (id <= 0) throw new IllegalArgumentException("Camera command id");
        JSONObject request = fields == null ? new JSONObject() : new JSONObject(fields.toString());
        request.put("msg_id", id).put("token", active.token);
        String encoded = request.toString();
        if (encoded.length() > MAX_REQUEST) throw new IOException("Camera request exceeds safety limit");
        Deadline deadline = new Deadline(active, id == 769 ? photoTimeoutMs : commandTimeoutMs);
        long started = System.nanoTime();
        if (id != 13) DebugLog.info("Camera request " + id + " sent");
        try {
            if (active.closed || session != active) throw new IOException("Camera connection closed before sending");
            active.out.write(encoded);
            active.out.flush();
            for (int i = 0; i < MAX_EVENTS; i++) {
                deadline.beforeRead();
                JSONObject reply = new JSONObject(JsonFramer.readObject(active.in, MAX_FRAME, deadline.expiresNanos));
                int replyId = reply.optInt("msg_id", -1);
                if (replyId != id) {
                    if (replyId == 7) {
                        String type = reply.optString("type", "unknown").replaceAll("[^a-zA-Z0-9_-]", "_");
                        if (id != 13) DebugLog.info("Camera event: " + type);
                        if ("video_record_complete".equals(type)) active.videoCompleteEvents.incrementAndGet();
                        // Firmware 1.4.14 may acknowledge a photo only through its completion event.
                        if (id == 769 && ("photo_taken".equals(type) || "burst_complete".equals(type))) {
                            deadline.accept();
                            DebugLog.info("Capture confirmed by " + type + " event in " + elapsedMs(started) + "ms");
                            return new JSONObject().put("msg_id", 769).put("rval", 0).put("confirmed_by", type);
                        }
                    }
                    continue;
                }
                int code = resultCode(reply);
                deadline.accept();
                if (code != 0) {
                    DebugLog.warn("Camera rejected request " + id + " code " + code);
                    throw new CameraRejectedException(id, reply);
                }
                if (id != 13) DebugLog.info("Camera replied OK to " + id + " in " + elapsedMs(started) + "ms");
                return reply;
            }
            throw new IOException("Camera protocol reply exceeded the status-event limit");
        } catch (CameraRejectedException rejection) {
            throw rejection;
        } catch (Exception failure) {
            invalidate(active);
            if (deadline.timedOut || failure instanceof SocketTimeoutException) {
                SocketTimeoutException timeout = deadline.timeout();
                timeout.initCause(failure);
                throw timeout;
            }
            throw new TransportException("Camera protocol reply failed; connection closed and command outcome may be unknown", failure);
        } finally {
            deadline.cancel();
            if (!active.closed) try { active.socket.setSoTimeout(commandTimeoutMs); } catch (IOException ignored) { }
        }
    }

    private static long elapsedMs(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static int resultCode(JSONObject reply) throws IOException {
        Object value = reply.opt("rval");
        String text = String.valueOf(value);
        if (!text.matches("-?[0-9]+")) throw new IOException("Camera protocol reply has no valid integer result code");
        try { return Integer.parseInt(text); }
        catch (NumberFormatException invalid) { throw new IOException("Camera protocol reply result code is out of range", invalid); }
    }
    public JSONObject request(int id) throws Exception { return request(id, null); }

    /** Completion notifications are consumed only on the serialized control channel. */
    public int takeVideoCompleteEvents() {
        Session active = session;
        return active == null ? 0 : active.videoCompleteEvents.getAndSet(0);
    }

    public void startRecording() throws Exception { request(513); }
    public void stopRecording() throws Exception { request(514); }
    public void takePhoto() throws Exception { request(769); }
    public void startViewfinder() throws Exception { request(259); }
    public void stopViewfinder() throws Exception { request(260); }
    public JSONObject getSettings() throws Exception { return request(3); }
    public JSONObject battery() throws Exception { return request(13); }

    public long sdSpace(String type) throws Exception {
        if (!"free".equals(type) && !"total".equals(type)) throw new IllegalArgumentException("Unsupported space query");
        JSONObject reply = request(5, new JSONObject().put("type", type));
        try { return Long.parseLong(String.valueOf(reply.opt("param"))); }
        catch (Exception failure) { throw new IOException("Camera returned unknown SD capacity format", failure); }
    }

    public JSONObject getChoices(String name) throws Exception { return request(9, new JSONObject().put("param", name)); }
    public JSONObject getSetting(String name) throws Exception { return request(1, new JSONObject().put("type", name)); }
    public void setSetting(String name, String value) throws Exception {
        request(2, new JSONObject().put("type", name).put("param", value));
    }

    private void invalidate(Session active) {
        synchronized (lifecycle) {
            active.closed = true;
            active.ready = false;
            if (session == active) session = null;
        }
        closeSocket(active.socket);
    }

    /** Closing does not wait for the command lock: TCP close promptly unblocks reads and writes. */
    @Override public void close() {
        final Session old;
        final Socket opening;
        synchronized (lifecycle) {
            ++generation;
            old = session;
            opening = connectingSocket;
            session = null;
            connectingSocket = null;
            if (old != null) { old.closed = true; old.ready = false; }
        }
        closeSocket(old == null ? null : old.socket);
        closeSocket(opening);
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
