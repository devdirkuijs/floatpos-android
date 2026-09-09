# FloatPOS — Android shell

A thin Capacitor wrapper that turns the live FloatPOS web app into an
installable Android app. It does **not** contain a copy of FloatPOS — the app
loads **https://floatpos.co.za** in a native WebView, so every normal FloatPOS
deploy (commit → Netlify) updates the Android app automatically too.

- **App name:** FloatPOS
- **Package id:** `za.co.floatpos.app`
- **Loads:** https://floatpos.co.za
- **Capacitor:** 8.x · builds on GitHub
- **Built-in printer:** MobiPrint 5 / MobiIoT SDK 4.1.1 (`floatpos-printer`)

---

## Why the MobiPrint 5 was silent

The 58mm head is **not** Bluetooth and **not** `window.print()`. FloatPOS already
had a Capacitor plugin (`FloatPrinterPlugin`) that calls `CsPrinter.printESCPOS()`.
Three bugs in that path meant the till never moved paper:

1. **SDK not in the APK.** The workflow copied `mp-mobiiot-sdk-4.1.1.aar` into
   `android/app/libs`, but Capacitor’s app Gradle only includes `*.jar`.
   `FloatPrinterPlugin` compiled (`compileOnly`) and the CI check passed because
   it only searched for that class. On the device `MobiiotAPI.init()` threw
   `ClassNotFoundException`, `isAvailable()` was false, and the web app fell
   back to the browser print dialog.
2. **Wrong `<queries>` actions** on Android 14. The plugin declared the AIDL
   *interface* names (`com.sagereal.printer.PrinterInterface`) instead of the
   real bind actions (`sagereal.intent.action.START_PRINTER_SERVICE_AIDL` and
   `sagereal.intent.action.CONN_PRINTIO_SERVICE_AIDL`). PrintIO’s bind then
   resolved 0 services, `MobiiotAPI.init()` could NPE, and ESC/POS went nowhere.
3. **Print before bind.** `MobiiotAPI.init()` is asynchronous. The first receipt
   hit a null stub: the SDK logged `service printer is KO`, `getPaperStatus()`
   returned false (surfaced as “out of paper”), and `printESCPOS()` no-op’d
   while still returning `{ ok: true }`.

Receipts use **PrintIO** (`transmitNew`), not SageReal `printText`. A successful
`printText` self-test does not prove ESC/POS receipts will print.

---

## Build the APK

Push to `main` on **github.com/devdirkuijs/floatpos-android** (or run
**Actions → Build FloatPOS APK → Run workflow**). Download the
**FloatPOS-release-apk** artifact and sideload `floatpos.apk` onto the
MobiPrint 5.

The CI verify step now fails the build if `MobiiotAPI` / `printESCPOS` are
missing from the APK, not only `FloatPrinterPlugin`.

---

## When do I need to rebuild the APK?

- Day-to-day FloatPOS UI changes ship via Netlify. No new APK.
- Rebuild for: app name, icon/splash, the URL it loads, or native plugins
  (printer, camera).
