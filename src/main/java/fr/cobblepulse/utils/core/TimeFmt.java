package fr.cobblepulse.utils.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formatage des durees et dates en francais. */
public final class TimeFmt {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    public static final long SECOND = 1000L;
    public static final long MINUTE = 60 * SECOND;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;

    private TimeFmt() {}

    public static String date(long epochMs) {
        if (epochMs <= 0) return "-";
        return DATE.format(Instant.ofEpochMilli(epochMs));
    }

    public static String duration(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000;
        long d = s / 86400;
        s %= 86400;
        long h = s / 3600;
        s %= 3600;
        long m = s / 60;
        s %= 60;
        StringBuilder b = new StringBuilder();
        if (d > 0) b.append(d).append("j ");
        if (h > 0) b.append(h).append("h ");
        if (m > 0 && d == 0) b.append(m).append("min ");
        if (s > 0 && d == 0 && h == 0) b.append(s).append("s");
        return b.toString().trim();
    }

    /** Chronometre h:mm:ss / m:ss. */
    public static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    public static long now() {
        return System.currentTimeMillis();
    }
}
