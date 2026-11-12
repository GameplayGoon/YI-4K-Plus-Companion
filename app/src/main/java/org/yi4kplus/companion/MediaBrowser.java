package org.yi4kplus.companion;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Network;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Browses the camera's local HTTP file service (read-only). */
public final class MediaBrowser {
    private final Supplier<Network> wifiSupplier;
    private final String host;
    private static final int MAX_RESULTS = 350;
    private volatile ReliableHttpTransfer activeTransfer;
    private volatile boolean transferCancelled;

    public MediaBrowser(Network wifi, String host) {
        this(() -> wifi, host);
    }

    /** Resolves the latest camera Wi-Fi handle for every retry after Android reconnects it. */
    public MediaBrowser(Supplier<Network> wifiSupplier, String host) {
        if (wifiSupplier == null || host == null) throw new IllegalArgumentException("Camera network and host are required");
        this.wifiSupplier = wifiSupplier;
        this.host = host;
    }

    public static final class MediaFile {
        public final URI location;
        public final String name;
        MediaFile(URI location) {
            this.location = location;
            String p = location.getPath();
            this.name = p.substring(p.lastIndexOf('/') + 1);
        }
    }

    private HttpURLConnection open(URI uri) throws IOException {
        if (!host.equalsIgnoreCase(uri.getHost()) || !"http".equals(uri.getScheme()) ||
            uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 ||
            uri.getPath() == null || !uri.getPath().startsWith("/DCIM/") ||
            uri.getPath().indexOf('\\') >= 0 || uri.getPath().indexOf('%') >= 0 ||
            !uri.normalize().equals(uri)) {
            throw new IOException("Blocked unexpected file URL");
        }
        for (String segment : uri.getPath().split("/")) {
            if (".".equals(segment) || "..".equals(segment)) throw new IOException("Blocked unsafe file path");
            for (int i = 0; i < segment.length(); i++)
                if (segment.charAt(i) < 32 || segment.charAt(i) == 127) throw new IOException("Blocked unsafe file path");
        }
        Network wifi = wifiSupplier.get();
        if (wifi == null) throw new IOException("Camera Wi-Fi is reconnecting");
        HttpURLConnection connection = (HttpURLConnection) wifi.openConnection(uri.toURL());
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(12000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "YI4KPlus-Companion/0.10");
        return connection;
    }

    /** Allow image/THM requests only within the same camera's DCIM directory. */
    HttpURLConnection openThumbnail(URI uri) throws IOException { return open(uri); }

