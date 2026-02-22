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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // SharedPreferences keys
    private static final String PREFS_NAME = "FieldSalesPrefs";
    private static final String KEY_ODOO_URL = "odoo_url";
    private static final String DEFAULT_ODOO_URL = "https://your-odoo-server.com/web#action=tts_field_sales";

    private static final int PERMISSION_REQUEST_CODE = 1001;

    private WebView webView;
    private ProgressBar progressBar;

    private SharedPreferences prefs;

    // Settings overlay views
    private FrameLayout settingsOverlay;
    private boolean settingsVisible = false;

    // Splash screen overlay
    private FrameLayout splashOverlay;

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1002;
    private static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 1003;

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

        // Root frame layout (allows overlaying settings on top of WebView)
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0f0f1a"));

        // --- WebView Layer (bottom) ---
        RelativeLayout webLayer = new RelativeLayout(this);
        webLayer.setBackgroundColor(Color.WHITE);

        // Progress bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#6C3FC5")));
        RelativeLayout.LayoutParams pbParams = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, 6
        );
        pbParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        webLayer.addView(progressBar, pbParams);

        // WebView
        webView = new WebView(this);
        RelativeLayout.LayoutParams wvParams = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        );
        webLayer.addView(webView, wvParams);

        root.addView(webLayer, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // --- Settings Overlay Layer ---
        settingsOverlay = buildSettingsOverlay();
        settingsOverlay.setVisibility(View.GONE);
        root.addView(settingsOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // --- Splash Screen Layer (on top, fades out when WebView loads) ---
        splashOverlay = buildSplashScreen();
        root.addView(splashOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);

        // Configure WebView
        setupWebView();



        // Request Location permissions (for Odoo geolocation features)
        requestLocationPermissions();
        
        // Request Bluetooth permissions (for Thermal Printing)
        requestBluetoothPermissions();

        printer = new EscPosPrinter();

        // First launch: no URL saved → hide splash immediately, show settings
        // Subsequent launches: splash shows while URL loads, then fades out in onPageFinished
        String savedUrl = prefs.getString(KEY_ODOO_URL, "");

        // ── LoginActivity session handoff ───────────────────────────────────
        // LoginActivity passes odoo_url + session_cookie via Intent extras.
        String intentUrl = getIntent().getStringExtra("odoo_url");
        String intentCookie = getIntent().getStringExtra("session_cookie");
        if (intentUrl != null && !intentUrl.isEmpty()) {
            // Save and use the URL from LoginActivity
            prefs.edit().putString(KEY_ODOO_URL, intentUrl).apply();
            savedUrl = intentUrl;
            // Sync the Odoo session cookie into WebView so the user is already logged in
            if (intentCookie != null && !intentCookie.isEmpty()) {
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setCookie(intentUrl, intentCookie);
                cm.flush();
            }
        }

        if (savedUrl.isEmpty() || savedUrl.equals(DEFAULT_ODOO_URL)) {
            // No valid URL at all — skip splash, show settings
            hideSplash();
            showSettings();
        } else {
            // Explicitly load the URL now (setupWebView reads prefs too early,
            // before LoginActivity's URL is written to prefs).
            webView.loadUrl(savedUrl);
            // Safety timeout: hide splash after 10 seconds even if page doesn't finish
            webView.postDelayed(() -> hideSplash(), 10000);
        }
    }

    // -------------------------------------------------------------------------
    // Settings Overlay (built programmatically - no XML needed)
    // -------------------------------------------------------------------------

    private FrameLayout buildSettingsOverlay() {
        // Semi-transparent dark background
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#CC000000"));

        // Card container
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1e1e2e"));
        card.setPadding(dp(24), dp(24), dp(24), dp(24));

        // Round corners via background drawable
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(Color.parseColor("#1e1e2e"));
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), Color.parseColor("#6C3FC5"));
        card.setBackground(cardBg);

        // --- Title Row ---
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("⚙  App Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleParams);

        // Close button
        Button closeBtn = new Button(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.parseColor("#aaaaaa"));
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setTextSize(18);
        closeBtn.setOnClickListener(v -> hideSettings());
        titleRow.addView(closeBtn);

        card.addView(titleRow);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#333355"));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        );
        divParams.setMargins(0, dp(12), 0, dp(20));
        card.addView(divider, divParams);

        // --- Odoo Server URL ---
        TextView urlLabel = new TextView(this);
        urlLabel.setText("Odoo Server URL");
        urlLabel.setTextColor(Color.parseColor("#aaaaaa"));
        urlLabel.setTextSize(13);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.setMargins(0, 0, 0, dp(6));
        card.addView(urlLabel, labelParams);

        EditText urlInput = new EditText(this);
        urlInput.setId(View.generateViewId());
        urlInput.setHint("https://your-odoo-server.com");
        urlInput.setHintTextColor(Color.parseColor("#555577"));
        urlInput.setTextColor(Color.WHITE);
        urlInput.setTextSize(14);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        urlInput.setPadding(dp(14), dp(12), dp(14), dp(12));

        // Input background
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(Color.parseColor("#2a2a3e"));
        inputBg.setCornerRadius(dp(8));
        inputBg.setStroke(dp(1), Color.parseColor("#6C3FC5"));
        urlInput.setBackground(inputBg);

        // Load saved URL
        String savedUrl = prefs.getString(KEY_ODOO_URL, "");
        if (!savedUrl.isEmpty()) {
            urlInput.setText(savedUrl);
        }

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(0, 0, 0, dp(8));
        card.addView(urlInput, inputParams);

        // Hint text
        TextView hint = new TextView(this);
        hint.setText("Include the full URL with /web#action=tts_field_sales at the end");
        hint.setTextColor(Color.parseColor("#666688"));
        hint.setTextSize(11);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        hintParams.setMargins(0, 0, 0, dp(24));
        card.addView(hint, hintParams);

        // --- Save & Load Button ---
        Button saveBtn = new Button(this);
        saveBtn.setText("💾  Save & Load App");
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setTextSize(15);
        saveBtn.setTypeface(null, Typeface.BOLD);
        saveBtn.setPadding(dp(16), dp(14), dp(16), dp(14));

        android.graphics.drawable.GradientDrawable saveBtnBg = new android.graphics.drawable.GradientDrawable();
        saveBtnBg.setColor(Color.parseColor("#6C3FC5"));
        saveBtnBg.setCornerRadius(dp(10));
        saveBtn.setBackground(saveBtnBg);

        LinearLayout.LayoutParams saveBtnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        saveBtnParams.setMargins(0, 0, 0, dp(12));
        card.addView(saveBtn, saveBtnParams);

        // --- Reload Current URL Button ---
        Button reloadBtn = new Button(this);
        reloadBtn.setText("🔄  Reload App");
        reloadBtn.setTextColor(Color.parseColor("#6C3FC5"));
        reloadBtn.setTextSize(14);
        reloadBtn.setPadding(dp(16), dp(12), dp(16), dp(12));

        android.graphics.drawable.GradientDrawable reloadBtnBg = new android.graphics.drawable.GradientDrawable();
        reloadBtnBg.setColor(Color.TRANSPARENT);
        reloadBtnBg.setCornerRadius(dp(10));
        reloadBtnBg.setStroke(dp(1), Color.parseColor("#6C3FC5"));
        reloadBtn.setBackground(reloadBtnBg);

        LinearLayout.LayoutParams reloadBtnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        card.addView(reloadBtn, reloadBtnParams);

        // --- Button Click Listeners ---
        saveBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!url.startsWith("http")) {
                Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_SHORT).show();
                return;
            }
            // Save to SharedPreferences
            prefs.edit().putString(KEY_ODOO_URL, url).apply();
            // Load the URL in WebView
            webView.loadUrl(url);
            hideSettings();
            Toast.makeText(this, "✅ URL saved!", Toast.LENGTH_SHORT).show();
        });

        reloadBtn.setOnClickListener(v -> {
            webView.reload();
            hideSettings();
        });

        // Center the card vertically
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.gravity = Gravity.CENTER_VERTICAL;
        cardParams.setMargins(dp(20), 0, dp(20), 0);

        overlay.addView(card, cardParams);

        // Tap outside to close
        overlay.setOnClickListener(v -> hideSettings());
        card.setOnClickListener(v -> {}); // Consume click so it doesn't close

        return overlay;
    }

    private void showSettings() {
        settingsVisible = true;
        settingsOverlay.setVisibility(View.VISIBLE);
    }

    private void hideSettings() {
        settingsVisible = false;
        settingsOverlay.setVisibility(View.GONE);
    }

    // Helper: dp to pixels
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // -------------------------------------------------------------------------
    // WebView Setup
    // -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Enable geolocation so navigator.geolocation works in the web app
        settings.setGeolocationEnabled(true);

        // User agent - identify as our custom app
        String defaultUA = settings.getUserAgentString();
        settings.setUserAgentString(defaultUA + " TurmerFieldSalesApp/1.0");



        // Also expose a Settings bridge so JS can open settings
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void openSettings() {
                runOnUiThread(() -> showSettings());
            }

            @android.webkit.JavascriptInterface
            public String getCurrentUrl() {
                return prefs.getString(KEY_ODOO_URL, DEFAULT_ODOO_URL);
            }

            /**
             * Open a URL in the device's external browser.
             * Called from JS: window.AndroidSettings.openInBrowser(fullUrl)
             * This lets Chrome/Firefox handle PDF download natively.
             */
            @android.webkit.JavascriptInterface
            public void openInBrowser(String url) {
                runOnUiThread(() -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url));
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(MainActivity.this,
                                "Cannot open: " + e.getMessage(),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }

            /**
             * Download a file to the device Downloads folder using DownloadManager.
             * Called from JS: window.AndroidSettings.downloadFile(url, filename)
             */
            @android.webkit.JavascriptInterface
            public void downloadFile(String url, String filename) {
                runOnUiThread(() -> {
                    try {
                        // Build the full URL if it's a relative path
                        String fullUrl = url.startsWith("http") ? url
                                : prefs.getString(KEY_ODOO_URL, DEFAULT_ODOO_URL).replaceAll("/+$", "") + url;

                        android.app.DownloadManager.Request request =
                                new android.app.DownloadManager.Request(
                                        android.net.Uri.parse(fullUrl));
                        request.setTitle(filename);
                        request.setDescription("Downloading " + filename);
                        request.setNotificationVisibility(
                                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        request.setDestinationInExternalPublicDir(
                                android.os.Environment.DIRECTORY_DOWNLOADS, filename);
                        // Copy session cookies so Odoo authentication works
                        String cookies = android.webkit.CookieManager.getInstance().getCookie(fullUrl);
                        if (cookies != null) {
                            request.addRequestHeader("Cookie", cookies);
                        }
                        android.app.DownloadManager dm =
                                (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        if (dm != null) {
                            dm.enqueue(request);
                            android.widget.Toast.makeText(MainActivity.this,
                                    "Downloading " + filename + "...",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        android.widget.Toast.makeText(MainActivity.this,
                                "Download failed: " + e.getMessage(),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }

            /**
             * Download PDF to app cache, then open the Android share sheet.
             * The user can pick WhatsApp (or any app) and the PDF is attached.
             * Called from JS: window.AndroidSettings.shareFile(relativeUrl, filename)
             */
            @android.webkit.JavascriptInterface
            public void shareFile(String url, String filename) {
                // Run network download on a background thread
                new Thread(() -> {
                    try {
                        // Build absolute URL
                        String baseUrl = prefs.getString(KEY_ODOO_URL, DEFAULT_ODOO_URL)
                                .replaceAll("/+$", "");
                        String fullUrl = url.startsWith("http") ? url : baseUrl + url;

                        // Set up HTTP connection with session cookies (Odoo auth)
                        java.net.URL urlObj = new java.net.URL(fullUrl);
                        java.net.HttpURLConnection conn =
                                (java.net.HttpURLConnection) urlObj.openConnection();
                        conn.setRequestMethod("GET");
                        String cookies = android.webkit.CookieManager.getInstance().getCookie(fullUrl);
                        if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                        conn.setConnectTimeout(30000);
                        conn.setReadTimeout(60000);
                        conn.connect();

                        if (conn.getResponseCode() != 200) {
                            throw new Exception("Server returned " + conn.getResponseCode());
                        }

                        // Save to app cache dir (accessible via FileProvider)
                        java.io.File cacheDir = new java.io.File(
                                getCacheDir(), "shared_pdfs");
                        //noinspection ResultOfMethodCallIgnored
                        cacheDir.mkdirs();
                        java.io.File outFile = new java.io.File(cacheDir, filename);

                        try (java.io.InputStream in = conn.getInputStream();
                             java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                        }

                        // Get content URI via FileProvider
                        android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                                MainActivity.this,
                                getPackageName() + ".fileprovider",
                                outFile);

                        // Fire share sheet on UI thread
                        runOnUiThread(() -> {
                            android.content.Intent shareIntent = new android.content.Intent(
                                    android.content.Intent.ACTION_SEND);
                            shareIntent.setType("application/pdf");
                            shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
                            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, filename);
                            shareIntent.addFlags(
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(android.content.Intent.createChooser(
                                    shareIntent, "Share PDF via"));
                        });

                    } catch (Exception e) {
                        runOnUiThread(() -> android.widget.Toast.makeText(
                                MainActivity.this,
                                "Share failed: " + e.getMessage(),
                                android.widget.Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
        }, "AndroidSettings");

        // --- NATIVE PRINT BRIDGE ---
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void printHtml(String html) {
                Log.d("AndroidPrint", "Received HTML to print (length: " + html.length() + ")");
                runOnUiThread(() -> renderAndPrint(html));
            }

            @android.webkit.JavascriptInterface
            public boolean isPrinterConnected() {
                return false; // Could be improved with active check
            }
        }, "AndroidPrint");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http") || url.startsWith("https")) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                injectHelperScript();
                // Hide splash screen with fade-out animation
                hideSplash();
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

            // Grant geolocation permission to the web page automatically.
            // Without this, navigator.geolocation.getCurrentPosition() silently
            // fails in WebView even if the Android app has location permission.
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    android.webkit.GeolocationPermissions.Callback callback) {
                // Grant location access to the Odoo app origin
                callback.invoke(origin, true, false);
            }
        });

        // Load saved URL (or default)
        String savedUrl = prefs.getString(KEY_ODOO_URL, "");
        if (!savedUrl.isEmpty() && !savedUrl.equals(DEFAULT_ODOO_URL)) {
            webView.loadUrl(savedUrl);
        }
        // If no URL set, settings screen will show automatically (handled in onCreate)
    }

    private void injectHelperScript() {
        String script = "javascript:(function() {" +
            "if (window._androidPrintInjected) return;" +
            "window._androidPrintInjected = true;" +
            "window.dispatchEvent(new CustomEvent('androidAppReady', {" +
            "  detail: { version: '1.0', hasBluetooth: true }" +
            "}));" +
            "window.AndroidPrint = { isAndroidApp: function() { return 'true'; }, print: function() { console.log('Print disabled'); } };" +
            // Expose openSettings to JS (e.g. from a gear icon in the web app)
            "window.openAndroidSettings = function() { window.AndroidSettings.openSettings(); };" +
            "console.log('[TurmerApp] Android bridge ready.');" +
            "})();";
        webView.loadUrl(script);
    }

    private void injectHelperScript() {
        String script = "javascript:(function() {" +
            "if (window._androidAppInjected) return;" +
            "window._androidAppInjected = true;" +
            "window.dispatchEvent(new CustomEvent('androidAppReady', {" +
            "  detail: { version: '1.1', hasNativePrint: !!window.AndroidPrint }" +
            "}));" +
            // Only set a fallback if the native bridge is somehow missing
            "if (!window.AndroidPrint) {" +
            "  window.AndroidPrint = { isAndroidApp: function() { return 'true'; }, printHtml: function() { console.log('Native print bridge missing'); } };" +
            "}" +
            "window.openAndroidSettings = function() { window.AndroidSettings.openSettings(); };" +
            "console.log('[TurmerApp] Native bridge detection complete.');" +
            "})();";
        webView.loadUrl(script);
    }

    /**
     * Renders HTML to a Bitmap in a background WebView and sends it to the printer.
     */
    private void renderAndPrint(String html) {
        final WebView offscreenWV = new WebView(this);
        offscreenWV.setLayoutParams(new RelativeLayout.LayoutParams(576, RelativeLayout.LayoutParams.WRAP_CONTENT)); // 576px = 80mm approx at 203dpi
        
        // Ensure images and Arabic text are rendered correctly
        offscreenWV.getSettings().setJavaScriptEnabled(true);
        offscreenWV.getSettings().setDomStorageEnabled(true);
        
        String styledHtml = "<html><head><style>" +
                "body { margin: 0; padding: 0; background: white; width: 576px; } " +
                "img { max-width: 100%; height: auto; } " +
                "table { width: 100%; border-collapse: collapse; } " +
                ".page { padding: 8px; } " +
                "p, div, span, td { font-size: 24px; color: black; } " +
                "</style></head><body><div class='page'>" + html + "</div></body></html>";

        offscreenWV.loadDataWithBaseURL(null, styledHtml, "text/html", "utf-8", null);
        
        offscreenWV.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Wait for layout to settle
                view.postDelayed(() -> {
                    try {
                        int width = 576;
                        int height = view.getHeight();
                        if (height <= 0) height = 1000; // Fallback

                        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565);
                        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                        view.draw(canvas);

                        // Send to printer on background thread
                        new Thread(() -> {
                            if (printer.connect()) {
                                printer.printBitmap(bitmap);
                                // Note: printer.close() after last page or keep open? 
                                // For now, close to release resources.
                                printer.close();
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Printing Success!", Toast.LENGTH_SHORT).show());
                            } else {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Printer connection failed. Please pair MPT-III.", Toast.LENGTH_LONG).show());
                            }
                            bitmap.recycle();
                        }).start();
                    } catch (Exception e) {
                        Log.e("AndroidPrint", "Rendering failed", e);
                    }
                }, 1000); // 1s delay to ensure CSS/Images are rendered
            }
        });
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] perms = {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            };
            boolean allGranted = true;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                ActivityCompat.requestPermissions(this, perms, BLUETOOTH_PERMISSION_REQUEST_CODE);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Back Button & Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (settingsVisible) {
            hideSettings();
            return;
        }

        // Ask the web app to handle back navigation first.
        // If we're in report_preview mode, call backFromPreview() and return true.
        webView.evaluateJavascript(
            "(function() {" +
            "  try {" +
            "    var app = document.querySelector('.o_field_sales_app');" +
            "    if (!app) return false;" +
            "    var owl = app.__owl__;" +
            "    if (owl && owl.component && owl.component.state && owl.component.state.mode === 'report_preview') {" +
            "      owl.component.backFromPreview();" +
            "      return true;" +
            "    }" +
            "  } catch(e) {}" +
            "  return false;" +
            "})()",
            result -> {
                if ("true".equals(result)) {
                    // JS handled it (closed print preview)
                    return;
                }
                // Fall back to normal WebView back / close app
                runOnUiThread(() -> {
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        super.onBackPressed();
                    }
                });
            }
        );
    }



    // -------------------------------------------------------------------------
    // Splash Screen
    // -------------------------------------------------------------------------

    private FrameLayout buildSplashScreen() {
        FrameLayout splash = new FrameLayout(this);
        // Deep purple gradient background
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.parseColor("#1a0533"), Color.parseColor("#0f0f1a")}
        );
        splash.setBackground(bg);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);

        // App name text (large, bold, white)
        TextView appTitle = new TextView(this);
        appTitle.setText("TURMER");
        appTitle.setTextColor(Color.WHITE);
        appTitle.setTextSize(42);
        appTitle.setTypeface(null, Typeface.BOLD);
        appTitle.setGravity(Gravity.CENTER);
        appTitle.setLetterSpacing(0.3f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.setMargins(0, 0, 0, dp(8));
        content.addView(appTitle, titleParams);

        // Subtitle
        TextView subtitle = new TextView(this);
        subtitle.setText("Field Sales");
        subtitle.setTextColor(Color.parseColor("#9b7fd4"));
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subParams.setMargins(0, 0, 0, dp(60));
        content.addView(subtitle, subParams);

        // Animated loading bar
        ProgressBar loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setIndeterminate(true);
        loadingBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#6C3FC5")));
        loadingBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#6C3FC5")));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(200), dp(4));
        barParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(loadingBar, barParams);

        // Loading text
        TextView loadingText = new TextView(this);
        loadingText.setText("Loading...");
        loadingText.setTextColor(Color.parseColor("#555577"));
        loadingText.setTextSize(12);
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ltParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        ltParams.setMargins(0, dp(12), 0, 0);
        content.addView(loadingText, ltParams);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        contentParams.gravity = Gravity.CENTER;
        splash.addView(content, contentParams);

        return splash;
    }

    private void hideSplash() {
        if (splashOverlay == null || splashOverlay.getVisibility() != View.VISIBLE) return;
        splashOverlay.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction(() -> splashOverlay.setVisibility(View.GONE))
            .start();
    }

    // -------------------------------------------------------------------------
    // Location Permission Handling
    // -------------------------------------------------------------------------

    private void requestLocationPermissions() {
        String[] locationPerms = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        };
        boolean allGranted = true;
        for (String perm : locationPerms) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, locationPerms, LOCATION_PERMISSION_REQUEST_CODE);
        }
    }



    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 &&
                              grantResults[0] == PackageManager.PERMISSION_GRANTED;
            String js = "javascript:window.dispatchEvent(new CustomEvent('bluetoothPermissionResult', " +
                        "{ detail: { granted: " + granted + " } }));";
            webView.loadUrl(js);
        }
    }
}
