package org.yi4kplus.companion;

import org.json.JSONObject;
import java.io.IOException;
import java.util.Locale;

/** Maps YI 4K+ settings to shooting modes. Writes must be confirmed by read-back.
 *  Some firmware versions acknowledge system_mode writes without actually switching families.
 */
public final class CameraModes {
    public static final int VIDEO=0, PHOTO=1, TIMELAPSE=2, SLOW_MOTION=3,
        LOOP=4, BURST=5, TIMER=6, VIDEO_PHOTO=7;
    private CameraModes() {}

    public static boolean isPhoto(int mode) { return mode==PHOTO || mode==BURST || mode==TIMER; }
    public static boolean isExtra(int mode) { return mode>=SLOW_MOTION; }
    public static String label(int mode) {
        switch (mode) {
            case PHOTO: return "Photo";
            case TIMELAPSE: return "Timelapse";
            case SLOW_MOTION: return "Slow-mo";
            case LOOP: return "Loop";
            case BURST: return "Burst";
            case TIMER: return "Timer";
            case VIDEO_PHOTO: return "Video + Photo";
            default: return "Video";
        }
    }
    public static String system(int mode) { return isPhoto(mode) ? "capture" : "record"; }
    public static String subKey(int mode) { return isPhoto(mode) ? "capture_mode" : "rec_mode"; }
    public static String subMode(int mode) {
        switch (mode) {
            case PHOTO: return "precise quality";
            case BURST: return "burst quality";
            case TIMER: return "precise self quality";
            case TIMELAPSE: return "record_timelapse";
            case SLOW_MOTION: return "record_slow_motion";
            case LOOP: return "record_loop";
            case VIDEO_PHOTO: return "record_photo";
            default: return "record";
        }
    }
    public static int fromCamera(String system, String sub) {
        if ("capture".equalsIgnoreCase(system)) {
            if ("burst quality".equals(sub)) return BURST;
            if ("precise self quality".equals(sub)) return TIMER;
            return PHOTO;
        }
        if ("record".equalsIgnoreCase(system)) {
            if ("record_timelapse".equals(sub)) return TIMELAPSE;
            if ("record_slow_motion".equals(sub)) return SLOW_MOTION;
            if ("record_loop".equals(sub)) return LOOP;
            if ("record_photo".equals(sub)) return VIDEO_PHOTO;
            return VIDEO;
        }
        return VIDEO;
    }
    /** Active viewfinder ('vf') is NOT a recording. */
    public static boolean isClearlyRecording(String status) {
        if (status == null) return false;
        String s = status.toLowerCase(Locale.ROOT).trim();
        return s.equals("recording") || s.equals("record") || s.equals("rec") || s.equals("video_record");
    }
    public static boolean isClearlyIdle(String status) {
        if (status == null) return false;
        String s = status.toLowerCase(Locale.ROOT).trim();
        return s.equals("idle") || s.equals("vf");
    }

    /** Kept package-private to test actual state transitions without a physical camera. */
    interface ModeIO {
        String get(String name) throws Exception;
        void set(String name, String value) throws Exception;
        void selectPhotoExperimental(String modeAndOption) throws Exception;
    }

    private static boolean waitUntil(ModeIO io, String key, String expected, int checks) throws Exception {
        for (int i = 0; i < checks; i++) {
            String observed = io.get(key);
            if (expected.equals(observed)) return true;
            if (i + 1 < checks) Thread.sleep(280);
        }
        return false;
    }