    public List<MediaFile> browse() throws IOException {
        long deadline = System.nanoTime() + 25_000_000_000L;
        LinkedHashSet<URI> found = new LinkedHashSet<>();
        ArrayList<URI> seeds = new ArrayList<>();
        seeds.add(URI.create("http://" + host + "/DCIM/"));
        seeds.add(URI.create("http://" + host + "/DCIM/100MEDIA/"));
        seeds.add(URI.create("http://" + host + "/DCIM/101MEDIA/"));
        seeds.add(URI.create("http://" + host + "/DCIM/102MEDIA/"));
        ArrayList<String> errors = new ArrayList<>();
        int[] reachableDirectories = {0};
        boolean[] budgetExceeded = {false};
        LinkedHashSet<URI> visited = new LinkedHashSet<>();
        for (URI seed : seeds) {
            walk(seed, 0, visited, found, errors, reachableDirectories, deadline, budgetExceeded);
            if (!found.isEmpty() || visited.size() >= 18 || budgetExceeded[0]) break;
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Gallery scan cancelled");
        if (budgetExceeded[0]) {
            if (found.isEmpty()) throw new IOException("Camera gallery scan timed out; reconnect and refresh");
            DebugLog.warn("Gallery scan reached its time budget; returning " + found.size() + " media files found so far");
        }
        if (found.isEmpty() && !errors.isEmpty()) throw new IOException(
            "Camera gallery scan did not complete; refresh when the Wi-Fi link settles. " + errors.get(0));
        if (found.isEmpty() && reachableDirectories[0] > 0) return new ArrayList<>();
        if (found.isEmpty()) throw new IOException("Camera HTTP gallery is not reachable. " +
            (errors.isEmpty() ? "No directory listing was accessible." : errors.get(0)));
        ArrayList<MediaFile> result = new ArrayList<>();
        for (URI uri : found) result.add(new MediaFile(uri));
        result.sort((a, b) -> b.name.compareToIgnoreCase(a.name));
        return result;
    }

    private void walk(URI directory, int depth, LinkedHashSet<URI> visited,
                      LinkedHashSet<URI> found, List<String> errors, int[] reachableDirectories,
                      long deadline, boolean[] budgetExceeded) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            budgetExceeded[0] = true;
            return;
        }
        if (depth >= 4 || visited.size() >= 18 || found.size() >= MAX_RESULTS ||
            !visited.add(directory)) return;
        HttpURLConnection c = null;
        try {
            c = open(directory);
            int remaining = (int) Math.max(1L, Math.min(6000L, (deadline - System.nanoTime()) / 1_000_000L));
            c.setConnectTimeout(Math.min(4000, remaining));
            c.setReadTimeout(Math.min(5000, remaining));
            if (c.getResponseCode() != 200) return;
            if (c.getContentLengthLong() > 2_000_000) return;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                        budgetExceeded[0] = true;
                        throw new InterruptedIOException("Gallery scan time budget reached");
                    }
                    if (text.size() + read > 2_000_000) throw new IOException("Directory listing too large");
                    text.write(buf, 0, read);
                }
                reachableDirectories[0]++;
                for (URI link : HtmlIndex.entries(directory, text.toString("UTF-8"))) {
                    if (found.size() >= MAX_RESULTS) break;
                    if (HtmlIndex.isMedia(link)) found.add(link);
                    else if (link.toString().endsWith("/"))
                        walk(link, depth + 1, visited, found, errors, reachableDirectories, deadline, budgetExceeded);
                    if (budgetExceeded[0]) break;
                }
            }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) budgetExceeded[0] = true;
            if (errors.size() < 3) errors.add(e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Retrieve just HTTP headers; never download a movie to determine its file size. */
    public long contentLength(MediaFile file) {
        HttpURLConnection connection = null;
        try {
            connection = open(file.location);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2500);
            int response = connection.getResponseCode();
            if (response == 200 || response == 206) {
                long length = connection.getContentLengthLong();
                if (length > 0) return length;
            }
        } catch (Exception ignored) {
            // Older YI HTTP servers sometimes do not implement HEAD.
        } finally {
            if (connection != null) connection.disconnect();
        }
        // A range request can supply Content-Range without transferring file bytes.
        // Only inspect response headers; immediately close, even if server ignores Range.
        try {
            connection = open(file.location);
            connection.setRequestProperty("Range", "bytes=0-0");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2500);
            int code = connection.getResponseCode();
            if (code == 206) {
                String range = connection.getHeaderField("Content-Range");
                if (range != null && range.matches("(?i)bytes\\s+\\d+-\\d+/\\d+")) {
                    long bytes = Long.parseLong(range.substring(range.lastIndexOf('/')+1));
                    return bytes > 0 ? bytes : -1;
                }
            }
            if (code == 200) {
                long bytes = connection.getContentLengthLong();
                return bytes > 0 ? bytes : -1;
            }
        } catch (Exception ignored) {
            // If even headers are unavailable, show 'Size unavailable'.
        } finally {
            if (connection != null) connection.disconnect();
        }
        return -1;
    }

    public interface Progress { void onUpdate(long copied, long expected); }

    public static final class DownloadResult {
        public final Uri uri;
        public final String savedPath;
        public DownloadResult(Uri uri, String savedPath) { this.uri = uri; this.savedPath = savedPath; }
    }

    interface TransferVerifier { void verify() throws Exception; }

    /** Cancel a queued or active transfer without waiting on the Android UI thread. */
    public void cancelTransfers() {
        transferCancelled = true;
        ReliableHttpTransfer transfer = activeTransfer;
        if (transfer != null) transfer.cancel();
    }

    private void requireTransferAllowed(java.util.function.BooleanSupplier transferAllowed) throws IOException {
        if (transferCancelled || Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled");
        if (!transferAllowed.getAsBoolean()) throw new IOException("Camera is no longer ready to copy this file; incomplete copy discarded");
    }

    private long finalLength(MediaFile file, java.util.function.BooleanSupplier transferAllowed) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            requireTransferAllowed(transferAllowed);
            long length = contentLength(file);
            if (length >= 0) return length;
            if (attempt < 2) {
                try { Thread.sleep(350L * (attempt + 1)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download cancelled while checking the original", interrupted);
                }
            }
        }
        throw new IOException("Camera did not provide a file size to verify the completed copy; try again after it finishes saving");
    }

    public DownloadResult download(Context context, MediaFile file, Progress progress,
                                   java.util.function.BooleanSupplier transferAllowed,
                                   TransferVerifier finalVideoCheck) throws Exception {
        if (!HtmlIndex.isMedia(file.location)) throw new IOException("Unsupported media format");
        requireTransferAllowed(transferAllowed);
        Uri destination = null;
        ContentResolver resolver = context.getContentResolver();
        File staging = null;
        try {
            String name = file.name;
            String lower = name.toLowerCase(Locale.ROOT);
            String mime = lower.endsWith(".mp4") ? "video/mp4" :
                lower.endsWith(".mov") ? "video/quicktime" : lower.endsWith(".png") ? "image/png" : "image/jpeg";
            boolean isVideo = mime.startsWith("video/");
            File folderCache = new File(context.getCacheDir(), "camera-transfers");
            if (!folderCache.isDirectory() && !folderCache.mkdirs()) throw new IOException("Cannot prepare private transfer storage");
            File[] leftovers = folderCache.listFiles();
            if (leftovers != null) for (File partial : leftovers) {
                if (partial.getName().startsWith("yi-transfer-") && partial.lastModified() < System.currentTimeMillis() - 24L * 60 * 60 * 1000)
                    partial.delete();
            }
            staging = File.createTempFile("yi-transfer-", ".part", folderCache);
            long before = contentLength(file);
            requireTransferAllowed(transferAllowed);
            ReliableHttpTransfer transfer = new ReliableHttpTransfer();
            activeTransfer = transfer;
            ReliableHttpTransfer.Result received = transfer.transfer(file.location, staging, before, uri -> {
                HttpURLConnection connection = open(uri);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(20000);
                return connection;
            }, () -> transferCancelled || !transferAllowed.getAsBoolean(), (copied, total) -> {
                // 100% means Android has published the verified copy, not merely that the last
                // HTTP body byte arrived. Keep the final update until all checks below pass.
                if (progress != null && (total < 0 || copied < total)) progress.onUpdate(copied, total);
            }, DebugLog::info);
            requireTransferAllowed(transferAllowed);
            if (received.bytes <= 0) throw new IOException("Camera returned an empty media file");
            if (!received.lengthVerified && finalLength(file, transferAllowed) != received.bytes)
                throw new IOException("Camera file length does not match the received copy");
            long available = folderCache.getUsableSpace();
            if (available > 0 && (available < 16L * 1024 * 1024 || received.bytes > available - 16L * 1024 * 1024))
                throw new IOException("Not enough free phone storage to save the verified original");

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            String folder = isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/YI4KPlus/");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            destination = resolver.insert(isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI :
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IOException("Android denied file save");
            try (InputStream in = new FileInputStream(staging); OutputStream out = resolver.openOutputStream(destination, "w")) {
                if (out == null) throw new IOException("Cannot open file destination");
                byte[] buf = new byte[65536];
                long copied = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    requireTransferAllowed(transferAllowed);
                    out.write(buf, 0, n);
                    copied += n;
                }
                out.flush();
                if (copied != received.bytes) throw new IOException("Private transfer copy became incomplete");
                requireTransferAllowed(transferAllowed);
            }
            // Camera HTTP can serve an MP4 before its recording is finished. Compare a fresh
            // HEAD size against the copied bytes BEFORE making the Android MediaStore file visible.
            // This protects against files growing during transfer; it is not a firmware flush guarantee.
            if (isVideo) {
                long liveSize = finalLength(file, transferAllowed);
                if (liveSize <= 0 || liveSize != received.bytes) throw new IOException(
                    "Camera video changed during download (before=" + received.bytes + ", after=" + liveSize + "); incomplete copy discarded");
                requireTransferAllowed(transferAllowed);
                // Query the actual camera state on the control channel before publishing.
                // This avoids depending solely on the last periodic UI recording update.
                finalVideoCheck.verify();
            }
            requireTransferAllowed(transferAllowed);
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(destination, complete, null, null) < 1)
                throw new IOException("Android could not publish the verified downloaded file");
            if (progress != null) try { progress.onUpdate(received.bytes, received.bytes); }
                catch (RuntimeException observerFailure) { DebugLog.warn("Download progress view is no longer available"); }
            return new DownloadResult(destination, (mime.startsWith("video/") ? "Movies" : "Pictures") + "/YI4KPlus/" + name);
        } catch (Exception e) {
            if (destination != null) {
                try { resolver.delete(destination, null, null); }
                catch (Exception cleanup) {
                    e.addSuppressed(cleanup);
                    DebugLog.warn("Incomplete Android copy could not be removed: " + cleanup.getClass().getSimpleName());
                }
            }
            throw e;
        } finally {
            activeTransfer = null;
            if (staging != null && staging.exists() && !staging.delete())
                DebugLog.warn("Private partial transfer will be cleaned on a later session");
        }
    }
}
