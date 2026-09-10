package za.co.floatpos.printer;

import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.WebViewListener;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.mobiiot.sdk.MobiiotAPI;
import com.mobiiot.sdk.printer.CsPrinter;
import com.mobiiot.sdk.utils.PrinterServiceUtil;
import com.mobiiot.sdk.utils.ServiceUtilIOPrint;

/**
 * FloatPOS — built-in thermal printer (MobiPrint 5 / MobiIoT SDK 4.1.1).
 *
 * Two system services have to be bound before anything reaches paper:
 *   SageReal  sagereal.intent.action.START_PRINTER_SERVICE_AIDL  (text/status)
 *   PrintIO   sagereal.intent.action.CONN_PRINTIO_SERVICE_AIDL   (raw ESC/POS)
 *
 * MobiiotAPI.init() starts those binds and returns immediately. Printing on
 * the first receipt therefore hits a null AIDL stub: CsPrinter logs
 * "service printer is KO", getPaperStatus() is false (which we used to
 * surface as "out of paper"), and printESCPOS() is a silent no-op that
 * still looked like success to the web layer.
 *
 * Wait for the bind on this plugin thread (not the main thread) so
 * onServiceConnected can run.
 *
 * The live till (floatpos.co.za) never calls this plugin — it only
 * tries Web Bluetooth then iframe window.print(). After the page
 * loads we wrap window._btTryPrint so the same ESC/POS bytes go
 * through printESCPOS() on the built-in head.
 */
@CapacitorPlugin(name = "FloatPrinter")
public class FloatPrinterPlugin extends Plugin {

    private static final String TAG = "FloatPrinter";
    private static final long BIND_TIMEOUT_MS = 8_000;

    private boolean sdkLoaded = false;
    private String initError = null;

    @Override
    public void load() {
        try {
            Class.forName("com.mobiiot.sdk.MobiiotAPI");
            sdkLoaded = true;
            bindSdk();
        } catch (Throwable t) {
            sdkLoaded = false;
            initError = "MobiIoT SDK missing from APK (" + t.getClass().getSimpleName() + ")";
            Log.e(TAG, initError, t);
        }
        installPrintHook();
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        ensureBound();
        JSObject r = new JSObject();
        boolean ready = sdkLoaded && printIoBound();
        r.put("available", ready);
        r.put("sdkLoaded", sdkLoaded);
        r.put("sageRealBound", sageRealBound());
        r.put("printIoBound", printIoBound());
        if (initError != null) r.put("error", initError);
        call.resolve(r);
    }

    @PluginMethod
    public void status(PluginCall call) {
        ensureBound();
        JSObject r = new JSObject();
        if (!sdkLoaded) {
            r.put("available", false);
            if (initError != null) r.put("error", initError);
            call.resolve(r);
            return;
        }
        try {
            boolean sage = sageRealBound();
            Boolean power = sage ? CsPrinter.getPowerState() : null;
            r.put("available", printIoBound() || sage);
            r.put("sageRealBound", sage);
            r.put("printIoBound", printIoBound());
            if (sage) {
                r.put("printer", CsPrinter.getPrinterStatus());
                r.put("paperOk", CsPrinter.getPaperStatus());
                r.put("tempOk", CsPrinter.getTempStatus());
                r.put("voltage", CsPrinter.getCurrentVoltageStatus());
                r.put("powerOk", power != null && power);
                r.put("lastError", CsPrinter.getLastError());
            } else {
                r.put("error", "SageReal printer service not connected yet");
            }
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("status failed: " + t.getMessage());
        }
    }

    /**
     * Print raw ESC/POS. `data` is base64 of the bytes from EscPos.build().
     * Goes through PrintIO (transmitNew), not SageReal printText.
     */
    @PluginMethod
    public void print(PluginCall call) {
        if (!sdkLoaded) {
            call.reject("no built-in printer on this device"
                    + (initError != null ? (" (" + initError + ")") : ""));
            return;
        }
        String b64 = call.getString("data");
        if (b64 == null || b64.length() == 0) {
            call.reject("no data");
            return;
        }
        try {
            if (!waitForPrintIo()) {
                call.reject("printer raw I/O service not connected (PrintIO). "
                        + "Check com.sagereal.printer is installed and the APK <queries> include "
                        + "sagereal.intent.action.CONN_PRINTIO_SERVICE_AIDL");
                return;
            }
            if (sageRealBound() && !CsPrinter.getPaperStatus()) {
                call.reject("out of paper");
                return;
            }
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            CsPrinter.printESCPOS(bytes, bytes.length);
            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("bytes", bytes.length);
            r.put("lastError", sageRealBound() ? CsPrinter.getLastError() : 0);
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("print failed: " + t.getMessage());
        }
    }

    @PluginMethod
    public void printText(PluginCall call) {
        if (!sdkLoaded) {
            call.reject("no built-in printer on this device");
            return;
        }
        String text = call.getString("text", "");
        try {
            if (!waitForSageReal()) {
                call.reject("SageReal printer service not connected");
                return;
            }
            boolean ok = CsPrinter.printText(text);
            CsPrinter.printEndLine();
            if (!ok) {
                call.reject("printText returned false (service printer is KO)");
                return;
            }
            JSObject r = new JSObject();
            r.put("ok", true);
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("printText failed: " + t.getMessage());
        }
    }

