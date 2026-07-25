package __PKG__;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The whole app: one Activity hosting one WebView, plus the plumbing modules
 * need to run Intents and permission dialogs from a background thread.
 */
public class MainActivity extends Activity {

    public static final String PREFS = "sakiika";

    private WebView web;
    private Bridge bridge;
    private SharedPreferences prefs;
    private TextView errorView;

    private final AtomicInteger requestSeq = new AtomicInteger(9000);
    private final SparseArray<ArrayBlockingQueue<Object>> pending = new SparseArray<>();

    private static final int RC_FILE_CHOOSER = 8001;
    private ValueCallback<Uri[]> fileChooserCallback;

    // ------------------------------------------------------------- lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        suppressSplashIfDisabled();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (Cfg.FULLSCREEN) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(web);

        errorView = new TextView(this);
        errorView.setVisibility(View.GONE);
        errorView.setPadding(48, 48, 48, 48);
        errorView.setTextSize(15f);
        root.addView(errorView);

        setContentView(root);

        configureWebView();
        applyTheme(isDark());

        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "__SakiikaNative");

        if (Cfg.PULL_TO_REFRESH) {
            installPullToRefresh();
        }

        String url = savedInstanceState == null ? null : savedInstanceState.getString("url");
        web.loadUrl(url != null ? url : "file:///android_asset/" + Cfg.ENTRY);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null && web.getUrl() != null) {
            out.putString("url", web.getUrl());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bridge != null) {
            bridge.emit("app.resume", Jsonx.obj());
        }
    }

    @Override
    protected void onPause() {
        if (bridge != null) {
            bridge.emit("app.pause", Jsonx.obj());
        }
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (bridge != null) {
            boolean dark = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            bridge.emit("app.configChanged", Jsonx.obj(
                    "systemDark", dark,
                    "orientation", newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                            ? "landscape" : "portrait"));
        }
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.dispose();
        }
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (Cfg.BACK_NAVIGATES && web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    /**
     * Kills the Android 12+ system splash screen as fast as the platform allows.
     *
     * <p>There is no API to opt out entirely — the window always exists — so this
     * removes its view the instant the exit animation would start. The theme also
     * sets {@code windowDisablePreview} and a zero animation duration, which
     * together make it imperceptible.
     */
    private void suppressSplashIfDisabled() {
        if (Cfg.SPLASH || Build.VERSION.SDK_INT < 31) {
            return;
        }
        try {
            getSplashScreen().setOnExitAnimationListener(view -> view.remove());
        } catch (Throwable t) {
            Log.w(Bridge.TAG, "スプラッシュの抑制に失敗しました", t);
        }
    }

    // -------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(Cfg.JS_ENABLED);
        s.setDomStorageEnabled(Cfg.DOM_STORAGE);
        s.setDatabaseEnabled(Cfg.DATABASE);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(Cfg.UNIVERSAL_FILE_ACCESS);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportZoom(Cfg.ZOOM);
        s.setBuiltInZoomControls(Cfg.ZOOM);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(Cfg.MEDIA_GESTURE);
        s.setMixedContentMode(Cfg.MIXED_CONTENT
                ? WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(Cfg.ALLOW_GEOLOCATION);
        s.setTextZoom(100);
        if (!Cfg.USER_AGENT_SUFFIX.isEmpty()) {
            s.setUserAgentString(s.getUserAgentString() + " " + Cfg.USER_AGENT_SUFFIX);
        }
        if (Cfg.WEB_DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web.setBackgroundColor(isDark() ? Color.parseColor(Cfg.DARK_BG)
                : Color.parseColor(Cfg.LIGHT_BG));

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (bridge != null) {
                    bridge.emit("app.pageLoaded", Jsonx.obj("url", url));
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showError("読み込みに失敗しました\n" + request.getUrl() + "\n" + error.getDescription());
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(Bridge.TAG, "console[" + cm.messageLevel() + "] " + cm.message()
                        + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                if (!Cfg.ALLOW_MEDIA_CAPTURE) {
                    request.deny();
                    return;
                }
                grantWebRtc(request);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback cb) {
                if (!Cfg.ALLOW_GEOLOCATION) {
                    cb.invoke(origin, false, false);
                    return;
                }
                boolean fine = hasPermission("android.permission.ACCESS_FINE_LOCATION")
                        || hasPermission("android.permission.ACCESS_COARSE_LOCATION");
                if (fine) {
                    cb.invoke(origin, true, false);
                } else {
                    requestOnBackground(new String[]{"android.permission.ACCESS_FINE_LOCATION"},
                            origin, cb);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (!Cfg.HTML_FILE_INPUT) {
                    return false;
                }
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), RC_FILE_CHOOSER);
                    return true;
                } catch (Throwable t) {
                    fileChooserCallback = null;
                    Log.w(Bridge.TAG, "file chooser failed", t);
                    return false;
                }
            }
        });
    }

    private void grantWebRtc(final PermissionRequest request) {
        final List<String> needed = new ArrayList<>();
        for (String res : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                    && !hasPermission("android.permission.CAMERA")) {
                needed.add("android.permission.CAMERA");
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                    && !hasPermission("android.permission.RECORD_AUDIO")) {
                needed.add("android.permission.RECORD_AUDIO");
            }
        }
        if (needed.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        // requestPermissionsBlocking() must not run on the main thread.
        final String[] perms = needed.toArray(new String[0]);
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                try {
                    JSONObject r = requestPermissionsBlocking(perms, 120000);
                    ok = r.optBoolean("granted", false);
                } catch (Throwable t) {
                    ok = false;
                }
                final boolean granted = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (granted) {
                            request.grant(request.getResources());
                        } else {
                            request.deny();
                        }
                    }
                });
            }
        }, "sakiika-webrtc-perm").start();
    }

    private void requestOnBackground(final String[] perms, final String origin,
                                     final GeolocationPermissions.Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                try {
                    ok = requestPermissionsBlocking(perms, 120000).optBoolean("granted", false);
                } catch (Throwable t) {
                    ok = false;
                }
                final boolean granted = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cb.invoke(origin, granted, false);
                    }
                });
            }
        }, "sakiika-geo-perm").start();
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        boolean http = "http".equals(scheme) || "https".equals(scheme);
        if (http && !Cfg.EXTERNAL_LINKS_IN_BROWSER) {
            return false;
        }
        if ("file".equals(scheme) || "about".equals(scheme) || "blob".equals(scheme)
                || "data".equals(scheme) || "javascript".equals(scheme)) {
            return false;
        }
        // tel:, mailto:, intent:, geo:, market: and (optionally) http(s) leave the app.
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(Bridge.TAG, "no handler for " + uri, t);
            return true;
        }
    }

    private void installPullToRefresh() {
        web.setOnTouchListener(new View.OnTouchListener() {
            private float startY = -1f;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = web.getScrollY() == 0 ? e.getY() : -1f;
                        break;
                    case MotionEvent.ACTION_UP:
                        if (startY >= 0 && e.getY() - startY > 220f && web.getScrollY() == 0) {
                            web.reload();
                        }
                        startY = -1f;
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private void showError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                errorView.setText(message);
                errorView.setVisibility(View.VISIBLE);
            }
        });
    }

    // ---------------------------------------------------------------- theme

    public boolean isDark() {
        if ("dark".equals(Cfg.THEME)) {
            return true;
        }
        if ("light".equals(Cfg.THEME)) {
            return false;
        }
        boolean systemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return prefs.getBoolean("dark", systemDark);
    }

    public void setDark(boolean dark) {
        prefs.edit().putBoolean("dark", dark).apply();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyTheme(dark);
                if (web != null) {
                    web.setBackgroundColor(Color.parseColor(dark ? Cfg.DARK_BG : Cfg.LIGHT_BG));
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    public void applyTheme(boolean dark) {
        int bar = Color.parseColor(dark ? Cfg.DARK_BG : Cfg.LIGHT_BG);
        Window window = getWindow();
        window.setStatusBarColor(bar);
        window.setNavigationBarColor(bar);
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (dark) {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    // ------------------------------------------------- permissions / results

    public boolean hasPermission(String manifestName) {
        return checkSelfPermission(manifestName) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Shows the runtime permission dialog and waits for the answer.
     * Must be called off the main thread.
     *
     * @return {"granted":bool, "results":{perm:bool,…}}
     */
    public JSONObject requestPermissionsBlocking(String[] perms, long timeoutMs)
            throws BridgeError {
        if (getMainLooper().isCurrentThread()) {
            throw new BridgeError("internal",
                    "requestPermissionsBlocking をメインスレッドから呼ぶとデッドロックします");
        }
        List<String> missing = new ArrayList<>();
        for (String p : perms) {
            if (!hasPermission(p)) {
                missing.add(p);
            }
        }
        JSONObject out = new JSONObject();
        JSONObject results = new JSONObject();
        try {
            if (missing.isEmpty()) {
                for (String p : perms) {
                    results.put(p, true);
                }
                out.put("granted", true);
                out.put("results", results);
                return out;
            }
            int code = requestSeq.incrementAndGet() & 0xFFFF;
            ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
            synchronized (pending) {
                pending.put(code, queue);
            }
            final String[] ask = missing.toArray(new String[0]);
            final int rc = code;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestPermissions(ask, rc);
                }
            });
            Object payload = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            synchronized (pending) {
                pending.remove(code);
            }
            if (payload == null) {
                throw new BridgeError("timeout", "権限ダイアログの応答がありませんでした");
            }
            boolean all = true;
            for (String p : perms) {
                boolean granted = hasPermission(p);
                results.put(p, granted);
                all = all && granted;
            }
            out.put("granted", all);
            out.put("results", results);
            return out;
        } catch (BridgeError e) {
            throw e;
        } catch (Throwable t) {
            throw new BridgeError("internal", "権限リクエストに失敗: " + t);
        }
    }

    public static class ActResult {
        public final int resultCode;
        public final Intent data;

        ActResult(int resultCode, Intent data) {
            this.resultCode = resultCode;
            this.data = data;
        }

        public boolean ok() {
            return resultCode == Activity.RESULT_OK;
        }
    }

    /** Launches an Intent and waits for its result. Must be called off the main thread. */
    public ActResult startForResultBlocking(final Intent intent, long timeoutMs)
            throws BridgeError {
        if (getMainLooper().isCurrentThread()) {
            throw new BridgeError("internal",
                    "startForResultBlocking をメインスレッドから呼ぶとデッドロックします");
        }
        final int code = requestSeq.incrementAndGet() & 0xFFFF;
        ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        synchronized (pending) {
            pending.put(code, queue);
        }
        final BridgeError[] launchError = new BridgeError[1];
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    startActivityForResult(intent, code);
                } catch (Throwable t) {
                    launchError[0] = new BridgeError("no_activity",
                            "この Intent を処理できるアプリがありません: " + intent.getAction());
                    ArrayBlockingQueue<Object> q;
                    synchronized (pending) {
                        q = pending.get(code);
                    }
                    if (q != null) {
                        q.offer(new ActResult(Activity.RESULT_CANCELED, null));
                    }
                }
            }
        });
        try {
            Object payload = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (launchError[0] != null) {
                throw launchError[0];
            }
            if (payload == null) {
                throw new BridgeError("timeout", "画面の結果が返ってきませんでした");
            }
            return (ActResult) payload;
        } catch (InterruptedException e) {
            throw new BridgeError("interrupted", "待機が中断されました");
        } finally {
            synchronized (pending) {
                pending.remove(code);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        ArrayBlockingQueue<Object> q;
        synchronized (pending) {
            q = pending.get(requestCode);
        }
        if (q != null) {
            q.offer(Boolean.TRUE);
        }
        if (bridge != null) {
            JSONObject payload = new JSONObject();
            try {
                for (int i = 0; i < permissions.length; i++) {
                    payload.put(permissions[i], grants[i] == PackageManager.PERMISSION_GRANTED);
                }
            } catch (Throwable ignored) {
            }
            bridge.emit("perm.result", payload);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_FILE_CHOOSER) {
            ValueCallback<Uri[]> cb = fileChooserCallback;
            fileChooserCallback = null;
            if (cb != null) {
                cb.onReceiveValue(resultCode == RESULT_OK && data != null
                        ? WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                        : null);
            }
            return;
        }
        ArrayBlockingQueue<Object> q;
        synchronized (pending) {
            q = pending.get(requestCode);
        }
        if (q != null) {
            q.offer(new ActResult(resultCode, data));
        }
    }

    // -------------------------------------------------------------- helpers

    public SharedPreferences prefs() {
        return prefs;
    }

    public WebView webView() {
        return web;
    }

    public Bridge bridge() {
        return bridge;
    }
}
