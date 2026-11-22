package app.wsprinter;

import android.content.Context;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25)
public class JobStoreTest {
    private Context context;
    private JobStore store;
    private static final String DB = "host-test.db";
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(DB);
        store = new JobStore(context, DB);
    }
    @After public void teardown() { store.close(); context.deleteDatabase(DB); }
    private JobStore.Entry add(Object id) throws Exception { return store.add("endpoint-a", Json.obj("id", id, "type", "status")); }
    private void finish(JobStore.Entry entry) throws Exception { store.event(entry, "done", Json.result(entry.job().get("id"), Json.obj("printed", true))); }

    @Test public void restartOnlyResumesJobsNeverStarted() throws Exception {
        JobStore.Entry queued = add("queued"), running = add("running"), done = add("done");
        store.event(running, "running", Json.progress("running", "printing", null));
        finish(done);
        store.close(); store = new JobStore(context, DB); store.recover();
        assertEquals(queued.row, store.next().row);
        assertEquals("done", store.find("endpoint-a", "running").state);
        assertEquals("outcome_unknown", new JSONObject(store.find("endpoint-a", "running").event).getJSONObject("error").getString("code"));
        assertEquals(done.event, store.find("endpoint-a", "done").event);
    }
    @Test public void acknowledgmentsCannotDropQueuedJobsOrOtherEndpoints() throws Exception {
        JobStore.Entry entry = add("one");
        store.ack("endpoint-a", "one");
        assertEquals(1, store.replay("endpoint-a").size());
        finish(entry);
        store.ack("endpoint-b", "one");
        assertEquals(1, store.replay("endpoint-a").size());
        assertTrue(store.replay("endpoint-b").isEmpty());
        store.ack("endpoint-a", "one");
        assertTrue(store.replay("endpoint-a").isEmpty());
        assertNotNull(store.find("endpoint-a", "one"));
    }
    @Test public void retentionEvictsOnlyAcknowledgedResults() throws Exception {
        for (int i = 0; i < JobStore.MAX_RECORDS; i++) {
            JobStore.Entry entry = add(i); finish(entry);
            if (i >= 10) store.ack("endpoint-a", i);
        }
        add("new");
        assertEquals(901, store.count(null));
        for (int i = 0; i < 10; i++) assertNotNull(store.find("endpoint-a", i));
        assertNull(store.find("endpoint-a", 10));
        assertNotNull(store.find("endpoint-a", 110));
    }
    @Test public void fullUnacknowledgedJournalBackpressuresInsteadOfLosingResults() throws Exception {
        for (int i = 0; i < JobStore.MAX_RECORDS; i++) finish(add(i));
        try { add("overflow"); fail("Expected backpressure"); }
        catch (JobError expected) { assertEquals("queue_full", expected.code); }
        assertEquals(JobStore.MAX_RECORDS, store.replay("endpoint-a").size());
    }
    @Test public void typedIdsAndReorderedObjectsRetainIdentity() throws Exception {
        add(1); add("1"); add(JSONObject.NULL);
        add(new JSONObject("{\"a\":1,\"b\":[null,true]}"));
        assertNotEquals(store.find("endpoint-a", 1).row, store.find("endpoint-a", "1").row);
        assertNotNull(store.find("endpoint-a", JSONObject.NULL));
        assertNotNull(store.find("endpoint-a", new JSONObject("{\"b\":[null,true],\"a\":1}")));
    }
}
