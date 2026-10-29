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
    private final java.util.concurrent.atomic.AtomicBoolean probing =
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

        root.addView(label("linumeRT · WebHMI", 20, ACCENT));
        root.addView(label("Panel serwera LinuxCNC — nie zastępuje sprzętowego E-STOP.",
                12, MUTED));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(FG);
        address.setHintTextColor(MUTED);
        address.setHint("https://cnc.example/ lub http://192.168.1.10:8765/");
        address.setText(prefs.getString("server_url", ""));
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setTextSize(14);
        root.addView(address, new LinearLayout.LayoutParams(-1, dp(52)));

        allowLocalHttp = new CheckBox(this);
        allowLocalHttp.setText("HTTP tylko w prywatnej sieci (token bez szyfrowania)");
        allowLocalHttp.setTextColor(FG);
        allowLocalHttp.setTextSize(12);
        allowLocalHttp.setChecked(false);
        root.addView(allowLocalHttp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(action("Połącz", this::openServer), weighted());
        buttons.addView(action("Rozłącz", () -> closeServer("Rozłączono")), weighted());
        buttons.addView(action("Odśwież", () -> {
            if (connected && web != null) web.reload();
        }), weighted());
        root.addView(buttons);

        status = label("Wpisz adres. Domyślnie akceptowane jest wyłącznie HTTPS.",
                12, MUTED);
        root.addView(status);

        // A proof of embedded CPython: this is not the LinuxCNC WebHMI server.
        root.addView(label("Lokalne środowisko (automatyczna diagnostyka)", 12, ACCENT));
        runtimeStatus = label("Sprawdzanie Chaquopy…", 12, MUTED);
        root.addView(runtimeStatus);
        root.addView(action("Sprawdź środowisko lokalne", this::checkEmbeddedRuntime));

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
        checkEmbeddedRuntime();
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
                if (!isFinishing() && !isDestroyed() && runtimeStatus != null)
                    runtimeStatus.setText(result);
            });
        }, "linumert-python-check").start();
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
        prefs.edit().putString("server_url", url.toString()).apply();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(address.getWindowToken(), 0);
        }
        connected = true;
        status.setText("Łączenie z " + url.getHost() + " — token wpisz na stronie WebHMI.");
        web.loadUrl(url.toString());
    }

    private void closeServer(String reason) {
        connected = false;
        selectedOrigin = null;
        if (web != null) web.loadUrl("about:blank"); // ends WebSocket session
        if (status != null) status.setText(reason);
    }

    @Override
    protected void onStop() {
        closeServer("Aplikacja w tle — sesja zamknięta. Połącz ponownie.");
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
            }
        }
    }
}
