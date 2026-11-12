package org.yi4kplus.companion;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Safe introspection: only the documented settings and valid-choice READ calls.
 * Never executes guessed command IDs, shell scripts, firmware APIs, or writes.
 */
public final class CameraScan {
    private CameraScan() {}
    public static boolean publicKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT);
        String[] forbidden = {"token", "password", "secret", "ssid", "wifi", "mac", "serial", "device_id",
            "key", "location", "latitude", "longitude", "gps", "auth", "account", "private", "pin"};
        for (String bad : forbidden) if (k.contains(bad)) return false;
        return k.matches("[a-zA-Z0-9_]{1,70}");
    }
    public static boolean safeChoiceKey(String key) {
        if (!publicKey(key)) return false;
        String k = key.toLowerCase(Locale.ROOT);
        String[] blocked = {"format", "erase", "delete", "factory", "reset", "reboot", "firmware", "upgrade",
            "shutdown", "power", "telnet", "sdcard", "sd_card", "update", "version", "status", "time", "date"};
        for (String bad : blocked) if (k.contains(bad)) return false;
        return true;
    }
    public static JSONObject publicSettings(Map<String, String> all) throws Exception {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            if (!publicKey(entry.getKey())) continue;
            String value = entry.getValue() == null ? "" : entry.getValue();
            result.put(entry.getKey(), value.length() <= 220 ? value : value.substring(0, 220) + "…");
        }
        return result;
    }
    public static JSONObject sanitizedChoices(JSONObject raw) throws Exception {
        JSONObject out = new JSONObject();
        out.put("permission", raw.optString("permission", "unknown"));
        JSONArray choices = raw.optJSONArray("options");
        JSONArray values = new JSONArray();
        if (choices != null) for (int i=0; i<Math.min(choices.length(), 60); i++) {
            String val = choices.optString(i, "");
            values.put(val.length() <= 100 ? val : val.substring(0,100));
        }
        out.put("options", values);
        return out;
    }
}
