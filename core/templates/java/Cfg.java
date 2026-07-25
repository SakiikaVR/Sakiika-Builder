package __PKG__;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Build settings, read at startup from {@code assets/__sakiika/config.json}.
 *
 * <p>These used to be compiled-in constants, which meant every project needed a
 * Java compiler. Reading them at run time lets one prebuilt template APK serve
 * every project, so the builder needs no JDK at all.
 *
 * <p>{@link #load} runs from {@code Application.attachBaseContext}, before any
 * ContentProvider or Activity exists, so the values are always ready.
 */
public final class Cfg {

    private static final String PATH = "__sakiika/config.json";

    private Cfg() {
    }

    public static String BUILDER_VERSION = "unknown";
    public static String APP_NAME = "App";
    public static String PACKAGE_NAME = "";
    public static String VERSION_NAME = "1.0";
    public static int VERSION_CODE = 1;
    public static String ENTRY = "index.html";

    /** OFF / APP_PRIVATE / FOLDER_PICK / DOCUMENTS / MEDIA_ONLY / FULL_MANAGER */
    public static String FILE_ACCESS = "APP_PRIVATE";
    public static boolean REFLECTION = true;
    public static boolean TRACE = false;
    public static String[] DECLARED_PERMISSIONS = new String[0];

    public static String THEME = "auto";
    public static boolean FULLSCREEN = false;
    public static boolean SPLASH = true;
    public static String LIGHT_BG = "#FFFFFFFF";
    public static String DARK_BG = "#FF121212";

    public static boolean JS_ENABLED = true;
    public static boolean DOM_STORAGE = true;
    public static boolean DATABASE = true;
    public static boolean UNIVERSAL_FILE_ACCESS = true;
    public static boolean MIXED_CONTENT = false;
    public static boolean ZOOM = false;
    public static boolean MEDIA_GESTURE = false;
    public static boolean WEB_DEBUG = false;
    public static String USER_AGENT_SUFFIX = "";
    public static boolean EXTERNAL_LINKS_IN_BROWSER = false;
    public static boolean BACK_NAVIGATES = true;
    public static boolean PULL_TO_REFRESH = false;
    public static boolean HTML_FILE_INPUT = true;
    public static boolean ALLOW_MEDIA_CAPTURE = true;
    public static boolean ALLOW_GEOLOCATION = true;

    /** Empty means every module is on. */
    private static Set<String> modules = Collections.emptySet();

    private static boolean loaded;

    public static synchronized void load(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        PACKAGE_NAME = context.getPackageName();
        JSONObject root = read(context);
        if (root == null) {
            Log.w(Bridge.TAG, PATH + " が読めなかったため既定値で動作します");
            return;
        }
        BUILDER_VERSION = root.optString("builderVersion", BUILDER_VERSION);
        APP_NAME = root.optString("appName", APP_NAME);
        VERSION_NAME = root.optString("versionName", VERSION_NAME);
        VERSION_CODE = root.optInt("versionCode", VERSION_CODE);
        ENTRY = root.optString("entry", ENTRY);

        FILE_ACCESS = root.optString("fileAccess", FILE_ACCESS);
        REFLECTION = root.optBoolean("reflection", REFLECTION);
        TRACE = root.optBoolean("trace", TRACE);
        DECLARED_PERMISSIONS = strings(root.optJSONArray("declaredPermissions"));
        modules = set(root.optJSONArray("modules"));

        THEME = root.optString("theme", THEME);
        FULLSCREEN = root.optBoolean("fullscreen", FULLSCREEN);
        SPLASH = root.optBoolean("splash", SPLASH);
        LIGHT_BG = root.optString("lightBackground", LIGHT_BG);
        DARK_BG = root.optString("darkBackground", DARK_BG);

        JSONObject web = root.optJSONObject("webview");
        if (web != null) {
            JS_ENABLED = web.optBoolean("javascriptEnabled", JS_ENABLED);
            DOM_STORAGE = web.optBoolean("domStorage", DOM_STORAGE);
            DATABASE = web.optBoolean("database", DATABASE);
            UNIVERSAL_FILE_ACCESS = web.optBoolean("allowUniversalFileAccess", UNIVERSAL_FILE_ACCESS);
            MIXED_CONTENT = web.optBoolean("mixedContent", MIXED_CONTENT);
            ZOOM = web.optBoolean("zoom", ZOOM);
            MEDIA_GESTURE = web.optBoolean("mediaPlaybackRequiresGesture", MEDIA_GESTURE);
            WEB_DEBUG = web.optBoolean("debuggable", WEB_DEBUG);
            USER_AGENT_SUFFIX = web.optString("userAgentSuffix", USER_AGENT_SUFFIX);
            EXTERNAL_LINKS_IN_BROWSER = web.optBoolean("externalLinksInBrowser", EXTERNAL_LINKS_IN_BROWSER);
            BACK_NAVIGATES = web.optBoolean("backNavigatesHistory", BACK_NAVIGATES);
            PULL_TO_REFRESH = web.optBoolean("pullToRefresh", PULL_TO_REFRESH);
            HTML_FILE_INPUT = web.optBoolean("htmlFileInput", HTML_FILE_INPUT);
            ALLOW_MEDIA_CAPTURE = web.optBoolean("allowMediaCapture", ALLOW_MEDIA_CAPTURE);
            ALLOW_GEOLOCATION = web.optBoolean("allowGeolocation", ALLOW_GEOLOCATION);
        }
    }

    /** Whether a bridge module should be exposed to the page. */
    public static boolean moduleEnabled(String name) {
        if ("fs".equals(name) && "OFF".equals(FILE_ACCESS)) {
            return false;
        }
        if ("reflect".equals(name) && !REFLECTION) {
            return false;
        }
        return modules.isEmpty() || modules.contains(name);
    }

    private static JSONObject read(Context context) {
        try (InputStream in = context.getAssets().open(PATH)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            Log.w(Bridge.TAG, "設定を読めません: " + PATH, t);
            return null;
        }
    }

    private static String[] strings(JSONArray array) {
        if (array == null) {
            return new String[0];
        }
        String[] out = new String[array.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.optString(i, "");
        }
        return out;
    }

    private static Set<String> set(JSONArray array) {
        Set<String> out = new LinkedHashSet<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }
}
