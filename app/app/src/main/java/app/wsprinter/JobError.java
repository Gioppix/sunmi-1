package app.wsprinter;

import org.json.JSONObject;

final class JobError extends Exception {
    final String code;
    private int index = -1;
    private final JSONObject extra = new JSONObject();

    JobError(String code, String message) {
        super(message);
        this.code = code;
    }

    JobError at(int commandIndex) {
        if (index < 0) {
            index = commandIndex;
        }
        return this;
    }

    JobError with(String key, Object value) {
        Json.put(extra, key, value);
        return this;
    }

    JSONObject toJson() {
        JSONObject out = Json.obj("code", code, "message", getMessage());
        if (index >= 0) {
            Json.put(out, "index", index);
        }
        java.util.Iterator<String> keys = extra.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Json.put(out, key, extra.opt(key));
        }
        return out;
    }
}
