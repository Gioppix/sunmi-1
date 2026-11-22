package app.wsprinter;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import okhttp3.OkHttpClient;

/** Device-side tests using Android's actual JSON, SQLite, Bitmap and Binder classes; no paper. */
public final class ProtocolInstrumentation extends Instrumentation {
    private int passed;
    interface Test { void run() throws Exception; }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            test("opaqueIds", () -> {
                JSONObject first = new JSONObject("{\"b\":[true,null,2],\"a\":\"x\"}");
                JSONObject second = new JSONObject("{\"a\":\"x\",\"b\":[true,null,2]}");
                check(JobStore.canonical(first).equals(JobStore.canonical(second)), "object key order changed identity");
                check(!JobStore.canonical("1").equals(JobStore.canonical(1)), "string/number ID collision");
                check(Json.result(JSONObject.NULL, new JSONObject()).has("id"), "null id lost");
                check(new JSONObject(Json.result(Long.MAX_VALUE, new JSONObject()).toString()).getLong("id") == Long.MAX_VALUE, "64-bit ID changed");
            });
            test("restartRecoveryAndAck", () -> {
                String db = "test-recovery.db";
                getTargetContext().deleteDatabase(db);
                JobStore store = new JobStore(getTargetContext(), db);
                try {
                    JobStore.Entry queued = store.add("a", Json.obj("id", "queued", "type", "status"));
                    JobStore.Entry running = store.add("a", Json.obj("id", "running", "type", "status"));
                    store.event(running, "running", Json.progress("running", "printing", null));
                    JobStore.Entry done = store.add("a", Json.obj("id", "done", "commands", new JSONArray().put(Json.obj("op", "text", "text", "large body"))));
                    store.event(done, "done", Json.result("done", Json.obj("confirmation", "printed")));
                    store.close(); store = new JobStore(getTargetContext(), db); store.recover();
                    check(store.next().row == queued.row, "queued job not retained");
                    JobStore.Entry recovered = store.find("a", "running");
                    check(recovered.state.equals("done"), "interrupted job would run again");
                    check(new JSONObject(recovered.event).getJSONObject("error").getString("code").equals("outcome_unknown"), "interrupted result not conservative");
                    check(!store.find("a", "done").request.contains("large body"), "completed payload retained");
                    check(store.find("a", "done").fingerprint.equals(done.fingerprint), "duplicate digest lost");
                    check(store.replay("a").size() == 3 && store.replay("b").isEmpty(), "endpoint results leaked");
                    store.ack("b", "done"); check(store.replay("a").size() == 3, "wrong endpoint ack accepted");
                    store.ack("a", "done"); check(store.replay("a").size() == 2, "ack not persisted");
                    store.ack("a", "queued"); check(store.replay("a").size() == 2, "queued job acknowledged early");
                } finally { store.close(); getTargetContext().deleteDatabase(db); }
            });
            test("boundedQueue", () -> {
                String db = "test-queue.db";
                getTargetContext().deleteDatabase(db);
                try (JobStore store = new JobStore(getTargetContext(), db)) {
                    for (int i = 0; i < JobStore.MAX_PENDING; i++) store.add("a", Json.obj("id", i, "type", "status"));
                    try { store.add("a", Json.obj("id", "overflow")); throw new AssertionError("queue exceeded limit"); }
                    catch (JobError expected) { check(expected.code.equals("queue_full"), "wrong overflow error"); }
                } finally { getTargetContext().deleteDatabase(db); }
            });
            test("callbacks", () -> {
                Callback callback = new Callback(); callback.onRunResult(true);
                check(callback.awaitAny(1), "run callback missing");
                check(!callback.awaitPrint(1), "acceptance treated as printed");
                callback.onPrintResult(0, "ok"); check(callback.awaitPrint(1) && !callback.failed(), "print completion lost");
                Callback bad = new Callback(); bad.onRaiseException(-5, "bad");
                check(bad.failed() && bad.awaitPrint(1), "printer error ignored");
            });
            test("preflightAndBytes", () -> {
                Json.checkNesting("{\"text\":\"{{[[\\\"\"}");
                StringBuilder deep = new StringBuilder();
                for (int i = 0; i < 40; i++) deep.append('[');
                try { Json.checkNesting(deep.toString()); throw new AssertionError("unbounded parser recursion"); }
                catch (JobError expected) { check(expected.code.equals("bad_request"), "wrong nesting error"); }
                check(Arrays.equals(Codec.bytes(Json.obj("hex", "1b 40 ff")), new byte[]{27, 64, -1}), "hex changed");
                check(Arrays.equals(Codec.bytes(new JSONArray("[0,255,-128]")), new byte[]{0, -1, -128}), "bytes changed");
                JobRunner runner = new JobRunner(null, new OkHttpClient());
                JSONObject valid = new JSONObject("{\"commands\":[{\"op\":\"text\",\"text\":\"no paper\"}],\"dry_run\":true}");
                check(runner.run(valid, (s,d) -> {}).getBoolean("printed") == false, "dry run touched printer");
                JSONObject invalid = new JSONObject("{\"commands\":[{\"op\":\"text\",\"text\":\"must not print\"},{\"op\":\"feed\",\"lines\":-1}]}");
                try { runner.run(invalid, (s,d) -> {}); throw new AssertionError("invalid job accepted"); }
                catch (JobError expected) { check(expected.code.equals("bad_request") && expected.toJson().getInt("index") == 1, "validation touched printer or lost index"); }
                JSONObject unbalanced = new JSONObject("{\"transaction\":false,\"dry_run\":true,\"commands\":[{\"op\":\"call\",\"method\":\"enterPrinterBuffer\",\"args\":[true]}]}");
                try { runner.run(unbalanced, (s,d) -> {}); throw new AssertionError("unbalanced buffer accepted"); }
                catch (JobError expected) { check(expected.code.equals("bad_request"), "wrong buffer validation error"); }
            });
            result.putString("stream", "\nOK (" + passed + " tests)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + passed + " tests: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void test(String name, Test body) throws Exception {
        body.run(); passed++;
        Bundle status = new Bundle(); status.putString("stream", "PASS " + name + "\n"); sendStatus(0, status);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
