package __PKG__;

import android.os.Bundle;
import android.os.Parcelable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Turns arbitrary Java values into something org.json can serialise, and reads
 * loosely-typed values back out of JSON. Every bridge result passes through
 * {@link #wrap}, so a module can return a plain Java object and the JS side
 * still receives sane JSON.
 */
public final class Jsonx {

    private Jsonx() {
    }

    /** Deepest nesting we will walk before giving up and calling toString(). */
    private static final int MAX_DEPTH = 6;

    public static Object wrap(Object value) {
        return wrap(value, 0);
    }

    private static Object wrap(Object value, int depth) {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return value;
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            // JSON has no way to spell NaN or Infinity; null round-trips safely.
            return (Double.isNaN(d) || Double.isInfinite(d)) ? JSONObject.NULL : (Object) d;
        }
        if (value instanceof Character) {
            return value.toString();
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value;
        }
        if (value instanceof byte[]) {
            return android.util.Base64.encodeToString((byte[]) value, android.util.Base64.NO_WRAP);
        }
        if (depth >= MAX_DEPTH) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            JSONObject o = new JSONObject();
            for (Object e : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) e;
                try {
                    o.put(String.valueOf(entry.getKey()), wrap(entry.getValue(), depth + 1));
                } catch (Throwable ignored) {
                }
            }
            return o;
        }
        if (value instanceof Collection) {
            JSONArray a = new JSONArray();
            for (Object item : (Collection<?>) value) {
                a.put(wrap(item, depth + 1));
            }
            return a;
        }
        if (value.getClass().isArray()) {
            JSONArray a = new JSONArray();
            int n = Array.getLength(value);
            for (int i = 0; i < n; i++) {
                a.put(wrap(Array.get(value, i), depth + 1));
            }
            return a;
        }
        if (value instanceof Bundle) {
            Bundle b = (Bundle) value;
            JSONObject o = new JSONObject();
            for (String key : b.keySet()) {
                try {
                    o.put(key, wrap(b.get(key), depth + 1));
                } catch (Throwable ignored) {
                }
            }
            return o;
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Throwable) {
            Throwable t = (Throwable) value;
            JSONObject o = new JSONObject();
            try {
                o.put("type", t.getClass().getName());
                o.put("message", String.valueOf(t.getMessage()));
            } catch (Throwable ignored) {
            }
            return o;
        }
        if (value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof Parcelable || value instanceof android.net.Uri) {
            return String.valueOf(value);
        }
        // Anything else: a readable string beats an opaque failure.
        return String.valueOf(value);
    }

    public static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                o.put(String.valueOf(kv[i]), wrap(kv[i + 1]));
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    public static String str(JSONObject a, String key, String fallback) {
        if (a == null || !a.has(key) || a.isNull(key)) {
            return fallback;
        }
        return a.optString(key, fallback);
    }

    /** Same as {@link #str} but refuses to silently continue without a value. */
    public static String need(JSONObject a, String key) throws BridgeError {
        String v = str(a, key, null);
        if (v == null || v.isEmpty()) {
            throw new BridgeError("引数 '" + key + "' が必要です");
        }
        return v;
    }

    public static int i(JSONObject a, String key, int fallback) {
        return a == null ? fallback : a.optInt(key, fallback);
    }

    public static long l(JSONObject a, String key, long fallback) {
        return a == null ? fallback : a.optLong(key, fallback);
    }

    public static double d(JSONObject a, String key, double fallback) {
        return a == null ? fallback : a.optDouble(key, fallback);
    }

    public static boolean b(JSONObject a, String key, boolean fallback) {
        return a == null ? fallback : a.optBoolean(key, fallback);
    }

    public static JSONObject o(JSONObject a, String key) {
        return a == null ? null : a.optJSONObject(key);
    }

    public static JSONArray arr(JSONObject a, String key) {
        return a == null ? null : a.optJSONArray(key);
    }

    public static String[] strings(JSONObject a, String key) {
        JSONArray ja = arr(a, key);
        if (ja == null) {
            String single = str(a, key, null);
            return single == null ? new String[0] : new String[]{single};
        }
        String[] out = new String[ja.length()];
        for (int i = 0; i < ja.length(); i++) {
            out[i] = ja.optString(i, "");
        }
        return out;
    }

    public static Set<String> keys(JSONObject o) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (o != null) {
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                out.add(it.next());
            }
        }
        return out;
    }
}
