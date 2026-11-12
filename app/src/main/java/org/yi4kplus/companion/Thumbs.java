package org.yi4kplus.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded low-resolution HTTP thumbnails; never sends write/delete requests. */
public final class Thumbs {
    private static final int MAX_IMAGE_BYTES = 14 * 1024 * 1024;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    public synchronized Bitmap cached(MediaBrowser.MediaFile file) {
        return cache.get(file.location.toString());
    }

    public Bitmap load(MediaBrowser browser, MediaBrowser.MediaFile file, int desiredPixels) throws Exception {
        checkInterrupted();
        Bitmap existing = cached(file);
        if (existing != null) return existing;
        String low = file.name.toLowerCase(Locale.ROOT);
        List<URI> candidates = new ArrayList<>();
        if (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")) {
            candidates.addAll(ThumbnailUrls.photoCandidates(file.location));
        } else if (low.endsWith(".mp4") || low.endsWith(".mov")) {
            String name = file.location.toString();
            int dot = name.lastIndexOf('.');
            String base = name.substring(0, dot);
            candidates.add(URI.create(base + ".THM"));
            candidates.add(URI.create(base + ".thm"));
            candidates.add(URI.create(base + ".JPG"));
            candidates.add(URI.create(base + "_THM.JPG"));
        }
        for (URI candidate : candidates) {
            checkInterrupted();
            try {
                int limit = MAX_IMAGE_BYTES;
                if (!candidate.equals(file.location) && candidate.getRawQuery() != null) {
                    // If old firmware ignores the resize option, skip a large
                    // original response and use the explicit original fallback.
                    limit = candidate.getRawQuery().toLowerCase(Locale.ROOT).contains("type=thumb")
                        ? 2 * 1024 * 1024 : 4 * 1024 * 1024;
                }
                Bitmap bitmap = readImage(browser, candidate, desiredPixels, limit);
                if (bitmap != null) {
                    synchronized (this) { cache.put(file.location.toString(), bitmap); }
                    return bitmap;
                }
            } catch (IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
                /* Some firmware does not expose resized images or THM thumbnails. */
            }
        }
        return null;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Thumbnail request cancelled");
    }

    private static Bitmap readImage(MediaBrowser browser, URI uri, int target, int maxBytes) throws Exception {
        checkInterrupted();
        target = Math.max(32, Math.min(2048, target));
        HttpURLConnection conn = browser.openThumbnail(uri);
        try {
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) return null;
            String mime = conn.getContentType();
            if (mime != null && (mime.startsWith("text/") || mime.startsWith("video/") || mime.contains("json"))) return null;
            if (conn.getContentLengthLong() > maxBytes) return null;
            byte[] data;
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] block = new byte[32 * 1024];
                int n;
                while ((n = in.read(block)) != -1) {
                    checkInterrupted();
                    if (out.size() + n > maxBytes) throw new IOException("Image too large for preview");
                    out.write(block, 0, n);
                }
                data = out.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            checkInterrupted();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > target * 2) sample *= 2;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } finally { conn.disconnect(); }
    }
}
