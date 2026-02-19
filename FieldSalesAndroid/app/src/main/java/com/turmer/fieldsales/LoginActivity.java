package com.turmer.fieldsales;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Premium branded login screen.
 *
 * Flow:
 *  1. User enters Server URL + Email + Password → tap LOG IN
 *  2. App calls  GET /web/database/list  to auto-detect the database:
 *       - 1 DB  → uses it silently, no extra UI
 *       - 0 / error → shows a "Database name" text field the user can fill
 *       - 2+ DBs → shows an AlertDialog picker
 *  3. Authenticates via POST /web/session/authenticate (JSON-RPC, no CSRF needed)
 *  4. On success → starts MainActivity with session cookie synced to WebView
 */
public class LoginActivity extends Activity {

    private static final String PREFS_NAME      = "FieldSalesPrefs";
    private static final String KEY_ODOO_URL    = "odoo_url";
    private static final String DEFAULT_ODOO_URL = "https://yourcompany.odoo.com";

    private EditText etUrl, etUsername, etPassword, etDatabase;
    private View     dbRow;          // The "Database name" row (hidden by default)
    private Button   btnLogin;
    private ProgressBar progressBar;
    private View     loadingOverlay;
    private boolean  passwordVisible = false;

    // ─────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        setContentView(buildUI());

        // Pre-fill saved URL
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etUrl.setText(prefs.getString(KEY_ODOO_URL, DEFAULT_ODOO_URL));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI BUILD
    // ─────────────────────────────────────────────────────────────────────────

