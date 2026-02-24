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
 * Behaviour:
 *  - Server URL field is shown only on FIRST launch. After it is saved it is hidden
 *    and replaced with a compact "Server: host  [Change]" label.
 *  - DB auto-detected via /web/database/list.
 *  - On success → opens MainActivity which loads the Field Sales app directly
 *    (using /web#action=tts_field_sales so Odoo bypasses its own login page).
 */
public class LoginActivity extends Activity {

    private static final String PREFS_NAME   = "FieldSalesPrefs";
    private static final String KEY_ODOO_URL = "odoo_url";

    /** The exact URL typed by the user — loaded by MainActivity after login. */
    private String rawAppUrl = "";

    private EditText   etUrl, etUsername, etPassword, etDatabase;
    private View       urlInputRow;    // full URL EditText + label wrapper (first-time only)
    private View       urlChipRow;    // compact "Server: xxx  [Change]" row (after URL saved)
    private TextView   tvUrlChip;     // the hostname chip text
    private View       dbRow;          // DB name field (hidden until needed)
    private Button     btnLogin;
    private ProgressBar progressBar;
    private View        loadingOverlay;
    private boolean    passwordVisible = false;
    private boolean    urlEditMode     = false;  // true when user tapped "Change"

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

        // Load saved URL and decide which URL row to show
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_ODOO_URL, "");

        if (savedUrl.isEmpty()) {
            // First launch — show the full URL input field
            urlInputRow.setVisibility(View.VISIBLE);
            urlChipRow.setVisibility(View.GONE);
        } else {
            // URL already set — show compact chip, hide the big input field
            urlInputRow.setVisibility(View.GONE);
            urlChipRow.setVisibility(View.VISIBLE);
            updateUrlChip(savedUrl);
            // Pre-populate if user ever taps Change
            etUrl.setText(savedUrl);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI BUILD
    // ─────────────────────────────────────────────────────────────────────────

    private View buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0F0C29, 0xFF302B63, 0xFF24243E}));

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(fill(FrameLayout.LayoutParams.class));
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
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(100), dp(100));
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.bottomMargin = dp(24);
        logoCircle.setLayoutParams(llp);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF667EEA);
        circle.setStroke(dp(3), 0x44FFFFFF);
        logoCircle.setBackground(circle);
        TextView logoTv = new TextView(this);
        logoTv.setText("🚀");
        logoTv.setTextSize(40);
        logoTv.setGravity(Gravity.CENTER);
        logoTv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        logoCircle.addView(logoTv);
        col.addView(logoCircle);

        col.addView(centeredText("Field Sales",       30, Color.WHITE,  true,  dp(6)));
        col.addView(centeredText("Sign in to continue", 14, 0xAAFFFFFF, false, dp(40)));

        // ── Card ──
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

        // ── URL compact chip row (shown after URL is saved) ──
        urlChipRow = buildUrlChipRow(card);   // adds itself to card, returns view
        card.addView(spacer(dp(16)));

        // ── URL input row (shown only first time or after Change) ──
        urlInputRow = buildUrlInputRow(card); // adds itself to card, returns view

        // ── Username ──
        card.addView(label("Email / Username"));
        etUsername = field("admin@example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        card.addView(etUsername);
        card.addView(spacer(dp(16)));

        // ── Password + eye toggle ──
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

        // ── Database row (hidden — appears when auto-detect fails) ──
        dbRow = buildDbRow(card);

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

        // Loading overlay
        loadingOverlay = new View(this);
        loadingOverlay.setBackgroundColor(0x88000000);
        loadingOverlay.setVisibility(View.GONE);
        loadingOverlay.setLayoutParams(fill(FrameLayout.LayoutParams.class));
        root.addView(loadingOverlay);

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dp(60), dp(60));
        pbLp.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbLp);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        return root;
    }

    // ─── Compact URL chip: "Server: hostname  [Change]" ───────────────────────
    private View buildUrlChipRow(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvUrlChip = new TextView(this);
        tvUrlChip.setTextColor(0xAAFFFFFF);
        tvUrlChip.setTextSize(12);
        tvUrlChip.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tvUrlChip.setSingleLine(true);
        tvUrlChip.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvUrlChip);

        TextView changeBtn = new TextView(this);
        changeBtn.setText("✏ Change");
        changeBtn.setTextSize(11);
        changeBtn.setTextColor(0xFF667EEA);
        changeBtn.setPadding(dp(8), dp(4), 0, dp(4));
        changeBtn.setOnClickListener(v -> switchToUrlEditMode());
        row.addView(changeBtn);

        card.addView(row);
        return row;
    }

    // ─── Full URL input (first-time or after Change) ──────────────────────────
    private View buildUrlInputRow(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setVisibility(View.GONE);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addView(label("Server URL"));
        etUrl = field("https://yourcompany.odoo.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        row.addView(etUrl);
        row.addView(spacer(dp(16)));

        card.addView(row);
        return row;
    }

    /** Switch from compact chip back to editable URL field. */
    private void switchToUrlEditMode() {
        urlEditMode = true;
        urlChipRow.setVisibility(View.GONE);
        urlInputRow.setVisibility(View.VISIBLE);
        etUrl.requestFocus();
    }

    /** Update chip text to show just the hostname. */
    private void updateUrlChip(String fullUrl) {
        try {
            java.net.URL parsed = new java.net.URL(fullUrl);
            tvUrlChip.setText("🔗  " + parsed.getHost());
        } catch (Exception e) {
            tvUrlChip.setText("🔗  " + fullUrl);
        }
    }

    // ─── DB row ───────────────────────────────────────────────────────────────
    private View buildDbRow(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setVisibility(View.GONE);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(spacer(dp(16)));
        row.addView(label("Database Name"));
        etDatabase = field("e.g. mycompany",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        row.addView(etDatabase);
        card.addView(row);
        return row;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOGIN FLOW
    // ─────────────────────────────────────────────────────────────────────────

    private void startLoginFlow() {
        // Determine the URL to use
        String rawUrl;
        if (urlInputRow.getVisibility() == View.VISIBLE) {
            // First time or Change mode — read from field
            rawUrl = etUrl.getText().toString().trim().replaceAll("/+$", "");
        } else {
            // Chip mode — read from saved prefs
            rawUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_ODOO_URL, "");
        }

        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (rawUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
            return;
        }

        // ── Save the exact URL the user typed — this is what the app will load after login
        rawAppUrl = rawUrl;

        // Extract base URL (scheme + host + port only) — used only for auth API calls
        String baseUrl;
        try {
            java.net.URL parsed = new java.net.URL(rawUrl);
            int port = parsed.getPort();
            baseUrl = parsed.getProtocol() + "://" + parsed.getHost()
                    + (port != -1 ? ":" + port : "");
        } catch (Exception ex) {
            showError("Invalid server URL");
            return;
        }

        // If DB row is visible and user typed a name → skip auto-detect
        String manualDb = etDatabase.getText().toString().trim();
        if (dbRow.getVisibility() == View.VISIBLE && !manualDb.isEmpty()) {
            authenticate(baseUrl, username, password, manualDb);
            return;
        }

        showLoading(true);
        String finalBase = baseUrl;
        new Thread(() -> fetchDatabaseList(finalBase, username, password)).start();
    }

    // ─── Step 1: detect DB ────────────────────────────────────────────────────
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

            JSONArray dbArray = new JSONObject(sb.toString()).optJSONArray("result");

            if (dbArray == null || dbArray.length() == 0) {
                runOnUiThread(() -> { showLoading(false); showDbField("Enter database name manually"); });
                return;
            }

            List<String> dbs = new ArrayList<>();
            for (int i = 0; i < dbArray.length(); i++) dbs.add(dbArray.getString(i));

            if (dbs.size() == 1) {
                authenticate(baseUrl, username, password, dbs.get(0));
            } else {
                showLoading(false);
                runOnUiThread(() -> showDbPicker(dbs, baseUrl, username, password));
            }

        } catch (Exception e) {
            runOnUiThread(() -> { showLoading(false); showDbField("Could not auto-detect. Enter DB name."); });
        }
    }

    private void showDbField(String hint) {
        dbRow.setVisibility(View.VISIBLE);
        etDatabase.setHint(hint);
        etDatabase.requestFocus();
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
    }

    private void showDbPicker(List<String> dbs, String baseUrl,
                               String username, String password) {
        new AlertDialog.Builder(this)
                .setTitle("Select Database")
                .setItems(dbs.toArray(new String[0]),
                        (d, i) -> authenticate(baseUrl, username, password, dbs.get(i)))
                .setCancelable(true)
                .show();
    }

    // ─── Step 2: authenticate ─────────────────────────────────────────────────
    private void authenticate(String baseUrl, String username,
                               String password, String database) {
        showLoading(true);
        new Thread(() -> {
            try {
                URL url = new URL(baseUrl + "/web/session/authenticate");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept",       "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                JSONObject params = new JSONObject();
                params.put("db",       database);
                params.put("login",    username);
                params.put("password", password);

                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("method",  "call");
                body.put("id",      1);
                body.put("params",  params);

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

                // Save the exact URL the user typed — this is what loads in the app
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_ODOO_URL, rawAppUrl).apply();

                showLoading(false);
                runOnUiThread(() -> {
                    Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                    intent.putExtra("odoo_url",       rawAppUrl);   // exact URL to load
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
    // HELPERS
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
    private TextView label(String text) {
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

    private EditText field(String hint, int inputType) {
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

    private TextView centeredText(String text, int sp, int color, boolean bold, int botMar) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        if (bold) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = botMar;
        tv.setLayoutParams(lp);
        return tv;
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    @SuppressWarnings("unchecked")
    private <T extends ViewGroup.LayoutParams> T fill(Class<T> cls) {
        if (cls == FrameLayout.LayoutParams.class)
            return (T) new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        return (T) new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
