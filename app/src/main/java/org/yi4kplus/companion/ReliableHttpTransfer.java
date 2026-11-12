package org.yi4kplus.companion;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded retries for a read-only HTTP download into a private, seekable staging file. */
public final class ReliableHttpTransfer {
    public interface ConnectionFactory { HttpURLConnection open(URI uri) throws IOException; }
    public interface Cancellation { boolean cancelled(); }
    public interface Progress { void update(long copied, long expected); }
    public interface Events { void record(String message); }

    public static final class Result {
        public final long bytes;
        public final long expected;
        public final boolean lengthVerified;
        Result(long bytes, long expected) {
            this.bytes = bytes;
            this.expected = expected;
            this.lengthVerified = expected >= 0 && bytes == expected;
        }
    }

    static final class FatalTransferException extends IOException {
        FatalTransferException(String message) { super(message); }
    }

    static final class ByteRange {
        private static final Pattern FORMAT = Pattern.compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+)$");
        final long first, last, total;
        private ByteRange(long first, long last, long total) {
            this.first = first; this.last = last; this.total = total;
        }
        static ByteRange parse(String value) throws IOException {
            Matcher matcher = FORMAT.matcher(value == null ? "" : value.trim());
            if (!matcher.matches()) throw new FatalTransferException("Camera returned an invalid Content-Range");
            try {
                long first = Long.parseLong(matcher.group(1));
                long last = Long.parseLong(matcher.group(2));
                long total = Long.parseLong(matcher.group(3));
                if (first < 0 || last < first || total <= last) throw new NumberFormatException();
                return new ByteRange(first, last, total);
            } catch (NumberFormatException invalid) {
                throw new FatalTransferException("Camera returned an invalid byte range size");
            }
        }
    }

    private static final long FREE_SPACE_RESERVE = 16L * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 4;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final long retryDelayMillis;
    private volatile HttpURLConnection active;
    private volatile Thread owner;

    public ReliableHttpTransfer() { this(1500); }

    /** Shorter delay is useful for deterministic local HTTP regression tests. */
    public ReliableHttpTransfer(long retryDelayMillis) {
        if (retryDelayMillis < 0 || retryDelayMillis > 2000) throw new IllegalArgumentException("Invalid retry delay");
        this.retryDelayMillis = retryDelayMillis;
    }

    /** Cancellation never waits for a blocked read while called from the Android UI thread. */
    public void cancel() {
        cancelled.set(true);
        Thread worker = owner;
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        final HttpURLConnection connection = active;
        if (connection != null) {
            Thread closer = new Thread(() -> connection.disconnect(), "yi-download-cancel");
            closer.setDaemon(true);
            closer.start();
        }
    }

    public Result transfer(URI uri, File destination, long expectedHint, ConnectionFactory factory,
                           Cancellation cancellation, Progress progress, Events events) throws IOException {
        if (uri == null || destination == null || factory == null) throw new IllegalArgumentException("Transfer arguments are required");
        if (!running.compareAndSet(false, true)) throw new IOException("A transfer is already running");
        // HttpURLConnection.disconnect() can wait behind its input stream's read lock on some
        // Android/JDK versions. Supervise the bounded network worker rather than making Cancel
        // wait for that lock. The worker owns its staging handle and never writes after it sees
        // cancellation; the caller never publishes a cancelled result.
        FutureTask<Result> task = new FutureTask<>(() -> executeTransfer(uri, destination,
                expectedHint, factory, cancellation, progress, events));
        Thread worker = new Thread(task, "yi-media-transfer");
        worker.setDaemon(true);
        owner = worker;
        worker.start();
        try {
            while (true) {
                checkCancelled(cancellation);
                try { return task.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException pending) { /* Check cancellation without shortening Wi-Fi read tolerance. */ }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Download cancelled while waiting for camera data");
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof IOException) throw (IOException) cause;
                    throw new IOException("Media transfer failed", cause);
                }
            }
        } catch (InterruptedIOException interrupted) {
            cancel();
            task.cancel(true);
            throw interrupted;
        }
    }

    private Result executeTransfer(URI uri, File destination, long expectedHint, ConnectionFactory factory,
                                   Cancellation cancellation, Progress progress, Events events) throws IOException {
        long expected = expectedHint >= 0 ? expectedHint : -1;
        String validator = null;
        String validatorHeader = null;
        IOException lastFailure = null;
        long lastProgress = 0;
        try (RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
            output.setLength(0);
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                checkCancelled(cancellation);
                long offset = output.length();
                // A partial body without an entity validator cannot safely be combined with a
                // later response. Likewise a failed final read is verified again from byte zero.
                if (offset > 0 && (validator == null || expected < 0 || offset >= expected)) {
                    event(events, "Restarting download from byte zero; safe resume is unavailable");
                    output.setLength(0);
                    offset = 0;
                }
                HttpURLConnection connection = null;
                try {
                    connection = factory.open(uri);
                    active = connection;
                    checkCancelled(cancellation);
                    connection.setRequestMethod("GET");
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    connection.setRequestProperty("Connection", "close");
                    if (connection.getConnectTimeout() == 0) connection.setConnectTimeout(8000);
                    if (connection.getReadTimeout() == 0) connection.setReadTimeout(20000);
                    if (offset > 0) {
                        connection.setRequestProperty("Range", "bytes=" + offset + "-");
                        connection.setRequestProperty("If-Range", validator);
                        event(events, "Resuming verified download at byte " + offset + " (attempt " + (attempt + 1) + ")");
                    }
                    int code = connection.getResponseCode();
                    checkCancelled(cancellation);
                    if (code != 200 && code != 206) {
                        if (code == 408 || code == 429 || code >= 500 && code <= 599)
                            throw new IOException("Camera HTTP service is temporarily unavailable (HTTP " + code + ")");
                        throw new FatalTransferException("Camera download returned HTTP " + code);
                    }
                    String encoding = connection.getHeaderField("Content-Encoding");
                    if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding))
                        throw new FatalTransferException("Camera returned an unexpected encoded media body");
                    String type = connection.getContentType();
                    if (type != null && (type.toLowerCase(java.util.Locale.ROOT).contains("text/html")
                            || type.toLowerCase(java.util.Locale.ROOT).contains("application/json")))
                        throw new FatalTransferException("Camera returned a page instead of the requested media file");

                    String currentValidator = validatorHeader == null ? null : connection.getHeaderField(validatorHeader);
                    if (validator != null && currentValidator != null && !validator.equals(currentValidator))
                        throw new FatalTransferException("Camera file changed during transfer; refresh the gallery and try again");
                    if (offset > 0 && code == 206 && currentValidator == null) {
                        output.setLength(0);
                        throw new IOException("Camera omitted the resume validator; restarting the whole file");
                    }
                    if (validator == null) {
                        String etag = connection.getHeaderField("ETag");
                        String modified = connection.getHeaderField("Last-Modified");
                        if (etag != null && !etag.trim().isEmpty() && !etag.trim().startsWith("W/")) {
                            validator = etag; validatorHeader = "ETag";
                        } else if (modified != null && !modified.trim().isEmpty()) {
                            validator = modified; validatorHeader = "Last-Modified";
                        }
                    }
                    long bodyLength = connection.getContentLengthLong();
                    long responseTotal;
                    long responseEnd;
                    if (code == 206) {
                        ByteRange range = ByteRange.parse(connection.getHeaderField("Content-Range"));
                        if (range.first != offset) throw new FatalTransferException("Camera returned the wrong resume position");
                        if (bodyLength >= 0 && bodyLength != range.last - range.first + 1)
                            throw new FatalTransferException("Camera returned inconsistent range headers");
                        responseTotal = range.total;
                        responseEnd = range.last + 1;
                    } else {
                        if (offset > 0) {
                            // Some old camera HTTP servers ignore Range. Never append a whole
                            // response to an existing prefix: replace the partial file instead.
                            output.setLength(0);
                            offset = 0;
                            event(events, "Camera ignored Range; restarting the complete file safely");
                        }
                        responseTotal = bodyLength;
                        responseEnd = bodyLength;
                    }
                    if (expected >= 0 && responseTotal >= 0 && expected != responseTotal)
                        throw new FatalTransferException("Camera file size changed during download");
                    if (expected < 0 && responseTotal >= 0) expected = responseTotal;
                    ensureSpace(destination, expected < 0 ? -1 : expected - offset);
                    output.seek(offset);
                    long copied = offset;
                    try (InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[128 * 1024];
                        while (true) {
                            checkCancelled(cancellation);
                            int count = input.read(buffer);
                            checkCancelled(cancellation);
                            if (count < 0) break;
                            if (count == 0) continue;
                            if (copied > Long.MAX_VALUE - count)
                                throw new FatalTransferException("Camera file exceeds the supported size");
                            if (expected >= 0 && count > expected - copied
                                    || responseEnd >= 0 && count > responseEnd - copied)
                                throw new FatalTransferException("Camera sent more bytes than the expected file size");
                            output.write(buffer, 0, count);
                            copied += count;
                            long now = System.nanoTime();
                            // Do not display 100% before EOF, flushing and all size checks pass.
                            if (progress != null && (expected < 0 || copied < expected)
                                    && now - lastProgress >= 200000000L) {
                                progress.update(copied, expected);
                                lastProgress = now;
                            }
                            if ((copied & 0x3fffff) < count) ensureSpace(destination, 0);
                        }
                    }
                    checkCancelled(cancellation);
                    if (responseEnd >= 0 && copied != responseEnd)
                        throw new IOException("Interrupted media body: received " + copied + " of " + responseEnd + " bytes");
                    if (expected >= 0 && copied != expected)
                        throw new IOException("Incomplete media transfer: received " + copied + " of " + expected + " bytes");
                    output.getFD().sync();
                    checkCancelled(cancellation);
                    if (progress != null) progress.update(copied, expected);
                    event(events, "Media body received: " + copied + " bytes" + (expected >= 0 ? " (length verified)" : " (no declared length)"));
                    return new Result(copied, expected);
                } catch (IOException failure) {
                    checkCancelled(cancellation);
                    if (failure instanceof FatalTransferException) throw failure;
                    lastFailure = failure;
                    if (attempt + 1 >= MAX_ATTEMPTS) break;
                    event(events, "Read-only download interrupted; retry " + (attempt + 2) + "/" + MAX_ATTEMPTS
                            + ": " + failure.getClass().getSimpleName());
                } finally {
                    active = null;
                    if (connection != null) connection.disconnect();
                }
                pause(Math.min(4000L, retryDelayMillis * (attempt + 1)), cancellation);
            }
            throw new IOException("Download interrupted after " + MAX_ATTEMPTS + " attempts; no incomplete media was published", lastFailure);
        } finally {
            owner = null;
            active = null;
            running.set(false);
            // A cancelled caller may already have removed this unique staging path while the
            // network read was unwinding. Remove a late-created private partial as well.
            if (cancelled.get()) destination.delete();
        }
    }

    private void checkCancelled(Cancellation cancellation) throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()
                || cancellation != null && cancellation.cancelled())
            throw new InterruptedIOException("Download cancelled or camera is no longer ready");
    }

    private void pause(long millis, Cancellation cancellation) throws IOException {
        long deadline = System.nanoTime() + millis * 1000000L;
        while (true) {
            checkCancelled(cancellation);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            try { Thread.sleep(Math.min(100L, Math.max(1L, remaining / 1000000L))); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Download cancelled during retry");
            }
        }
    }

    private static void ensureSpace(File file, long remaining) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        long available = parent == null ? 0 : parent.getUsableSpace();
        if (available > 0 && (available < FREE_SPACE_RESERVE
                || remaining > 0 && remaining > available - FREE_SPACE_RESERVE))
            throw new FatalTransferException("Not enough free phone storage to stage the original media file");
    }

    private static void event(Events events, String message) {
        if (events != null) try { events.record(message); } catch (RuntimeException ignored) { }
    }
}
