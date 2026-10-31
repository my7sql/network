# Network — Android cellular diagnostics + hosted web dashboard

This repository contains:
- `web/`: a responsive dashboard you can publish with GitHub Pages.
- `android/`: a native Android app (Kotlin + WebView) that reads cellular information exposed by Android and connects it to the hosted dashboard through a JavaScript bridge.

## Important limitations

A normal website cannot read Android telephony APIs. The Android app is the native collector; the hosted website is the interface.

Android/Samsung may withhold some fields depending on model, modem, Android version, carrier, SIM state, permissions and location settings. This app does not bypass Android security restrictions and cannot read IMEI/IMSI.

Tower coordinates are **database estimates**, not guaranteed exact physical mast locations. The app can query OpenCelliD when you provide your own API key. The database may have no record, stale coordinates, or a location for a sector/cell that is not the precise mast. For authoritative exact locations, you need the mobile operator's infrastructure records or field verification.

## 1. Publish the website with GitHub Pages

1. Create a GitHub repository named `network`.
2. Upload the contents of this project (the `web` folder can remain as a folder).
3. In GitHub, open **Settings → Pages**.
4. Choose **Deploy from a branch**, select `main`, and set the folder to `/web`.
5. Save. Your dashboard URL will normally be:
   `https://YOUR_GITHUB_USERNAME.github.io/network/`

Alternatively, copy the contents of `web/` to the repository root and publish from `/root`.

## 2. Configure the Android app to use your hosted page

Open:

`android/app/build.gradle`

Find:

`buildConfigField "String", "DASHBOARD_URL", "\"https://YOUR_GITHUB_USERNAME.github.io/network/\""`

Replace `YOUR_GITHUB_USERNAME` with your GitHub username. Keep the URL HTTPS and make sure it is the exact GitHub Pages URL.

Then open the `android/` folder in Android Studio, allow Gradle sync, and build:
**Build → Build Bundle(s) / APK(s) → Build APK(s)**

Requirements:
- Android Studio with Android SDK 35 installed (or let Android Studio install it)
- JDK 17
- A physical Samsung Android phone is recommended for cellular diagnostics

Install the generated debug APK from:
`android/app/build/outputs/apk/debug/app-debug.apk`

The app asks for location and phone-state permissions. Grant them, enable Location on the phone, and allow the app to use location while it is open. Android may require these permissions before it returns cell information.

## 3. Open the dashboard

- Open the Android app. It loads the hosted GitHub Pages dashboard.
- The native app exposes a JavaScript bridge only when the loaded page host matches the configured GitHub Pages host.
- The dashboard automatically refreshes available cell information while the app is open.
- The same website can also be opened in an ordinary browser. In a normal browser it shows browser/network diagnostics, but it cannot access native cell IDs or radio measurements.

## 4. Tower lookup (OpenCelliD)

1. Create an account and obtain an API key from OpenCelliD: https://opencellid.org/
2. In the dashboard, open **Tower lookup** and enter your API key.
3. Select **Look up serving cell**. The Android app sends the serving cell's radio type, MCC, MNC, TAC/LAC and Cell ID to OpenCelliD.
4. The dashboard displays returned coordinates and any returned accuracy/range metadata.

The key is kept in the browser's local storage on that device. Treat it as a secret. Do not publish it in source code or share screenshots containing it. For a public multi-user deployment, use a backend proxy with rate limiting and secret storage instead of asking every visitor to enter a key into the page.

OpenCelliD may return no result, especially for new/rare cells or 5G cells. It is a crowdsourced database, not an official operator tower registry.

## 5. What is collected

Depending on Android support, the app attempts to read:
- Network operator name and MCC/MNC
- Radio technology / data network type
- Registered/serving cell and neighboring cells
- LTE: CI, TAC, PCI, EARFCN, RSRP, RSRQ, RSSI, RSSNR (when exposed)
- NR/5G: NCI, TAC, PCI, NRARFCN, SS-RSRP, SS-RSRQ, SS-SINR (when exposed)
- WCDMA/GSM: CID, LAC, PSC/BSIC, UARFCN/ARFCN and signal strength (when exposed)

Signal values and identifiers can be unavailable or reported as platform sentinel values. The dashboard labels unavailable values instead of fabricating them.

## Privacy and safety

- Cellular identifiers and signal measurements are sensitive location-related data. Keep the dashboard private if you do not want to disclose your movements or network environment.
- This project does not upload cellular data to your own server. Tower lookup sends the selected serving-cell identifiers to OpenCelliD when you explicitly request a lookup.
- No IMEI, IMSI, phone number, SMS, call logs, contacts or precise GPS coordinates are collected.
- No root access is required.
