package app.wsprinter;

import android.os.RemoteException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import woyou.aidlservice.jiuiv5.IWoyouService;

final class JobRunner {
    interface Events { void progress(String stage, JSONObject detail); }
    private final Printer printer;
    private final OkHttpClient http;

    JobRunner(Printer printer, OkHttpClient http) { this.printer = printer; this.http = http; }

    JSONObject run(JSONObject job, Events events) throws JobError {
        List<MethodCall.Prepared> calls = new ArrayList<>();
        IWoyouService p = null;
        boolean buffer = false;
        boolean touched = false;
        int index = -1;
        try {
            if (job.optString("type", "job").equals("status")) {
                p = requirePrinter();
                int state = p.updatePrinterState();
                return Json.obj("state", state, "state_name", Printer.stateName(state), "model", p.getPrinterModal(),
                        "service_version", p.getServiceVersion(), "firmware", p.getPrinterVersion(),
                        "paper_dots", Printer.paperWidthDots(p));
            }
            if (!job.optString("type", "job").equals("job")) throw new JobError("bad_request", "type must be job, status, ping or ack");
            for (String key : new String[]{"transaction", "dry_run", "reset"}) {
                if (job.has(key) && !(job.get(key) instanceof Boolean)) throw new JobError("bad_request", key + " must be boolean");
            }
            boolean transaction = job.optBoolean("transaction", true);
            int timeout = Commands.number(job, "timeout_ms", 30000, 1000, 120000);
            JSONArray commands = job.getJSONArray("commands");
            if (commands.length() == 0 || commands.length() > 128) throw new JobError("bad_request", "commands must contain 1..128 entries");
            long pixels = 0;
            boolean manualBuffer = false;
            // Resolve every argument and image before touching the printer.
            for (index = 0; index < commands.length(); index++) {
                JSONObject c = commands.getJSONObject(index);
                Style.validate(c);
                JSONObject normalized = Commands.normalize(c);
                MethodCall.Prepared call = normalized == null ? null : MethodCall.prepare(normalized, http);
                calls.add(call);
                if (call != null) {
                    String name = call.method.getName();
                    if (transaction && (name.contains("Buffer") || name.equals("commitPrint") || name.equals("printerSelfChecking")
                            || name.equals("tax") || name.equals("postPrintData"))) {
                        throw new JobError("bad_request", name + " requires transaction:false");
                    }
                    if (!transaction) {
                        if (name.equals("enterPrinterBuffer")) manualBuffer = true;
                        if (name.startsWith("exitPrinterBuffer")) manualBuffer = false;
                    }
                    for (android.graphics.Bitmap image : call.images) pixels += (long) image.getWidth() * image.getHeight();
                    if (pixels > 1000000) throw new JobError("bad_request", "Combined image size exceeds 1000000 pixels");
                }
            }
            index = -1;
            if (manualBuffer) throw new JobError("bad_request", "Manual buffer must exit within the same job");
            if (job.optBoolean("dry_run", false)) return Json.obj("validated", commands.length(), "printed", false);
            p = requirePrinter();
            if (transaction) {
                int state = p.updatePrinterState();
                if (state != 1) throw new JobError("printer_unavailable", Printer.stateName(state)).with("state", state);
            }
            events.progress("running", Json.obj("commands", commands.length()));
            touched = true;
            // Discard any abandoned transaction owned by this app before a new job.
            p.enterPrinterBuffer(true);
            buffer = true;
            if (!transaction) { p.exitPrinterBuffer(false); buffer = false; }
            if (job.optBoolean("reset", true)) p.printerInit(null);
            Style style = new Style();
            JSONArray results = new JSONArray();
            for (index = 0; index < commands.length(); index++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                JSONObject command = commands.getJSONObject(index);
                MethodCall.Prepared call = calls.get(index);
                JSONObject result;
                if (call == null) { style.set(p, command); result = new JSONObject(); }
                else {
                    style.push(p, command);
                    String method = call.method.getName();
                    boolean committing = method.equals("commitPrinterBufferWithCallback")
                            || (method.equals("exitPrinterBufferWithCallback") && Boolean.TRUE.equals(call.args[0]));
                    try { result = call.invoke(p, transaction || (manualBuffer && !committing) ? 0 : timeout); }
                    
                    finally { style.pop(p, command); }
                    if (method.equals("enterPrinterBuffer")) manualBuffer = true;
                    if (method.startsWith("exitPrinterBuffer")) manualBuffer = false;
                }
                results.put(result);
                events.progress("command", Json.obj("index", index, "total", commands.length(), "state", transaction ? "buffered" : "accepted"));
            }
            index = -1;
            if (transaction) {
                events.progress("printing", null);
                Callback commit = new Callback();
                p.exitPrinterBufferWithCallback(true, commit);
                if (!commit.awaitPrint(timeout)) throw new JobError("outcome_unknown", "Print completion timed out; do not automatically reprint");
                buffer = false;
                if (commit.failed()) throw new JobError("printer_error", "Transaction failed").with("callback", commit.toJson());
                for (index = 0; index < calls.size(); index++) {
                    MethodCall.Prepared call = calls.get(index);
                    if (call != null) { call.check(); if (call.hasCallback) Json.put(results.getJSONObject(index), "callback", call.callback.toJson()); }
                }
                return Json.obj("confirmation", "printed", "commands", results, "callback", commit.toJson());
            }
            for (index = 0; index < calls.size(); index++) {
                MethodCall.Prepared call = calls.get(index);
                if (call != null) { call.check(); if (call.hasCallback) Json.put(results.getJSONObject(index), "callback", call.callback.toJson()); }
            }
            return Json.obj("confirmation", "submitted", "commands", results);
        } catch (JobError e) {
            throw index < 0 ? e : e.at(index);
        } catch (JSONException | IllegalArgumentException e) {
            throw new JobError("bad_request", e.getMessage()).at(index);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobError(touched ? "outcome_unknown" : "interrupted", "Service stopped during job").at(index);
        } catch (RemoteException e) {
            throw new JobError(touched ? "outcome_unknown" : "printer_unavailable", e.toString()).at(index);
        } catch (Exception e) {
            throw new JobError(touched ? "outcome_unknown" : "bad_request", e.toString()).at(index);
        } finally {
            if (p != null && (buffer || touched)) {
                try { p.enterPrinterBuffer(true); p.exitPrinterBuffer(false); }
                catch (RemoteException | RuntimeException cleanupError) {
                    // Cleanup is best effort after the outcome is known. A remote service
                    // exception here must not erase a confirmed result or kill the queue worker.
                    Diag.log("printer buffer cleanup failed: " + cleanupError.getClass().getSimpleName());
                }
            }
            for (MethodCall.Prepared call : calls) if (call != null) call.close();
        }
    }

    private IWoyouService requirePrinter() throws Exception {
        IWoyouService p = printer.await(10000);
        if (p == null) throw new JobError("printer_unavailable", "Printer service is not connected");
        return p;
    }
}
