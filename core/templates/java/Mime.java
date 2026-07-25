package __PKG__;

import java.util.Locale;

/** Extension → MIME type, with a few types Android's own map still misses. */
public final class Mime {

    private Mime() {
    }

    public static String guess(String name) {
        if (name == null) {
            return "application/octet-stream";
        }
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        String fromMap = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (fromMap != null) {
            return fromMap;
        }
        switch (ext) {
            case "json":
                return "application/json";
            case "md":
            case "markdown":
                return "text/markdown";
            case "txt":
            case "log":
            case "ini":
            case "cfg":
                return "text/plain";
            case "js":
            case "mjs":
                return "text/javascript";
            case "ts":
                return "text/typescript";
            case "yml":
            case "yaml":
                return "application/yaml";
            case "csv":
                return "text/csv";
            case "apk":
                return "application/vnd.android.package-archive";
            case "webp":
                return "image/webp";
            case "opus":
                return "audio/opus";
            default:
                return "application/octet-stream";
        }
    }
}
