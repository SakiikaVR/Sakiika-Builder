package __PKG__;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * A persistent key/value store. localStorage already exists in the WebView, but
 * it is wiped by "clear app data" heuristics on some OEM builds and is not
 * visible to native code — this is.
 */
public class PrefsApi extends ApiModule {

    private static final String STORE = "sakiika.kv";

    public PrefsApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "prefs";
    }

    @Override
    public String[] methods() {
        return new String[]{"get", "set", "remove", "clear", "keys", "all", "has"};
    }

    private SharedPreferences store() {
        return act.getSharedPreferences(STORE, android.content.Context.MODE_PRIVATE);
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        SharedPreferences sp = store();
        switch (method) {
            case "get": {
                String key = Jsonx.need(a, "key");
                if (!sp.contains(key)) {
                    return a.has("fallback") ? a.get("fallback") : JSONObject.NULL;
                }
                return decode(sp.getString(key, null));
            }
            case "set": {
                String key = Jsonx.need(a, "key");
                Object value = a.has("value") ? a.get("value") : JSONObject.NULL;
                sp.edit().putString(key, encode(value)).apply();
                return true;
            }
            case "remove":
                sp.edit().remove(Jsonx.need(a, "key")).apply();
                return true;
            case "clear":
                sp.edit().clear().apply();
                return true;
            case "has":
                return sp.contains(Jsonx.need(a, "key"));
            case "keys": {
                JSONArray out = new JSONArray();
                for (String k : sp.getAll().keySet()) {
                    out.put(k);
                }
                return out;
            }
            case "all": {
                JSONObject out = new JSONObject();
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    out.put(e.getKey(), decode(String.valueOf(e.getValue())));
                }
                return out;
            }
            default:
                throw unknown(method);
        }
    }

    /** Values round-trip through JSON so objects and numbers keep their type. */
    private static String encode(Object value) {
        JSONObject box = new JSONObject();
        try {
            box.put("v", Jsonx.wrap(value));
        } catch (Throwable ignored) {
        }
        return box.toString();
    }

    private static Object decode(String stored) {
        if (stored == null) {
            return JSONObject.NULL;
        }
        try {
            JSONObject box = new JSONObject(stored);
            return box.get("v");
        } catch (Throwable t) {
            // Written by an older build, or by native code — hand back the raw string.
            return stored;
        }
    }
}
