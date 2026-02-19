package com.turmer.fieldsales;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Premium branded login screen.
 * Authenticates via Odoo's /web/session/authenticate endpoint.
 * On success, starts MainActivity with the session cookie copied.
 */
public class LoginActivity extends Activity {

    private static final String PREFS_NAME = "FieldSalesPrefs";
    private static final String KEY_ODOO_URL = "odoo_url";
    private static final String DEFAULT_ODOO_URL = "https://yourcompany.odoo.com";

    private EditText etUrl, etUsername, etPassword;
    private Button btnLogin;
    private ProgressBar progressBar;
    private View overlay;
    private boolean passwordVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full-screen immersive
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        setContentView(buildUI());

        // Load saved URL
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_ODOO_URL, DEFAULT_ODOO_URL);
        etUrl.setText(savedUrl);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI BUILD (all in code — no XML needed)
    // ─────────────────────────────────────────────────────────────────────────

    private View buildUI() {
        // Root: FrameLayout (allows overlay stacking)
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Gradient Background ---
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0F0C29, 0xFF302B63, 0xFF24243E});
        root.setBackground(bg);

        // --- Scrollable content ---
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setFillViewport(true);
        root.addView(scroll);

        // --- Main card column ---
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(32), dp(80), dp(32), dp(40));
        col.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.addView(col);

        // ── Logo circle ──
        FrameLayout logoCircle = new FrameLayout(this);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(100), dp(100));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        logoLp.bottomMargin = dp(24);
        logoCircle.setLayoutParams(logoLp);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF667EEA);
        circle.setStroke(dp(3), 0x44FFFFFF);
        logoCircle.setBackground(circle);

        TextView logoIcon = new TextView(this);
        logoIcon.setText("🚀");
        logoIcon.setTextSize(40);
        logoIcon.setGravity(Gravity.CENTER);
        logoIcon.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        logoCircle.addView(logoIcon);
        col.addView(logoCircle);

        // ── App title ──
        TextView tvTitle = new TextView(this);
        tvTitle.setText("Field Sales");
        tvTitle.setTextSize(30);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(6);
        tvTitle.setLayoutParams(titleLp);
        col.addView(tvTitle);

        // ── Subtitle ──
        TextView tvSub = new TextView(this);
        tvSub.setText("Sign in to continue");
        tvSub.setTextSize(14);
        tvSub.setTextColor(0xAAFFFFFF);
        tvSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(40);
        tvSub.setLayoutParams(subLp);
        col.addView(tvSub);

        // ── Glassmorphism card ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(28));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(24);
        card.setLayoutParams(cardLp);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dp(20));
        cardBg.setColor(0x33FFFFFF);
        cardBg.setStroke(1, 0x44FFFFFF);
        card.setBackground(cardBg);
        col.addView(card);

        // ── Server URL field ──
        card.addView(fieldLabel("Server URL"));
        etUrl = styledField("https://yourcompany.odoo.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.addView(etUrl);
        card.addView(spacer(dp(16)));

        // ── Username field ──
        card.addView(fieldLabel("Email / Username"));
        etUsername = styledField("admin@example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(etUsername);
        card.addView(spacer(dp(16)));

        // ── Password field with toggle ──
        card.addView(fieldLabel("Password"));
        FrameLayout passRow = new FrameLayout(this);
        passRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        etPassword = styledField("••••••••",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        passRow.addView(etPassword);

        // Eye toggle
        TextView eyeBtn = new TextView(this);
        eyeBtn.setText("👁");
        eyeBtn.setTextSize(18);
        eyeBtn.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams eyeLp = new FrameLayout.LayoutParams(dp(48), dp(52));
        eyeLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        eyeBtn.setLayoutParams(eyeLp);
        eyeBtn.setOnClickListener(v -> togglePasswordVisibility());
        passRow.addView(eyeBtn);
        card.addView(passRow);

        // ── Login Button ──
        card.addView(spacer(dp(28)));
        btnLogin = new Button(this);
        btnLogin.setText("LOG IN");
        btnLogin.setTextSize(16);
        btnLogin.setTextColor(Color.WHITE);
        btnLogin.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btnLogin.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        card.addView(btnLogin, btnLp);
        GradientDrawable btnBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF667EEA, 0xFF764BA2});
        btnBg.setCornerRadius(dp(14));
        btnLogin.setBackground(btnBg);
        btnLogin.setOnClickListener(v -> attemptLogin());

        // ── Footer ──
        TextView footer = new TextView(this);
        footer.setText("Powered by Truets Tech Solutions Pvt.Ltd");
        footer.setTextSize(11);
        footer.setTextColor(0x66FFFFFF);
        footer.setGravity(Gravity.CENTER);
        col.addView(footer);

        // ── Loading overlay ──
        overlay = new View(this);
        overlay.setBackgroundColor(0x88000000);
        overlay.setVisibility(View.GONE);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlay);

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dp(60), dp(60));
        pbLp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbLp);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        return root;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private TextView fieldLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xCCFFFFFF);
        tv.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private EditText styledField(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(0x66FFFFFF);
        et.setTextColor(Color.WHITE);
        et.setTextSize(15);
        et.setInputType(inputType);
        et.setPadding(dp(16), dp(14), dp(48), dp(14));
        et.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        et.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(12));
        bg.setColor(0x22FFFFFF);
        bg.setStroke(1, 0x44FFFFFF);
        et.setBackground(bg);
        return et;
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        if (passwordVisible) {
            etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        } else {
            etPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        etPassword.setSelection(etPassword.getText().length());
    }

    private void showLoading(boolean show) {
        runOnUiThread(() -> {
            overlay.setVisibility(show ? View.VISIBLE : View.GONE);
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            btnLogin.setEnabled(!show);
            btnLogin.setAlpha(show ? 0.5f : 1.0f);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AUTHENTICATION
    // ─────────────────────────────────────────────────────────────────────────

    private void attemptLogin() {
        String serverUrl = etUrl.getText().toString().trim().replaceAll("/+$", "");
        String username  = etUsername.getText().toString().trim();
        String password  = etPassword.getText().toString();

        if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save URL
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_ODOO_URL, serverUrl).apply();

        showLoading(true);

        String finalServer = serverUrl;
        new Thread(() -> {
            try {
                // Call Odoo authenticate endpoint
                String authUrl = finalServer + "/web/session/authenticate";
                URL url = new URL(authUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                // JSON-RPC body — db="" lets Odoo pick single-db automatically
                JSONObject params = new JSONObject();
                params.put("db", "");         // empty = auto-select DB
                params.put("login", username);
                params.put("password", password);

                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("method", "call");
                body.put("id", 1);
                body.put("params", params);

                byte[] payload = body.toString().getBytes("UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(payload.length));

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                // Read response
                int code = conn.getResponseCode();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                code == 200 ? conn.getInputStream() : conn.getErrorStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                // Parse result
                JSONObject resp = new JSONObject(sb.toString());
                JSONObject result = resp.optJSONObject("result");
                JSONObject error  = resp.optJSONObject("error");

                if (error != null) {
                    // JSON-RPC level error
                    String msg = error.optJSONObject("data") != null
                            ? error.getJSONObject("data").optString("message", "Login failed")
                            : error.optString("message", "Login failed");
                    showLoading(false);
                    runOnUiThread(() -> showError(msg));
                    return;
                }

                // uid == false → bad credentials
                Object uid = result != null ? result.opt("uid") : null;
                if (uid == null || uid.equals(false) || uid.equals(JSONObject.NULL)
                        || String.valueOf(uid).equals("false")) {
                    showLoading(false);
                    runOnUiThread(() -> showError("Invalid username or password"));
                    return;
                }

                // ── SUCCESS ──────────────────────────────────────────────────
                // Extract session_id from response header or cookie
                String setCookie = conn.getHeaderField("Set-Cookie");
                // WebView CookieManager will sync cookies automatically when we load the URL.
                // We just need to pass the server URL to MainActivity.

                showLoading(false);
                runOnUiThread(() -> {
                    Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                    intent.putExtra("odoo_url", finalServer);
                    intent.putExtra("session_cookie", setCookie != null ? setCookie : "");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                });

            } catch (Exception e) {
                showLoading(false);
                runOnUiThread(() -> showError("Connection failed: " + e.getMessage()));
            }
        }).start();
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        // Shake the login button to indicate error
        ObjectAnimator shake = ObjectAnimator.ofFloat(btnLogin, "translationX",
                0, -20, 20, -15, 15, -10, 10, 0);
        shake.setDuration(400);
        shake.start();
    }
}
