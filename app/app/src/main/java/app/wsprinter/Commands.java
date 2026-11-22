package app.wsprinter;

import org.json.JSONArray;
import org.json.JSONObject;

/** Friendly commands expand to the same methods as the generic call escape hatch. */
final class Commands {
    static JSONObject call(String method, Object... args) {
        JSONArray array = new JSONArray();
        for (Object arg : args) array.put(arg);
        return Json.obj("method", method, "args", array);
    }

    static int integer(Object value, String name, int min, int max) throws JobError {
        if (!(value instanceof Number)) throw new JobError("bad_request", name + " must be an integer");
        double n = ((Number) value).doubleValue();
        if ((Double.isNaN(n) || Double.isInfinite(n)) || n != Math.rint(n) || n < min || n > max) {
            throw new JobError("bad_request", name + " must be in " + min + ".." + max);
        }
        return (int) n;
    }

    static int number(JSONObject c, String name, int fallback, int min, int max) throws Exception {
        return c.has(name) ? integer(c.get(name), name, min, max) : fallback;
    }

    static String string(JSONObject c, String name) throws Exception {
        Object value = c.get(name);
        if (!(value instanceof String)) throw new JobError("bad_request", name + " must be a string");
        return (String) value;
    }

    static JSONObject normalize(JSONObject c) throws Exception {
        switch (string(c, "op")) {
            case "call": return c;
            case "text": return call("printText", string(c, "text"));
            case "original_text": return call("printOriginalText", string(c, "text"));
            case "text_font": return call("printTextWithFont", string(c, "text"), c.optString("font", ""), c.optDouble("size", 24));
            case "feed": return call("lineWrap", number(c, "lines", 1, 0, 255));
            case "raw": return call("sendRAWData", Json.obj("bytes", new JSONArray(Codec.commandBytes(c))));
            case "image": return c.has("mode") ? call("printBitmapCustom", c, number(c, "mode", 0, 0, 2)) : call("printBitmap", c);
            case "qr": return call("printQRCode", string(c, "data"), number(c, "size", 4, 1, 16), number(c, "error", 1, 0, 3));
            case "barcode": return call("printBarCode", string(c, "data"), number(c, "symbology", 8, 0, 8),
                    number(c, "height", 64, 1, 255), number(c, "width", 2, 2, 6), number(c, "text_position", 0, 0, 3));
            case "code2d": return call("print2DCode", string(c, "data"), number(c, "symbology", 0, 0, 2),
                    number(c, "size", 4, 1, 16), number(c, "error", 1, 0, 8));
            case "columns": {
                JSONArray texts = c.getJSONArray("texts"), widths = c.getJSONArray("widths"), aligns = c.getJSONArray("aligns");
                if (texts.length() == 0 || texts.length() != widths.length() || texts.length() != aligns.length()) {
                    throw new JobError("bad_request", "columns arrays must have equal nonzero lengths");
                }
                JSONArray normalized = new JSONArray();
                for (int i = 0; i < widths.length(); i++) {
                    integer(widths.get(i), "column width", 1, 384);
                    normalized.put(Style.alignment(aligns.get(i)));
                }
                return call(c.optBoolean("weighted", true) ? "printColumnsString" : "printColumnsText", texts, widths, normalized);
            }
            case "style": return null;
            default: throw new JobError("bad_request", "Unknown op: " + c.optString("op"));
        }
    }
}
