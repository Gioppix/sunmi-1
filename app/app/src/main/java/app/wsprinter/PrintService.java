package app.wsprinter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

public final class PrintService extends Service implements SocketLink.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Semaphore work = new Semaphore(0);
    private final ThreadPoolExecutor ingress = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64));
    private SocketLink link;
    private Printer printer;
    private JobStore store;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private volatile boolean stopping;
    private final BroadcastReceiver network = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (link != null) link.reconnect(); }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, PrintService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }
    static String savedUrl(Context context) { return context.getSharedPreferences("config", MODE_PRIVATE).getString("url", ""); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel("printer", "Printer connection", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notification = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "printer") : new Notification.Builder(this);
        startForeground(1, notification.setSmallIcon(R.drawable.ic_printer).setContentTitle("WS Printer")
                .setContentText("Background printer connection active").setContentIntent(open).setOngoing(true).build());
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wsprinter:connection");
        wake.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) { wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wsprinter:connection"); wifi.acquire(); }
        printer = new Printer(this, handler); printer.bind();
        store = new JobStore(this);
        link = new SocketLink(handler, this);
        registerReceiver(network, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        worker = new Thread(this::runJobs, "printer-jobs"); worker.start();
        Diag.log("foreground service started");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        link.configure(savedUrl(this));
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void opened(String endpoint) {
        submit(() -> {
            link.send(endpoint, Json.obj("type", "hello", "protocol", 1, "version", BuildConfig.VERSION_NAME,
                    "model", Build.MODEL, "max_pending", JobStore.MAX_PENDING, "result_ack", true).toString());
            synchronized (store) {
                for (JobStore.Entry entry : store.replay(endpoint)) link.send(endpoint, entry.event);
            }
        });
    }
    @Override public void message(String endpoint, String text) { submit(() -> receive(endpoint, text)); }
    private void submit(Runnable action) {
        if (stopping) return;
        try { ingress.execute(action); }
        catch (RejectedExecutionException e) { Diag.log("input overloaded; reconnecting for server retry"); handler.post(() -> link.configure("")); handler.post(() -> link.configure(savedUrl(this))); }
    }

    private void receive(String endpoint, String text) {
        Object id = JSONObject.NULL;
        try {
            Json.checkNesting(text);
            JSONTokener tokener = new JSONTokener(text);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) throw new JobError("bad_request", "Expected one JSON object");
            JSONObject job = (JSONObject) parsed;
            if (!job.has("id")) throw new JobError("bad_request", "Missing id");
            id = job.get("id");
            String type = job.optString("type", "job");
            if (type.equals("ack")) { store.ack(endpoint, id); return; }
            if (type.equals("ping")) { link.send(endpoint, Json.result(id, Json.obj("pong", true)).toString()); return; }
            synchronized (store) {
                JobStore.Entry existing = store.find(endpoint, id);
                if (existing != null) {
                    if (!existing.fingerprint.equals(JobStore.fingerprint(job))) throw new JobError("id_conflict", "This id already belongs to a different job");
                    link.send(endpoint, existing.event); return;
                }
                JobStore.Entry entry = store.add(endpoint, job);
                Diag.pending = store.count("state='queued'");
                link.send(endpoint, entry.event); work.release();
            }
        } catch (Exception e) {
            JobError error = e instanceof JobError ? (JobError) e : new JobError("bad_request", e.getMessage());
            link.send(endpoint, Json.failure(id, error.toJson()).toString());
            Diag.log("request rejected: " + error.code);
        }
    }

    private void runJobs() {
        try {
            store.recover();
            work.release();
            JobRunner runner = new JobRunner(printer, link.http);
            while (!stopping) {
                work.acquire();
                JobStore.Entry entry;
                while (!stopping && (entry = store.next()) != null) {
                    final JobStore.Entry current = entry;
                    JSONObject job = entry.job();
                    Object id = job.get("id");
                    Diag.running = Diag.clip(String.valueOf(id));
                    publish(entry, "running", Json.progress(id, "validating", null));
                    Diag.pending = store.count("state='queued'");
                    JSONObject result;
                    try {
                        result = Json.result(id, runner.run(job, (stage, data) -> publish(current, "running", Json.progress(id, stage, data))));
                        Diag.succeeded++;
                    } catch (JobError e) {
                        result = Json.failure(id, e.toJson()); Diag.failed++;
                    }
                    publish(entry, "done", result);
                    Diag.log("job " + Diag.clip(String.valueOf(id)) + ": " + (result.optBoolean("ok") ? "ok" : result.optJSONObject("error")));
                    Diag.running = "-";
                }
                work.drainPermits();
                // Admission can happen between the last next() and drainPermits().
                if (store.next() != null) work.release();
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { Diag.log("job worker failed: " + e); handler.post(this::stopSelf); }
        finally { store.close(); }
    }
    private void publish(JobStore.Entry entry, String state, JSONObject event) {
        synchronized (store) {
            store.event(entry, state, event); link.send(entry.endpoint, event.toString());
        }
    }

    @Override public void onDestroy() {
        stopping = true;
        unregisterReceiver(network);
        ingress.shutdownNow();
        if (worker != null) worker.interrupt();
        if (link != null) link.stop();
        if (printer != null) printer.unbind();
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (wake != null && wake.isHeld()) wake.release();
        Diag.log("service stopped");
        super.onDestroy();
    }
}
