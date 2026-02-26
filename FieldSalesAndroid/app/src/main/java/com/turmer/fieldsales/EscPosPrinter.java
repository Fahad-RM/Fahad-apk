package com.turmer.fieldsales;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

public class EscPosPrinter {

    private static final String TAG = "EscPosPrinter";
    // Standard SPP (Serial Port Profile) UUID — works for ALL ESC/POS Bluetooth printers
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private final Context context;

    public EscPosPrinter(Context context) {
        this.context = context;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CONNECT — works with ANY paired Bluetooth ESC/POS printer (3-inch or 4-inch)
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    public boolean connect() {

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device");
            return false;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is disabled");
            return false;
        }

        SharedPreferences prefs =
                context.getSharedPreferences("FieldSalesPrefs", Context.MODE_PRIVATE);
        String savedMac = prefs.getString("selected_printer_mac", "");

        BluetoothDevice device = null;

        if (!savedMac.isEmpty()) {
            // Use saved printer MAC — direct connection
            try {
                device = bluetoothAdapter.getRemoteDevice(savedMac);
                Log.d(TAG, "Using saved printer MAC: " + savedMac);
            } catch (Exception e) {
                Log.w(TAG, "Saved MAC invalid, falling back to first paired device");
                device = null;
            }
        }

        // If no saved MAC or invalid, try the first available paired device
        if (device == null) {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices == null || pairedDevices.isEmpty()) {
                Log.e(TAG, "No paired Bluetooth devices found");
                return false;
            }
            // Pick the first paired device — no name filter (works for any brand)
            device = pairedDevices.iterator().next();
            Log.d(TAG, "No saved MAC — using first paired device: " + device.getName());
        }

