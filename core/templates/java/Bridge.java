package __PKG__;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single object exposed to JavaScript. Everything the page can reach goes
 * through {@link #invoke} (blocking, for cheap reads) or {@link #invokeAsync}
 * (promise-backed, for anything that touches IO, the UI thread, or a permission
 * dialog).
 *
 * <p>WebView calls {@code @JavascriptInterface} methods on a dedicated binder
 * thread, never the main thread, so blocking inside them is safe and does not
 * freeze the UI — but it <em>does</em> freeze the calling JS, which is why the
 * async path exists.
 */
public class Bridge {

    public static final String TAG = "Sakiika";

    private final MainActivity act;
    private final WebView web;
    private final Map<String, ApiModule> modules = new LinkedHashMap<>();
    private final ExecutorService pool;

    public Bridge(MainActivity act, WebView web) {
        this.act = act;
        this.web = web;
        final AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sakiika-bridge-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        register();
    }

    /** Skips modules the project disabled; see {@link Cfg#moduleEnabled}. */
    private void add(ApiModule m) {
        if (!Cfg.moduleEnabled(m.name())) {
            return;
        }
        modules.put(m.name(), m);
    }

    /**
     * Every module is compiled into the template APK; which ones the page can
     * see is decided here from the project settings.
     */
    private void register() {
        add(new SysApi(this));
        add(new UiApi(this));
        add(new PermApi(this));
        add(new FsApi(this));
        add(new PrefsApi(this));
        add(new ClipApi(this));
        add(new NetApi(this));
        add(new IntentApi(this));
        add(new SensorApi(this));
        add(new LocationApi(this));
        add(new MediaApi(this));
        add(new NotifyApi(this));
        add(new ContentApi(this));
        add(new PkgApi(this));
        add(new BiometricApi(this));
        add(new ReflectApi(this));
    }

    public MainActivity activity() {
        return act;
    }

    public WebView webView() {
        return web;
    }

    public ApiModule module(String name) {
        return modules.get(name);
    }

    // ---------------------------------------------------------------- dispatch

    /** Blocking call. Returns an envelope: {"ok":true,"value":…} or {"ok":false,…}. */
    @JavascriptInterface
    public String invoke(String module, String method, String argsJson) {
        return envelope(module, method, argsJson).toString();
    }

    /** Non-blocking call; the result arrives via {@code __sakiika.settle(callId, …)}. */
    @JavascriptInterface
    public void invokeAsync(final int callId, final String module, final String method,
                            final String argsJson) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject env = envelope(module, method, argsJson);
                settle(callId, env);
            }
        });
    }

    /** Module → method names, so the JS side can build a typed facade. */
    @JavascriptInterface
    public String describe() {
        JSONObject out = new JSONObject();
        try {
            for (Map.Entry<String, ApiModule> e : modules.entrySet()) {
                JSONArray names = new JSONArray();
                for (String m : e.getValue().methods()) {
                    names.put(m);
                }
                out.put(e.getKey(), names);
            }
        } catch (Throwable t) {
            Log.e(TAG, "describe failed", t);
        }
        return out.toString();
    }

    /** Build-time facts the page may want without a round trip. */
    @JavascriptInterface
    public String buildInfo() {
        return Jsonx.obj(
                "appName", Cfg.APP_NAME,
                "packageName", Cfg.PACKAGE_NAME,
                "versionName", Cfg.VERSION_NAME,
                "versionCode", Cfg.VERSION_CODE,
                "fileAccess", Cfg.FILE_ACCESS,
                "reflection", Cfg.REFLECTION,
                "declaredPermissions", Cfg.DECLARED_PERMISSIONS,
                "entry", Cfg.ENTRY,
                "builtBy", "Sakiika " + Cfg.BUILDER_VERSION
        ).toString();
    }

    private JSONObject envelope(String module, String method, String argsJson) {
        long t0 = System.nanoTime();
        try {
            ApiModule m = modules.get(module);
            if (m == null) {
                throw new BridgeError("unknown_module",
                        "モジュール '" + module + "' は無効か存在しません（有効: " + modules.keySet() + "）");
            }
            JSONObject args = parseArgs(argsJson);
            Object result = m.invoke(method, args);
            JSONObject env = new JSONObject();
            env.put("ok", true);
            env.put("value", Jsonx.wrap(result));
            if (Cfg.TRACE) {
                Log.d(TAG, module + "." + method + " ok in "
                        + ((System.nanoTime() - t0) / 1000000L) + "ms");
            }
            return env;
        } catch (BridgeError e) {
            if (Cfg.TRACE) {
                Log.w(TAG, module + "." + method + " -> " + e.code + ": " + e.getMessage());
            }
            return errorEnvelope(e.code, e.getMessage(), null);
        } catch (Throwable t) {
            Log.e(TAG, "bridge call failed: " + module + "." + method, t);
            return errorEnvelope("internal", describeThrowable(t), t);
        }
    }

    private static String describeThrowable(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static JSONObject errorEnvelope(String code, String message, Throwable t) {
        JSONObject env = new JSONObject();
        try {
            env.put("ok", false);
            JSONObject err = new JSONObject();
            err.put("code", code == null ? "internal" : code);
            err.put("message", message == null ? "（詳細なし）" : message);
            if (t != null && Cfg.TRACE) {
                StringBuilder sb = new StringBuilder();
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < st.length && i < 8; i++) {
                    sb.append(st[i].toString()).append('\n');
                }
                err.put("stack", sb.toString());
            }
            env.put("error", err);
        } catch (Throwable ignored) {
        }
        return env;
    }

    private static JSONObject parseArgs(String argsJson) throws BridgeError {
        if (argsJson == null || argsJson.isEmpty() || "null".equals(argsJson)) {
            return new JSONObject();
        }
        try {
            return new JSONObject(argsJson);
        } catch (Throwable t) {
            throw new BridgeError("bad_args", "引数の JSON が壊れています: " + argsJson);
        }
    }

    // ------------------------------------------------------------ back to JS

    private void settle(int callId, JSONObject envelope) {
        eval("window.__sakiika&&window.__sakiika.settle(" + callId + "," + envelope + ")");
    }

    /** Push an event to the page: {@code Android.on(channel, handler)}. */
    public void emit(String channel, Object payload) {
        eval("window.__sakiika&&window.__sakiika.emit(" + JSONObject.quote(channel) + ","
                + jsonLiteral(payload) + ")");
    }

    /** Renders any value as a JSON literal that can be pasted into JS source. */
    private static String jsonLiteral(Object payload) {
        Object w = Jsonx.wrap(payload);
        if (w == JSONObject.NULL) {
            return "null";
        }
        if (w instanceof JSONObject || w instanceof JSONArray || w instanceof Boolean
                || w instanceof Number) {
            return w.toString();
        }
        return JSONObject.quote(String.valueOf(w));
    }

    private void eval(final String js) {
        web.post(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript(js, null);
                } catch (Throwable t) {
                    Log.w(TAG, "evaluateJavascript failed", t);
                }
            }
        });
    }

    // ------------------------------------------------------------- utilities

    /**
     * Runs {@code c} on the main thread and waits for it. Safe from a bridge
     * thread; throws if called from the main thread to make the deadlock loud
     * rather than mysterious.
     */
    public <T> T onUi(final Callable<T> c) throws Exception {
        if (act.getMainLooper().isCurrentThread()) {
            return c.call();
        }
        FutureTask<T> task = new FutureTask<>(c);
        act.runOnUiThread(task);
        try {
            return task.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new BridgeError("internal", describeThrowable(cause));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new BridgeError("timeout", "UI スレッドの処理が 30 秒で完了しませんでした");
        }
    }

    public void dispose() {
        for (ApiModule m : modules.values()) {
            try {
                m.dispose();
            } catch (Throwable t) {
                Log.w(TAG, "dispose failed for " + m.name(), t);
            }
        }
        pool.shutdownNow();
    }
}
