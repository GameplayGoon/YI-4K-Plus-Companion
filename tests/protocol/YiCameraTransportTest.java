package org.yi4kplus.companion;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.SocketFactory;
import org.json.JSONObject;

/** Host TCP tests exercise production YiCamera; only unused Android/log adapters are stubbed. */
public final class YiCameraTransportTest {
    interface Script { void serve(Socket socket, Reader in, OutputStream out) throws Exception; }
    static int passed;
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static JSONObject read(Reader in) throws Exception {
        return new JSONObject(JsonFramer.readObject(in, 262144));
    }
    static void send(OutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8)); out.flush();
    }
    static void hello(Reader in, OutputStream out) throws Exception {
        JSONObject request = read(in);
        check(request.getInt("msg_id") == 257 && request.getInt("token") == 0, "fresh handshake token");
        send(out, "{\"msg_id\":257,\"rval\":0,\"param\":43}");
    }
    static final class Fake implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final Thread worker;
        volatile Socket socket;
        Fake(Script script) throws Exception {
            worker = new Thread(() -> {
                try (Socket connected = listener.accept()) {
                    socket = connected;
                    connected.setSoTimeout(2500);
                    Reader in = new InputStreamReader(connected.getInputStream(), StandardCharsets.UTF_8);
                    script.serve(connected, in, connected.getOutputStream());
                } catch (SocketException expectedWhenClientCloses) {
                } catch (Throwable failure) { error.set(failure); }
            }, "fake-yi-camera");
            worker.setDaemon(true); worker.start();
        }
        YiCamera camera(int timeout, int photoTimeout) {
            return new YiCamera(SocketFactory.getDefault(), "127.0.0.1", listener.getLocalPort(), timeout, photoTimeout);
        }
        @Override public void close() throws Exception {
            worker.join(1500);
            listener.close();
            if (socket != null) socket.close();
            worker.join(500);
            Throwable failure = error.get();
            if (failure != null) throw new AssertionError("Fake camera failed", failure);
        }
    }
    interface Test { void run() throws Exception; }
    static void run(String name, Test test) throws Exception {
        test.run(); passed++; System.out.println("PASS: " + name);
    }
    static void rejectionRetainsSession() throws Exception {
        try (Fake fake = new Fake((socket, in, out) -> {
            hello(in, out);
            check(read(in).getInt("msg_id") == 259, "preview request");
            send(out, "{\"msg_id\":259,\"rval\":-14}");
            check(read(in).getInt("msg_id") == 13, "battery after rejection");
            send(out, "{\"msg_id\":13,\"rval\":0,\"param\":75}");
        }); YiCamera camera = fake.camera(1000, 1500)) {
            camera.connect();
            try { camera.startViewfinder(); throw new AssertionError("rejection expected"); }
            catch (YiCamera.CameraRejectedException rejected) {
                check(rejected.messageId == 259 && rejected.rval == -14, "typed rejection details");
            }
            check(camera.isOpen(), "explicit rejection does not kill session");
            check(camera.battery().getInt("param") == 75, "next command still works");
        }
    }
    static void fragmentationAndEvents() throws Exception {
        try (Fake fake = new Fake((socket, in, out) -> {
            read(in);
            byte[] bytes = ("junk{\"msg_id\":7,\"type\":\"boot\",\"param\":\"é {quoted} \\\"hi\\\"\"}" +
                "{\"msg_id\":257,\"rval\":0,\"param\":43}").getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < bytes.length; i++) { out.write(bytes[i]); if (i % 7 == 0) out.flush(); }
            out.flush();
            JSONObject battery = read(in);
            check(battery.getInt("msg_id") == 13 && battery.getInt("token") == 43, "negotiated token used");
            send(out, "{\"msg_id\":7,\"type\":\"video_record_complete\"}{\"msg_id\":13,\"rval\":0,\"param\":80}");
        }); YiCamera camera = fake.camera(1000, 1500)) {
            camera.connect();
            check(camera.battery().getInt("param") == 80, "fragmented stream is parsed");
            check(camera.takeVideoCompleteEvents() == 1 && camera.takeVideoCompleteEvents() == 0,
                "completion events preserved and atomically consumed");
        }
    }
    static void eventStormDeadline() throws Exception {
        AtomicInteger received = new AtomicInteger();
        try (Fake fake = new Fake((socket, in, out) -> {
            hello(in, out); read(in); received.incrementAndGet();
            for (int i = 0; i < 30; i++) {
                send(out, "{\"msg_id\":7,\"type\":\"battery\",\"param\":60}");
                Thread.sleep(30);
            }
            send(out, "{\"msg_id\":513,\"rval\":0}");
        }); YiCamera camera = fake.camera(250, 500)) {
            camera.connect();
            long start = System.nanoTime();
            try { camera.startRecording(); throw new AssertionError("timeout expected"); }
            catch (SocketTimeoutException expected) { }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            check(elapsed >= 150 && elapsed < 1100, "events must not extend overall timeout: " + elapsed);
            check(!camera.isOpen(), "timeout invalidates channel");
            try { camera.startRecording(); throw new AssertionError("closed channel expected"); }
            catch (IOException expected) { }
            check(received.get() == 1, "mutation not automatically replayed");
        }
    }
    static void partialDripDeadline() throws Exception {
        try (Fake fake = new Fake((socket, in, out) -> {
            hello(in, out); read(in);
            for (char ch : "{\"msg_id\":13,\"rval\":0,\"param\":90}".toCharArray()) {
                out.write(ch); out.flush(); Thread.sleep(55);
            }
        }); YiCamera camera = fake.camera(260, 500)) {
            camera.connect(); long start = System.nanoTime();
            try { camera.battery(); throw new AssertionError("deadline expected"); }
            catch (SocketTimeoutException expected) { }
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000, "partial bytes cannot reset timeout");
            check(!camera.isOpen(), "partial timed-out frame closes session");
        }
    }
    static void closeUnblocksRequest() throws Exception {
        CountDownLatch requestSeen = new CountDownLatch(1);
        try (Fake fake = new Fake((socket, in, out) -> {
            hello(in, out); read(in); requestSeen.countDown();
            while (in.read() != -1) { }
        }); YiCamera camera = fake.camera(3000, 4000)) {
            camera.connect();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> pending = worker.submit(() -> {
                    try { camera.battery(); throw new AssertionError("request should fail on close"); }
                    catch (YiCamera.TransportException expected) { }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                });
                check(requestSeen.await(1, TimeUnit.SECONDS), "request reached fake camera");
                long start = System.nanoTime(); camera.close();
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250, "close does not wait on request lock");
                pending.get(1, TimeUnit.SECONDS);
                check(!camera.isOpen(), "closed after cancellation");
            } finally { worker.shutdownNow(); }
        }
    }
    static void eventPhotoAck() throws Exception {
        try (Fake fake = new Fake((socket, in, out) -> {
            hello(in, out);
            check(read(in).getInt("msg_id") == 769, "photo command preserved");
            Thread.sleep(220);
            send(out, "{\"msg_id\":7,\"type\":\"photo_taken\",\"param\":\"YI0001.JPG\"}");
            check(read(in).getInt("msg_id") == 13, "next command after event ack");
            send(out, "{\"msg_id\":769,\"rval\":0}{\"msg_id\":13,\"rval\":0,\"param\":55}");
        }); YiCamera camera = fake.camera(140, 1000)) {
            camera.connect();
            JSONObject reply = camera.request(769);
            check("photo_taken".equals(reply.optString("confirmed_by")), "event-only firmware capture accepted");
            check(camera.battery().getInt("param") == 55, "late photo reply cannot match battery command");
        }
    }
    static void eofAndMalformed() throws Exception {
        String[] cases = {"{\"msg_id\":13,\"rval\":", "{\"msg_id\":13,\"param\":20}", "{\"msg_id\":13,\"rval\":\"garbage\"}", "{\"msg_id\":13,\"rval\":0,\"param\":[}}"};
        for (String response : cases) {
            try (Fake fake = new Fake((socket, in, out) -> {
                hello(in, out); read(in); send(out, response); socket.shutdownOutput();
            }); YiCamera camera = fake.camera(700, 1000)) {
                camera.connect();
                try { camera.battery(); throw new AssertionError("bad reply must fail"); }
                catch (YiCamera.TransportException expected) { }
                check(!camera.isOpen(), "bad/partial reply closes session");
            }
        }
        try (Fake fake = new Fake((socket, in, out) -> {
            hello(in, out); read(in);
            out.write(new byte[]{'{','"','m','s','g','_', 'i','d','"',':','1','3',',','"','r','v','a','l','"',':','0',',','"','p','a','r','a','m','"',':','"',(byte)0xc3,(byte)0x28,'"','}'});
            out.flush();
        }); YiCamera camera = fake.camera(700, 1000)) {
            camera.connect();
            try { camera.battery(); throw new AssertionError("invalid UTF-8 must fail"); }
            catch (YiCamera.TransportException expected) { }
            check(!camera.isOpen(), "invalid encoding cannot be silently replaced");
        }
    }
    static void serialization() throws Exception {
        CountDownLatch firstSeen = new CountDownLatch(1);
        try (Fake fake = new Fake((socket, in, out) -> {
            hello(in, out); JSONObject first = read(in); firstSeen.countDown();
            socket.setSoTimeout(140);
            try { in.read(); throw new AssertionError("second command sent before first reply"); }
            catch (SocketTimeoutException expected) { }
            socket.setSoTimeout(1000);
            send(out, "{\"msg_id\":" + first.getInt("msg_id") + ",\"rval\":0,\"param\":60}");
            JSONObject second = read(in);
            send(out, "{\"msg_id\":" + second.getInt("msg_id") + ",\"rval\":0,\"param\":70}");
        }); YiCamera camera = fake.camera(1200, 1500)) {
            camera.connect(); ExecutorService worker = Executors.newFixedThreadPool(2);
            try {
                Future<JSONObject> first = worker.submit(() -> camera.request(13));
                check(firstSeen.await(1, TimeUnit.SECONDS), "first request arrived");
                Future<JSONObject> second = worker.submit(() -> camera.request(3));
                check(first.get(2, TimeUnit.SECONDS).getInt("param") == 60, "first result matched");
                check(second.get(2, TimeUnit.SECONDS).getInt("param") == 70, "second result matched");
            } finally { worker.shutdownNow(); }
        }
    }
    static void cancelHandshake() throws Exception {
        CountDownLatch helloSeen = new CountDownLatch(1);
        try (Fake fake = new Fake((socket, in, out) -> {
            read(in); helloSeen.countDown(); while (in.read() != -1) { }
        }); YiCamera camera = fake.camera(3000, 4000)) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> connecting = worker.submit(() -> {
                    try { camera.connect(); throw new AssertionError("handshake should be cancelled"); }
                    catch (Exception expected) { }
                });
                check(helloSeen.await(1, TimeUnit.SECONDS), "handshake started");
                camera.close(); connecting.get(1, TimeUnit.SECONDS);
                check(!camera.isOpen(), "cancelled handshake must not publish a ready session");
            } finally { worker.shutdownNow(); }
        }
    }
    static void invalidToken() throws Exception {
        try (Fake fake = new Fake((socket, in, out) -> {
            read(in); send(out, "{\"msg_id\":257,\"rval\":0,\"param\":0}");
        }); YiCamera camera = fake.camera(1000, 1500)) {
            try { camera.connect(); throw new AssertionError("token zero must fail"); }
            catch (IOException expected) { }
            check(!camera.isOpen(), "invalid handshake must close transport");
        }
    }
    static SocketFactory testFactory(java.util.function.Supplier<Socket> create) {
        return new SocketFactory() {
            @Override public Socket createSocket() { return create.get(); }
            @Override public Socket createSocket(String host, int port) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress host, int port) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { throw new UnsupportedOperationException(); }
        };
    }
    static void externalIntentCancellation() throws Exception {
        AtomicInteger created = new AtomicInteger();
        SocketFactory factory = testFactory(() -> { created.incrementAndGet(); return new Socket(); });
        try (YiCamera camera = new YiCamera(factory, "127.0.0.1", 7878, 500, 700)) {
            camera.close(); // Cancellation happened after publication, before connect() started.
            try { camera.connect(() -> false); throw new AssertionError("cancelled intent should fail"); }
            catch (IOException expected) { }
            check(created.get() == 0 && !camera.isOpen(), "cancelled queued intent must not invoke socket factory");
        }
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(160);
            AtomicBoolean wanted = new AtomicBoolean(true);
            AtomicReference<Socket> createdSocket = new AtomicReference<>();
            SocketFactory cancelledFactory = testFactory(() -> {
                Socket socket = new Socket(); createdSocket.set(socket); wanted.set(false); return socket;
            });
            try (YiCamera camera = new YiCamera(cancelledFactory, "127.0.0.1", listener.getLocalPort(), 500, 700)) {
                try { camera.connect(wanted::get); throw new AssertionError("cancelled factory result should fail"); }
                catch (IOException expected) { }
                check(createdSocket.get().isClosed() && !camera.isOpen(), "cancelled newly created socket must be closed");
                try { listener.accept(); throw new AssertionError("cancelled connection must send no TCP traffic"); }
                catch (SocketTimeoutException expected) { }
            }
        }
        // A cancellation does not turn close() into a terminal state: the normal API remains reusable.
        try (Fake fake = new Fake((socket, in, out) -> hello(in, out));
             YiCamera camera = fake.camera(1000, 1500)) {
            camera.close();
            try { camera.connect(() -> false); throw new AssertionError("cancelled intent should fail"); }
            catch (IOException expected) { }
            camera.connect();
            check(camera.isOpen(), "a later wanted connection can still succeed");
        }
    }
    static void frameLimits() throws Exception {
        try { JsonFramer.readObject(new StringReader("x".repeat(5000) + "{}"), 262144); throw new AssertionError("noise bound expected"); }
        catch (IOException expected) { }
        try { JsonFramer.readObject(new StringReader("{" + "[".repeat(130) + "]".repeat(130) + "}"), 262144); throw new AssertionError("depth bound expected"); }
        catch (IOException expected) { }
        try { JsonFramer.readObject(new StringReader("{\"p\":\"" + "x".repeat(2000) + "\"}"), 1000); throw new AssertionError("frame bound expected"); }
        catch (IOException expected) { }
        try { JsonFramer.readObject(new StringReader("{}"), 1000, System.nanoTime() - 1); throw new AssertionError("expired deadline expected"); }
        catch (SocketTimeoutException expected) { }
    }
    public static void main(String[] args) throws Exception {
        run("fragmented UTF-8 handshake, events and negotiated token", YiCameraTransportTest::fragmentationAndEvents);
        run("explicit camera rejection preserves the live session", YiCameraTransportTest::rejectionRetainsSession);
        run("unsolicited events cannot extend a mutation deadline or trigger replay", YiCameraTransportTest::eventStormDeadline);
        run("slow partial JSON cannot extend the command deadline", YiCameraTransportTest::partialDripDeadline);
        run("concurrent close promptly unblocks a pending command", YiCameraTransportTest::closeUnblocksRequest);
        run("event-only photo acknowledgement and longer capture timeout remain supported", YiCameraTransportTest::eventPhotoAck);
        run("EOF, malformed replies and invalid UTF-8 invalidate the session", YiCameraTransportTest::eofAndMalformed);
        run("simultaneous commands stay serialized and matched", YiCameraTransportTest::serialization);
        run("cancelled handshake cannot resurrect the connection", YiCameraTransportTest::cancelHandshake);
        run("nonpositive handshake token is rejected", YiCameraTransportTest::invalidToken);
        run("cancelled external intent opens no socket and does not prevent later reuse", YiCameraTransportTest::externalIntentCancellation);
        run("preamble, nesting, frame and time limits remain bounded", YiCameraTransportTest::frameLimits);
        System.out.println("PASS: " + passed + " production transport test groups");
    }
}
