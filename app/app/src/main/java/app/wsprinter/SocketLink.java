package app.wsprinter;

import android.os.Handler;
import android.os.SystemClock;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** All mutable connection state belongs to the main looper. Stale callbacks are ignored. */
final class SocketLink {
    interface Listener {
        void opened(String endpoint);
        void message(String endpoint, String text);
    }
    final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build();
    // OkHttp 3.12 keeps the call timeout active for the lifetime of a WebSocket.
    // Images have a deadline; the persistent socket is governed by ping/pong instead.
    private final OkHttpClient socketHttp = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build();
    private final Handler handler;
    private final Listener listener;
    private WebSocket socket;
    private String endpoint = "";
    private int generation, attempt;
    private boolean connected, stopped;
    private final java.util.concurrent.atomic.AtomicInteger incoming = new java.util.concurrent.atomic.AtomicInteger();
    private final Runnable retry = this::connect;

    SocketLink(Handler handler, Listener listener) { this.handler = handler; this.listener = listener; }

    static String validate(String url) {
        if (url == null) throw new IllegalArgumentException("URL is required");
        String trimmed = url.trim();
        if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) throw new IllegalArgumentException("URL must start with ws:// or wss://");
        Request request = new Request.Builder().url(trimmed).build();
        if (request.url().fragment() != null) throw new IllegalArgumentException("WebSocket URL cannot contain a fragment");
        return trimmed;
    }

    void configure(String url) {
        if (url.equals(endpoint) && socket != null) return;
        generation++;
        handler.removeCallbacks(retry);
        if (socket != null) socket.cancel();
        socket = null; connected = false; Diag.connectedAt = 0;
        endpoint = url; Diag.url = redact(url); attempt = 0;
        if (url.isEmpty()) { Diag.link = "URL not configured"; return; }
        connect();
    }

    private void connect() {
        if (stopped || endpoint.isEmpty()) return;
        int token = ++generation;
        String target = endpoint;
        Diag.link = "connecting";
        Diag.log("connecting to " + redact(target));
        socket = socketHttp.newWebSocket(new Request.Builder().url(target).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) { handler.post(() -> {
                if (token != generation || stopped) { ws.cancel(); return; }
                connected = true; attempt = 0; Diag.link = "connected"; Diag.connectedAt = SystemClock.elapsedRealtime();
                Diag.log("WebSocket connected"); listener.opened(target);
            }); }
            @Override public void onMessage(WebSocket ws, String text) {
                if (text.length() > 1024 * 1024) {
                    ws.close(1009, "Job exceeds 1 MiB"); handler.post(() -> lost(token)); return;
                }
                if (incoming.incrementAndGet() > 64) {
                    incoming.decrementAndGet(); ws.close(1008, "Input queue full; retry later"); handler.post(() -> lost(token)); return;
                }
                handler.post(() -> {
                    try { if (token == generation && !stopped) listener.message(target, text); }
                    finally { incoming.decrementAndGet(); }
                });
            }
            @Override public void onMessage(WebSocket ws, ByteString bytes) { ws.close(1003, "Use JSON text frames"); handler.post(() -> lost(token)); }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                // This OkHttp version predates newer close codes such as 1012.
                try { ws.close(code, null); }
                catch (IllegalArgumentException unsupportedCode) { ws.close(1000, null); }
                handler.post(() -> lost(token));
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) { handler.post(() -> lost(token)); }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) { handler.post(() -> {
                if (token == generation) Diag.log("WebSocket failed: " + error.getClass().getSimpleName());
                lost(token);
            }); }
        });
    }

    private void lost(int token) {
        if (stopped || token != generation) return;
        generation++; connected = false; Diag.connectedAt = 0;
        if (socket != null) socket.cancel();
        socket = null;
        long delay = Math.min(30000, 1000L << Math.min(attempt++, 5)) + (long) (Math.random() * 500);
        Diag.link = "retry in " + delay / 1000 + "s";
        handler.removeCallbacks(retry); handler.postDelayed(retry, delay);
    }

    void send(String target, String message) { handler.post(() -> {
        if (connected && target.equals(endpoint) && socket != null && !socket.send(message)) lost(generation);
    }); }

    void reconnect() { if (!connected && socket == null && !endpoint.isEmpty()) { handler.removeCallbacks(retry); if (socket != null) socket.cancel(); connect(); } }
    void stop() {
        stopped = true; generation++; handler.removeCallbacks(retry);
        if (socket != null) socket.cancel();
        http.dispatcher().cancelAll(); http.connectionPool().evictAll(); http.dispatcher().executorService().shutdown();
        Diag.link = "stopped"; Diag.connectedAt = 0;
    }
    static String redact(String url) {
        if (url.isEmpty()) return url;
        try {
            okhttp3.HttpUrl parsed = new Request.Builder().url(url).build().url();
            return (url.startsWith("wss:") ? "wss://" : "ws://") + parsed.host() + ":" + parsed.port() + "/…";
        } catch (IllegalArgumentException e) { return "(invalid URL)"; }
    }
}
