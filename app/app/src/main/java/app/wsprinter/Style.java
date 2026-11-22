package app.wsprinter;

import android.os.RemoteException;

import org.json.JSONException;
import org.json.JSONObject;

import woyou.aidlservice.jiuiv5.IWoyouService;

// Tracks the printer's text style within one job. The "style" command changes it for
// the rest of the job; style keys on other commands apply to that command only and
// are put back afterwards.
final class Style {
    private static final String[] FLAGS = {
            "double_width", "double_height", "bold", "underline",
            "anti_white", "strikethrough", "italic", "invert"};
    private static final int FLAG_BASE = 1000;
    private static final String[] VALUES = {
            "right_spacing", "relative_position", "absolute_position",
            "line_spacing", "left_spacing", "strikethrough_style"};
    private static final int VALUE_BASE = 2000;
    private static final int ENABLE = 1;
    private static final int DISABLE = 2;
    static final float DEFAULT_SIZE = 24f;

    private int align;
    private float size = DEFAULT_SIZE;
    private final boolean[] flags = new boolean[FLAGS.length];

    static void validate(JSONObject c) throws Exception {
        if (c.has("align")) alignment(c.get("align"));
        if (c.has("size")) {
            Object value = c.get("size");
            if (!(value instanceof Number) || ((Number) value).doubleValue() < 1
                    || ((Number) value).doubleValue() > 256) throw new JobError("bad_request", "size must be 1..256");
        }
        if (c.has("font")) Commands.string(c, "font");
        for (String flag : FLAGS) if (c.has(flag) && !(c.get(flag) instanceof Boolean)) {
            throw new JobError("bad_request", flag + " must be boolean");
        }
        for (String value : VALUES) if (c.has(value)) Commands.integer(c.get(value), value, 0, 65535);
    }

    void set(IWoyouService p, JSONObject c) throws JSONException, JobError, RemoteException {
        if (c.has("align")) {
            align = alignment(c.get("align"));
            p.setAlignment(align, null);
        }
        if (c.has("size")) {
            size = (float) c.getDouble("size");
            p.setFontSize(size, null);
        }
        if (c.has("font")) {
            p.setFontName(c.getString("font"), null);
        }
        for (int i = 0; i < FLAGS.length; i++) {
            if (c.has(FLAGS[i])) {
                flags[i] = c.getBoolean(FLAGS[i]);
                p.setPrinterStyle(FLAG_BASE + i, flags[i] ? ENABLE : DISABLE);
            }
        }
        for (int i = 0; i < VALUES.length; i++) {
            if (c.has(VALUES[i])) {
                p.setPrinterStyle(VALUE_BASE + i, c.getInt(VALUES[i]));
            }
        }
    }

    private static boolean hasTextSize(JSONObject c) {
        String op = c.optString("op");
        return c.has("size") && !op.equals("qr") && !op.equals("code2d");
    }

    void push(IWoyouService p, JSONObject c) throws JSONException, JobError, RemoteException {
        if (c.has("align")) p.setAlignment(alignment(c.get("align")), null);
        if (hasTextSize(c)) p.setFontSize((float) c.getDouble("size"), null);
        for (int i = 0; i < FLAGS.length; i++) {
            if (c.has(FLAGS[i])) {
                p.setPrinterStyle(FLAG_BASE + i, c.getBoolean(FLAGS[i]) ? ENABLE : DISABLE);
            }
        }
    }

    void pop(IWoyouService p, JSONObject c) throws RemoteException {
        if (c.has("align")) p.setAlignment(align, null);
        if (hasTextSize(c)) p.setFontSize(size, null);
        for (int i = 0; i < FLAGS.length; i++) {
            if (c.has(FLAGS[i])) {
                p.setPrinterStyle(FLAG_BASE + i, flags[i] ? ENABLE : DISABLE);
            }
        }
    }

    static int alignment(Object value) throws JobError {
        if (value instanceof Number) {
            int n = Commands.integer(value, "align", 0, 2);
            if (n >= 0 && n <= 2) return n;
        } else if (value != null) {
            switch (value.toString().toLowerCase(java.util.Locale.ROOT)) {
                case "left": return 0;
                case "center": return 1;
                case "right": return 2;
            }
        }
        throw new JobError("bad_request", "align must be left, center or right");
    }
}