        return connectToDevice(device);
    }

    @SuppressLint("MissingPermission")
    private boolean connectToDevice(BluetoothDevice device) {
        // Cancel discovery to avoid slowing down connection
        bluetoothAdapter.cancelDiscovery();

        // ── Attempt 1: Normal RFCOMM via SPP UUID ──
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            outputStream = socket.getOutputStream();
            Log.d(TAG, "Connected via standard RFCOMM to: " + device.getName());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Standard RFCOMM failed (" + e.getMessage() + "). Trying secure fallback...");
            closeSocket();
        }

        // ── Attempt 2: createInsecureRfcommSocketToServiceRecord (some printers need this) ──
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            outputStream = socket.getOutputStream();
            Log.d(TAG, "Connected via insecure RFCOMM to: " + device.getName());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Insecure RFCOMM failed (" + e.getMessage() + "). Trying reflection fallback...");
            closeSocket();
        }

        // ── Attempt 3: Reflection-based socket (fixes channel-mismatch on older printers) ──
        try {
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            socket = (BluetoothSocket) m.invoke(device, 1); // channel 1
            socket.connect();
            outputStream = socket.getOutputStream();
            Log.d(TAG, "Connected via reflection channel-1 to: " + device.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "All connection attempts failed: " + e.getMessage());
            closeSocket();
            return false;
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        socket = null;
        outputStream = null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PRINT BITMAP — universal ESC/POS raster bitmap command (GS v 0)
    // Works on HPRT, Epson, Star, Bixolon, Rongta, Xprinter, and generic printers
    // ──────────────────────────────────────────────────────────────────────────

    public void printBitmap(Bitmap bitmap) {

        if (outputStream == null) {
            Log.e(TAG, "Not connected — outputStream is null");
            return;
        }

        try {
            // ESC @ — Initialize printer
            outputStream.write(new byte[]{0x1B, 0x40});
            outputStream.flush();

            // ESC a 1 — Center align
            outputStream.write(new byte[]{0x1B, 0x61, 0x01});
            outputStream.flush();

            // ESC 3 0 — Set line spacing to 0 for seamless band printing
            outputStream.write(new byte[]{0x1B, 0x33, 0x00});
            outputStream.flush();

            int imgWidth = bitmap.getWidth();
            int imgHeight = bitmap.getHeight();
            
            // ── BANDED PRINTING — critical for Bixolon and mobile BT printers ──
            // Mobile printers have very small image buffers. A single GS v 0 command
            // with a height > 255 or > 512 often causes the printer to hang (blue light flashes
            // but nothing prints). We slice the image into 200-pixel tall bands.
            final int BAND_HEIGHT = 200; 

            for (int y = 0; y < imgHeight; y += BAND_HEIGHT) {
                int currentBandHeight = Math.min(BAND_HEIGHT, imgHeight - y);
                Bitmap band = Bitmap.createBitmap(bitmap, 0, y, imgWidth, currentBandHeight);
                
                // Encode this band to ESC/POS raster format
                byte[] bandData = encodeBitmapToRaster(band);
                band.recycle();

                // ── CHUNKED WRITE — prevent Bluetooth transport buffer overflow ──
                final int CHUNK_SIZE = 512;
                int offset = 0;
                while (offset < bandData.length) {
                    int end = Math.min(offset + CHUNK_SIZE, bandData.length);
                    outputStream.write(bandData, offset, end - offset);
                    outputStream.flush();
                    offset = end;
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                }
            }

            // ESC 2 — Reset line spacing to default
            outputStream.write(new byte[]{0x1B, 0x32});
            outputStream.flush();

            // Feed 3 lines then full cut — ~3 character lines of clearance for cutter
            // (Note: portable printers will simply ignore the cut command)
            outputStream.write(new byte[]{
                    0x0A, 0x0A, 0x0A,          // 3× line feed
                    0x1D, 0x56, 0x42, 0x00      // Full cut
            });
            outputStream.flush();

            Log.d(TAG, "Print job sent successfully. Total height: " + imgHeight);

        } catch (IOException e) {
            Log.e(TAG, "Failed to send print data", e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ESC/POS Raster Bitmap Encoder
    // GS v 0 m xL xH yL yH d1…dk
    //   m = 0 (normal density), xL/xH = bytes per row, yL/yH = rows
    // ──────────────────────────────────────────────────────────────────────────

    private byte[] encodeBitmapToRaster(Bitmap bitmap) {

        int width  = bitmap.getWidth();
        int height = bitmap.getHeight();

        // bytes per raster row (round up to byte boundary)
        int bytesPerRow = (width + 7) / 8;

        // Header: GS v 0 + 6 bytes header + pixel data
        int dataLen = 8 + bytesPerRow * height;
        byte[] data = new byte[dataLen];

        // GS v 0 command header
        data[0] = 0x1D;                          // GS
        data[1] = 0x76;                          // v
        data[2] = 0x30;                          // 0
        data[3] = 0x00;                          // m = normal
        data[4] = (byte) (bytesPerRow & 0xFF);   // xL
        data[5] = (byte) (bytesPerRow >> 8);     // xH
        data[6] = (byte) (height & 0xFF);        // yL
        data[7] = (byte) (height >> 8);          // yH

        // Pixel data: each bit = 1 if pixel is dark (<128 luminance)
        int k = 8;
        for (int y = 0; y < height; y++) {
            for (int xByte = 0; xByte < bytesPerRow; xByte++) {
                int b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = xByte * 8 + bit;
                    if (x < width) {
                        int pixel = bitmap.getPixel(x, y);
                        int r = Color.red(pixel);
                        int g = Color.green(pixel);
                        int bl = Color.blue(pixel);
                        // Luminance: dark pixel → print dot
                        int gray = (77 * r + 150 * g + 29 * bl) >> 8; // perceptual weighting
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

    // ──────────────────────────────────────────────────────────────────────────
    // CLOSE
    // ──────────────────────────────────────────────────────────────────────────

    public void close() {
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {}
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        outputStream = null;
        socket = null;
    }
}