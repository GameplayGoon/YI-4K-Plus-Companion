package org.yi4kplus.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Size;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.widget.PopupMenu;
import java.io.InputStream;
import android.widget.ImageView;
import android.os.Build;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import java.io.IOException;
import java.net.SocketException;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * YI 4K+ Companion 0.9 beta. Local-only, no cloud account, no proprietary YI binaries.
 * RTSP preview and JSON protocol are based on community projects and MUST be
 * verified on the user's own camera/firmware. File operations are read/download only.
 */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 16, 25);
    private static final int GOLD = Color.rgb(255, 187, 92);
    private static final int MAX_GALLERY_ROWS = 36;
    private static final int EXPORT_REPORT = 420;
    private static final int NOTIFICATION_REQUEST = 509;
    private static final int CARD = Color.rgb(21, 31, 45);
    private static final int RAISED = Color.rgb(34, 49, 64);
    private static final int WHITE = Color.rgb(242, 248, 251);
    private static final int MUTED = Color.rgb(161, 177, 193);
    private static final int MINT = Color.rgb(50, 220, 173);
    private static final int RED = Color.rgb(242, 82, 89);
    private static final String[] PROTECTED_KEYS = {
        "format", "erase", "delete", "factory", "reset", "reboot", "firmware",
        "upgrade", "telnet", "wifi", "network", "password", "shutdown",
        "power_off", "sdcard", "sd_card", "update"
    };

    private final ScheduledExecutorService controlIO = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService mediaIO = Executors.newSingleThreadExecutor();
    private final ExecutorService fileIO = Executors.newSingleThreadExecutor(); // local deletions never wait behind big Wi-Fi transfers
    private final ExecutorService thumbnailIO = Executors.newFixedThreadPool(1);
    private final ExecutorService metadataIO = Executors.newFixedThreadPool(1);
    private final java.util.concurrent.ConcurrentHashMap<String, String> metadataCache = new java.util.concurrent.ConcurrentHashMap<>();
    // Only visible remote videos are periodically re-checked. A camera can list an MP4
    // before it has finished writing it, so a filename alone cannot imply completion.
    private final java.util.Map<String, TextView> remoteVideoDetails = new java.util.HashMap<>();
    private final java.util.Set<String> videosSeenRecording = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile int videoSizeRefreshScans = 0;
    private boolean galleryWasRecording = false;
    private int gallerySyncsSinceVideoRecheck = 0;
    private final java.util.Set<String> selectedRemote = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> selectedLocal = new java.util.LinkedHashSet<>();
    private boolean selectionMode = false;
    private Button gallerySelectButton;
    private final Thumbs thumbnails = new Thumbs();
    private volatile String host = "192.168.42.1";
    private volatile Network wifi;
    private volatile YiCamera camera;
    private volatile boolean connected;
    private volatile boolean recording;
    private volatile boolean captureBusy;
    private volatile boolean connecting;
    private volatile boolean userDisconnected = true;
    private final java.util.concurrent.atomic.AtomicBoolean reconnectPending = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile int reconnectAttempts = 0;
    private final java.util.concurrent.atomic.AtomicInteger connectionGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private final Object connectionLock = new Object();
    private volatile boolean destroyed;
    private Network priorProcessBinding;
    private Network boundCameraNetwork;
    private boolean processBindingOwned;
    private java.util.concurrent.ScheduledFuture<?> reconnectFuture;
    private volatile MediaBrowser activeTransferBrowser;
    private final java.util.concurrent.atomic.AtomicInteger downloadGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private ConnectivityManager.NetworkCallback wifiCallback;
    private volatile int batteryFailures;
    private volatile String lastCameraInfo = "Not queried yet";
    private int galleryVisible = MAX_GALLERY_ROWS;
    private volatile boolean previewStarted;
    private volatile int tab;
    private volatile int captureMode = CameraModes.VIDEO;
    private volatile String cameraSystemMode = "unknown";
    private volatile String cameraSubMode = "unknown";
    private volatile String cameraAppStatus = "unknown";
    private volatile String storageSummary = "SD · —";
    private volatile String storageCompact = "SD —";
    private volatile int storageUsedPercent = -1;
    private StorageMeterView storageMeter;
    private final java.util.Map<Integer, Button> modeChips = new java.util.HashMap<>();
    private Button moreModesChip;
    private volatile int queuedMode = -1;
    private final java.util.concurrent.ConcurrentHashMap<String, DownloadState> activeDownloads = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger downloadQueueLength = new java.util.concurrent.atomic.AtomicInteger();

    private static final class DownloadState {
        volatile int percent = -1;
        volatile boolean started;
        volatile Button visibleButton;
        volatile long bytes;
        String label() {
            if (!started) return "⏳ Queued";
            return percent >= 0 ? "↓ " + percent + "%" : (bytes > 0 ? "↓ " + (bytes / 1048576L) + " MB" : "↓ Starting…");
        }
    }
    private volatile String batterySource = "Unknown";
    private volatile String batteryReading = "Unknown";
    private TextView storageTag;
    private boolean loopModeAccepted;
    private int mediaFilter = 0; // 0 all, 1 photo, 2 video
    private boolean advancedSettings = false;
    private final List<String[]> lastSettingsRows = new ArrayList<>();
    private FrameLayout liveFrame;
    private VlcPreview vlc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int previewRetries = 0;
    private int previewRecoveries = 0;
    private volatile int previewCycle = 0;
    private boolean previewReady = false;
    private Button shutterButton;
    private TextView shutterLabel;
    private TextView batteryTag;
    private BatteryIconView batteryIcon;
    private FrameLayout shutterControl;
    private View shutterMark;
    private volatile boolean appForeground = false;
    private volatile boolean remoteViewfinderEnabled = false;
    private volatile boolean mediaLoading = false;
    private volatile boolean settingsLoading = false;
    private volatile long lastMediaLoadedAt = 0;
    private volatile boolean galleryKnownEmpty = false;
    private volatile boolean galleryRetryNeeded = false;
    private volatile long lastSettingsLoadedAt = 0;
    private final LocalMediaIndex savedMedia = new LocalMediaIndex();
    private final Runnable recordingClock = new Runnable() {
        @Override public void run() {
            if (recordIndicator != null && recording) recordIndicator.setText(recordingSince > 0 ? "● REC  " + recordingTime() : "● REC");
            if (shutterLabel != null && recording && !captureBusy) shutterLabel.setText(recordingSince > 0 ?
                "Recording  " + recordingTime() + "  •  Tap to stop" : "Recording  •  Tap to stop");
            handler.postDelayed(this, 1000);
        }
    };
    private String recordingTime() {
        if (recording && recordingSince <= 0) return "";
        long seconds = recordingSince > 0 ? Math.max(0, (SystemClock.elapsedRealtime() - recordingSince) / 1000) : 0;
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
    private LinearLayout[] navItems;
    private volatile long recordingSince;
    private String lastScan = "No scan yet";
    private static final int EXPORT_SCAN = 421;
    private boolean scanning = false;
    private LinearLayout page;
    private TextView statusText;
    private TextView connectionTag;
    private TextView previewHint;
    private PlayerView livePlayerView;
    private ExoPlayer livePlayer;
    private ExoPlayer galleryPlayer;
    private AlertDialog galleryDialog;
    private LinearLayout galleryItems;
    private LinearLayout settingsItems;
    private Button connectButton;
    private Button previewButton;
    private Button recordButton;
    private Button stopButton;
    private TextView recordIndicator;
    private TextView cameraInfoContent;
    private TextView diagnosticsPreview;
    private final List<MediaBrowser.MediaFile> mediaFiles = new ArrayList<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        DebugLog.init(this);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, err) -> {
            DebugLog.error("Uncaught exception on " + thread.getName(), err);
            if (previous != null) previous.uncaughtException(thread, err);
        });
        host = getPreferences(MODE_PRIVATE).getString("host", "192.168.42.1");
        DebugLog.info("App created; camera IP " + host);
        vlc = new VlcPreview(this);
        buildShell();
        // Keep an inexpensive control heartbeat while the connected-device service is active,
        // including when screen is locked. Never keep the RTSP decoder alive in background.
        controlIO.scheduleWithFixedDelay(() -> {
            final YiCamera active = camera;
            final int generation = connectionGeneration.get();
            if (!destroyed && !userDisconnected && connected && (active == null || !active.isOpen())) {
                connectionLost("closed control socket", new IOException("Camera control socket closed"), active);
                return;
            }
            if (!currentSession(generation, active)) return;
            try {
                JSONObject battery = active.battery();
                if (!currentSession(generation, active)) return;
                String raw = battery.optString("level", battery.optString("param", "?"));
                String label = battery.optString("type", "");
                batteryFailures = 0;
                batteryReading = raw;
                batterySource = label;
                sessionUi(generation, () -> {
                    if (connected && connectionTag != null) connectionTag.setText("●  Connected");
                    if (batteryTag != null) {
                        String digits = raw.replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) {
                            int pct = Math.max(0, Math.min(100, Integer.parseInt(digits)));
                            batteryTag.setText(pct + "%");
                            if (batteryIcon != null) batteryIcon.setLevel(pct);
                        } else batteryTag.setText(raw);
                    }
                });
            } catch (Exception e) {
                batteryFailures++;
                if (batteryFailures == 1 || batteryFailures % 8 == 0) DebugLog.error("Battery polling", e);
                if (isTransportFailure(e) || !active.isOpen()) connectionLost("heartbeat", e, active);
            }
        }, 10, 12, TimeUnit.SECONDS);
        controlIO.scheduleWithFixedDelay(() -> {
            if (!connected || camera == null || !appForeground) return;
            try { refreshStorageOnControlThread(); }
            catch (Exception e) { DebugLog.warn("SD space poll unavailable: " + e.getMessage()); }
        }, 40, 65, TimeUnit.SECONDS);
        // Reads only; picks up on-camera button presses without restarting live view.
        controlIO.scheduleWithFixedDelay(this::pollCameraMode, 5, 6, TimeUnit.SECONDS);
        // Only poll the directory when the user is actually viewing it. A single HTTP listing
        // every ~5s is moderate, and transfers take priority on the one media worker.
        controlIO.scheduleWithFixedDelay(() -> {
            if (!appForeground || !connected || tab != 1 || mediaLoading || captureBusy ||
                downloadQueueLength.get() != 0) return;
            if (SystemClock.elapsedRealtime() - lastMediaLoadedAt >= 5000L) loadMedia();
        }, 5, 5, TimeUnit.SECONDS);
        observeWifiChanges();
    }

    private void pollCameraMode() {
        final YiCamera active = camera;
        final int generation = connectionGeneration.get();
        if (!currentSession(generation, active) || !appForeground || connecting || captureBusy) return;
        try {
            String system = active.getSetting("system_mode").optString("param", "unknown");
            if (!"record".equals(system) && !"capture".equals(system)) return;
            String sub = active.getSetting("record".equals(system) ? "rec_mode" : "capture_mode")
                .optString("param", "unknown");
            String status = active.getSetting("app_status").optString("param", "unknown");
            if (!currentSession(generation, active)) return;
            int physicalMode = CameraModes.fromCamera(system, sub);
            boolean changed = physicalMode != captureMode;
            cameraSystemMode = system; cameraSubMode = sub; cameraAppStatus = status;
            int completedEvents = active.takeVideoCompleteEvents();
            if (completedEvents > 0) {
                DebugLog.info("Camera sent video_record_complete; refreshing remote video metadata");
                videoSizeRefreshScans = Math.max(videoSizeRefreshScans, 4);
                lastMediaLoadedAt = 0;
            }
            if (CameraModes.isClearlyRecording(status) && !recording) {
                recording = true;
                recordingSince = 0; // Camera button: unknown recording start time.
                changed = true;
                lastMediaLoadedAt = 0;
                DebugLog.info("Recording started on camera; remote videos are not treated as finished");
            } else if (recording && CameraModes.isClearlyIdle(status)) {
                recording = false; recordingSince = 0;
                changed = true;
                videoSizeRefreshScans = Math.max(videoSizeRefreshScans, 4);
                lastMediaLoadedAt = 0;
                DebugLog.info("Camera now idle; checking current video sizes after recording");
            }
            if (physicalMode != captureMode) captureMode = physicalMode;
            if (changed) {
                DebugLog.info("Camera controls changed externally: " + system + "/" + sub + ", status=" + status);
                sessionUi(generation, () -> { updateModeUi(); updateCaptureUi(); });
            }
        } catch (Exception e) {
            if (isTransportFailure(e)) connectionLost("mode sync", e, active);
            else DebugLog.warn("Mode sync paused: " + shortError(e));
        }
    }

    private static String shortError(Throwable e) {
        String msg = String.valueOf(e.getMessage()).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        return msg.length() > 180 ? msg.substring(0, 180) + "…" : msg;
    }

    private static boolean isTransportFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof YiCamera.TransportException || t instanceof SocketException || t instanceof java.io.EOFException ||
                t instanceof java.net.SocketTimeoutException) return true;
            String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("broken pipe") || message.contains("connection abort") ||
                message.contains("connection reset") || message.contains("network unreachable") ||
                message.contains("machine is not on the network") || message.contains("invalid camera json") ||
                message.contains("camera protocol reply") || message.contains("enonet")) return true;
        }
        return false;
    }

    private boolean currentIntent(int generation) {
        return !destroyed && !userDisconnected && generation == connectionGeneration.get();
    }

    private boolean currentSession(int generation, YiCamera source) {
        return currentIntent(generation) && connected && source != null && camera == source && source.isOpen();
    }

    private void sessionUi(int generation, Runnable task) {
        handler.post(() -> { if (currentIntent(generation) && !isFinishing()) task.run(); });
    }

    private void observeWifiChanges() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { wakeReconnect(); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                if (network.equals(wifi) || !connected) wakeReconnect();
            }
            @Override public void onLost(Network network) {
                if (!userDisconnected && network.equals(wifi)) {
                    connectionLost("camera Wi-Fi disappeared", new IOException("Camera Wi-Fi network lost"));
                }
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback);
        } catch (Exception e) { DebugLog.warn("Wi-Fi callback unavailable: " + shortError(e)); wifiCallback = null; }
    }

    private void cancelReconnectLocked() {
        if (reconnectFuture != null) reconnectFuture.cancel(false);
        reconnectFuture = null;
        reconnectPending.set(false);
    }

    private void wakeReconnect() {
        synchronized (connectionLock) {
            if (destroyed || userDisconnected || connected || connecting) return;
            cancelReconnectLocked();
            reconnectAttempts = 0;
        }
        scheduleReconnect(1);
    }

    private void connectionLost(String where, Throwable cause) {
        connectionLost(where, cause, null);
    }

    private void connectionLost(String where, Throwable cause, YiCamera source) {
        final int generation;
        final YiCamera old;
        synchronized (connectionLock) {
            if (destroyed || userDisconnected || (source != null && source != camera)) return;
            if (!connected && !connecting && camera == null) { scheduleReconnect(1); return; }
            connected = false;
            connecting = false;
            remoteViewfinderEnabled = false;
            old = camera;
            camera = null;
            generation = connectionGeneration.incrementAndGet();
            cancelReconnectLocked();
        }
        if (old != null) old.close(); // Interrupt a blocked read immediately, never behind the command queue.
        DebugLog.warn("Control link lost at " + where + " (" + shortError(cause) + "); reconnecting");
        sessionUi(generation, () -> {
            stopPreview(true);
            updateCaptureUi();
            if (connectionTag != null) connectionTag.setText("○ Reconnecting");
            postStatus("Camera connection interrupted. Reconnecting automatically…");
        });
        scheduleReconnect(1);
    }

    private void scheduleReconnect(long minimumSeconds) {
        synchronized (connectionLock) {
            if (destroyed || userDisconnected || connecting || connected || controlIO.isShutdown() || reconnectPending.get()) return;
            int attempt = ++reconnectAttempts;
            long seconds = Math.max(minimumSeconds, Math.min(30L, 1L << Math.min(attempt, 5)));
            int generation = connectionGeneration.get();
            reconnectPending.set(true);
            DebugLog.info("Reconnect attempt " + attempt + " scheduled in " + seconds + "s");
            reconnectFuture = controlIO.schedule(() -> {
                synchronized (connectionLock) {
                    if (!currentIntent(generation)) return;
                    reconnectPending.set(false);
                    reconnectFuture = null;
                    if (connected || connecting) return;
                    connecting = true;
                }
                openCameraSession(generation, true);
            }, seconds, TimeUnit.SECONDS);
        }
    }

    private void startConnectionService() {
        try {
            Intent intent = new Intent(this, CameraConnectionService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) { DebugLog.warn("Background connection service unavailable: " + shortError(e)); }
    }

    private void stopConnectionService() {
        try { stopService(new Intent(this, CameraConnectionService.class)); }
        catch (Exception ignored) {}
    }

    /** Android 13+ hides ordinary foreground-service notifications until the user opts in.
     * The service can still run without this permission; ask once, with a clear explanation.
     */
    private void offerConnectionNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (getPreferences(MODE_PRIVATE).getBoolean("notification_prompt_shown", false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("notification_prompt_shown", true).apply();
        new AlertDialog.Builder(this)
            .setTitle("Show camera connection notification?")
            .setMessage("Android can keep the camera connection running without showing a normal notification. " +
                "Allow notifications if you want to see 'YI 4K+ connected' while the app is minimised. " +
                "You can change this later in Android Settings → Apps → YI 4K+ Companion → Notifications.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Allow", (dialog, which) ->
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST))
            .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQUEST) {
            boolean allowed = grantResults.length > 0 &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            DebugLog.info("Connection notification permission: " + (allowed ? "allowed" : "not allowed"));
            postStatus(allowed ? "Camera connection notification enabled" :
                "Camera connection can continue; notification is hidden by Android settings");
        }
    }

    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable shape(int fill, float radius, int strokeColor, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), strokeColor);
        return drawable;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sizeSp);
        view.setFontFeatureSettings("kern");
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private Button button(String label, int fill, int foreground, int outline) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(foreground);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable base = shape(fill, 14, outline, outline == fill ? 0 : 1);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), base, null));
        b.setMinHeight(dp(48));
        b.setElevation(0);
        return b;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, -2); }

    private void space(LinearLayout parent, int height) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void addButton(LinearLayout row, Button b, int left, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1);
        lp.setMargins(dp(left), 0, dp(right), 0);
        row.addView(b, lp);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = vertical();
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(shape(CARD, 22, RAISED, 1));
        LinearLayout.LayoutParams lp = match();
        lp.bottomMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private LinearLayout navigationItem(String iconText, String label, int index) {
        LinearLayout item = vertical();
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(7), dp(2), dp(5));
        NavGlyph icon = new NavGlyph(this, index, index == tab ? MINT : MUTED);
        item.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView caption = text(label, 11, index == tab ? WHITE : MUTED, index == tab);
        caption.setGravity(Gravity.CENTER);
        item.addView(caption);
        item.setBackground(shape(index == tab ? RAISED : BG, 15, index == tab ? MINT : BG, index == tab ? 1 : 0));
        item.setOnClickListener(v -> selectTab(index));
        return item;
    }

    private void buildShell() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        root.setPadding(dp(10), dp(5), dp(10), dp(4));
        setContentView(root);
        LinearLayout toolbar = horizontal();
        TextView heading = text("YI 4K+", 21, WHITE, true);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout storageBadge = vertical();
        storageBadge.setGravity(Gravity.CENTER_VERTICAL);
        storageBadge.setPadding(dp(5), dp(3), dp(4), dp(3));
        storageTag = text(storageCompact, 10, MINT, true);
        storageTag.setSingleLine(true);
        storageTag.setGravity(Gravity.CENTER);
        storageBadge.addView(storageTag);
        storageMeter = new StorageMeterView(this);
        storageMeter.setUsedPercent(storageUsedPercent);
        LinearLayout.LayoutParams meterLp = new LinearLayout.LayoutParams(-1, dp(5));
        meterLp.topMargin = dp(5);
        storageBadge.addView(storageMeter, meterLp);
        toolbar.addView(storageBadge, new LinearLayout.LayoutParams(dp(120), dp(42)));
        LinearLayout batteryGroup = horizontal();
        batteryGroup.setGravity(Gravity.CENTER_VERTICAL);
        batteryGroup.setPadding(dp(4), dp(4), dp(8), dp(4));
        batteryIcon = new BatteryIconView(this);
        batteryGroup.addView(batteryIcon, new LinearLayout.LayoutParams(dp(30), dp(19)));
        batteryTag = text(connected ? "--%" : "", 13, MINT, true);
        batteryTag.setPadding(dp(5), 0, 0, 0);
        batteryGroup.addView(batteryTag);
        toolbar.addView(batteryGroup);
        connectionTag = text(connected ? "●  Connected" : "○  Offline", 12, connected ? MINT : MUTED, true);
        connectionTag.setPadding(dp(11), dp(8), dp(11), dp(8));
        connectionTag.setBackground(shape(CARD, 13, RAISED, 1));
        toolbar.addView(connectionTag);
        // Connection action/label is already shown in the camera controls.
        // Keep the narrow SD meter and the battery visible on smaller phones.
        connectionTag.setVisibility(View.GONE);
        root.addView(toolbar);
        statusText = text("Connect to the camera's Wi-Fi to begin", 12, MUTED, false);
        statusText.setMaxLines(2);
        root.addView(statusText);
        space(root, 4);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        page = vertical();
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        space(root, 5);
        LinearLayout nav = horizontal();
        nav.setBackground(shape(CARD, 20, RAISED, 1));
        nav.setPadding(dp(5), dp(4), dp(5), dp(4));
        navItems = new LinearLayout[]{
            navigationItem("camera", "Camera", 0),
            navigationItem("gallery", "Gallery", 1),
            navigationItem("settings", "Settings", 2)
        };
        for (LinearLayout item : navItems) nav.addView(item, new LinearLayout.LayoutParams(0, dp(64), 1));
        root.addView(nav);
        selectTab(tab);
    }

    private void selectTab(int next) {
        int oldTab = tab;
        // Tab navigation pauses the local decoder, as minimising does. Keep the
        // remote viewfinder intact instead of sending stop/start commands on every tab.
        if (tab == 0 && (next != 0 || previewStarted)) stopPreview(true);
        tab = next;
        if (navItems != null) {
            String[] names = {"camera", "gallery", "settings"};
            String[] labels = {"Camera", "Gallery", "Settings"};
            for (int i = 0; i < navItems.length; i++) {
                LinearLayout previous = navItems[i];
                LinearLayout parent = (LinearLayout) previous.getParent();
                int idx = parent.indexOfChild(previous);
                parent.removeView(previous);
                LinearLayout fresh = navigationItem(names[i], labels[i], i);
                navItems[i] = fresh;
                parent.addView(fresh, idx, new LinearLayout.LayoutParams(0, dp(64), 1));
            }
        }
        page.removeAllViews();
        livePlayerView = null;
        liveFrame = null;
        previewHint = null;
        galleryItems = null;
        settingsItems = null;
        connectButton = null;
        previewButton = null;
        recordButton = null;
        stopButton = null;
        shutterButton = null;
        shutterControl = null;
        shutterMark = null;
        shutterLabel = null;
        recordIndicator = null;
        cameraInfoContent = null;
        diagnosticsPreview = null;
        if (tab == 0) {
            showCamera();
            if (connected && !previewStarted) handler.postDelayed(() -> {
                if (tab == 0 && connected && !previewStarted) startPreview();
            }, 280);
        } else if (tab == 1) {
            showGallery();
            if (connected && (oldTab != 1 || SystemClock.elapsedRealtime() - lastMediaLoadedAt > 15000)) loadMedia();
        } else if (tab == 2) {
            showSettings();
            if (connected && (oldTab != 2 || SystemClock.elapsedRealtime() - lastSettingsLoadedAt > 15000)) loadSettings();
        } else showDiagnostics();
    }

    private void showCamera() {
        modeChips.clear();
        moreModesChip = null;
        // Compact controls; keep most of the screen for the viewfinder.
        LinearLayout connection = horizontal();
        connection.setGravity(Gravity.CENTER_VERTICAL);
        Button wifiButton = button("Wi-Fi", CARD, WHITE, RAISED);
        wifiButton.setTextSize(12);
        connection.addView(wifiButton, new LinearLayout.LayoutParams(dp(80), dp(43)));
        wifiButton.setOnClickListener(v -> openWifiSettings());
        connectButton = button(connected ? "Disconnect" : !userDisconnected ? "Cancel connection" : "Connect camera",
            connected ? CARD : MINT, connected ? WHITE : BG, connected ? RAISED : MINT);
        connectButton.setEnabled(!destroyed);
        LinearLayout.LayoutParams connectLp = new LinearLayout.LayoutParams(0, dp(43), 1);
        connectLp.leftMargin = dp(8);
        connection.addView(connectButton, connectLp);
        connectButton.setOnClickListener(v -> { if (!userDisconnected) disconnect(); else connect(); });
        previewButton = button("↻ Preview", CARD, MINT, RAISED);
        previewButton.setTextSize(12);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(dp(95), dp(43));
        previewLp.leftMargin = dp(8);
        connection.addView(previewButton, previewLp);
        previewButton.setOnClickListener(v -> {
            if (previewStarted) { stopPreview(); handler.postDelayed(this::startPreview, 350); }
            else startPreview();
        });
        page.addView(connection);
        space(page, 8);

        FrameLayout preview = new FrameLayout(this);
        preview.setBackground(shape(Color.BLACK, 12, RAISED, 1));
        preview.setClipToOutline(true);
        int viewHeight = getResources().getDisplayMetrics().heightPixels;
        int previewHeight = Math.max(dp(265), Math.min(dp(520), (int)(viewHeight * .47f)));
        page.addView(preview, new LinearLayout.LayoutParams(-1, previewHeight));
        liveFrame = new FrameLayout(this);
        preview.addView(liveFrame, new FrameLayout.LayoutParams(-1, -1));
        previewHint = text(connected ? "Preparing live view…" : "◉\n\nLive preview appears here", 15, MUTED, false);
        previewHint.setGravity(Gravity.CENTER);
        preview.addView(previewHint, new FrameLayout.LayoutParams(-1, -1));
        recordIndicator = text(recording ? (recordingSince > 0 ? "● REC  " + recordingTime() : "● REC") : "LIVE PREVIEW", 11, recording ? RED : WHITE, true);
        recordIndicator.setPadding(dp(10), dp(7), dp(10), dp(7));
        recordIndicator.setBackground(shape(recording ? 0xFF642832 : 0xC0202935, 12, RAISED, 0));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        badge.setMargins(dp(12), dp(12), 0, 0);
        preview.addView(recordIndicator, badge);
        space(page, 14);

        LinearLayout modeBar = horizontal();
        modeBar.setGravity(Gravity.CENTER_VERTICAL);
        modeBar.setPadding(dp(1), dp(1), dp(1), dp(1));
        final int[] commonModes = { CameraModes.VIDEO, CameraModes.PHOTO, CameraModes.TIMELAPSE };
        for (int i = 0; i < commonModes.length; i++) {
            int mode = commonModes[i];
            boolean selected = captureMode == mode;
            Button chip = button(CameraModes.label(mode), selected ? MINT : CARD,
                selected ? BG : WHITE, selected ? MINT : RAISED);
            chip.setTextSize(12);
            addButton(modeBar, chip, i == 0 ? 0 : 3, 3);
            chip.setEnabled(!captureBusy && !recording);
            modeChips.put(mode, chip);
            chip.setOnClickListener(v -> switchCaptureMode(mode));
        }
        Button other = button(CameraModes.isExtra(captureMode) ? CameraModes.label(captureMode) + " ▾" : "More ▾",
            CameraModes.isExtra(captureMode) ? MINT : CARD, CameraModes.isExtra(captureMode) ? BG : WHITE, RAISED);
        other.setTextSize(12);
        other.setEnabled(!captureBusy && !recording);
        moreModesChip = other;
        addButton(modeBar, other, 3, 0);
        other.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, other);
            int[] extra = {CameraModes.SLOW_MOTION, CameraModes.LOOP, CameraModes.BURST,
                CameraModes.TIMER, CameraModes.VIDEO_PHOTO};
            for (int mode : extra) menu.getMenu().add(0, mode, mode, CameraModes.label(mode));
            menu.setOnMenuItemClickListener(item -> { switchCaptureMode(item.getItemId()); return true; });
            menu.show();
        });
        page.addView(modeBar);
        space(page, 11);
        LinearLayout controls = horizontal();
        controls.setGravity(Gravity.CENTER);
        Button gallery = button("▦", CARD, WHITE, RAISED);
        gallery.setTextSize(25);
        controls.addView(gallery, new LinearLayout.LayoutParams(dp(66), dp(66)));
        gallery.setOnClickListener(v -> selectTab(1));
        // Custom shutter: centred white record mark, a square while recording;
        // photo mode is a clean solid red circle.
        shutterControl = new FrameLayout(this);
        shutterControl.setBackground(shape(CameraModes.isPhoto(captureMode) ? RED : (recording ? RAISED : RED), 48, WHITE, 3));
        LinearLayout.LayoutParams shutterLp = new LinearLayout.LayoutParams(dp(86), dp(86));
        shutterLp.leftMargin = dp(26); shutterLp.rightMargin = dp(26);
        controls.addView(shutterControl, shutterLp);
        shutterMark = new View(this);
        FrameLayout.LayoutParams markLp = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER);
        shutterControl.addView(shutterMark, markLp);
        shutterControl.setContentDescription(CameraModes.isPhoto(captureMode) ? "Take photo" : recording ? "Stop recording" : "Start recording");
        shutterControl.setOnClickListener(v -> {
            if (CameraModes.isPhoto(captureMode)) performCommand("Take photo", 769, false);
            else performCommand(recording ? "Stop recording" : "Start recording", recording ? 514 : 513, !recording);
        });
        Button tools = button("⚙", CARD, WHITE, RAISED);
        tools.setTextSize(25);
        controls.addView(tools, new LinearLayout.LayoutParams(dp(66), dp(66)));
        tools.setOnClickListener(v -> selectTab(2));
        page.addView(controls);
        space(page, 8);
        shutterLabel = text(CameraModes.isPhoto(captureMode) ? "Tap to take photo" : recording ? "Recording • tap to stop" : "Tap to record", 13, MUTED, false);
        shutterLabel.setGravity(Gravity.CENTER);
        page.addView(shutterLabel);
        updateCaptureUi();
    }

    /** Repaint the mode selector without tearing down and restarting the camera stream. */
    private void updateModeUi() {
        for (java.util.Map.Entry<Integer, Button> entry : modeChips.entrySet()) {
            Button chip = entry.getValue();
            boolean selected = captureMode == entry.getKey();
            chip.setBackground(shape(selected ? MINT : CARD, 13, selected ? MINT : RAISED, 1));
            chip.setTextColor(selected ? BG : WHITE);
        }
        for (Button b : modeChips.values()) b.setEnabled(!captureBusy && !recording);
        if (moreModesChip != null) {
            moreModesChip.setEnabled(!captureBusy && !recording);
            boolean extra = CameraModes.isExtra(captureMode);
            moreModesChip.setText(extra ? CameraModes.label(captureMode) + " ▾" : "More ▾");
            moreModesChip.setTextColor(extra ? BG : WHITE);
            moreModesChip.setBackground(shape(extra ? MINT : CARD, 13, extra ? MINT : RAISED, 1));
        }
    }

    private void updateCaptureUi() {
        if (recordIndicator != null) {
            recordIndicator.setText(recording ? (recordingSince > 0 ? "● REC  " + recordingTime() : "● REC") : "LIVE PREVIEW");
            recordIndicator.setTextColor(recording ? RED : WHITE);
        }
        if (shutterControl != null) {
            shutterControl.setEnabled(connected && !captureBusy);
            shutterControl.setAlpha(connected && !captureBusy ? 1f : .5f);
            shutterControl.setBackground(shape(CameraModes.isPhoto(captureMode) ? RED : (recording ? RAISED : RED), 48, WHITE, 3));
            shutterControl.setContentDescription(CameraModes.isPhoto(captureMode) ? "Take photo" : recording ? "Stop recording" : "Start recording");
            if (shutterMark != null) {
                shutterMark.setVisibility(CameraModes.isPhoto(captureMode) ? View.GONE : View.VISIBLE);
                shutterMark.setBackground(shape(WHITE, recording ? 7 : 40, WHITE, 0));
            }
        }
        if (shutterLabel != null) shutterLabel.setText(captureBusy ? "Working…" :
            CameraModes.isPhoto(captureMode) ? "Tap to take photo" : recording ? (recordingSince > 0 ? "Recording  " + recordingTime() + "  •  Tap to stop" : "Recording  •  Tap to stop") : "Tap to start " + CameraModes.label(captureMode).toLowerCase(Locale.ROOT));
    }

    private void showGallery() {
        LinearLayout title = horizontal();
        LinearLayout labels = vertical();
        labels.addView(text("Gallery", 25, WHITE, true));
        labels.addView(text(!connected ? "Saved files on your phone" :
            mediaFiles.isEmpty() ? "Photos and clips on your camera" : mediaFiles.size() + " files on camera", 12, MUTED, false));
        title.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Button selection = button(selectionMode ? "Cancel" : "Select", CARD, MINT, RAISED);
        selection.setTextSize(12);
        title.addView(selection, new LinearLayout.LayoutParams(dp(80), dp(39)));
        gallerySelectButton = selection;
        selection.setOnClickListener(v -> {
            selectionMode = !selectionMode;
            if (!selectionMode) { selectedRemote.clear(); selectedLocal.clear(); }
            if (connected) renderMedia(); else renderLocalDownloads();
            selection.setText(selectionMode ? "Cancel" : "Select");
        });
        page.addView(title);
        space(page, 12);
        LinearLayout filters = horizontal();
        String[] captions = {"All", "Photos", "Videos"};
        for (int i = 0; i < captions.length; i++) {
            final int filter = i;
            Button chip = button(captions[i], i == mediaFilter ? MINT : CARD, i == mediaFilter ? BG : MUTED,
                i == mediaFilter ? MINT : RAISED);
            addButton(filters, chip, i == 0 ? 0 : 4, 4);
            chip.setOnClickListener(v -> { mediaFilter = filter; selectTab(1); });
        }
        page.addView(filters);
        space(page, 13);
        galleryItems = vertical();
        page.addView(galleryItems);
        if (!connected) renderLocalDownloads();
        else if (mediaFiles.isEmpty()) addEmpty(galleryItems, galleryKnownEmpty ?
            "No photos or videos on the camera" : "Loading photos and videos…");
        else renderMedia();
    }

    private void showSettings() {
        page.addView(text("Camera settings", 25, WHITE, true));
        space(page, 5);
        page.addView(text("The settings you use most, without the clutter.", 12, MUTED, false));
        space(page, 14);
        LinearLayout quick = card(page);
        LinearLayout header = horizontal();
        header.addView(text("Quick settings", 19, WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView sync = text("AUTO SYNC", 11, MINT, true);
        sync.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(sync);
        quick.addView(header);
        space(quick, 5);
        quick.addView(text("Choose a setting to see the options your camera supports.", 12, MUTED, false));
        settingsItems = vertical();
        page.addView(settingsItems);
        if (!lastSettingsRows.isEmpty()) showSettingsRows(lastSettingsRows);
        else addEmpty(settingsItems, connected ? "Reading camera settings…" : "Connect to your camera to see its settings.");
        space(page, 8);
        LinearLayout device = card(page);
        device.addView(text("Advanced camera scan", 17, WHITE, true));
        space(device, 5);
        device.addView(text("For troubleshooting only. Reads the settings supported by your particular camera.", 12, MUTED, false));
        space(device, 8);
        Button scan = button(scanning ? "Scanning…" : "Export camera capabilities", RAISED, MINT, MINT);
        scan.setEnabled(!scanning);
        device.addView(scan);
        scan.setOnClickListener(v -> beginScan());
        LinearLayout wifiCard = card(page);
        wifiCard.addView(text("Connection", 18, WHITE, true));
        space(wifiCard, 7);
        wifiCard.addView(text("Camera IP: " + host, 12, MUTED, false));
        space(wifiCard, 10);
        LinearLayout wifiButtons = horizontal();
        Button wifiBtn = button("Wi-Fi", RAISED, WHITE, RAISED);
        Button address = button("Change IP", RAISED, WHITE, RAISED);
        addButton(wifiButtons, wifiBtn, 0, 4);
        addButton(wifiButtons, address, 4, 0);
        wifiCard.addView(wifiButtons);
        wifiBtn.setOnClickListener(v -> openWifiSettings());
        address.setOnClickListener(v -> changeHost());
        space(wifiCard, 9);
        Button disconnectButton = button(connected ? "Disconnect camera" : "Camera disconnected", CARD, connected ? RED : MUTED, RAISED);
        disconnectButton.setEnabled(connected);
        wifiCard.addView(disconnectButton);
        disconnectButton.setOnClickListener(v -> disconnect());
        space(wifiCard, 9);
        Button notificationControl = button("Connection notification settings", RAISED, WHITE, RAISED);
        notificationControl.setTextSize(12);
        wifiCard.addView(notificationControl);
        notificationControl.setOnClickListener(v -> {
            try {
                Intent config = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                config.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(config);
            } catch (Exception e) {
                Intent config = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
                startActivity(config);
            }
        });
        LinearLayout support = card(page);
        support.addView(text("Help improve the app", 18, WHITE, true));
        space(support, 6);
        support.addView(text("If the preview freezes, open diagnostics and export a report.", 12, MUTED, false));
        space(support, 10);
        Button diagnostics = button("Diagnostics & export logs  ›", RAISED, MINT, MINT);
        support.addView(diagnostics);
        diagnostics.setOnClickListener(v -> selectTab(3));

    }

    private void showDiagnostics() {
        Button back = button("‹ Back to settings", RAISED, MINT, RAISED);
        page.addView(back);
        back.setOnClickListener(v -> selectTab(2));
        space(page, 10);
        LinearLayout diagnosticCard = card(page);
        diagnosticCard.addView(text("Diagnostics & feedback", 22, WHITE, true));
        space(diagnosticCard, 7);
        diagnosticCard.addView(text("The app stores recent errors and actions on this phone. Export a text report and send it back when something misbehaves.", 13, MUTED, false));
        space(diagnosticCard, 12);
        Button export = button("↓ Export debug report (.txt)", MINT, BG, MINT);
        diagnosticCard.addView(export);
        export.setOnClickListener(v -> exportDebugReport());
        space(diagnosticCard, 9);
        Button refresh = button("↻ Refresh visible log", RAISED, WHITE, RAISED);
        diagnosticCard.addView(refresh);
        refresh.setOnClickListener(v -> {
            if (diagnosticsPreview != null) diagnosticsPreview.setText(DebugLog.lastLines(35));
        });
        space(diagnosticCard, 15);
        diagnosticCard.addView(text("RECENT EVENTS", 12, MINT, true));
        space(diagnosticCard, 7);
        diagnosticsPreview = text(DebugLog.lastLines(35), 11, MUTED, false);
        diagnosticsPreview.setTextIsSelectable(true);
        diagnosticCard.addView(diagnosticsPreview);
        space(page, 8);
        addEmpty(page, "Privacy: reports include your phone model, Android version, camera IP, and error details. Review them before sharing. Passwords and serial numbers are not intentionally collected.");
    }

    private void inspectCamera() {
        if (!ready()) return;
        postStatus("Reading camera information …");
        controlIO.execute(() -> {
            try {
                JSONObject reply = camera.getSettings();
                lastCameraInfo = CameraInfo.detail(CameraInfo.settings(reply));
                DebugLog.info("Camera inspection successful; software/hardware fields retrieved");
                postStatus("Camera inspection complete.");
                runOnUiThread(() -> { if (cameraInfoContent != null) cameraInfoContent.setText(lastCameraInfo); });
            } catch (Exception e) { error("Inspect camera", e); }
        });
    }

    private void beginScan() {
        if (!ready() || scanning) return;
        new AlertDialog.Builder(this).setTitle("Inspect camera capabilities?")
            .setMessage("This performs a read-only scan of camera settings and supported choices, plus a read-only RTSP stream description. It may take around 15 seconds. No settings will be changed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Scan safely", (d, w) -> {
                scanning = true;
                postStatus("Scanning supported functions (read-only)…");
                controlIO.execute(() -> {
                    JSONObject results = new JSONObject();
                    try {
                        JSONObject settingsResponse = camera.getSettings();
                        java.util.LinkedHashMap<String, String> all = CameraInfo.settings(settingsResponse);
                        results.put("scan_mode", "read_only");
                        results.put("camera_information", CameraInfo.detail(all));
                        results.put("settings", CameraScan.publicSettings(all));
                        JSONObject options = new JSONObject();
                        int tried = 0, succeeded = 0;
                        for (String key : all.keySet()) {
                            if (!connected || !camera.isOpen()) throw new Exception("Camera disconnected during scan; partial results saved");
                            if (!CameraScan.safeChoiceKey(key) || tried >= 45) continue;
                            tried++;
                            try {
                                JSONObject reply = camera.getChoices(key);
                                options.put(key, CameraScan.sanitizedChoices(reply));
                                succeeded++;
                            } catch (Exception e) { DebugLog.warn("Scan choices " + key + ": " + e.getClass().getSimpleName()); }
                            try { Thread.sleep(140); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        }
                        results.put("choices", options);
                        results.put("choice_queries", tried);
                        results.put("choice_responses", succeeded);
                        DebugLog.info("Read-only scan: " + all.size() + " settings; " + succeeded + "/" + tried + " choice responses");
                        try { results.put("rtsp_description", RtspDiagnostics.inspect(wifi, host)); }
                        catch (Exception rtsp) { results.put("rtsp_error", rtsp.getClass().getSimpleName() + ": " + rtsp.getMessage()); }
                        lastScan = "YI 4K+ Companion capability scan\nFirmware read-only / no writes\n\n" + results.toString(2);
                        postStatus("Scan complete. Export the report to share the results.");
                        final int settingsCount = all.size();
                        final int inspectedCount = succeeded;
                        runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Camera scan complete")
                            .setMessage("Found " + settingsCount + " settings and inspected " + inspectedCount + " supported-choice responses. You can export the report for development and troubleshooting.")
                            .setNegativeButton("Close", null)
                            .setPositiveButton("Export report", (x, y) -> exportScan()).show());
                    } catch (Exception e) {
                        DebugLog.error("Read-only camera scan", e);
                        lastScan = "Partial YI camera scan\n" + results.toString();
                        error("Camera scan", e);
                    } finally { scanning = false; }
                });
            }).show();
    }

    private void exportScan() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "YI-Camera-Capabilities-" + System.currentTimeMillis() + ".txt");
            startActivityForResult(intent, EXPORT_SCAN);
        } catch (Exception e) { error("Export capability scan", e); }
    }

    private void exportDebugReport() {
        try {
            DebugLog.info("User requested diagnostic export");
            Intent export = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            export.addCategory(Intent.CATEGORY_OPENABLE);
            export.setType("text/plain");
            export.putExtra(Intent.EXTRA_TITLE, "YI-Companion-Debug-" + System.currentTimeMillis() + ".txt");
            startActivityForResult(export, EXPORT_REPORT);
        } catch (Exception e) { error("Export debug report", e); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != EXPORT_REPORT && requestCode != EXPORT_SCAN) ||
            resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new Exception("Android did not provide a writable location");
            String content = requestCode == EXPORT_SCAN ? lastScan :
                DebugLog.report(this, host, connected, lastCameraInfo);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            postStatus("Report saved. Attach the .txt file to your message.");
            new AlertDialog.Builder(this).setTitle("Report saved")
                .setMessage("Find the saved .txt report in the folder you selected, then attach it to our conversation.")
                .setPositiveButton("OK", null).show();
        } catch (Exception e) { error("Write debug report", e); }
    }

    private void addEmpty(LinearLayout root, String title) {
        LinearLayout empty = card(root);
        empty.setPadding(dp(20), dp(26), dp(20), dp(26));
        TextView message = text(title, 14, MUTED, false);
        message.setGravity(Gravity.CENTER);
        empty.addView(message);
    }

    private void postStatus(String message) {
        if (!message.startsWith("Downloading ") || !message.contains("%")) DebugLog.info(message);
        runOnUiThread(() -> { if (!destroyed && !isFinishing() && statusText != null) statusText.setText(message); });
    }

    private void error(String action, Exception error) {
        DebugLog.error(action, error);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            statusText.setText(action + " failed: " + error.getMessage());
            new AlertDialog.Builder(this).setTitle(action + " failed")
                .setMessage(error.getMessage() + "\n\nMake sure your phone stays connected to the Yi camera's Wi-Fi, even when Android says 'no internet'.")
                .setPositiveButton("OK", null).show();
        });
    }

    private Network findWifi(int generation, String requestedHost) throws Exception {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) throw new IOException("Android connectivity service unavailable");
        for (Network candidate : cm.getAllNetworks()) {
            NetworkCapabilities cap = cm.getNetworkCapabilities(candidate);
            if (cap == null || !cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            LinkProperties properties = cm.getLinkProperties(candidate);
            if (properties == null) continue;
            boolean matches = false;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (WifiRoutes.contains(address.getAddress().getHostAddress(), address.getPrefixLength(), requestedHost)) {
                    matches = true; break;
                }
            }
            if (!matches) continue;
            synchronized (connectionLock) {
                if (!currentIntent(generation)) throw new IOException("Connection request cancelled");
                if (!processBindingOwned) {
                    priorProcessBinding = cm.getBoundNetworkForProcess();
                    processBindingOwned = true;
                }
                if (!cm.bindProcessToNetwork(candidate)) throw new IOException("Android could not route traffic over camera Wi-Fi");
                wifi = candidate;
                boundCameraNetwork = candidate;
            }
            DebugLog.info("Using camera Wi-Fi network " + candidate + " with its actual IP prefix; mobile data is not used");
            return candidate;
        }
        throw new IOException("Camera Wi-Fi is not ready. Join the camera network and choose Stay connected if Android reports no internet.");
    }

    private void restoreProcessBinding() {
        synchronized (connectionLock) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null && processBindingOwned && boundCameraNetwork != null &&
                    boundCameraNetwork.equals(cm.getBoundNetworkForProcess())) {
                cm.bindProcessToNetwork(priorProcessBinding);
            }
            wifi = null;
            boundCameraNetwork = null;
            priorProcessBinding = null;
            processBindingOwned = false;
        }
    }

    private void connect() {
        final int generation;
        final YiCamera old;
        synchronized (connectionLock) {
            if (destroyed || connecting || connected) return;
            userDisconnected = false;
            generation = connectionGeneration.incrementAndGet();
            cancelReconnectLocked();
            reconnectAttempts = 0;
            connecting = true;
            connected = false;
            old = camera;
            camera = null;
        }
        if (old != null) old.close();
        stopPreview(true);
        startConnectionService(); // User initiated while Activity is visible; Android 12+ permits this.
        if (connectButton != null) { connectButton.setEnabled(true); connectButton.setText("Cancel connection"); }
        postStatus("Connecting to camera " + host + " …");
        controlIO.execute(() -> openCameraSession(generation, false));
    }

    private void openCameraSession(int generation, boolean automatic) {
        YiCamera replacement = null;
        try {
            if (!currentIntent(generation)) return;
            final String requestedHost = host;
            Network selected = findWifi(generation, requestedHost);
            replacement = new YiCamera(selected, requestedHost);
            synchronized (connectionLock) {
                if (!currentIntent(generation)) { replacement.close(); return; }
                camera = replacement; // Publish the opening socket so Disconnect can cancel it.
            }
            replacement.connect(() -> currentIntent(generation));
            if (!currentIntent(generation) || camera != replacement) { replacement.close(); return; }
            try {
                java.util.Map<String,String> settings = CameraInfo.settings(replacement.getSettings());
                if (!currentIntent(generation)) return;
                cameraSystemMode = settings.getOrDefault("system_mode", "unknown");
                cameraSubMode = settings.getOrDefault("record".equals(cameraSystemMode) ? "rec_mode" : "capture_mode", "unknown");
                cameraAppStatus = settings.getOrDefault("app_status", "unknown");
                if ("record".equals(cameraSystemMode) || "capture".equals(cameraSystemMode))
                    captureMode = CameraModes.fromCamera(cameraSystemMode, cameraSubMode);
                if (CameraModes.isClearlyRecording(cameraAppStatus)) { recording = true; recordingSince = 0; }
                else if (CameraModes.isClearlyIdle(cameraAppStatus)) { recording = false; recordingSince = 0; }
            } catch (Exception e) {
                if (!replacement.isOpen()) throw e;
                DebugLog.warn("Optional camera state read unavailable: " + shortError(e));
            }
            synchronized (connectionLock) {
                if (!currentIntent(generation) || camera != replacement) { replacement.close(); return; }
                connected = true;
                remoteViewfinderEnabled = false;
                batteryFailures = 0;
                reconnectAttempts = 0;
                lastMediaLoadedAt = 0;
                lastSettingsLoadedAt = 0;
            }
            DebugLog.info(automatic ? "Camera session restored; no capture command was replayed" : "Camera session connected");
            sessionUi(generation, () -> {
                if (!connected) return;
                postStatus(automatic ? "Camera reconnected automatically." : "Connected to YI camera.");
                if (connectionTag != null) { connectionTag.setText("● Connected"); connectionTag.setTextColor(MINT); }
                if (!automatic) offerConnectionNotificationPermission();
                updateModeUi(); updateCaptureUi();
                if (appForeground) selectTab(tab);
            });
        } catch (Exception e) {
            if (replacement != null) replacement.close();
            if (currentIntent(generation)) {
                synchronized (connectionLock) {
                    if (camera == replacement) camera = null;
                    connected = false;
                }
                DebugLog.warn((automatic ? "Reconnect" : "Connect") + " did not complete: " + shortError(e));
                sessionUi(generation, () -> {
                    if (connectionTag != null) connectionTag.setText("○ Reconnecting");
                    updateCaptureUi();
                    postStatus("Camera connection unavailable; retrying automatically. " + shortError(e));
                });
            }
        } finally {
            if (currentIntent(generation)) {
                connecting = false;
                sessionUi(generation, () -> { if (connectButton != null) connectButton.setEnabled(true); });
                if (!connected) scheduleReconnect(2);
            } else if (replacement != null && camera != replacement) replacement.close();
        }
    }

    private void cancelActiveTransfer() {
        MediaBrowser transfer = activeTransferBrowser;
        if (transfer != null) transfer.cancelTransfers();
    }

    private void disconnect() {
        final YiCamera old;
        synchronized (connectionLock) {
            userDisconnected = true;
            downloadGeneration.incrementAndGet();
            connectionGeneration.incrementAndGet();
            cancelReconnectLocked();
            reconnectAttempts = 0;
            connected = false;
            connecting = false;
            remoteViewfinderEnabled = false;
            old = camera;
            camera = null;
        }
        DebugLog.info("User disconnected; recording on the camera is not changed");
        if (old != null) old.close();
        cancelActiveTransfer();
        stopConnectionService();
        stopPreview(true);
        restoreProcessBinding();
        recording = false;
        recordingSince = 0;
        if (connectionTag != null) connectionTag.setText("○ Offline");
        if (batteryTag != null) batteryTag.setText("");
        if (batteryIcon != null) batteryIcon.setLevel(-1);
        storageSummary = "SD · —";
        storageCompact = "SD —";
        storageUsedPercent = -1;
        if (storageTag != null) storageTag.setText(storageCompact);
        if (storageMeter != null) storageMeter.setUsedPercent(-1);
        postStatus("Disconnected. Use the camera's shutter button to finish any ongoing recording.");
        selectTab(tab);
    }

    /** Uses documented Yi settings rather than treating the highlighted tab as camera state. */
    private void switchCaptureMode(int desired) {
        if (connecting) return;
        if (recording) { postStatus("Stop recording before changing camera modes."); return; }
        if (captureBusy) {
            // Replace any pending choice rather than firing overlapping commands.
            queuedMode = desired;
            postStatus("Switching camera… next: " + CameraModes.label(desired));
            return;
        }
        if (desired == captureMode) return;
        if (desired == CameraModes.LOOP && !loopModeAccepted) {
            new AlertDialog.Builder(this).setTitle("Loop recording can replace older clips")
                .setMessage("Loop mode overwrites earlier recordings when the SD card fills. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use loop mode", (dialog, which) -> {
                    loopModeAccepted = true; switchCaptureMode(CameraModes.LOOP);
                }).show();
            return;
        }
        if (!connected) {
            captureMode = desired;
            updateModeUi(); updateCaptureUi();
            postStatus("Connect to apply " + CameraModes.label(desired) + " on the active.");
            return;
        }
        if (!ready()) return;
        final YiCamera active = camera;
        final int generation = connectionGeneration.get();
        captureBusy = true;
        queuedMode = -1;
        updateModeUi();
        updateCaptureUi();
        stopPreview();
        postStatus("Switching camera to " + CameraModes.label(desired) + "…");
        controlIO.execute(() -> {
            if (!currentSession(generation, active)) { captureBusy = false; return; }
            try {
                String status = active.getSetting("app_status").optString("param", "unknown");
                cameraAppStatus = status;
                if (CameraModes.isClearlyRecording(status)) {
                    recording = true; recordingSince = 0;
                    throw new IllegalStateException("Camera is recording; stop it first");
                }
                CameraModes.apply(active, desired);
                cameraSystemMode = CameraModes.system(desired);
                cameraSubMode = CameraModes.subMode(desired);
                captureMode = desired;
                if (!currentSession(generation, active)) return;
                DebugLog.info("Verified camera mode " + cameraSystemMode + "/" + cameraSubMode);
                postStatus(CameraModes.label(desired) + " ready.");
            } catch (Exception e) {
                // Delayed hardware transitions can still finish after a request. Never
                // blindly retry a mode write on every tap: reconcile first.
                DebugLog.warn("Mode change still pending/failed: " + shortError(e));
                if (isTransportFailure(e)) connectionLost("change camera mode", e, active);
                postStatus("Camera did not confirm " + CameraModes.label(desired) + "; use its mode button if it stays stuck. Syncing actual mode…");
                try {
                    if (!currentSession(generation, active)) return;
                    String system = active.getSetting("system_mode").optString("param", "unknown");
                    String sub = active.getSetting("record".equals(system) ? "rec_mode" : "capture_mode")
                        .optString("param", "unknown");
                    cameraSystemMode = system;
                    cameraSubMode = sub;
                    if ("record".equals(system) || "capture".equals(system))
                        captureMode = CameraModes.fromCamera(system, sub);
                } catch (Exception readError) { DebugLog.warn("Mode recheck: " + readError.getMessage()); }
            } finally {
                captureBusy = false;
                sessionUi(generation, () -> {
                    updateModeUi(); updateCaptureUi();
                    int next = queuedMode;
                    queuedMode = -1;
                    if (next >= 0 && next != captureMode && !recording) switchCaptureMode(next);
                    else if (tab == 0 && connected && !previewStarted && appForeground) startPreview();
                });
            }
        });
    }

    private static String sizeLabel(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes >= 1073741824L) return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
        return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
    }

    private void refreshStorageOnControlThread() throws Exception {
        if (camera == null || !connected) return;
        long freeRaw = camera.sdSpace("free");
        long totalRaw;
        try { totalRaw = camera.sdSpace("total"); } catch (Exception ignored) { totalRaw = -1; }
        if (freeRaw < 0) return;
        // The user's 4K+ firmware reports about 30 million for a ~32 GB card:
        // evidently KiB, not bytes as in some earlier Yi protocol documentation.
        // Do not apply a multiplier to large raw byte counts on other firmwares.
        long scale = StorageStats.unitScale(freeRaw, totalRaw);
        long free = StorageStats.scaled(freeRaw, scale);
        long total = totalRaw > 0 ? StorageStats.scaled(totalRaw, scale) : -1;
        int used = StorageStats.usedPercent(free, total);
        storageUsedPercent = used;
        storageSummary = StorageStats.display(free, total);
        storageCompact = StorageStats.readable(free).replace(" GB", "GB").replace(" MB", "MB") +
            (used >= 0 ? " · " + used + "% Full" : " free");
        final String label = storageSummary;
        final String shortLabel = storageCompact;
        DebugLog.info("Camera SD raw free=" + freeRaw + " total=" + totalRaw +
            " scale=" + scale + " => " + label);
        runOnUiThread(() -> {
            if (storageTag != null && connected) storageTag.setText(shortLabel);
            if (storageTag != null) storageTag.setContentDescription(label);
            if (storageMeter != null && connected) storageMeter.setUsedPercent(used);
        });
    }

    private boolean ready() {
        YiCamera active = camera;
        if (connected && active != null && active.isOpen()) return true;
        if (connected && !userDisconnected) connectionLost("control availability", new IOException("Camera control socket closed"), active);
        if (!userDisconnected) {
            scheduleReconnect(1);
            postStatus("Camera connection interrupted; reconnecting automatically…");
        } else postStatus("Connect to the camera first.");
        return false;
    }

    private void performCommand(String label, int msgId, boolean starting) {
        if (!ready() || captureBusy) return;
        if (msgId == 513 && recording) { postStatus("Already recording."); return; }
        if (msgId == 769 && recording) { postStatus("Stop video recording before taking photos."); return; }
        if (msgId == 514 && !recording) { postStatus("Not currently recording."); return; }
        if (msgId == 769 && !CameraModes.isPhoto(captureMode)) return;
        if (msgId == 513 && CameraModes.isPhoto(captureMode)) return;
        captureBusy = true;
        updateCaptureUi();
        postStatus(label + " …");
        final int requestedMode = captureMode;
        final YiCamera active = camera;
        final int generation = connectionGeneration.get();
        controlIO.execute(() -> {
            if (!currentSession(generation, active)) { captureBusy = false; return; }
            try {
                if (msgId != 514) {
                    // Even if a camera's hardware buttons changed mode externally, verify it
                    // immediately before capture instead of trusting the UI toggle.
                    String appState = active.getSetting("app_status").optString("param", "unknown");
                    cameraAppStatus = appState;
                    if (CameraModes.isClearlyRecording(appState)) {
                        recording = true; recordingSince = 0;
                        throw new java.io.IOException("Camera is already recording. Stop it before changing mode or taking a picture.");
                    }
                    CameraModes.apply(active, requestedMode);
                    cameraSystemMode = CameraModes.system(requestedMode);
                    cameraSubMode = CameraModes.subMode(requestedMode);
                }
                try { active.request(msgId); }
                catch (java.io.IOException transientError) {
                    if (msgId != 514 || !String.valueOf(transientError.getMessage()).contains("code -21")) throw transientError;
                    DebugLog.warn("Stop recording busy (-21), waiting 900 ms before a single retry");
                    Thread.sleep(900);
                    active.request(msgId);
                }
                if (!currentSession(generation, active)) return;
                if (msgId == 513) {
                    recording = true; recordingSince = SystemClock.elapsedRealtime();
                    lastMediaLoadedAt = 0;
                }
                if (msgId == 514) {
                    recording = false; recordingSince = 0;
                    videoSizeRefreshScans = Math.max(videoSizeRefreshScans, 4);
                    lastMediaLoadedAt = 0;
                }
                DebugLog.info("Camera mode/status: " + cameraSystemMode + "/" + cameraSubMode +
                    ", record=" + recording + ", command=" + msgId);
                postStatus(label + " confirmed by camera." + (recording ? " ● REC" : ""));
                if (msgId == 513 || msgId == 514) {
                    sessionUi(generation, () -> {
                        final int current = previewCycle;
                        // Recording may interrupt RTSP; restart decoder only, no
                        // viewfinder stop/start while recording is active.
                        if (tab == 0 && appForeground && previewStarted)
                            handler.postDelayed(() -> {
                                if (tab == 0 && appForeground && previewStarted && previewCycle == current)
                                    startVlcAttempt(current);
                            }, msgId == 513 ? 1500 : 1200);
                    });
                }
                if (msgId == 514 || msgId == 769) {
                    lastMediaLoadedAt = 0;
                    try { refreshStorageOnControlThread(); }
                    catch (Exception e) { DebugLog.warn("SD space after capture: " + e.getMessage()); }
                }
            } catch (Exception e) {
                if (isTransportFailure(e)) connectionLost(label, e, active);
                else error(label, e);
                // When a command fails, don't flip the local REC indicator optimistically.
                // Read the camera status before allowing another attempt.
                try {
                    if (!currentSession(generation, active)) return;
                    String actual = active.getSetting("app_status").optString("param", "unknown");
                    cameraAppStatus = actual;
                    if (CameraModes.isClearlyRecording(actual)) {
                        recording = true;
                        if (recordingSince <= 0) recordingSince = 0;
                    } else if (CameraModes.isClearlyIdle(actual)) {
                        recording = false; recordingSince = 0;
                    }
                    DebugLog.warn("Reconciled camera app_status after failed command: " + actual);
                } catch (Exception readError) {
                    DebugLog.warn("Could not reconcile camera state: " + readError.getMessage());
                }
            } finally {
                captureBusy = false;
                sessionUi(generation, this::updateCaptureUi);
            }
        });
    }

    private void startPreview() {
        if (!ready() || liveFrame == null || previewStarted || tab != 0 || !appForeground) return;
        postStatus("Starting live view…");
        previewStarted = true;
        previewReady = false;
        previewRetries = 0;
        previewRecoveries = 0;
        final int cycle = ++previewCycle;
        final YiCamera active = camera;
        final int generation = connectionGeneration.get();
        controlIO.execute(() -> {
            if (!currentSession(generation, active)) return;
            try {
                if (!recording && !remoteViewfinderEnabled && cameraIdleForViewfinder(active, generation)) {
                    active.startViewfinder();
                    remoteViewfinderEnabled = true;
                    DebugLog.info("Remote viewfinder enabled");
                } else if (recording) {
                    DebugLog.info("Recording active: RTSP player only, no viewfinder start command");
                }
                if (previewCycle != cycle || !appForeground || !connected) {
                    if (!recording && remoteViewfinderEnabled && cameraIdleForViewfinder(active, generation)) {
                        try { active.stopViewfinder(); }
                        catch (Exception e) { DebugLog.warn("Deferred viewfinder stop: " + e.getMessage()); }
                        remoteViewfinderEnabled = false;
                    }
                    return;
                }
                DebugLog.info("VLC playback engine active");
                sessionUi(generation, () -> {
                    if (previewCycle != cycle || tab != 0 || !connected) return;
                    startVlcAttempt(cycle);
                });
            } catch (Exception e) {
                if (!currentIntent(generation) || camera != active) return;
                previewStarted = false;
                remoteViewfinderEnabled = false;
                DebugLog.warn("Start viewfinder unavailable: " + shortError(e));
                if (isTransportFailure(e)) {
                    connectionLost("start viewfinder", e, active);
                    postStatus("Reconnecting camera control and preview…");
                } else postStatus("Preview is unavailable. Recording controls may still work.");
                sessionUi(generation, () -> {
                    if (previewHint != null) {
                        previewHint.setText("Preview paused\nTap Live view to retry");
                        previewHint.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void startVlcAttempt(int cycle) {
        if (destroyed || !connected || !appForeground || previewCycle != cycle || !previewStarted || tab != 0 || liveFrame == null) return;
        final YiCamera active = camera;
        final int generation = connectionGeneration.get();
        try {
            if (previewHint != null) { previewHint.setText("Connecting to live feed…"); previewHint.setVisibility(View.VISIBLE); }
            DebugLog.info("VLC RTSP attempt " + (previewRetries + 1) + ": rtsp://" + host + "/live");
            vlc.play(liveFrame, "rtsp://" + host + "/live", new VlcPreview.Listener() {
                @Override public void playing() {
                    if (previewCycle != cycle || !previewStarted) return;
                    previewReady = true;
                    previewRetries = 0;
                    if (previewHint != null) previewHint.setVisibility(View.GONE);
                    postStatus("Live view connected");
                }
                @Override public void failed() {
                    if (previewCycle != cycle || !previewStarted) return;
                    DebugLog.warn("VLC playback error; attempt " + (previewRetries + 1));
                    if (++previewRetries <= 2) {
                        postStatus("Video interrupted; retrying…");
                        handler.postDelayed(() -> startVlcAttempt(cycle), 800L * previewRetries);
                    } else if (!recording && appForeground && previewRecoveries < 1) {
                        previewRecoveries++;
                        final int newCycle = ++previewCycle;
                        previewRetries = 0;
                        postStatus("Restoring camera video connection…");
                        DebugLog.warn("VLC retries exhausted: one camera-side recovery while idle");
                        controlIO.execute(() -> {
                            if (!currentSession(generation, active) || recording || !appForeground || tab != 0 || newCycle != previewCycle) return;
                            try {
                                if (!cameraIdleForViewfinder(active, generation)) {
                                    sessionUi(generation, () -> { if (newCycle == previewCycle) startVlcAttempt(newCycle); });
                                    return;
                                }
                                if (remoteViewfinderEnabled) {
                                    active.stopViewfinder();
                                    remoteViewfinderEnabled = false;
                                }
                                Thread.sleep(350);
                                if (recording || !connected || !appForeground || newCycle != previewCycle) return;
                                if (!cameraIdleForViewfinder(active, generation)) return;
                                active.startViewfinder();
                                remoteViewfinderEnabled = true;
                                sessionUi(generation, () -> { if (newCycle == previewCycle) startVlcAttempt(newCycle); });
                            } catch (Exception e) {
                                DebugLog.error("Camera stream recovery", e);
                                sessionUi(generation, () -> {
                                    if (previewHint != null) {
                                        previewHint.setText("Stream unavailable\nTap Restart to try again");
                                        previewHint.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                        });
                    } else {
                        postStatus(recording ? "Camera is recording; preview paused. Recording continues." :
                            "Video unavailable. Tap Restart to try again; controls still work.");
                        if (previewHint != null) {
                            previewHint.setText(recording ? "Recording continues on camera\nPreview paused" :
                                "Video unavailable\nTap Restart to try again");
                            previewHint.setVisibility(View.VISIBLE);
                        }
                        // Never toggle the remote viewfinder during a recording.
                    }
                }
            });
        } catch (Exception e) { error("VLC live preview", e); }
    }

    private boolean cameraIdleForViewfinder(YiCamera active, int generation) throws Exception {
        if (!currentSession(generation, active) || recording) return false;
        String state;
        try { state = active.getSetting("app_status").optString("param", "unknown"); }
        catch (YiCamera.CameraRejectedException unsupported) {
            DebugLog.warn("Camera status unavailable; preserving remote viewfinder state");
            return false;
        }
        if (!currentSession(generation, active)) return false;
        cameraAppStatus = state;
        if (CameraModes.isClearlyRecording(state)) {
            recording = true;
            recordingSince = 0;
            sessionUi(generation, this::updateCaptureUi);
            return false;
        }
        if (!CameraModes.isClearlyIdle(state)) {
            DebugLog.info("Preserving camera viewfinder while app_status=" + state);
            return false;
        }
        return true;
    }

    private void stopPreview() { stopPreview(false); }

    private void stopPreview(boolean preserveRemote) {
        ++previewCycle;
        previewReady = false;
        if (vlc != null) vlc.stop();
        if (previewHint != null) {
            previewHint.setText("Live view paused");
            previewHint.setVisibility(View.VISIBLE);
        }
        boolean wasStarted = previewStarted;
        previewStarted = false;
        if (wasStarted) {
            DebugLog.info("Local RTSP preview stopped; recording=" + recording + ", foreground=" + appForeground);
            if (preserveRemote) {
                DebugLog.info("Camera viewfinder preserved during background / reconnection; only local VLC stopped");
            } else if (!recording && remoteViewfinderEnabled) {
                remoteViewfinderEnabled = false;
                final YiCamera active = camera;
                final int generation = connectionGeneration.get();
                controlIO.execute(() -> {
                    if (currentSession(generation, active) && !recording) {
                        try {
                            if (cameraIdleForViewfinder(active, generation)) active.stopViewfinder();
                        }
                        catch (Exception e) {
                            DebugLog.warn("Stop viewfinder: " + shortError(e));
                            if (isTransportFailure(e)) connectionLost("stop viewfinder", e, active);
                        }
                    } else DebugLog.info("Skipped viewfinder stop: recording/connection changed");
                });
            } else if (recording) {
                DebugLog.info("Kept camera viewfinder state intact while recording; decoder stopped only");
            }
        }
    }

    private void loadMedia() {
        if (!ready() || mediaLoading || !appForeground || tab != 1 || downloadQueueLength.get() > 0) return;
        final int generation = connectionGeneration.get();
        final Network mediaNetwork = wifi;
        final String mediaHost = host;
        mediaLoading = true;
        // No modal error for empty/temporarily unavailable directory; preserve last known list.
        mediaIO.execute(() -> {
            try {
                if (!currentIntent(generation) || !connected || downloadQueueLength.get() > 0) return;
                List<MediaBrowser.MediaFile> list = new MediaBrowser(mediaNetwork, mediaHost).browse();
                if (!currentIntent(generation)) return;
                DebugLog.info("HTTP gallery sync: " + list.size() + " camera files");
                galleryRetryNeeded = false;
                mediaLoaded(list, generation);
            } catch (Exception httpError) {
                mediaLoading = false;
                galleryRetryNeeded = true;
                DebugLog.warn("Gallery HTTP temporarily unavailable: " + shortError(httpError));
                // The HTTP file service may stop independently from the control port.
                // An HTTP failure alone must not reset a healthy control socket.
                sessionUi(generation, () -> {
                    if (tab != 1) return;
                    if (!mediaFiles.isEmpty()) {
                        postStatus("Camera gallery temporarily unavailable; showing last synced files.");
                    } else {
                        postStatus("Could not reach camera gallery; will retry automatically.");
                        if (galleryItems != null) {
                            galleryItems.removeAllViews();
                            addEmpty(galleryItems, "Camera gallery temporarily unavailable.\nChecking connection and retrying…");
                        }
                    }
                });
            } finally {
                mediaLoading = false;
            }
        });
    }

    private void mediaLoaded(List<MediaBrowser.MediaFile> files, int generation) {
        mediaLoading = false;
        lastMediaLoadedAt = SystemClock.elapsedRealtime();
        sessionUi(generation, () -> {
            boolean changed = mediaFiles.size() != files.size();
            if (!changed) {
                for (int i = 0; i < files.size(); i++) {
                    if (!mediaFiles.get(i).location.equals(files.get(i).location)) { changed = true; break; }
                }
            }
            boolean firstEmptySync = files.isEmpty() && !galleryKnownEmpty;
            galleryKnownEmpty = files.isEmpty();
            boolean recordingChanged = galleryWasRecording != recording;
            galleryWasRecording = recording;
            if (changed) {
                mediaFiles.clear();
                mediaFiles.addAll(files);
                metadataCache.clear(); // new/removed camera file can reuse an old filename
                DebugLog.info("Gallery changed: " + files.size() + " file(s); refreshing thumbnails");
            }
            // A video can enter the HTTP listing during recording, and its size will
            // continue to change without any filename change. Do not cache that early size.
            if (recording) {
                // Conservative: without a filename-specific event, we cannot identify which
                // MP4 is being written, so *all* remote video downloads are temporarily blocked.
                for (MediaBrowser.MediaFile file : files) if (isVideo(file))
                    videosSeenRecording.add(file.location.toString());
            }
            if (recordingChanged) {
                // Ensure the "Recording" button and video badge return to their normal state
                // immediately after the camera confirms it is idle.
                for (MediaBrowser.MediaFile file : files)
                    if (isVideo(file)) metadataCache.remove(file.location.toString());
                if (!recording) videoSizeRefreshScans = Math.max(videoSizeRefreshScans, 4);
            }
            statusText.setText(files.isEmpty() ? "No photos or videos on the camera" :
                "Gallery synced • " + files.size() + " camera files" +
                    (recording ? " · Camera recording" : ""));
            if (tab == 1 && (changed || firstEmptySync || recordingChanged)) renderMedia();
            else if (tab == 1 && (recording || videoSizeRefreshScans > 0 || ++gallerySyncsSinceVideoRecheck >= 6)) {
                gallerySyncsSinceVideoRecheck = 0;
                refreshVisibleVideoDetails();
            }
            if (!recording && videoSizeRefreshScans > 0) {
                videoSizeRefreshScans--;
                if (videoSizeRefreshScans == 0) videosSeenRecording.clear();
            }
        });
    }

    private static boolean isVideo(MediaBrowser.MediaFile file) {
        String lower = file.name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mov");
    }

    /** Selection is restricted to phone copies; the camera's originals are never deleted. */
    private void toggleRemoteSelection(MediaBrowser.MediaFile item) {
        selectionMode = true;
        if (gallerySelectButton != null) gallerySelectButton.setText("Cancel");
        String key = item.location.toString();
        if (!selectedRemote.add(key)) selectedRemote.remove(key);
        renderMedia();
    }

    private void toggleLocalSelection(LocalMediaIndex.SavedItem item) {
        selectionMode = true;
        if (gallerySelectButton != null) gallerySelectButton.setText("Cancel");
        String key = item.uri.toString();
        if (!selectedLocal.add(key)) selectedLocal.remove(key);
        renderLocalDownloads();
    }

    private void finishSelection() {
        selectionMode = false;
        if (gallerySelectButton != null) gallerySelectButton.setText("Select");
        selectedRemote.clear();
        selectedLocal.clear();
        if (connected) renderMedia(); else renderLocalDownloads();
    }

    private void addBulkToolbar(boolean local) {
        if (!selectionMode || galleryItems == null) return;
        final int selectedCount = local ? selectedLocal.size() : selectedRemote.size();
        LinearLayout selectionRow = vertical();
        selectionRow.setPadding(dp(10), dp(10), dp(10), dp(12));
        selectionRow.setBackground(shape(CARD, 12, RAISED, 1));
        selectionRow.addView(text(selectedCount + " selected  ·  Tap photos to add/remove", 12, MINT, true));
        LinearLayout actions = horizontal();
        if (!local) {
            Button downloadMany = button("↓ Download", RAISED, MINT, RAISED);
            downloadMany.setEnabled(selectedCount > 0);
            addButton(actions, downloadMany, 0, 4);
            downloadMany.setOnClickListener(v -> {
                java.util.Set<String> keys = new java.util.HashSet<>(selectedRemote);
                int queued = 0;
                int videosDeferred = 0;
                for (MediaBrowser.MediaFile item : mediaFiles) {
                    if (!keys.contains(item.location.toString()) || savedMedia.find(this, item) != null) continue;
                    if (recording && isVideo(item)) {
                        videosDeferred++;
                        continue;
                    }
                    download(item, null);
                    queued++;
                }
                finishSelection();
                postStatus(queued + " download(s) queued" + (videosDeferred > 0 ?
                    "; " + videosDeferred + " video(s) deferred until recording finishes" : ""));
            });
        }
        Button deleteMany = button("Delete phone copies", RAISED, RED, RAISED);
        deleteMany.setEnabled(selectedCount > 0);
        addButton(actions, deleteMany, 4, 4);
        deleteMany.setOnClickListener(v -> confirmBulkDelete(local));
        Button cancel = button("✕", RAISED, MUTED, RAISED);
        actions.addView(cancel, new LinearLayout.LayoutParams(dp(45), dp(38)));
        cancel.setOnClickListener(v -> finishSelection());
        space(selectionRow, 9);
        selectionRow.addView(actions);
        galleryItems.addView(selectionRow, match());
        space(galleryItems, 10);
    }

    private void confirmBulkDelete(boolean local) {
        java.util.Set<String> keys = new java.util.HashSet<>(local ? selectedLocal : selectedRemote);
        if (keys.isEmpty()) return;
        new AlertDialog.Builder(this)
            .setTitle("Delete selected phone copies?")
            .setMessage("Delete up to " + keys.size() + " downloaded file(s) from this phone? Nothing will be deleted from the camera SD card.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete phone copies", (dialog, which) -> {
                finishSelection();
                postStatus("Deleting selected phone copies…");
                fileIO.execute(() -> {
                    int deleted = 0;
                    int failed = 0;
                    if (local) {
                        for (LocalMediaIndex.SavedItem item : savedMedia.listLocal(this)) {
                            if (!keys.contains(item.uri.toString())) continue;
                            try {
                                if (getContentResolver().delete(item.uri, null, null) > 0) deleted++;
                                else failed++;
                            } catch (Exception e) { failed++; DebugLog.warn("Delete " + item.name + ": " + e.getMessage()); }
                        }
                    } else {
                        for (MediaBrowser.MediaFile item : new ArrayList<>(mediaFiles)) {
                            if (!keys.contains(item.location.toString())) continue;
                            try {
                                if (savedMedia.find(this, item) != null) {
                                    savedMedia.delete(this, item);
                                    metadataCache.remove(item.location.toString());
                                    deleted++;
                                }
                            } catch (Exception e) { failed++; DebugLog.warn("Delete " + item.name + ": " + e.getMessage()); }
                        }
                    }
                    final int complete = deleted;
                    final int errors = failed;
                    runOnUiThread(() -> {
                        postStatus("Deleted " + complete + " phone copy(s)" + (errors > 0 ? "; " + errors + " could not be deleted" : ""));
                        if (tab == 1) { if (connected) renderMedia(); else renderLocalDownloads(); }
                    });
                });
            }).show();
    }

    private void loadRemoteDetails(MediaBrowser.MediaFile item, Uri local, boolean video, TextView target) {
        String key = item.location.toString();
        if (video && local == null) {
            remoteVideoDetails.put(key, target);
        }
        String cached = metadataCache.get(key);
        if (cached != null && !(video && local == null && recording)) {
            target.setText(cached);
            return;
        }
        target.setText(video && local == null && recording ?
            "Camera recording · size is not final" : "Checking file size…");
        final int generation = connectionGeneration.get();
        final Network mediaNetwork = wifi;
        final String mediaHost = host;
        metadataIO.execute(() -> {
            if (destroyed || Thread.currentThread().isInterrupted() || target.getParent() == null) return;
            if (local == null && (!currentIntent(generation) || tab != 1 || downloadQueueLength.get() > 0)) return;
            boolean isRecording = recording;
            String detail = local != null ? MediaDetails.local(this, local, video)
                : MediaDetails.combine(MediaDetails.remoteSize(new MediaBrowser(mediaNetwork, mediaHost), item), -1, video)
                    + (video ? (isRecording ? " · Recording (size not final)" : " · Length after download") : "");
            // Never freeze the first observed size of an in-progress recording.
            if (!(video && local == null && isRecording)) metadataCache.put(key, detail);
            runOnUiThread(() -> {
                if (!destroyed && (local != null || currentIntent(generation)) && target.getParent() != null &&
                    (!video || local != null || remoteVideoDetails.get(key) == target)) target.setText(detail);
            });
        });
    }

    /** Refresh only a bounded set of visible video sizes, not every file on the SD card. */
    private void refreshVisibleVideoDetails() {
        int refreshed = 0;
        for (MediaBrowser.MediaFile file : mediaFiles) {
            if (!isVideo(file)) continue;
            String key = file.location.toString();
            TextView field = remoteVideoDetails.get(key);
            if (field == null || field.getParent() == null) continue;
            if (savedMedia.find(this, file) != null) continue;
            metadataCache.remove(key);
            loadRemoteDetails(file, null, true, field);
            if (++refreshed >= 6) break; // Avoid overloading camera HTTP while syncing gallery.
        }
    }

    private void renderMedia() {
        if (galleryItems == null) return;
        if (!connected) { renderLocalDownloads(); return; }
        remoteVideoDetails.clear();
        galleryItems.removeAllViews();
        addBulkToolbar(false);
        ArrayList<MediaBrowser.MediaFile> filtered = new ArrayList<>();
        for (MediaBrowser.MediaFile file : mediaFiles) {
            if (mediaFilter == 0 || (mediaFilter == 2) == isVideo(file)) filtered.add(file);
        }
        if (filtered.isEmpty()) {
            addEmpty(galleryItems, mediaFilter == 1 ? "No photos on the camera" :
                mediaFilter == 2 ? "No videos on the camera" : "No photos or videos on the camera");
            return;
        }
        int count = Math.min(galleryVisible, filtered.size());
        galleryItems.addView(text(count + " of " + filtered.size() + " items", 11, MUTED, true));
        space(galleryItems, 10);
        LinearLayout row = null;
        for (int i = 0; i < count; i++) {
            if (i % 2 == 0) {
                row = horizontal();
                row.setGravity(Gravity.TOP);
                galleryItems.addView(row, match());
            }
            final MediaBrowser.MediaFile file = filtered.get(i);
            final boolean video = isVideo(file);
            final Uri downloaded = savedMedia.find(this, file);
            LinearLayout tile = vertical();
            tile.setPadding(dp(8), dp(8), dp(8), dp(10));
            tile.setBackground(shape(CARD, 16, RAISED, 1));
            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(0, -2, 1);
            tileLp.setMargins(i % 2 == 0 ? 0 : dp(5), 0, i % 2 == 0 ? dp(5) : 0, dp(10));
            row.addView(tile, tileLp);
            FrameLayout picture = new FrameLayout(this);
            picture.setBackground(shape(RAISED, 12, RAISED, 0));
            picture.setClipToOutline(true);
            tile.addView(picture, new LinearLayout.LayoutParams(-1, dp(128)));
            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setTag(file.location.toString());
            picture.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
            TextView placeholder = text(video ? "▶" : "▧", 32, MINT, true);
            placeholder.setGravity(Gravity.CENTER);
            picture.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            if (video) {
                TextView videoBadge = text(recording && downloaded == null &&
                    videosSeenRecording.contains(file.location.toString()) ? " ● REC ": " VIDEO ", 10, WHITE, true);
                videoBadge.setPadding(dp(7), dp(4), dp(7), dp(4));
                videoBadge.setBackground(shape(0xBA101821, 9, 0xBA101821, 0));
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.TOP);
                badgeParams.setMargins(0, dp(8), dp(8), 0);
                picture.addView(videoBadge, badgeParams);
            }
            TextView checkmark = text(selectedRemote.contains(file.location.toString()) ? "☑" : "☐", 25, MINT, true);
            checkmark.setBackground(shape(0xC9101821, 8, 0xC9101821, 0));
            FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(dp(39), dp(39), Gravity.TOP | Gravity.LEFT);
            checkLp.setMargins(dp(5), dp(5), 0, 0);
            checkmark.setGravity(Gravity.CENTER);
            checkmark.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            picture.addView(checkmark, checkLp);
            picture.setOnLongClickListener(v -> { toggleRemoteSelection(file); return true; });
            picture.setOnClickListener(v -> {
                if (selectionMode) toggleRemoteSelection(file);
                else if (video && recording && downloaded == null) postStatus("Video is still being recorded. Wait until the camera finishes before opening it.");
                else openMediaPreview(file);
            });
            space(tile, 8);
            TextView filename = text(file.name, 12, WHITE, true);
            filename.setSingleLine(true);
            filename.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tile.addView(filename);
            TextView detail = text("Checking file size…", 11, MUTED, false);
            tile.addView(detail);
            loadRemoteDetails(file, downloaded, video, detail);
            space(tile, 7);
            DownloadState active = activeDownloads.get(file.location.toString());
            Button save = button(active == null ? (downloaded != null ? "✓ Open saved" :
                video && recording ? "● Recording · wait" : "↓ Download") : active.label(), RAISED, MINT, RAISED);
            save.setTextSize(12);
            if (downloaded == null && video && recording) save.setEnabled(false);
            if (active != null) {
                active.visibleButton = save;
                save.setEnabled(false);
            }
            tile.addView(save, new LinearLayout.LayoutParams(-1, dp(38)));
            save.setOnClickListener(v -> {
                Uri local = savedMedia.find(this, file);
                if (local != null) openSavedFile(file, local);
                else if (video && recording) postStatus("Recording in progress; download after the camera finishes.");
                else { save.setEnabled(false); download(file, save); }
            });
            if (downloaded != null) {
                Button actions = button("⋯  Saved file options", CARD, MUTED, RAISED);
                actions.setTextSize(11);
                tile.addView(actions, new LinearLayout.LayoutParams(-1, dp(33)));
                actions.setOnClickListener(v -> savedFileMenu(file, actions));
            }
            final int generation = connectionGeneration.get();
            final Network mediaNetwork = wifi;
            final String mediaHost = host;
            thumbnailIO.execute(() -> {
                if (destroyed || Thread.currentThread().isInterrupted() || tab != 1 || !appForeground || !currentIntent(generation)) return;
                try {
                    Bitmap bitmap = null;
                    if (downloaded != null) {
                        try { bitmap = getContentResolver().loadThumbnail(downloaded, new Size(360, 360), null); }
                        catch (Exception e) { DebugLog.warn("Local media thumbnail unavailable: " + e.getClass().getSimpleName()); }
                    }
                    if (bitmap == null && downloadQueueLength.get() == 0 && currentIntent(generation))
                        bitmap = thumbnails.load(new MediaBrowser(mediaNetwork, mediaHost), file, 360);
                    final Bitmap displayedBitmap = bitmap;
                    if (displayedBitmap != null) runOnUiThread(() -> {
                        if (!destroyed && currentIntent(generation) && file.location.toString().equals(thumbnail.getTag()) && thumbnail.getParent() != null) {
                            thumbnail.setImageBitmap(displayedBitmap);
                            placeholder.setVisibility(View.GONE);
                        }
                    });
                } catch (Exception e) {
                    DebugLog.warn("No thumbnail for " + file.name + ": " + e.getMessage());
                }
            });
        }
        if (filtered.size() > count) {
            Button more = button("Load more  (" + (filtered.size() - count) + " remaining)", RAISED, WHITE, RAISED);
            galleryItems.addView(more, match());
            more.setOnClickListener(v -> { galleryVisible += MAX_GALLERY_ROWS; renderMedia(); });
        }
    }

    private void renderLocalDownloads() {
        if (galleryItems == null) return;
        galleryItems.removeAllViews();
        addBulkToolbar(true);
        ArrayList<LocalMediaIndex.SavedItem> items = savedMedia.listLocal(this);
        galleryItems.addView(text("SAVED ON THIS PHONE  ·  " + items.size(), 12, MINT, true));
        space(galleryItems, 8);
        if (items.isEmpty()) {
            addEmpty(galleryItems, "Your camera is offline. Photos and videos you download will appear here and remain available without Wi-Fi.");
            return;
        }
        LinearLayout row = null;
        int shown = 0;
        for (LocalMediaIndex.SavedItem item : items) {
            if (mediaFilter == 1 && item.isVideo()) continue;
            if (mediaFilter == 2 && !item.isVideo()) continue;
            if (shown >= 48) break;
            if (shown % 2 == 0) {
                row = horizontal();
                galleryItems.addView(row, match());
            }
            LinearLayout tile = vertical();
            tile.setPadding(dp(8), dp(8), dp(8), dp(9));
            tile.setBackground(shape(CARD, 14, RAISED, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(shown % 2 == 0 ? 0 : dp(5), 0, shown % 2 == 0 ? dp(5) : 0, dp(10));
            row.addView(tile, lp);
            FrameLayout photo = new FrameLayout(this);
            photo.setBackground(shape(RAISED, 12, RAISED, 0));
            photo.setClipToOutline(true);
            tile.addView(photo, new LinearLayout.LayoutParams(-1, dp(120)));
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photo.addView(image, new FrameLayout.LayoutParams(-1, -1));
            TextView checkmark = text(selectedLocal.contains(item.uri.toString()) ? "☑" : "☐", 25, MINT, true);
            checkmark.setBackground(shape(0xC9101821, 8, 0xC9101821, 0));
            FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(dp(39), dp(39), Gravity.TOP | Gravity.LEFT);
            checkLp.setMargins(dp(5), dp(5), 0, 0);
            checkmark.setGravity(Gravity.CENTER);
            checkmark.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            photo.addView(checkmark, checkLp);
            photo.setOnLongClickListener(v -> { toggleLocalSelection(item); return true; });
            photo.setOnClickListener(v -> { if (selectionMode) toggleLocalSelection(item); else openLocalSaved(item); });
            TextView label = text(item.name, 12, WHITE, true);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tile.addView(label);
            TextView details = text("Reading file details…", 11, MUTED, false);
            tile.addView(details);
            metadataIO.execute(() -> {
                String description = MediaDetails.local(this, item.uri, item.isVideo());
                runOnUiThread(() -> { if (details.getParent() != null) details.setText(description); });
            });
            space(tile, 5);
            Button open = button("✓ Open", RAISED, MINT, RAISED);
            tile.addView(open, new LinearLayout.LayoutParams(-1, dp(38)));
            open.setOnClickListener(v -> openLocalSaved(item));
            Button options = button("⋯  Options", CARD, MUTED, RAISED);
            options.setTextSize(11);
            tile.addView(options, new LinearLayout.LayoutParams(-1, dp(32)));
            options.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(this, options);
                menu.getMenu().add(0, 1, 0, "Show saved file location");
                menu.getMenu().add(0, 2, 1, "Delete downloaded copy");
                menu.setOnMenuItemClickListener(it -> {
                    if (it.getItemId() == 1) openDownloadFolder(item.isVideo(), item.uri);
                    else if (it.getItemId() == 2) {
                        new AlertDialog.Builder(this).setTitle("Delete phone copy?")
                            .setMessage("Delete " + item.name + " from this phone? Camera originals are unaffected.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete", (dialog, which) -> {
                                options.setText("Deleting…");
                                options.setEnabled(false);
                                fileIO.execute(() -> {
                                    try {
                                        if (getContentResolver().delete(item.uri, null, null) < 1)
                                            throw new Exception("Android did not delete the file");
                                        DebugLog.info("Deleted offline local copy " + item.name);
                                        runOnUiThread(() -> { if (tab == 1) renderLocalDownloads(); });
                                    } catch (Exception e) {
                                        error("Delete local media", e);
                                        runOnUiThread(() -> { options.setEnabled(true); options.setText("⋯  Options"); });
                                    }
                                });
                            }).show();
                    }
                    return true;
                });
                menu.show();
            });
            thumbnailIO.execute(() -> {
                try {
                    Bitmap bmp = getContentResolver().loadThumbnail(item.uri, new Size(320, 320), null);
                    runOnUiThread(() -> { if (image.getParent() != null) image.setImageBitmap(bmp); });
                } catch (Exception ignored) { /* thumbnails optional */ }
            });
            shown++;
        }
        if (shown == 0) addEmpty(galleryItems, "No saved items match this filter.");
        if (items.size() > 48) galleryItems.addView(text("Showing the newest 48 downloaded files.", 11, MUTED, false));
    }

    private void openLocalSaved(LocalMediaIndex.SavedItem saved) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(saved.uri, saved.mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open " + saved.name));
        } catch (Exception e) { error("Open saved file", e); }
    }

    private void openMediaPreview(MediaBrowser.MediaFile file) {
        Uri localFile = savedMedia.find(this, file);
        if (localFile != null) { openSavedFile(file, localFile); return; }
        boolean video = file.name.toLowerCase(Locale.ROOT).endsWith(".mp4") ||
            file.name.toLowerCase(Locale.ROOT).endsWith(".mov");
        if (!video) {
            Bitmap bitmap = thumbnails.cached(file);
            if (bitmap == null) { postStatus("Photo preview is loading. Try again shortly."); return; }
            ImageView large = new ImageView(this);
            large.setImageBitmap(bitmap);
            large.setScaleType(ImageView.ScaleType.FIT_CENTER);
            new AlertDialog.Builder(this).setTitle(file.name).setView(large)
                .setMessage("Low-resolution preview. Download the original for full detail.")
                .setPositiveButton("Close", null).show();
            DebugLog.info("Photo thumbnail preview: " + file.name);
            return;
        }
        if (!ready()) return;
        FrameLayout container = new FrameLayout(this);
        PlayerView playerView = new PlayerView(this);
        container.addView(playerView, new FrameLayout.LayoutParams(-1, dp(250)));
        if (galleryDialog != null) galleryDialog.dismiss();
        ExoPlayer player = new ExoPlayer.Builder(this).build();
        galleryPlayer = player;
        playerView.setPlayer(player);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(file.name).setView(container)
            .setMessage("Streaming from the camera. Playback may depend on the camera's HTTP range support.")
            .setPositiveButton("Close", null).create();
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException exception) {
                DebugLog.error("Gallery video preview " + file.name, exception);
                postStatus("Video preview failed. Download the original to play it locally.");
            }
        });
        galleryDialog = dialog;
        dialog.setOnDismissListener(d -> {
            player.release();
            if (galleryPlayer == player) galleryPlayer = null;
            if (galleryDialog == dialog) galleryDialog = null;
        });
        dialog.show();
        try {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(file.location.toString())));
            player.prepare();
            player.play();
            DebugLog.info("Streaming gallery video: " + file.name);
        } catch (Exception e) { error("Open video preview", e); dialog.dismiss(); }
    }

    private void openSavedFile(MediaBrowser.MediaFile file, Uri local) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(local, isVideo(file) ? "video/*" : "image/*");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, "Open saved " + (isVideo(file) ? "video" : "photo")));
            DebugLog.info("Opened local media " + file.name);
        } catch (Exception e) {
            DebugLog.error("Open local media", e);
            postStatus("No compatible viewer installed for " + file.name);
        }
    }

    private void savedFileMenu(MediaBrowser.MediaFile file, View anchor) {
        Uri saved = savedMedia.find(this, file);
        if (saved == null) { renderMedia(); return; }
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Open downloaded file");
        menu.getMenu().add(0, 2, 1, "Show saved file location");
        menu.getMenu().add(0, 3, 2, "Delete downloaded copy");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) openSavedFile(file, saved);
            else if (item.getItemId() == 2) openDownloadFolder(isVideo(file), saved);
            else if (item.getItemId() == 3) {
                new AlertDialog.Builder(this).setTitle("Delete phone copy?")
                    .setMessage("Delete " + file.name + " from your phone? The original on the camera SD card will not be changed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete phone copy", (dialog, which) -> {
                        postStatus("Deleting phone copy of " + file.name + "…");
                        fileIO.execute(() -> {
                            try {
                                savedMedia.delete(this, file);
                                DebugLog.info("Deleted local copy of " + file.name);
                                runOnUiThread(() -> {
                                    postStatus("Phone copy deleted. Camera original kept.");
                                    if (tab == 1) renderMedia();
                                });
                            } catch (Exception e) { error("Delete downloaded copy", e); }
                        });
                    }).show();
            }
            return true;
        });
        menu.show();
    }

    private void openDownloadFolder(boolean video, Uri item) {
        boolean oldDownload = item != null && item.toString().toLowerCase(Locale.ROOT).contains("/downloads/");
        String folder = oldDownload ? "Download/YI4KPlus" :
            (video ? "Movies/YI4KPlus" : "Pictures/YI4KPlus");
        new AlertDialog.Builder(this).setTitle("Saved on your phone")
            .setMessage("File location: Internal storage / " + folder +
                "\n\nOther photo and video apps can view files in Pictures and Movies. " +
                "The file picker may open at its nearest supported location.")
            .setNegativeButton("Close", null)
            .setPositiveButton("Open file picker", (dialog, which) -> {
                try {
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.setType("*/*");
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    Uri initial = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" +
                        Uri.encode(folder));
                    picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
                    startActivity(picker);
                } catch (Exception e) {
                    postStatus("Open Files on your phone and browse " + folder);
                }
            }).show();
    }

    /** Check the camera itself, not only a potentially stale UI flag. This is read-only. */
    private boolean transferIntentCurrent(int generation, String requestedHost) {
        return !destroyed && !userDisconnected && generation == downloadGeneration.get() && requestedHost.equals(host);
    }

    /** A file check can wait briefly for a read-only control reconnection; it never starts or stops recording. */
    private void requireCameraIdleForVideo(String filename, int transferGeneration, String transferHost) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (!transferIntentCurrent(transferGeneration, transferHost) || Thread.currentThread().isInterrupted())
                throw new IOException("Download cancelled");
            final YiCamera active = camera;
            final int generation = connectionGeneration.get();
            if (!currentSession(generation, active)) { Thread.sleep(200); continue; }
            java.util.concurrent.Future<String> result = controlIO.submit(() -> {
                if (!currentSession(generation, active)) throw new IOException("Camera session changed during file verification");
                return active.getSetting("app_status").optString("param", "unknown");
            });
            String status;
            try {
                long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                status = result.get(Math.min(20000, remaining), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                result.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("Download cancelled while verifying camera status", interrupted);
            } catch (java.util.concurrent.TimeoutException timeout) {
                result.cancel(true);
                throw new IOException("Camera was too busy to verify that the video is finished", timeout);
            } catch (java.util.concurrent.ExecutionException failed) {
                if (!active.isOpen() || isTransportFailure(failed)) {
                    connectionLost("video verification", failed, active);
                    continue;
                }
                throw new IOException("Camera could not verify recording status for " + filename, failed.getCause());
            }
            if (!currentSession(generation, active)) continue;
            if (!CameraModes.isClearlyIdle(status)) throw new IOException(
                "Camera is " + status + "; wait until recording completes before downloading " + filename);
            return;
        }
        throw new IOException("Camera did not reconnect in time to verify the completed video; try the download again");
    }

    private void updateDownloadButton(DownloadState state) {
        Button current = state.visibleButton;
        if (current != null && current.getParent() != null) {
            current.setText(state.label());
            current.setEnabled(false);
        }
    }

    private void download(MediaBrowser.MediaFile file, Button button) {
        final String key = file.location.toString();
        final int transferGeneration = downloadGeneration.get();
        final String transferHost = host;
        if (!transferIntentCurrent(transferGeneration, transferHost)) { postStatus("Connect before downloading."); return; }
        if (isVideo(file) && recording) {
            postStatus("Camera is still recording. Video downloads are paused until it finishes.");
            return;
        }
        DownloadState existing = activeDownloads.get(key);
        if (existing != null) {
            existing.visibleButton = button;
            updateDownloadButton(existing);
            return; // An existing queued/running request is already responsible.
        }
        DownloadState state = new DownloadState();
        state.visibleButton = button;
        DownloadState collision = activeDownloads.putIfAbsent(key, state);
        if (collision != null) {
            collision.visibleButton = button;
            updateDownloadButton(collision);
            return;
        }
        int depth = downloadQueueLength.incrementAndGet();
        DebugLog.info("Download queued: " + file.name + " (queue count " + depth + ")");
        updateDownloadButton(state);
        mediaIO.execute(() -> {
            MediaBrowser browser = null;
            try {
                if (!transferIntentCurrent(transferGeneration, transferHost)) throw new IOException("Queued download cancelled");
                state.started = true;
                runOnUiThread(() -> { if (transferIntentCurrent(transferGeneration, transferHost)) updateDownloadButton(state); });
                DebugLog.info("Download started: " + file.name);
                if (isVideo(file)) requireCameraIdleForVideo(file.name, transferGeneration, transferHost);
                final int[] lastPercent = {-1};
                browser = new MediaBrowser(() -> transferIntentCurrent(transferGeneration, transferHost) ? wifi : null, transferHost);
                activeTransferBrowser = browser;
                if (!transferIntentCurrent(transferGeneration, transferHost)) { browser.cancelTransfers(); throw new IOException("Download cancelled"); }
                MediaBrowser.DownloadResult result = browser.download(this, file, (read, total) -> {
                    state.bytes = read;
                    int pct = total > 0 ? (int) Math.min(100, Math.round(100.0 * read / total)) : -1;
                    if (pct >= 0 && pct != lastPercent[0]) {
                        if (pct / 10 != lastPercent[0] / 10) DebugLog.info("Download " + file.name + " at " + pct + "%");
                        lastPercent[0] = pct;
                        state.percent = pct;
                        runOnUiThread(() -> { if (transferIntentCurrent(transferGeneration, transferHost)) updateDownloadButton(state); });
                    } else if (pct < 0) runOnUiThread(() -> { if (transferIntentCurrent(transferGeneration, transferHost)) updateDownloadButton(state); });
                }, () -> transferIntentCurrent(transferGeneration, transferHost) && (!isVideo(file) || !recording),
                    () -> requireCameraIdleForVideo(file.name, transferGeneration, transferHost));
                savedMedia.remember(this, file, result.uri);
                metadataCache.remove(key); // Refresh size and clip length from the saved original.
                DebugLog.info("Download completed: " + file.name + " (" + result.savedPath + ")");
                runOnUiThread(() -> { if (transferIntentCurrent(transferGeneration, transferHost)) postStatus("Saved " + file.name + " to " + result.savedPath); });
            } catch (Exception e) {
                DebugLog.error("Download " + file.name, e);
                runOnUiThread(() -> { if (transferIntentCurrent(transferGeneration, transferHost)) postStatus("Download failed (" + file.name + "): " + e.getMessage()); });
            } finally {
                if (activeTransferBrowser == browser) activeTransferBrowser = null;
                activeDownloads.remove(key, state);
                downloadQueueLength.decrementAndGet();
                runOnUiThread(() -> {
                    if (transferIntentCurrent(transferGeneration, transferHost) && tab == 1 && galleryItems != null) renderMedia();
                });
            }
        });
    }

    private void loadSettings() {
        if (!ready() || settingsLoading) return;
        final YiCamera active = camera;
        final int generation = connectionGeneration.get();
        settingsLoading = true;
        postStatus("Syncing camera settings…");
        controlIO.execute(() -> {
            try {
                if (!currentSession(generation, active)) return;
                JSONObject reply = active.getSettings();
                if (!currentSession(generation, active)) return;
                lastCameraInfo = CameraInfo.detail(CameraInfo.settings(reply));
                Object param = reply.opt("param");
                ArrayList<String[]> settings = new ArrayList<>();
                if (param instanceof JSONArray) {
                    JSONArray arr = (JSONArray) param;
                    for (int i = 0; i < arr.length(); i++) readPairs(settings, arr.optJSONObject(i));
                } else if (param instanceof JSONObject) readPairs(settings, (JSONObject) param);
                else throw new Exception("Firmware returned an unfamiliar settings format");
                settings.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
                settingsLoading = false;
                lastSettingsLoadedAt = SystemClock.elapsedRealtime();
                sessionUi(generation, () -> {
                    lastSettingsRows.clear();
                    lastSettingsRows.addAll(settings);
                    showSettingsRows(lastSettingsRows);
                    if (cameraInfoContent != null) cameraInfoContent.setText(lastCameraInfo);
                    postStatus("Camera settings up to date");
                });
            } catch (Exception e) {
                settingsLoading = false;
                DebugLog.warn("Settings sync paused: " + shortError(e));
                if (isTransportFailure(e)) connectionLost("settings", e, active);
                sessionUi(generation, () -> {
                    if (tab == 2) postStatus("Settings temporarily unavailable; reconnecting and retrying…");
                });
            } finally { settingsLoading = false; }
        });
    }

    private static void readPairs(ArrayList<String[]> dest, JSONObject object) {
        if (object == null) return;
        java.util.Iterator<String> it = object.keys();
        while (it.hasNext()) {
            String k = it.next();
            dest.add(new String[]{k, object.optString(k, "")});
        }
    }

    private static boolean protectedSetting(String key) {
        String low = key.toLowerCase(Locale.ROOT);
        return Arrays.stream(PROTECTED_KEYS).anyMatch(low::contains);
    }

    private static String settingLabel(String key) {
        if ("fov".equals(key)) return "Field of view";
        if ("ev".equals(key)) return "Exposure compensation";
        if ("iso".equals(key)) return "ISO sensitivity";
        String[] words = key.split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (label.length() > 0) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    private static boolean quickSetting(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        String[] familiar = {
            "video_resolution", "video_quality", "video_standard", "photo_size", "capture_mode",
            "fov", "white_balance", "iso", "ev", "exposure", "metering_mode", "sharpness",
            "color", "video_stamp", "auto_low_light", "buzzer_volume", "led_mode", "loop_record",
            "photo_quality", "photo_resolution", "wb", "timelapse_video", "video_loop"
        };
        for (String s : familiar) if (s.equals(k)) return true;
        return false;
    }

    private void showSettingsRows(List<String[]> rows) {
        if (settingsItems == null) return;
        settingsItems.removeAllViews();
        int shown = 0;
        for (String[] pair : rows) {
            String key = pair[0];
            if (protectedSetting(key) || !CameraScan.publicKey(key)) continue;
            if (!advancedSettings && !quickSetting(key)) continue;
            LinearLayout entry = horizontal();
            entry.setPadding(dp(14), dp(13), dp(14), dp(13));
            entry.setBackground(shape(CARD, 14, RAISED, 1));
            LinearLayout textColumn = vertical();
            textColumn.addView(text(settingLabel(key), 15, WHITE, true));
            String value = pair[1];
            if (value.length() > 60) value = value.substring(0, 60) + "…";
            textColumn.addView(text(value, 12, MUTED, false));
            entry.addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1));
            entry.addView(text("›", 26, MINT, true));
            LinearLayout.LayoutParams lp = match();
            lp.bottomMargin = dp(7);
            settingsItems.addView(entry, lp);
            entry.setOnClickListener(v -> showSettingChoices(pair[0], pair[1]));
            shown++;
        }
        if (shown == 0) addEmpty(settingsItems, "No quick controls loaded. Try Advanced settings or tap Read again.");
        space(settingsItems, 5);
        Button more = button(advancedSettings ? "Show quick settings only  ↑" : "Show advanced settings  ↓", RAISED, WHITE, RAISED);
        settingsItems.addView(more, match());
        more.setOnClickListener(v -> { advancedSettings = !advancedSettings; showSettingsRows(lastSettingsRows); });
        if (advancedSettings) {
            space(settingsItems, 9);
            settingsItems.addView(text("Advanced settings are shown only when the camera reports them. Some may be read-only.", 11, MUTED, false));
        }
        DebugLog.info("Settings UI: " + shown + " visible options (advanced=" + advancedSettings + ")");
    }

    private void showSettingChoices(String key, String current) {
        if (!ready() || protectedSetting(key) || !CameraScan.publicKey(key)) return;
        postStatus("Checking valid choices for " + key + " …");
        controlIO.execute(() -> {
            try {
                JSONObject response = camera.getChoices(key);
                JSONArray options = response.optJSONArray("options");
                if (options == null || options.length() == 0 || !"settable".equals(response.optString("permission"))) {
                    runOnUiThread(() -> new AlertDialog.Builder(this).setTitle(key)
                        .setMessage("Current: " + current + "\nCamera marks this setting as read-only or gives no choices.")
                        .setPositiveButton("OK", null).show());
                    return;
                }
                String[] choices = new String[options.length()];
                for (int i = 0; i < options.length(); i++) choices[i] = options.optString(i);
                runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle(key + "  •  " + current)
                    .setItems(choices, (dialog, selected) -> confirmChange(key, current, choices[selected]))
                    .setNegativeButton("Cancel", null).show());
            } catch (Exception e) { error("Setting choices", e); }
        });
    }

    private void confirmChange(String key, String current, String value) {
        final YiCamera active = camera;
        final int generation = connectionGeneration.get();
        new AlertDialog.Builder(this).setTitle("Change camera setting?")
            .setMessage(key + "\nCurrent: " + current + "\nNew: " + value)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", (d, w) -> controlIO.execute(() -> {
                try {
                    if (!currentSession(generation, active)) throw new IOException("Camera connection changed; reopen this setting before applying it");
                    if (protectedSetting(key) || !CameraScan.publicKey(key)) throw new Exception("System setting protected");
                    JSONObject options = active.getChoices(key);
                    if (!"settable".equals(options.optString("permission"))) throw new Exception("Setting is not editable");
                    JSONArray allowed = options.optJSONArray("options");
                    boolean matched = false;
                    if (allowed != null) {
                        for (int i = 0; i < allowed.length(); i++) if (value.equals(allowed.optString(i))) matched = true;
                    }
                    if (!matched) throw new Exception("Value no longer offered by camera");
                    if (recording && ("system_mode".equals(key) || "rec_mode".equals(key) || "capture_mode".equals(key)))
                        throw new Exception("Stop recording before changing mode");
                    active.setSetting(key, value);
                    String actual = active.getSetting(key).optString("param", "unknown");
                    postStatus(key + " → " + actual);
                    if ("system_mode".equals(key) || "rec_mode".equals(key) || "capture_mode".equals(key)) {
                        try {
                            java.util.Map<String,String> latest = CameraInfo.settings(active.getSettings());
                            cameraSystemMode = latest.getOrDefault("system_mode", "unknown");
                            cameraSubMode = latest.getOrDefault("record".equals(cameraSystemMode) ? "rec_mode" : "capture_mode", "unknown");
                            captureMode = CameraModes.fromCamera(cameraSystemMode, cameraSubMode);
                        } catch (Exception e) { DebugLog.warn("Mode resync after setting: " + e.getMessage()); }
                    }
                    sessionUi(generation, () -> {
                        for (String[] row : lastSettingsRows) if (row[0].equals(key)) row[1] = actual;
                        if (tab == 2) showSettingsRows(lastSettingsRows);
                        // Synchronise other changed values automatically after a successful edit.
                        loadSettings();
                    });
                } catch (Exception e) { error("Apply setting", e); }
            })).show();
    }

    private void openWifiSettings() {
        try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
        catch (Exception e) { error("Open Wi-Fi", e); }
    }

    private void changeHost() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_PHONE);
        field.setText(host);
        field.setPadding(dp(18), dp(14), dp(18), dp(14));
        new AlertDialog.Builder(this).setTitle("Camera IP address")
            .setMessage("Default: 192.168.42.1. Only change this if your camera uses another IP.")
            .setView(field).setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (d, w) -> {
                String ip = field.getText().toString().trim();
                if (!validIpv4(ip)) { postStatus("Invalid IPv4 address: " + ip); return; }
                disconnect();
                host = ip;
                getPreferences(MODE_PRIVATE).edit().putString("host", host).apply();
                connectionTag.setText("○ NOT CONNECTED");
                postStatus("Camera IP changed to " + host + ". Reconnect to continue.");
            }).show();
    }

    private static boolean validIpv4(String input) {
        String[] parts = input.split("\\.", -1);
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3 || !part.matches("[0-9]+")) return false;
                int n = Integer.parseInt(part);
                if (n < 0 || n > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) { return false; }
    }

    @Override protected void onResume() {
        super.onResume();
        appForeground = true;
        handler.removeCallbacks(recordingClock);
        handler.post(recordingClock);
        DebugLog.info("Activity resumed: recording=" + recording + ", tab=" + tab);
        if (!userDisconnected && !connected) scheduleReconnect(1);
        if (connected && tab == 0 && !previewStarted) {
            handler.postDelayed(() -> { if (appForeground && connected && tab == 0 && !previewStarted) startPreview(); }, 650);
        } else if (connected && tab == 1 && SystemClock.elapsedRealtime() - lastMediaLoadedAt > 15000) loadMedia();
        else if (connected && tab == 2 && SystemClock.elapsedRealtime() - lastSettingsLoadedAt > 15000) loadSettings();
    }

    @Override protected void onPause() {
        appForeground = false;
        handler.removeCallbacks(recordingClock);
        DebugLog.info("Activity paused: recording=" + recording + "; stopping local decoder, preserving camera control session");
        stopPreview(true);
        if (galleryDialog != null) galleryDialog.dismiss();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        appForeground = false;
        final YiCamera old;
        synchronized (connectionLock) {
            userDisconnected = true;
            downloadGeneration.incrementAndGet();
            connectionGeneration.incrementAndGet();
            cancelReconnectLocked();
            connected = false;
            connecting = false;
            old = camera;
            camera = null;
        }
        if (old != null) old.close();
        cancelActiveTransfer();
        stopConnectionService();
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivity != null && wifiCallback != null) {
            try { connectivity.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) { }
        }
        stopPreview(true);
        handler.removeCallbacksAndMessages(null);
        if (galleryDialog != null) galleryDialog.dismiss();
        if (vlc != null) vlc.release();
        controlIO.shutdownNow();
        mediaIO.shutdownNow();
        fileIO.shutdown();
        thumbnailIO.shutdownNow();
        metadataIO.shutdownNow();
        restoreProcessBinding();
        DebugLog.info("App closed; active requests cancelled without changing camera recording");
        super.onDestroy();
    }
}
