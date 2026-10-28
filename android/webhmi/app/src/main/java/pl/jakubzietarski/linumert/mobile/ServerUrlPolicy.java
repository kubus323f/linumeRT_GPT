/* SPDX-License-Identifier: GPL-2.0-or-later */
package pl.jakubzietarski.linumert.mobile;

import java.net.URI;
import java.util.Locale;

/** Only HTTPS or explicitly approved HTTP for literal private network addresses. */
public final class ServerUrlPolicy {
    private ServerUrlPolicy() {}

    public static boolean privateLanHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.equals("::1") || value.equals("localhost")) return true;
        if (value.contains(":")) {
            // Unique local fc00::/7 and link-local fe80::/10. URI validates syntax.
            return value.startsWith("fc") || value.startsWith("fd")
                    || value.startsWith("fe8") || value.startsWith("fe9")
                    || value.startsWith("fea") || value.startsWith("feb");
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) return false;
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) return false;
            }
        } catch (NumberFormatException error) {
            return false;
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 169 && octets[1] == 254)
                || octets[0] == 127;
    }

    public static URI parse(String raw, boolean allowLocalHttp) {
        try {
            URI url = new URI(raw.trim());
            String scheme = url.getScheme();
            if (scheme == null || url.getHost() == null
                    || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))
                    || url.getRawUserInfo() != null || url.getRawQuery() != null
                    || url.getRawFragment() != null || url.getPort() > 65535
                    || url.getPort() == 0 || url.getPort() < -1
                    || !(url.getPath() == null || url.getPath().isEmpty()
                         || url.getPath().equals("/"))) {
                return null;
            }
            if (scheme.equalsIgnoreCase("http")
                    && (!allowLocalHttp || !privateLanHost(url.getHost()))) {
                return null;
            }
            return new URI(scheme.toLowerCase(Locale.ROOT), null,
                    url.getHost().toLowerCase(Locale.ROOT), url.getPort(), "/", null, null);
        } catch (Exception error) {
            return null;
        }
    }

    public static boolean sameOrigin(URI selectedOrigin, String target) {
        if (selectedOrigin == null) return false;
        try {
            URI url = new URI(target);
            return selectedOrigin.getScheme().equalsIgnoreCase(url.getScheme())
                    && selectedOrigin.getHost().equalsIgnoreCase(url.getHost())
                    && effectivePort(selectedOrigin) == effectivePort(url)
                    && url.getRawUserInfo() == null;
        } catch (Exception error) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
