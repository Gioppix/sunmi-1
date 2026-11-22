package app.wsprinter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import woyou.aidlservice.jiuiv5.IWoyouService;

final class Printer {
    static final String PACKAGE = "woyou.aidlservice.jiuiv5";

    private final Context context;
    private final Handler handler;
    private final Runnable retryBind = this::bind;
    private IWoyouService service;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (Printer.this) {
                service = IWoyouService.Stub.asInterface(binder);
                Printer.this.notifyAll();
            }
            Diag.printer = "connected";
            Diag.log("printer service connected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            unbind();
            handler.postDelayed(retryBind, 1000);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            unbind();
            Diag.printer = "null binding";
            handler.postDelayed(retryBind, 5000);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (Printer.this) {
                service = null;
            }
            Diag.printer = "disconnected";
            Diag.log("printer service disconnected; Android rebinds it automatically");
        }
    };

    Printer(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
    }

    void bind() {
        if (bound) return;
        handler.removeCallbacks(retryBind);
        Intent intent = new Intent(PACKAGE + ".IWoyouService").setPackage(PACKAGE);
        try { bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException e) { bound = false; Diag.log("bind failed: " + e.getClass().getSimpleName()); }
        if (!bound) {
            Diag.printer = "service missing";
            Diag.log("printer service not found; retrying in 5s");
            handler.postDelayed(retryBind, 5000);
        }
    }

    synchronized void unbind() {
        service = null;
        handler.removeCallbacks(retryBind);
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
    }

    synchronized IWoyouService get() {
        return service;
    }

    synchronized IWoyouService await(long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (service == null) {
            long left = deadline - SystemClock.uptimeMillis();
            if (left <= 0) {
                return null;
            }
            wait(left);
        }
        return service;
    }

    // Codes 6-9 come from other Sunmi models; the V2 service only reports 1-5, 505 and 507.
    static String stateName(int code) {
        switch (code) {
            case 1: return "normal";
            case 2: return "initializing";
            case 3: return "communication_error";
            case 4: return "out_of_paper";
            case 5: return "overheated";
            case 6: return "cover_open";
            case 7: return "cutter_error";
            case 8: return "cutter_recovered";
            case 9: return "black_label_missing";
            case 505: return "no_printer";
            case 507: return "firmware_update_failed";
            default: return "unknown_" + code;
        }
    }

    static int paperWidthDots(IWoyouService printer) throws android.os.RemoteException {
        return printer.getPrinterPaper() == 1 ? 384 : 576;
    }
}
