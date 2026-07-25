package __PKG__;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Everything that changes what the user sees or feels. */
public class UiApi extends ApiModule {

    public UiApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "ui";
    }

    @Override
    public String[] methods() {
        return new String[]{"toast", "vibrate", "cancelVibrate", "setDark", "isDark",
                "setBarColor", "setFullscreen", "keepScreenOn", "setBrightness", "getBrightness",
                "setOrientation", "share", "alert", "confirm", "prompt", "pick",
                "reload", "setTitle", "exit"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "toast":
                return toast(a);
            case "vibrate":
                return vibrate(a);
            case "cancelVibrate":
                vibrator().cancel();
                return true;
            case "setDark":
                act.setDark(Jsonx.b(a, "dark", true));
                return true;
            case "isDark":
                return act.isDark();
            case "setBarColor":
                return setBarColor(a);
            case "setFullscreen":
                return setFullscreen(Jsonx.b(a, "on", true));
            case "keepScreenOn":
                return keepScreenOn(Jsonx.b(a, "on", true));
            case "setBrightness":
                return setBrightness(a);
            case "getBrightness":
                return getBrightness();
            case "setOrientation":
                return setOrientation(Jsonx.str(a, "mode", "unspecified"));
            case "share":
                return share(a);
            case "alert":
                return dialog(a, "alert");
            case "confirm":
                return dialog(a, "confirm");
            case "prompt":
                return dialog(a, "prompt");
            case "pick":
                return pick(a);
            case "reload":
                return bridge.onUi(() -> {
                    act.webView().reload();
                    return true;
                });
            case "setTitle":
                final String t = Jsonx.need(a, "title");
                return bridge.onUi(() -> {
                    act.setTitle(t);
                    return true;
                });
            case "exit":
                return bridge.onUi(() -> {
                    act.finishAffinity();
                    return true;
                });
            default:
                throw unknown(method);
        }
    }

    private Object toast(JSONObject a) throws Exception {
        final String text = Jsonx.need(a, "text");
        final boolean lng = "long".equals(Jsonx.str(a, "duration", "short"));
        return bridge.onUi(() -> {
            Toast.makeText(act, text, lng ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private Vibrator vibrator() throws BridgeError {
        Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) {
            throw BridgeError.unsupported("バイブレーター");
        }
        return v;
    }

    @SuppressWarnings("deprecation")
    private Object vibrate(JSONObject a) throws Exception {
        Vibrator v = vibrator();
        JSONArray pattern = Jsonx.arr(a, "pattern");
        int amplitude = Jsonx.i(a, "amplitude", -1);
        if (pattern != null) {
            long[] ms = new long[pattern.length()];
            for (int i = 0; i < ms.length; i++) {
                ms[i] = Math.max(0, pattern.optLong(i, 0));
            }
            int repeat = Jsonx.i(a, "repeat", -1);
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(ms, repeat));
            } else {
                v.vibrate(ms, repeat);
            }
            return true;
        }
        long duration = Jsonx.l(a, "ms", 40);
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(duration,
                    amplitude > 0 ? Math.min(255, amplitude) : VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(duration);
        }
        return true;
    }

    private Object setBarColor(JSONObject a) throws Exception {
        final String status = Jsonx.str(a, "status", null);
        final String nav = Jsonx.str(a, "navigation", null);
        final Boolean lightIcons = a.has("lightIcons") ? a.optBoolean("lightIcons") : null;
        return bridge.onUi(() -> {
            if (status != null) {
                act.getWindow().setStatusBarColor(Color.parseColor(status));
            }
            if (nav != null) {
                act.getWindow().setNavigationBarColor(Color.parseColor(nav));
            }
            if (lightIcons != null) {
                View decor = act.getWindow().getDecorView();
                int flags = decor.getSystemUiVisibility();
                // "light icons" means white glyphs, i.e. the *dark background* flags off.
                if (lightIcons) {
                    flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
            return true;
        });
    }

    private Object setFullscreen(final boolean on) throws Exception {
        return bridge.onUi(() -> {
            View decor = act.getWindow().getDecorView();
            if (on) {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                act.applyTheme(act.isDark());
            }
            return true;
        });
    }

    private Object keepScreenOn(final boolean on) throws Exception {
        return bridge.onUi(() -> {
            if (on) {
                act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            return true;
        });
    }

    /**
     * Window-local brightness (0..1, or -1 to follow the system). Changing the
     * *system* setting needs WRITE_SETTINGS and is deliberately not done here.
     */
    private Object setBrightness(JSONObject a) throws Exception {
        final float value = (float) Jsonx.d(a, "value", -1);
        if (value != -1 && (value < 0 || value > 1)) {
            throw new BridgeError("value は 0〜1、またはシステム追従なら -1 を指定してください");
        }
        return bridge.onUi(() -> {
            WindowManager.LayoutParams lp = act.getWindow().getAttributes();
            lp.screenBrightness = value;
            act.getWindow().setAttributes(lp);
            return true;
        });
    }

    private JSONObject getBrightness() {
        int sys = -1;
        try {
            sys = Settings.System.getInt(act.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
        } catch (Throwable ignored) {
        }
        return Jsonx.obj(
                "window", act.getWindow().getAttributes().screenBrightness,
                "systemRaw", sys,
                "system", sys < 0 ? -1.0 : sys / 255.0);
    }

    private Object setOrientation(String mode) throws Exception {
        final int value;
        switch (mode) {
            case "portrait":
                value = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case "landscape":
                value = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case "sensor":
                value = ActivityInfo.SCREEN_ORIENTATION_SENSOR;
                break;
            case "locked":
                value = ActivityInfo.SCREEN_ORIENTATION_LOCKED;
                break;
            case "unspecified":
                value = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                break;
            default:
                throw new BridgeError("mode は portrait/landscape/sensor/locked/unspecified");
        }
        return bridge.onUi(() -> {
            act.setRequestedOrientation(value);
            return true;
        });
    }

    private Object share(JSONObject a) throws Exception {
        final String text = Jsonx.str(a, "text", null);
        final String subject = Jsonx.str(a, "subject", null);
        final String uri = Jsonx.str(a, "uri", null);
        final String mime = Jsonx.str(a, "mime", uri != null ? "*/*" : "text/plain");
        if (text == null && uri == null) {
            throw new BridgeError("text か uri のどちらかが必要です");
        }
        return bridge.onUi(() -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            if (text != null) {
                i.putExtra(Intent.EXTRA_TEXT, text);
            }
            if (subject != null) {
                i.putExtra(Intent.EXTRA_SUBJECT, subject);
            }
            if (uri != null) {
                i.putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            act.startActivity(Intent.createChooser(i, Jsonx.str(a, "title", "共有")));
            return true;
        });
    }

    /**
     * Native dialogs. The page could use window.alert, but those are blocking,
     * unstyled and impossible to await a text answer from.
     */
    private Object dialog(JSONObject a, final String kind) throws Exception {
        final String title = Jsonx.str(a, "title", null);
        final String message = Jsonx.str(a, "message", "");
        final String ok = Jsonx.str(a, "ok", "OK");
        final String cancel = Jsonx.str(a, "cancel", "キャンセル");
        final String initial = Jsonx.str(a, "value", "");
        final ArrayBlockingQueue<Object[]> answer = new ArrayBlockingQueue<>(1);

        bridge.onUi(() -> {
            AlertDialog.Builder b = new AlertDialog.Builder(act);
            if (title != null) {
                b.setTitle(title);
            }
            final EditText input;
            if ("prompt".equals(kind)) {
                input = new EditText(act);
                input.setText(initial);
                input.setSelection(initial.length());
                b.setView(input);
                if (!message.isEmpty()) {
                    b.setMessage(message);
                }
            } else {
                input = null;
                b.setMessage(message);
            }
            b.setPositiveButton(ok, (d, w) -> answer.offer(new Object[]{
                    Boolean.TRUE, input == null ? null : input.getText().toString()}));
            if (!"alert".equals(kind)) {
                b.setNegativeButton(cancel, (d, w) ->
                        answer.offer(new Object[]{Boolean.FALSE, null}));
            }
            b.setOnCancelListener(d -> answer.offer(new Object[]{Boolean.FALSE, null}));
            b.setCancelable(!"alert".equals(kind));
            b.show();
            return true;
        });

        Object[] result = answer.poll(5, TimeUnit.MINUTES);
        if (result == null) {
            throw new BridgeError("timeout", "ダイアログの応答がありませんでした");
        }
        boolean confirmed = Boolean.TRUE.equals(result[0]);
        if ("alert".equals(kind)) {
            return true;
        }
        if ("confirm".equals(kind)) {
            return confirmed;
        }
        return confirmed ? Jsonx.obj("ok", true, "value", result[1])
                : Jsonx.obj("ok", false, "value", JSONObject.NULL);
    }

    /** A single-choice list dialog; resolves to the chosen index, or -1. */
    private Object pick(JSONObject a) throws Exception {
        final String[] items = Jsonx.strings(a, "items");
        if (items.length == 0) {
            throw new BridgeError("items に 1 つ以上の選択肢が必要です");
        }
        final String title = Jsonx.str(a, "title", null);
        final ArrayBlockingQueue<Integer> answer = new ArrayBlockingQueue<>(1);
        bridge.onUi(() -> {
            AlertDialog.Builder b = new AlertDialog.Builder(act);
            if (title != null) {
                b.setTitle(title);
            }
            b.setItems(items, (d, which) -> answer.offer(which));
            b.setOnCancelListener(d -> answer.offer(-1));
            b.show();
            return true;
        });
        Integer which = answer.poll(5, TimeUnit.MINUTES);
        if (which == null) {
            throw new BridgeError("timeout", "選択されませんでした");
        }
        return Jsonx.obj("index", which,
                "value", which >= 0 ? items[which] : JSONObject.NULL);
    }
}
