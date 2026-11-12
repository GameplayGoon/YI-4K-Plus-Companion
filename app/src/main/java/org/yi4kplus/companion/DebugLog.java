package org.yi4kplus.companion;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/** Private, bounded app log. No camera session tokens or Wi-Fi passwords are logged. */
public final class DebugLog {
    private static final String TAG = "YI4KCompanion";
    private static final long MAX_FILE = 360 * 1024;
    private static final int MAX_ENTRY = 16384;
    private static final Pattern PRIVATE_VALUE = Pattern.compile(
        "(?i)([\\\"']?(?:token|password|ssid|ssid_key|wifi_key|wifi_password|passphrase|serial_number|serial_no|device_sn)[\\\"']?\\s*[=:]\\s*)"
        + "(?:\\\"(?:[^\\\"\\\\]++|\\\\.)*+\\\"|'(?:[^'\\\\]++|\\\\.)*+'|[^,}\\r\\n&]+)");
    private static final Pattern PRIVATE_QUERY = Pattern.compile(
        "(?i)([?&](?:sn|token|password|ssid_key|wifi_key)=)[^&\\s#]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
        "(?i)(\\b(?:rtsp|https?)://)[^\\s/@]+@");
    private static File file;
    private static String appVersion = "unknown";
    private DebugLog() {}

    public static synchronized void init(Context context) {
        if (file != null) return;
        try { appVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { }
        file = new File(context.getFilesDir(), "yi_companion_debug.txt");
        write("SESSION", "YI 4K+ Companion " + appVersion + "; " + Build.MANUFACTURER + " " + Build.MODEL
            + "; Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    }

    static String clean(String raw) {
        if (raw == null) return "(null)";
        if (raw.length() > MAX_ENTRY) raw = raw.substring(0, MAX_ENTRY) + " [truncated]";
        // In case an unexpected exception contains protocol or private fields.
        String cleaned = PRIVATE_QUERY.matcher(raw).replaceAll("$1[REDACTED]");
        cleaned = URL_CREDENTIALS.matcher(cleaned).replaceAll("$1[REDACTED]@");
        return PRIVATE_VALUE.matcher(cleaned).replaceAll("$1\"[REDACTED]\"");
    }

    public static synchronized void info(String event) { write("INFO", event); }
    public static synchronized void warn(String event) { write("WARN", event); }
    public static synchronized void error(String event, Throwable throwable) {
        StringWriter buffer = new StringWriter();
        if (throwable != null) throwable.printStackTrace(new PrintWriter(buffer));
        String details = buffer.toString();
        if (details.length() > 12000) details = details.substring(0, 12000) + " [truncated]";
        write("ERROR", event + (throwable != null ? "\n" + details : ""));
    }

    private static void write(String level, String message) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
            + " [" + level + "] " + clean(message) + "\n";
        Log.i(TAG, line.trim());
        if (file == null) return;
        try {
            if (file.exists() && file.length() > MAX_FILE) {
                // Retain the last half of the log when rotating; avoids unbounded storage.
                byte[] previous = readBytes();
                int from = Math.max(0, previous.length - 180 * 1024);
                while (from < previous.length && previous[from] != '\n') from++;
                if (from < previous.length) from++;
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write(previous, from, previous.length - from);
                }
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { Log.e(TAG, "Could not write diagnostics", e); }
    }

    private static byte[] readBytes() throws Exception {
        if (file == null || !file.exists()) return new byte[0];
        try (FileInputStream in = new FileInputStream(file)) {
            long length = file.length();
            byte[] data = new byte[(int) Math.min(length, MAX_FILE + MAX_ENTRY)];
            // Preserve the newest diagnostics even if an older build left a
            // larger-than-expected file behind.
            in.getChannel().position(Math.max(0, length - data.length));
            int total = 0;
            int count;
            while (total < data.length && (count = in.read(data, total, data.length - total)) > 0) total += count;
            if (total == data.length) return data;
            return java.util.Arrays.copyOf(data, total);
        }
    }

    public static synchronized String lastLines(int maxLines) {
        String all = read();
        String[] lines = all.split("\n");
        StringBuilder result = new StringBuilder();
        for (int i = Math.max(0, lines.length - maxLines); i < lines.length; i++) {
            result.append(lines[i]).append('\n');
        }
        return result.toString();
    }

    public static synchronized String read() {
        try { return new String(readBytes(), StandardCharsets.UTF_8); }
        catch (Exception e) { return "Could not read log: " + e.getMessage(); }
    }

    public static synchronized String report(Context context, String host, boolean connected, String cameraInfo) {
        return "YI 4K+ Companion diagnostic export\n"
            + "App: " + appVersion + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
            + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
            + "Camera address: " + host + "\nSession connected when exported: " + connected + "\n"
            + "Camera details (serial/password hidden):\n" + clean(cameraInfo) + "\n\n"
            + "---- APP EVENT LOG ----\n" + read() + "\n---- END ----\n";
    }
}
