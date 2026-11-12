package org.yi4kplus.companion;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.util.ArrayList;

/** Plays the camera's older RTSP SDP (which Media3 rejects without fmtp).
 *  One instance per Activity; all public calls are on the main thread.
 */
public final class VlcPreview {
    public interface Listener { void playing(); void failed(); }
    private final Activity activity;
    private LibVLC libvlc;
    private MediaPlayer player;
    private VLCVideoLayout layout;
    private volatile long generation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable startupTimeout;
    private boolean failed;
    private boolean playing;

    public VlcPreview(Activity context) { activity = context; }
    public void play(FrameLayout container, String url, Listener listener) {
        stop();
        final long thisGeneration = generation;
        failed = false;
        try {
            if (libvlc == null) {
                ArrayList<String> args = new ArrayList<>();
                args.add("--no-audio");
                args.add("--rtsp-tcp");
                args.add("--network-caching=500");
                libvlc = new LibVLC(activity.getApplicationContext(), args);
            }
            layout = new VLCVideoLayout(activity);
            container.addView(layout, new FrameLayout.LayoutParams(-1, -1));
            player = new MediaPlayer(libvlc);
            player.attachViews(layout, null, false, false);
            player.setEventListener(event -> {
                if (thisGeneration != generation) return;
                if (event.type == MediaPlayer.Event.Playing) activity.runOnUiThread(() -> {
                    if (thisGeneration == generation && !failed) {
                        playing = true;
                        cancelStartupTimeout();
                        listener.playing();
                    }
                });
                else if (event.type == MediaPlayer.Event.EncounteredError ||
                         event.type == MediaPlayer.Event.EndReached) activity.runOnUiThread(() -> {
                    failOnce(thisGeneration, listener, "VLC reported a stopped or failed stream");
                });
            });
            Media media = new Media(libvlc, Uri.parse(url));
            try {
                media.addOption(":rtsp-tcp");
                media.addOption(":network-caching=500");
                media.addOption(":no-audio");
                media.setHWDecoderEnabled(true, false);
                player.setMedia(media);
            } finally { media.release(); }
            player.play();
            // A dead RTSP socket is not guaranteed to produce an error callback on
            // every phone. Hand control back to the existing bounded retry policy.
            startupTimeout = () -> {
                if (!playing) failOnce(thisGeneration, listener, "Live preview startup timed out");
            };
            main.postDelayed(startupTimeout, 18000);
        } catch (RuntimeException setupFailure) {
            DebugLog.error("VLC preview setup failed", setupFailure);
            stop();
            final long stoppedGeneration = generation;
            main.post(() -> {
                if (stoppedGeneration == generation) failOnce(stoppedGeneration, listener,
                    "VLC could not initialise live preview");
            });
        }
    }
    private void cancelStartupTimeout() {
        if (startupTimeout != null) main.removeCallbacks(startupTimeout);
        startupTimeout = null;
    }
    private void failOnce(long expectedGeneration, Listener listener, String reason) {
        if (expectedGeneration != generation || failed) return;
        failed = true;
        playing = false;
        cancelStartupTimeout();
        DebugLog.warn(reason);
        listener.failed();
    }
    public void stop() {
        generation++;
        cancelStartupTimeout();
        failed = false;
        playing = false;
        MediaPlayer oldPlayer = player;
        player = null;
        if (oldPlayer != null) {
            try { oldPlayer.setEventListener(null); } catch (RuntimeException ignored) {}
            try { oldPlayer.stop(); } catch (RuntimeException ignored) {}
            try { oldPlayer.detachViews(); } catch (RuntimeException ignored) {}
            try { oldPlayer.release(); } catch (RuntimeException e) {
                DebugLog.warn("VLC player cleanup: " + e.getClass().getSimpleName());
            }
        }
        if (layout != null) {
            ViewGroup parent = (ViewGroup) layout.getParent();
            if (parent != null) parent.removeView(layout);
            layout = null;
        }
    }
    public void release() {
        stop();
        LibVLC oldLibrary = libvlc;
        libvlc = null;
        if (oldLibrary != null) {
            try { oldLibrary.release(); } catch (RuntimeException e) {
                DebugLog.warn("VLC library cleanup: " + e.getClass().getSimpleName());
            }
        }
    }
}
