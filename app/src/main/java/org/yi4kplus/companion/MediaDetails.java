package org.yi4kplus.companion;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.net.HttpURLConnection;
import java.util.Locale;

/** Best-effort metadata. Never stream entire remote videos just to calculate duration. */
final class MediaDetails {
    private MediaDetails() {}

    static long remoteSize(MediaBrowser browser, MediaBrowser.MediaFile item) {
        return browser.contentLength(item);
    }

    static String local(Context context, Uri uri, boolean video) {
        long bytes = -1;
        long duration = -1;
        String[] cols = {OpenableColumns.SIZE};
        try (Cursor c = context.getContentResolver().query(uri, cols, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) bytes = c.getLong(0);
        } catch (Exception ignored) {}
        if (video) {
            try (Cursor c = context.getContentResolver().query(uri,
                    new String[]{MediaStore.Video.VideoColumns.DURATION}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) duration = c.getLong(0);
            } catch (Exception ignored) {}
            if (duration <= 0) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(context, uri);
                    String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (raw != null) duration = Long.parseLong(raw);
                } catch (Exception ignored) {}
                finally { try { retriever.release(); } catch (Exception ignored) {} }
            }
        }
        return combine(bytes, duration, video);
    }

    static String combine(long bytes, long milliseconds, boolean video) {
        String size = bytes < 0 ? "Size unavailable" : StorageStats.readable(bytes);
        if (!video || milliseconds <= 0) return size;
        long seconds = milliseconds / 1000;
        String duration = seconds >= 3600
            ? String.format(Locale.US,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
            : String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);
        return size + "  ·  " + duration;
    }
}
