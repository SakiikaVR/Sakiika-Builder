package __PKG__;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Other apps on the device: enumerate, inspect, launch, uninstall.
 *
 * <p>Android 11+ hides most of the package list unless the app holds
 * QUERY_ALL_PACKAGES, so a short list here usually means that permission is
 * missing rather than that the device is empty.
 */
public class PkgApi extends ApiModule {

    public PkgApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "pkg";
    }

    @Override
    public String[] methods() {
        return new String[]{"list", "info", "icon", "isInstalled", "launch", "uninstall",
                "install", "openDetails", "self"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "list":
                return list(a);
            case "info":
                return info(Jsonx.need(a, "package"));
            case "icon":
                return icon(a);
            case "isInstalled":
                return isInstalled(Jsonx.need(a, "package"));
            case "launch":
                return launch(Jsonx.need(a, "package"));
            case "uninstall":
                return uninstall(Jsonx.need(a, "package"));
            case "install":
                return install(a);
            case "openDetails":
                return openDetails(Jsonx.need(a, "package"));
            case "self":
                return info(act.getPackageName());
            default:
                throw unknown(method);
        }
    }

    private JSONObject list(JSONObject a) throws Exception {
        PackageManager pm = act.getPackageManager();
        boolean systemToo = Jsonx.b(a, "system", false);
        boolean launchableOnly = Jsonx.b(a, "launchableOnly", true);
        String search = Jsonx.str(a, "search", "").toLowerCase(java.util.Locale.ROOT);
        int limit = Math.max(1, Math.min(2000, Jsonx.i(a, "limit", 300)));

        List<PackageInfo> all = pm.getInstalledPackages(0);
        JSONArray out = new JSONArray();
        for (PackageInfo pi : all) {
            if (out.length() >= limit) {
                break;
            }
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) {
                continue;
            }
            boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (isSystem && !systemToo) {
                continue;
            }
            if (launchableOnly && pm.getLaunchIntentForPackage(pi.packageName) == null) {
                continue;
            }
            String label = String.valueOf(pm.getApplicationLabel(ai));
            if (!search.isEmpty()
                    && !label.toLowerCase(java.util.Locale.ROOT).contains(search)
                    && !pi.packageName.toLowerCase(java.util.Locale.ROOT).contains(search)) {
                continue;
            }
            out.put(Jsonx.obj(
                    "package", pi.packageName,
                    "label", label,
                    "versionName", pi.versionName,
                    "versionCode", Build.VERSION.SDK_INT >= 28
                            ? pi.getLongVersionCode() : (long) pi.versionCode,
                    "system", isSystem,
                    "enabled", ai.enabled,
                    "firstInstallTime", pi.firstInstallTime,
                    "lastUpdateTime", pi.lastUpdateTime,
                    "targetSdk", ai.targetSdkVersion));
        }
        return Jsonx.obj("count", out.length(), "total", all.size(), "apps", out,
                "note", Build.VERSION.SDK_INT >= 30
                        && !act.hasPermission("android.permission.QUERY_ALL_PACKAGES")
                        ? "Android 11+ では QUERY_ALL_PACKAGES がないと一部のアプリしか見えません"
                        : JSONObject.NULL);
    }

    private JSONObject info(String pkg) throws Exception {
        PackageManager pm = act.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            throw new BridgeError("not_installed", "インストールされていません: " + pkg);
        }
        ApplicationInfo ai = pi.applicationInfo;
        JSONArray perms = new JSONArray();
        if (pi.requestedPermissions != null) {
            for (String p : pi.requestedPermissions) {
                perms.put(p);
            }
        }
        return Jsonx.obj(
                "package", pi.packageName,
                "label", ai == null ? pkg : String.valueOf(pm.getApplicationLabel(ai)),
                "versionName", pi.versionName,
                "versionCode", Build.VERSION.SDK_INT >= 28
                        ? pi.getLongVersionCode() : (long) pi.versionCode,
                "requestedPermissions", perms,
                "firstInstallTime", pi.firstInstallTime,
                "lastUpdateTime", pi.lastUpdateTime,
                "targetSdk", ai == null ? -1 : ai.targetSdkVersion,
                "minSdk", ai == null ? -1 : ai.minSdkVersion,
                "dataDir", ai == null ? JSONObject.NULL : ai.dataDir,
                "sourceDir", ai == null ? JSONObject.NULL : ai.sourceDir,
                "system", ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                "debuggable", ai != null && (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                "launchable", pm.getLaunchIntentForPackage(pkg) != null,
                "installer", installerOf(pkg));
    }

    private Object installerOf(String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return act.getPackageManager().getInstallSourceInfo(pkg).getInstallingPackageName();
            }
            return act.getPackageManager().getInstallerPackageName(pkg);
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /** Launcher icon as a PNG data URL. */
    private JSONObject icon(JSONObject a) throws Exception {
        String pkg = Jsonx.need(a, "package");
        int max = Math.max(16, Math.min(512, Jsonx.i(a, "size", 96)));
        Drawable d;
        try {
            d = act.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            throw new BridgeError("not_installed", "インストールされていません: " + pkg);
        }
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : max;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : max;
        float scale = Math.min(1f, (float) max / Math.max(w, h));
        int tw = Math.max(1, Math.round(w * scale));
        int th = Math.max(1, Math.round(h * scale));
        Bitmap bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        d.setBounds(0, 0, tw, th);
        d.draw(canvas);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return Jsonx.obj("package", pkg, "width", tw, "height", th,
                "dataUrl", "data:image/png;base64,"
                        + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
    }

    private Object isInstalled(String pkg) {
        try {
            act.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Object launch(String pkg) throws Exception {
        Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            throw new BridgeError("not_launchable", "起動用の画面がありません: " + pkg);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent launch = i;
        return bridge.onUi(() -> {
            act.startActivity(launch);
            return Jsonx.obj("started", true, "package", pkg);
        });
    }

    @SuppressWarnings("deprecation")
    private Object uninstall(String pkg) throws Exception {
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
        MainActivity.ActResult r = act.startForResultBlocking(i, 600000);
        return Jsonx.obj("requested", true, "resultCode", r.resultCode,
                "stillInstalled", isInstalled(pkg));
    }

    /** Hands an APK to the system installer; the user still has to confirm. */
    private Object install(JSONObject a) throws Exception {
        String path = Jsonx.need(a, "path");
        java.io.File apk = new java.io.File(path);
        if (!apk.isFile()) {
            throw new BridgeError("not_found", "APK がありません: " + path);
        }
        java.io.File cached = new java.io.File(act.getCacheDir(), "install");
        if (!cached.exists() && !cached.mkdirs()) {
            throw new BridgeError("io", "作業フォルダーを作れません");
        }
        java.io.File dest = new java.io.File(cached, apk.getName());
        // The installer runs in another process, so the file has to be reachable
        // through our content provider rather than as a raw path.
        try (java.io.InputStream in = new java.io.FileInputStream(apk);
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        }
        Uri uri = ShareProvider.uriFor(act, dest);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent launch = i;
        return bridge.onUi(() -> {
            act.startActivity(launch);
            return Jsonx.obj("started", true, "uri", uri.toString());
        });
    }

    private Object openDetails(String pkg) throws Exception {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent launch = i;
        return bridge.onUi(() -> {
            act.startActivity(launch);
            return Jsonx.obj("opened", true, "package", pkg);
        });
    }
}