    /**
     * One conservative experiment for video -> photo switching only.
     * Message 16777228 is documented as *photo-mode selection* on the related YI Discovery J22,
     * not on the YI 4K+. It is NEVER used with a video mode and never repeated automatically.
     * The user's camera firmware may reject it. Every result is read back and validated.
     */
    private static boolean tryPhotoSelection(ModeIO io, int mode) {
        if (!isPhoto(mode)) return false;
        String suffix = "off";
        try {
            if (mode == TIMER) {
                String current = io.get("precise_selftime");
                suffix = current.matches("(?:3|5|10|15)s") ? current : "5s";
            } else if (mode == BURST) {
                String current = io.get("burst_capture_number");
                suffix = current.matches("(?:3|5|10|20|30) p / (?:s|[236]s)") ? current : "3 p / s";
            }
            String argument = subMode(mode) + ";" + suffix;
            DebugLog.warn("Photo-family mode-select experiment (related YI J22 only): " + argument);
            io.selectPhotoExperimental(argument);
            boolean result = waitUntil(io, "system_mode", "capture", 10);
            DebugLog.info("Experimental photo selection result: " + (result ? "family= capture" : "no transition"));
            return result;
        } catch (Exception e) {
            DebugLog.warn("Experimental photo-mode selector not supported: " + e.getMessage());
            return false;
        }
    }

    /** Never accept an OK response alone as proof of a successful mode change. */
    static void apply(ModeIO io, int mode) throws Exception {
        String desiredSystem = system(mode), key = subKey(mode), targetSub = subMode(mode);
        String actualSystem = io.get("system_mode");
        if (!desiredSystem.equals(actualSystem)) {
            // A cross-family mode change requires more than capture_mode/rec_mode.
            // Prepare the target submode, then ask firmware to change the active family.
            try {
                DebugLog.info("Prepare " + key + " = " + targetSub);
                io.set(key, targetSub);
            } catch (Exception e) {
                DebugLog.warn("Target submode cannot be prepared yet: " + e.getMessage());
            }
            DebugLog.info("Request system_mode " + actualSystem + " → " + desiredSystem);
            io.set("system_mode", desiredSystem);
            if (!waitUntil(io, "system_mode", desiredSystem, 9)) {
                // The v0.6 log shows a few switches succeed only on a later press.
                // Make ONE additional attempt, serially, rather than submitting an
                // unlimited stream of commands or assuming the initial OK was enough.
                DebugLog.warn("Mode change not yet reflected in firmware; one bounded retry");
                if (!desiredSystem.equals(io.get("system_mode"))) {
                    try { io.set("system_mode", desiredSystem); }
                    catch (Exception e) { DebugLog.warn("Mode retry rejected: " + e.getMessage()); }
                }
                if (!waitUntil(io, "system_mode", desiredSystem, 9)) {
                    DebugLog.warn("Firmware acknowledged system_mode but did not change active family");
                    if (!tryPhotoSelection(io, mode)) {
                        String observed = io.get("system_mode");
                        throw new IOException("Camera stayed in " + observed + " mode. Firmware did not switch from " +
                            actualSystem + " to " + desiredSystem + "; use the camera mode button for now.");
                    }
                }
            }
        }
        if (!targetSub.equals(io.get(key))) {
            DebugLog.info("Select " + key + " → " + targetSub);
            io.set(key, targetSub);
            if (!waitUntil(io, key, targetSub, 12))
                throw new IOException("Camera did not confirm " + key + " = " + targetSub);
        }
        String verifiedSystem = io.get("system_mode");
        String verifiedSub = io.get(key);
        if (!desiredSystem.equals(verifiedSystem) || !targetSub.equals(verifiedSub))
            throw new IOException("Camera actually " + verifiedSystem + "/" + verifiedSub +
                ", not " + desiredSystem + "/" + targetSub);
        DebugLog.info("Mode confirmed: " + verifiedSystem + "/" + verifiedSub);
    }

    public static void apply(YiCamera camera, int mode) throws Exception {
        apply(new ModeIO() {
            @Override public String get(String key) throws Exception {
                return camera.getSetting(key).optString("param", "unknown");
            }
            @Override public void set(String key, String value) throws Exception { camera.setSetting(key, value); }
            @Override public void selectPhotoExperimental(String value) throws Exception {
                JSONObject p = new JSONObject().put("param", value);
                camera.request(16777228, p);
            }
        }, mode);
    }
}
