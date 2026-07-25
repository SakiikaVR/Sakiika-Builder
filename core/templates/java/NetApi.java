package __PKG__;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * Connectivity facts, plus an HTTP client that runs in Java.
 *
 * <p>The Java client matters because a {@code file://} page has a null origin:
 * every cross-origin {@code fetch()} is blocked by CORS no matter what the
 * server says. Going through here sidesteps the browser's origin model entirely.
 */
public class NetApi extends ApiModule {

    private static final int MAX_BODY = 16 * 1024 * 1024;

    public NetApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "net";
    }

    @Override
    public String[] methods() {
        return new String[]{"status", "wifi", "telephony", "interfaces", "request", "download",
                "resolve", "ping"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "status":
                return status();
            case "wifi":
                return wifi();
            case "telephony":
                return telephony();
            case "interfaces":
                return interfaces();
            case "request":
                return request(a);
            case "download":
                return download(a);
            case "resolve":
                return resolve(a);
            case "ping":
                return ping(a);
            default:
                throw unknown(method);
        }
    }

    private ConnectivityManager cm() throws BridgeError {
        ConnectivityManager cm =
                (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            throw BridgeError.unsupported("ConnectivityManager");
        }
        return cm;
    }

    private JSONObject status() throws Exception {
        ConnectivityManager cm = cm();
        Network active = cm.getActiveNetwork();
        if (active == null) {
            return Jsonx.obj("online", false, "type", "none");
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps == null) {
            return Jsonx.obj("online", false, "type", "unknown");
        }
        String type = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ? "bluetooth"
                : "other";
        JSONObject out = Jsonx.obj(
                "online", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                "validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                "type", type,
                "metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                "downstreamKbps", caps.getLinkDownstreamBandwidthKbps(),
                "upstreamKbps", caps.getLinkUpstreamBandwidthKbps(),
                "vpn", caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        if (Build.VERSION.SDK_INT >= 29) {
            out.put("signalStrength", caps.getSignalStrength());
        }
        LinkProperties lp = cm.getLinkProperties(active);
        if (lp != null) {
            JSONArray addrs = new JSONArray();
            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                addrs.put(la.getAddress().getHostAddress());
            }
            JSONArray dns = new JSONArray();
            for (InetAddress d : lp.getDnsServers()) {
                dns.put(d.getHostAddress());
            }
            out.put("interfaceName", lp.getInterfaceName());
            out.put("addresses", addrs);
            out.put("dns", dns);
            out.put("domains", lp.getDomains());
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private JSONObject wifi() throws Exception {
        WifiManager wm = (WifiManager) act.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            throw BridgeError.unsupported("Wi-Fi");
        }
        JSONObject out = Jsonx.obj("enabled", wm.isWifiEnabled());
        try {
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info != null) {
                out.put("ssid", info.getSSID());
                out.put("bssid", info.getBSSID());
                out.put("rssi", info.getRssi());
                out.put("linkSpeedMbps", info.getLinkSpeed());
                out.put("frequencyMhz", info.getFrequency());
                out.put("hidden", info.getHiddenSSID());
            }
        } catch (SecurityException e) {
            out.put("note", "詳細には ACCESS_FINE_LOCATION と ACCESS_WIFI_STATE が必要です");
        }
        return out;
    }

    private JSONObject telephony() throws Exception {
        TelephonyManager tm = (TelephonyManager) act.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            throw BridgeError.unsupported("テレフォニー");
        }
        JSONObject out = new JSONObject();
        try {
            out.put("networkOperatorName", tm.getNetworkOperatorName());
            out.put("simOperatorName", tm.getSimOperatorName());
            out.put("networkCountryIso", tm.getNetworkCountryIso());
            out.put("simCountryIso", tm.getSimCountryIso());
            out.put("simState", tm.getSimState());
            out.put("phoneType", tm.getPhoneType());
            out.put("isNetworkRoaming", tm.isNetworkRoaming());
            if (Build.VERSION.SDK_INT >= 30) {
                out.put("dataNetworkType", tm.getDataNetworkType());
            }
        } catch (SecurityException e) {
            out.put("note", "一部の項目に READ_PHONE_STATE が必要です");
        }
        return out;
    }

    private JSONArray interfaces() throws Exception {
        JSONArray out = new JSONArray();
        java.util.Enumeration<java.net.NetworkInterface> e =
                java.net.NetworkInterface.getNetworkInterfaces();
        while (e != null && e.hasMoreElements()) {
            java.net.NetworkInterface ni = e.nextElement();
            JSONArray addrs = new JSONArray();
            java.util.Enumeration<InetAddress> ia = ni.getInetAddresses();
            while (ia.hasMoreElements()) {
                addrs.put(ia.nextElement().getHostAddress());
            }
            out.put(Jsonx.obj(
                    "name", ni.getName(),
                    "displayName", ni.getDisplayName(),
                    "up", ni.isUp(),
                    "loopback", ni.isLoopback(),
                    "mtu", ni.getMTU(),
                    "addresses", addrs));
        }
        return out;
    }

    /**
     * A general HTTP request. Bodies can be text or base64, and the response
     * comes back either way so binary payloads survive the trip.
     */
    private JSONObject request(JSONObject a) throws Exception {
        String urlText = Jsonx.need(a, "url");
        String method = Jsonx.str(a, "method", "GET").toUpperCase(java.util.Locale.ROOT);
        int timeout = Math.max(1000, Jsonx.i(a, "timeoutMs", 30000));
        String responseAs = Jsonx.str(a, "responseType", "text");

        URL url = new URL(urlText);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(Jsonx.b(a, "followRedirects", true));

            JSONObject headers = Jsonx.o(a, "headers");
            for (String key : Jsonx.keys(headers)) {
                conn.setRequestProperty(key, headers.optString(key));
            }

            String body = Jsonx.str(a, "body", null);
            if (body != null && !"GET".equals(method) && !"HEAD".equals(method)) {
                byte[] payload = "base64".equalsIgnoreCase(Jsonx.str(a, "bodyEncoding", "utf8"))
                        ? Base64.decode(body, Base64.DEFAULT)
                        : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
            }

            int code = conn.getResponseCode();
            byte[] responseBytes;
            try (InputStream in = code >= 400 && conn.getErrorStream() != null
                    ? conn.getErrorStream() : conn.getInputStream()) {
                responseBytes = in == null ? new byte[0] : drain(in);
            }

            JSONObject responseHeaders = new JSONObject();
            for (Map.Entry<String, List<String>> h : conn.getHeaderFields().entrySet()) {
                if (h.getKey() == null) {
                    continue;
                }
                List<String> values = h.getValue();
                responseHeaders.put(h.getKey(),
                        values.size() == 1 ? values.get(0) : Jsonx.wrap(values));
            }

            JSONObject out = Jsonx.obj(
                    "status", code,
                    "statusText", conn.getResponseMessage(),
                    "ok", code >= 200 && code < 300,
                    "url", conn.getURL().toString(),
                    "contentType", conn.getContentType(),
                    "bytes", responseBytes.length,
                    "headers", responseHeaders);
            if ("base64".equalsIgnoreCase(responseAs)) {
                out.put("body", Base64.encodeToString(responseBytes, Base64.NO_WRAP));
                out.put("bodyEncoding", "base64");
            } else {
                String text = new String(responseBytes, java.nio.charset.StandardCharsets.UTF_8);
                out.put("body", text);
                out.put("bodyEncoding", "utf8");
                if ("json".equalsIgnoreCase(responseAs)) {
                    try {
                        out.put("json", new org.json.JSONTokener(text).nextValue());
                    } catch (Throwable t) {
                        out.put("jsonError", "JSON として解釈できませんでした");
                    }
                }
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }

    /** Streams a URL straight to a file, so big downloads never hit the heap. */
    private JSONObject download(JSONObject a) throws Exception {
        String urlText = Jsonx.need(a, "url");
        String dest = Jsonx.need(a, "path");
        java.io.File target;
        if (dest.startsWith("/")) {
            target = new java.io.File(dest);
        } else {
            target = new java.io.File(act.getExternalFilesDir(null) == null
                    ? act.getFilesDir() : act.getExternalFilesDir(null), dest);
        }
        java.io.File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new BridgeError("io", "保存先フォルダーを作れません: " + parent);
        }
        URL url = new URL(urlText);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(Math.max(1000, Jsonx.i(a, "timeoutMs", 30000)));
        conn.setReadTimeout(Math.max(1000, Jsonx.i(a, "timeoutMs", 30000)));
        long total = 0;
        try (InputStream in = conn.getInputStream();
             OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buf = new byte[65536];
            int read;
            long expected = conn.getContentLengthLong();
            long lastEmit = 0;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                total += read;
                // Progress events, but not one per chunk.
                if (total - lastEmit > 262144) {
                    lastEmit = total;
                    bridge.emit("net.progress", Jsonx.obj(
                            "url", urlText, "bytes", total, "total", expected));
                }
            }
        } finally {
            conn.disconnect();
        }
        return Jsonx.obj("path", target.getAbsolutePath(), "bytes", total);
    }

    private JSONObject resolve(JSONObject a) throws Exception {
        String host = Jsonx.need(a, "host");
        InetAddress[] all = InetAddress.getAllByName(host);
        JSONArray addrs = new JSONArray();
        for (InetAddress ia : all) {
            addrs.put(ia.getHostAddress());
        }
        return Jsonx.obj("host", host, "addresses", addrs,
                "canonical", all.length > 0 ? all[0].getCanonicalHostName() : JSONObject.NULL);
    }

    private JSONObject ping(JSONObject a) throws Exception {
        String host = Jsonx.need(a, "host");
        int timeout = Math.max(100, Jsonx.i(a, "timeoutMs", 3000));
        long start = System.nanoTime();
        boolean reachable = InetAddress.getByName(host).isReachable(timeout);
        long ms = (System.nanoTime() - start) / 1000000L;
        return Jsonx.obj("host", host, "reachable", reachable, "ms", ms);
    }

    private static byte[] drain(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[32768];
        int read;
        int total = 0;
        while ((read = in.read(buf)) != -1) {
            total += read;
            if (total > MAX_BODY) {
                throw new java.io.IOException("レスポンスが大きすぎます（上限 " + MAX_BODY + " bytes）");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }
}
