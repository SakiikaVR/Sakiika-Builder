package __PKG__;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONObject;

/**
 * Permission state and permission requests.
 *
 * <p>Three kinds exist and they behave differently:
 * install-time (nothing to ask), runtime/dangerous (a system dialog), and
 * special ones like all-files access or overlay which can only be granted by
 * sending the user to a Settings screen. {@link #request} handles all three.
 */
public class PermApi extends ApiModule {

    public PermApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "perm";
    }

    @Override
    public String[] methods() {
        return new String[]{"declared", "check", "request", "shouldExplain",
                "openSettings", "openSpecial", "specialState"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "declared":
                return declared();
            case "check":
                return check(a);
            case "request":
                return request(a);
            case "shouldExplain":
                return shouldExplain(a);
            case "openSettings":
                return openAppSettings();
            case "openSpecial":
                return openSpecial(Jsonx.need(a, "kind"));
            case "specialState":
                return specialState();
            default:
                throw unknown(method);
        }
    }

    /** What the manifest actually declares — asking for anything else always fails. */
    private JSONObject declared() throws Exception {
        PackageInfo pi = act.getPackageManager()
                .getPackageInfo(act.getPackageName(), PackageManager.GET_PERMISSIONS);
        JSONObject out = new JSONObject();
        String[] names = pi.requestedPermissions == null ? new String[0] : pi.requestedPermissions;
        for (String n : names) {
            out.put(n, act.hasPermission(n));
        }
        return out;
    }

    /** Accepts short names ("CAMERA") or full ones ("android.permission.CAMERA"). */
    private static String normalize(String name) {
        if (name.contains(".")) {
            return name;
        }
        return "android.permission." + name;
    }

    private Object check(JSONObject a) throws Exception {
        String[] wanted = Jsonx.strings(a, "permissions");
        if (wanted.length == 0) {
            throw new BridgeError("permissions に権限名を 1 つ以上指定してください");
        }
        JSONObject results = new JSONObject();
        boolean all = true;
        for (String p : wanted) {
            boolean granted = act.hasPermission(normalize(p));
            results.put(p, granted);
            all = all && granted;
        }
        return Jsonx.obj("granted", all, "results", results);
    }

    private Object request(JSONObject a) throws Exception {
        String[] wanted = Jsonx.strings(a, "permissions");
        if (wanted.length == 0) {
            throw new BridgeError("permissions に権限名を 1 つ以上指定してください");
        }
        String[] full = new String[wanted.length];
        for (int i = 0; i < wanted.length; i++) {
            full[i] = normalize(wanted[i]);
        }
        long timeout = Jsonx.l(a, "timeoutMs", 180000);
        return act.requestPermissionsBlocking(full, timeout);
    }

    /**
     * True when the user has denied once but not permanently — the moment to
     * show your own explanation before asking again.
     */
    private Object shouldExplain(JSONObject a) throws Exception {
        String[] wanted = Jsonx.strings(a, "permissions");
        JSONObject out = new JSONObject();
        for (String p : wanted) {
            out.put(p, act.shouldShowRequestPermissionRationale(normalize(p)));
        }
        return out;
    }

    private Object openAppSettings() throws Exception {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", act.getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(i);
        return true;
    }

    /**
     * Sends the user to the Settings page for a permission that has no dialog,
     * then reports the state once they come back.
     */
    private Object openSpecial(String kind) throws Exception {
        String action;
        boolean withPackage = true;
        switch (kind) {
            case "allFiles":
                if (Build.VERSION.SDK_INT < 30) {
                    // Before Android 11 there is no such screen; the plain
                    // storage permission is the whole story.
                    return Jsonx.obj("granted", act.hasPermission(
                            "android.permission.READ_EXTERNAL_STORAGE"),
                            "note", "Android 10 以下では全ファイルアクセスの設定画面はありません");
                }
                action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION;
                break;
            case "overlay":
                action = Settings.ACTION_MANAGE_OVERLAY_PERMISSION;
                break;
            case "writeSettings":
                action = Settings.ACTION_MANAGE_WRITE_SETTINGS;
                break;
            case "batteryOptimization":
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;
                break;
            case "usageStats":
                action = Settings.ACTION_USAGE_ACCESS_SETTINGS;
                withPackage = false;
                break;
            case "notifications":
                if (Build.VERSION.SDK_INT >= 26) {
                    Intent n = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    n.putExtra(Settings.EXTRA_APP_PACKAGE, act.getPackageName());
                    act.startForResultBlocking(n, 600000);
                    return specialState();
                }
                return openAppSettings();
            case "exactAlarm":
                if (Build.VERSION.SDK_INT < 31) {
                    return Jsonx.obj("granted", true, "note", "Android 11 以下では不要です");
                }
                action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM;
                break;
            case "installUnknownApps":
                action = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES;
                break;
            default:
                throw new BridgeError("kind は allFiles/overlay/writeSettings/"
                        + "batteryOptimization/usageStats/notifications/exactAlarm/"
                        + "installUnknownApps のいずれかです");
        }
        Intent i = withPackage
                ? new Intent(action, Uri.parse("package:" + act.getPackageName()))
                : new Intent(action);
        act.startForResultBlocking(i, 600000);
        return specialState();
    }

    /** Current state of every "special" permission we know how to open. */
    private JSONObject specialState() {
        boolean allFiles;
        if (Build.VERSION.SDK_INT >= 30) {
            allFiles = Environment.isExternalStorageManager();
        } else {
            allFiles = act.hasPermission("android.permission.WRITE_EXTERNAL_STORAGE");
        }
        boolean notifications = true;
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    act.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            notifications = nm == null || nm.areNotificationsEnabled();
        } catch (Throwable ignored) {
        }
        boolean exactAlarm = true;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                android.app.AlarmManager am = (android.app.AlarmManager)
                        act.getSystemService(android.content.Context.ALARM_SERVICE);
                exactAlarm = am == null || am.canScheduleExactAlarms();
            }
        } catch (Throwable ignored) {
        }
        boolean battery = false;
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    act.getSystemService(android.content.Context.POWER_SERVICE);
            battery = pm != null && pm.isIgnoringBatteryOptimizations(act.getPackageName());
        } catch (Throwable ignored) {
        }
        return Jsonx.obj(
                "allFiles", allFiles,
                "overlay", Settings.canDrawOverlays(act),
                "writeSettings", Settings.System.canWrite(act),
                "notifications", notifications,
                "exactAlarm", exactAlarm,
                "batteryOptimizationIgnored", battery,
                "installUnknownApps", Build.VERSION.SDK_INT < 26
                        || act.getPackageManager().canRequestPackageInstalls());
    }
}
