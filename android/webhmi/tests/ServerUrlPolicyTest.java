/* SPDX-License-Identifier: GPL-2.0-or-later */
package pl.jakubzietarski.linumert.mobile;

import java.net.URI;

/** Standalone Java 17 regression checks (no Android SDK necessary). */
public final class ServerUrlPolicyTest {
    private static int checked;

    private static void accepted(String input, boolean insecure, String expected) {
        URI result = ServerUrlPolicy.parse(input, insecure);
        if (result == null || !expected.equals(result.toString())) {
            throw new AssertionError("Expected " + expected + " from " + input + ", got " + result);
        }
        checked++;
    }

    private static void denied(String input, boolean insecure) {
        if (ServerUrlPolicy.parse(input, insecure) != null) {
            throw new AssertionError("Should reject " + input);
        }
        checked++;
    }

    private static void same(URI origin, String url, boolean expected) {
        if (ServerUrlPolicy.sameOrigin(origin, url) != expected) {
            throw new AssertionError("Wrong origin decision for " + url);
        }
        checked++;
    }

    public static void main(String[] args) {
        accepted("https://cnc.example/", false, "https://cnc.example/");
        accepted("HTTPS://CNC.EXAMPLE:443", false, "https://cnc.example:443/");
        accepted("http://192.168.1.10:8765/", true, "http://192.168.1.10:8765/");
        accepted("http://10.10.0.1", true, "http://10.10.0.1/");
        accepted("http://172.31.10.100", true, "http://172.31.10.100/");
        accepted("http://[::1]:8765", true, "http://[::1]:8765/");
        accepted("http://[fd00::1]:8765", true, "http://[fd00::1]:8765/");
        denied("http://192.168.1.10:8765", false);
        denied("http://8.8.8.8:8765", true);
        denied("http://172.32.0.1:8765", true);
        denied("http://example.com:8765", true);
        denied("http://[2001:db8::1]:8765", true);
        denied("https://cnc.example/path", false);
        denied("https://cnc.example/?token=secret", false);
        denied("https://cnc.example/#secret", false);
        denied("https://user@cnc.example/", false);
        denied("javascript:alert(1)", false);
        denied("file:///sdcard/Download/x.html", false);
        denied("https://cnc.example:99999", false);
        denied("https://cnc.example:0", false);
        denied("https://cnc.example\\@evil.example/", false);
        denied("https://cnc.example:bad", false);
        URI origin = ServerUrlPolicy.parse("https://cnc.example/", false);
        same(origin, "https://cnc.example/static/app.js", true);
        same(origin, "https://cnc.example:443/ws", true);
        same(origin, "http://cnc.example/ws", false);
        same(origin, "https://evil.example/ws", false);
        same(origin, "https://user@cnc.example/ws", false);
        same(origin, "data:text/html,evil", false);
        System.out.println("PASS: " + checked + " Java URL and origin security assertions");
    }
}
