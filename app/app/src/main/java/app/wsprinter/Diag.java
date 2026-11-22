package app.wsprinter;

import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

final class Diag {
    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    static volatile String link = "stopped";
    static volatile String url = "";
    static volatile long connectedAt;
    static volatile String printer = "unbound";
    static volatile int pending;
    static volatile String running = "-";
    static volatile long succeeded;
    static volatile long failed;

    private Diag() {
    }

    static synchronized void log(String message) {
        message = clip(message);
        Log.i("WsPrinter", message);
        lines.addLast(clock.format(new Date()) + "  " + message);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    static synchronized String logText() {
        StringBuilder out = new StringBuilder();
        Iterator<String> newestFirst = lines.descendingIterator();
        while (newestFirst.hasNext()) {
            out.append(newestFirst.next()).append('\n');
        }
        return out.toString();
    }

    static String summary() {
        String uptime = connectedAt == 0 ? "" : " for " + (SystemClock.elapsedRealtime() - connectedAt) / 1000 + "s";
        return "link:     " + link + uptime + "\n"
                + "url:      " + (url.isEmpty() ? "(not set)" : url) + "\n"
                + "printer:  " + printer + "\n"
                + "queue:    " + pending + " pending, running " + running + "\n"
                + "jobs:     " + succeeded + " ok, " + failed + " failed\n"
                + "version:  " + BuildConfig.VERSION_NAME;
    }

    static String clip(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "… (" + text.length() + " chars)";
    }
}
