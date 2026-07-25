package __PKG__;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Location via the platform LocationManager (Play Services' fused provider is
 * not available in a dependency-free build, and this works on any device
 * including ones without Google apps).
 */
public class LocationApi extends ApiModule {

    private LocationListener watcher;

    public LocationApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "location";
    }

    @Override
    public String[] methods() {
        return new String[]{"providers", "isEnabled", "last", "current", "watch", "stopWatch",
                "distance"};
    }

    private LocationManager lm() throws BridgeError {
        LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            throw BridgeError.unsupported("LocationManager");
        }
        return lm;
    }

    private void requireLocationPermission() throws Exception {
        if (act.hasPermission("android.permission.ACCESS_FINE_LOCATION")
                || act.hasPermission("android.permission.ACCESS_COARSE_LOCATION")) {
            return;
        }
        // Ask rather than fail: the page almost always wants the dialog here.
        JSONObject r = act.requestPermissionsBlocking(new String[]{
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"}, 180000);
        JSONObject results = r.optJSONObject("results");
        boolean any = results != null
                && (results.optBoolean("android.permission.ACCESS_FINE_LOCATION")
                || results.optBoolean("android.permission.ACCESS_COARSE_LOCATION"));
        if (!any) {
            throw BridgeError.denied("android.permission.ACCESS_FINE_LOCATION");
        }
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "providers":
                return providers();
            case "isEnabled":
                return Jsonx.obj(
                        "gps", lm().isProviderEnabled(LocationManager.GPS_PROVIDER),
                        "network", lm().isProviderEnabled(LocationManager.NETWORK_PROVIDER),
                        "any", isAnyEnabled());
            case "last":
                return last(a);
            case "current":
                return current(a);
            case "watch":
                return watch(a);
            case "stopWatch":
                return stopWatch();
            case "distance":
                return distance(a);
            default:
                throw unknown(method);
        }
    }

    private boolean isAnyEnabled() throws Exception {
        LocationManager lm = lm();
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                || lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);
    }

    private JSONArray providers() throws Exception {
        JSONArray out = new JSONArray();
        LocationManager lm = lm();
        List<String> all = lm.getAllProviders();
        for (String p : all) {
            out.put(Jsonx.obj("name", p, "enabled", lm.isProviderEnabled(p)));
        }
        return out;
    }

    private static JSONObject describe(Location l) {
        if (l == null) {
            return null;
        }
        JSONObject o = Jsonx.obj(
                "latitude", l.getLatitude(),
                "longitude", l.getLongitude(),
                "provider", l.getProvider(),
                "time", l.getTime(),
                "ageMs", System.currentTimeMillis() - l.getTime());
        try {
            if (l.hasAccuracy()) {
                o.put("accuracy", (double) l.getAccuracy());
            }
            if (l.hasAltitude()) {
                o.put("altitude", l.getAltitude());
            }
            if (l.hasSpeed()) {
                o.put("speed", (double) l.getSpeed());
            }
            if (l.hasBearing()) {
                o.put("bearing", (double) l.getBearing());
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    private Object last(JSONObject a) throws Exception {
        requireLocationPermission();
        LocationManager lm = lm();
        Location best = null;
        for (String p : lm.getAllProviders()) {
            Location l;
            try {
                l = lm.getLastKnownLocation(p);
            } catch (SecurityException e) {
                continue;
            }
            if (l == null) {
                continue;
            }
            if (best == null || l.getTime() > best.getTime()) {
                best = l;
            }
        }
        if (best == null) {
            return Jsonx.obj("available", false,
                    "hint", "キャッシュされた位置がありません。location.current() を使ってください");
        }
        JSONObject o = describe(best);
        o.put("available", true);
        return o;
    }

    /** Waits for a genuinely fresh fix. */
    private Object current(JSONObject a) throws Exception {
        requireLocationPermission();
        if (!isAnyEnabled()) {
            throw new BridgeError("location_off",
                    "位置情報がオフです。端末の設定でオンにしてください");
        }
        final LocationManager lm = lm();
        final ArrayBlockingQueue<Location> box = new ArrayBlockingQueue<>(1);
        final LocationListener once = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                box.offer(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        final String provider = Jsonx.str(a, "provider",
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER);
        bridge.onUi(() -> {
            lm.requestLocationUpdates(provider, 0L, 0f, once, act.getMainLooper());
            return true;
        });
        try {
            Location l = box.poll(Math.max(1000, Jsonx.l(a, "timeoutMs", 20000)),
                    TimeUnit.MILLISECONDS);
            if (l == null) {
                throw new BridgeError("timeout",
                        "測位できませんでした（屋内では GPS が届かないことがあります）");
            }
            JSONObject o = describe(l);
            o.put("available", true);
            return o;
        } finally {
            bridge.onUi(() -> {
                lm.removeUpdates(once);
                return true;
            });
        }
    }

    private Object watch(JSONObject a) throws Exception {
        requireLocationPermission();
        stopWatch();
        final LocationManager lm = lm();
        final long minTime = Math.max(0, Jsonx.l(a, "minTimeMs", 2000));
        final float minDistance = (float) Jsonx.d(a, "minDistanceM", 0);
        final String provider = Jsonx.str(a, "provider",
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER);
        watcher = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                bridge.emit("location.update", describe(location));
            }

            @Override
            public void onStatusChanged(String p, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String p) {
                bridge.emit("location.provider", Jsonx.obj("provider", p, "enabled", true));
            }

            @Override
            public void onProviderDisabled(String p) {
                bridge.emit("location.provider", Jsonx.obj("provider", p, "enabled", false));
            }
        };
        final LocationListener listener = watcher;
        bridge.onUi(() -> {
            lm.requestLocationUpdates(provider, minTime, minDistance, listener,
                    act.getMainLooper());
            return true;
        });
        return Jsonx.obj("watching", true, "provider", provider,
                "channel", "location.update");
    }

    private Object stopWatch() throws Exception {
        if (watcher == null) {
            return Jsonx.obj("watching", false);
        }
        final LocationListener listener = watcher;
        watcher = null;
        final LocationManager lm = lm();
        bridge.onUi(() -> {
            lm.removeUpdates(listener);
            return true;
        });
        return Jsonx.obj("stopped", true);
    }

    private Object distance(JSONObject a) throws Exception {
        float[] out = new float[3];
        Location.distanceBetween(
                Jsonx.d(a, "lat1", 0), Jsonx.d(a, "lon1", 0),
                Jsonx.d(a, "lat2", 0), Jsonx.d(a, "lon2", 0), out);
        return Jsonx.obj("meters", (double) out[0], "initialBearing", (double) out[1],
                "finalBearing", (double) out[2]);
    }

    @Override
    public void dispose() {
        try {
            stopWatch();
        } catch (Throwable ignored) {
        }
    }
}