    private View buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Gradient background: deep navy → purple
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0F0C29, 0xFF302B63, 0xFF24243E}));

        // Scrollable column
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(fill());
        scroll.setFillViewport(true);
        root.addView(scroll);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(32), dp(80), dp(32), dp(40));
        col.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.addView(col);

        // ── Logo ──
        FrameLayout logoCircle = new FrameLayout(this);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(dp(100), dp(100));
        lLp.gravity = Gravity.CENTER_HORIZONTAL;
        lLp.bottomMargin = dp(24);
        logoCircle.setLayoutParams(lLp);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF667EEA);
        circle.setStroke(dp(3), 0x44FFFFFF);
        logoCircle.setBackground(circle);
        TextView logoText = new TextView(this);
        logoText.setText("🚀");
        logoText.setTextSize(40);
        logoText.setGravity(Gravity.CENTER);
        logoText.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        logoCircle.addView(logoText);
        col.addView(logoCircle);

        // ── Title ──
        col.addView(centeredText("Field Sales", 30, Color.WHITE, true, dp(6)));
        col.addView(centeredText("Sign in to continue", 14, 0xAAFFFFFF, false, dp(40)));

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

        // Server URL
        card.addView(label("Server URL"));
        etUrl = field("https://yourcompany.odoo.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.addView(etUrl);
        card.addView(spacer(dp(16)));

        // Username
        card.addView(label("Email / Username"));
        etUsername = field("admin@example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(etUsername);
        card.addView(spacer(dp(16)));

        // Password + eye toggle
        card.addView(label("Password"));
        FrameLayout passRow = new FrameLayout(this);
        passRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        etPassword = field("••••••••",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        passRow.addView(etPassword);
        TextView eye = new TextView(this);
        eye.setText("👁");
        eye.setTextSize(18);
        eye.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams eyeLp = new FrameLayout.LayoutParams(dp(48), dp(52));
        eyeLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        eye.setLayoutParams(eyeLp);
        eye.setOnClickListener(v -> togglePassword());
        passRow.addView(eye);
        card.addView(passRow);

        // ── Database row (hidden by default, shown when needed) ──
        card.addView(spacer(dp(0)));  // placeholder spacing managed by visibility
        dbRow = buildDbRow(card);     // adds label + field, returns the wrapper view

        // ── Login button ──
        card.addView(spacer(dp(28)));
        btnLogin = new Button(this);
        btnLogin.setText("LOG IN");
        btnLogin.setTextSize(16);
        btnLogin.setTextColor(Color.WHITE);
        btnLogin.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btnLogin.setLetterSpacing(0.1f);
        GradientDrawable btnBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF667EEA, 0xFF764BA2});
        btnBg.setCornerRadius(dp(14));
        btnLogin.setBackground(btnBg);
        btnLogin.setOnClickListener(v -> startLoginFlow());
        card.addView(btnLogin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        // Footer
        col.addView(centeredText("Powered by Truets Tech Solutions Pvt.Ltd",
                11, 0x66FFFFFF, false, 0));

        // Loading overlay (on top of everything)
        loadingOverlay = new View(this);
        loadingOverlay.setBackgroundColor(0x88000000);
        loadingOverlay.setVisibility(View.GONE);
        loadingOverlay.setLayoutParams(fill());
        root.addView(loadingOverlay);

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dp(60), dp(60));
        pbLp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbLp);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        return root;
    }

    /** Builds the database label + text field row inside the card, hidden by default. */
    private View buildDbRow(LinearLayout card) {
        // We wrap label + spacer + field in a single LinearLayout so we can show/hide together
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setVisibility(View.GONE);   // hidden until needed
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View topSpace = spacer(dp(16));
        row.addView(topSpace);
        row.addView(buildLabelView("Database Name"));
        etDatabase = buildFieldView("e.g.  mycompany",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        row.addView(etDatabase);

        card.addView(row);
        return row;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOGIN FLOW  (Step 1: detect DB, step 2: authenticate)
    // ─────────────────────────────────────────────────────────────────────────

    private void startLoginFlow() {
        String rawUrl    = etUrl.getText().toString().trim().replaceAll("/+$", "");
        String username  = etUsername.getText().toString().trim();
        String password  = etPassword.getText().toString();

        if (rawUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        // Derive clean base URL (scheme+host+port only)
        String baseUrl;
        try {
            java.net.URL parsed = new java.net.URL(rawUrl);
            int port = parsed.getPort();
            baseUrl = parsed.getProtocol() + "://" + parsed.getHost()
                    + (port != -1 ? ":" + port : "");
        } catch (Exception ex) {
            showError("Invalid server URL: " + ex.getMessage());
            return;
        }

        // If the db row is visible and user typed a DB name, skip auto-detection
        String manualDb = etDatabase.getText().toString().trim();
        if (dbRow.getVisibility() == View.VISIBLE && !manualDb.isEmpty()) {
            authenticate(baseUrl, username, password, manualDb);
            return;
        }

        // Step 1 — auto-detect DB list
        showLoading(true);
        String finalBase = baseUrl;
        new Thread(() -> fetchDatabaseList(finalBase, username, password)).start();
    }

    // ─── Step 1: fetch DB list ────────────────────────────────────────────────

    private void fetchDatabaseList(String baseUrl, String username, String password) {
        try {
            URL url = new URL(baseUrl + "/web/database/list");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            // JSON-RPC body
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", "call");
            body.put("id", 1);
            body.put("params", new JSONObject());

            byte[] payload = body.toString().getBytes("UTF-8");
            try (OutputStream os = conn.getOutputStream()) { os.write(payload); }

            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code == 200 ? conn.getInputStream() : conn.getErrorStream()))) {
                String l; while ((l = br.readLine()) != null) sb.append(l);
            }

            JSONObject resp = new JSONObject(sb.toString());
            JSONArray dbArray = resp.optJSONArray("result");

            if (dbArray == null || dbArray.length() == 0) {
                // API disabled or no databases — show manual field
                runOnUiThread(() -> {
                    showLoading(false);
                    showDbFieldWithHint("Database list unavailable — please enter it manually");
                });
                return;
            }

            List<String> dbs = new ArrayList<>();
            for (int i = 0; i < dbArray.length(); i++) dbs.add(dbArray.getString(i));

            if (dbs.size() == 1) {
                // Perfect — one DB, proceed silently
                authenticate(baseUrl, username, password, dbs.get(0));
            } else {
                // Multiple DBs — ask user to pick
                showLoading(false);
                runOnUiThread(() -> showDbPicker(dbs, baseUrl, username, password));
            }

        } catch (Exception e) {
            // Network error or endpoint not available — show manual field
            runOnUiThread(() -> {
                showLoading(false);
                showDbFieldWithHint("Could not auto-detect DB. Enter it manually.");
            });
        }
    }

    /** Show the hidden database field + a hint toast so the user knows why. */
    private void showDbFieldWithHint(String hint) {
        dbRow.setVisibility(View.VISIBLE);
        etDatabase.requestFocus();
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
    }

    /** Show an AlertDialog with the list of found databases. */
    private void showDbPicker(List<String> dbs, String baseUrl,
                              String username, String password) {
        String[] items = dbs.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Select Database")
                .setItems(items, (dialog, which) ->
                        authenticate(baseUrl, username, password, items[which]))
                .setCancelable(true)
                .show();
    }

    // ─── Step 2: authenticate with chosen DB ──────────────────────────────────

    private void authenticate(String baseUrl, String username,
                               String password, String database) {
        showLoading(true);
        new Thread(() -> {
            try {
                URL url = new URL(baseUrl + "/web/session/authenticate");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                JSONObject params = new JSONObject();
                params.put("db", database);
                params.put("login", username);
                params.put("password", password);

                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("method", "call");
                body.put("id", 1);
                body.put("params", params);

                byte[] payload = body.toString().getBytes("UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
                try (OutputStream os = conn.getOutputStream()) { os.write(payload); }

                int code = conn.getResponseCode();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        code == 200 ? conn.getInputStream() : conn.getErrorStream()))) {
                    String l; while ((l = br.readLine()) != null) sb.append(l);
                }

                JSONObject resp   = new JSONObject(sb.toString());
                JSONObject result = resp.optJSONObject("result");
                JSONObject error  = resp.optJSONObject("error");

                if (error != null) {
                    String msg = error.optJSONObject("data") != null
                            ? error.getJSONObject("data").optString("message", "Login failed")
                            : error.optString("message", "Login failed");
                    showLoading(false);
                    runOnUiThread(() -> showError(msg));
                    return;
                }

                Object uid = result != null ? result.opt("uid") : null;
                if (uid == null || uid.equals(false) || uid.equals(JSONObject.NULL)
                        || String.valueOf(uid).equals("false")) {
                    showLoading(false);
                    runOnUiThread(() -> showError("Invalid username or password"));
                    return;
                }

                // ── SUCCESS ──────────────────────────────────────────────────
                String setCookie = conn.getHeaderField("Set-Cookie");

                // Save clean base URL
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_ODOO_URL, baseUrl).apply();

                showLoading(false);
                runOnUiThread(() -> {
                    Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                    intent.putExtra("odoo_url", baseUrl);
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

    // ─────────────────────────────────────────────────────────────────────────
    // UI HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        runOnUiThread(() -> {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            btnLogin.setEnabled(!show);
            btnLogin.setAlpha(show ? 0.5f : 1.0f);
        });
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        ObjectAnimator.ofFloat(btnLogin, "translationX", 0, -20, 20, -15, 15, -10, 10, 0)
                .setDuration(400).start();
    }

    private void togglePassword() {
        passwordVisible = !passwordVisible;
        etPassword.setInputType(passwordVisible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setSelection(etPassword.getText().length());
    }

    // ── Widget builders ───────────────────────────────────────────────────────

    private TextView label(String text) { return buildLabelView(text); }
    private TextView buildLabelView(String text) {
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

    private EditText field(String hint, int inputType) { return buildFieldView(hint, inputType); }
    private EditText buildFieldView(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(0x66FFFFFF);
        et.setTextColor(Color.WHITE);
        et.setTextSize(15);
        et.setInputType(inputType);
        et.setPadding(dp(16), dp(14), dp(48), dp(14));
        et.setSingleLine(true);
        et.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(12));
        bg.setColor(0x22FFFFFF);
        bg.setStroke(1, 0x44FFFFFF);
        et.setBackground(bg);
        return et;
    }

    private TextView centeredText(String text, int sizeSp, int color, boolean bold, int bottomMar) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        if (bold) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMar;
        tv.setLayoutParams(lp);
        return tv;
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    private FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
