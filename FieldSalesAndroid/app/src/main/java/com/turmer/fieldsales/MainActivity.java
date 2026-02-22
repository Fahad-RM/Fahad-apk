package com.turmer.fieldsales;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;   // ✅ ADDED (Fix for Log error)
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "FieldSalesPrefs";
    private static final String KEY_ODOO_URL = "odoo_url";
    private static final String DEFAULT_ODOO_URL = "https://your-odoo-server.com/web#action=tts_field_sales";

    private WebView webView;
    private ProgressBar progressBar;
    private SharedPreferences prefs;
    private FrameLayout settingsOverlay;
    private FrameLayout splashOverlay;
    private boolean settingsVisible = false;

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1002;
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 1003;

    private EscPosPrinter printer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        FrameLayout root = new FrameLayout(this);

        RelativeLayout webLayer = new RelativeLayout(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);

        RelativeLayout.LayoutParams pbParams =
                new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, 6);
        pbParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        webLayer.addView(progressBar, pbParams);

        webView = new WebView(this);
        webLayer.addView(webView,
                new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT,
                        RelativeLayout.LayoutParams.MATCH_PARENT
                ));

        root.addView(webLayer);

        splashOverlay = new FrameLayout(this);
        root.addView(splashOverlay);

        setContentView(root);

        printer = new EscPosPrinter();

        setupWebView();
        requestLocationPermissions();
        requestBluetoothPermissions();

        String savedUrl = prefs.getString(KEY_ODOO_URL, "");
        if (!savedUrl.isEmpty()) {
            webView.loadUrl(savedUrl);
        }
    }

    // =========================================================
    // WEBVIEW SETUP
    // =========================================================

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);

        webView.addJavascriptInterface(new Object() {

            @android.webkit.JavascriptInterface
            public void printHtml(String html) {
                Log.d("AndroidPrint", "Received HTML length: " + html.length());
                runOnUiThread(() -> renderAndPrint(html));
            }

        }, "AndroidPrint");

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                injectHelperScript();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });
    }

    // =========================================================
    // SINGLE injectHelperScript (Duplicate Removed)
    // =========================================================

    private void injectHelperScript() {
        String script = "javascript:(function() {" +
                "if (window._androidAppInjected) return;" +
                "window._androidAppInjected = true;" +
                "window.dispatchEvent(new CustomEvent('androidAppReady', {" +
                "  detail: { version: '1.1', hasNativePrint: !!window.AndroidPrint }" +
                "}));" +
                "console.log('[TurmerApp] Native bridge ready.');" +
                "})();";

        webView.loadUrl(script);
    }

    // =========================================================
    // PRINTING
    // =========================================================

    private void renderAndPrint(String html) {

        WebView offscreenWV = new WebView(this);
        offscreenWV.getSettings().setJavaScriptEnabled(true);

        offscreenWV.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

        offscreenWV.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {

                view.postDelayed(() -> {
                    try {

                        int width = 576;
                        int height = view.getContentHeight();

                        android.graphics.Bitmap bitmap =
                                android.graphics.Bitmap.createBitmap(
                                        width,
                                        height,
                                        android.graphics.Bitmap.Config.RGB_565);

                        android.graphics.Canvas canvas =
                                new android.graphics.Canvas(bitmap);

                        view.layout(0, 0, width, height);
                        view.draw(canvas);

                        new Thread(() -> {

                            if (printer.connect()) {
                                printer.printBitmap(bitmap);
                                printer.close();
                                runOnUiThread(() ->
                                        Toast.makeText(MainActivity.this,
                                                "Print Success",
                                                Toast.LENGTH_SHORT).show());
                            } else {
                                runOnUiThread(() ->
                                        Toast.makeText(MainActivity.this,
                                                "Printer connection failed",
                                                Toast.LENGTH_LONG).show());
                            }

                        }).start();

                    } catch (Exception e) {
                        Log.e("AndroidPrint", "Rendering failed", e);
                    }

                }, 1000);
            }
        });
    }

    // =========================================================
    // PERMISSIONS
    // =========================================================

    private void requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    BLUETOOTH_PERMISSION_REQUEST_CODE);
        }
    }

    // =========================================================
    // BACK BUTTON
    // =========================================================

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

}