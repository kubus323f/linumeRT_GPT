/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * linumeRT WebHMI mobile client. NOT a safety-rated motion controller.
 */
package pl.jakubzietarski.linumert.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.net.URI;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 19, 26);
    private static final int PANEL = Color.rgb(19, 35, 45);
    private static final int FG = Color.rgb(229, 243, 242);
    private static final int MUTED = Color.rgb(157, 187, 187);
    private static final int ACCENT = Color.rgb(92, 201, 181);

    private EditText address;
    private CheckBox allowLocalHttp;
    private TextView status;
    private WebView web;
    private URI selectedOrigin;
    private boolean connected;
    private SharedPreferences prefs;
    private TextView runtimeStatus;
    private TextView localServerStatus;
    private TextView wifiAddress;
    private TextView tokenDisplay;
    private CheckBox wifiShare;
    private CheckBox embeddedPanel;
    private volatile String localSessionToken;
    private volatile String activeWifiIp = "";
    private final java.util.concurrent.atomic.AtomicBoolean probing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean localStarting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("linumert_mobile_settings", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(dp(12) + bars.left, dp(8) + bars.top,
                        dp(12) + bars.right, dp(8) + bars.bottom);
                return insets;
            });
        }
        setContentView(root);
        // All controls are scrollable even when the optional WebView is hidden.
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView controlScroll = new android.widget.ScrollView(this);
        controlScroll.setFillViewport(false);
        controlScroll.addView(controls);
        root.addView(controlScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        controls.addView(label("linumeRT · WebHMI", 20, ACCENT));
        controls.addView(label("Panel serwera LinuxCNC — nie zastępuje sprzętowego E-STOP.",
                12, MUTED));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(FG);
        address.setHintTextColor(MUTED);
        address.setHint("https://cnc.example/ lub http://192.168.1.10:8765/");
        address.setText(prefs.getString("server_url", ""));
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setTextSize(14);
        controls.addView(address, new LinearLayout.LayoutParams(-1, dp(52)));

        allowLocalHttp = new CheckBox(this);
        allowLocalHttp.setText("HTTP tylko w prywatnej sieci (token bez szyfrowania)");
        allowLocalHttp.setTextColor(FG);
        allowLocalHttp.setTextSize(12);
        allowLocalHttp.setChecked(false);
        controls.addView(allowLocalHttp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(action("Połącz", this::openServer), weighted());
        buttons.addView(action("Rozłącz", () -> closeServer("Rozłączono")), weighted());
        buttons.addView(action("Odśwież", () -> {
            if (connected && web != null) web.reload();
        }), weighted());
        controls.addView(buttons);

        status = label("Wpisz adres. Domyślnie akceptowane jest wyłącznie HTTPS.",
                12, MUTED);
        controls.addView(status);

        // A proof of embedded CPython: this is not the LinuxCNC WebHMI server.
        controls.addView(label("Lokalne środowisko (automatyczna diagnostyka)", 12, ACCENT));
        runtimeStatus = label("Sprawdzanie Chaquopy…", 12, MUTED);
        controls.addView(runtimeStatus);
        controls.addView(action("Sprawdź środowisko lokalne", this::checkEmbeddedRuntime));
        localServerStatus = label(BuildConfig.LOCAL_WEBHMI_ENABLED
                ? "Lokalny serwer WebHMI: nieuruchomiony"
                : "WebHMI lokalnie niedostępne w tej wersji APK; dostępny serwer zdalny.",
                12, MUTED);
        controls.addView(localServerStatus);
        if (BuildConfig.LOCAL_WEBHMI_ENABLED) {
            LinearLayout localActions = new LinearLayout(this);
            localActions.setOrientation(LinearLayout.HORIZONTAL);
            localActions.addView(action("Uruchom symulację lokalną", this::startLocalWebHmi),
                    weighted());
            localActions.addView(action("Zatrzymaj lokalny serwer", this::stopLocalWebHmi),
                    weighted());
            controls.addView(localActions);

            controls.addView(label("Przeglądarka i dostęp w sieci", 14, ACCENT));
            LinearLayout browserActions = new LinearLayout(this);
            browserActions.setOrientation(LinearLayout.HORIZONTAL);
            browserActions.addView(action("Otwórz w przeglądarce", this::openLocalBrowser),
                    weighted());
            browserActions.addView(action("Kopiuj token", this::copyLocalToken), weighted());
            controls.addView(browserActions);
            controls.addView(action("Pokaż / ukryj token na ekranie", this::toggleTokenDisplay));
            tokenDisplay = label("Token ukryty", 12, MUTED);
            controls.addView(tokenDisplay);

            wifiShare = new CheckBox(this);
            wifiShare.setText("Udostępnij symulator w obecnej sieci Wi-Fi (HTTP bez szyfrowania)");
            wifiShare.setTextColor(FG);
            wifiShare.setTextSize(12);
            wifiShare.setChecked(false); // Never implicitly expose the port on a fresh start.
            controls.addView(wifiShare);
            controls.addView(action("Zastosuj ustawienie Wi-Fi", this::configureWifiSharing));
            wifiAddress = label("Wi-Fi: wyłączone. Port 8765 tylko na tym telefonie.",
                    12, MUTED);
            controls.addView(wifiAddress);
            controls.addView(action("Kopiuj adres sieciowy", this::copyWifiUrl));
        }

        embeddedPanel = new CheckBox(this);
        embeddedPanel.setText("Pokaż panel wewnątrz aplikacji (zamiast przeglądarki)");
        embeddedPanel.setTextSize(12);
        embeddedPanel.setTextColor(FG);
        embeddedPanel.setChecked(false);
        controls.addView(embeddedPanel);

        web = new WebView(this);
        web.setBackgroundColor(PANEL);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        web.getSettings().setSupportMultipleWindows(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.getSettings().setSafeBrowsingEnabled(true);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebView.setWebContentsDebuggingEnabled(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new GuardedClient());
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.loadUrl("about:blank");
        web.setVisibility(View.GONE);
        embeddedPanel.setOnCheckedChangeListener((buttonView, visible) -> {
            web.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) {
                // Explicit user action. Background service stays independent of WebView.
                if (address.getText().length() > 0) openServer();
            } else {
                web.loadUrl("about:blank");
            }
        });
        if (BuildConfig.LOCAL_WEBHMI_ENABLED) checkEmbeddedRuntime();
        else maybeOpenSecureRemote();
    }

    private void checkEmbeddedRuntime() {
        if (!probing.compareAndSet(false, true)) return;
        if (runtimeStatus != null) runtimeStatus.setText("Sprawdzam lokalny Python…");
        new Thread(() -> {
            String outcome;
            try {
                outcome = ChaquopyProbe.startAndVerify(getApplicationContext());
            } catch (Throwable error) {
                outcome = "NIEDOSTĘPNE: " + error.getClass().getSimpleName()
                        + ". WebHMI wymaga rzeczywistego serwera; użyj zdalnego połączenia.";
            } finally {
                probing.set(false);
            }
            final String result = outcome;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && runtimeStatus != null) {
                    runtimeStatus.setText(result);
                    if (BuildConfig.LOCAL_WEBHMI_ENABLED && result.startsWith("PASS:"))
                        startLocalWebHmi();
                }
            });
        }, "linumert-python-check").start();
    }

    private void startLocalWebHmi() {
        startLocalWebHmi(false, "");
    }

    private void configureWifiSharing() {
        if (wifiShare == null || localStarting.get()) return;
        String desired = "";
        if (wifiShare.isChecked()) {
            desired = WifiInterface.currentPrivateIpv4(this);
            if (desired.isEmpty()) {
                wifiShare.setChecked(!activeWifiIp.isEmpty());
                wifiAddress.setText("Nie wykryto prywatnego adresu IPv4 na Wi-Fi. "
                        + "Udostępnianie nie zostało zmienione.");
                return;
            }
        }
        startLocalWebHmi(true, desired);
    }

    private void startLocalWebHmi(boolean changeWifiMode, String requestedIp) {
        if (!BuildConfig.LOCAL_WEBHMI_ENABLED || !localStarting.compareAndSet(false, true))
            return;
        localServerStatus.setText("Uruchamianie i uwierzytelniona kontrola WebHMI…");
        new Thread(() -> {
            LocalWebHmiClient.StartResult ready = null;
            String problem = null;
            try {
                ready = LocalWebHmiClient.start(
                        getApplicationContext(), requestedIp, changeWifiMode);
            } catch (Throwable failure) {
                problem = failure.getClass().getSimpleName() + ": "
                        + String.valueOf(failure.getMessage());
            } finally {
                localStarting.set(false);
            }
            final LocalWebHmiClient.StartResult result = ready;
            final String error = problem;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null) {
                    localSessionToken = null;
                    localServerStatus.setText("Serwer lokalny NIEGOTOWY: " + error
                            + ". Próbuję zapamiętane bezpieczne połączenie zdalne.");
                    maybeOpenSecureRemote();
                    return;
                }
                localSessionToken = result.token;
                activeWifiIp = result.wifiIp;
                if (wifiShare != null) wifiShare.setChecked(!activeWifiIp.isEmpty());
                if (wifiAddress != null) {
                    wifiAddress.setText(activeWifiIp.isEmpty()
                            ? "Tylko na telefonie: http://127.0.0.1:8765/"
                            : "Inne urządzenia w tym Wi-Fi: " + result.wifiUrl()
                            + " (HTTP bez szyfrowania; wymagany token)");
                }
                String hosting;
                try {
                    LocalServerService.retain(this);
                    hosting = "Usługa w tle: uruchomiona (trwałość zależy od systemu).";
                } catch (RuntimeException denied) {
                    hosting = "Usługa w tle niedostępna: " +
                            denied.getClass().getSimpleName() + ".";
                }
                localServerStatus.setText(
                        "PASS: WebHMI 127.0.0.1:8765, symulacja bez sterowania sprzętem. "
                        + hosting);
                address.setText(result.address);
                allowLocalHttp.setChecked(true);
                openServer();
            });
        }, "linumert-webhmi-start").start();
    }

    private void stopLocalWebHmi() {
        if (!BuildConfig.LOCAL_WEBHMI_ENABLED) return;
        if (localStarting.get()) {
            localServerStatus.setText("Trwa uruchamianie. Spróbuj zatrzymać po zakończeniu.");
            return;
        }
        localSessionToken = null;
        activeWifiIp = "";
        if (wifiShare != null) wifiShare.setChecked(false);
        if (wifiAddress != null) wifiAddress.setText("Udostępnianie wyłączone.");
        closeServer("Zamykanie lokalnej sesji");
        localServerStatus.setText("Zatrzymywanie serwera…");
        new Thread(() -> {
            String outcome;
            try {
                outcome = LocalWebHmiClient.stop();
            } catch (Throwable failure) {
                outcome = "STOP_FAILED_" + failure.getClass().getSimpleName();
            }
            final String report = outcome;
            if ("STOPPED".equals(report)) {
                try {
                    stopService(new android.content.Intent(getApplicationContext(),
                            LocalServerService.class));
                } catch (RuntimeException ignored) { }
            }
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed())
                    localServerStatus.setText("Serwer lokalny: " + report);
            });
        }, "linumert-webhmi-stop").start();
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(dp(2), dp(5), dp(2), dp(5));
        return view;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, dp(48), 1);
        layout.setMargins(0, dp(4), dp(4), dp(4));
        return layout;
    }

    private Button action(String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setOnClickListener(v -> click.run());
        return button;
    }

    private void openServer() {
        URI url = ServerUrlPolicy.parse(address.getText().toString(),
                allowLocalHttp.isChecked());
        if (url == null) {
            status.setText("Niepoprawny adres: użyj HTTPS albo zatwierdź HTTP dla "
                    + "prywatnego adresu IP. Nie dodawaj tokenu do adresu.");
            return;
        }
        selectedOrigin = url;
        // A local simulation token must never be transferred to another origin.
        if (!LocalWebHmiClient.LOCAL_URL.equals(url.toString()))
            localSessionToken = null;
        if (!LocalWebHmiClient.LOCAL_URL.equals(url.toString()))
            prefs.edit().putString("server_url", url.toString()).apply();
        if (!LocalWebHmiClient.LOCAL_URL.equals(url.toString())
                && "https".equalsIgnoreCase(url.getScheme())) {
            // Only previously user-selected HTTPS endpoints are eligible for
            // silent fallback. No credentials or local insecure URLs are saved.
            prefs.edit().putString("remote_server_url", url.toString()).apply();
        }
        InputMethodManager keyboard = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(address.getWindowToken(), 0);
        }
        connected = true;
        status.setText("Łączenie z " + url.getHost() + " — token wpisz na stronie WebHMI.");
        if (web != null && web.getVisibility() == View.VISIBLE) {
            web.loadUrl(url.toString());
        } else {
            status.setText("Serwer gotowy: " + url + " Otwórz w przeglądarce "
                    + "lub zaznacz panel wewnątrz aplikacji.");
        }
    }

    private void maybeOpenSecureRemote() {
        String configured = prefs.getString("remote_server_url", "");
        URI secure = ServerUrlPolicy.parse(configured, false);
        MobileRuntimePolicy.Backend choice = MobileRuntimePolicy.select(
                MobileRuntimePolicy.Purpose.SIMULATION, true,
                false, false, secure != null);
        if (choice != MobileRuntimePolicy.Backend.REMOTE) return;
        address.setText(secure.toString());
        allowLocalHttp.setChecked(false);
        openServer(); // no automatic authentication or CNC commands
    }

    private void openLocalBrowser() {
        if (localSessionToken == null) {
            status.setText("Uruchom lokalny serwer przed otwarciem przeglądarki.");
            return;
        }
        try {
            android.content.Intent browser = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(LocalWebHmiClient.LOCAL_URL));
            startActivity(browser);
            status.setText("Panel w przeglądarce telefonu. Wklej token po skopiowaniu.");
        } catch (RuntimeException unavailable) {
            status.setText("Nie można otworzyć przeglądarki: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    private void toggleTokenDisplay() {
        if (tokenDisplay == null) return;
        if (localSessionToken == null) {
            tokenDisplay.setText("Brak aktywnego tokenu.");
        } else if (tokenDisplay.getText().toString().equals(localSessionToken)) {
            tokenDisplay.setText("Token ukryty");
        } else {
            tokenDisplay.setText(localSessionToken);
        }
    }

    private void copyLocalToken() {
        if (localSessionToken == null || localSessionToken.isEmpty()) {
            status.setText("Brak aktywnego tokenu lokalnego serwera.");
            return;
        }
        android.content.ClipboardManager cb = (android.content.ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb == null) return;
        android.content.ClipData clip = android.content.ClipData.newPlainText(
                "linumeRT token", localSessionToken);
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cb.setPrimaryClip(clip);
        status.setText("Token skopiowany. Nie wysyłaj go nieznanym osobom. "
                + "Przez HTTP w Wi-Fi token i polecenia nie są szyfrowane.");
    }

    private void copyWifiUrl() {
        if (activeWifiIp.isEmpty()) {
            status.setText("Najpierw włącz udostępnianie przez Wi-Fi.");
            return;
        }
        android.content.ClipboardManager cb = (android.content.ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(android.content.ClipData.newPlainText("linumeRT WebHMI URL",
                    "http://" + activeWifiIp + ":8765/"));
            status.setText("Skopiowano adres WebHMI do udostępnienia w tej sieci Wi-Fi.");
        }
    }

    private void closeServer(String reason) {
        connected = false;
        selectedOrigin = null;
        if (web != null) web.loadUrl("about:blank"); // ends WebSocket session
        if (status != null) status.setText(reason);
    }

    @Override
    protected void onStop() {
        if (tokenDisplay != null) tokenDisplay.setText("Token ukryty");
        closeServer("Aplikacja w tle — panel zamknięty, serwer nadal działa.");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private final class GuardedClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !ServerUrlPolicy.sameOrigin(selectedOrigin, request.getUrl().toString());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view,
                                                          WebResourceRequest request) {
            if (!ServerUrlPolicy.sameOrigin(selectedOrigin, request.getUrl().toString())) {
                WebResourceResponse denied = new WebResourceResponse(
                        "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                denied.setStatusCodeAndReasonPhrase(403, "Forbidden");
                return denied;
            }
            return null;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            status.setText("Błąd certyfikatu HTTPS; połączenie odrzucone.");
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame() && connected) {
                status.setText("Błąd połączenia: " + error.getDescription()
                        + ". Sprawdź adres, sieć i nagłówki Host/Origin serwera.");
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (connected && ServerUrlPolicy.sameOrigin(selectedOrigin, url)) {
                status.setText("Panel otwarty. Uwierzytelnij tokenem WebHMI.");
                // Only the locally launched and authenticated FakeBackend receives this
                // short-lived secret. It is never stored or appended to a URL.
                if (localSessionToken != null
                        && LocalWebHmiClient.LOCAL_URL.equals(selectedOrigin.toString())) {
                    String quoted = org.json.JSONObject.quote(localSessionToken);
                    view.evaluateJavascript(
                        "(function(){let n=0;function join(){"
                        + "const t=document.getElementById('token');"
                        + "const b=document.getElementById('connect');"
                        + "if(t&&b&&document.readyState==='complete'){"
                        + "t.value=" + quoted + ";b.click();t.value='';"
                        + "}else if(++n<15){setTimeout(join,120);}}"
                        + "setTimeout(join,300);})();", null);
                }
            }
        }
    }
}
