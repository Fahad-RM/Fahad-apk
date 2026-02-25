package com.turmer.fieldsales;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;

import androidx.annotation.NonNull;
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
    private static final String KEY_PRINTER_MAC = "selected_printer_mac";
    private static final String KEY_PRINTER_WIDTH = "printer_width_mm"; // 80 or 104
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1002;
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 1003;

    private WebView webView;
    private ProgressBar progressBar;
    private SharedPreferences prefs;

    private EscPosPrinter printer;

    // ──────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        // ── Build layout ──
        FrameLayout root = new FrameLayout(this);

        // WebView layer
        RelativeLayout webLayer = new RelativeLayout(this);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFCC0000));
        RelativeLayout.LayoutParams pbParams =
                new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, dp(4));
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

        // ── Load URL and cookies from Intent ──
        String odooUrl = getIntent().getStringExtra("odoo_url");
        String sessionCookie = getIntent().getStringExtra("session_cookie");

        if (odooUrl != null && !odooUrl.isEmpty()) {
            if (sessionCookie != null && !sessionCookie.isEmpty()) {
                android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setCookie(odooUrl, sessionCookie);
                cookieManager.flush();
            }

            // Navigate to the Field Sales POS UI
            if (!odooUrl.endsWith("/pos/ui")) {
                odooUrl = odooUrl.replaceAll("/+$", "") + "/pos/ui";
            }

            webView.loadUrl(odooUrl);
        } else {
            Toast.makeText(this, "Error: No Odoo URL provided", Toast.LENGTH_LONG).show();
        }

        requestLocationPermissions();
        requestBluetoothPermissions();

        // Check Bluetooth connection status after short delay (permissions may be pending)
        webView.postDelayed(this::checkBluetoothPrinterStatus, 2500);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLUETOOTH PRINTER STATUS CHECK
    // ──────────────────────────────────────────────────────────────────────────

    private void checkBluetoothPrinterStatus() {
        String savedMac = prefs.getString(KEY_PRINTER_MAC, null);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return; // No Bluetooth hardware

        if (!adapter.isEnabled()) {
            showBluetoothWarning("Bluetooth is OFF",
                    "Please enable Bluetooth to connect to your thermal printer.");
            return;
        }

        if (savedMac == null || savedMac.isEmpty()) {
            showBluetoothWarning("No Printer Paired",
                    "Tap the 🖨 button in the bottom-right corner to select your thermal printer.");
        }
        // If MAC is saved and BT is on, we assume the user knows how to connect
    }

    private void showBluetoothWarning(String title, String message) {
        runOnUiThread(() -> {
            // Only show if activity is still alive
            if (isFinishing() || isDestroyed()) return;

            // Snackbar-style toast at bottom of screen
            Toast toast = Toast.makeText(this, 
                "⚠️  " + title + " — " + message, 
                Toast.LENGTH_LONG);
            toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(80));
            toast.show();
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // WEBVIEW SETUP
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);

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
            public void setPrinterWidth(int widthMm) {
                // Called from JS to set preferred paper width (80 or 104)
                prefs.edit().putInt(KEY_PRINTER_WIDTH, widthMm).apply();
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Printer width set to " + widthMm + "mm", Toast.LENGTH_SHORT).show());
            }

            @android.webkit.JavascriptInterface
            public int getPrinterWidth() {
                return prefs.getInt(KEY_PRINTER_WIDTH, 80);
            }

            @android.webkit.JavascriptInterface
            public void hideSplash() {
                // No-op: Splash screen removed
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
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(newProgress);
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PRINT RENDERING
    // ──────────────────────────────────────────────────────────────────────────

    private void renderAndPrint(String html) {
        Toast.makeText(this, "Preparing Print...", Toast.LENGTH_SHORT).show();

        // Read saved settings
        String odooUrl = prefs.getString(KEY_ODOO_URL, "");
        int widthMm = prefs.getInt(KEY_PRINTER_WIDTH, 80);

        // 80mm (3-inch) → 576px  |  104mm (4-inch) → 768px
        int logicalWidth = (widthMm >= 100) ? 768 : 576;
        int printWidth   = logicalWidth;

        // Build @page CSS for the correct paper width
        String pageSize = widthMm + "mm";

        // Inject space-saving and width-fix CSS
        String style = "<style>" +
                "@page { size: " + pageSize + " auto; margin: 0; }" +
                "body { margin: 0 !important; padding: 0 2mm !important; width: 100% !important;" +
                "       box-sizing: border-box !important; font-size: 11px !important;" +
                "       line-height: 1.1 !important; font-weight: 600 !important;" +
                "       background: #FFF !important; color: #000 !important; font-family: sans-serif; }" +
                "div, p, span, td, th { line-height: 1.1 !important; }" +
                ".o_main_navbar, .o_control_panel, header, footer { display: none !important; }" +
                ".page { margin: 0 !important; border: none !important; padding-top: 2px !important; }" +
                "table { width: 100% !important; border-collapse: collapse; table-layout: fixed;" +
                "        margin-top: 2px !important; margin-bottom: 2px !important; }" +
                "td, th { padding: 1px !important; word-wrap: break-word; }" +
                "img { max-width: 100% !important; height: auto !important;" +
                "      object-fit: contain; margin-bottom: 2px !important; }" +
                ".text-right { text-align: right !important; }" +
                "strong { font-weight: 900 !important; }" +
                "</style></head>";
        String styledHtml = html.replace("</head>", style);

        final WebView offscreenWV = new WebView(this);
        offscreenWV.getSettings().setJavaScriptEnabled(true);
        offscreenWV.loadDataWithBaseURL(odooUrl, styledHtml, "text/html", "utf-8", null);

        int fLogicalWidth = logicalWidth;
        int fPrintWidth   = printWidth;

        // Attach off-screen so Android renders it
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(fLogicalWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = -10000;
        offscreenWV.setLayoutParams(lp);
        ((ViewGroup) getWindow().getDecorView()).addView(offscreenWV);

        offscreenWV.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.postDelayed(() -> {
                    try {
                        view.measure(
                                View.MeasureSpec.makeMeasureSpec(fLogicalWidth, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        );

                        int height = view.getMeasuredHeight();
                        if (height <= 0) height = 1000;

                        android.graphics.Bitmap logicalBitmap =
                                android.graphics.Bitmap.createBitmap(
                                        fLogicalWidth, height,
                                        android.graphics.Bitmap.Config.RGB_565);

                        android.graphics.Canvas canvas = new android.graphics.Canvas(logicalBitmap);
                        canvas.drawColor(android.graphics.Color.WHITE);

                        view.layout(0, 0, fLogicalWidth, height);
                        view.draw(canvas);

                        int scaledHeight = (int) (height * ((float) fPrintWidth / fLogicalWidth));
                        android.graphics.Bitmap printBitmap =
                                android.graphics.Bitmap.createScaledBitmap(
                                        logicalBitmap, fPrintWidth, scaledHeight, true);
                        logicalBitmap.recycle();

                        new Thread(() -> {
                            boolean connected = printer.connect();
                            if (connected) {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                        "Printing...", Toast.LENGTH_SHORT).show());
                                printer.printBitmap(printBitmap);
                                printer.close();
                                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                        "✅ Print Successful!", Toast.LENGTH_SHORT).show());
                            } else {
                                runOnUiThread(() -> {
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("⚠️ Printer Not Connected")
                                            .setMessage("Cannot reach the printer. Make sure Bluetooth is ON and your printer is powered and paired.")
                                            .setPositiveButton("Select Printer", (d, w) -> showBluetoothDevicePicker())
                                            .setNegativeButton("Cancel", null)
                                            .show();
                                });
                            }
                            printBitmap.recycle();
                        }).start();

                    } catch (Exception e) {
                        Log.e("AndroidPrint", "Print failed", e);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "❌ Render failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    } finally {
                        runOnUiThread(() -> {
                            ((ViewGroup) getWindow().getDecorView()).removeView(offscreenWV);
                            offscreenWV.destroy();
                        });
                    }
                }, 1500);
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLUETOOTH DEVICE PICKER
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void showBluetoothDevicePicker() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!adapter.isEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Bluetooth is OFF")
                    .setMessage("Please enable Bluetooth in your device settings to connect to the thermal printer.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        if (pairedDevices == null || pairedDevices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No Paired Devices")
                    .setMessage("No Bluetooth devices are paired. Please pair your thermal printer via Android Settings → Bluetooth first.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        List<String> names = new ArrayList<>();
        List<String> macs  = new ArrayList<>();
        for (BluetoothDevice device : pairedDevices) {
            names.add(device.getName() + "\n" + device.getAddress());
            macs.add(device.getAddress());
        }

        new AlertDialog.Builder(this)
                .setTitle("🖨 Select Printer")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    String mac = macs.get(which);
                    prefs.edit().putString(KEY_PRINTER_MAC, mac).apply();
                    // After selecting device, ask for paper width
                    showPaperWidthDialog(mac);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PERMISSIONS
    // ──────────────────────────────────────────────────────────────────────────

    private void requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List<String> needed = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!needed.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                        needed.toArray(new String[0]),
                        BLUETOOTH_PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this,
                        "📍 Location permission is needed for route tracking and Bluetooth scanning.",
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this,
                        "🔵 Bluetooth permission denied. Printing will not work without Bluetooth access.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BACK NAVIGATION
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────────────────────

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}