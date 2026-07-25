package __PKG__;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.Locale;
import java.util.TimeZone;

/** Read-only facts about the device, the build, the screen and the battery. */
public class SysApi extends ApiModule {

    public SysApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "sys";
    }

    @Override
    public String[] methods() {
        return new String[]{"info", "build", "screen", "battery", "locale", "memory",
                "storage", "features", "uptime", "androidId", "app"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "info":
                return info();
            case "build":
                return build();
            case "screen":
                return screen();
            case "battery":
                return battery();
            case "locale":
                return locale();
            case "memory":
                return memory();
            case "storage":
                return storage();
            case "features":
                return features();
            case "uptime":
                return Jsonx.obj(
                        "elapsedRealtimeMs", android.os.SystemClock.elapsedRealtime(),
                        "uptimeMs", android.os.SystemClock.uptimeMillis(),
                        "currentTimeMs", System.currentTimeMillis());
            case "androidId":
                return Settings.Secure.getString(act.getContentResolver(),
                        Settings.Secure.ANDROID_ID);
            case "app":
                return app();
            default:
                throw unknown(method);
        }
    }

    private JSONObject info() {
        return Jsonx.obj(
                "manufacturer", Build.MANUFACTURER,
                "brand", Build.BRAND,
                "model", Build.MODEL,
                "device", Build.DEVICE,
                "product", Build.PRODUCT,
                "hardware", Build.HARDWARE,
                "androidRelease", Build.VERSION.RELEASE,
                "sdkInt", Build.VERSION.SDK_INT,
                "abis", Build.SUPPORTED_ABIS,
                "isEmulator", looksLikeEmulator());
    }

    private JSONObject build() {
        return Jsonx.obj(
                "fingerprint", Build.FINGERPRINT,
                "id", Build.ID,
                "display", Build.DISPLAY,
                "type", Build.TYPE,
                "tags", Build.TAGS,
                "host", Build.HOST,
                "bootloader", Build.BOOTLOADER,
                "securityPatch", Build.VERSION.SECURITY_PATCH,
                "codename", Build.VERSION.CODENAME,
                "incremental", Build.VERSION.INCREMENTAL);
    }

    private static boolean looksLikeEmulator() {
        String f = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        return f.startsWith("generic") || f.contains("vbox") || f.contains("emulator")
                || "goldfish".equals(Build.HARDWARE) || "ranchu".equals(Build.HARDWARE)
                || (Build.MODEL != null && Build.MODEL.contains("Emulator"));
    }

    @SuppressWarnings("deprecation")
    private JSONObject screen() throws Exception {
        return bridge.onUi(() -> {
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            WindowManager wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            return Jsonx.obj(
                    "widthPx", dm.widthPixels,
                    "heightPx", dm.heightPixels,
                    "density", dm.density,
                    "densityDpi", dm.densityDpi,
                    "scaledDensity", dm.scaledDensity,
                    "widthDp", Math.round(dm.widthPixels / dm.density),
                    "heightDp", Math.round(dm.heightPixels / dm.density),
                    "refreshRate", d.getRefreshRate(),
                    "rotation", d.getRotation() * 90,
                    "orientation", dm.widthPixels >= dm.heightPixels ? "landscape" : "portrait",
                    "systemDark", (act.getResources().getConfiguration().uiMode
                            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        });
    }

    private JSONObject battery() {
        Intent status = act.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager bm = (BatteryManager) act.getSystemService(Context.BATTERY_SERVICE);
        int level = -1;
        int plugged = -1;
        int health = -1;
        int temperature = -1;
        int voltage = -1;
        if (status != null) {
            int raw = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (raw >= 0 && scale > 0) {
                level = Math.round(raw * 100f / scale);
            }
            plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            health = status.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            temperature = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            voltage = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        }
        String source = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "ac"
                : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "usb"
                : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless"
                : "battery";
        return Jsonx.obj(
                "level", level,
                "charging", plugged > 0,
                "source", source,
                "health", health,
                "temperatureC", temperature < 0 ? -1 : temperature / 10.0,
                "voltageMv", voltage,
                "isPowerSaveMode", isPowerSave(),
                "capacityUah", bm == null ? -1
                        : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER));
    }

    private boolean isPowerSave() {
        android.os.PowerManager pm =
                (android.os.PowerManager) act.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isPowerSaveMode();
    }

    private JSONObject locale() {
        Locale l = Locale.getDefault();
        TimeZone tz = TimeZone.getDefault();
        return Jsonx.obj(
                "language", l.getLanguage(),
                "country", l.getCountry(),
                "tag", l.toLanguageTag(),
                "displayName", l.getDisplayName(),
                "timeZone", tz.getID(),
                "timeZoneOffsetMin", tz.getOffset(System.currentTimeMillis()) / 60000,
                "is24Hour", android.text.format.DateFormat.is24HourFormat(act));
    }

    private JSONObject memory() {
        ActivityManager am = (ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) {
            am.getMemoryInfo(mi);
        }
        Runtime rt = Runtime.getRuntime();
        return Jsonx.obj(
                "totalDevice", mi.totalMem,
                "availDevice", mi.availMem,
                "lowMemory", mi.lowMemory,
                "jvmMax", rt.maxMemory(),
                "jvmTotal", rt.totalMemory(),
                "jvmFree", rt.freeMemory());
    }

    private JSONObject storage() {
        JSONObject out = new JSONObject();
        try {
            out.put("internal", statOf(Environment.getDataDirectory().getAbsolutePath()));
            java.io.File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                out.put("shared", statOf(ext.getAbsolutePath()));
                out.put("sharedPath", ext.getAbsolutePath());
            }
            out.put("appFiles", act.getFilesDir().getAbsolutePath());
            out.put("appCache", act.getCacheDir().getAbsolutePath());
            java.io.File extFiles = act.getExternalFilesDir(null);
            out.put("appExternalFiles", extFiles == null ? JSONObject.NULL
                    : extFiles.getAbsolutePath());
            out.put("emulated", Environment.isExternalStorageEmulated());
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static JSONObject statOf(String path) {
        StatFs fs = new StatFs(path);
        long total = fs.getBlockCountLong() * fs.getBlockSizeLong();
        long free = fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
        return Jsonx.obj("totalBytes", total, "freeBytes", free, "usedBytes", total - free);
    }

    private JSONObject features() {
        PackageManager pm = act.getPackageManager();
        return Jsonx.obj(
                "camera", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                "cameraFront", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT),
                "flash", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH),
                "microphone", pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                "gps", pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
                "nfc", pm.hasSystemFeature(PackageManager.FEATURE_NFC),
                "bluetooth", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
                "bluetoothLe", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
                "telephony", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
                "fingerprint", pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT),
                "accelerometer", pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER),
                "gyroscope", pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE),
                "compass", pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_COMPASS),
                "barometer", pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_BAROMETER),
                "stepCounter", pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER),
                "touchscreen", pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
                "vulkan", pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION));
    }

    private JSONObject app() throws Exception {
        PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
        return Jsonx.obj(
                "packageName", pi.packageName,
                "versionName", pi.versionName,
                "versionCode", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode()
                        : (long) pi.versionCode,
                "firstInstallTime", pi.firstInstallTime,
                "lastUpdateTime", pi.lastUpdateTime,
                "targetSdk", pi.applicationInfo.targetSdkVersion,
                "minSdk", pi.applicationInfo.minSdkVersion,
                "dataDir", pi.applicationInfo.dataDir,
                "label", act.getPackageManager().getApplicationLabel(pi.applicationInfo));
    }
}
