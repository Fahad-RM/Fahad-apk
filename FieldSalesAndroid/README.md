# Field Sales Android App - Bluetooth Printing Wrapper

## Overview
This is a lightweight Android WebView wrapper that loads the Turmer Field Sales Odoo app and provides **native Bluetooth printing** to the Honeywell CT32 thermal printer via a JavaScript bridge.

## How It Works
1. The app loads your Odoo Field Sales URL in a full-screen WebView
2. It injects `window.AndroidPrint` into the JavaScript context
3. The Odoo app detects this bridge and uses it for direct Bluetooth printing
4. No third-party apps required!

## Setup Before Building

### ✅ No Hardcoded URL Needed!
The Odoo URL is configured **from within the app** — no need to edit any code.

On first launch, the app will automatically show the **Settings screen** where you enter your Odoo URL.

You can also open Settings anytime by pressing the **Back button** when on the main screen.

## Building the APK

### Requirements
- Android Studio (latest version)
- JDK 11 or higher

### Steps
1. Open Android Studio
2. Click **File → Open** and select this `FieldSalesAndroid` folder
3. Wait for Gradle sync to complete
4. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. APK will be in: `app/build/outputs/apk/debug/app-debug.apk`

## Installing on Samsung Mobile

### Method 1: USB Transfer
1. Enable **Developer Options** on Samsung: Settings → About Phone → tap Build Number 7 times
2. Enable **USB Debugging**: Settings → Developer Options → USB Debugging
3. Connect Samsung to PC via USB
4. Copy `app-debug.apk` to the phone's Downloads folder
5. On the phone: Files → Downloads → tap the APK → Install

### Method 2: Direct Download
1. Upload the APK to Google Drive or any cloud storage
2. Download it on the Samsung phone
3. Tap to install (allow "Install from Unknown Sources" when prompted)

## First-Time Setup on Samsung

1. **Pair the Honeywell CT32 printer**:
   - Go to Samsung Settings → Connections → Bluetooth
   - Turn on the Honeywell CT32 printer
   - Tap "Scan" and pair with the printer

2. **Open the Field Sales app**
3. **Tap the Printer icon** in the header
4. **Select "Honeywell CT32"** from the list
5. The printer icon turns **green** ✅ - you're connected!

## JavaScript Bridge API
The following methods are available via `window.AndroidPrint`:

| Method | Returns | Description |
|--------|---------|-------------|
| `listPairedDevices()` | JSON string | List of paired BT devices |
| `connect(address)` | "OK:name" or "ERROR:msg" | Connect to printer |
| `print(base64Data)` | "OK" or "ERROR:msg" | Print ESC/POS data |
| `disconnect()` | "OK" | Disconnect printer |
| `isConnected()` | "true" or "false" | Check connection |
| `getConnectedDevice()` | device name | Name of connected device |
| `isAndroidApp()` | "true" | Detect Android wrapper |

## Project Structure
```
FieldSalesAndroid/
├── app/
│   ├── src/main/
│   │   ├── java/com/turmer/fieldsales/
│   │   │   ├── MainActivity.java        ← Main WebView activity
│   │   │   └── BluetoothPrintBridge.java ← JS bridge for Bluetooth
│   │   ├── res/values/
│   │   │   ├── strings.xml
│   │   │   └── themes.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
└── settings.gradle
```
