package com.turmer.fieldsales;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;                 // ✅ ADDED
import android.content.SharedPreferences;       // ✅ ADDED
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class EscPosPrinter {

    private static final String TAG = "EscPosPrinter";
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private Context context;

    public EscPosPrinter(Context context) {
        this.context = context;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @SuppressLint("MissingPermission")
    public boolean connect() {

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported");
            return false;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is OFF");
            return false;
        }

        try {

            SharedPreferences prefs =
                    context.getSharedPreferences("FieldSalesPrefs", Context.MODE_PRIVATE);

            String savedMac = prefs.getString("selected_printer_mac", "");

            BluetoothDevice device = null;

            if (!savedMac.isEmpty()) {
                device = bluetoothAdapter.getRemoteDevice(savedMac);
                Log.d(TAG, "Connecting to saved printer: " + savedMac);
            } else {

                Set<BluetoothDevice> pairedDevices =
                        bluetoothAdapter.getBondedDevices();

                if (pairedDevices == null || pairedDevices.isEmpty()) {
                    Log.e(TAG, "No paired devices found");
                    return false;
                }

                for (BluetoothDevice d : pairedDevices) {
                    String name = d.getName();
                    if (name != null && name.toUpperCase().contains("MPT")) {
                        device = d;
                        break;
                    }
                }

                if (device == null) {
                    device = pairedDevices.iterator().next();
                }
            }

            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            outputStream = socket.getOutputStream();

            Log.d(TAG, "Printer connected successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Printer connection failed", e);
            close();
            return false;
        }
    }

    public void printBitmap(Bitmap bitmap) {

        if (outputStream == null) {
            Log.e(TAG, "OutputStream is null");
            return;
        }

        try {

            // Initialize printer
            outputStream.write(new byte[]{0x1B, 0x40});

            byte[] command = decodeBitmap(bitmap);
            outputStream.write(command);

            // Feed & Cut
            outputStream.write(new byte[]{
                    0x0A, 0x0A, 0x0A, 0x0A,
                    0x1D, 0x56, 0x42, 0x00
            });

            outputStream.flush();

        } catch (IOException e) {
            Log.e(TAG, "Print failed", e);
        }
    }

    private byte[] decodeBitmap(Bitmap bitmap) {

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int bw = (width + 7) / 8;

        byte[] data = new byte[8 + bw * height];

        data[0] = 0x1D;
        data[1] = 0x76;
        data[2] = 0x30;
        data[3] = 0x00;
        data[4] = (byte) (bw & 0xFF);
        data[5] = (byte) ((bw >> 8) & 0xFF);
        data[6] = (byte) (height & 0xFF);
        data[7] = (byte) ((height >> 8) & 0xFF);

        int k = 8;

        for (int y = 0; y < height; y++) {

            for (int xByte = 0; xByte < bw; xByte++) {

                int b = 0;

                for (int bit = 0; bit < 8; bit++) {

                    int x = xByte * 8 + bit;

                    if (x < width) {

                        int pixel = bitmap.getPixel(x, y);

                        int r = Color.red(pixel);
                        int g = Color.green(pixel);
                        int bl = Color.blue(pixel);

                        int gray = (r + g + bl) / 3;

                        if (gray < 128) {
                            b |= (1 << (7 - bit));
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

        outputStream = null;
        socket = null;
    }
}