package app.wsprinter;

import android.graphics.Bitmap;
import com.sunmi.trans.TransBean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.ITax;
import woyou.aidlservice.jiuiv5.IWoyouService;

/** Only methods declared by the printer AIDL can be invoked. */
final class MethodCall {
    static final class Prepared implements AutoCloseable {
        final Method method;
        final Object[] args;
        final Callback callback = new Callback();
        Callback.Tax tax;
        boolean hasCallback;
        Long waitOverride;
        final List<Bitmap> images = new ArrayList<>();

        Prepared(Method method) {
            this.method = method;
            args = new Object[method.getParameterTypes().length];
        }

        JSONObject invoke(IWoyouService printer, long waitMs) throws Exception {
            if (waitMs > 0 && waitOverride != null) waitMs = waitOverride;
            Object value;
            try {
                value = method.invoke(printer, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new JobError("printer_exception", String.valueOf(cause));
            }
            if (waitMs > 0 && hasCallback) {
                boolean commit = method.getName().equals("commitPrinterBufferWithCallback")
                        || (method.getName().equals("exitPrinterBufferWithCallback") && Boolean.TRUE.equals(args[0]));
                boolean received = commit ? callback.awaitPrint(waitMs) : callback.awaitAny(waitMs);
                if (!received) throw new JobError("outcome_unknown", "Printer callback timed out; do not automatically reprint");
                check();
            }
            if (waitMs > 0 && tax != null && !tax.await(waitMs)) {
                throw new JobError("outcome_unknown", "Tax callback timed out");
            }
            JSONObject out = new JSONObject();
            if (method.getReturnType() != void.class) Json.put(out, "return", value == null ? JSONObject.NULL : value);
            if (hasCallback) Json.put(out, "callback", callback.toJson());
            if (tax != null && tax.data != null) Json.put(out, "tax", Codec.base64(tax.data));
            return out;
        }

        void check() throws JobError {
            if (callback.failed()) throw new JobError("printer_error", "Printer rejected command")
                    .with("callback", callback.toJson());
        }

        @Override public void close() {
            for (Bitmap image : images) image.recycle();
        }
    }

    static Prepared prepare(JSONObject command, OkHttpClient http) throws Exception {
        String name = command.getString("method");
        JSONArray args = command.has("args") ? command.getJSONArray("args") : new JSONArray();
        Method method = find(name, args.length());
        Prepared out = new Prepared(method);
        try {
            if (command.has("wait_ms")) out.waitOverride = (long) Commands.integer(command.get("wait_ms"), "wait_ms", 0, 120000);
            int next = 0;
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] == ICallback.class) {
                    out.args[i] = out.callback;
                    out.hasCallback = true;
                } else if (types[i] == ITax.class) {
                    out.args[i] = out.tax = new Callback.Tax();
                } else {
                    out.args[i] = convert(types[i], args, next++, http);
                    if (out.args[i] instanceof Bitmap) out.images.add((Bitmap) out.args[i]);
                }
            }
            return out;
        } catch (Exception e) {
            out.close();
            throw e;
        }
    }

    private static Method find(String name, int count) throws JobError {
        for (Method method : IWoyouService.class.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            int size = 0;
            for (Class<?> type : method.getParameterTypes()) if (type != ICallback.class && type != ITax.class) size++;
            if (size == count) return method;
        }
        throw new JobError("bad_request", "Unknown printer method or argument count: " + name);
    }

    private static Object convert(Class<?> type, JSONArray args, int index, OkHttpClient http) throws Exception {
        Object value = args.get(index);
        if (type == int.class) return Commands.integer(value, "argument " + index, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (type == float.class) {
            if (!(value instanceof Number) || (Double.isNaN(((Number) value).doubleValue()) || Double.isInfinite(((Number) value).doubleValue()))) throw new JobError("bad_request", "Expected finite number");
            float result = ((Number) value).floatValue();
            if (Float.isInfinite(result)) throw new JobError("bad_request", "Number too large");
            return result;
        }
        if (type == boolean.class && value instanceof Boolean) return value;
        if (type == String.class && (value instanceof String || value == JSONObject.NULL)) return value == JSONObject.NULL ? null : value;
        if (type == byte[].class) return Codec.bytes(value);
        if (type == Bitmap.class) return Codec.image(value, http);
        if (type == String[].class) {
            JSONArray array = args.getJSONArray(index);
            String[] result = new String[array.length()];
            for (int i = 0; i < result.length; i++) {
                if (!(array.get(i) instanceof String)) throw new JobError("bad_request", "Expected string array");
                result[i] = array.getString(i);
            }
            return result;
        }
        if (type == int[].class) {
            JSONArray array = args.getJSONArray(index);
            int[] result = new int[array.length()];
            for (int i = 0; i < result.length; i++) result[i] = Commands.integer(array.get(i), "array value", Integer.MIN_VALUE, Integer.MAX_VALUE);
            return result;
        }
        if (type == TransBean[].class) {
            JSONArray array = args.getJSONArray(index);
            TransBean[] result = new TransBean[array.length()];
            for (int i = 0; i < result.length; i++) {
                JSONObject bean = array.getJSONObject(i);
                result[i] = new TransBean((byte) Commands.integer(bean.get("type"), "type", 0, 255),
                        bean.optString("text"), bean.has("data") ? Codec.bytes(bean.get("data")) : null);
            }
            return result;
        }
        throw new JobError("bad_request", "Wrong type for argument " + index + "; expected " + type.getSimpleName());
    }
}
