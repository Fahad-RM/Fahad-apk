package com.turmer.fieldsales;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "FieldSalesPrefs";
    private static final String KEY_ODOO_URL = "odoo_url";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1002;
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 1003;

    private WebView webView;
    private ProgressBar progressBar;
    private SharedPreferences prefs;
    private FrameLayout settingsOverlay;
    private boolean settingsVisible = false;

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

        setContentView(root);

        printer = new EscPosPrinter(this);

        setupWebView();

        // ── LOAD URL AND COOKIES FROM INTENT ──
        String odooUrl = getIntent().getStringExtra("odoo_url");
        String sessionCookie = getIntent().getStringExtra("session_cookie");

        if (odooUrl != null && !odooUrl.isEmpty()) {
            if (sessionCookie != null && !sessionCookie.isEmpty()) {
                android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setCookie(odooUrl, sessionCookie);
                cookieManager.flush();
            }
            
            // Auto-direct to the POS UI or allow the server's default redirect
            if (!odooUrl.endsWith("/pos/ui")) {
                if (odooUrl.endsWith("/")) {
                    odooUrl += "pos/ui";
                } else {
                    odooUrl += "/pos/ui";
                }
            }

            webView.loadUrl(odooUrl);
        } else {
            Toast.makeText(this, "Error: No Odoo URL provided", Toast.LENGTH_LONG).show();
        }

        requestLocationPermissions();
        requestBluetoothPermissions();
    }

    private void setupWebView() {

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);

        webView.addJavascriptInterface(new Object() {

            @android.webkit.JavascriptInterface
            public void printHtml(String html) {
                Log.d("AndroidPrint", "Received HTML for print");
                runOnUiThread(() -> renderAndPrint(html));
            }

            @android.webkit.JavascriptInterface
            public void showDevicePicker() {
                runOnUiThread(() -> showBluetoothDevicePicker());
            }

            @android.webkit.JavascriptInterface
            public void hideSplash() {
                // No-op: Splash screen removed entirely
            }

        }, "AndroidPrint");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(newProgress);
            }
        });
    }

    private void renderAndPrint(String html) {

        Toast.makeText(this, "Preparing Print...", Toast.LENGTH_SHORT).show();

        // Get the base Odoo URL to allow the WebView to fetch relative CSS and Images
        String odooUrl = prefs.getString(KEY_ODOO_URL, "");

        final WebView offscreenWV = new WebView(this);
        // HPRT MPT-III 80mm (3 inch) is exactly 576 dots wide. 
        // Rendering at exactly 576px prevents scaling distortion and blurriness.
        int logicalWidth = 576; 
        int printWidth = 576; 

        // Cleanly inject CSS into the existing Odoo document <head>
        // Let the content naturally fill 100% of the 576px envelope.
        // Shrink font to 11px and reduce table padding to fit all data.
        String style = "<style>" +
                "@page { size: 80mm auto; margin: 0; }" +
                "body { margin: 0 !important; padding: 0 2mm !important; width: 100% !important; box-sizing: border-box !important; font-size: 11px !important; background: #FFF !important; color: black; font-family: sans-serif; }" +
                ".o_main_navbar, .o_control_panel, header, footer { display: none !important; }" +
                ".page { margin: 0 !important; border: none !important; padding-top: 5px !important; }" +
                "table { width: 100% !important; border-collapse: collapse; table-layout: fixed; }" +
                "td, th { padding: 2px 1px !important; word-wrap: break-word; }" +
                "img { max-width: 100% !important; height: auto !important; object-fit: contain; }" +
                ".text-right { text-align: right !important; }" +
                "</style></head>";
        String styledHtml = html.replace("</head>", style);

        offscreenWV.getSettings().setJavaScriptEnabled(true);
        // CRITICAL: Pass odooUrl as the base URL so relative <link rel="stylesheet"> work
        offscreenWV.loadDataWithBaseURL(odooUrl, styledHtml, "text/html", "utf-8", null);

        // Attach to window so Android actually renders it (placed offscreen)
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(logicalWidth, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = -10000;
        offscreenWV.setLayoutParams(lp);
        ((android.view.ViewGroup) getWindow().getDecorView()).addView(offscreenWV);

        offscreenWV.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {

                view.postDelayed(() -> {
                    try {

                        // Force measure and layout for the bitmap using the LARGER logical width
                        view.measure(
                                View.MeasureSpec.makeMeasureSpec(logicalWidth, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        );

                        int height = view.getMeasuredHeight();
                        if (height <= 0) height = 1000;

                        // Create a bitmap at the larger logical size
                        android.graphics.Bitmap logicalBitmap =
                                android.graphics.Bitmap.createBitmap(
                                        logicalWidth,
                                        height,
                                        android.graphics.Bitmap.Config.RGB_565
                                );

                        // Provide a canvas with a white background
                        android.graphics.Canvas canvas = new android.graphics.Canvas(logicalBitmap);
                        canvas.drawColor(android.graphics.Color.WHITE);

                        view.layout(0, 0, logicalWidth, height);
                        view.draw(canvas);

                        // Scale the bitmap down to the exact physical printer width (576)
                        // This acts as a "Zoom Out" ensuring everything fits on the paper
                        int scaledHeight = (int) (height * ((float) printWidth / logicalWidth));
                        android.graphics.Bitmap printBitmap =
                                android.graphics.Bitmap.createScaledBitmap(logicalBitmap, printWidth, scaledHeight, true);

                        // Recycle the large one to save memory
                        logicalBitmap.recycle();

                        new Thread(() -> {

                            boolean connected = printer.connect();

                            if (connected) {

                                runOnUiThread(() ->
                                        Toast.makeText(MainActivity.this,
                                                "Printing...",
                                                Toast.LENGTH_SHORT).show());

                                printer.printBitmap(printBitmap);
                                printer.close();

                                runOnUiThread(() ->
                                        Toast.makeText(MainActivity.this,
                                                "✅ Printing Success!",
                                                Toast.LENGTH_SHORT).show());

                            } else {

                                runOnUiThread(() ->
                                        Toast.makeText(MainActivity.this,
                                                "❌ Printer connection failed",
                                                Toast.LENGTH_LONG).show());
                            }

                            printBitmap.recycle();

                        }).start();

                    } catch (Exception e) {
                        Log.e("AndroidPrint", "Print failed", e);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ Render failed", Toast.LENGTH_SHORT).show());
                    } finally {
                        runOnUiThread(() -> {
                            ((android.view.ViewGroup) getWindow().getDecorView()).removeView(offscreenWV);
                            offscreenWV.destroy();
                        });
                    }

                }, 1500);
            }
        });
    }

    private void showBluetoothDevicePicker() {

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            return;
        }

        @SuppressLint("MissingPermission")
        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();

        if (pairedDevices == null || pairedDevices.isEmpty()) {
            Toast.makeText(this,
                    "No paired devices found",
                    Toast.LENGTH_LONG).show();
            return;
        }

        List<String> names = new ArrayList<>();
        List<String> macs = new ArrayList<>();

        for (BluetoothDevice device : pairedDevices) {
            names.add(device.getName() + "\n" + device.getAddress());
            macs.add(device.getAddress());
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Printer")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    prefs.edit().putString("selected_printer_mac", macs.get(which)).apply();
                    Toast.makeText(this, "Printer Selected", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

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

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}