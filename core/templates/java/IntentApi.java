package __PKG__;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Arbitrary Intents from JavaScript. This is the escape hatch for "talk to
 * another app": share sheets, the camera app, Settings screens, deep links,
 * anything with an exported entry point.
 */
public class IntentApi extends ApiModule {

    public IntentApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "intent";
    }

    @Override
    public String[] methods() {
        return new String[]{"start", "startForResult", "broadcast", "startService",
                "canHandle", "resolveAll", "openUrl", "dial", "call", "sms", "email",
                "openSettings", "openApp", "openStore", "parseUri", "pickContact",
                "addCalendarEvent", "setAlarm", "openTimer"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "start":
                return start(build(a), a, false);
            case "startForResult":
                return start(build(a), a, true);
            case "broadcast": {
                Intent i = build(a);
                act.sendBroadcast(i);
                return Jsonx.obj("sent", true, "action", i.getAction());
            }
            case "startService": {
                Intent i = build(a);
                ComponentName cn = Jsonx.b(a, "foreground", false) && Build.VERSION.SDK_INT >= 26
                        ? act.startForegroundService(i) : act.startService(i);
                return Jsonx.obj("started", cn != null,
                        "component", cn == null ? JSONObject.NULL : cn.flattenToString());
            }
            case "canHandle": {
                Intent i = build(a);
                return act.getPackageManager().resolveActivity(i, 0) != null;
            }
            case "resolveAll":
                return resolveAll(build(a));
            case "openUrl":
                return start(view(Jsonx.need(a, "url")), a, false);
            case "dial":
                return start(new Intent(Intent.ACTION_DIAL,
                        Uri.parse("tel:" + Jsonx.need(a, "number"))), a, false);
            case "call": {
                if (!act.hasPermission("android.permission.CALL_PHONE")) {
                    throw BridgeError.denied("android.permission.CALL_PHONE");
                }
                return start(new Intent(Intent.ACTION_CALL,
                        Uri.parse("tel:" + Jsonx.need(a, "number"))), a, false);
            }
            case "sms": {
                Intent i = new Intent(Intent.ACTION_SENDTO,
                        Uri.parse("smsto:" + Jsonx.str(a, "number", "")));
                String body = Jsonx.str(a, "body", null);
                if (body != null) {
                    i.putExtra("sms_body", body);
                }
                return start(i, a, false);
            }
            case "email":
                return email(a);
            case "openSettings": {
                String action = Jsonx.str(a, "action", android.provider.Settings.ACTION_SETTINGS);
                if (!action.startsWith("android.settings.")) {
                    action = "android.settings." + action;
                }
                return start(new Intent(action), a, false);
            }
            case "openApp":
                return openApp(Jsonx.need(a, "package"));
            case "openStore":
                return start(view("market://details?id=" + Jsonx.need(a, "package")), a, false);
            case "parseUri":
                return parseUri(Jsonx.need(a, "uri"));
            case "pickContact": {
                Intent i = new Intent(Intent.ACTION_PICK,
                        android.provider.ContactsContract.Contacts.CONTENT_URI);
                MainActivity.ActResult r = act.startForResultBlocking(i, 300000);
                if (!r.ok() || r.data == null || r.data.getData() == null) {
                    return Jsonx.obj("ok", false, "reason", "cancelled");
                }
                return Jsonx.obj("ok", true, "uri", r.data.getData().toString());
            }
            case "addCalendarEvent":
                return calendarEvent(a);
            case "setAlarm":
                return alarm(a);
            case "openTimer": {
                Intent i = new Intent("android.intent.action.SET_TIMER");
                i.putExtra("android.intent.extra.alarm.LENGTH", Jsonx.i(a, "seconds", 60));
                i.putExtra("android.intent.extra.alarm.MESSAGE", Jsonx.str(a, "message", "タイマー"));
                i.putExtra("android.intent.extra.alarm.SKIP_UI", Jsonx.b(a, "skipUi", false));
                return start(i, a, false);
            }
            default:
                throw unknown(method);
        }
    }

    private static Intent view(String uri) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
    }

    /** Assembles an Intent from the JSON description the page supplied. */
    private Intent build(JSONObject a) throws Exception {
        Intent i;
        String uriText = Jsonx.str(a, "uri", null);
        String action = Jsonx.str(a, "action", null);

        if (uriText != null && uriText.startsWith("intent:")) {
            // A full intent: URI already encodes action, component, extras.
            i = Intent.parseUri(uriText, Intent.URI_INTENT_SCHEME);
        } else {
            i = new Intent();
            if (action != null) {
                i.setAction(action.contains(".") ? action : "android.intent.action." + action);
            }
        }

        String pkg = Jsonx.str(a, "package", null);
        String cls = Jsonx.str(a, "class", null);
        String mime = Jsonx.str(a, "mime", null);

        if (uriText != null && !uriText.startsWith("intent:")) {
            if (mime != null) {
                i.setDataAndType(Uri.parse(uriText), mime);
            } else {
                i.setData(Uri.parse(uriText));
            }
        } else if (mime != null) {
            i.setType(mime);
        }
        if (pkg != null && cls != null) {
            i.setComponent(new ComponentName(pkg, cls.startsWith(".") ? pkg + cls : cls));
        } else if (pkg != null) {
            i.setPackage(pkg);
        }

        for (String c : Jsonx.strings(a, "categories")) {
            if (!c.isEmpty()) {
                i.addCategory(c.contains(".") ? c : "android.intent.category." + c);
            }
        }

        JSONArray flags = Jsonx.arr(a, "flags");
        if (flags != null) {
            for (int k = 0; k < flags.length(); k++) {
                Object f = flags.opt(k);
                if (f instanceof Number) {
                    i.addFlags(((Number) f).intValue());
                } else if (f != null) {
                    i.addFlags(flagByName(String.valueOf(f)));
                }
            }
        }
        if (Jsonx.b(a, "newTask", false)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        JSONObject extras = Jsonx.o(a, "extras");
        if (extras != null) {
            putExtras(i, extras);
        }
        return i;
    }

    private static int flagByName(String name) throws BridgeError {
        String field = name.startsWith("FLAG_") ? name : "FLAG_" + name;
        try {
            return Intent.class.getField(field).getInt(null);
        } catch (Throwable t) {
            throw new BridgeError("未知の Intent フラグ: " + name);
        }
    }

    /**
     * Extras support two spellings: a bare JSON value (type inferred) or
     * {@code {"type":"long","value":…}} when the receiver is picky.
     */
    private static void putExtras(Intent i, JSONObject extras) throws Exception {
        for (String key : Jsonx.keys(extras)) {
            Object raw = extras.get(key);
            if (raw instanceof JSONObject && ((JSONObject) raw).has("type")) {
                JSONObject typed = (JSONObject) raw;
                String type = typed.optString("type");
                Object value = typed.opt("value");
                switch (type) {
                    case "int":
                        i.putExtra(key, value instanceof Number ? ((Number) value).intValue() : 0);
                        break;
                    case "long":
                        i.putExtra(key, value instanceof Number ? ((Number) value).longValue() : 0L);
                        break;
                    case "float":
                        i.putExtra(key, value instanceof Number
                                ? ((Number) value).floatValue() : 0f);
                        break;
                    case "double":
                        i.putExtra(key, value instanceof Number
                                ? ((Number) value).doubleValue() : 0d);
                        break;
                    case "boolean":
                        i.putExtra(key, Boolean.TRUE.equals(value));
                        break;
                    case "uri":
                        i.putExtra(key, Uri.parse(String.valueOf(value)));
                        break;
                    case "stringArray": {
                        JSONArray ja = value instanceof JSONArray ? (JSONArray) value
                                : new JSONArray();
                        String[] arr = new String[ja.length()];
                        for (int k = 0; k < arr.length; k++) {
                            arr[k] = ja.optString(k);
                        }
                        i.putExtra(key, arr);
                        break;
                    }
                    case "intArray": {
                        JSONArray ja = value instanceof JSONArray ? (JSONArray) value
                                : new JSONArray();
                        int[] arr = new int[ja.length()];
                        for (int k = 0; k < arr.length; k++) {
                            arr[k] = ja.optInt(k);
                        }
                        i.putExtra(key, arr);
                        break;
                    }
                    default:
                        i.putExtra(key, String.valueOf(value));
                        break;
                }
                continue;
            }
            if (raw instanceof Boolean) {
                i.putExtra(key, (Boolean) raw);
            } else if (raw instanceof Integer) {
                i.putExtra(key, (Integer) raw);
            } else if (raw instanceof Long) {
                i.putExtra(key, (Long) raw);
            } else if (raw instanceof Double) {
                i.putExtra(key, (Double) raw);
            } else if (raw instanceof JSONArray) {
                JSONArray ja = (JSONArray) raw;
                String[] arr = new String[ja.length()];
                for (int k = 0; k < arr.length; k++) {
                    arr[k] = ja.optString(k);
                }
                i.putExtra(key, arr);
            } else if (raw == null || raw == JSONObject.NULL) {
                i.putExtra(key, (String) null);
            } else {
                i.putExtra(key, String.valueOf(raw));
            }
        }
    }

    private Object start(Intent i, JSONObject a, boolean forResult) throws Exception {
        String chooserTitle = Jsonx.str(a, "chooser", null);
        final Intent launch = chooserTitle != null ? Intent.createChooser(i, chooserTitle) : i;

        if (forResult) {
            MainActivity.ActResult r = act.startForResultBlocking(launch,
                    Jsonx.l(a, "timeoutMs", 600000));
            JSONObject out = Jsonx.obj("ok", r.ok(), "resultCode", r.resultCode);
            if (r.data != null) {
                out.put("uri", r.data.getData() == null ? JSONObject.NULL
                        : r.data.getData().toString());
                Bundle extras = r.data.getExtras();
                out.put("extras", extras == null ? JSONObject.NULL : Jsonx.wrap(extras));
            }
            return out;
        }
        if (act.getPackageManager().resolveActivity(launch, 0) == null) {
            throw new BridgeError("no_activity",
                    "この Intent を受け取れるアプリがありません: " + launch.getAction());
        }
        return bridge.onUi(() -> {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(launch);
            return Jsonx.obj("started", true, "action", launch.getAction());
        });
    }

    private JSONArray resolveAll(Intent i) {
        JSONArray out = new JSONArray();
        List<ResolveInfo> hits = act.getPackageManager().queryIntentActivities(i, 0);
        for (ResolveInfo ri : hits) {
            out.put(Jsonx.obj(
                    "package", ri.activityInfo.packageName,
                    "class", ri.activityInfo.name,
                    "label", ri.loadLabel(act.getPackageManager()),
                    "exported", ri.activityInfo.exported,
                    "priority", ri.priority));
        }
        return out;
    }

    private Object email(JSONObject a) throws Exception {
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        String[] to = Jsonx.strings(a, "to");
        if (to.length > 0) {
            i.putExtra(Intent.EXTRA_EMAIL, to);
        }
        String[] cc = Jsonx.strings(a, "cc");
        if (cc.length > 0) {
            i.putExtra(Intent.EXTRA_CC, cc);
        }
        String subject = Jsonx.str(a, "subject", null);
        if (subject != null) {
            i.putExtra(Intent.EXTRA_SUBJECT, subject);
        }
        String body = Jsonx.str(a, "body", null);
        if (body != null) {
            i.putExtra(Intent.EXTRA_TEXT, body);
        }
        return start(i, a, false);
    }

    private Object openApp(String pkg) throws Exception {
        Intent launch = act.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            throw new BridgeError("not_installed", "起動できるアプリがありません: " + pkg);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent finalLaunch = launch;
        return bridge.onUi(() -> {
            act.startActivity(finalLaunch);
            return Jsonx.obj("started", true, "package", pkg);
        });
    }

    private JSONObject parseUri(String uri) throws Exception {
        Intent i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        Bundle extras = i.getExtras();
        return Jsonx.obj(
                "action", i.getAction(),
                "data", i.getData() == null ? JSONObject.NULL : i.getData().toString(),
                "type", i.getType(),
                "package", i.getPackage(),
                "component", i.getComponent() == null ? JSONObject.NULL
                        : i.getComponent().flattenToString(),
                "categories", i.getCategories() == null ? new JSONArray()
                        : Jsonx.wrap(i.getCategories()),
                "flags", i.getFlags(),
                "extras", extras == null ? JSONObject.NULL : Jsonx.wrap(extras));
    }

    private Object calendarEvent(JSONObject a) throws Exception {
        Intent i = new Intent(Intent.ACTION_INSERT)
                .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.Events.TITLE,
                        Jsonx.need(a, "title"));
        String desc = Jsonx.str(a, "description", null);
        if (desc != null) {
            i.putExtra(android.provider.CalendarContract.Events.DESCRIPTION, desc);
        }
        String where = Jsonx.str(a, "location", null);
        if (where != null) {
            i.putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, where);
        }
        long begin = Jsonx.l(a, "beginMs", 0);
        if (begin > 0) {
            i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin);
        }
        long end = Jsonx.l(a, "endMs", 0);
        if (end > 0) {
            i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end);
        }
        i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY,
                Jsonx.b(a, "allDay", false));
        return start(i, a, false);
    }

    private Object alarm(JSONObject a) throws Exception {
        Intent i = new Intent("android.intent.action.SET_ALARM");
        i.putExtra("android.intent.extra.alarm.HOUR", Jsonx.i(a, "hour", 7));
        i.putExtra("android.intent.extra.alarm.MINUTES", Jsonx.i(a, "minute", 0));
        i.putExtra("android.intent.extra.alarm.MESSAGE", Jsonx.str(a, "message", "アラーム"));
        i.putExtra("android.intent.extra.alarm.SKIP_UI", Jsonx.b(a, "skipUi", false));
        return start(i, a, false);
    }
}
