package app.wsprinter;

import android.os.Handler;
import android.os.Looper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real localhost TCP/WebSocket exchanges through the app's SocketLink implementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25)
@LooperMode(LooperMode.Mode.PAUSED)
public class SocketLinkTest {
    private MockWebServer server;
    private SocketLink link;
    private final LinkedBlockingQueue<WebSocket> connections = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<String> fromApp = new LinkedBlockingQueue<>();
    private final List<String> received = new ArrayList<>();
    private int opened;
    private String url;
    @Before public void setup() throws Exception {
        server = new MockWebServer(); server.start();
        url = server.url("/test?secret=not-for-logs").toString().replace("http://", "ws://");
        enqueue();
        link = new SocketLink(new Handler(Looper.getMainLooper()), new SocketLink.Listener() {
            @Override public void opened(String endpoint) { opened++; }
            @Override public void message(String endpoint, String message) { received.add(endpoint + " " + message); }
        });
    }
    private void enqueue() {
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) { connections.add(ws); }
            @Override public void onMessage(WebSocket ws, String message) { fromApp.add(message); }
            @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, null); }
        }));
    }
    @After public void teardown() throws Exception { link.stop(); server.shutdown(); }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10); }
        assertTrue("Timed out awaiting asynchronous socket state", condition.getAsBoolean());
    }
    private WebSocket connect() throws Exception {
        link.configure(url);
        WebSocket ws = connections.poll(8, TimeUnit.SECONDS);
        assertNotNull(ws); await(() -> opened == 1); return ws;
    }
    @Test public void exchangesFramesAndNeverSendsToWrongEndpoint() throws Exception {
        WebSocket ws = connect();
        ws.send("{\"id\":\"opaque\"}"); await(() -> received.size() == 1);
        assertEquals(url + " {\"id\":\"opaque\"}", received.get(0));
        link.send("ws://different-host/", "wrong"); link.send(url, "right");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("right", fromApp.poll(5, TimeUnit.SECONDS));
        assertNull(fromApp.poll(100, TimeUnit.MILLISECONDS));
        assertFalse(Diag.url.contains("secret"));
    }
    @Test public void reconnectsAfterServerClose() throws Exception {
        WebSocket ws = connect(); enqueue();
        ws.close(1001, "restart"); await(() -> Diag.link.startsWith("retry"));
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS);
        assertNotNull(connections.poll(8, TimeUnit.SECONDS)); await(() -> opened == 2);
    }
    @Test public void oversizedIncomingJobIsRejectedBeforeDelivery() throws Exception {
        WebSocket ws = connect();
        char[] data = new char[1024 * 1024 + 1]; java.util.Arrays.fill(data, 'x');
        ws.send(new String(data)); await(() -> Diag.link.startsWith("retry"));
        assertTrue(received.isEmpty());
    }
    @Test public void binaryFramesAreRejected() throws Exception {
        WebSocket ws = connect(); ws.send(okio.ByteString.encodeUtf8("binary"));
        await(() -> Diag.link.startsWith("retry")); assertTrue(received.isEmpty());
    }
    @Test public void remainsConnectedPastImageDownloadDeadline() throws Exception {
        connect();
        // Regression: the shared client's callTimeout used to kill every socket after 30s.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        while (System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50); }
        link.send(url, "still-connected"); shadowOf(Looper.getMainLooper()).idle();
        assertEquals("still-connected", fromApp.poll(5, TimeUnit.SECONDS));
        assertEquals(1, opened);
    }
}
