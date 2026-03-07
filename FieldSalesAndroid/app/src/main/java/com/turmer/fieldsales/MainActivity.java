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
        // Disable Android's elastic bounce scrolling
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
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

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void downloadFile(String path, String filename) {
                runOnUiThread(() -> {
                    try {
                        String base = webView.getUrl();
                        if (base == null) base = prefs.getString(KEY_ODOO_URL, "");
                        String url = android.net.Uri.parse(base).buildUpon().encodedPath(path).build().toString();

                        android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
                        request.setTitle(filename);
                        request.allowScanningByMediaScanner();
                        request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, filename);

                        String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
                        if (cookies != null) {
                            request.addRequestHeader("Cookie", cookies);
                        }

                        android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                        dm.enqueue(request);
                        Toast.makeText(MainActivity.this, "Downloading " + filename + "...", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @android.webkit.JavascriptInterface
            public void shareFile(String path, String filename) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Preparing PDF for share...", Toast.LENGTH_SHORT).show();
                    String baseStr = webView.getUrl();
                    if (baseStr == null) baseStr = prefs.getString(KEY_ODOO_URL, "");
                    final String finalBase = baseStr;
                    final String urlStr = android.net.Uri.parse(finalBase).buildUpon().encodedPath(path).build().toString();
                    final String cookies = android.webkit.CookieManager.getInstance().getCookie(urlStr);

                    new Thread(() -> {
                        try {
                            java.net.URL url = new java.net.URL(urlStr);
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                            if (cookies != null) {
                                conn.setRequestProperty("Cookie", cookies);
                            }
                            conn.connect();

                            java.io.File cacheDir = new java.io.File(getCacheDir(), "shared_pdfs");
                            if (!cacheDir.exists()) cacheDir.mkdirs();
                            java.io.File pdfFile = new java.io.File(cacheDir, filename);

                            java.io.InputStream in = conn.getInputStream();
                            java.io.FileOutputStream out = new java.io.FileOutputStream(pdfFile);
                            byte[] buffer = new byte[2048];
                            int len;
                            while ((len = in.read(buffer)) > 0) {
                                out.write(buffer, 0, len);
                            }
                            out.close();
                            in.close();

                            runOnUiThread(() -> {
                                android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", pdfFile);
                                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                                intent.setType("application/pdf");
                                intent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
                                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(android.content.Intent.createChooser(intent, "Share PDF"));
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                });
            }
        }, "AndroidSettings");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false; // Let WebView handle normally
                }
                try {
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "App not installed to handle this link", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false; // Let WebView handle normally
                }
                try {
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "App not installed to handle this link", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }

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

            // ── CRITICAL: without this override, Android silently blocks ALL
            // JS geolocation requests inside WebView, even with the permission granted.
            // This grants location access to the Odoo page so check-in / check-out works.
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    android.webkit.GeolocationPermissions.Callback callback) {

                // Check that we actually have the system permission before granting
                boolean granted = ContextCompat.checkSelfPermission(
                        MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

                callback.invoke(origin, granted, false); // retain=false (don't persist across sessions)

                if (!granted) {
                    // Request the permission if we don't have it yet
                    ActivityCompat.requestPermissions(
                            MainActivity.this,
                            new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                            },
                            LOCATION_PERMISSION_REQUEST_CODE);
                }
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

        // 58mm (2-inch) → 384px | 80mm (3-inch) → 512px  |  104mm (4-inch) → 832px
        int logicalWidth = 512;
        if (widthMm >= 100) logicalWidth = 832;
        else if (widthMm <= 58) logicalWidth = 384;
        
        int printWidth   = logicalWidth;

        // Build @page CSS for the correct paper width
        String pageSize = widthMm + "mm";

        // Inject space-saving and width-fix CSS
        String style = "<style>" +
                "@page { size: " + pageSize + " auto; margin: 0; }" +
                "body { margin: 0 !important; padding: 0 0.5px !important; width: 100% !important;" +
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

        // ── CRITICAL: Use a large fixed height (not WRAP_CONTENT) ──
        // WRAP_CONTENT causes the offscreen WebView to stop rendering content
        // beyond the screen height, cutting off totals, QR codes, and disclaimers.
        // A 10000px fixed height tells Android to render the FULL document.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(fLogicalWidth, 10000);
        lp.leftMargin = -20000; // Push far off-screen so it's never visible
        offscreenWV.setLayoutParams(lp);
        ((ViewGroup) getWindow().getDecorView()).addView(offscreenWV);

        offscreenWV.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.postDelayed(() -> {
                    try {
                        // 1. Capture the full 10000px layout
                        int height = 10000;

                        android.graphics.Bitmap logicalBitmap =
                                android.graphics.Bitmap.createBitmap(
                                        fLogicalWidth, height,
                                        android.graphics.Bitmap.Config.RGB_565);

                        // 2. Draw the WebView into our full 10000px bitmap
                        android.graphics.Canvas canvas = new android.graphics.Canvas(logicalBitmap);
                        canvas.drawColor(android.graphics.Color.WHITE);
                        view.layout(0, 0, fLogicalWidth, height);
                        view.draw(canvas);

                        // 3. Scan from bottom up to find the true content height (crop empty white space)
                        int actualContentHeight = 0;
                        int[] pixels = new int[fLogicalWidth];
                        for (int y = height - 1; y >= 0; y--) {
                            logicalBitmap.getPixels(pixels, 0, fLogicalWidth, 0, y, fLogicalWidth, 1);
                            boolean hasContent = false;
                            for (int x = 0; x < fLogicalWidth; x++) {
                                // In RGB_565, pure white is -1 (0xFFFFFFFF)
                                if (pixels[x] != android.graphics.Color.WHITE) {
                                    hasContent = true;
                                    break;
                                }
                            }
                            if (hasContent) {
                                // Add 40px buffer (~2 lines space) to prevent cutter blocking
                                actualContentHeight = Math.min(height, y + 40);
                                break;
                            }
                        }

                        if (actualContentHeight < 600) actualContentHeight = 600; // sanity minimum

                        // 4. Crop the bitmap to the actual content height
                        android.graphics.Bitmap croppedBitmap = android.graphics.Bitmap.createBitmap(
                                logicalBitmap, 0, 0, fLogicalWidth, actualContentHeight);
                        logicalBitmap.recycle(); // free the 10000px giant

                        // 5. Scale down to printer width
                        int scaledHeight = (int) (actualContentHeight * ((float) fPrintWidth / fLogicalWidth));
                        android.graphics.Bitmap printBitmap =
                                android.graphics.Bitmap.createScaledBitmap(
                                        croppedBitmap, fPrintWidth, scaledHeight, true);
                        croppedBitmap.recycle();

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
                }, 2500);
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
                    showPaperWidthDialog();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPaperWidthDialog() {
        String[] sizes = {"🗒  2-inch (58mm)", "🗒  3-inch (80mm) — Standard", "📄  4-inch (104mm) — Wide"};
        int currentWidth = prefs.getInt(KEY_PRINTER_WIDTH, 80);
        int checkedItem = 1;
        if (currentWidth >= 104) checkedItem = 2;
        else if (currentWidth <= 58) checkedItem = 0;

        new AlertDialog.Builder(this)
                .setTitle("Select Paper Width")
                .setSingleChoiceItems(sizes, checkedItem, (dialog, which) -> {
                    int newMm = 80;
                    if (which == 2) newMm = 104;
                    else if (which == 0) newMm = 58;
                    prefs.edit().putInt(KEY_PRINTER_WIDTH, newMm).apply();
                    dialog.dismiss();
                    Toast.makeText(this, "Paper width set to " + newMm + "mm", Toast.LENGTH_SHORT).show();
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