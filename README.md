# FloatPOS — Android shell

A thin Capacitor wrapper that turns the live FloatPOS web app into an
installable Android app. It does **not** contain a copy of FloatPOS — the app
loads **https://floatpos.co.za** in a native WebView, so every normal FloatPOS
deploy (commit -> Netlify) updates the Android app automatically too. The
build-gate still governs every device.

- **App name:** FloatPOS
- **Package id:** za.co.floatpos.app
- **Loads:** https://floatpos.co.za
- **Capacitor:** 8.x  ·  builds on GitHub (no Android Studio needed)

---

## Build the APK (all in the browser — your Mac does nothing)

**1. Create the repo**
- GitHub -> New repository -> name it `floatpos-android` -> **Private** -> Create.

**2. Upload these files**
- In the new repo: **Add file -> Upload files**.
- Drag in everything from this folder, keeping the structure:
  `capacitor.config.json`, `package.json`, `.gitignore`, `README.md`,
  the `assets/` folder, the `www/` folder, and the `.github/` folder.
- If the `.github` folder won't drag (some browsers hide dot-folders), create it
  by hand: **Add file -> Create new file**, type the name
  `.github/workflows/build-apk.yml`, and paste the contents of that file.
- **Commit** straight to `main`.

**3. The build runs itself**
- Go to the **Actions** tab. A run called **Build FloatPOS APK** starts on the
  commit. First run takes ~4-6 min (it installs the toolchain in the cloud).
- Green tick = done. (Red = open the run, read the failed step, send it to me.)

**4. Download the APK**
- Open the finished run -> scroll to **Artifacts** -> download
  **FloatPOS-debug-apk**.
- Unzip it -> you get `app-debug.apk`.

**5. Install on an Android device** (MatePad, phone, later a MobiPrint)
- Copy `app-debug.apk` onto the device.
- Settings may ask to allow **"install unknown apps"** for your file manager —
  allow it.
- Tap the APK -> Install -> open **FloatPOS**. It should boot straight into the
  live till.

---

## Re-running the build later
- **Actions -> Build FloatPOS APK -> Run workflow** (the `workflow_dispatch`
  button) rebuilds any time, or just push any change.

## When do I need to rebuild the APK?
- **Almost never.** Day-to-day FloatPOS changes ship the normal way (Netlify) and
  the app picks them up on next load — no new APK.
- Only rebuild for: app **name**, **icon/splash**, the **URL** it loads, or when
  we add a native feature (e.g. Phase 2 camera scanning).

## What's next (Phase 2)
- Camera barcode scanning, wired to the existing `handleScannedBarcode()` so a
  scan behaves exactly like the manual barcode field. That phase adds one
  Capacitor plugin here + a small dormant hook in FloatPOS.

## Notes
- This produces a **debug** APK (fine for sideloading + piloting). A signed
  **release** APK (for Play Store or wide distribution) is a later step — needs a
  signing key, which we'll set up when you're ready.
- First launch needs internet (to cache the app). After that the FloatPOS
  service worker handles offline trading as usual.
