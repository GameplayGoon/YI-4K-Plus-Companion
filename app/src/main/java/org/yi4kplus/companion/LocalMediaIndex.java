package org.yi4kplus.companion;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Remembers the actual Android MediaStore URI, not just a filename. Only deletes our phone copy. */
public final class LocalMediaIndex {
    private static final String PREFS = "saved_camera_media_v1";
    private final Map<String, Uri> cache = new HashMap<>();
    private final Set<String> checked = new HashSet<>();

    private static String key(MediaBrowser.MediaFile cameraFile) {
        return "file_" + Integer.toHexString(cameraFile.location.toString().hashCode()) + "_" + cameraFile.name;
    }

    private static boolean exists(Context context, Uri uri) {
        boolean sizeReportedZero = false;
        // MediaStore permits an app to read its own IS_PENDING rows. Merely
        // opening the URI would therefore mistake a half-written transfer for a
        // finished download, including one left behind after process death.
        try (Cursor metadata = context.getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE}, null, null, null)) {
            if (metadata == null || !metadata.moveToFirst()) return false;
            int pending = metadata.getColumnIndex(MediaStore.MediaColumns.IS_PENDING);
            if (pending >= 0 && !metadata.isNull(pending) && metadata.getInt(pending) != 0) return false;
            int size = metadata.getColumnIndex(MediaStore.MediaColumns.SIZE);
            // Some providers update SIZE asynchronously. Confirm a reported
            // zero with the actual descriptor before rejecting a valid file.
            sizeReportedZero = size >= 0 && !metadata.isNull(size) && metadata.getLong(size) == 0;
        } catch (Exception unavailable) { return false; }
        try (android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) return false;
            long actualSize = fd.getStatSize();
            return actualSize > 0 || (actualSize < 0 && !sizeReportedZero);
        } catch (Exception e) { return false; }
    }

    public synchronized Uri find(Context context, MediaBrowser.MediaFile file) {
        String key = key(file);
        Uri cached = cache.get(key);
        if (cached != null && exists(context, cached)) return cached;
        cache.remove(key);
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null);
        if (stored != null) {
            Uri uri = Uri.parse(stored);
            if (exists(context, uri)) { cache.put(key, uri); return uri; }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply();
        }
        if (!checked.add(key)) return null;
        // Also discover files saved by previous versions of the companion, if owned by this app.
        ContentResolver resolver = context.getContentResolver();
        boolean video = file.name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4") ||
            file.name.toLowerCase(java.util.Locale.ROOT).endsWith(".mov");
        Uri[] collections = {video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Downloads.EXTERNAL_CONTENT_URI};
        String[] folders = {(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/YI4KPlus/",
            Environment.DIRECTORY_DOWNLOADS + "/YI4KPlus/"};
        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
            + MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.IS_PENDING + "=0";
        for (int i = 0; i < collections.length; i++) {
            try (Cursor cursor = resolver.query(collections[i], projection, selection,
                    new String[]{file.name, folders[i]}, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                while (cursor != null && cursor.moveToNext()) {
                    Uri uri = ContentUris.withAppendedId(collections[i], cursor.getLong(0));
                    if (exists(context, uri)) { remember(context, file, uri); return uri; }
                }
            } catch (Exception e) { DebugLog.warn("Local file lookup: " + e.getClass().getSimpleName()); }
        }
        return null;
    }

    public synchronized void remember(Context context, MediaBrowser.MediaFile file, Uri uri) {
        cache.put(key(file), uri);
        checked.add(key(file));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(file), uri.toString()).apply();
    }

    public static final class SavedItem {
        public final Uri uri;
        public final String name;
        public final String mime;
        public SavedItem(Uri uri, String name, String mime) {
            this.uri = uri; this.name = name; this.mime = mime;
        }
        public boolean isVideo() { return mime != null && mime.startsWith("video/"); }
    }

    /** Offline listing from app-owned Pictures, Movies, and older Downloads. */
    public ArrayList<SavedItem> listLocal(Context context) {
        ArrayList<SavedItem> result = new ArrayList<>();
        String[] columns = {MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE};
        Uri[] collections = {MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Downloads.EXTERNAL_CONTENT_URI};
        String[] folders = {Environment.DIRECTORY_PICTURES + "/YI4KPlus/",
            Environment.DIRECTORY_MOVIES + "/YI4KPlus/", Environment.DIRECTORY_DOWNLOADS + "/YI4KPlus/"};
        for (int i = 0; i < collections.length; i++) {
            try (Cursor cursor = context.getContentResolver().query(
                    collections[i], columns, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.IS_PENDING + "=0",
                    new String[]{folders[i]}, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                while (cursor != null && cursor.moveToNext() && result.size() < 200) {
                    String mime = cursor.getString(2);
                    if (mime == null || (!mime.startsWith("image/") && !mime.startsWith("video/"))) continue;
                    Uri uri = ContentUris.withAppendedId(collections[i], cursor.getLong(0));
                    if (!cursor.isNull(3) && cursor.getLong(3) == 0 && !exists(context, uri)) continue;
                    result.add(new SavedItem(uri, cursor.getString(1), mime));
                }
            } catch (Exception e) { DebugLog.warn("Offline media index: " + e.getClass().getSimpleName()); }
        }
        return result;
    }

    public synchronized void delete(Context context, MediaBrowser.MediaFile file) throws IOException {
        Uri uri = find(context, file);
        if (uri == null) throw new IOException("Downloaded copy was not found");
        int count;
        try { count = context.getContentResolver().delete(uri, null, null); }
        catch (SecurityException e) { throw new IOException("Android would not allow this app to delete that copy", e); }
        if (count < 1) throw new IOException("Android could not remove downloaded file");
        cache.remove(key(file));
        checked.remove(key(file));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key(file)).apply();
    }
}
