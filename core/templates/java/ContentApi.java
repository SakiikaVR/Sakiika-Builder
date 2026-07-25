package __PKG__;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Raw ContentResolver access. Almost every shared data store on Android —
 * contacts, SMS, call log, calendar, media, system settings — is a content
 * provider, so this one module covers all of them without a wrapper per store.
 */
public class ContentApi extends ApiModule {

    public ContentApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "content";
    }

    @Override
    public String[] methods() {
        return new String[]{"query", "insert", "update", "delete", "type", "shortcuts",
                "contacts", "settingsGet", "settingsList"};
    }

    /** Short names for the providers people actually ask for. */
    private static Uri resolveUri(String name) throws BridgeError {
        if (name.contains("://")) {
            return Uri.parse(name);
        }
        switch (name) {
            case "contacts":
                return ContactsContract.Contacts.CONTENT_URI;
            case "contactPhones":
                return ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            case "contactEmails":
                return ContactsContract.CommonDataKinds.Email.CONTENT_URI;
            case "contactData":
                return ContactsContract.Data.CONTENT_URI;
            case "rawContacts":
                return ContactsContract.RawContacts.CONTENT_URI;
            case "sms":
                return Telephony.Sms.CONTENT_URI;
            case "smsInbox":
                return Telephony.Sms.Inbox.CONTENT_URI;
            case "smsSent":
                return Telephony.Sms.Sent.CONTENT_URI;
            case "mms":
                return Telephony.Mms.CONTENT_URI;
            case "threads":
                return Telephony.Threads.CONTENT_URI;
            case "calls":
                return CallLog.Calls.CONTENT_URI;
            case "calendars":
                return CalendarContract.Calendars.CONTENT_URI;
            case "events":
                return CalendarContract.Events.CONTENT_URI;
            case "reminders":
                return CalendarContract.Reminders.CONTENT_URI;
            case "attendees":
                return CalendarContract.Attendees.CONTENT_URI;
            case "images":
                return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            case "video":
                return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            case "audio":
                return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            case "audioAlbums":
                return MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI;
            case "audioArtists":
                return MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI;
            case "files":
                return MediaStore.Files.getContentUri("external");
            case "downloads":
                if (android.os.Build.VERSION.SDK_INT < 29) {
                    throw BridgeError.needsApi(29);
                }
                return MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            default:
                throw new BridgeError("未知のプロバイダー名: " + name
                        + "（content.shortcuts() で一覧、または content:// URI を直接指定）");
        }
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "query":
                return query(a);
            case "insert":
                return insert(a);
            case "update":
                return update(a);
            case "delete":
                return delete(a);
            case "type":
                return act.getContentResolver().getType(resolveUri(Jsonx.need(a, "uri")));
            case "shortcuts":
                return shortcuts();
            case "contacts":
                return contacts(a);
            case "settingsGet":
                return settingsGet(a);
            case "settingsList":
                return settingsList(a);
            default:
                throw unknown(method);
        }
    }

    private static JSONArray shortcuts() {
        JSONArray out = new JSONArray();
        for (String s : new String[]{"contacts", "contactPhones", "contactEmails", "contactData",
                "rawContacts", "sms", "smsInbox", "smsSent", "mms", "threads", "calls",
                "calendars", "events", "reminders", "attendees", "images", "video", "audio",
                "audioAlbums", "audioArtists", "files", "downloads"}) {
            out.put(s);
        }
        return out;
    }

    private JSONObject query(JSONObject a) throws Exception {
        Uri uri = resolveUri(Jsonx.need(a, "uri"));
        String[] projection = Jsonx.strings(a, "projection");
        String selection = Jsonx.str(a, "selection", null);
        String[] args = Jsonx.strings(a, "args");
        String sort = Jsonx.str(a, "sort", null);
        int limit = Math.max(1, Math.min(5000, Jsonx.i(a, "limit", 200)));
        int offset = Math.max(0, Jsonx.i(a, "offset", 0));

        JSONArray rows = new JSONArray();
        JSONArray columns = new JSONArray();
        try (Cursor c = act.getContentResolver().query(uri,
                projection.length == 0 ? null : projection,
                selection, args.length == 0 ? null : args, sort)) {
            if (c == null) {
                throw new BridgeError("query_failed", "クエリできませんでした: " + uri);
            }
            for (int i = 0; i < c.getColumnCount(); i++) {
                columns.put(c.getColumnName(i));
            }
            int skipped = 0;
            while (c.moveToNext() && rows.length() < limit) {
                if (skipped++ < offset) {
                    continue;
                }
                rows.put(rowOf(c));
            }
            return Jsonx.obj("uri", uri.toString(), "columns", columns,
                    "count", rows.length(), "rows", rows);
        } catch (SecurityException e) {
            throw new BridgeError("permission_denied",
                    "このプロバイダーを読む権限がありません: " + uri
                            + "（必要な権限をビルド設定で有効にし perm.request してください）");
        }
    }

    private static JSONObject rowOf(Cursor c) throws Exception {
        JSONObject row = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
            String key = c.getColumnName(i);
            if (c.isNull(i)) {
                row.put(key, JSONObject.NULL);
                continue;
            }
            switch (c.getType(i)) {
                case Cursor.FIELD_TYPE_INTEGER:
                    row.put(key, c.getLong(i));
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    row.put(key, c.getDouble(i));
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    row.put(key, "[blob " + c.getBlob(i).length + " bytes]");
                    break;
                default:
                    row.put(key, c.getString(i));
                    break;
            }
        }
        return row;
    }

    private static ContentValues valuesOf(JSONObject values) throws Exception {
        ContentValues cv = new ContentValues();
        for (String key : Jsonx.keys(values)) {
            Object v = values.get(key);
            if (v == null || v == JSONObject.NULL) {
                cv.putNull(key);
            } else if (v instanceof Integer) {
                cv.put(key, (Integer) v);
            } else if (v instanceof Long) {
                cv.put(key, (Long) v);
            } else if (v instanceof Double) {
                cv.put(key, (Double) v);
            } else if (v instanceof Boolean) {
                cv.put(key, (Boolean) v);
            } else {
                cv.put(key, String.valueOf(v));
            }
        }
        return cv;
    }

    private JSONObject insert(JSONObject a) throws Exception {
        Uri uri = resolveUri(Jsonx.need(a, "uri"));
        JSONObject values = Jsonx.o(a, "values");
        if (values == null) {
            throw new BridgeError("values オブジェクトが必要です");
        }
        try {
            Uri created = act.getContentResolver().insert(uri, valuesOf(values));
            return Jsonx.obj("ok", created != null,
                    "uri", created == null ? JSONObject.NULL : created.toString());
        } catch (SecurityException e) {
            throw new BridgeError("permission_denied", "書き込み権限がありません: " + uri);
        }
    }

    private JSONObject update(JSONObject a) throws Exception {
        Uri uri = resolveUri(Jsonx.need(a, "uri"));
        JSONObject values = Jsonx.o(a, "values");
        if (values == null) {
            throw new BridgeError("values オブジェクトが必要です");
        }
        String[] args = Jsonx.strings(a, "args");
        try {
            int n = act.getContentResolver().update(uri, valuesOf(values),
                    Jsonx.str(a, "selection", null), args.length == 0 ? null : args);
            return Jsonx.obj("updated", n);
        } catch (SecurityException e) {
            throw new BridgeError("permission_denied", "書き込み権限がありません: " + uri);
        }
    }

    private JSONObject delete(JSONObject a) throws Exception {
        Uri uri = resolveUri(Jsonx.need(a, "uri"));
        String[] args = Jsonx.strings(a, "args");
        if (Jsonx.str(a, "selection", null) == null && !Jsonx.b(a, "confirmAll", false)) {
            throw new BridgeError("selection なしの全件削除は confirmAll:true が必要です");
        }
        try {
            int n = act.getContentResolver().delete(uri, Jsonx.str(a, "selection", null),
                    args.length == 0 ? null : args);
            return Jsonx.obj("deleted", n);
        } catch (SecurityException e) {
            throw new BridgeError("permission_denied", "削除権限がありません: " + uri);
        }
    }

    /** Contacts joined with their phone numbers, which the raw tables do not do. */
    private JSONObject contacts(JSONObject a) throws Exception {
        if (!act.hasPermission("android.permission.READ_CONTACTS")) {
            JSONObject r = act.requestPermissionsBlocking(
                    new String[]{"android.permission.READ_CONTACTS"}, 180000);
            if (!r.optBoolean("granted", false)) {
                throw BridgeError.denied("android.permission.READ_CONTACTS");
            }
        }
        String search = Jsonx.str(a, "search", null);
        int limit = Math.max(1, Math.min(2000, Jsonx.i(a, "limit", 200)));
        Uri uri = search == null || search.isEmpty()
                ? ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                : Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(search));
        JSONArray out = new JSONArray();
        try (Cursor c = act.getContentResolver().query(uri, new String[]{
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE},
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            while (c != null && c.moveToNext() && out.length() < limit) {
                out.put(Jsonx.obj(
                        "id", c.getLong(0),
                        "name", c.getString(1),
                        "number", c.getString(2),
                        "type", c.getInt(3)));
            }
        } catch (SecurityException e) {
            throw BridgeError.denied("android.permission.READ_CONTACTS");
        }
        return Jsonx.obj("count", out.length(), "contacts", out);
    }

    private Object settingsGet(JSONObject a) throws Exception {
        String namespace = Jsonx.str(a, "namespace", "system");
        String key = Jsonx.need(a, "key");
        String value;
        switch (namespace) {
            case "secure":
                value = Settings.Secure.getString(act.getContentResolver(), key);
                break;
            case "global":
                value = Settings.Global.getString(act.getContentResolver(), key);
                break;
            default:
                value = Settings.System.getString(act.getContentResolver(), key);
                break;
        }
        return Jsonx.obj("namespace", namespace, "key", key,
                "value", value == null ? JSONObject.NULL : value);
    }

    /** Dumps a settings namespace; handy for figuring out what a key is called. */
    private Object settingsList(JSONObject a) throws Exception {
        String namespace = Jsonx.str(a, "namespace", "system");
        Uri uri;
        switch (namespace) {
            case "secure":
                uri = Settings.Secure.CONTENT_URI;
                break;
            case "global":
                uri = Settings.Global.CONTENT_URI;
                break;
            default:
                uri = Settings.System.CONTENT_URI;
                break;
        }
        JSONObject out = new JSONObject();
        try (Cursor c = act.getContentResolver().query(uri,
                new String[]{"name", "value"}, null, null, "name ASC")) {
            while (c != null && c.moveToNext()) {
                out.put(c.getString(0), c.isNull(1) ? JSONObject.NULL : c.getString(1));
            }
        }
        return Jsonx.obj("namespace", namespace, "settings", out);
    }
}
