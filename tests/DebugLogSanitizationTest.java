package org.yi4kplus.companion;

public final class DebugLogSanitizationTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] arguments) {
        String json = "{\"msg_id\":13,\"token\":7345,\"password\":\"a secret with spaces\","
            + "\"serial_number\":\"CAMERA-PRIVATE-42\",\"param\":78,\"rval\":0}";
        String cleaned = DebugLog.clean(json);
        require(!cleaned.contains("7345") && !cleaned.contains("a secret")
            && !cleaned.contains("CAMERA-PRIVATE"), "Quoted JSON credentials must be hidden");
        require(cleaned.contains("\"msg_id\":13") && cleaned.contains("\"param\":78")
            && cleaned.contains("\"rval\":0"), "Public troubleshooting fields must remain readable");
        require(!DebugLog.clean("password='spaces and \\\"quotes\\\"', token=ABC-123")
            .contains("ABC-123"), "Assignment credentials must be hidden");
        String url = DebugLog.clean("GET http://192.168.42.1/DCIM/A.MP4?sn=CAMERA-ABC&time=now");
        require(!url.contains("CAMERA-ABC") && url.contains("time=now"), "URL serial query must be hidden");
        String userinfo = DebugLog.clean("rtsp://alice:secret@192.168.42.1/live");
        require(!userinfo.contains("alice") && !userinfo.contains("secret"), "URL credentials must be hidden");
        String escaped = DebugLog.clean("{\"password\":\"a\\\"b\\\\c\",\"rval\":0}");
        require(!escaped.contains("b\\\\c") && escaped.contains("\"rval\":0"), "Escaped JSON passwords must be hidden");
        String oversized = DebugLog.clean("{\"password\":\"" + "s".repeat(100000) + "\"}");
        require(oversized.length() < 17000 && !oversized.contains("ssss"), "Oversized messages must stay bounded and redacted");
        require(DebugLog.clean(null).equals("(null)"), "Null log values must be supported");
        System.out.println("PASS: diagnostic redaction, escaping, public fields and bounded messages");
    }
}
