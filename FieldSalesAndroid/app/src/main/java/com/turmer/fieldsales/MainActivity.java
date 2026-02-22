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
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "FieldSalesPrefs";
    private static final String KEY_ODOO_URL = "odoo_url";
    private static final String DEFAULT_ODOO_URL = "https://your-odoo-server.com/web#action=tts_field_sales";

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1002;
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 1003;

    private WebView webView;
    private ProgressBar progressBar;
    private SharedPreferences prefs;

    private FrameLayout settingsOverlay;
    private boolean settingsVisible = false;
    private FrameLayout splashOverlay;

    private EscPosPrinter printer;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Full screen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0f0f1a"));

        // --- WebView Layer ---
        RelativeLayout webLayer = new RelativeLayout(this);
        webLayer.setBackgroundColor(Color.WHITE);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#6C3FC5")));
        RelativeLayout.LayoutParams pbParams = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, 6
        );
        pbParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        webLayer.addView(progressBar, pbParams);

        webView = new WebView(this);
        webLayer.addView(webView, new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ));

        root.addView(webLayer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // --- Settings Overlay ---
        settingsOverlay = buildSettingsOverlay();
        settingsOverlay.setVisibility(View.GONE);
        root.addView(settingsOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // --- Splash Screen ---
        splashOverlay = buildSplashScreen();
        root.addView(splashOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);

        printer = new EscPosPrinter(this);
        setupWebView();
        requestLocationPermissions();
        requestBluetoothPermissions();

        // Login handoff logic
        String savedUrl = prefs.getString(KEY_ODOO_URL, "");
        String intentUrl = getIntent().getStringExtra("odoo_url");
        String intentCookie = getIntent().getStringExtra("session_cookie");

        if (intentUrl != null && !intentUrl.isEmpty()) {
            prefs.edit().putString(KEY_ODOO_URL, intentUrl).apply();
            savedUrl = intentUrl;
            if (intentCookie != null && !intentCookie.isEmpty()) {
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setCookie(intentUrl, intentCookie);
                cm.flush();
            }
        }

        if (savedUrl.isEmpty() || savedUrl.equals(DEFAULT_ODOO_URL)) {
            hideSplash();
            showSettings();
        } else {
            webView.loadUrl(savedUrl);
            webView.postDelayed(() -> hideSplash(), 10000);
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        String defaultUA = settings.getUserAgentString();
        settings.setUserAgentString(defaultUA + " TurmerFieldSalesApp/1.1");

        // JS Bridges
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void openSettings() { runOnUiThread(() -> showSettings()); }
            
            @android.webkit.JavascriptInterface
            public String getCurrentUrl() { return prefs.getString(KEY_ODOO_URL, DEFAULT_ODOO_URL); }

            @android.webkit.JavascriptInterface
            public void openInBrowser(String url) {
                runOnUiThread(() -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) { Toast.makeText(MainActivity.this, "Cannot open: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
                });
            }

            @android.webkit.JavascriptInterface
            public void shareFile(String url, String filename) {
                new Thread(() -> {
                    try {
                        String baseUrl = prefs.getString(KEY_ODOO_URL, DEFAULT_ODOO_URL).replaceAll("/+$", "");
                        String fullUrl = url.startsWith("http") ? url : baseUrl + url;
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(fullUrl).openConnection();
                        String cookies = android.webkit.CookieManager.getInstance().getCookie(fullUrl);
                        if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                        conn.connect();
                        java.io.File outFile = new java.io.File(new java.io.File(getCacheDir(), "shared_pdfs"), filename);
                        outFile.getParentFile().mkdirs();
                        try (java.io.InputStream in = conn.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                            byte[] buf = new byte[8192]; int len; while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                        }
                        android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", outFile);
                        runOnUiThread(() -> {
                            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                            shareIntent.setType("application/pdf");
                            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
                            shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(android.content.Intent.createChooser(shareIntent, "Share PDF via"));
                        });
                    } catch (Exception e) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
                }).start();
            }
        }, "AndroidSettings");

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
        }, "AndroidPrint");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                injectHelperScript();
                hideSplash();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(newProgress);
            }
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
    }

    private void showBluetoothDevicePicker() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show();
            return;
        }

        @SuppressLint("MissingPermission") Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices == null || pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found. Please pair your printer in Android Settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        List<String> deviceNames = new ArrayList<>();
        final List<String> deviceMacs = new ArrayList<>();

        for (@SuppressLint("MissingPermission") BluetoothDevice device : pairedDevices) {
            @SuppressLint("MissingPermission") String name = device.getName();
            if (name == null || name.isEmpty()) name = "Unknown Device";
            deviceNames.add(name + "\n" + device.getAddress());
            deviceMacs.add(device.getAddress());
        }

        String[] deviceArray = deviceNames.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("Select Bluetooth Printer")
                .setItems(deviceArray, (dialog, which) -> {
                    String selectedMac = deviceMacs.get(which);
                    prefs.edit().putString("selected_printer_mac", selectedMac).apply();
                    Toast.makeText(this, "Printer selected! You can now print.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderAndPrint(String html) {
        Toast.makeText(this, "Preparing High-Fidelity Print...", Toast.LENGTH_SHORT).show();
        final WebView offscreenWV = new WebView(this);
        int width = 576;
        offscreenWV.layout(0, 0, width, 5000);
        offscreenWV.getSettings().setJavaScriptEnabled(true);
        offscreenWV.getSettings().setDomStorageEnabled(true);

        String styledHtml = "<html><head><style>" +
                "body { margin: 0; padding: 0; background: white; width: 384px; } " +
                "img { max-width: 100%; height: auto; } " +
                "table { width: 100%; border-collapse: collapse; } " +
                ".page { padding: 4mm; } " +
                "p, div, span, td { font-size: 20px; color: black; line-height: 1.2; } " +
                "</style></head><body><div class='page'>" + html + "</div></body></html>";

        offscreenWV.loadDataWithBaseURL(null, styledHtml, "text/html", "utf-8", null);
        offscreenWV.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.postDelayed(() -> {
                    try {
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        int mWidth = view.getMeasuredWidth();
                        int mHeight = view.getMeasuredHeight();
                        if (mHeight <= 0) mHeight = 1000;

                        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(mWidth, mHeight, android.graphics.Bitmap.Config.RGB_565);
                        view.draw(new android.graphics.Canvas(bitmap));

                        new Thread(() -> {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connecting to printer...", Toast.LENGTH_SHORT).show());
                            String result = printer.connect();
                            if ("OK".equals(result)) {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Printing...", Toast.LENGTH_SHORT).show());
                                printer.printBitmap(bitmap);
                                printer.close();
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "✅ Printing Success!", Toast.LENGTH_SHORT).show());
                            } else {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "❌ " + result, Toast.LENGTH_LONG).show());
                            }
                            bitmap.recycle();
                        }).start();
                    } catch (Exception e) { Log.e("AndroidPrint", "failed", e); }
                }, 2000);
            }
        });
    }

    private void injectHelperScript() {
        String script = "javascript:(function() {" +
            "if (window._appInjected) return;" +
            "window._appInjected = true;" +
            "window.dispatchEvent(new CustomEvent('androidAppReady', { detail: { hasNativePrint: !!window.AndroidPrint } }));" +
            "window.openAndroidSettings = function() { window.AndroidSettings.openSettings(); };" +
            "})();";
        webView.loadUrl(script);
    }

    private FrameLayout buildSettingsOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#CC000000"));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1e1e2e"));
        card.setPadding(40, 40, 40, 40);
        
        TextView title = new TextView(this);
        title.setText("⚙ App Settings"); title.setTextColor(Color.WHITE); title.setTextSize(18);
        card.addView(title);

        EditText urlInput = new EditText(this);
        urlInput.setHint("Odoo URL"); urlInput.setTextColor(Color.WHITE);
        String savedUrl = prefs.getString(KEY_ODOO_URL, "");
        if (!savedUrl.isEmpty()) urlInput.setText(savedUrl);
        card.addView(urlInput);

        Button saveBtn = new Button(this);
        saveBtn.setText("Save & Load");
        saveBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if(!url.isEmpty()) { prefs.edit().putString(KEY_ODOO_URL, url).apply(); webView.loadUrl(url); hideSettings(); }
        });
        card.addView(saveBtn);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER; params.setMargins(50, 0, 50, 0);
        overlay.addView(card, params);
        overlay.setOnClickListener(v -> hideSettings());
        card.setOnClickListener(v -> {});
        return overlay;
    }

    private FrameLayout buildSplashScreen() {
        FrameLayout splash = new FrameLayout(this);
        splash.setBackgroundColor(Color.parseColor("#1a0533"));
        TextView title = new TextView(this);
        title.setText("TURMER"); title.setTextColor(Color.WHITE); title.setTextSize(42);
        title.setGravity(Gravity.CENTER);
        splash.addView(title);
        return splash;
    }

    private void showSettings() { settingsVisible = true; settingsOverlay.setVisibility(View.VISIBLE); }
    private void hideSettings() { settingsVisible = false; settingsOverlay.setVisibility(View.GONE); }
    private void hideSplash() { if (splashOverlay.getVisibility() == View.VISIBLE) splashOverlay.animate().alpha(0f).setDuration(500).withEndAction(() -> splashOverlay.setVisibility(View.GONE)).start(); }

    private void requestLocationPermissions() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE); }
    private void requestBluetoothPermissions() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION_REQUEST_CODE); }

    @Override
    public void onBackPressed() { if (settingsVisible) hideSettings(); else if (webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
}