package app.wsprinter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded durable queue and result journal. IDs are scoped to the exact endpoint. */
final class JobStore extends SQLiteOpenHelper {
    static final int MAX_RECORDS = 1000;
    static final int MAX_PENDING = 64;
    static final class Entry {
        long row;
        String endpoint, key, request, event, state, fingerprint;
        JSONObject job() throws Exception { return new JSONObject(request); }
    }

    JobStore(Context context) { this(context, "jobs.db"); }
    JobStore(Context context, String name) { super(context, name, null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE jobs (_id INTEGER PRIMARY KEY AUTOINCREMENT, endpoint TEXT NOT NULL, id_key TEXT NOT NULL, request TEXT NOT NULL, fingerprint TEXT NOT NULL, state TEXT NOT NULL, event TEXT NOT NULL, ack INTEGER NOT NULL DEFAULT 0, UNIQUE(endpoint,id_key))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("No migration"); }

    static String fingerprint(JSONObject job) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical(job).getBytes("UTF-8"));
        return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
    }

    static String canonical(Object value) throws Exception { return canonical(value, 0); }
    private static String canonical(Object value, int depth) throws Exception {
        if (depth > 32) throw new JobError("bad_request", "JSON nesting exceeds 32 levels");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> it = object.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) { if (out.length() > 1) out.append(','); out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key), depth + 1)); }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) out.append(','); out.append(canonical(array.get(i), depth + 1)); }
            return out.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    synchronized Entry find(String endpoint, Object id) throws Exception {
        try (Cursor c = getReadableDatabase().query("jobs", null, "endpoint=? AND id_key=?", new String[]{endpoint, canonical(id)}, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    synchronized Entry add(String endpoint, JSONObject job) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        // Keep recent acknowledged results for duplicate protection. Never evict unacknowledged results.
        if (count(null) >= MAX_RECORDS) db.execSQL("DELETE FROM jobs WHERE _id IN (SELECT _id FROM jobs WHERE ack=1 AND state='done' ORDER BY _id LIMIT 100)");
        if (count(null) >= MAX_RECORDS || count("state!='done'") >= MAX_PENDING) throw new JobError("queue_full", "Queue/result journal full; acknowledge completed results and retry later");
        Entry entry = new Entry();
        entry.endpoint = endpoint;
        entry.key = canonical(job.get("id"));
        if (entry.key.length() > 1024) throw new JobError("bad_request", "id exceeds 1024 characters");
        entry.request = job.toString();
        entry.fingerprint = fingerprint(job);
        entry.state = "queued";
        entry.event = Json.progress(job.get("id"), "queued", null).toString();
        ContentValues values = new ContentValues();
        values.put("endpoint", endpoint); values.put("id_key", entry.key); values.put("request", entry.request);
        values.put("fingerprint", entry.fingerprint);
        values.put("state", entry.state); values.put("event", entry.event);
        entry.row = db.insertOrThrow("jobs", null, values);
        return entry;
    }

    synchronized int count(String where) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM jobs" + (where == null ? "" : " WHERE " + where), null)) { c.moveToFirst(); return c.getInt(0); }
    }
    synchronized Entry next() {
        try (Cursor c = getReadableDatabase().query("jobs", null, "state='queued'", null, null, null, "_id", "1")) { return c.moveToFirst() ? read(c) : null; }
    }
    synchronized void event(Entry entry, String state, JSONObject event) {
        entry.state = state; entry.event = event.toString();
        ContentValues values = new ContentValues(); values.put("state", state); values.put("event", entry.event);
        if (state.equals("done")) {
            // Completed requests may contain large images; retain only their digest and opaque ID.
            values.put("request", Json.obj("id", event.opt("id")).toString());
        }
        getWritableDatabase().update("jobs", values, "_id=?", new String[]{Long.toString(entry.row)});
    }
    synchronized void ack(String endpoint, Object id) throws Exception {
        ContentValues values = new ContentValues(); values.put("ack", 1);
        getWritableDatabase().update("jobs", values, "endpoint=? AND id_key=? AND state='done'", new String[]{endpoint, canonical(id)});
    }
    synchronized List<Entry> replay(String endpoint) {
        List<Entry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("jobs", null, "endpoint=? AND ack=0", new String[]{endpoint}, null, null, "_id")) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }
    synchronized void recover() throws Exception {
        List<Entry> interrupted = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("jobs", null, "state='running'", null, null, null, null)) { while (c.moveToNext()) interrupted.add(read(c)); }
        for (Entry entry : interrupted) event(entry, "done", Json.failure(entry.job().get("id"),
                new JobError("outcome_unknown", "App restarted during job; inspect receipt before retrying with a new id").toJson()));
    }
    private Entry read(Cursor c) {
        Entry e = new Entry();
        e.row = c.getLong(c.getColumnIndexOrThrow("_id")); e.endpoint = c.getString(c.getColumnIndexOrThrow("endpoint"));
        e.key = c.getString(c.getColumnIndexOrThrow("id_key")); e.request = c.getString(c.getColumnIndexOrThrow("request"));
        e.fingerprint = c.getString(c.getColumnIndexOrThrow("fingerprint"));
        e.event = c.getString(c.getColumnIndexOrThrow("event")); e.state = c.getString(c.getColumnIndexOrThrow("state"));
        return e;
    }
}
