package __PKG__;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Hardware sensors as event streams.
 *
 * <p>{@code sensor.start({type:"accelerometer"})} then
 * {@code Android.on("sensor.accelerometer", e => …)}. Readings are throttled per
 * subscription because a raw 200 Hz sensor would drown the JS bridge.
 */
public class SensorApi extends ApiModule {

    private final Map<String, SensorEventListener> active = new HashMap<>();

    public SensorApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "sensor";
    }

    @Override
    public String[] methods() {
        return new String[]{"list", "start", "stop", "stopAll", "read", "active"};
    }

    private SensorManager sm() throws BridgeError {
        SensorManager sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) {
            throw BridgeError.unsupported("SensorManager");
        }
        return sm;
    }

    /** Friendly names → Sensor.TYPE_* so the page never writes magic numbers. */
    private static int typeOf(String name) throws BridgeError {
        switch (name) {
            case "accelerometer":
                return Sensor.TYPE_ACCELEROMETER;
            case "gyroscope":
                return Sensor.TYPE_GYROSCOPE;
            case "magnetometer":
                return Sensor.TYPE_MAGNETIC_FIELD;
            case "light":
                return Sensor.TYPE_LIGHT;
            case "proximity":
                return Sensor.TYPE_PROXIMITY;
            case "pressure":
                return Sensor.TYPE_PRESSURE;
            case "gravity":
                return Sensor.TYPE_GRAVITY;
            case "linearAcceleration":
                return Sensor.TYPE_LINEAR_ACCELERATION;
            case "rotationVector":
                return Sensor.TYPE_ROTATION_VECTOR;
            case "stepCounter":
                return Sensor.TYPE_STEP_COUNTER;
            case "stepDetector":
                return Sensor.TYPE_STEP_DETECTOR;
            case "temperature":
                return Sensor.TYPE_AMBIENT_TEMPERATURE;
            case "humidity":
                return Sensor.TYPE_RELATIVE_HUMIDITY;
            case "heartRate":
                return Sensor.TYPE_HEART_RATE;
            case "gameRotationVector":
                return Sensor.TYPE_GAME_ROTATION_VECTOR;
            case "significantMotion":
                return Sensor.TYPE_SIGNIFICANT_MOTION;
            default:
                try {
                    return Integer.parseInt(name);
                } catch (NumberFormatException e) {
                    throw new BridgeError("未知のセンサー種別: " + name
                            + "（sensor.list() で使える名前を確認できます）");
                }
        }
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "list":
                return list();
            case "start":
                return start(a);
            case "stop":
                return stop(Jsonx.need(a, "type"));
            case "stopAll":
                return stopAll();
            case "read":
                return readOnce(a);
            case "active": {
                JSONArray out = new JSONArray();
                synchronized (active) {
                    for (String k : active.keySet()) {
                        out.put(k);
                    }
                }
                return out;
            }
            default:
                throw unknown(method);
        }
    }

    private JSONArray list() throws Exception {
        JSONArray out = new JSONArray();
        for (Sensor s : sm().getSensorList(Sensor.TYPE_ALL)) {
            out.put(Jsonx.obj(
                    "name", s.getName(),
                    "vendor", s.getVendor(),
                    "type", s.getType(),
                    "typeName", s.getStringType(),
                    "maxRange", s.getMaximumRange(),
                    "resolution", s.getResolution(),
                    "powerMa", s.getPower(),
                    "minDelayUs", s.getMinDelay(),
                    "wakeUp", s.isWakeUpSensor()));
        }
        return out;
    }

    private static int delayFor(String rate) {
        switch (rate) {
            case "fastest":
                return SensorManager.SENSOR_DELAY_FASTEST;
            case "game":
                return SensorManager.SENSOR_DELAY_GAME;
            case "ui":
                return SensorManager.SENSOR_DELAY_UI;
            default:
                return SensorManager.SENSOR_DELAY_NORMAL;
        }
    }

    private Object start(JSONObject a) throws Exception {
        final String typeName = Jsonx.need(a, "type");
        int type = typeOf(typeName);
        SensorManager sm = sm();
        Sensor sensor = sm.getDefaultSensor(type);
        if (sensor == null) {
            throw BridgeError.unsupported("センサー " + typeName);
        }
        if (type == Sensor.TYPE_HEART_RATE
                && !act.hasPermission("android.permission.BODY_SENSORS")) {
            throw BridgeError.denied("android.permission.BODY_SENSORS");
        }
        stop(typeName);

        final long minIntervalMs = Math.max(0, Jsonx.l(a, "intervalMs", 100));
        final String channel = "sensor." + typeName;
        SensorEventListener listener = new SensorEventListener() {
            private long lastEmit;

            @Override
            public void onSensorChanged(SensorEvent event) {
                long now = System.currentTimeMillis();
                if (now - lastEmit < minIntervalMs) {
                    return;
                }
                lastEmit = now;
                JSONArray values = new JSONArray();
                for (float v : event.values) {
                    // put(Object) rather than put(double): the latter throws on NaN.
                    values.put(Double.valueOf(v));
                }
                JSONObject payload = Jsonx.obj(
                        "type", typeName,
                        "values", values,
                        "accuracy", event.accuracy,
                        "timestampNs", event.timestamp,
                        "at", now);
                try {
                    if (event.values.length >= 3) {
                        payload.put("x", (double) event.values[0]);
                        payload.put("y", (double) event.values[1]);
                        payload.put("z", (double) event.values[2]);
                    } else if (event.values.length >= 1) {
                        payload.put("value", (double) event.values[0]);
                    }
                } catch (Throwable ignored) {
                }
                bridge.emit(channel, payload);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                bridge.emit(channel + ".accuracy",
                        Jsonx.obj("type", typeName, "accuracy", accuracy));
            }
        };
        boolean ok = sm.registerListener(listener, sensor,
                delayFor(Jsonx.str(a, "rate", "ui")));
        if (!ok) {
            throw new BridgeError("register_failed", "センサーを開始できませんでした: " + typeName);
        }
        synchronized (active) {
            active.put(typeName, listener);
        }
        return Jsonx.obj("started", true, "channel", channel, "sensor", sensor.getName());
    }

    private Object stop(String typeName) throws Exception {
        SensorEventListener listener;
        synchronized (active) {
            listener = active.remove(typeName);
        }
        if (listener != null) {
            sm().unregisterListener(listener);
            return Jsonx.obj("stopped", true, "type", typeName);
        }
        return Jsonx.obj("stopped", false, "type", typeName);
    }

    private Object stopAll() throws Exception {
        int n;
        synchronized (active) {
            n = active.size();
            for (SensorEventListener l : active.values()) {
                sm().unregisterListener(l);
            }
            active.clear();
        }
        return Jsonx.obj("stopped", n);
    }

    /** One reading, then unregister — for a value you only need on a button press. */
    private Object readOnce(JSONObject a) throws Exception {
        final String typeName = Jsonx.need(a, "type");
        Sensor sensor = sm().getDefaultSensor(typeOf(typeName));
        if (sensor == null) {
            throw BridgeError.unsupported("センサー " + typeName);
        }
        final java.util.concurrent.ArrayBlockingQueue<JSONObject> box =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        SensorEventListener once = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                JSONArray values = new JSONArray();
                for (float v : event.values) {
                    // put(Object) rather than put(double): the latter throws on NaN.
                    values.put(Double.valueOf(v));
                }
                box.offer(Jsonx.obj("type", typeName, "values", values,
                        "accuracy", event.accuracy, "timestampNs", event.timestamp));
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        sm().registerListener(once, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        try {
            JSONObject result = box.poll(Math.max(200, Jsonx.l(a, "timeoutMs", 3000)),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (result == null) {
                throw new BridgeError("timeout", "センサーの値が来ませんでした: " + typeName);
            }
            return result;
        } finally {
            sm().unregisterListener(once);
        }
    }

    @Override
    public void dispose() {
        try {
            stopAll();
        } catch (Throwable ignored) {
        }
    }
}
