/* SPDX-License-Identifier: GPL-2.0-or-later */
package pl.jakubzietarski.linumert.mobile;

import android.content.Context;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Android-to-Python supervisor: FakeBackend only, never real machine control. */
final class LocalWebHmiClient {
    static final String LOCAL_URL = "http://127.0.0.1:8765/";
    private LocalWebHmiClient() {}

    static final class StartResult {
        final String token;
        final String address;
        final String wifiIp;
        StartResult(String token, String address, String wifiIp) {
            this.token = token;
            this.address = address;
            this.wifiIp = wifiIp;
        }
        String wifiUrl() {
            return wifiIp.isEmpty() ? "" : "http://" + wifiIp + ":8765/";
        }
    }

    static StartResult start(Context context) throws Exception {
        return start(context, "", false);
    }

    /**
     * If explicitChange is false, preserve an already running network mode.
     * If true, stop the previous listener before binding the approved address.
     * Old tokens become invalid after the restart.
     */
    static StartResult start(Context context, String wifiIp, boolean explicitChange)
            throws Exception {
        if (!BuildConfig.LOCAL_WEBHMI_ENABLED)
            throw new IllegalStateException("WEBHMI_NOT_BUNDLED");
        if (!Python.isStarted()) Python.start(
                new AndroidPlatform(context.getApplicationContext()));
        com.chaquo.python.PyObject module = Python.getInstance().getModule("mobile_webhmi");
        JSONObject prior = new JSONObject(module.callAttr("status").toString());
        String oldIp = prior.optString("wifi_ip", "");
        String currentState = prior.optString("state", "");
        String desired = explicitChange ? wifiIp : (
                ("STARTING".equals(currentState) || "READY".equals(currentState))
                        ? oldIp : "");
        if (explicitChange && !desired.equals(oldIp)
                && ("READY".equals(currentState) || "STARTING".equals(currentState))) {
            String stopped = module.callAttr("stop").toString();
            if (!"STOPPED".equals(stopped)) throw new IllegalStateException("STOP_PENDING");
        }
        JSONObject started = new JSONObject(module.callAttr("start", desired).toString());
        String token = started.optString("token", "");
        if (token.length() < 16) throw new IllegalStateException("MISSING_LOCAL_TOKEN");
        String activeIp = started.optString("wifi_ip", "");
        if (!desired.equals(activeIp)) throw new IllegalStateException("WIFI_MODE_MISMATCH");

        Exception last = null;
        for (int i = 0; i < 25; i++) {
            try {
                JSONObject state = new JSONObject(module.callAttr("status").toString());
                if ("READY".equals(state.optString("state"))
                        && activeIp.equals(state.optString("wifi_ip", ""))
                        && verify(token, activeIp))
                    return new StartResult(token, LOCAL_URL, activeIp);
                if ("FAILED".equals(state.optString("state")))
                    throw new IllegalStateException("LOCAL_SERVER_FAILED");
            } catch (Exception failed) {
                last = failed;
            }
            if (Thread.currentThread().isInterrupted()) break;
            Thread.sleep(200);
        }
        String stopped = stop();
        throw new IllegalStateException("LOCAL_WEBHMI_UNVERIFIED_" + stopped + "_"
                + (last == null ? "TIMEOUT" : last.getClass().getSimpleName()));
    }

    static String stop() {
        if (!Python.isStarted()) return "STOPPED";
        return Python.getInstance().getModule("mobile_webhmi").callAttr("stop").toString();
    }

    static boolean verify(String token, String wifiIp) throws Exception {
        if (!verifyAt(LOCAL_URL, token)) return false;
        return wifiIp.isEmpty() || verifyAt("http://" + wifiIp + ":8765/", token);
    }

    private static boolean verifyAt(String base, String token) throws Exception {
        JSONObject health = request(base, "/api/health", null);
        if (!MobileRuntimePolicy.canUseLocalHealth(200,
                health.optBoolean("ok", false),
                health.optInt("schema", -1), health.optString("task_state", null))) return false;
        JSONObject state = request(base, "/api/state", token);
        JSONObject payload = state.optJSONObject("state");
        return payload != null && payload.optInt("schema", -1) == 1
                && !payload.optString("task_state", "").isEmpty();
    }

    private static JSONObject request(String base, String path, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(base + path.substring(1))
                .openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(850);
        c.setReadTimeout(850);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        if (token != null) c.setRequestProperty("X-Linumert-Token", token);
        try {
            if (c.getResponseCode() != 200)
                throw new IllegalStateException("HTTP_" + c.getResponseCode());
            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] block = new byte[1024];
                int n;
                while ((n = in.read(block)) != -1) {
                    if (out.size() + n > 262144)
                        throw new IllegalStateException("RESPONSE_TOO_LARGE");
                    out.write(block, 0, n);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { c.disconnect(); }
    }
}
