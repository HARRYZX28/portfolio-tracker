package app.harry.portfolio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedDispatcher;
import org.json.JSONObject;

/**
 * The app screen is a local web page (assets/www) shown full screen. It talks to
 * this class through window.Native; all data and maths come from PortfolioStore.
 */
public class MainActivity extends Activity {

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int insetTop, insetBottom;
    private boolean pageReady;
    private static java.lang.ref.WeakReference<MainActivity> current = new java.lang.ref.WeakReference<>(null);

    /** Called when Android reports an update install failed or was cancelled. */
    static void notifyUpdateFailed(String message) {
        MainActivity a = current.get();
        if (a != null) a.callJs("onUpdateFailed", message);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new java.lang.ref.WeakReference<>(this);
        Window window = getWindow();
        // Edge to edge: the background runs behind the status and navigation bars.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B1416"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                // Anything else (e.g. a link to the Sheet) opens in the browser.
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                sendInsets();
            }
        });
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetTop = bars.top;
                insetBottom = bars.bottom;
            } else {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
            }
            sendInsets();
            return insets;
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    /** Back closes Settings (or any open panel) first, then leaves the app. */
    private void handleBack() {
        web.evaluateJavascript("window.app && window.app.back ? window.app.back() : false", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) web.evaluateJavascript("window.app && window.app.onResume && window.app.onResume()", null);
    }

    private void sendInsets() {
        if (!pageReady) return;
        float d = getResources().getDisplayMetrics().density;
        String js = "document.documentElement.style.setProperty('--sat','" + Math.round(insetTop / d) + "px');"
            + "document.documentElement.style.setProperty('--sab','" + Math.round(insetBottom / d) + "px');";
        web.evaluateJavascript(js, null);
    }

    private void callJs(String function, String json) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript("window.app && window.app." + function + "(" + JSONObject.quote(json) + ")", null);
        });
    }

    /** Methods the page can call as window.Native.xxx(). */
    private final class Bridge {
        @JavascriptInterface
        public String state() {
            return PortfolioStore.stateJson(MainActivity.this);
        }

        /** Re-reads the Sheet in the background, then calls app.onRefreshed(stateJson). */
        @JavascriptInterface
        public void refresh() {
            new Thread(() -> {
                PortfolioStore.fetch(MainActivity.this);
                PortfolioWidget.redrawAll(MainActivity.this);
                callJs("onRefreshed", PortfolioStore.stateJson(MainActivity.this));
            }).start();
        }

        @JavascriptInterface
        public void saveSettings(String url, boolean widgetHoldings, boolean hideValues) {
            PortfolioStore.saveSettings(MainActivity.this, url, widgetHoldings, hideValues);
            PortfolioWidget.redrawAll(MainActivity.this);
        }

        @JavascriptInterface
        public void haptic(String kind) {
            main.post(() -> {
                int type = "tick".equals(kind) ? HapticFeedbackConstants.CLOCK_TICK
                    : "confirm".equals(kind) && Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM
                    : HapticFeedbackConstants.CONTEXT_CLICK;
                web.performHapticFeedback(type);
            });
        }

        /** Checks GitHub for a newer version, then calls app.onUpdateChecked(json). */
        @JavascriptInterface
        public void checkUpdate() {
            new Thread(() -> callJs("onUpdateChecked", Updater.check(MainActivity.this).toString())).start();
        }

        /** Downloads the update and opens Android's install screen; calls app.onUpdateFailed(msg) on failure. */
        @JavascriptInterface
        public void installUpdate(String apkUrl) {
            new Thread(() -> {
                String error = Updater.install(MainActivity.this, apkUrl);
                if (error != null) callJs("onUpdateFailed", error);
            }).start();
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
