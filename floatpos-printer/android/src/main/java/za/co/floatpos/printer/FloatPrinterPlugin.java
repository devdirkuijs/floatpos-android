package za.co.floatpos.printer;

import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.mobiiot.sdk.MobiiotAPI;
import com.mobiiot.sdk.printer.CsPrinter;

/**
 * FloatPOS -- built-in thermal printer (MobiPrint 5 / MobiIoT SDK 4.1.1).
 *
 * The MP5's print head is wired to the board and driven by the MobiIoT SDK
 * in-process. It is not a Bluetooth peripheral and it is not registered with
 * Android's print framework, so neither window.print() nor Float's Web
 * Bluetooth path can reach it. The web layer hands the bytes to native code,
 * which is all this does.
 *
 * It deliberately does NOT format receipts. Float already has a complete
 * ESC/POS encoder written for the K329, including 58mm at 384 dots.
 * CsPrinter.printESCPOS() accepts raw ESC/POS bytes, so that encoder feeds
 * this directly. A second formatter here would be two calculators for the
 * same receipt, and they would drift.
 *
 * Bytes cross the bridge base64-encoded: Capacitor marshals JS values as
 * JSON, and a raw byte array would arrive mangled by UTF-8 handling.
 */
@CapacitorPlugin(name = "FloatPrinter")
public class FloatPrinterPlugin extends Plugin {

    private boolean sdkReady = false;
    private String  initError = null;

    @Override
    public void load() {
        // MobiiotAPI.init() binds the printer service. It throws on any device
        // that is not MobiIoT hardware -- every phone, tablet and desktop in
        // the fleet -- so the failure is caught and remembered rather than
        // crashing the app on boot. isAvailable() then reports it honestly and
        // FloatPOS falls back to the browser print dialog.
        try {
            MobiiotAPI.init(getContext());
            sdkReady = true;
        } catch (Throwable t) {
            sdkReady = false;
            initError = t.getClass().getSimpleName()
                      + (t.getMessage() != null ? (": " + t.getMessage()) : "");
        }
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject r = new JSObject();
        r.put("available", sdkReady);
        if (initError != null) r.put("error", initError);
        call.resolve(r);
    }

    /**
     * Paper, temperature, voltage and power as the SDK reports them. Float
     * uses this to say "out of paper" instead of failing silently -- a till
     * that prints nothing and says nothing is what a shop blames on the
     * software.
     */
    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        if (!sdkReady) {
            r.put("available", false);
            if (initError != null) r.put("error", initError);
            call.resolve(r);
            return;
        }
        try {
            Boolean power = CsPrinter.getPowerState();
            r.put("available",   true);
            r.put("printer",     CsPrinter.getPrinterStatus());
            r.put("paperOk",     CsPrinter.getPaperStatus());
            r.put("tempOk",      CsPrinter.getTempStatus());
            r.put("voltage",     CsPrinter.getCurrentVoltageStatus());
            r.put("powerOk",     power != null && power);
            r.put("lastError",   CsPrinter.getLastError());
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("status failed: " + t.getMessage());
        }
    }

    /**
     * Print raw ESC/POS. `data` is base64 of the bytes from EscPos.build().
     *
     * Paper is checked BEFORE printing: the SDK will accept bytes with no
     * paper loaded and report nothing, and a cashier would hand over a
     * receipt that never existed.
     */
    @PluginMethod
    public void print(PluginCall call) {
        if (!sdkReady) {
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
            if (!CsPrinter.getPaperStatus()) {
                call.reject("out of paper");
                return;
            }
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            CsPrinter.printESCPOS(bytes, bytes.length);

            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("bytes", bytes.length);
            r.put("lastError", CsPrinter.getLastError());
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("print failed: " + t.getMessage());
        }
    }

    /**
     * Plain text, for a self-test that does not depend on Float's encoder
     * being right. If this prints and print() does not, the fault is in the
     * ESC/POS bytes, not the hardware.
     */
    @PluginMethod
    public void printText(PluginCall call) {
        if (!sdkReady) { call.reject("no built-in printer on this device"); return; }
        String text = call.getString("text", "");
        try {
            boolean ok = CsPrinter.printText(text);
            CsPrinter.printEndLine();
            JSObject r = new JSObject();
            r.put("ok", ok);
            call.resolve(r);
        } catch (Throwable t) {
            call.reject("printText failed: " + t.getMessage());
        }
    }

    /** Feed past the tear bar so the slip can be torn off cleanly. */
    @PluginMethod
    public void feed(PluginCall call) {
        if (!sdkReady) { call.reject("no built-in printer on this device"); return; }
        try {
            CsPrinter.printEndLine();
            JSObject ok = new JSObject();
            ok.put("ok", true);
            call.resolve(ok);
        } catch (Throwable t) {
            call.reject("feed failed: " + t.getMessage());
        }
    }

    /** 1-5 or so, device dependent. Darker costs battery and head life. */
    @PluginMethod
    public void setDarkness(PluginCall call) {
        if (!sdkReady) { call.reject("no built-in printer on this device"); return; }
        try {
            CsPrinter.printSetDarkness(call.getInt("level", 3));
            JSObject ok = new JSObject();
            ok.put("ok", true);
            call.resolve(ok);
        } catch (Throwable t) {
            call.reject("setDarkness failed: " + t.getMessage());
        }
    }
}
