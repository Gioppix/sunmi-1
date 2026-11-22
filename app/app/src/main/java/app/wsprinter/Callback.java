package app.wsprinter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.ITax;

final class Callback extends ICallback.Stub {
    private final CountDownLatch anyResult = new CountDownLatch(1);
    private final CountDownLatch printResult = new CountDownLatch(1);

    volatile Boolean success;
    volatile String returned;
    volatile boolean raised;
    volatile int errorCode;
    volatile String errorMessage;
    volatile boolean printed;
    volatile int printCode;
    volatile String printMessage;

    @Override
    public void onRunResult(boolean isSuccess) {
        success = isSuccess;
        anyResult.countDown();
    }

    @Override
    public void onReturnString(String result) {
        returned = result;
        anyResult.countDown();
    }

    @Override
    public void onRaiseException(int code, String msg) {
        errorCode = code;
        errorMessage = msg;
        raised = true;
        anyResult.countDown();
        printResult.countDown();
    }

    @Override
    public void onPrintResult(int code, String msg) {
        printCode = code;
        printMessage = msg;
        printed = true;
        anyResult.countDown();
        printResult.countDown();
    }

    boolean awaitAny(long timeoutMs) throws InterruptedException {
        if (!anyResult.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return false;
        }
        // On failure the service calls onRunResult(false) and then onRaiseException,
        // so give the exception a moment to arrive before reading the fields.
        for (int i = 0; i < 20 && Boolean.FALSE.equals(success) && !raised; i++) {
            Thread.sleep(10);
        }
        return true;
    }

    boolean awaitPrint(long timeoutMs) throws InterruptedException {
        return printResult.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    boolean failed() {
        return raised || Boolean.FALSE.equals(success) || (printed && printCode != 0);
    }

    JSONObject toJson() {
        JSONObject out = new JSONObject();
        Json.put(out, "success", success);
        Json.put(out, "returned", returned);
        if (raised) {
            Json.put(out, "error", Json.obj("code", errorCode, "message", errorMessage));
        }
        if (printed) {
            Json.put(out, "print_result", Json.obj("code", printCode, "message", printMessage));
        }
        return out;
    }

    static final class Tax extends ITax.Stub {
        private final CountDownLatch done = new CountDownLatch(1);
        volatile byte[] data;

        @Override
        public void onDataResult(byte[] result) {
            data = result;
            done.countDown();
        }

        boolean await(long timeoutMs) throws InterruptedException {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }
}
