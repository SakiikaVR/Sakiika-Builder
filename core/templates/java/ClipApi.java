package __PKG__;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;

import org.json.JSONObject;

/**
 * Clipboard read/write. The DOM clipboard API needs a user gesture and a secure
 * origin, neither of which a file:// page reliably has, so this exists.
 */
public class ClipApi extends ApiModule {

    public ClipApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "clipboard";
    }

    @Override
    public String[] methods() {
        return new String[]{"read", "write", "clear", "hasText"};
    }

    private ClipboardManager cm() throws BridgeError {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            throw BridgeError.unsupported("クリップボード");
        }
        return cm;
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        final ClipboardManager cm = cm();
        switch (method) {
            case "read":
                return bridge.onUi(() -> {
                    ClipData data = cm.getPrimaryClip();
                    if (data == null || data.getItemCount() == 0) {
                        return Jsonx.obj("text", JSONObject.NULL, "empty", true);
                    }
                    ClipData.Item item = data.getItemAt(0);
                    CharSequence text = item.coerceToText(act);
                    return Jsonx.obj(
                            "text", text == null ? JSONObject.NULL : text.toString(),
                            "empty", false,
                            "uri", item.getUri() == null ? JSONObject.NULL
                                    : item.getUri().toString(),
                            "label", data.getDescription() == null ? JSONObject.NULL
                                    : String.valueOf(data.getDescription().getLabel()));
                });
            case "write": {
                final String text = Jsonx.need(a, "text");
                final String label = Jsonx.str(a, "label", "text");
                return bridge.onUi(() -> {
                    cm.setPrimaryClip(ClipData.newPlainText(label, text));
                    return true;
                });
            }
            case "clear":
                return bridge.onUi(() -> {
                    // clearPrimaryClip landed in API 28; an empty clip is the fallback.
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        cm.clearPrimaryClip();
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""));
                    }
                    return true;
                });
            case "hasText":
                return cm.hasPrimaryClip()
                        && cm.getPrimaryClipDescription() != null
                        && cm.getPrimaryClipDescription()
                        .hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
            default:
                throw unknown(method);
        }
    }
}
