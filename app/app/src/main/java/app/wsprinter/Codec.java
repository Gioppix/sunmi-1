package app.wsprinter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class Codec {
    private static final long MAX_DOWNLOAD = 16 * 1024 * 1024;

    private Codec() {
    }

    // Accepts a base64 string, {"base64": ...}, {"hex": ...}, {"bytes": [..]} or a plain array of byte values.
    static byte[] bytes(Object spec) throws JobError, JSONException {
        if (spec instanceof String) {
            return base64((String) spec);
        }
        if (spec instanceof JSONArray) {
            return fromArray((JSONArray) spec);
        }
        if (spec instanceof JSONObject) {
            JSONObject o = (JSONObject) spec;
            if (o.has("base64")) return base64(o.getString("base64"));
            if (o.has("hex")) return hex(o.getString("hex"));
            if (o.has("bytes")) return fromArray(o.getJSONArray("bytes"));
        }
        throw new JobError("bad_request", "expected base64, hex or bytes");
    }

    static byte[] commandBytes(JSONObject command) throws JobError, JSONException {
        if (command.has("base64")) return base64(command.getString("base64"));
        if (command.has("hex")) return hex(command.getString("hex"));
        if (command.has("bytes")) return fromArray(command.getJSONArray("bytes"));
        throw new JobError("bad_request", "raw needs base64, hex or bytes");
    }

    static Bitmap image(JSONObject spec, OkHttpClient http) throws JobError, JSONException {
        byte[] data;
        if (spec.has("url")) {
            data = download(spec.getString("url"), http);
        } else if (spec.has("base64")) {
            data = base64(spec.getString("base64"));
        } else {
            throw new JobError("bad_request", "image needs base64 or url");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 576
                || (long) bounds.outWidth * bounds.outHeight > 240000) {
            throw new JobError("bad_request", "image must be <=576 dots wide and <=240000 pixels; split larger images");
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) {
            throw new JobError("bad_request", "image data could not be decoded");
        }
        return bitmap;
    }

    static Bitmap image(Object spec, OkHttpClient http) throws JobError, JSONException {
        if (spec instanceof String) {
            return image(Json.obj("base64", spec), http);
        }
        if (spec instanceof JSONObject) {
            return image((JSONObject) spec, http);
        }
        throw new JobError("bad_request", "expected an image as base64 or {\"url\": ...}");
    }

    private static byte[] download(String url, OkHttpClient http) throws JobError {
        Request request;
        try {
            request = new Request.Builder().url(url).build();
        } catch (IllegalArgumentException e) {
            throw new JobError("bad_request", "invalid image URL");
        }
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new JobError("download_failed", "image download HTTP " + response.code());
            }
            if (body.contentLength() > MAX_DOWNLOAD) {
                throw new JobError("download_failed", "image larger than 16 MB");
            }
            try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (output.size() + count > MAX_DOWNLOAD) throw new JobError("download_failed", "image larger than 16 MB");
                    output.write(chunk, 0, count);
                }
                return output.toByteArray();
            }
        } catch (IOException e) {
            throw new JobError("download_failed", e.getClass().getSimpleName());
        }
    }

    static String base64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static byte[] base64(String text) throws JobError {
        try {
            return Base64.decode(text, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new JobError("bad_request", "invalid base64");
        }
    }

    private static byte[] hex(String text) throws JobError {
        String digits = text.replaceAll("[\\s:,]", "");
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            digits = digits.substring(2);
        }
        if (digits.length() % 2 != 0) {
            throw new JobError("bad_request", "hex string has an odd number of digits");
        }
        byte[] out = new byte[digits.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(digits.charAt(2 * i), 16);
            int lo = Character.digit(digits.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new JobError("bad_request", "invalid hex digit");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static byte[] fromArray(JSONArray array) throws JSONException, JobError {
        byte[] out = new byte[array.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Commands.integer(array.get(i), "byte", -128, 255);
        }
        return out;
    }
}
