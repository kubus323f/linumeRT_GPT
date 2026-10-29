/* SPDX-License-Identifier: GPL-2.0-or-later */
package pl.jakubzietarski.linumert.mobile;

import android.content.Context;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Test of embedded CPython and loopback HTTP; deliberately not a CNC server. */
final class ChaquopyProbe {
    private ChaquopyProbe() {}
    static String startAndVerify(Context context) throws Exception {
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        Python.getInstance().getModule("mobile_bootstrap").callAttr("start");
        HttpURLConnection connection = (HttpURLConnection)new URL(
                "http://127.0.0.1:8766/probe").openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(1500);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("PROBE_HTTP");
            String content;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder result = new StringBuilder();
                int next;
                while ((next = br.read()) != -1) {
                    if (result.length() >= 2048) throw new IllegalStateException("PROBE_TOO_LARGE");
                    result.append((char) next);
                }
                content = result.toString();
            }
            JSONObject data = new JSONObject(content);
            if (!data.optBoolean("ok",false)
                    || !"python-runtime-probe".equals(data.optString("kind"))
                    || data.optBoolean("webhmi",true)
                    || data.optInt("schema",-1) != 0) {
                throw new IllegalStateException("PROBE_INVALID");
            }
            long pageSize;
            try {
                pageSize = android.system.Os.sysconf(
                        android.system.OsConstants._SC_PAGESIZE);
            } catch (Exception notAvailable) {
                pageSize = -1;
            }
            return "PASS: Python " + data.optString("python")
                    + ", CPU ABI=" + android.os.Build.SUPPORTED_ABIS[0]
                    + ", strona pamięci=" + pageSize + " B"
                    + ", loopback 127.0.0.1:8766. "
                    + "Serwer WebHMI jeszcze nie został uruchomiony.";
        } finally {
            connection.disconnect();
        }
    }
    static void stop() {
        if (Python.isStarted()) {
            Python.getInstance().getModule("mobile_bootstrap").callAttr("stop");
        }
    }
}
