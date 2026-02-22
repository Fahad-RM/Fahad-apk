package com.turmer.fieldsales;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class EscPosPrinter {
    private static final String TAG = "EscPosPrinter";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private OutputStream outputStream;

    public EscPosPrinter() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @SuppressLint("MissingPermission")
    public String connect() {
        if (bluetoothAdapter == null) return "Bluetooth not supported";
        if (!bluetoothAdapter.isEnabled()) return "Bluetooth is Off";

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices == null || pairedDevices.isEmpty()) {
            return "No paired devices found. Please pair printer in Settings.";
        }

        StringBuilder sb = new StringBuilder();

        // 1. First, try to find a device that has a typical printer name
        BluetoothDevice likelyPrinter = null;
        for (BluetoothDevice device : pairedDevices) {
            String name = device.getName();
            if (name == null) continue;
            String upper = name.toUpperCase();
            if (upper.contains("PRINTER") || upper.contains("MPT") || 
                upper.contains("HPRT") || upper.contains("BT") || 
                upper.contains("58") || upper.contains("80") ||
                upper.contains("POS")) {
                likelyPrinter = device;
                break;
            }
        }

        // Try the likely printer first to avoid long connection timeouts on other devices
        if (likelyPrinter != null) {
            try {
                Log.d(TAG, "Connecting to likely printer: " + likelyPrinter.getName());
                socket = likelyPrinter.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                outputStream = socket.getOutputStream();
                return "OK";
            } catch (IOException e) {
                Log.e(TAG, "Failed likely printer: " + likelyPrinter.getName(), e);
                sb.append(likelyPrinter.getName()).append(" (Failed), ");
                close();
            }
        }

        // 2. If no likely printer or it failed, try ALL other paired devices
        for (BluetoothDevice device : pairedDevices) {
            if (likelyPrinter != null && device.getAddress().equals(likelyPrinter.getAddress())) {
                continue; // Already tried
            }

            String name = device.getName();
            Log.d(TAG, "Trying fallback connection to: " + name);
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                outputStream = socket.getOutputStream();
                return "OK";
            } catch (IOException e) {
                Log.e(TAG, "Failed fallback: " + name, e);
                sb.append(name).append(" (Failed), ");
                close();
            }
        }

        return "Could not connect to any paired device. Tried: " + sb.toString();
    }

    public void printBitmap(Bitmap bitmap) {
        if (outputStream == null) return;

        try {
            // Initialize printer
            outputStream.write(new byte[]{0x1B, 0x40});
            
            // Print bitmap data
            byte[] command = decodeBitmap(bitmap);
            outputStream.write(command);
            
            // Feed and cut (or partial cut)
            outputStream.write(new byte[]{0x0A, 0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x42, 0x00});
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Print failed", e);
        }
    }

    /**
     * Convert Bitmap to ESC/POS GS v 0 (Raster Image) bytes.
     */
    private byte[] decodeBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int bw = (width + 7) / 8;
        
        byte[] data = new byte[8 + bw * height];
        data[0] = 0x1D; // GS
        data[1] = 0x76; // v
        data[2] = 0x30; // 0
        data[3] = 0x00; // Mode 0 (Normal)
        data[4] = (byte) (bw & 0xFF);
        data[5] = (byte) ((bw >> 8) & 0xFF);
        data[6] = (byte) (height & 0xFF);
        data[7] = (byte) ((height >> 8) & 0xFF);

        int k = 8;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < bw; j++) {
                int b = 0;
                for (int m = 0; m < 8; m++) {
                    int x = j * 8 + m;
                    if (x < width) {
                        int pixel = bitmap.getPixel(x, i);
                        int r = Color.red(pixel);
                        int g = Color.green(pixel);
                        int b_val = Color.blue(pixel);
                        // Convert to monochrome using luminance (approx)
                        if (r < 128 || g < 128 || b_val < 128) {
                            b |= (1 << (7 - m));
                        }
                    }
                }
                data[k++] = (byte) b;
            }
        }
        return data;
    }

    public void close() {
        try {
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            Log.e(TAG, "Close failed", e);
        }
    }
}
