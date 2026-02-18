package com.turmer.fieldsales;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * JavaScript Bridge for Bluetooth Printing
 * Exposed to WebView as window.AndroidPrint
 *
 * Usage from JavaScript:
 *   window.AndroidPrint.listPairedDevices()  -> JSON string of paired devices
 *   window.AndroidPrint.connect(address)      -> "OK" or error message
 *   window.AndroidPrint.print(base64Data)     -> "OK" or error message
 *   window.AndroidPrint.disconnect()          -> "OK"
 *   window.AndroidPrint.isConnected()         -> "true" or "false"
 *   window.AndroidPrint.getConnectedDevice()  -> device name or ""
 */
public class BluetoothPrintBridge {

    // Standard SPP UUID for Classic Bluetooth serial communication
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Activity activity;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private String connectedDeviceName = "";

    public BluetoothPrintBridge(Activity activity) {
        this.activity = activity;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * Returns a JSON array of paired Bluetooth devices
     * Called from JS: window.AndroidPrint.listPairedDevices()
     */
    @JavascriptInterface
    public String listPairedDevices() {
        try {
            if (!checkBluetoothPermission()) {
                return "{\"error\": \"Bluetooth permission not granted\"}";
            }

            if (bluetoothAdapter == null) {
                return "{\"error\": \"Bluetooth not supported on this device\"}";
            }

            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            JSONArray devices = new JSONArray();

            for (BluetoothDevice device : pairedDevices) {
                JSONObject deviceInfo = new JSONObject();
                deviceInfo.put("name", device.getName() != null ? device.getName() : "Unknown");
                deviceInfo.put("address", device.getAddress());
                deviceInfo.put("type", device.getType()); // 1=Classic, 2=BLE, 3=Dual
                devices.put(deviceInfo);
            }

            JSONObject result = new JSONObject();
            result.put("devices", devices);
            return result.toString();

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Connects to a Bluetooth device by MAC address
     * Called from JS: window.AndroidPrint.connect("AA:BB:CC:DD:EE:FF")
     */
    @JavascriptInterface
    public String connect(String deviceAddress) {
        try {
            if (!checkBluetoothPermission()) {
                return "ERROR: Bluetooth permission not granted";
            }

            if (bluetoothAdapter == null) {
                return "ERROR: Bluetooth not supported";
            }

            // Disconnect existing connection
            disconnect();

            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            connectedDeviceName = device.getName() != null ? device.getName() : deviceAddress;

            // Cancel discovery to speed up connection
            bluetoothAdapter.cancelDiscovery();

            // Connect via SPP (Serial Port Profile) - used by most thermal printers
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            bluetoothSocket.connect();
            outputStream = bluetoothSocket.getOutputStream();

            return "OK:" + connectedDeviceName;

        } catch (IOException e) {
            connectedDeviceName = "";
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            connectedDeviceName = "";
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Prints raw ESC/POS data (base64 encoded)
     * Called from JS: window.AndroidPrint.print(base64EncodedData)
     */
    @JavascriptInterface
    public String print(String base64Data) {
        try {
            if (outputStream == null || bluetoothSocket == null || !bluetoothSocket.isConnected()) {
                return "ERROR: Printer not connected";
            }

            // Decode base64 to bytes
            byte[] data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);

            // Write in chunks to avoid buffer overflow
            int chunkSize = 512;
            for (int i = 0; i < data.length; i += chunkSize) {
                int end = Math.min(i + chunkSize, data.length);
                outputStream.write(data, i, end - i);
                outputStream.flush();
                Thread.sleep(50); // Small delay between chunks
            }

            return "OK";

        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Prints raw text (plain string, not base64)
     * Called from JS: window.AndroidPrint.printText(text)
     */
    @JavascriptInterface
    public String printText(String text) {
        try {
            if (outputStream == null || bluetoothSocket == null || !bluetoothSocket.isConnected()) {
                return "ERROR: Printer not connected";
            }

            byte[] data = text.getBytes("UTF-8");
            outputStream.write(data);
            outputStream.flush();

            return "OK";

        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Disconnects from the Bluetooth printer
     * Called from JS: window.AndroidPrint.disconnect()
     */
    @JavascriptInterface
    public String disconnect() {
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
                bluetoothSocket = null;
            }
            connectedDeviceName = "";
            return "OK";
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Returns whether a printer is currently connected
     * Called from JS: window.AndroidPrint.isConnected()
     */
    @JavascriptInterface
    public String isConnected() {
        boolean connected = bluetoothSocket != null && bluetoothSocket.isConnected();
        return connected ? "true" : "false";
    }

    /**
     * Returns the name of the connected device
     * Called from JS: window.AndroidPrint.getConnectedDevice()
     */
    @JavascriptInterface
    public String getConnectedDevice() {
        return connectedDeviceName;
    }

    /**
     * Shows a native Android toast message
     * Called from JS: window.AndroidPrint.showToast("message")
     */
    @JavascriptInterface
    public void showToast(String message) {
        activity.runOnUiThread(() ->
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        );
    }

    /**
     * Check if running inside Android WebView (always returns true)
     * Called from JS: window.AndroidPrint.isAndroidApp()
     */
    @JavascriptInterface
    public String isAndroidApp() {
        return "true";
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean checkBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            // Android 11 and below
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }
}
