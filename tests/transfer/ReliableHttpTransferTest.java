package org.yi4kplus.companion;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real local HTTP fixtures: no camera or Android runtime is required. */
public final class ReliableHttpTransferTest {
    private static final byte[] DATA = pattern(131083);
    private static final int CUT = 17011;
    private static int tests;
    private static Path temp;

    public static void main(String[] args) throws Exception {
        temp = Files.createTempDirectory(new File(System.getProperty("yi.test.tmp", "build")).toPath(), "http-transfer-");
        try {
            truncationResumesAtExactOffset();
            ignoredRangeRestartsInsteadOfAppending();
            changedValidatorFails();
            malformedRangeFails();
            changedTotalFails();
            missingValidatorRestarts();
            lastModifiedSupportsSafeResume();
            largeRangeRetainsLongOffsets();
            transientStatusRetries();
            permanentStatusDoesNotRetry();
            redirectsAreNotFollowed();
            htmlBodyIsNotPublishedAsFootage();
            unknownLengthRemainsExplicitlyUnverified();
            cancellationClosesAnActiveRead();
            cameraStateCancellationReturnsPromptly();
            lateReadFailureCannotReportCompletion();
            System.out.println("PASS: " + tests + " reliable HTTP transfer integration tests");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        }
    }

    private static void truncationResumesAtExactOffset() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> ranges = new CopyOnWriteArrayList<>();
        try (Fixture server = new Fixture(exchange -> {
            int call = calls.incrementAndGet();
            ranges.add(exchange.getRequestHeaders().getFirst("Range"));
            if (call == 1) respond(exchange, 200, DATA, 0, CUT, DATA.length, "\"stable\"", null);
            else {
                require(("bytes=" + CUT + "-").equals(exchange.getRequestHeaders().getFirst("Range")), "resume uses exact byte offset");
                require("\"stable\"".equals(exchange.getRequestHeaders().getFirst("If-Range")), "resume pins the original entity");
                respond(exchange, 206, DATA, CUT, DATA.length - CUT, DATA.length - CUT, "\"stable\"", range(CUT, DATA.length));
            }
        })) {
            File target = target("resume");
            ReliableHttpTransfer.Result result = transfer(server, target, DATA.length, null, null);
            require(calls.get() == 2, "truncation needs only one safe retry");
            require(ranges.get(0) == null, "first GET requests the entire original");
            checkResult(result, target, DATA, true);
            pass("truncated GET resumes with validated Range and If-Range");
        }
    }

    private static void ignoredRangeRestartsInsteadOfAppending() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) respond(exchange, 200, DATA, 0, CUT, DATA.length, "\"stable\"", null);
            else respond(exchange, 200, DATA, 0, DATA.length, DATA.length, "\"stable\"", null);
        })) {
            File target = target("ignored-range");
            checkResult(transfer(server, target, DATA.length, null, null), target, DATA, true);
            require(calls.get() == 2, "ignored Range restarts with the 200 body");
            pass("server ignoring Range restarts output without corrupt appending");
        }
    }

    private static void changedValidatorFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> {
            if (calls.incrementAndGet() == 1) respond(exchange, 200, DATA, 0, CUT, DATA.length, "\"before\"", null);
            else respond(exchange, 206, DATA, CUT, DATA.length - CUT, DATA.length - CUT, "\"after\"", range(CUT, DATA.length));
        })) {
            expectFailure(() -> transfer(server, target("changed-validator"), DATA.length, null, null));
            pass("changed entity validator cannot splice two camera files");
        }
    }

    private static void malformedRangeFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> {
            if (calls.incrementAndGet() == 1) respond(exchange, 200, DATA, 0, CUT, DATA.length, "\"same\"", null);
            else respond(exchange, 206, DATA, CUT, DATA.length - CUT, DATA.length - CUT, "\"same\"", range(CUT + 1, DATA.length));
        })) {
            expectFailure(() -> transfer(server, target("bad-range"), DATA.length, null, null));
            pass("mismatched Content-Range start is rejected");
        }
    }

    private static void changedTotalFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> {
            if (calls.incrementAndGet() == 1) respond(exchange, 200, DATA, 0, CUT, DATA.length, "\"same\"", null);
            else respond(exchange, 206, DATA, CUT, DATA.length - CUT, DATA.length - CUT, "\"same\"",
                    "bytes " + CUT + "-" + (DATA.length - 1) + "/" + (DATA.length + 1));
        })) {
            expectFailure(() -> transfer(server, target("changed-total"), DATA.length, null, null));
            pass("changed source total size is rejected");
        }
    }

    private static void missingValidatorRestarts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> {
            int call = calls.incrementAndGet();
            require(exchange.getRequestHeaders().getFirst("Range") == null, "unvalidated partial is never resumed");
            if (call == 1) respond(exchange, 200, DATA, 0, CUT, DATA.length, null, null);
            else respond(exchange, 200, DATA, 0, DATA.length, DATA.length, null, null);
        })) {
            File target = target("no-validator");
            checkResult(transfer(server, target, DATA.length, null, null), target, DATA, true);
            require(calls.get() == 2, "missing validator permits a bounded full restart");
            pass("camera without validators restarts rather than unsafe resume");
        }
    }

    private static void lastModifiedSupportsSafeResume() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String modified = "Wed, 11 Nov 2026 12:00:00 GMT";
        try (Fixture server = new Fixture(exchange -> {
            exchange.getResponseHeaders().set("Last-Modified", modified);
            if (calls.incrementAndGet() == 1) respond(exchange, 200, DATA, 0, CUT, DATA.length, "W/\"weak\"", null);
            else {
                require(("bytes=" + CUT + "-").equals(exchange.getRequestHeaders().getFirst("Range")), "Last-Modified camera can resume");
                require(modified.equals(exchange.getRequestHeaders().getFirst("If-Range")), "weak ETag is not used as If-Range");
                respond(exchange, 206, DATA, CUT, DATA.length - CUT, DATA.length - CUT, "W/\"weak\"", range(CUT, DATA.length));
            }
        })) {
            File target = target("last-modified");
            checkResult(transfer(server, target, DATA.length, null, null), target, DATA, true);
            require(calls.get() == 2, "Last-Modified resume has one bounded retry");
            pass("older camera Last-Modified validator safely replaces a weak ETag");
        }
    }

    private static void largeRangeRetainsLongOffsets() throws Exception {
        long first = 5L * 1024 * 1024 * 1024 + 123;
        long total = first + 100000;
        ReliableHttpTransfer.ByteRange parsed = ReliableHttpTransfer.ByteRange.parse(range(first, total));
        require(parsed.first == first && parsed.last == total - 1 && parsed.total == total,
                "Content-Range uses long offsets above four GiB");
        expectFailure(() -> ReliableHttpTransfer.ByteRange.parse("bytes 0-9223372036854775807/9223372036854775808"));
        expectFailure(() -> ReliableHttpTransfer.ByteRange.parse("bytes 10-5/20"));
        expectFailure(() -> ReliableHttpTransfer.ByteRange.parse("bytes 0-20/20"));
        pass("large resume offsets parse without 32-bit overflow");
    }

    private static void transientStatusRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> {
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                respond(exchange, 503, new byte[0], 0, 0, -1, null, null);
            } else respond(exchange, 200, DATA, 0, DATA.length, DATA.length, "\"same\"", null);
        })) {
            File target = target("transient");
            checkResult(transfer(server, target, DATA.length, null, null), target, DATA, true);
            require(calls.get() == 2, "503 is retried once before success");
            pass("temporary camera HTTP failure retries a read-only GET");
        }
    }

    private static void permanentStatusDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 404, new byte[0], 0, 0, -1, null, null);
        })) {
            expectFailure(() -> transfer(server, target("permanent"), DATA.length, null, null));
            require(calls.get() == 1, "missing original is not retried");
            pass("permanent HTTP errors fail promptly");
        }
    }

    private static void redirectsAreNotFollowed() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/different-file");
            respond(exchange, 302, new byte[0], 0, 0, -1, null, null);
        })) {
            expectFailure(() -> transfer(server, target("redirect"), DATA.length, null, null));
            require(requests.get() == 1, "HTTP redirects are never followed by a camera download");
            pass("camera redirect cannot substitute a different download location");
        }
    }

    private static void htmlBodyIsNotPublishedAsFootage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture server = new Fixture(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, DATA.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(DATA); }
            catch (IOException expectedOnRejection) { }
        })) {
            expectFailure(() -> transfer(server, target("html-body"), DATA.length, null, null));
            require(requests.get() == 1, "camera listing page is a permanent media-body error");
            pass("HTML success page cannot masquerade as original footage");
        }
    }

    private static void unknownLengthRemainsExplicitlyUnverified() throws Exception {
        try (Fixture server = new Fixture(exchange -> respond(exchange, 200, DATA, 0, DATA.length, 0, null, null))) {
            File target = target("unknown-length");
            ReliableHttpTransfer.Result result = transfer(server, target, -1, null, null);
            checkResult(result, target, DATA, false);
            require(result.expected < 0, "unknown source length remains unknown");
            pass("chunked content copies through EOF without claiming length verification");
        }
    }

    private static void cancellationClosesAnActiveRead() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        CountDownLatch finishServer = new CountDownLatch(1);
        try (Fixture server = new Fixture(exchange -> {
            exchange.getResponseHeaders().set("ETag", "\"same\"");
            exchange.sendResponseHeaders(200, DATA.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(DATA, 0, 256);
                output.flush();
                sent.countDown();
                try { finishServer.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } catch (IOException expectedOnCancel) { }
        })) {
            ReliableHttpTransfer transfer = new ReliableHttpTransfer(1);
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { transfer.transfer(server.uri(), targetUnchecked("cancel"), DATA.length,
                        uri -> connection(uri, 10000), () -> false, null, null); }
                catch (Throwable failure) { result.set(failure); }
            }, "transfer-cancellation-test");
            worker.setDaemon(true);
            worker.start();
            require(sent.await(3, TimeUnit.SECONDS), "server started a body before cancellation");
            long start = System.nanoTime();
            Thread cancel = new Thread(transfer::cancel, "cancel-active-http");
            cancel.setDaemon(true);
            cancel.start();
            worker.join(2500);
            finishServer.countDown();
            require(!worker.isAlive(), "cancel unblocks a stalled HTTP read");
            require(result.get() instanceof IOException, "cancel returns an I/O failure, never a completed file");
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2600, "cancel does not wait for ten-second timeout");
            pass("cancellation closes an active stalled download promptly");
        } finally { finishServer.countDown(); }
    }

    private static void cameraStateCancellationReturnsPromptly() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        CountDownLatch finishServer = new CountDownLatch(1);
        AtomicBoolean recording = new AtomicBoolean();
        try (Fixture server = new Fixture(exchange -> {
            exchange.sendResponseHeaders(200, DATA.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(DATA, 0, 256);
                output.flush();
                sent.countDown();
                try { finishServer.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } catch (IOException expectedOnCancel) { }
        })) {
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { new ReliableHttpTransfer(1).transfer(server.uri(), target("camera-state-cancel"), DATA.length,
                        uri -> connection(uri, 10000), recording::get, null, null); }
                catch (Throwable failure) { result.set(failure); }
            }, "camera-state-cancellation-test");
            worker.setDaemon(true);
            worker.start();
            require(sent.await(3, TimeUnit.SECONDS), "camera stream was active before its state changed");
            recording.set(true);
            worker.join(2500);
            finishServer.countDown();
            require(!worker.isAlive(), "camera recording state cancels without waiting for a stalled read");
            require(result.get() instanceof IOException, "recording state cannot publish a completed transfer");
            pass("camera-state cancellation is polled independently of blocked network reads");
        } finally { finishServer.countDown(); }
    }

    private static void lateReadFailureCannotReportCompletion() throws Exception {
        List<long[]> progress = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ReliableHttpTransfer transfer = new ReliableHttpTransfer(1);
        expectFailure(() -> transfer.transfer(URI.create("http://127.0.0.1/DCIM/a.MP4"), target("late-read"), DATA.length,
                uri -> { calls.incrementAndGet(); return new LateFailureConnection(uri.toURL()); }, () -> false,
                (copied, expected) -> progress.add(new long[]{copied, expected}), null));
        for (long[] update : progress) require(update[1] < 0 || update[0] < update[1], "no 100% report precedes verified EOF");
        require(calls.get() <= 4, "late failures have a bounded retry budget");
        pass("late body errors never produce a completion progress callback");
    }

    private static ReliableHttpTransfer.Result transfer(Fixture server, File file, long hint,
            ReliableHttpTransfer.Cancellation cancel, ReliableHttpTransfer.Progress progress) throws IOException {
        return new ReliableHttpTransfer(1).transfer(server.uri(), file, hint,
                uri -> connection(uri, 500), cancel, progress, null);
    }

    private static HttpURLConnection connection(URI uri, int timeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(timeout);
        return connection;
    }

    private static void respond(HttpExchange exchange, int status, byte[] data, int offset, int count,
            long declaredLength, String etag, String contentRange) throws IOException {
        if (etag != null) exchange.getResponseHeaders().set("ETag", etag);
        if (contentRange != null) exchange.getResponseHeaders().set("Content-Range", contentRange);
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(status, declaredLength);
        try (OutputStream output = exchange.getResponseBody()) {
            if (count > 0) output.write(data, offset, count);
        } catch (IOException expectedForTruncatedFixture) {
            // A deliberately short fixed-length response is the test's fault injection.
        } finally { exchange.close(); }
    }

    private static String range(long start, long total) { return "bytes " + start + "-" + (total - 1) + "/" + total; }
    private static File target(String name) { return temp.resolve(name + ".part").toFile(); }
    private static File targetUnchecked(String name) { return target(name); }
    private static void checkResult(ReliableHttpTransfer.Result result, File target, byte[] expected, boolean verified) throws IOException {
        require(result.bytes == expected.length, "result retains exact long byte count");
        require(result.lengthVerified == verified, "length verification status is truthful");
        require(Arrays.equals(Files.readAllBytes(target.toPath()), expected), "saved bytes exactly match the original");
    }
    private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked work) throws Exception {
        try { work.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected an I/O failure");
    }
    private static byte[] pattern(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + i / 17);
        return bytes;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void pass(String label) { tests++; System.out.println("PASS: " + label); }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers;
        final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        Fixture(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            workers = Executors.newCachedThreadPool(work -> { Thread thread = new Thread(work, "http-fixture"); thread.setDaemon(true); return thread; });
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try { handler.handle(exchange); }
                catch (Throwable failure) { serverFailure.compareAndSet(null, failure); exchange.close(); }
            });
            server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/DCIM/100MEDIA/test.MP4"); }
        @Override public void close() {
            server.stop(0); workers.shutdownNow();
            Throwable failure = serverFailure.get();
            if (failure != null) throw new AssertionError("HTTP fixture assertion failed", failure);
        }
    }

    private static final class LateFailureConnection extends HttpURLConnection {
        LateFailureConnection(URL url) { super(url); }
        @Override public int getResponseCode() { return 200; }
        @Override public long getContentLengthLong() { return DATA.length; }
        @Override public String getHeaderField(String name) {
            if ("ETag".equalsIgnoreCase(name)) return "\"same\"";
            if ("Content-Length".equalsIgnoreCase(name)) return Integer.toString(DATA.length);
            if ("Content-Type".equalsIgnoreCase(name)) return "application/octet-stream";
            return null;
        }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                private final ByteArrayInputStream bytes = new ByteArrayInputStream(DATA);
                @Override public int read() throws IOException {
                    if (bytes.available() == 0) throw new IOException("Injected read failure after declared bytes");
                    return bytes.read();
                }
                @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                    if (bytes.available() == 0) throw new IOException("Injected read failure after declared bytes");
                    return bytes.read(buffer, offset, count);
                }
            };
        }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { connected = true; }
    }
}
