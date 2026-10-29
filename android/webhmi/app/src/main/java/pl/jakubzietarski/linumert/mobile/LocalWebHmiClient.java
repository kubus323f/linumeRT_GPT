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

/** Only starts the pinned FakeBackend; does not use the LinuxCNC machine backend. */
final class LocalWebHmiClient {
    static final String LOCAL_URL = "http://127.0.0.1:8765/";
    private LocalWebHmiClient() {}

    static final class StartResult {
        final String token;
        final String address;
        StartResult(String token, String address) { this.token = token; this.address = address; }
    }

    static StartResult start(Context context) throws Exception {
        if (!BuildConfig.LOCAL_WEBHMI_ENABLED)
            throw new IllegalStateException("WEBHMI_NOT_BUNDLED");
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        JSONObject started = new JSONObject(Python.getInstance()
                .getModule("mobile_webhmi").callAttr("start").toString());
        String token = started.optString("token", "");
        if (token.length() < 16) throw new IllegalStateException("MISSING_LOCAL_TOKEN");
        Exception last = null;
        for (int i = 0; i < 25; i++) {
            try {
                JSONObject runtime = new JSONObject(Python.getInstance()
                        .getModule("mobile_webhmi").callAttr("status").toString());
                if ("READY".equals(runtime.optString("state")) && verify(token))
                    return new StartResult(token, LOCAL_URL);
                if ("FAILED".equals(runtime.optString("state")))
                    throw new IllegalStateException("LOCAL_SERVER_FAILED");
            } catch (Exception failed) {
                last = failed;
            }
            if (Thread.currentThread().isInterrupted()) break;
            Thread.sleep(200);
        }
        // After uncertain start, disable this session; never attach to unverified process.
        String stopped = stop();
        throw new IllegalStateException(
                "LOCAL_WEBHMI_UNVERIFIED_" + stopped + "_" +
                (last == null ? "TIMEOUT" : last.getClass().getSimpleName()));
    }

    static String stop() {
        if (!Python.isStarted()) return "STOPPED";
        return Python.getInstance().getModule("mobile_webhmi").callAttr("stop").toString();
    }

    static boolean verify(String token) throws Exception {
        JSONObject health = request("/api/health", null);
        if (!MobileRuntimePolicy.canUseLocalHealth(200,
                health.optBoolean("ok", false),
                health.optInt("schema", -1), health.optString("task_state", null))) return false;
        JSONObject state = request("/api/state", token);
        JSONObject payload = state.optJSONObject("state");
        return payload != null && payload.optInt("schema", -1) == 1
                && !payload.optString("task_state", "").isEmpty();
    }

    private static JSONObject request(String path, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(LOCAL_URL + path.substring(1))
                .openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(850);
        c.setReadTimeout(850);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        if (token != null) c.setRequestProperty("X-Linumert-Token", token);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP_" + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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
