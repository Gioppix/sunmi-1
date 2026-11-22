package app.wsprinter;

import org.json.JSONException;
import org.json.JSONObject;

final class Json {
    private Json() {
    }

    static JSONObject obj(Object... keyValues) {
        JSONObject out = new JSONObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            put(out, (String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    // A null value leaves the key out, which is how org.json treats put(key, null).
    static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    // Bound parser recursion before Android's JSONTokener allocates nested objects.
    static void checkNesting(String text) throws JobError {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == 39) quote = c;
            else if (c == '{' || c == '[') {
                if (++depth > 32) throw new JobError("bad_request", "JSON nesting exceeds 32 levels");
            } else if (c == '}' || c == ']') depth--;
        }
    }

    static JSONObject result(Object id, JSONObject data) {
        return obj("id", id, "type", "result", "ok", true, "data", data);
    }

    static JSONObject failure(Object id, JSONObject error) {
        return obj("id", id, "type", "result", "ok", false, "error", error);
    }

    static JSONObject progress(Object id, String stage, JSONObject extra) {
        JSONObject out = obj("id", id, "type", "progress", "stage", stage);
        if (extra != null) {
            java.util.Iterator<String> keys = extra.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                put(out, key, extra.opt(key));
            }
        }
        return out;
    }
}
