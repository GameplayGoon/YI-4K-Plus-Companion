package org.yi4kplus.companion;

import java.util.Locale;

/** Storage readout based on the camera's msg_id 5 free/total counters. */
public final class StorageStats {
    private StorageStats() {}
    public static long unitScale(long free, long total) {
        // Yi 4K+ 1.4.14 reported roughly 29,000,000/30,000,000 for a ~32GB card.
        // Such a card cannot contain 4K recordings if these are literal bytes.
        // For larger counts assume standard byte reporting as documented on older Yi.
        long n = total > 0 ? total : free;
        return n >= 1_000_000L && n < 250_000_000L ? 1024L : 1L;
    }
    public static long scaled(long value, long scale) {
        if (value < 0) return -1;
        if (value > Long.MAX_VALUE / scale) return -1;
        return value * scale;
    }
    public static int usedPercent(long free, long total) {
        if (total <= 0 || free < 0 || free > total) return -1;
        return (int)Math.min(100, Math.max(0, Math.round(100.0 * (total-free) / total)));
    }
    public static String readable(long bytes) {
        if (bytes < 0) return "—";
        if (bytes >= (1L << 30)) return String.format(Locale.US, "%.1f GB", bytes / (double)(1L<<30));
        return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
    }
    public static String display(long free, long total) {
        if (free < 0) return "SD · —";
        int used=usedPercent(free,total);
        if (used < 0) return "SD · " + readable(free) + " free";
        return "SD · " + readable(free) + " free · " + used + "% used";
    }
}
