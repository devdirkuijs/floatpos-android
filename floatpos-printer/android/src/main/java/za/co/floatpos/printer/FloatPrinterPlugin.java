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
 * FloatPOS — built-in thermal printer (MobiPrint 5 / MobiIoT SDK 4.1.1).
 *
 * WHY THIS EXISTS
 * The MP5's print head is wired to the board and driven by the MobiIoT SDK
 * in-process. It is not a Bluetooth peripheral and it is not registered with
 * Android's print framework, so neither window.print() nor Float's existing
 * Web Bluetooth path can reach it — there is nothing for them to find. The web
 * layer therefore has to hand the bytes to native code, which is all this does.
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 * It does not format receipts. Float already has a complete ESC/POS encoder
 * (EscPos in index.html) written for the K329, including 58mm support at 384
 * dots. CsPrinter.printESCPOS() accepts raw ESC/POS bytes, so that encoder
 * feeds this directly. Building a second formatter here would be two
 * calculators for the same receipt, and they would drift.
 *
 * Bytes cross the bridge base64-encoded: Capacitor marshals JS values as JSON,
 * and a raw byte array would arrive mangled by UTF-8 handling. Base64 is the
 * only lossless option and costs ~33% on a payload measured in kilobytes.
 */
@CapacitorPlugin(name = "FloatPrinter")
public class FloatPrinterPlugin extends Plugin {

    private boolean sdkReady = false;
    private String  initError = null;

    @Override
    public void load() {
        // MobiiotAPI.init() binds the printer service. It throws on any device
        // that is not MobiIoT hardware, which is every phone, tablet and
        // desktop in the fleet — so the failure is caught and remembered
        // rather than crashing the app on boot. isAvailable() then reports it
        // honestly and Float falls back to the browser print dialog.
        try {
            MobiiotAPI.init(getContext());
            sdkReady = true;
        } catch (Throwable t) {
            sdkReady = false;
            initError = t.getClass().getSimpleName()
                      + (t.getMessage() != null ? (": " + t.getMessage()) : "");
        }
    }

    /**
     * Is there a built-in printer on this device? Float calls this once and
     * routes receipts accordingly. A device without one is not an error.
     */
    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject r = new JSObject();
        r.put("available", sdkReady);
        if (initError != null) r.put("error", initError);
        call.resolve(r);
    }

    /**
     * Paper, temperature, voltage and power, as the SDK reports them.
     * Float uses this to say "the printer is out of paper" instead of failing
     * silently — a till that prints nothing and says nothing is the thing a
     * shop blames on the software.
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
            call.reject("status failed: " + t.getMessage(), t);
        }
    }

    /**
     * Print raw ESC/POS. `data` is base64 of the bytes from EscPos.build().
     *
     * printESCPOS returns void, so success is inferred from it not throwing
     * and from the paper check below. That is why the paper state is read
     * BEFORE printing: the SDK will happily accept bytes with no paper loaded
     * and report nothing, and a cashier would hand over a receipt that never
     * existed.
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
            call.reject("print failed: " + t.getMessage(), t);
        }
    }

    /**
     * Plain text, for a self-test that does not depend on Float's encoder
     * being right. If this prints and print() does not, the fault is in the
     * ESC/POS bytes, not the hardware — worth being able to tell apart.
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
            call.reject("printText failed: " + t.getMessage(), t);
        }
    }

    /** Feed past the tear bar so the slip can be torn off cleanly. */
    @PluginMethod
    public void feed(PluginCall call) {
        if (!sdkReady) { call.reject("no built-in printer on this device"); return; }
        try {
            CsPrinter.printEndLine();
            call.resolve(new JSObject().put("ok", true));
        } catch (Throwable t) {
            call.reject("feed failed: " + t.getMessage(), t);
        }
    }

    /** 1–5 or so, device dependent. Darker costs battery and head life. */
    @PluginMethod
    public void setDarkness(PluginCall call) {
        if (!sdkReady) { call.reject("no built-in printer on this device"); return; }
        try {
            CsPrinter.printSetDarkness(call.getInt("level", 3));
            call.resolve(new JSObject().put("ok", true));
        } catch (Throwable t) {
            call.reject("setDarkness failed: " + t.getMessage(), t);
        }
    }
}
