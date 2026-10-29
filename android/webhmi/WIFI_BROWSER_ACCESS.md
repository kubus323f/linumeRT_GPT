# linumeRT Mobile: browser, LAN and background service (preview dev3)

## Device-confirmed baseline (2026-10-29)
Android 16/HyperOS screenshot shows Chaquopy CPython 3.13.16 diagnostics,
local WebHMI FakeBackend on 127.0.0.1:8765, a running foreground service,
and an authenticated operator page. This validates the preceding dev2,
not the untested new dev3 Wi-Fi listener.

## Local browser
Start linumeRT Mobile (TEST), then press **Otwórz w przeglądarce**.
Visit http://127.0.0.1:8765/ on the phone. Copy the current session token
using **Kopiuj token**, or choose **Pokaż / ukryj token** to type it manually.
No token is appended to the URL or stored in settings. The embedded WebView
can be enabled separately and remains optional.

## Share with another device on the connected Wi-Fi
1. Connect the phone to a trusted private Wi-Fi network.
2. Check **Udostępnij symulator w obecnej sieci Wi-Fi (HTTP bez szyfrowania)**.
3. Press **Zastosuj ustawienie Wi-Fi**; the existing simulation server
   is restarted only when its listening mode actually changes.
4. Copy the displayed http://PHONE_WIFI_IP:8765/ URL to another device
   on the same network. Obtain the session token separately from the phone.
5. Remote browser users authenticate with the token before commands
   or machine state can be accessed. Uncheck sharing and apply to disable.
6. AP/client isolation, Wi-Fi firewall or the phone changing networks can
   prevent other devices from reaching the address.

The Python server binds explicit sockets only on 127.0.0.1 and
the approved Wi-Fi IPv4 interface address. It never uses wildcard 0.0.0.0
or a cellular interface. Network reconfiguration invalidates the old token.

## Persistent notification and limitations
A user-initiated Android foreground service advertises whether the WebHMI
is localhost-only or also exposed through Wi-Fi. Stopping the operator panel
does not stop the service. Use **Zatrzymaj lokalny serwer** or the
notification STOP action to shut down the server.

Android/HyperOS can still terminate the process, and this build does not
promise reboot auto-start. Wi-Fi roaming and screen-off behavior require
physical verification. The app requests permission to display notifications
on Android 13 and newer.

## Security
LAN access currently uses plaintext HTTP and WebSocket; both token and
commands are visible to anyone who can monitor that network. Use only on
trusted Wi-Fi, never the public internet. For untrusted networks, add HTTPS
with a verified certificate or a protected tunnel in a later stage.
Host/Origin checks and bearer-token authentication remain enabled.
The bundled backend is FakeBackend (simulation only), not real LinuxCNC.
Hardware E-STOP cannot be replaced by this software.

## Acceptance tests outstanding
- Compile/install preview dev3 on Android 16/HyperOS.
- Local browser, authenticated WebSocket, second-device LAN access.
- Reject wrong token, Host, Origin, public IP and wildcard binding.
- Revoke sharing and verify old token no longer authenticates.
- Screen-off, process termination, STOP notification, Wi-Fi disconnect,
  network change and thermal/power tests.
