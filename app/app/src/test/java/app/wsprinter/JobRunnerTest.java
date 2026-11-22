package app.wsprinter;

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.IWoyouService;
import static org.junit.Assert.*;

/** Fault injection against the actual runner, Android JSON and Binder callback implementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25)
public class JobRunnerTest {
    private FakePrinter fake;
    private JobRunner runner;
    private final List<String> stages = new ArrayList<>();

    @Before public void setup() {
        fake = new FakePrinter();
        Printer printer = new Printer(RuntimeEnvironment.getApplication(), new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(printer, "service", fake.proxy());
        runner = new JobRunner(printer, new OkHttpClient());
    }

    private JSONObject run(String json) throws Exception {
        return runner.run(new JSONObject(json), (stage, detail) -> stages.add(stage));
    }
    private JobError failure(String json, String code) throws Exception {
        try { run(json); fail("Expected " + code); return null; }
        catch (JobError error) { assertEquals(code, error.code); return error; }
    }
    private static final String RECEIPT = "{\"timeout_ms\":1000,\"commands\":[{\"op\":\"text\",\"text\":\"test\\n\"},{\"op\":\"feed\",\"lines\":1}]}";

    @Test public void dryRunNeverAccessesPrinter() throws Exception {
        JSONObject job = new JSONObject(RECEIPT).put("dry_run", true);
        assertFalse(run(job.toString()).getBoolean("printed"));
        assertTrue(fake.calls.isEmpty());
        assertTrue(stages.isEmpty());
    }
    @Test public void lateInvalidCommandDoesNotPrintLeadingText() throws Exception {
        JobError error = failure("{\"commands\":[{\"op\":\"text\",\"text\":\"must not print\"},{\"op\":\"feed\",\"lines\":-1}]}", "bad_request");
        assertEquals(1, error.toJson().getInt("index"));
        assertTrue(fake.calls.isEmpty());
    }
    @Test public void transactionWaitsForPrintAndFlushesOnce() throws Exception {
        JSONObject result = run(RECEIPT);
        assertEquals("printed", result.getString("confirmation"));
        assertEquals(1, fake.commits);
        assertEquals(0, fake.buffered.size());
        assertFalse(fake.bufferMode);
        assertEquals("printing", stages.get(stages.size() - 1));
        assertTrue(result.getJSONArray("commands").getJSONObject(0).getJSONObject("callback").getBoolean("success"));
    }
    @Test public void acceptanceWithoutPrintCompletionIsUnknown() throws Exception {
        fake.suppressPrintResult = true;
        failure(RECEIPT, "outcome_unknown");
        assertEquals(1, fake.commits);
        assertFalse(fake.bufferMode);
    }
    @Test public void physicalFailureIsNotSuccess() throws Exception {
        fake.printCode = 1;
        JobError error = failure(RECEIPT, "printer_error");
        assertEquals(1, error.toJson().getJSONObject("callback").getJSONObject("print_result").getInt("code"));
    }
    @Test public void individualCommandFailureSurvivesSuccessfulCommit() throws Exception {
        fake.rejectedMethod = "printText";
        JobError error = failure(RECEIPT, "printer_error");
        assertEquals(0, error.toJson().getInt("index"));
    }
    @Test public void paperOutStopsBeforeAnySubmission() throws Exception {
        fake.state = 4;
        JobError error = failure(RECEIPT, "printer_unavailable");
        assertEquals(4, error.toJson().getInt("state"));
        assertEquals(1, fake.calls.size());
        assertEquals("updatePrinterState", fake.calls.get(0));
    }
    @Test public void binderDeathNeverClaimsNoPrintOrRetries() throws Exception {
        fake.deadMethod = "printText";
        failure(RECEIPT, "outcome_unknown");
        assertEquals(1, java.util.Collections.frequency(fake.calls, "printText"));
        assertEquals(0, fake.commits);
        assertFalse(fake.bufferMode);
    }
    @Test public void manualBufferDefersCallbacksUntilCommit() throws Exception {
        JSONObject result = run("{\"transaction\":false,\"timeout_ms\":1000,\"commands\":["
                + "{\"op\":\"call\",\"method\":\"enterPrinterBuffer\",\"args\":[true]},"
                + "{\"op\":\"text\",\"text\":\"test\\n\"},"
                + "{\"op\":\"call\",\"method\":\"exitPrinterBufferWithCallback\",\"args\":[true]}]}");
        assertEquals("submitted", result.getString("confirmation"));
        assertEquals(1, fake.commits);
        assertTrue(result.getJSONArray("commands").getJSONObject(1).getJSONObject("callback").getBoolean("success"));
    }
    @Test public void leftoverManualBufferIsRejectedBeforeSubmission() throws Exception {
        failure("{\"transaction\":false,\"commands\":[{\"op\":\"call\",\"method\":\"enterPrinterBuffer\",\"args\":[true]}]}", "bad_request");
        assertTrue(fake.calls.isEmpty());
    }
    @Test public void qrModuleSizeDoesNotAlterTextFont() throws Exception {
        run("{\"commands\":[{\"op\":\"qr\",\"data\":\"x\",\"size\":2}]}");
        assertFalse("QR size is in modules, not a text font size", fake.calls.contains("setFontSize"));
    }
    @Test public void cleanupFailureDoesNotEraseConfirmedPhysicalResult() throws Exception {
        fake.failCleanup = true;
        assertEquals("printed", run(RECEIPT).getString("confirmation"));
    }
    @Test public void queryReturnsNativeValueWithoutPrint() throws Exception {
        JSONObject result = run("{\"transaction\":false,\"commands\":[{\"op\":\"call\",\"method\":\"getPrinterPaper\"}]}");
        assertEquals(1, result.getJSONArray("commands").getJSONObject(0).getInt("return"));
        assertEquals(0, fake.commits);
    }

    static final class FakePrinter implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        final List<ICallback> buffered = new ArrayList<>();
        boolean bufferMode, suppressPrintResult, failCleanup;
        int state = 1, commits, printCode;
        String rejectedMethod, deadMethod;
        IWoyouService proxy() { return (IWoyouService) Proxy.newProxyInstance(IWoyouService.class.getClassLoader(), new Class<?>[]{IWoyouService.class}, this); }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            String name = method.getName(); calls.add(name);
            if (failCleanup && commits > 0 && name.equals("enterPrinterBuffer")) throw new IllegalStateException("Injected remote cleanup failure");
            if (name.equals(deadMethod)) throw new RemoteException("Injected binder death");
            ICallback callback = null;
            if (args != null) for (Object arg : args) if (arg instanceof ICallback) callback = (ICallback) arg;
            if (name.equals("enterPrinterBuffer")) {
                bufferMode = true;
                if ((Boolean) args[0]) buffered.clear();
            } else if (name.startsWith("exitPrinterBuffer") || name.startsWith("commitPrinterBuffer")) {
                boolean exiting = name.startsWith("exit");
                boolean commit = !exiting || (Boolean) args[0];
                if (bufferMode && commit) {
                    commits++;
                    for (ICallback queued : buffered) queued.onRunResult(true);
                    buffered.clear();
                    if (callback != null) {
                        callback.onRunResult(true);
                        if (!suppressPrintResult) callback.onPrintResult(printCode, "injected physical result");
                    }
                }
                if (exiting) bufferMode = false;
            } else if (callback != null) {
                if (name.equals(rejectedMethod)) callback.onRaiseException(-5, "Injected rejection");
                else if (bufferMode) buffered.add(callback);
                else callback.onRunResult(true);
            }
            if (name.equals("updatePrinterState")) return state;
            if (method.getReturnType() == int.class) return 1;
            if (method.getReturnType() == boolean.class) return true;
            if (method.getReturnType() == String.class) return "fake";
            return null;
        }
    }
}
