package __PKG__;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** System notifications, including progress bars and per-channel settings. */
public class NotifyApi extends ApiModule {

    private static final String DEFAULT_CHANNEL = "sakiika.default";

    public NotifyApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "notify";
    }

    @Override
    public String[] methods() {
        return new String[]{"show", "progress", "cancel", "cancelAll", "channels",
                "createChannel", "deleteChannel", "enabled", "active"};
    }

    private NotificationManager nm() throws BridgeError {
        NotificationManager nm =
                (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            throw BridgeError.unsupported("NotificationManager");
        }
        return nm;
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "show":
                return show(a, false);
            case "progress":
                return show(a, true);
            case "cancel":
                nm().cancel(Jsonx.i(a, "id", 1));
                return true;
            case "cancelAll":
                nm().cancelAll();
                return true;
            case "channels":
                return channels();
            case "createChannel":
                return createChannel(Jsonx.need(a, "id"),
                        Jsonx.str(a, "name", Jsonx.need(a, "id")),
                        Jsonx.i(a, "importance", 3),
                        Jsonx.str(a, "description", null));
            case "deleteChannel":
                if (Build.VERSION.SDK_INT >= 26) {
                    nm().deleteNotificationChannel(Jsonx.need(a, "id"));
                }
                return true;
            case "enabled":
                return Jsonx.obj("enabled", nm().areNotificationsEnabled(),
                        "postPermission", Build.VERSION.SDK_INT < 33
                                || act.hasPermission("android.permission.POST_NOTIFICATIONS"));
            case "active":
                return active();
            default:
                throw unknown(method);
        }
    }

    /** Android 13+ refuses to post at all without the runtime permission. */
    private void ensureAllowed() throws Exception {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (act.hasPermission("android.permission.POST_NOTIFICATIONS")) {
            return;
        }
        JSONObject r = act.requestPermissionsBlocking(
                new String[]{"android.permission.POST_NOTIFICATIONS"}, 180000);
        if (!r.optBoolean("granted", false)) {
            throw BridgeError.denied("android.permission.POST_NOTIFICATIONS");
        }
    }

    private JSONObject createChannel(String id, String name, int importance, String description)
            throws Exception {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(id, name,
                    Math.max(NotificationManager.IMPORTANCE_MIN,
                            Math.min(NotificationManager.IMPORTANCE_HIGH, importance)));
            if (description != null) {
                ch.setDescription(description);
            }
            nm().createNotificationChannel(ch);
        }
        return Jsonx.obj("id", id, "created", true);
    }

    private Object show(JSONObject a, boolean progress) throws Exception {
        ensureAllowed();
        String channelId = Jsonx.str(a, "channel", DEFAULT_CHANNEL);
        if (DEFAULT_CHANNEL.equals(channelId)) {
            createChannel(DEFAULT_CHANNEL, Cfg.APP_NAME, 3, null);
        }
        int id = Jsonx.i(a, "id", 1);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(act, channelId)
                : new Notification.Builder(act);
        b.setContentTitle(Jsonx.str(a, "title", Cfg.APP_NAME));
        b.setContentText(Jsonx.str(a, "text", ""));
        b.setSmallIcon(android.R.drawable.stat_notify_more);
        b.setAutoCancel(Jsonx.b(a, "autoCancel", true));
        b.setOngoing(Jsonx.b(a, "ongoing", progress));
        b.setOnlyAlertOnce(Jsonx.b(a, "onlyAlertOnce", progress));

        String big = Jsonx.str(a, "bigText", null);
        if (big != null) {
            b.setStyle(new Notification.BigTextStyle().bigText(big));
        }
        if (progress) {
            int max = Jsonx.i(a, "max", 100);
            int value = Jsonx.i(a, "value", 0);
            b.setProgress(max, value, Jsonx.b(a, "indeterminate", false));
        }
        if (Jsonx.b(a, "openApp", true)) {
            Intent open = new Intent(act, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            b.setContentIntent(PendingIntent.getActivity(act, id, open, flags));
        }
        nm().notify(id, b.build());
        return Jsonx.obj("id", id, "channel", channelId, "posted", true);
    }

    private Object channels() throws Exception {
        JSONArray out = new JSONArray();
        if (Build.VERSION.SDK_INT >= 26) {
            for (NotificationChannel ch : nm().getNotificationChannels()) {
                out.put(Jsonx.obj("id", ch.getId(), "name", ch.getName(),
                        "importance", ch.getImportance(),
                        "description", ch.getDescription(),
                        "blocked", ch.getImportance() == NotificationManager.IMPORTANCE_NONE));
            }
        }
        return out;
    }

    private Object active() throws Exception {
        JSONArray out = new JSONArray();
        try {
            for (android.service.notification.StatusBarNotification sbn
                    : nm().getActiveNotifications()) {
                out.put(Jsonx.obj("id", sbn.getId(), "tag", sbn.getTag(),
                        "postTime", sbn.getPostTime()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
