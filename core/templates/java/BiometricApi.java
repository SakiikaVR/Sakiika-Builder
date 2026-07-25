package __PKG__;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;

import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Fingerprint / face unlock via the platform BiometricPrompt (API 28+).
 *
 * <p>This gates access to something in *your* page — it is not device security.
 * A determined user can always read the app's own files, so treat the result as
 * a UX gate, not a vault.
 */
public class BiometricApi extends ApiModule {

    public BiometricApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "biometric";
    }

    @Override
    public String[] methods() {
        return new String[]{"available", "authenticate", "deviceSecure"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "available":
                return available();
            case "authenticate":
                return authenticate(a);
            case "deviceSecure": {
                KeyguardManager km =
                        (KeyguardManager) act.getSystemService(Context.KEYGUARD_SERVICE);
                return Jsonx.obj(
                        "secure", km != null && km.isDeviceSecure(),
                        "locked", km != null && km.isKeyguardLocked());
            }
            default:
                throw unknown(method);
        }
    }

    private JSONObject available() {
        if (Build.VERSION.SDK_INT < 29) {
            // BiometricManager arrived in 29; on 28 we can only try the prompt.
            boolean maybe = act.getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_FINGERPRINT);
            return Jsonx.obj("available", maybe && Build.VERSION.SDK_INT >= 28,
                    "status", "unknown",
                    "note", "Android 10 未満では詳細な状態が取得できません");
        }
        BiometricManager bm = (BiometricManager) act.getSystemService(Context.BIOMETRIC_SERVICE);
        if (bm == null) {
            return Jsonx.obj("available", false, "status", "no_service");
        }
        int result = bm.canAuthenticate();
        String status;
        switch (result) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                status = "ok";
                break;
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                status = "no_hardware";
                break;
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                status = "hw_unavailable";
                break;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                status = "none_enrolled";
                break;
            default:
                status = "unknown_" + result;
                break;
        }
        return Jsonx.obj("available", result == BiometricManager.BIOMETRIC_SUCCESS,
                "status", status, "raw", result);
    }

    private Object authenticate(JSONObject a) throws Exception {
        if (Build.VERSION.SDK_INT < 28) {
            throw BridgeError.needsApi(28);
        }
        final String title = Jsonx.str(a, "title", "本人確認");
        final String subtitle = Jsonx.str(a, "subtitle", null);
        final String description = Jsonx.str(a, "description", null);
        final String negative = Jsonx.str(a, "cancel", "キャンセル");
        final ArrayBlockingQueue<JSONObject> answer = new ArrayBlockingQueue<>(1);
        final CancellationSignal cancel = new CancellationSignal();

        final Executor mainExecutor = act.getMainExecutor();
        bridge.onUi(() -> {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(act)
                    .setTitle(title)
                    .setNegativeButton(negative, mainExecutor,
                            (dialog, which) -> answer.offer(
                                    Jsonx.obj("ok", false, "reason", "cancelled")));
            if (subtitle != null) {
                b.setSubtitle(subtitle);
            }
            if (description != null) {
                b.setDescription(description);
            }
            b.build().authenticate(cancel, mainExecutor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            answer.offer(Jsonx.obj("ok", true));
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            answer.offer(Jsonx.obj("ok", false, "reason", "error",
                                    "code", errorCode, "message", String.valueOf(errString)));
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // Not terminal: the user can try another finger.
                            bridge.emit("biometric.failed", Jsonx.obj("attempt", "failed"));
                        }
                    });
            return true;
        });

        try {
            JSONObject result = answer.poll(Math.max(5000, Jsonx.l(a, "timeoutMs", 120000)),
                    TimeUnit.MILLISECONDS);
            if (result == null) {
                cancel.cancel();
                throw new BridgeError("timeout", "認証がタイムアウトしました");
            }
            return result;
        } catch (InterruptedException e) {
            cancel.cancel();
            throw new BridgeError("interrupted", "認証が中断されました");
        }
    }
}
