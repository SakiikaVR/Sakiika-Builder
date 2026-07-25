package __PKG__;

/**
 * A failure that is the page's fault (bad arguments, denied permission, missing
 * capability) rather than a crash. These become a rejected JS promise carrying
 * {@code code} and {@code message}; anything else becomes an "internal" error.
 */
public class BridgeError extends Exception {

    public final String code;

    public BridgeError(String message) {
        this("bad_request", message);
    }

    public BridgeError(String code, String message) {
        super(message);
        this.code = code;
    }

    public static BridgeError denied(String permission) {
        return new BridgeError("permission_denied", "権限が許可されていません: " + permission);
    }

    public static BridgeError unsupported(String what) {
        return new BridgeError("unsupported", "この端末では利用できません: " + what);
    }

    public static BridgeError disabled(String what) {
        return new BridgeError("disabled",
                what + " はこのアプリのビルド設定で無効化されています");
    }

    public static BridgeError needsApi(int level) {
        return new BridgeError("unsupported",
                "Android API " + level + " 以上が必要です（この端末は "
                        + android.os.Build.VERSION.SDK_INT + "）");
    }
}
