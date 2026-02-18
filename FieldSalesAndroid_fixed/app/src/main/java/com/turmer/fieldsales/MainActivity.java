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
    private BluetoothPrintBridge bluetoothBridge;
    private SharedPreferences prefs;

    // Settings overlay views
    private FrameLayout settingsOverlay;
    private boolean settingsVisible = false;

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

        // --- WebView Layer ---
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

        setContentView(root);

        // Configure WebView
        setupWebView();

        // Request Bluetooth permissions
        requestBluetoothPermissions();

        // If no URL configured yet, show settings immediately
        String savedUrl = prefs.getString(KEY_ODOO_URL, "");
        if (savedUrl.isEmpty() || savedUrl.equals(DEFAULT_ODOO_URL)) {
            showSettings();
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

        // User agent - identify as our custom app
        String defaultUA = settings.getUserAgentString();
        settings.setUserAgentString(defaultUA + " TurmerFieldSalesApp/1.0");

        // Set up Bluetooth bridge - exposed as window.AndroidPrint in JavaScript
        bluetoothBridge = new BluetoothPrintBridge(this);
        webView.addJavascriptInterface(bluetoothBridge, "AndroidPrint");

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
        }, "AndroidSettings");

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
            "window.AndroidPrint.listDevices = function() {" +
            "  try { return JSON.parse(window.AndroidPrint.listPairedDevices()); }" +
            "  catch(e) { return { error: e.message }; }" +
            "};" +
            // Expose openSettings to JS (e.g. from a gear icon in the web app)
            "window.openAndroidSettings = function() { window.AndroidSettings.openSettings(); };" +
            "console.log('[TurmerApp] Android bridge ready.');" +
            "})();";
        webView.loadUrl(script);
    }

    // -------------------------------------------------------------------------
    // Back Button & Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (settingsVisible) {
            hideSettings();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothBridge != null) {
            bluetoothBridge.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Bluetooth Permission Handling
    // -------------------------------------------------------------------------

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] permissions = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            };
            boolean allGranted = true;
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN},
                    PERMISSION_REQUEST_CODE);
            }
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