    @PluginMethod
    public void feed(PluginCall call) {
        if (!sdkLoaded) {
            call.reject("no built-in printer on this device");
            return;
        }
        try {
            if (!waitForSageReal()) {
                call.reject("SageReal printer service not connected");
                return;
            }
            CsPrinter.printEndLine();
            JSObject ok = new JSObject();
            ok.put("ok", true);
            call.resolve(ok);
        } catch (Throwable t) {
            call.reject("feed failed: " + t.getMessage());
        }
    }

    @PluginMethod
    public void setDarkness(PluginCall call) {
        if (!sdkLoaded) {
            call.reject("no built-in printer on this device");
            return;
        }
        try {
            if (!waitForSageReal()) {
                call.reject("SageReal printer service not connected");
                return;
            }
            CsPrinter.printSetDarkness(call.getInt("level", 3));
            JSObject ok = new JSObject();
            ok.put("ok", true);
            call.resolve(ok);
        } catch (Throwable t) {
            call.reject("setDarkness failed: " + t.getMessage());
        }
    }

    private void bindSdk() {
        try {
            MobiiotAPI.init(getContext());
            initError = null;
        } catch (Throwable t) {
            initError = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? (": " + t.getMessage()) : "");
            Log.e(TAG, "MobiiotAPI.init failed: " + initError, t);
        }
    }

    private void ensureBound() {
        if (!sdkLoaded) return;
        if (printIoBound() && sageRealBound()) return;
        bindSdk();
        waitForPrintIo();
    }

    private boolean waitForPrintIo() {
        waitUntil(BIND_TIMEOUT_MS, this::printIoBound);
        if (!printIoBound()) {
            bindSdk();
            waitUntil(2_000, this::printIoBound);
        }
        return printIoBound();
    }

    private boolean waitForSageReal() {
        waitUntil(BIND_TIMEOUT_MS, this::sageRealBound);
        if (!sageRealBound()) {
            bindSdk();
            waitUntil(2_000, this::sageRealBound);
        }
        return sageRealBound();
    }

    private boolean sageRealBound() {
        try {
            return PrinterServiceUtil.getPrinterService() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean printIoBound() {
        try {
            return ServiceUtilIOPrint.getiMyAidlInterface() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void waitUntil(long timeoutMs, Ready check) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            try {
                Thread.sleep(150);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private interface Ready {
        boolean ok();
    }

    /**
     * The hosted till never imports FloatPrinter. Wrap its global
     * {@code _btTryPrint} after each page load so receipts, job cards,
     * laybys and work orders hit PrintIO instead of Bluetooth / HTML.
     */
    private void installPrintHook() {
        try {
            getBridge().addWebViewListener(new WebViewListener() {
                @Override
                public void onPageLoaded(WebView webView) {
                    webView.post(() -> webView.evaluateJavascript(HOOK_JS, null));
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "could not install print hook", t);
        }
    }

    // Token __floatPrinterHook is grepped by CI to prove this JS shipped.
    private static final String HOOK_JS = """
        (function(){
          if (window.__floatPrinterHookPending) return;
          window.__floatPrinterHookPending = true;
          try { if (!localStorage.getItem('ld_bt_width')) localStorage.setItem('ld_bt_width','58'); } catch (e) {}
          function b64(u8){
            var s = '', i, c = 0x8000;
            u8 = u8 instanceof Uint8Array ? u8 : new Uint8Array(u8);
            for (i = 0; i < u8.length; i += c) s += String.fromCharCode.apply(null, u8.subarray(i, i + c));
            return btoa(s);
          }
          function plugin(){
            try { return window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.FloatPrinter; }
            catch (e) { return null; }
          }
          async function send(builder){
            var fp = plugin();
            if (!fp || typeof fp.print !== 'function') return false;
            var avail = await fp.isAvailable();
            if (!avail || !avail.available) return false;
            var raw = builder && typeof builder.build === 'function' ? builder.build() : builder;
            if (!(raw instanceof Uint8Array) || !raw.length) return false;
            var r = await fp.print({ data: b64(raw) });
            return !!(r && r.ok);
          }
          function wrap(){
            var orig = window._btTryPrint;
            if (typeof orig !== 'function' || orig.__fpWrapped) return typeof orig === 'function';
            var wrapped = async function(builder){
              try { if (await send(builder)) return true; }
              catch (e) { console.warn('[FloatPrinter] hook', e && e.message); }
              try { return await orig.apply(this, arguments); }
              catch (e) { return false; }
            };
            wrapped.__fpWrapped = true;
            window._btTryPrint = wrapped;
            console.log('[FloatPrinter] hooked _btTryPrint');
            return true;
          }
          if (wrap()) return;
          var n = 0;
          var t = setInterval(function(){
            n += 1;
            if (wrap() || n > 80) clearInterval(t);
          }, 250);
        })();
        """;
}
