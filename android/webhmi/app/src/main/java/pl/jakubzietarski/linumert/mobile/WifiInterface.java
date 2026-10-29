/* SPDX-License-Identifier: GPL-2.0-or-later */
package pl.jakubzietarski.linumert.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;

/** IPv4 address from an actually connected Wi-Fi transport, not cellular or VPN. */
final class WifiInterface {
    private WifiInterface() {}

    static String currentPrivateIpv4(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "";
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities cap = cm.getNetworkCapabilities(n);
                if (cap == null || !cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    continue;
                }
                LinkProperties link = cm.getLinkProperties(n);
                if (link == null) continue;
                for (LinkAddress a : link.getLinkAddresses()) {
                    InetAddress ip = a.getAddress();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()
                            && !ip.isAnyLocalAddress() && !ip.isLoopbackAddress()) {
                        String value = ip.getHostAddress();
                        if (ServerUrlPolicy.privateLanHost(value)) return value;
                    }
                }
            }
        } catch (RuntimeException denied) {
            // No permission or restricted OEM network service => no Wi-Fi sharing.
        }
        return "";
    }
}
