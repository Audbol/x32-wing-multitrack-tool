package x32wing;

import java.util.Locale;

final class Util {
    private Util() {}

    static String hms(double sec) {
        long s = (long) sec;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    static String hmsMillis(double sec) {
        long ms = Math.round(sec * 1000), s = ms / 1000;
        return String.format("%d:%02d:%02d.%03d", s / 3600, s / 60 % 60, s % 60, ms % 1000);
    }

    /** Parses "h:mm:ss.sss", "mm:ss" or plain seconds. */
    static double parseTime(String t) {
        String[] p = t.trim().split(":");
        double v = 0;
        for (String x : p) v = v * 60 + Double.parseDouble(x.trim());
        return v;
    }

    static String bytes(long b) {
        if (b >= 1L << 30) return String.format(Locale.ROOT, "%.2f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(Locale.ROOT, "%.1f MB", b / (double) (1L << 20));
        return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
    }

    /** dBFS of a 32-bit left-justified peak. */
    static double db(long peak) {
        return peak <= 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(peak / 2147483648.0);
    }

    static String dbLabel(long peak) {
        double d = db(peak);
        return Double.isInfinite(d) ? "silent" : String.format(Locale.ROOT, "%.1f dB", d);
    }

    /** Shortens a path for display: keeps the root and the last two parts ("C:\…\X_LIVE\5D76955A"). */
    static String shortPath(java.nio.file.Path p) {
        if (p == null) return "";
        int n = p.getNameCount();
        if (n <= 3) return p.toString();
        java.nio.file.Path root = p.getRoot();
        String sep = p.getFileSystem().getSeparator();
        return (root == null ? "" : root.toString()) + "…" + sep + p.subpath(n - 2, n);
    }

    static String safeFileName(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (r.endsWith(".")) r = r.substring(0, r.length() - 1);
        return r.isEmpty() ? "untitled" : r;
    }
}
