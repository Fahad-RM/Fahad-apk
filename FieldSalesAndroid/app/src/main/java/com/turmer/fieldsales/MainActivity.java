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
    private FrameLayout splashOverlay;
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

        splashOverlay = new FrameLayout(this);
        splashOverlay.setBackgroundColor(Color.parseColor("#1a0533"));
        root.addView(splashOverlay);

        setContentView(root);

        printer = new EscPosPrinter(this);

        setupWebView();
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
                runOnUiThread(() -> {
                    if (splashOverlay != null && splashOverlay.getVisibility() == View.VISIBLE) {
                        splashOverlay.setVisibility(View.GONE);
                    }
                });
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

        final WebView offscreenWV = new WebView(this);
        int width = 576; // 80mm printer width

        // Inject high-fidelity CSS for exact matching of the 80mm template
        String styledHtml = "<html><head><style>" +
                "@page { size: 80mm auto; margin: 0; }" +
                "body { margin: 0 !important; padding: 0 4mm !important; width: 80mm !important; font-size: 12px !important; background: #FFF !important; color: black; font-family: sans-serif; }" +
                ".o_main_navbar, .o_control_panel, header, footer { display: none !important; }" +
                ".page { margin: 0 !important; border: none !important; }" +
                "table { width: 100% !important; border-collapse: collapse; }" +
                "img { max-width: 100% !important; height: auto !important; }" +
                "</style></head><body>" + html + "</body></html>";

        offscreenWV.getSettings().setJavaScriptEnabled(true);
        offscreenWV.loadDataWithBaseURL(null, styledHtml, "text/html", "utf-8", null);

        offscreenWV.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {

                view.postDelayed(() -> {
                    try {

                        view.measure(
                                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        );

                        int height = view.getMeasuredHeight();
                        if (height <= 0) height = 1000;

                        android.graphics.Bitmap bitmap =
                                android.graphics.Bitmap.createBitmap(
                                        width,
                                        height,
                                        android.graphics.Bitmap.Config.RGB_565
                                );

                        view.layout(0, 0, width, height);
                        view.draw(new android.graphics.Canvas(bitmap));

                        new Thread(() -> {

                            boolean connected = printer.connect();

                            if (connected) {

                                runOnUiThread(() ->
                                        Toast.makeText(MainActivity.this,
                                                "Printing...",
                                                Toast.LENGTH_SHORT).show());

                                printer.printBitmap(bitmap);
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

                            bitmap.recycle();

                        }).start();

                    } catch (Exception e) {
                        Log.e("AndroidPrint", "Print failed", e);
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