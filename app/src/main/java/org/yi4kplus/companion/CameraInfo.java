package org.yi4kplus.companion;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Only the camera's normal, read-only settings response is inspected. */
public final class CameraInfo {
    private CameraInfo() {}

    public static LinkedHashMap<String, String> settings(JSONObject response) {
        LinkedHashMap<String, String> all = new LinkedHashMap<>();
        if (response == null) return all;
        Object value = response.opt("param");
        if (value instanceof JSONObject) add(all, (JSONObject) value);
        else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) add(all, array.optJSONObject(i));
        }
        return all;
    }

    private static void add(Map<String, String> map, JSONObject obj) {
        if (obj == null) return;
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String key = it.next();
            map.put(key, obj.optString(key, ""));
        }
    }

    public static String detail(Map<String, String> settings) {
        StringBuilder info = new StringBuilder();
        boolean version = false;
        for (Map.Entry<String, String> item : settings.entrySet()) {
            String key = item.getKey();
            String low = key.toLowerCase(Locale.ROOT);
            if (low.contains("password") || low.contains("ssid") || low.contains("token") || low.contains("key")) continue;
            if (low.contains("version") || low.contains("model") || low.contains("serial") ||
                low.contains("chip") || low.equals("sd_card_status") || low.equals("app_status") ||
                low.equals("video_resolution") || low.equals("capture_mode")) {
                String value = item.getValue();
                if (low.contains("serial")) {
                    value = value.length() > 4 ? "••••" + value.substring(value.length() - 4) : "Hidden";
                }
                if (low.contains("version")) version = true;
                info.append(key.replace('_', ' ')).append(": ").append(value).append('\n');
            }
        }
        if (!version) info.insert(0, "Firmware version: Not reported by this camera response.\n");
        if (info.length() == 0) info.append("No device information available from the camera settings API.\n");
        return info.toString();
    }
}
