package com.npcl.com.vcpopdl;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * MeterReadingSDK — zero-UI DLMS optical meter reading library.
 *
 * Usage:
 *   MeterReadingSDK sdk = new MeterReadingSDK(context);
 *   sdk.startReading(MeterMake.SECURE, ReadingMode.COMPLETE, "MyFile", callback);
 *   sdk.abort();  // optional mid-read
 *
 * The TXT data file is saved to getExternalMediaDirs()[0]/&lt;fileName&gt;.TXT.
 * Callbacks arrive on the main thread.
 */
public class MeterReadingSDK {

    // =========================================================================
    // PUBLIC ENUMS
    // =========================================================================

    public enum MeterMake {
        SECURE ("Secure",     "ABCD0001"),
        GENUS  ("Genus",      "1A2B3C4D"),
        LNT    ("L&T",        "lnt1"),
        HPL    ("HPL",        "1111111111111111"),
        AVON   ("AVON",       "Hello"),
        LANDIS ("Landis+Gyr", "11111111"),
        LNG    ("L&G",        "");

        public final String displayName;
        public final String password;
        MeterMake(String d, String p) { displayName = d; password = p; }
    }

    public enum ReadingMode {
        INSTANTANEOUS("Instantaneous", "I"),
        BILLING      ("Billing",       "B"),
        COMPLETE     ("Complete",      "C");

        public final String displayName;
        public final String filePrefix;
        ReadingMode(String d, String p) { displayName = d; filePrefix = p; }
    }

    // =========================================================================
    // PUBLIC CALLBACK / RESULT
    // =========================================================================

    public interface ReadingCallback {
        /** Called from main thread during reading with status text and 0-100 progress. */
        void onProgress(String message, int progressPercent);
        /** Called from main thread when reading finishes successfully. */
        void onComplete(MeterReadingResult result);
        /** Called from main thread when reading fails. */
        void onError(String errorMessage);
    }

    public static class MeterReadingResult {
        public final String filePath;
        public final String meterNo;
        public final String manufacturer;
        public final String validationSummary;
        /** Full TXT content returned to the caller for display/parsing. */
        public final String rawData;

        public MeterReadingResult(String fp, String mn, String mf, String vs, String raw) {
            filePath = fp; meterNo = mn; manufacturer = mf;
            validationSummary = vs; rawData = (raw != null ? raw : "");
        }
        @Override public String toString() {
            return "MeterReadingResult{meterNo=" + meterNo + ", file=" + filePath + "}";
        }
    }

    // =========================================================================
    // CONSTRUCTOR / PUBLIC API
    // =========================================================================

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> currentFuture = null;

    public MeterReadingSDK(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Start reading. Only one read can run at a time.
     * @param meterMake   meter manufacturer
     * @param readingMode what data to download
     * @param fileName    output file base name (no extension); timestamp is appended
     * @param callback    receives progress / completion / error on the main thread
     */
    public void startReading(MeterMake meterMake, ReadingMode readingMode,
                              String fileName, ReadingCallback callback) {
        if (currentFuture != null && !currentFuture.isDone()) {
            fireError(callback, "A reading is already in progress. Call abort() first.");
            return;
        }
        abortRequested = false;
        consecutiveSendFailures = 0;
        lpDeadlineMs = 0;
        currentMeterMake = meterMake;
        currentFuture = executor.submit(new ReadingTask(meterMake, readingMode, fileName, callback));
    }

    /** Request the running read to stop cleanly at the next safe check-point. */
    public void abort() {
        abortRequested = true;
        lpDeadlineMs = 0;
    }

    /** True while a read is executing. */
    public boolean isReading() {
        return currentFuture != null && !currentFuture.isDone();
    }

    // =========================================================================
    // PRIVATE FIELDS — DLMS protocol state (mirrors Reading.java)
    // =========================================================================

    private volatile boolean abortRequested = false;
    private volatile long    lpDeadlineMs   = 0;

    private byte[] nPkt     = new byte[1024];
    private byte[] nRcvPkt  = new byte[1024];
    private byte   bytAddMode;
    private byte   nRecv, nRecvLast, nRecvCntr;
    private byte   nSent, nSentLast, nSentCntr;
    private int    nCounter;
    private int    intProfilePd = 15;
    private byte   nRetLSH;
    private byte[] buffer  = new byte[1024];
    private int[]  fcstab  = new int[256];
    private byte   bytTimOut = (byte) 8;
    private byte   bytTryCnt = (byte) 3;
    private final  int bytWait = 30;
    private int    pktLength;

    private int  consecutiveSendFailures = 0;
    private static final int MAX_SEND_FAILURES = 3;
    private static final int USB_WRITE_TIMEOUT_MS = 500;

    private MeterMake currentMeterMake = MeterMake.SECURE;
    private String    hplLogicalDeviceName = "";
    private int       lastGplsResult = 0;
    private boolean   rtcWarningFlag    = false;
    private String    rtcWarningMessage = "";

    // Diag log
    private static final String LOG_DIR_NAME  = "CescRaj_SDK_LOGS";
    private static final String LOG_PREFIX    = "SDK_OPTICAL_LOG_";
    private static final String LOG_SUFFIX    = ".TXT";
    private static final int    LOG_FLUSH_EVERY = 5;
    private final StringBuilder logBuffer    = new StringBuilder();
    private int    logLineCount  = 0;
    private String currentLogPath = null;

    // =========================================================================
    // READING TASK
    // =========================================================================

    private class ReadingTask implements Runnable {
        private final MeterMake   meterMake;
        private final ReadingMode readingMode;
        private final String      baseFileName;
        private final ReadingCallback callback;

        ReadingTask(MeterMake mm, ReadingMode rm, String fn, ReadingCallback cb) {
            meterMake = mm; readingMode = rm; baseFileName = fn; callback = cb;
        }

        @Override
        public void run() {
            String meterNo = "";
            String manufacturer = "";
            try {
                startDiagLog(meterMake.displayName, readingMode.displayName, 0);
                appendLog("=== SDK ReadingTask START make=" + meterMake.displayName
                        + " mode=" + readingMode.displayName + " ===");

                if (meterMake == MeterMake.LNG) {
                    fireError(callback, "L&G meters use IEC 62056-21 optical protocol which is not supported by this SDK. Use the dedicated L&G reader.");
                    return;
                }

                fireProgress(callback, "Starting — " + meterMake.displayName
                        + " | " + readingMode.displayName, 5);

                // ── Open USB port ──────────────────────────────────────────
                UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
                if (drivers == null || drivers.isEmpty()) {
                    fireError(callback, "USB cable not detected. Connect optical cable and retry.");
                    return;
                }
                UsbSerialDriver driver = drivers.get(0);
                UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
                if (connection == null) {
                    fireError(callback, "USB permission denied. Grant permission and retry.");
                    return;
                }
                UsbSerialPort port = driver.getPorts().get(0);
                port.open(connection);
                port.setParameters(9600, UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                Fcs_Tab();

                fireProgress(callback, "Port open 9600 8N1 — Initializing HDLC...", 10);
                AddressInit();

                if (currentMeterMake == MeterMake.LNT) {
                    drainPort(port);
                    AddressInit();
                    appendLog("LNT_NATIVE_HDLC");
                }

                // ── SNRM ──────────────────────────────────────────────────
                fireProgress(callback, "Sending SNRM...", 15);
                boolean nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                if (!nrmOk) {
                    android.os.SystemClock.sleep(500);
                    drainPort(port); AddressInit();
                    nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                }
                if (!nrmOk) {
                    android.os.SystemClock.sleep(1000);
                    drainPort(port); AddressInit();
                    nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                }
                if (abortRequested) { fireError(callback, "Aborted by caller."); return; }
                if (!nrmOk && currentMeterMake == MeterMake.LNT) {
                    bytAddMode = 1; AddressInit();
                    android.os.SystemClock.sleep(500);
                    nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                    if (!nrmOk) { bytAddMode = 0; AddressInit(); }
                }
                if (!nrmOk) {
                    fireError(callback, "NRM failed — check optical cable alignment on meter.");
                    return;
                }
                fireProgress(callback, "NRM OK — Sending AARQ (Authentication)...", 20);

                // ── AARQ ──────────────────────────────────────────────────
                doFakeWork();
                int aarqResult = AARQ(port, (byte) 1, meterMake.password,
                        bytWait, (byte) 3, bytTimOut);
                if (aarqResult == 1) {
                    fireError(callback, "Authentication FAILED — wrong password or meter type mismatch.");
                    return;
                } else if (aarqResult == 2) {
                    fireError(callback, "Communication error during AARQ — check cable.");
                    return;
                }
                fireProgress(callback, "Authentication OK", 25);

                // ── Read Meter No ──────────────────────────────────────────
                StringBuilder strbld = new StringBuilder();
                fireProgress(callback, "Reading Meter Number...", 28);
                StringBuilder sbMtrNo = GetParameter(port, (byte) 1, "0000600100FF",
                        (byte) 2, bytWait, bytTryCnt, bytTimOut, false, strbld);
                if (sbMtrNo != null && !sbMtrNo.toString().isEmpty()) {
                    String d = sbMtrNo.toString().replace("0A0A", "").trim();
                    meterNo = parseMeterNoFromDlms(d);
                }
                if (meterNo.isEmpty()) {
                    meterNo = "MTR_" + new SimpleDateFormat("yyyyMMdd_HHmmss",
                            Locale.US).format(new Date());
                    fireProgress(callback, "WARN: Meter No not readable — using: " + meterNo, 30);
                } else {
                    fireProgress(callback, "Meter No: " + meterNo, 30);
                }

                // ── Read Manufacturer ──────────────────────────────────────
                fireProgress(callback, "Reading Manufacturer...", 32);
                StringBuilder sbMfg = new StringBuilder();
                StringBuilder mfgData = GetParameter(port, (byte) 1, "0000600800FF",
                        (byte) 2, bytWait, bytTryCnt, bytTimOut, false, sbMfg);
                if (mfgData != null && !mfgData.toString().isEmpty()) {
                    manufacturer = hexToString(mfgData.toString().replace("0A0A", ""))
                            .replace("\b","").replace("\r\n","").replace("\n","")
                            .replace("\t","").trim();
                }
                if (manufacturer.isEmpty()) manufacturer = meterMake.displayName;
                String fileHeader = "MANUFACTURER=" + manufacturer + "\r\nMETERNO=" + meterNo + "\r\n";

                // ── RTC Check ─────────────────────────────────────────────
                rtcWarningFlag = false;
                try {
                    StringBuilder rtcSb = new StringBuilder();
                    StringBuilder rtcData = GetParameter(port, (byte) 8, "0000010000FF",
                            (byte) 2, bytWait, bytTryCnt, bytTimOut, false, rtcSb);
                    if (rtcData != null && rtcData.length() > 24) {
                        String str2 = rtcData.toString();
                        if (str2.length() >= 28) {
                            str2 = str2.substring(str2.length() - 24);
                            int DD = (int)(Long.parseLong(str2.substring(6,8),16));
                            int MM = (int)(Long.parseLong(str2.substring(4,6),16));
                            int YYYY=(int)(Long.parseLong(str2.substring(0,4),16));
                            int HH = (int)(Long.parseLong(str2.substring(10,12),16));
                            int MI = (int)(Long.parseLong(str2.substring(12,14),16));
                            int SS = (int)(Long.parseLong(str2.substring(14,16),16));
                            String meterDate = String.format("%02d/%02d/%04d %02d:%02d:%02d",
                                    DD, MM, YYYY, HH, MI, SS);
                            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);
                            Date meterDt = sdf.parse(meterDate);
                            long diffDays = Math.abs(meterDt.getTime() - new Date().getTime())
                                    / (24L*60*60*1000);
                            if (diffDays > 4) {
                                rtcWarningFlag = true;
                                rtcWarningMessage = "RTC MISMATCH: Meter=" + meterDate
                                        + " diff=" + diffDays + " days";
                                fireProgress(callback, "WARN: " + rtcWarningMessage, 32);
                            } else {
                                fireProgress(callback, "RTC OK — Meter: " + meterDate, 32);
                            }
                        }
                    }
                } catch (Exception rtcEx) {
                    fireProgress(callback, "WARN: RTC check error — " + rtcEx.getMessage(), 32);
                }

                // ── Name Plate ─────────────────────────────────────────────
                byte savedTO = bytTimOut; byte savedTC = bytTryCnt;
                bytTimOut = 3; bytTryCnt = 1;
                fireProgress(callback, "Reading Name Plate...", 35);
                StringBuilder MeterData = new StringBuilder();
                StringBuilder sbNp = ReadNamePlate(port);
                bytTimOut = savedTO; bytTryCnt = savedTC;
                if (sbNp != null && sbNp.length() > 0) MeterData.append(sbNp);

                // ── Mode switch ────────────────────────────────────────────
                switch (readingMode) {

                    case INSTANTANEOUS: {
                        byte sTO = bytTimOut; byte sTC = bytTryCnt;
                        bytTimOut = 3; bytTryCnt = 1;
                        fireProgress(callback, "Downloading Instantaneous data...", 50);
                        MeterData.append(ReadInstantData(port));
                        bytTimOut = sTO; bytTryCnt = sTC;
                        fireProgress(callback, "Instantaneous read OK", 80);
                        break;
                    }

                    case BILLING: {
                        byte sTO = bytTimOut; byte sTC = bytTryCnt;
                        bytTimOut = 4; bytTryCnt = 1;
                        fireProgress(callback, "Downloading Instantaneous data...", 45);
                        MeterData.append(ReadInstantData(port));
                        bytTimOut = 10; bytTryCnt = 3;
                        long tBm_bill = System.currentTimeMillis();
                        fireProgress(callback, "Downloading Billing data... (15-30s)", 56);
                        StringBuilder billStr = ReadBillingData(port, readingMode);
                        long tBm_billE = (System.currentTimeMillis()-tBm_bill)/1000;
                        bytTimOut = sTO; bytTryCnt = sTC;
                        if (billStr.length() > 10) {
                            MeterData.append(billStr);
                            fireProgress(callback, "✓ Billing done (" + tBm_billE + "s)", 80);
                        } else {
                            fireProgress(callback, "WARN: Billing empty — check meter (" + tBm_billE + "s)", 80);
                        }
                        break;
                    }

                    case COMPLETE: {
                        long sessionStartMs = System.currentTimeMillis();
                        long sessionDeadlineMs = sessionStartMs + 540_000L; // 9-min cap
                        byte fastTO = (byte)4; byte fastTC = (byte)1;
                        byte billTO = (byte)10; byte billTC = (byte)3;
                        byte origTO = bytTimOut; byte origTC = bytTryCnt;

                        // Phase 1: Instantaneous
                        bytTimOut = fastTO; bytTryCnt = fastTC;
                        fireProgress(callback, "Downloading Instantaneous data...", 30);
                        MeterData.append(ReadInstantData(port));
                        fireProgress(callback, "Instantaneous OK", 38);

                        if (System.currentTimeMillis() < sessionDeadlineMs) {
                            // Phase 2: Billing
                            bytTimOut = billTO; bytTryCnt = billTC;
                            drainPort(port);
                            android.os.SystemClock.sleep(150);
                            fireProgress(callback, "Downloading Billing data...", 40);
                            StringBuilder billingData = ReadBillingData(port, readingMode);
                            MeterData.append(billingData);
                            if (billingData != null && billingData.length() > 0)
                                fireProgress(callback, "Billing OK", 50);
                            else fireProgress(callback, "WARN: Billing empty", 50);

                            if (System.currentTimeMillis() < sessionDeadlineMs) {
                                // Phase 3: Midnight Snapshot
                                bytTimOut = fastTO; bytTryCnt = fastTC;
                                fireProgress(callback, "Downloading Midnight Snapshot...", 52);
                                MeterData.append(ReadMidnightSnapshot(port, 0));
                                fireProgress(callback, "Midnight OK", 58);

                                // Phase 4: Load Profile — budget = remaining time minus 30s events buffer, cap 6 min
                                long lpRemaining = sessionDeadlineMs - System.currentTimeMillis() - 30_000L;
                                if (lpRemaining >= 60_000L) {
                                    long lpBudgetMs = Math.min(lpRemaining, 360_000L);
                                    bytTimOut = (byte)8; bytTryCnt = (byte)2;
                                    lpDeadlineMs = System.currentTimeMillis() + lpBudgetMs;
                                    fireProgress(callback, "Downloading Load Profile... (up to " + (lpBudgetMs/1000) + "s)", 60);
                                    MeterData.append(ReadLoadSurveyData(port, 0));
                                    lpDeadlineMs = 0;
                                    fireProgress(callback, "Load Profile OK", 73);
                                    flushLog();
                                } else {
                                    appendLog("SESSION_SKIP_LP: only " + (lpRemaining/1000) + "s remaining");
                                    fireProgress(callback, "WARN: Insufficient time for Load Profile — skipping", 73);
                                }

                                // Phase 5: Events — re-establish COSEM first.
                                // After large LP block transfer the meter COSEM association is exhausted;
                                // subsequent GetRequest returns empty frames. SNRM+AARQ reopens it.
                                if (System.currentTimeMillis() < sessionDeadlineMs) {
                                    appendLog("REASSOC_START — re-establishing COSEM before events");
                                    try {
                                        drainPort(port);
                                        android.os.SystemClock.sleep(200);
                                        boolean nrmOk2 = SetNRM(port, bytWait, (byte)2, bytTimOut);
                                        if (nrmOk2) {
                                            int aarqRes2 = AARQ(port, (byte)1, currentMeterMake.password, bytWait, (byte)2, bytTimOut);
                                            appendLog("REASSOC_" + (aarqRes2 == 0 ? "OK" : "FAIL res=" + aarqRes2));
                                        } else { appendLog("REASSOC_NRM_FAIL — events may be incomplete"); }
                                    } catch (Exception reEx) { appendLog("REASSOC_EX: " + reEx.getMessage()); }
                                    bytTimOut = fastTO; bytTryCnt = fastTC;
                                    fireProgress(callback, "Downloading Events...", 74);
                                    MeterData.append(ReadEventData(port));
                                    fireProgress(callback, "Events OK", 79);
                                }
                            }
                        }
                        bytTimOut = origTO; bytTryCnt = origTC;
                        long sessionElapsed = System.currentTimeMillis() - sessionStartMs;
                        appendLog("SESSION_END elapsed=" + (sessionElapsed/1000) + "s limit=540s");
                        fireProgress(callback, "Complete read OK (" + (sessionElapsed/1000) + "s)", 80);
                        break;
                    }
                }

                // ── Save file ─────────────────────────────────────────────
                fireProgress(callback, "Saving data file...", 85);
                String cleanMeterNo = meterNo.replace("\r\n","").replace("\n","").trim();
                String dataFileName = buildDataFileName(meterMake.displayName,
                        readingMode.displayName, cleanMeterNo);
                if (baseFileName != null && !baseFileName.trim().isEmpty()) {
                    dataFileName = sanitizeFilePart(baseFileName) + "_" + cleanMeterNo + "_"
                            + new SimpleDateFormat("ddMMyy_HHmmss", Locale.US).format(new Date());
                }

                postProcessMeterData(MeterData);
                validateMeterDataForXML(MeterData, readingMode.name());
                String validationSummary = buildValidationBitmap(MeterData.toString());

                String filePath = "";
                if (MeterData.length() > 20) {
                    filePath = MakeDataFile(dataFileName, fileHeader + MeterData);
                }
                if (filePath.isEmpty()) {
                    if (MeterData.length() > 0) {
                        filePath = MakeDataFile(dataFileName + "_partial", fileHeader + MeterData);
                        fireProgress(callback, "WARN: Partial data saved: " + filePath, 90);
                    } else {
                        fireError(callback, "No data downloaded. Check cable and meter connection.");
                        return;
                    }
                }

                fireProgress(callback, "Record saved: " + filePath
                        + (rtcWarningFlag ? " | RTC WARNING" : ""), 100);
                appendLog("=== SDK SESSION END OK file=" + filePath + " ===");
                flushLog();

                final MeterReadingResult result = new MeterReadingResult(
                        filePath, meterNo, manufacturer, validationSummary, MeterData.toString());
                mainHandler.post(() -> callback.onComplete(result));

            } catch (Exception ex) {
                appendLog("SDK_EXCEPTION: " + ex.getMessage());
                flushLog();
                fireError(callback, "Exception: " + ex.getMessage());
            }
        }
    }

    // =========================================================================
    // CALLBACK HELPERS
    // =========================================================================

    private void fireProgress(ReadingCallback cb, String msg, int pct) {
        appendLog("PROGRESS " + pct + "% — " + msg);
        mainHandler.post(() -> cb.onProgress(msg, pct));
    }

    private void fireError(ReadingCallback cb, String msg) {
        appendLog("ERROR: " + msg);
        flushLog();
        mainHandler.post(() -> cb.onError(msg));
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    private void doFakeWork() {
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
    }

    public static int toInt32_2(byte[] bytes, int index) {
        return ((bytes[index+3]&0xFF))|((bytes[index+2]&0xFF)<<8)
                |((bytes[index+1]&0xFF)<<16)|((bytes[index]&0xFF)<<24);
    }

    private boolean IntToBool(int value) { return value != 0; }

    public String Hex2Digit(byte a) {
        String r = Integer.toHexString(0xff & a);
        return r.length() == 1 ? "0" + r : r;
    }

    private static String hexToString(String hex) {
        try {
            hex = hex.replaceAll("\\s+","");
            if (hex.length() % 2 != 0) hex = "0" + hex;
            // Skip DLMS type+length prefix if present (tag=0x0A or 0x09)
            int start = 0;
            if (hex.length() >= 4) {
                int tag = Integer.parseInt(hex.substring(0,2),16);
                if (tag == 0x0A || tag == 0x09) {
                    int len = Integer.parseInt(hex.substring(2,4),16);
                    start = 4;
                    hex = hex.substring(start, Math.min(start+len*2, hex.length()));
                    start = 0;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < hex.length()-1; i+=2) {
                int b = Integer.parseInt(hex.substring(i,i+2),16);
                if (b >= 0x20 && b <= 0x7E) sb.append((char)b);
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public static String parseMeterNoFromDlms(String rawHex) {
        try {
            if (rawHex == null || rawHex.isEmpty()) return "";
            String h = rawHex.replaceAll("\\s+","").toUpperCase();
            if (h.length() < 2) return "";
            int tag = Integer.parseInt(h.substring(0,2),16);
            if (tag == 0x06 && h.length() >= 10) return Long.toString(Long.parseLong(h.substring(2,10),16));
            if (tag == 0x05 && h.length() >= 10) {
                long v = Long.parseLong(h.substring(2,10),16);
                return Long.toString(v > 0x7FFFFFFFL ? v-0x100000000L : v);
            }
            if (tag == 0x12 && h.length() >= 6) return Integer.toString(Integer.parseInt(h.substring(2,6),16));
            if ((tag == 0x09 || tag == 0x0A) && h.length() >= 4) {
                int len = Integer.parseInt(h.substring(2,4),16);
                if (h.length() >= 4+len*2) {
                    StringBuilder sb = new StringBuilder();
                    for (int i=0; i<len; i++) {
                        int b = Integer.parseInt(h.substring(4+i*2,6+i*2),16);
                        if (b >= 0x20 && b <= 0x7E) sb.append((char)b);
                    }
                    return sb.toString().trim();
                }
            }
            return hexToString(h).replaceAll("[\\r\\n\\t]","").trim();
        } catch (Exception e) { return ""; }
    }

    private static String parseVisibleStringHex(String hexPayload) {
        if (hexPayload == null || hexPayload.length() < 4) return "";
        try {
            String h = hexPayload.replaceAll("\\s+","").toUpperCase();
            int tag = Integer.parseInt(h.substring(0,2),16);
            if (tag != 0x0A && tag != 0x09) return "";
            int len = Integer.parseInt(h.substring(2,4),16);
            if (h.length() < 4+len*2) return "";
            StringBuilder sb = new StringBuilder();
            for (int i=0; i<len; i++) {
                int b = Integer.parseInt(h.substring(4+i*2,6+i*2),16);
                if (b >= 0x20 && b <= 0x7E) sb.append((char)b);
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private String sanitizeFilePart(String value) {
        if (value == null || value.trim().isEmpty()) return "NA";
        return value.trim().replaceAll("[^A-Za-z0-9]+","_").replaceAll("^_+|_+$","");
    }

    private String buildDataFileName(String make, String mode, String meterNo) {
        return sanitizeFilePart(make) + "_" + sanitizeFilePart(mode) + "_"
                + sanitizeFilePart(meterNo) + "_"
                + new SimpleDateFormat("ddMMyy_HHmmss", Locale.US).format(new Date());
    }

    private boolean hasMeaningfulDlmsPayload(StringBuilder data) {
        if (data == null) return false;
        String text = data.toString().trim();
        if (text.isEmpty()) return false;
        String[] parts = text.split("\\s+");
        String payload = parts[parts.length-1].toUpperCase();
        if (payload.matches("0+") || payload.matches("F+")) return false;
        if (payload.startsWith("0580000000") || payload.equals("12FFFF")) return false;
        if (payload.startsWith("090C") && payload.length() >= 28) {
            String ts = payload.substring(4,28);
            if (ts.matches("F+") || ts.startsWith("FFFF")) return false;
        }
        return true;
    }

    private boolean hasDlmsScalarObjects() { return true; } // all makes
    private boolean isSecureMeter() { return hasDlmsScalarObjects(); }

    // =========================================================================
    // FILE SAVE
    // =========================================================================

    public String MakeDataFile(String fileName, String data) {
        try {
            File[] dirs = context.getExternalMediaDirs();
            if (dirs == null || dirs.length == 0 || dirs[0] == null) {
                appendLog("MakeDataFile ERROR: external media unavailable");
                return "";
            }
            File logFile = new File(dirs[0], fileName + ".TXT");
            int sizeKb = data.length() / 1024;
            if (sizeKb > 4096) appendLog("WARN: TXT size=" + sizeKb + " KB");
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, false));
            buf.append("===SESSION START ")
               .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
               .append("===");
            buf.newLine();
            buf.append(data);
            buf.newLine();
            buf.close();
            appendLog("MakeDataFile OK: " + logFile.getAbsolutePath() + " size=" + sizeKb + " KB");
            return logFile.getAbsolutePath();
        } catch (IOException e) {
            appendLog("MakeDataFile error: " + e.getMessage());
            return "";
        }
    }

    // =========================================================================
    // LOG
    // =========================================================================

    public void appendLog(String text) {
        Log.d("MeterReadingSDK", text);
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        logBuffer.append("[").append(ts).append("] ").append(text).append("\r\n");
        logLineCount++;
        if (logLineCount >= LOG_FLUSH_EVERY) flushLog();
    }

    public void flushLog() {
        if (logBuffer.length() == 0 || currentLogPath == null) return;
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(currentLogPath, true));
            bw.write(logBuffer.toString());
            bw.close();
            logBuffer.setLength(0);
            logLineCount = 0;
        } catch (Exception ignored) {}
    }

    private void startDiagLog(String make, String mode, int lpDays) {
        logBuffer.setLength(0); logLineCount = 0;
        try {
            File[] dirs = context.getExternalMediaDirs();
            if (dirs == null || dirs.length == 0 || dirs[0] == null) return;
            File logDir = new File(dirs[0], LOG_DIR_NAME);
            if (!logDir.exists()) logDir.mkdirs();
            String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            long cutoff = System.currentTimeMillis() - 24L*60*60*1000;
            int maxIdx = 0;
            File[] existing = logDir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    if (!f.getName().startsWith(LOG_PREFIX)) continue;
                    if (f.lastModified() < cutoff) { f.delete(); continue; }
                    if (f.getName().contains(today)) {
                        try {
                            String[] p = f.getName().replace(LOG_PREFIX,"")
                                    .replace(LOG_SUFFIX,"").split("_");
                            if (p.length == 2) { int i=Integer.parseInt(p[1]); if(i>maxIdx) maxIdx=i; }
                        } catch (Exception ignored) {}
                    }
                }
            }
            String fname = LOG_PREFIX + today + "_" + (maxIdx+1) + LOG_SUFFIX;
            File logFile = new File(logDir, fname);
            currentLogPath = logFile.getAbsolutePath();
            BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, false));
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            bw.write("==============================\r\n");
            bw.write("[" + ts + "] SESSION START | Make=" + make
                    + " | Mode=" + mode + " | bytTimOut=" + bytTimOut + "\r\n");
            bw.write("==============================\r\n");
            bw.close();
        } catch (Exception ignored) { currentLogPath = null; }
    }

    // =========================================================================
    // HDLC HELPERS
    // =========================================================================

    public void ClearBuffer() {
        for (int i = 0; i <= nCounter; i++) nRcvPkt[i] = 0;
        nCounter = 0;
    }

    private void drainPort(UsbSerialPort port) {
        try {
            byte[] sink = new byte[256];
            int drained = 0, read;
            long deadline = System.currentTimeMillis() + 100;
            do { read = port.read(sink, 20); drained += read; }
            while (read > 0 && System.currentTimeMillis() < deadline);
            ClearBuffer();
            if (drained > 0) appendLog("DRAIN_PORT drained=" + drained);
        } catch (Exception ex) { ClearBuffer(); }
    }

    private boolean SendPkt(UsbSerialPort port, byte[] buf, int length) {
        try {
            if (length <= 0 || length > buf.length) return false;
            byte[] s = new byte[length];
            System.arraycopy(buf, 0, s, 0, length);
            int i = port.write(s, USB_WRITE_TIMEOUT_MS);
            consecutiveSendFailures = 0;
            return i >= 0;
        } catch (Exception ex) {
            appendLog("SendPkt error: " + ex.getMessage());
            consecutiveSendFailures++;
            if (consecutiveSendFailures >= MAX_SEND_FAILURES) {
                appendLog("SEND_FAIL_ABORT");
                abortRequested = true;
            }
            return false;
        }
    }

    private void DataReceive(UsbSerialPort port) { DataReceive(port, 20); }
    private void DataReceive(UsbSerialPort port, int timeoutMs) {
        try {
            int n = port.read(buffer, timeoutMs);
            for (int i = 0; i < n; i++) nRcvPkt[nCounter++] = buffer[i];
        } catch (Exception ex) { appendLog(ex.getMessage()); }
    }

    private boolean receiveFrame(UsbSerialPort port, long deadlineMs) {
        while (System.currentTimeMillis() < deadlineMs) {
            if (abortRequested) return false;
            DataReceive(port, 20);
            if (nCounter > 2) {
                int pLen = parsePktLen();
                if (pLen > 0 && (pLen+2) <= nCounter && (pLen+1) < nRcvPkt.length
                        && (nRcvPkt[pLen+1]&0xff) == 0x7E && fcs(nRcvPkt, pLen, (byte)0)) {
                    pktLength = pLen;
                    return true;
                }
            }
        }
        return false;
    }

    private int parsePktLen() {
        int h = ((int)nRcvPkt[1]) & 7;
        int l = (int)nRcvPkt[2] & 0xff;
        int len = (h<<8)|l;
        return (len > 0 && len < nRcvPkt.length-2) ? len : 0;
    }

    private long decodeBlockNum(int addrOff) {
        return (((long)(nRcvPkt[addrOff+15]&0xff))<<24)|(((long)(nRcvPkt[addrOff+16]&0xff))<<16)
                |(((long)(nRcvPkt[addrOff+17]&0xff))<<8)|((long)(nRcvPkt[addrOff+18]&0xff));
    }

    private boolean sendRR(UsbSerialPort port, int addrOff, byte nTimeOut, byte nTryCount) {
        nPkt[2] = (byte)(addrOff+7);
        nRetLSH = (byte)(0xff&((int)nRecvCntr<<5));
        nPkt[addrOff+5] = (byte)((int)nRetLSH|0x11);
        fcs(nPkt, addrOff+5, (byte)1);
        nPkt[addrOff+8] = (byte)0x7E;
        byte retries = 0;
        do {
            ClearBuffer();
            int sz = addrOff+9;
            byte[] cmd = new byte[sz];
            for (int i=0; i<sz; i++) cmd[i]=nPkt[i];
            SendPkt(port, cmd, sz);
            long tStart = System.currentTimeMillis();
            do {
                if (abortRequested||(lpDeadlineMs>0&&System.currentTimeMillis()>lpDeadlineMs)) return false;
                DataReceive(port, 20);
                if (nCounter > 2) {
                    int pLen = parsePktLen();
                    if (pLen>0&&(pLen+2)<=nCounter&&(pLen+1)<nRcvPkt.length
                            &&(nRcvPkt[pLen+1]&0xff)==0x7E&&fcs(nRcvPkt,pLen,(byte)0)) {
                        pktLength=pLen; retries=0; FrameType(); return true;
                    }
                }
                if ((System.currentTimeMillis()-tStart)/1000>(int)nTimeOut&&(int)retries<(int)nTryCount) {
                    retries++; break;
                }
            } while ((int)retries != (int)nTryCount);
        } while ((int)retries != (int)nTryCount);
        return false;
    }

    private void FrameType() {
        String h1 = Integer.toHexString((0xff&nRcvPkt[1])&7);
        if (h1.length()==1) h1="0"+h1;
        String h2 = Integer.toHexString(0xff&nRcvPkt[2]);
        if (h2.length()==1) h2="0"+h2;
        pktLength = Integer.parseInt(h1+h2, 16);
        if ((int)nRcvPkt[(0xff&bytAddMode)+5] != 115 && ((int)(0xff&nRcvPkt[1])&168)==160) {
            nRecv = (byte)((int)(nRcvPkt[(0xff&bytAddMode)+5]&0xff)>>5);
            nSent = (byte)((int)(nRcvPkt[(0xff&bytAddMode)+5]&0xff)>>1&7);
        }
        if (((int)(0xff&nRcvPkt[1])&168)==160&&pktLength>10||((int)(0xff&nRcvPkt[1])&168)==168)
            nRecvCntr = (int)nRecvCntr!=7 ? ++nRecvCntr : (byte)0;
        if (((int)(0xff&nRcvPkt[1])&168)!=168) {
            if ((int)nRecvLast!=7) { if ((int)nRecv-(int)nRecvLast==1) nSentCntr=(int)nSentCntr!=7?++nSentCntr:(byte)0; }
            else if ((int)nRecvLast-(int)nRecv==7) nSentCntr=(int)nSentCntr!=7?++nSentCntr:(byte)0;
        }
        nRecvLast=nRecv; nSentLast=nSent;
    }

    public void Fcs_Tab() {
        int num1 = 0;
        do {
            int num2 = num1;
            short num3 = 8;
            while (num3 != 0) {
                num2 = (IntToBool(num2&1) ? (num2>>1)^33800 : num2>>1);
                fcstab[num1] = num2 & 65535;
                num3--;
            }
        } while (++num1 != 256);
    }

    public boolean fcs(byte[] cp, int len, byte flag) {
        if (flag != 0) {
            int num = fcs_cal(65535, cp, len)^65535;
            cp[len+1] = (byte)(num&255);
            cp[len+2] = (byte)(num>>(8&255));
            return true;
        } else {
            return (int)fcs_cal(65535, cp, len) == 61624;
        }
    }

    private int fcs_cal(int fcs, byte[] cp, int len) {
        for (int i = 1; i <= len; i++)
            fcs = (fcs>>8) ^ fcstab[(fcs^(0xff&cp[i]))&0xff];
        return fcs;
    }

    private void AddressInit() {
        int client = 65;
        nPkt[0] = (byte)126; nPkt[1] = (byte)160;
        if ((int)bytAddMode == 0) { nPkt[3]=(byte)3; nPkt[4]=(byte)client; }
        else { nPkt[3]=(byte)0x04; nPkt[4]=(byte)0x01; nPkt[5]=(byte)client; }
        nRecv=nRecvLast=nRecvCntr=nSent=nSentLast=nSentCntr=0;
    }

    // =========================================================================
    // SetNRM
    // =========================================================================

    private boolean SetNRM(UsbSerialPort port, int nWait, byte nTryCount, byte nTimeOut) {
        boolean flag1 = false;
        byte num1 = (byte)(5+(int)bytAddMode);
        nPkt[2] = (byte)(7+(int)bytAddMode);
        nPkt[(int)num1] = (byte)83;
        nPkt[(int)num1+1+2] = (byte)126;
        fcs(nPkt, 5+(int)bytAddMode, (byte)1);
        ClearBuffer();
        int discLen = 9+(int)bytAddMode;
        byte[] disc = new byte[discLen];
        for (int i=0; i<discLen; i++) disc[i]=nPkt[i];
        SendPkt(port, disc, discLen);
        long wakeDeadline = System.currentTimeMillis()+2000L;
        while (System.currentTimeMillis()<wakeDeadline) {
            DataReceive(port, 20);
            if (nCounter>2&&nRcvPkt[2]+2<=nCounter&&fcs(nRcvPkt,nRcvPkt[2],(byte)0)) { flag1=true; break; }
        }
        if (!flag1) appendLog("DISC_UA_MISSING — proceeding to SNRM");
        byte num5 = (byte)(5+(int)bytAddMode);
        nPkt[(int)num5] = (byte)147;
        int my = num5+1+1;
        nPkt[2] = (byte)my;
        fcs(nPkt, bytAddMode+5, (byte)1);
        nPkt[(int)num5+1+2] = (byte)126;
        byte num70 = 0; boolean flag2 = false;
        long snrmDeadline = System.currentTimeMillis()+Math.min((int)nTryCount*(int)nTimeOut*1000L, 8000L);
        do {
            if (abortRequested||System.currentTimeMillis()>snrmDeadline) break;
            ClearBuffer(); flag2=false;
            byte[] cmd = new byte[num5+1+3];
            for (int i=0; i<num5+1+3; i++) cmd[i]=nPkt[i];
            SendPkt(port, cmd, num5+1+3);
            long innerStart = System.currentTimeMillis();
            long innerTO = (int)nTimeOut*1000L;
            do {
                if (abortRequested||System.currentTimeMillis()>snrmDeadline) break;
                DataReceive(port);
                if (nCounter>2&&(int)nRcvPkt[2]+2<=nCounter
                        &&((int)nRcvPkt[(int)nRcvPkt[2]+1]==126)
                        &&fcs(nRcvPkt,nRcvPkt[2],(byte)0)) { flag2=true; break; }
                else if (System.currentTimeMillis()-innerStart>=innerTO&&(int)num70<(int)nTryCount) { ++num70; break; }
            } while (!flag2);
        } while (!flag2&&(int)num70!=(int)nTryCount&&!abortRequested&&System.currentTimeMillis()<=snrmDeadline);
        return (int)nRcvPkt[bytAddMode+5]==115 && flag2;
    }

    // =========================================================================
    // HEX HELPERS
    // =========================================================================

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len/2];
        for (int i=0; i<len; i+=2)
            data[i/2]=(byte)((Character.digit(s.charAt(i),16)<<4)+Character.digit(s.charAt(i+1),16));
        return data;
    }

    public static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length*2);
        for (byte b : a) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // =========================================================================
    // AARQ — copied from Reading.java with UI/DB refs removed
    // =========================================================================

    private int AARQ(UsbSerialPort port, byte bytAsslevel, String strPsd,
                     int nWait, byte nTryCount, byte nTimeOut) {
        byte num1 = 8;
        nRetLSH = (byte)((int)(nRecvCntr<<5));
        nPkt[(0xff&bytAddMode)+5] = (byte)((int)nRetLSH|16);
        nRetLSH = (byte)((int)nSentCntr<<1);
        nPkt[(0xff&bytAddMode)+5] = (byte)((int)nRetLSH|(int)nPkt[(0xff&bytAddMode)+5]);
        // AARQ header bytes (DLMS application-layer association request)
        nPkt[(int)num1]=(byte)230; num1++;
        nPkt[(int)num1]=(byte)230; num1++;
        nPkt[(int)num1]=(byte)0;   num1++;
        nPkt[(int)num1]=(byte)96;  num1++;
        nPkt[(int)num1]=(byte)0;   num1++;
        nPkt[(int)num1]=(byte)161; num1++;
        nPkt[(int)num1]=(byte)9;   num1++;
        nPkt[(int)num1]=(byte)6;   num1++;
        nPkt[(int)num1]=(byte)7;   num1++;
        nPkt[(int)num1]=(byte)96;  num1++;
        nPkt[(int)num1]=(byte)133; num1++;
        nPkt[(int)num1]=(byte)116; num1++;
        nPkt[(int)num1]=(byte)5;   num1++;
        nPkt[(int)num1]=(byte)8;   num1++;
        nPkt[(int)num1]=(byte)1;   num1++;
        nPkt[(int)num1]=(byte)1;   num1++;
        if ((int)bytAsslevel == 0) {
            nPkt[(0xff&bytAddMode)+12] = (byte)29;
        } else {
            // Exact byte sequence from Reading.java:
            // 8A 02 07 80  — mechanism-name (tag=0x8A, len=2, value=0x0780)
            // 8B 07 60 85 74 05 08 02 [bytAsslevel]  — calling-mechanism-name OID
            // AC [psd.len+2] 80 [psd.len] [password]  — calling-authentication-value
            nPkt[(int)num1]=(byte)138;  num1++;  // 0x8A mechanism-name
            nPkt[(int)num1]=(byte)2;    num1++;  // length = 2
            nPkt[(int)num1]=(byte)7;    num1++;  // value byte 1
            nPkt[(int)num1]=(byte)128;  num1++;  // value byte 2 = 0x80
            nPkt[(int)num1]=(byte)139;  num1++;  // 0x8B calling-mechanism-name
            nPkt[(int)num1]=(byte)7;    num1++;  // length = 7
            nPkt[(int)num1]=(byte)96;   num1++;  // OID: 0x60
            nPkt[(int)num1]=(byte)133;  num1++;  // 0x85
            nPkt[(int)num1]=(byte)116;  num1++;  // 0x74
            nPkt[(int)num1]=(byte)5;    num1++;  // 0x05
            nPkt[(int)num1]=(byte)8;    num1++;  // 0x08
            nPkt[(int)num1]=(byte)2;    num1++;  // 0x02
            nPkt[(int)num1]=(byte)bytAsslevel; num1++;  // mechanism-id value

            // Password — use hardcoded byte arrays for known passwords (exact from Reading.java)
            byte[] psdBytes;
            if (strPsd.equals("lnt1"))
                psdBytes = new byte[]{ 108, 110, 116, 49 };
            else if (strPsd.equals("ABCD0001"))
                psdBytes = new byte[]{ 65, 66, 67, 68, 48, 48, 48, 49 };
            else if (strPsd.equals("1A2B3C4D"))
                psdBytes = new byte[]{ 49, 65, 50, 66, 51, 67, 52, 68 };
            else if (strPsd.equals("1111111111111111"))
                psdBytes = new byte[]{ 49,49,49,49,49,49,49,49,49,49,49,49,49,49,49,49 };
            else if (strPsd.equals("11111111"))
                psdBytes = new byte[]{ 49, 49, 49, 49, 49, 49, 49, 49 };
            else {
                psdBytes = new byte[strPsd.length()];
                for (int pi = 0; pi < strPsd.length(); pi++) psdBytes[pi] = (byte)strPsd.charAt(pi);
            }
            nPkt[(int)num1]=(byte)172; num1++;               // 0xAC calling-auth-value
            nPkt[(int)num1]=(byte)(psdBytes.length+2); num1++;
            nPkt[(int)num1]=(byte)128; num1++;               // 0x80
            nPkt[(int)num1]=(byte)psdBytes.length; num1++;
            for (byte b : psdBytes) { nPkt[(int)num1]=(byte)b; num1++; }
            nPkt[(0xff&bytAddMode)+12] = (byte)(46 + strPsd.length());
        }
        // DLMS user-information (xDLMS initiate request) — same for both branches
        nPkt[(int)num1]=(byte)190; num1++;  // 0xBE user-information
        nPkt[(int)num1]=(byte)16;  num1++;
        nPkt[(int)num1]=(byte)4;   num1++;
        nPkt[(int)num1]=(byte)14;  num1++;
        nPkt[(int)num1]=(byte)1;   num1++;
        nPkt[(int)num1]=(byte)0;   num1++;
        nPkt[(int)num1]=(byte)0;   num1++;
        nPkt[(int)num1]=(byte)0;   num1++;
        nPkt[(int)num1]=(byte)6;   num1++;
        nPkt[(int)num1]=(byte)95;  num1++;
        nPkt[(int)num1]=(byte)31;  num1++;
        nPkt[(int)num1]=(byte)4;   num1++;
        nPkt[(int)num1]=(byte)0;   num1++;
        nPkt[(int)num1]=(byte)0;   num1++;
        nPkt[(int)num1]=(byte)24;  num1++;  // 0x18 — conformance block byte 3 (matches Reading.java)
        nPkt[(int)num1]=(byte)29;  num1++;  // 0x1D — conformance block byte 4
        nPkt[(int)num1]=(byte)255; num1++;  // 0xFF — client max receive PDU size high byte
        nPkt[(int)num1]=(byte)255; num1++;  // 0xFF — client max receive PDU size low byte
        // HDLC frame length + HCS (header) + FCS (full frame) — two-pass as in Reading.java
        nPkt[2] = (byte)((int)num1+1);
        fcs(nPkt, (int)(bytAddMode+5), (byte)1);  // HCS over header bytes
        fcs(nPkt, (int)num1-1, (byte)1);           // FCS over full frame
        nPkt[(int)num1+2] = (byte)126;
        byte retry = 0; boolean flag2 = false;
        do {
            if (abortRequested) break;
            ClearBuffer(); flag2 = false;
            int sendLen = (int)num1+3;
            byte[] cmd = new byte[sendLen];
            for (int i=0; i<sendLen; i++) cmd[i]=nPkt[i];
            SendPkt(port, cmd, sendLen);
            long deadline = System.currentTimeMillis()+(int)nTimeOut*1000L;
            do {
                if (abortRequested) break;
                DataReceive(port);
                if (nCounter>2&&(int)nRcvPkt[2]+2<=nCounter
                        &&fcs(nRcvPkt,nRcvPkt[2],(byte)0)) { flag2=true; break; }
                else if (System.currentTimeMillis()>deadline&&(int)retry<(int)nTryCount) { retry++; break; }
            } while (!flag2);
        } while (!flag2&&(int)retry!=(int)nTryCount&&!abortRequested);
        if (!flag2 || nCounter <= 27) return 2;
        if ((int)nRcvPkt[(0xff&bytAddMode)+28] != 0) return 1;
        return 0;
    }

    // =========================================================================
    // GetParameter
    // =========================================================================

    private StringBuilder GetParameter(UsbSerialPort port, byte nClassID, String sOBISCode,
            byte nAttribID, int nWait, byte nTryCount, byte nTimeOut,
            boolean isDLM, StringBuilder strbldDLMdata) {
        StringBuilder SbData = new StringBuilder();
        lastGplsResult = 0;
        try {
            boolean flag1 = false;
            long num1 = 0L;
            strbldDLMdata = new StringBuilder();
            nPkt[2] = (byte) ((int) bytAddMode + 25);
            nRetLSH = (byte) (0xff & ((int) nRecvCntr << 5));
            nPkt[(0xff & bytAddMode) + 5] = (byte) ((int) nRetLSH | 16);
            nRetLSH = (byte) ((int) nSentCntr << 1);
            nPkt[(0xff & bytAddMode) + 5] = (byte) ((int) nRetLSH | (int) nPkt[(0xff & bytAddMode) + 5]);
            int wi = (int) (0xff & ((byte) ((int) bytAddMode + 8)));
            nPkt[wi] = (byte) 230; wi++;
            nPkt[wi] = (byte) 230; wi++;
            nPkt[wi] = (byte) 0;   wi++;
            nPkt[wi] = (byte) 192; wi++;
            nPkt[wi] = (byte) 1;   wi++;
            nPkt[wi] = (byte) 129; wi++;
            nPkt[wi] = (byte) 0;   wi++;
            nPkt[wi] = (byte) nClassID; wi++;
            byte[] ob = hexStringToByteArray(sOBISCode.substring(0, 12));
            for (byte b : ob) { nPkt[wi] = (byte) b; wi++; }
            nPkt[wi] = (byte) nAttribID; wi++;
            nPkt[wi] = (byte) 0; wi++;
            byte num31 = (byte) wi;
            fcs(nPkt, (0xff & bytAddMode) + 5, (byte) 1);
            fcs(nPkt, (int) (byte) ((int) num31 - 1), (byte) 1);
            nPkt[(int) num31 + 2] = (byte) 126;
            if (isDLM) {
                String ch = Integer.toHexString(nClassID);
                if (ch.length() == 1) strbldDLMdata.append("\r\n000" + ch + " " + sOBISCode + " 0" + nAttribID + " ");
                else strbldDLMdata.append("\r\n00" + ch + " " + sOBISCode + " 0" + nAttribID + " ");
            }
            byte n33 = 0; boolean flag2;
            do {
                ClearBuffer(); flag2 = false;
                byte[] cmd = new byte[num31 + 3];
                for (int i = 0; i < num31 + 3; i++) cmd[i] = (byte) (nPkt[i] & 0xff);
                SendPkt(port, cmd, num31 + 3);
                long gpDl = System.currentTimeMillis() + (int) nTimeOut * 1000L;
                ClearBuffer();
                flag2 = receiveFrame(port, gpDl);
                if (flag2) { n33 = 0; FrameType(); } else ++n33;
            } while (!flag2 && (int) n33 != (int) nTryCount);
            // Fast-fail ACCESS_ERROR
            if (flag2 && (int) (0xff & nRcvPkt[(0xff & bytAddMode) + 11]) == 0xC4
                    && (int) (0xff & nRcvPkt[(0xff & bytAddMode) + 12]) == 0x01
                    && (int) (0xff & nRcvPkt[(0xff & bytAddMode) + 14]) != 0) {
                lastGplsResult = 1; SbData.append(strbldDLMdata); return SbData;
            }
            if (!flag2) lastGplsResult = 2;
            int aO = (int) bytAddMode;
            if ((int) (0xff & nRcvPkt[aO + 11]) == 196 && (int) (0xff & nRcvPkt[aO + 12]) == 2) {
                num1 = decodeBlockNum(aO);
                flag1 = !IntToBool(nRcvPkt[aO + 14]);
                int off = aO + 20;
                if ((int) (0xff & nRcvPkt[off]) == 130) { for (int i = aO + 23; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
                else if ((int) (0xff & nRcvPkt[off]) == 129) { for (int i = aO + 22; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
                else { for (int i = aO + 21; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
            } else {
                for (int i = aO + 15; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));
            }
            // HDLC segmentation
            while (((int) (0xff & nRcvPkt[1]) & 168) == 168) {
                nPkt[2] = (byte) ((int) bytAddMode + 7);
                nRetLSH = (byte) (0xff & ((int) nRecvCntr << 5));
                nPkt[(0xff & bytAddMode) + 5] = (byte) ((int) nRetLSH | 0x11);
                fcs(nPkt, (0xff & bytAddMode) + 5, (byte) 1);
                nPkt[(0xff & bytAddMode) + 8] = (byte) 126;
                byte xr = 0; boolean xf;
                do {
                    drainPort(port); xf = false;
                    byte[] c2 = new byte[bytAddMode + 9];
                    for (int i = 0; i < bytAddMode + 9; i++) c2[i] = nPkt[i];
                    SendPkt(port, c2, bytAddMode + 9);
                    long cDl = System.currentTimeMillis() + (int) nTimeOut * 1000L;
                    xf = receiveFrame(port, cDl);
                    if (!xf && nCounter > 0) {
                        int pl = parsePktLen();
                        if (pl > 0 && pl <= nCounter && fcs(nRcvPkt, pl, (byte) 0)) { pktLength = pl; FrameType(); xf = true; }
                    }
                    if (xf) { xr = 0; for (int i = (0xff & bytAddMode) + 8; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); FrameType(); }
                    else ++xr;
                    if (xf && ((int) nRcvPkt[(0xff & bytAddMode) + 5] == 151 || ((int) nRcvPkt[(0xff & bytAddMode) + 5] & 1) == 1)) { /* continue */ }
                    else if (xf) break;
                } while ((int) xr != (int) nTryCount);
                if (!xf || (int) nRcvPkt[1] != 160) break;
            }
            // Multi-block
            while (flag1) {
                if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) break;
                flag1 = false;
                nPkt[2] = (byte) ((int) bytAddMode + 19);
                nRetLSH = (byte) (0xff & ((int) nRecvCntr << 5));
                nPkt[(0xff & bytAddMode) + 5] = (byte) ((int) nRetLSH | 16);
                nRetLSH = (byte) ((int) nSentCntr << 1);
                nPkt[(0xff & bytAddMode) + 5] = (byte) ((int) nRetLSH | (int) nPkt[(0xff & bytAddMode) + 5]);
                byte b8 = (byte) ((int) bytAddMode + 8);
                nPkt[(int)b8]=(byte)230;b8++; nPkt[(int)b8]=(byte)230;b8++; nPkt[(int)b8]=(byte)0;b8++;
                nPkt[(int)b8]=(byte)192;b8++; nPkt[(int)b8]=(byte)2;b8++; nPkt[(int)b8]=(byte)129;b8++;
                nPkt[(int)b8]=(byte)((num1>>24)&0xFF);b8++; nPkt[(int)b8]=(byte)((num1>>16)&0xFF);b8++;
                nPkt[(int)b8]=(byte)((num1>>8)&0xFF);b8++;  nPkt[(int)b8]=(byte)(num1&0xFF);b8++;
                byte n63 = b8;
                fcs(nPkt,(0xff&bytAddMode)+5,(byte)1); fcs(nPkt,(int)(byte)((int)n63-1),(byte)1);
                nPkt[(int)n63+2]=(byte)126;
                byte n65=0; boolean f3;
                do {
                    ClearBuffer(); f3=false;
                    byte[] c2=new byte[n63+3]; for(int i=0;i<n63+3;i++) c2[i]=nPkt[i];
                    SendPkt(port,c2,n63+3);
                    long o=System.currentTimeMillis();
                    do {
                        DataReceive(port,20);
                        String h1=Integer.toHexString((0xff&nRcvPkt[1])&7); if(h1.length()==1)h1="0"+h1;
                        String h2=Integer.toHexString(0xff&nRcvPkt[2]); if(h2.length()==1)h2="0"+h2;
                        pktLength=Integer.parseInt(h1+h2,16);
                        if(nCounter>2&&pktLength+2<=nCounter&&(int)nRcvPkt[pktLength+1]==126&&fcs(nRcvPkt,pktLength,(byte)0)){f3=true;n65=0;FrameType();break;}
                        else if((System.currentTimeMillis()-o)/1000>(int)nTimeOut&&(int)n65<(int)nTryCount){n65++;break;}
                    } while (!f3);
                } while (!f3&&(int)n65!=(int)nTryCount);
                if (!f3) { lastGplsResult=2; break; }
                if ((int)(0xff&nRcvPkt[aO+11])==196&&(int)(0xff&nRcvPkt[aO+12])==2) {
                    num1=decodeBlockNum(aO); flag1=!IntToBool(0xff&nRcvPkt[aO+14]);
                }
                if ((int)(0xff&nRcvPkt[aO+20])==130){for(int i=aO+23;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));}
                else if((int)(0xff&nRcvPkt[aO+20])==129){for(int i=aO+22;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));}
                else{for(int i=aO+21;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));}
                // nested HDLC segment inside flag1
                while(((int)(0xff&nRcvPkt[1])&168)==168){
                    nPkt[2]=(byte)((int)bytAddMode+7);
                    nRetLSH=(byte)(0xff&((int)nRecvCntr<<5));
                    nPkt[(0xff&bytAddMode)+5]=(byte)((int)nRetLSH|0x11);
                    fcs(nPkt,(0xff&bytAddMode)+5,(byte)1); nPkt[(0xff&bytAddMode)+8]=(byte)126;
                    byte xr2=0; boolean xf2;
                    do {
                        ClearBuffer(); xf2=false;
                        SendPkt(port,nPkt,(byte)((int)bytAddMode+9));
                        long o2=System.currentTimeMillis();
                        do {
                            if(abortRequested||(lpDeadlineMs>0&&System.currentTimeMillis()>lpDeadlineMs)){xf2=false;break;}
                            DataReceive(port);
                            String h3=Integer.toHexString((0xff&nRcvPkt[1])&7); if(h3.length()==1)h3="0"+h3;
                            String h4=Integer.toHexString(0xff&nRcvPkt[2]); if(h4.length()==1)h4="0"+h4;
                            pktLength=Integer.parseInt(h3+h4,16);
                            if(nCounter>2&&pktLength+2<=nCounter&&(int)nRcvPkt[pktLength+1]==126&&fcs(nRcvPkt,pktLength,(byte)0)){xf2=true;xr2=0;break;}
                            if((System.currentTimeMillis()-o2)/1000>(int)nTimeOut&&(int)xr2<(int)nTryCount){xr2++;break;}
                        } while (!xf2);
                    } while (!xf2&&(int)xr2!=(int)nTryCount);
                    if (!xf2) break;
                    if((int)(0xff&nRcvPkt[aO+11])==0xC4&&(int)(0xff&nRcvPkt[aO+12])==0x02){
                        num1=decodeBlockNum(aO); flag1=!IntToBool(0xff&nRcvPkt[aO+14]);
                        int o3=aO+20;
                        if((int)(0xff&nRcvPkt[o3])==130){for(int i=aO+23;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));}
                        else if((int)(0xff&nRcvPkt[o3])==129){for(int i=aO+22;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));}
                        else{for(int i=aO+21;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));}
                    } else {
                        for(int i=aO+8;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));
                    }
                    FrameType(); if((int)nRcvPkt[1]==160) break;
                }
            }
            SbData.append(strbldDLMdata);
        } catch (Exception e) { appendLog("GetParameter ex: " + e.getMessage()); }
        return SbData;
    }

    // =========================================================================
    // GetParameter1 — multi-block DLMS read (billing / LP)
    // Exact copy from Reading.java; handles GetRequest_Next block transfer loop.
    // =========================================================================
    private StringBuilder GetParameter1(UsbSerialPort port, byte nClassID, String sOBISCode,
            byte nAttribID, int nWait, byte nTryCount, byte nTimeOut,
            boolean isDLM, StringBuilder strbldDLMdata) {
        StringBuilder SbData = new StringBuilder();
        try {
            boolean flag1 = false;
            long num1 = 0L;
            byte num2 = (byte)(bytAddMode + 8);
            strbldDLMdata = new StringBuilder();
            nPkt[2] = (byte)(bytAddMode + 25);
            nRetLSH = (byte)(0xff & ((int)nRecvCntr << 5));
            nPkt[(0xff & bytAddMode) + 5] = (byte)(nRetLSH | 16);
            nRetLSH = (byte)(nSentCntr << 1);
            nPkt[(0xff & bytAddMode) + 5] = (byte)(nRetLSH | nPkt[(0xff & bytAddMode) + 5]);
            int wi = 0xff & num2;
            nPkt[wi] = (byte)230; wi++;
            nPkt[wi] = (byte)230; wi++;
            nPkt[wi] = (byte)0;   wi++;
            nPkt[wi] = (byte)192; wi++;
            nPkt[wi] = (byte)1;   wi++;
            nPkt[wi] = (byte)129; wi++;
            nPkt[wi] = (byte)0;   wi++;
            nPkt[wi] = (byte)nClassID; wi++;
            byte[] ob = hexStringToByteArray(sOBISCode.substring(0, 12));
            for (byte b : ob) { nPkt[wi++] = b; }
            nPkt[wi] = nAttribID; wi++;
            nPkt[wi] = 0; wi++;
            byte num31 = (byte)wi;
            fcs(nPkt, (0xff & bytAddMode) + 5, (byte)1);
            fcs(nPkt, (int)(byte)(num31 - 1), (byte)1);
            nPkt[num31 + 2] = (byte)126;
            if (isDLM) {
                String ch = Integer.toHexString(nClassID);
                if (ch.length() == 1) strbldDLMdata.append("\r\n000"+ch+" "+sOBISCode+" 0"+nAttribID+" ");
                else strbldDLMdata.append("\r\n00"+ch+" "+sOBISCode+" 0"+nAttribID+" ");
            }
            byte num33 = 0; boolean flag2;
            do {
                appendLog("1 loop");
                ClearBuffer(); flag2 = false;
                byte[] cmd = new byte[num31 + 3];
                for (int i = 0; i < num31 + 3; i++) cmd[i] = (byte)(nPkt[i] & 0xff);
                SendPkt(port, cmd, num31 + 3);
                ClearBuffer();
                flag2 = receiveFrame(port, System.currentTimeMillis() + (int)nTimeOut * 1000L);
                if (flag2) { num33 = 0; FrameType(); } else ++num33;
            } while (!flag2 && (int)num33 != (int)nTryCount);
            appendLog("after 1 loop");
            if (flag2 && (int)(0xff & nRcvPkt[(0xff & bytAddMode)+11]) == 0xC4
                    && (int)(0xff & nRcvPkt[(0xff & bytAddMode)+12]) == 0x01
                    && (int)(0xff & nRcvPkt[(0xff & bytAddMode)+14]) != 0) {
                SbData.append(strbldDLMdata); return SbData;
            }
            if (flag2 && (int)(0xff & nRcvPkt[(0xff & bytAddMode)+11]) == 196
                    && (int)(0xff & nRcvPkt[(0xff & bytAddMode)+12]) == 2) {
                num1 = (((long)(nRcvPkt[(int)bytAddMode+15]&0xFF))<<24)
                      |(((long)(nRcvPkt[(int)bytAddMode+16]&0xFF))<<16)
                      |(((long)(nRcvPkt[(int)bytAddMode+17]&0xFF))<< 8)
                      | ((long)(nRcvPkt[(int)bytAddMode+18]&0xFF));
                flag1 = !IntToBool(nRcvPkt[(0xff & bytAddMode)+14]);
                appendLog("Condition 1 blockNum="+num1+" moreBlocks="+flag1);
            }
            if ((int)(0xff & nRcvPkt[(0xff & bytAddMode)+11]) == 196
                    && (int)(0xff & nRcvPkt[(0xff & bytAddMode)+12]) == 2) {
                int off = (int)bytAddMode + 20;
                if ((int)(0xff & nRcvPkt[off]) == 130) {
                    for (int i = (0xff&bytAddMode)+23; i < pktLength-1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));
                } else if ((int)(0xff & nRcvPkt[off]) == 129) {
                    for (int i = (0xff&bytAddMode)+22; i < pktLength-1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));
                } else {
                    for (int i = (0xff&bytAddMode)+21; i < pktLength-1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));
                }
            } else {
                for (int i = (0xff&bytAddMode)+15; i < pktLength-1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));
            }
            long billingDeadlineMs = System.currentTimeMillis() + 90_000L;
            int gp1ConsecFail = 0;
            int contFrameCount = 0;
            while (((int)(0xff & nRcvPkt[1]) & 168) == 168) {
                contFrameCount++;
                nPkt[2] = (byte)(bytAddMode + 7);
                nRetLSH = (byte)(0xff & ((int)nRecvCntr << 5));
                nPkt[(0xff&bytAddMode)+5] = (byte)(nRetLSH | 16 | 1);
                fcs(nPkt, (0xff&bytAddMode)+5, (byte)1);
                nPkt[(0xff&bytAddMode)+8] = (byte)126;
                byte num34 = 0; boolean flag3;
                do {
                    if (abortRequested || (lpDeadlineMs>0 && System.currentTimeMillis()>lpDeadlineMs)) { flag3=false; num34=nTryCount; break; }
                    drainPort(port); flag3 = false;
                    byte[] c2 = new byte[bytAddMode+9]; for(int i=0;i<bytAddMode+9;i++) c2[i]=nPkt[i];
                    SendPkt(port, c2, bytAddMode+9);
                    long t0 = System.currentTimeMillis();
                    flag3 = receiveFrame(port, t0+(int)nTimeOut*1000L);
                    if (!flag3 && nCounter>0) {
                        int pLen=parsePktLen();
                        if (pLen>0&&pLen<=nCounter&&fcs(nRcvPkt,pLen,(byte)0)){pktLength=pLen;FrameType();flag3=true;}
                    }
                    if (flag3) { num34=0; for(int i=(0xff&bytAddMode)+8;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); FrameType(); }
                    else { appendLog("GPLS_CONT_TIMEOUT frame="+contFrameCount+" retry="+num34); ++num34; }
                    if (flag3 && ((int)nRcvPkt[(0xff&bytAddMode)+5]==151 || ((int)nRcvPkt[(0xff&bytAddMode)+5]&1)==1)) break;
                } while ((int)num34!=(int)nTryCount);
                if (!flag3) { gp1ConsecFail++; break; }
                else { gp1ConsecFail=0; if ((int)nRcvPkt[1]!=160) {} else break; }
            }
            while (flag1) {
                if (abortRequested) break;
                if (System.currentTimeMillis() > billingDeadlineMs) { appendLog("GP1_BILLING_DEADLINE"); break; }
                if (gp1ConsecFail >= 3) { appendLog("GP1_CONSEC_FAIL_ABORT"); break; }
                flag1 = false;
                nPkt[2] = (byte)(bytAddMode + 19);
                nRetLSH = (byte)(0xff & ((int)nRecvCntr<<5));
                nPkt[(0xff&bytAddMode)+5] = (byte)(nRetLSH | 16);
                nRetLSH = (byte)(nSentCntr<<1);
                nPkt[(0xff&bytAddMode)+5] = (byte)(nRetLSH | nPkt[(0xff&bytAddMode)+5]);
                int wi2 = 0xff & (bytAddMode + 8);
                nPkt[wi2]=(byte)230; wi2++;nPkt[wi2]=(byte)230; wi2++;nPkt[wi2]=(byte)0; wi2++;
                nPkt[wi2]=(byte)192; wi2++;nPkt[wi2]=(byte)2;   wi2++;nPkt[wi2]=(byte)129; wi2++;nPkt[wi2]=(byte)0; wi2++;
                nPkt[wi2]=(byte)((num1>>24)&0xFF); wi2++;
                nPkt[wi2]=(byte)((num1>>16)&0xFF); wi2++;
                nPkt[wi2]=(byte)((num1>> 8)&0xFF); wi2++;
                nPkt[wi2]=(byte)( num1      &0xFF);
                byte num63 = (byte)(wi2+1);
                appendLog("GP1_NEXT_BLOCK blockNum="+num1);
                fcs(nPkt, (0xff&bytAddMode)+5, (byte)1);
                fcs(nPkt, (int)(byte)(num63-1), (byte)1);
                nPkt[num63+2] = (byte)126;
                byte num65 = 0; boolean flag3;
                do {
                    if (abortRequested || (lpDeadlineMs>0&&System.currentTimeMillis()>lpDeadlineMs)) { flag3=false; num65=nTryCount; break; }
                    ClearBuffer(); flag3=false;
                    byte[] cmd2=new byte[num63+3]; for(int i=0;i<num63+3;i++) cmd2[i]=nPkt[i];
                    SendPkt(port,cmd2,num63+3);
                    ClearBuffer();
                    flag3=receiveFrame(port,System.currentTimeMillis()+(int)nTimeOut*1000L);
                    if (flag3) { num65=0; FrameType(); } else ++num65;
                    if (flag3 && ((int)nRcvPkt[(0xff&bytAddMode)+5]==151||((int)nRcvPkt[(0xff&bytAddMode)+5]&1)==1)) break;
                } while ((int)num65!=(int)nTryCount);
                if (flag3 && (int)(0xff&nRcvPkt[(0xff&bytAddMode)+11])==196
                        && (int)(0xff&nRcvPkt[(0xff&bytAddMode)+12])==2) {
                    num1=(((long)(nRcvPkt[(0xff&bytAddMode)+15]&0xFF))<<24)
                        |(((long)(nRcvPkt[(0xff&bytAddMode)+16]&0xFF))<<16)
                        |(((long)(nRcvPkt[(0xff&bytAddMode)+17]&0xFF))<< 8)
                        | ((long)(nRcvPkt[(0xff&bytAddMode)+18]&0xFF));
                    flag1=!IntToBool(0xff&nRcvPkt[(0xff&bytAddMode)+14]);
                }
                int off2=(int)bytAddMode+20;
                if ((int)nRcvPkt[off2]==130) { for(int i=(0xff&bytAddMode)+23;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
                else if ((int)nRcvPkt[off2]==129) { for(int i=(0xff&bytAddMode)+22;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
                else { for(int i=(0xff&bytAddMode)+21;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
                while (((int)(0xff&nRcvPkt[1])&168)==168) {
                    nPkt[2]=(byte)(bytAddMode+7); nRetLSH=(byte)(0xff&((int)nRecvCntr<<5));
                    nPkt[(0xff&bytAddMode)+5]=(byte)(nRetLSH|16|1);
                    fcs(nPkt,(0xff&bytAddMode)+5,(byte)1); nPkt[(0xff&bytAddMode)+8]=(byte)126;
                    byte num66=0; boolean flag4;
                    do {
                        flag4=false; drainPort(port);
                        byte[] c3=new byte[bytAddMode+9]; for(int i=0;i<bytAddMode+9;i++) c3[i]=nPkt[i];
                        SendPkt(port,c3,bytAddMode+9);
                        flag4=receiveFrame(port,System.currentTimeMillis()+(int)nTimeOut*1000L);
                        if (flag4){num66=0;for(int i=(0xff&bytAddMode)+8;i<pktLength-1;i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));FrameType();}
                        else ++num66;
                        if(flag4&&((int)nRcvPkt[(0xff&bytAddMode)+5]==151||((int)nRcvPkt[(0xff&bytAddMode)+5]&1)==1)){SbData.append(strbldDLMdata);return SbData;}
                        else if(flag4) break;
                    } while((int)num66!=(int)nTryCount);
                    if (!flag4) break;
                }
            }
            SbData.append(strbldDLMdata);
        } catch (Exception ex) { appendLog("GetParameter1 ex: " + ex.getMessage()); }
        return SbData;
    }

    // =========================================================================
    // ReadScalarUnit
    // =========================================================================

    private StringBuilder ReadScalarUnit(String w, UsbSerialPort port) {
        StringBuilder sb = new StringBuilder();
        String[][] o;
        if ("INSTANT".equals(w))
            o = new String[][]{{"7","01005E5B03FF","3"},{"7","01005E5B03FF","2"}};
        else if ("BILLTYPC".equals(w))
            o = new String[][]{{"7","01005E5B06FF","3"},{"7","01005E5B06FF","2"}};
        else if ("BLOCKLOAD".equals(w))
            o = new String[][]{{"7","01005E5B04FF","3"},{"7","01005E5B04FF","2"}};
        else if ("DAILYLOAD".equals(w))
            o = new String[][]{{"7","01005E5B05FF","3"},{"7","01005E5B05FF","2"}};
        else if ("EVENT".equals(w))
            o = new String[][]{{"7","01005E5B07FF","3"},{"7","01005E5B07FF","2"}};
        else
            o = new String[][]{{"7","01005E5B03FF","2"},{"7","01005E5B04FF","2"},{"7","01005E5B05FF","2"},{"7","01005E5B06FF","2"},{"7","01005E5B07FF","2"}};
        for (String[] e : o) {
            StringBuilder l = new StringBuilder();
            StringBuilder d = GetParameter(port, Byte.parseByte(e[0]), e[1], Byte.parseByte(e[2]), bytWait, bytTryCnt, bytTimOut, true, l);
            if (hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        return sb;
    }

    // =========================================================================
    // ReadNamePlate
    // =========================================================================

    private StringBuilder ReadNamePlate(UsbSerialPort port) {
        StringBuilder sb = new StringBuilder();
        StringBuilder d; StringBuilder l;

        // ── 1. Billing amendment object (Secure makes only) ────────────────
        // OBIS 0.0.94.91.10.255 (00005E5B0AFF) — signals whether billing amendment
        // is active, needed by the XML converter for TOU tariff mapping.
        if (isSecureMeter()) {
            l = new StringBuilder();
            d = GetParameter(port,(byte)7,"00005E5B0AFF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l);
            if (d != null && d.length() > 25) {
                sb.append(d);
                l = new StringBuilder();
                d = GetParameter(port,(byte)7,"00005E5B0AFF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l);
                if (hasMeaningfulDlmsPayload(d)) sb.append(d);
            }
        }

        // ── 2. COSEM logical device name (OBIS 0.0.42.0.0.255 = 00002A0000FF) ──
        // HPL meters encode the model sub-variant here: "HPLSPEM6..." (14-col LP),
        // "HPLPPEM6..." (15-col LP), "HPLCT05..." (9-col LP).
        // Used by getCaptureObjectsForMake() to select the correct LP column layout.
        l = new StringBuilder();
        d = GetParameter(port,(byte)1,"00002A0000FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l);
        if (hasMeaningfulDlmsPayload(d)) {
            sb.append(d);
            if (currentMeterMake == MeterMake.HPL) {
                String dmStr = d.toString();
                int lineEnd = dmStr.lastIndexOf('\n');
                String dataLine = lineEnd >= 0 ? dmStr.substring(lineEnd).trim() : dmStr.trim();
                int lastSp = dataLine.lastIndexOf(' ');
                if (lastSp >= 0) {
                    String devName = parseVisibleStringHex(dataLine.substring(lastSp+1).trim());
                    if (!devName.isEmpty()) {
                        hplLogicalDeviceName = devName.toUpperCase();
                        appendLog("HPL_LOGICAL_DEV=" + hplLogicalDeviceName);
                    }
                }
            }
        }

        // ── 3. Integration period (OBIS 1.0.0.8.4.255 = 0100000804FF) ──────
        // Parsed to set intProfilePd — NOT written to output (matches Reading.java).
        // NOTE: LP capture_period from 0100630100FF attr=4 is NOT read here (BUG-19 fix
        // in Reading.java: reading it here caused stale value before the LP phase).
        l = new StringBuilder();
        d = GetParameter(port,(byte)1,"0100000804FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l);
        if (hasMeaningfulDlmsPayload(d)) {
            // Do NOT append to sb — Reading.java only parses this for intProfilePd, doesn't write it
            try {
                String rawPd = d.toString().trim();
                String pdHex = rawPd.length() >= 4 ? rawPd.substring(rawPd.length()-4) : rawPd;
                intProfilePd = (int)(Long.parseLong(pdHex, 16) / 60);
                if (intProfilePd <= 0) intProfilePd = 15;
                appendLog("LP_PERIOD_FROM_804=" + intProfilePd);
            } catch (Exception ignored) {}
        }

        // ── 4. RTC / Clock ────────────────────────────────────────────────
        l = new StringBuilder();
        d = GetParameter(port,(byte)8,"0000010000FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l);
        if (hasMeaningfulDlmsPayload(d)) sb.append(d);

        // ── 5. Nameplate OBIS — exact sequence from Reading.java ReadNamePlate ───
        // Meter serial, firmware version, meter identifier, meter rating
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0000600100FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0100000200FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // TOU configuration (Secure only) — placed here to match Reading.java's ReadNamePlate order
        if (isSecureMeter()) {
            l=new StringBuilder(); d=GetParameter(port,(byte)1,"00005E5B09FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0000000100FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0100000800FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Manufacturer name, year of manufacture
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0000600101FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0000600104FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // CT/PT ratio, billing period reset timestamp
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0100000402FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0100000403FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0000000102FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Make-specific (single attempt each — not in all association lists)
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0100608012FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)63,"0000600A01FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0100608017FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Meter constant (Genus LC)
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100800800FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100800800FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Tamper duration counters (Genus LC)
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0000600885FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0000600885FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0000600886FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0000600887FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // L&T extended nameplate registers 0.0.96.11.x.255 (single attempt each)
        for (String nb : new String[]{"0000600B01FF","0000600B02FF","0000600B03FF","0000600B04FF","0000600B05FF"}) {
            l=new StringBuilder(); d=GetParameter(port,(byte)1,nb,(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        // Firmware/software version string and electronic serial number (all makes, bytTryCnt)
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0000600700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0000600200FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Special Days table (class 16, attr=4) — all makes
        l=new StringBuilder(); d=GetParameter(port,(byte)16,"00000F0000FF",(byte)4,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // HPL-specific nameplate and configuration registers (single attempt each)
        for (String hplNp : new String[]{"010080800EFF","010080800FFF","010080058CFF"}) {
            l=new StringBuilder(); d=GetParameter(port,(byte)1,hplNp,(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0100600500FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100800880FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100800880FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        for (String hplSt : new String[]{"0100800580FF","0100800581FF","0100800582FF","0100800583FF","0100800584FF","0100800585FF","0100800587FF","0100800597FF"}) {
            l=new StringBuilder(); d=GetParameter(port,(byte)1,hplSt,(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        // Genus optical interface type (single attempt)
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"0000601410FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        return sb;
    }

    // =========================================================================
    // ReadInstantData
    // =========================================================================

    private StringBuilder ReadInstantData(UsbSerialPort port) {
        StringBuilder sb = new StringBuilder(); StringBuilder d; StringBuilder l;
        d = ReadScalarUnit("INSTANT", port);
        if (hasMeaningfulDlmsPayload(d)) sb.append(d);
        if (isSecureMeter()) {
            l=new StringBuilder(); d=GetParameter(port,(byte)7,"01005E5B00FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)7,"01005E5B00FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        boolean isSecure = (currentMeterMake == MeterMake.SECURE);
        boolean isGenus  = (currentMeterMake == MeterMake.GENUS || currentMeterMake == MeterMake.AVON);
        boolean isHPL    = (currentMeterMake == MeterMake.HPL);
        boolean isLNT    = (currentMeterMake == MeterMake.LNT);
        boolean needIndivScalers = !isSecure;
        // Current L1 — attr=3 skipped for Secure (compound object provides all scalers)
        if (needIndivScalers) { l=new StringBuilder(); d=GetParameter(port,(byte)3,"01001F0700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d); }
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"01001F0700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Current L2
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100330700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100330700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Current L3
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100470700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100470700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Voltage L1
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100200700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100200700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Voltage L2
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100340700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100340700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Voltage L3
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100480700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100480700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // PF L1
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100210700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100210700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // PF L2
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100350700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100350700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // PF L3
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100490700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100490700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Active power import
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100010700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100010700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Reactive power import
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100030700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100030700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Apparent power
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100090700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100090700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Frequency
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"01000E0700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"01000E0700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // PF total
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"01000D0700FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"01000D0700FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // kWh import
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100010800FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100010800FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // Export probe — read kWh export first; skip all export OBIS on import-only meters
        boolean exportSupported = false;
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100020800FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100020800FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l);
        if (hasMeaningfulDlmsPayload(d)) { sb.append(d); exportSupported=true; appendLog("EXPORT_PROBE: kWh_exp present — reading all export OBIS"); }
        else { appendLog("EXPORT_PROBE: kWh_exp empty — import-only meter, skipping export OBIS"); }
        // kVAh import
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100090800FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100090800FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // kVArh Q1 lag
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100050800FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100050800FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // kVArh Q4 lead
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100080800FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100080800FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // kVAh export
        if (exportSupported) {
            l=new StringBuilder(); d=GetParameter(port,(byte)3,"01000A0800FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)3,"01000A0800FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        // MD kW import
        l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100010600FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100010600FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100010600FF",(byte)5,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // MD kVA import
        l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100090600FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100090600FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100090600FF",(byte)5,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // MD kW + kVA export
        if (exportSupported) {
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100020600FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100020600FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100020600FF",(byte)5,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"01000A0600FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"01000A0600FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"01000A0600FF",(byte)5,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        // Genus MD kW/kVA export (Genus-specific OBIS)
        if (isGenus) {
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100B20600FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100B20600FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100B20600FF",(byte)5,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100B30600FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100B30600FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)4,"0100B30600FF",(byte)5,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        // L&T per-phase active power
        if (isLNT) {
            for (String ob2 : new String[]{"0100240700FF","0100380700FF","01004C0700FF"}) {
                if (abortRequested) break;
                l=new StringBuilder(); d=GetParameter(port,(byte)3,ob2,(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
                l=new StringBuilder(); d=GetParameter(port,(byte)3,ob2,(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            }
        }
        // Genus kVAh .2. group
        if (isGenus) {
            l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100090200FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100090200FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        // HPL phasor angles
        if (isHPL) {
            for (String ob2 : new String[]{"0100510701FF","0100510702FF","0100510704FF","0100510705FF","0100510706FF"}) {
                if (abortRequested) break;
                l=new StringBuilder(); d=GetParameter(port,(byte)3,ob2,(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
                l=new StringBuilder(); d=GetParameter(port,(byte)3,ob2,(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            }
        }
        // Manufacturer ID
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0000600800FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)3,"0000600800FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // HPL phasor/power-quality snapshot profiles
        if (isHPL) {
            for (String ob2 : new String[]{"0100638100FF","0100638000FF","0100638200FF","0100638300FF"}) {
                if (abortRequested) break;
                l=new StringBuilder(); d=GetParameter(port,(byte)7,ob2,(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
                l=new StringBuilder(); d=GetParameter(port,(byte)7,ob2,(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            }
        }
        // Genus harmonics (.7C. group) + kWh .2.
        if (isGenus) {
            for (String hob : new String[]{"010020077CFF","010034077CFF","010048077CFF","01001F077CFF","010033077CFF","010047077CFF"}) {
                if (abortRequested) break;
                l=new StringBuilder(); d=GetParameter(port,(byte)3,hob,(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
                l=new StringBuilder(); d=GetParameter(port,(byte)3,hob,(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            }
            l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100010200FF",(byte)3,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
            l=new StringBuilder(); d=GetParameter(port,(byte)3,"0100010200FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        }
        return sb;
    }

    // =========================================================================
    // ReadBillingData
    // =========================================================================

    private String[] appendToArray(String[] arr, String... extras) {
        String[] r = new String[arr.length + extras.length];
        System.arraycopy(arr, 0, r, 0, arr.length);
        System.arraycopy(extras, 0, r, arr.length, extras.length);
        return r;
    }

    private StringBuilder ReadBillingData(UsbSerialPort port, ReadingMode rm) {
        StringBuilder sb = new StringBuilder(); StringBuilder d; StringBuilder l;
        boolean billingBufferFailed = false;
        // STEP 1: Current RTC — must be first so parser knows read timestamp
        l=new StringBuilder(); d=GetParameter(port,(byte)8,"0000010000FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // STEP 2: Scalar/unit descriptor for billing type
        d=ReadScalarUnit("BILLTYPC",port); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // STEP 2b: Per-register billing scalers (BSCL prefix)
        String[] bo = {
            "0100010800FF","0100020800FF","0100090800FF","01000A0800FF",
            "0100050800FF","0100060800FF","0100070800FF","0100080800FF",
            "0100010600FF","0100090600FF","0100090601FF","0100010601FF",
            "01000A0600FF","0100020600FF",
            "0100B20600FF","0100B30600FF"
        };
        for (int t=1; t<=8; t++) bo = appendToArray(bo, String.format("010001080%XFF", t));
        for (int t=1; t<=4; t++) bo = appendToArray(bo, String.format("010002080%XFF", t));
        for (int t=1; t<=8; t++) bo = appendToArray(bo, String.format("010009080%XFF", t));
        for (String ob : bo) {
            if (abortRequested) break;
            StringBuilder scalerSb = new StringBuilder();
            d = GetParameter(port,(byte)3,ob,(byte)3,bytWait,(byte)1,bytTimOut,false,scalerSb);
            if (hasMeaningfulDlmsPayload(d)) { sb.append("\r\nBSCL ").append(ob).append(" ").append(d.toString().trim()); appendLog("BILL_SCALER_READ obis=" + ob); }
        }
        // STEP 3: Billing profile capture_objects (attr=3)
        l=new StringBuilder(); d=GetParameter(port,(byte)7,"0100620100FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l);
        if (hasMeaningfulDlmsPayload(d)) sb.append(d); else billingBufferFailed = true;
        // STEP 4: Billing profile buffer (attr=2) — BILLING mode uses selective access (2 records only)
        boolean usedSelectiveAccess = false;
        if (rm == ReadingMode.BILLING) {
            try {
                java.util.Calendar now = java.util.Calendar.getInstance();
                int yyyy=now.get(java.util.Calendar.YEAR); int mm=now.get(java.util.Calendar.MONTH)+1;
                int dd2=now.get(java.util.Calendar.DAY_OF_MONTH); int hh=now.get(java.util.Calendar.HOUR_OF_DAY);
                int min=now.get(java.util.Calendar.MINUTE); int ss2=now.get(java.util.Calendar.SECOND);
                byte[] fromDt = new byte[]{(byte)(yyyy>>8),(byte)(yyyy&0xFF),(byte)mm,0x01,(byte)0xFF,0x00,0x00,0x00,(byte)0xFF,(byte)0x80,0x00,(byte)0xFF};
                byte[] toDt   = new byte[]{(byte)(yyyy>>8),(byte)(yyyy&0xFF),(byte)mm,(byte)dd2,(byte)0xFF,(byte)hh,(byte)min,(byte)ss2,0x00,(byte)0x80,0x00,(byte)0xFF};
                StringBuilder dest = new StringBuilder();
                d = GetParameterWithRangeAccess(port,(byte)7,"0100620100FF",fromDt,toDt,(byte)bytWait,bytTryCnt,bytTimOut,true,dest);
                if (hasMeaningfulDlmsPayload(d)) {
                    sb.append(d); usedSelectiveAccess = true;
                    appendLog("BILLING_SELECTIVE: 2-date read succeeded (" + String.format("%02d/%02d/%04d", 1, mm, yyyy) + " to " + String.format("%02d/%02d/%04d %02d:%02d:%02d", dd2, mm, yyyy, hh, min, ss2) + ")");
                }
            } catch (Exception selEx) { appendLog("BILLING_SELECTIVE_FAIL: " + selEx.getMessage() + " — falling back to full buffer"); }
        }
        if (!usedSelectiveAccess) {
            l=new StringBuilder(); d=GetParameter1(port,(byte)7,"0100620100FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,l);
            if (hasMeaningfulDlmsPayload(d)) sb.append(d);
            else { billingBufferFailed = true; appendLog("BILLING_WARN: Buffer (attr=2) failed — CO and EIU preserved"); }
        }
        // STEP 5: Billing profile entries_in_use (attr=7)
        l=new StringBuilder(); d=GetParameter(port,(byte)7,"0100620100FF",(byte)7,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // STEP 6: ActivityCalendar (Class 20, OBIS 0.0.13.0.0.255) — tariff schedule
        for (byte attr : new byte[]{2,3,4,5}) { l=new StringBuilder(); d=GetParameter(port,(byte)20,"00000D0000FF",attr,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d); }
        l=new StringBuilder(); d=GetParameter(port,(byte)20,"00000D0000FF",(byte)7,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)20,"00000D0000FF",(byte)9,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        l=new StringBuilder(); d=GetParameter(port,(byte)1,"00000D0080FF",(byte)2,bytWait,(byte)1,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        if (billingBufferFailed) { appendLog("BILLING_WARN: Billing buffer missing — partial billing section retained (CO+EIU present)"); sb.append("\r\nBILLING_BUFFER_FAILED=1"); }
        return sb;
    }

    private StringBuilder GetParameterWithRangeAccess(UsbSerialPort port, byte classId, String obis,
            byte[] fromDt, byte[] toDt, byte wait, byte tryCnt, byte timeout, boolean append, StringBuilder dest) {
        try {
            byte[] clockObisBytes = new byte[]{0x00,0x00,0x01,0x00,0x00,(byte)0xFF};
            byte[] selectData = new byte[]{
                0x01, 0x02,0x04, 0x02,0x04, 0x12,0x00,0x08, 0x09,0x06,
                clockObisBytes[0],clockObisBytes[1],clockObisBytes[2],clockObisBytes[3],clockObisBytes[4],clockObisBytes[5],
                0x11,0x02, 0x12,0x00,0x00, 0x09,0x0C,
                fromDt[0],fromDt[1],fromDt[2],fromDt[3],fromDt[4],fromDt[5],fromDt[6],fromDt[7],fromDt[8],fromDt[9],fromDt[10],fromDt[11],
                0x09,0x0C,
                toDt[0],toDt[1],toDt[2],toDt[3],toDt[4],toDt[5],toDt[6],toDt[7],toDt[8],toDt[9],toDt[10],toDt[11],
                0x01,0x00
            };
            return GetParameter1WithSelectiveAccess(port, classId, obis, (byte)2, selectData, wait, tryCnt, timeout, append, dest);
        } catch (Exception ex) {
            appendLog("RANGE_ACCESS_BUILD_FAIL: " + ex.getMessage());
            return GetParameter1(port, classId, obis, (byte)2, wait, tryCnt, timeout, append, dest);
        }
    }

    private StringBuilder GetParameter1WithSelectiveAccess(UsbSerialPort port,
            byte classId, String obis, byte attrId, byte[] selectData,
            byte wait, byte tryCnt, byte timeout, boolean append, StringBuilder dest) {
        appendLog("SELECTIVE_ACCESS: falling back to full buffer (APDU encoding pending)");
        return GetParameter1(port, classId, obis, attrId, wait, tryCnt, timeout, append, dest);
    }

    // =========================================================================
    // ReadMidnightSnapshot / ReadEventData
    // =========================================================================

    private StringBuilder ReadMidnightSnapshot(UsbSerialPort port, int ls) {
        StringBuilder sb = new StringBuilder(); StringBuilder d; StringBuilder l;
        // Daily snapshot scalar/unit (01005E5B05FF) — Secure only, fast-fail on others
        d = ReadScalarUnit("DAILYLOAD", port);
        if (hasMeaningfulDlmsPayload(d)) sb.append(d);
        // attr=3: capture_objects — column definitions
        l=new StringBuilder(); d=GetParameter(port,(byte)7,"0100630200FF",(byte)3,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // attr=4: capture_period in seconds — needed for selective access alignment
        l=new StringBuilder(); d=GetParameter(port,(byte)7,"0100630200FF",(byte)4,bytWait,bytTryCnt,bytTimOut,true,l); if(hasMeaningfulDlmsPayload(d)) sb.append(d);
        // attr=7: entries_in_use — cross-check with buffer result
        l=new StringBuilder(); d=GetParameter(port,(byte)7,"0100630200FF",(byte)7,bytWait,bytTryCnt,bytTimOut,true,l);
        int midnightEiu = -1;
        if (hasMeaningfulDlmsPayload(d)) {
            sb.append(d);
            try {
                String[] ep = d.toString().trim().split("\\s+");
                if (ep.length >= 4 && ep[3].length() >= 10)
                    midnightEiu = (int) Long.parseLong(ep[3].substring(2, 10), 16);
                appendLog("MIDNIGHT_EIU=" + midnightEiu);
            } catch (Exception ignored) {}
        }
        // attr=2: buffer — selective access for last snapDays days
        int snapDays = (ls > 0) ? ls : 35;
        StringBuilder bufD = null;
        try {
            java.util.Calendar calEnd = java.util.Calendar.getInstance();
            calEnd.set(java.util.Calendar.HOUR_OF_DAY, 23); calEnd.set(java.util.Calendar.MINUTE, 59); calEnd.set(java.util.Calendar.SECOND, 59);
            java.util.Calendar calStart = java.util.Calendar.getInstance();
            calStart.add(java.util.Calendar.DAY_OF_YEAR, -snapDays);
            calStart.set(java.util.Calendar.HOUR_OF_DAY, 0); calStart.set(java.util.Calendar.MINUTE, 0); calStart.set(java.util.Calendar.SECOND, 0);
            appendLog("MIDNIGHT_SEL days=" + snapDays);
            l=new StringBuilder();
            bufD = GetParameterSelective(port,(byte)7,"0100630200FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,calStart.getTime(),calEnd.getTime(),1440,l);
        } catch (Exception e2) {
            appendLog("MIDNIGHT_SEL_EX: " + e2.getMessage() + " — falling back to full buffer");
        }
        if (hasMeaningfulDlmsPayload(bufD)) { sb.append(bufD); }
        else {
            appendLog("MIDNIGHT_SEL_EMPTY — retrying with full buffer read");
            StringBuilder fbSb = new StringBuilder();
            bufD = GetParameter_LS(port,(byte)7,"0100630200FF",(byte)2,bytWait,bytTryCnt,bytTimOut,true,fbSb);
            if (hasMeaningfulDlmsPayload(bufD)) sb.append(bufD);
        }
        // Cross-check EIU vs buffer
        if (midnightEiu == 0) appendLog("MIDNIGHT_INFO: entries_in_use=0 — buffer confirmed empty");
        else if (midnightEiu > 0 && !hasMeaningfulDlmsPayload(bufD)) appendLog("MIDNIGHT_CRITICAL: entries_in_use=" + midnightEiu + " but buffer empty — read failure");
        if (sb.length() == 0) appendLog("MIDNIGHT_WARN: Midnight snapshot all responses empty — skipping section.");
        return sb;
    }

    private StringBuilder ReadEventData(UsbSerialPort port) {
        StringBuilder strbldDLMdata = new StringBuilder(); StringBuilder DLMdata;
        DLMdata = ReadScalarUnit("EVENT", port);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        String[] eventObis = {
            "0000636200FF","0000636201FF","0000636202FF","0000636203FF","0000636204FF",
            "0000636205FF","0000636206FF",
            "0000636233FF","0000636234FF","0000636235FF","0000636236FF","0000636237FF","0000636238FF","0000636239FF",
            "0000636281FF","0000636285FF","0000636286FF","0000636288FF",
            "000063628FFF","0000636290FF","0000636291FF","0000636292FF","0000636293FF","0000636294FF","0000636295FF",
            "00005E5B08FF","00005E5B09FF","00005E5B0AFF","00005E5B0BFF","00005E5B0CFF","00005E5B0DFF","00005E5B0EFF"
        };
        boolean eventCoMissing = false; boolean eventCoProbed = false;

        for (String obis : eventObis) {
            if (abortRequested) { appendLog("EVENT_LOOP_ABORT — abortRequested=true, skipping remaining event OBIS"); break; }
            // attr=3: capture objects
            StringBuilder l = new StringBuilder();
            DLMdata = GetParameter(port,(byte)7,obis,(byte)3,bytWait,bytTryCnt,bytTimOut,true,l);
            if (!hasMeaningfulDlmsPayload(DLMdata)) {
                if (!eventCoMissing && !eventCoProbed) {
                    eventCoProbed = true;
                    appendLog("EVENT_CO_PROBE obis=" + obis + " — attr=3 empty, probing attr=2 directly");
                    StringBuilder probeSb = new StringBuilder();
                    StringBuilder probeDat = GetParameter(port,(byte)7,obis,(byte)2,bytWait,(byte)1,bytTimOut,false,probeSb);
                    if (hasMeaningfulDlmsPayload(probeDat)) {
                        String probeHex = probeDat.toString().trim().split("\\s+").length > 3 ? probeDat.toString().trim().split("\\s+")[3] : "";
                        if (probeHex.length() >= 4 && probeHex.startsWith("01") && Integer.parseInt(probeHex.substring(2,4),16) > 0) {
                            eventCoMissing = true; strbldDLMdata.append(probeDat);
                            appendLog("EVENT_CO_PROBE_HAS_DATA obis=" + obis + " — EIU/CO firmware bug, reading all events without CO check");
                            continue;
                        } else { appendLog("EVENT_CO_PROBE_EMPTY obis=" + obis + " — events not supported"); }
                    } else { appendLog("EVENT_CO_PROBE_NO_RESPONSE obis=" + obis + " — events not supported"); }
                }
                if (!eventCoMissing) { appendLog("EVENT_SKIP obis=" + obis + " attr=3 absent — not supported on this meter"); continue; }
            } else { strbldDLMdata.append(DLMdata); }

            // attr=7: entries_in_use
            l = new StringBuilder();
            DLMdata = GetParameter(port,(byte)7,obis,(byte)7,bytWait,(byte)1,bytTimOut,true,l);
            int eiu = -1;
            if (hasMeaningfulDlmsPayload(DLMdata)) {
                strbldDLMdata.append(DLMdata);
                try { String[] ep = DLMdata.toString().trim().split("\\s+"); if (ep.length >= 4 && ep[3].length() >= 10) eiu = (int)Long.parseLong(ep[3].substring(2,10),16); } catch (Exception ignored) {}
                appendLog("EVENT_EIU obis=" + obis + " entries=" + eiu);
            }

            // Session recovery if DM frame after attr=7
            if (lastGplsResult == 2) {
                appendLog("EVENT_DM_BEFORE_ATTR2 obis=" + obis + " — re-establishing session");
                try {
                    AddressInit(); boolean nrmOk = SetNRM(port, bytWait, (byte)2, bytTimOut);
                    if (nrmOk) { int aarqRes = AARQ(port,(byte)1,currentMeterMake.password,bytWait,(byte)2,bytTimOut); if(aarqRes==0){drainPort(port);lastGplsResult=0;}else{appendLog("EVENT_DM_RECOVER_FAIL obis="+obis);continue;} }
                    else { appendLog("EVENT_DM_RECOVER_NRM_FAIL obis=" + obis); continue; }
                } catch (Exception evDmEx) { appendLog("EVENT_DM_RECOVER_EX " + evDmEx.getMessage()); continue; }
            }
            // Skip attr=2 when EIU=0
            if (eiu == 0) { appendLog("EVENT_SKIP_ATTR2 obis=" + obis + " entries_in_use=0"); continue; }

            // attr=2: buffer with per-event deadline
            long eventReadBudgetMs = (long)bytTimOut * bytTryCnt * 2 * 1000L;
            long eventDeadline = System.currentTimeMillis() + eventReadBudgetMs;
            appendLog("EVENT_ATTR2_START obis=" + obis + " eiu=" + eiu + " budget=" + (eventReadBudgetMs/1000) + "s");
            l = new StringBuilder();
            DLMdata = GetParameter(port,(byte)7,obis,(byte)2,bytWait,bytTryCnt,bytTimOut,true,l);
            if (hasMeaningfulDlmsPayload(DLMdata)) {
                strbldDLMdata.append(DLMdata);
                appendLog("EVENT_ATTR2_OK obis=" + obis + " elapsed=" + (System.currentTimeMillis()-eventDeadline+eventReadBudgetMs) + "ms");
            } else if (eiu == 0) {
                appendLog("EVENT_INFO obis=" + obis + " buffer empty — consistent with entries_in_use=0");
            } else if (eiu > 0) {
                appendLog("EVENT_WARN obis=" + obis + " buffer empty but entries_in_use=" + eiu + " — read failure");
                if (lastGplsResult == 2) {
                    appendLog("EVENT_ATTR2_DM obis=" + obis + " — session dropped, re-establishing");
                    try {
                        AddressInit(); boolean nrmOk = SetNRM(port, bytWait, (byte)2, bytTimOut);
                        if (nrmOk) { int aarqRes = AARQ(port,(byte)1,currentMeterMake.password,bytWait,(byte)2,bytTimOut); if(aarqRes==0){drainPort(port);lastGplsResult=0;appendLog("EVENT_ATTR2_DM_RECOVER_OK obis="+obis);} }
                    } catch (Exception evEx) { appendLog("EVENT_ATTR2_DM_EX " + evEx.getMessage()); }
                }
            } else { appendLog("EVENT_SKIP obis=" + obis + " attr=2 zero-or-empty"); }
        }
        if (strbldDLMdata.length() == 0) appendLog("EVENT_WARN: All event responses empty — meter may not support event logs");
        return strbldDLMdata;
    }

    // =========================================================================
    // =========================================================================
    // Helper functions for ReadLoadSurveyData (copied from Reading.java)
    // =========================================================================

    private boolean hasLoadProfileRecords(String lpHex) {
        if (lpHex == null || lpHex.isEmpty()) return false;
        if (lpHex.contains("0212090c")) return true;
        if (lpHex.contains("0212090C")) return true;
        int tsCount = 0; int idx = 0; String lowerHex = lpHex.toLowerCase();
        while ((idx = lowerHex.indexOf("090c", idx)) >= 0) { tsCount++; idx += 4; if (tsCount >= 2) return true; }
        return false;
    }

    private String mergeLpPageHexList(java.util.List<String> pages) {
        if (pages == null || pages.isEmpty()) return null;
        if (pages.size() == 1) return pages.get(0);
        StringBuilder allRecords = new StringBuilder(); int totalCount = 0; int skippedPages = 0;
        for (String page : pages) {
            if (page == null || page.length() < 4) continue;
            String lower = page.toLowerCase(); int dataStart = -1; int cnt = 0;
            for (int skip = 0; skip <= 32; skip += 2) {
                if (skip + 4 > lower.length()) break;
                int tagByte; try { tagByte = Integer.parseInt(lower.substring(skip, skip + 2), 16); } catch (Exception e) { break; }
                if (tagByte == 0x01) {
                    int cbPos = skip + 2; if (cbPos + 2 > lower.length()) break;
                    int countByte; try { countByte = Integer.parseInt(lower.substring(cbPos, cbPos + 2), 16); } catch (Exception e) { break; }
                    if ((countByte & 0x80) == 0) { cnt = countByte; dataStart = cbPos + 2; }
                    else { int nb = countByte & 0x7F; if (nb == 1 && cbPos + 4 <= lower.length()) { try { cnt = Integer.parseInt(lower.substring(cbPos + 2, cbPos + 4), 16); } catch (Exception e) { break; } dataStart = cbPos + 4; } else if (nb == 2 && cbPos + 6 <= lower.length()) { try { cnt = Integer.parseInt(lower.substring(cbPos + 2, cbPos + 6), 16); } catch (Exception e) { break; } dataStart = cbPos + 6; } }
                    break;
                }
            }
            if (dataStart < 0 || dataStart >= page.length()) {
                int recStart = -1; int idx = 0;
                while ((idx = lower.indexOf("090c", idx)) >= 0) { int candidate = idx - 4; if (candidate >= 0 && lower.charAt(candidate) == '0' && lower.charAt(candidate + 1) == '2') { recStart = candidate; break; } idx += 4; }
                if (recStart >= 0 && recStart < page.length()) { int recCnt = countLoadProfileRecords(page); totalCount += recCnt; allRecords.append(page.substring(recStart)); } else { skippedPages++; }
            } else if (dataStart + 2 <= lower.length() && lower.charAt(dataStart) == '0' && lower.charAt(dataStart + 1) == '2') {
                totalCount += cnt; allRecords.append(page.substring(dataStart));
            } else {
                int recStart = -1; int idx = 0;
                while ((idx = lower.indexOf("090c", idx)) >= 0) { int candidate = idx - 4; if (candidate >= 0 && lower.charAt(candidate) == '0' && lower.charAt(candidate + 1) == '2') { recStart = candidate; break; } idx += 4; }
                if (recStart >= 0 && recStart < page.length()) { int recCnt = countLoadProfileRecords(page); totalCount += recCnt; allRecords.append(page.substring(recStart)); } else { skippedPages++; }
            }
        }
        if (skippedPages > 0) appendLog("LP_MERGE_SKIPPED_PAGES=" + skippedPages);
        String berCount;
        if (totalCount < 0x80) berCount = String.format("%02X", totalCount);
        else if (totalCount < 0x100) berCount = String.format("81%02X", totalCount);
        else berCount = String.format("82%04X", totalCount);
        return "01" + berCount + allRecords.toString();
    }

    private java.util.Date extractLastLpTimestamp(String lpHex, int capturePeriodMin) {
        try {
            String lowerHex = lpHex.toLowerCase(); int lastPos = -1; int idx = 0;
            while (true) { int found = lowerHex.indexOf("090c07e", idx); if (found < 0) break; lastPos = found; idx = found + 1; }
            if (lastPos < 0) return null;
            String ts = lowerHex.substring(lastPos + 4, lastPos + 28); if (ts.length() < 24) return null;
            int y  = Integer.parseInt(ts.substring(0, 4),  16); int mo = Integer.parseInt(ts.substring(4, 6),  16); if (mo > 127) mo = 1;
            int d  = Integer.parseInt(ts.substring(6, 8),  16); if (d  > 127) d  = 1;
            int h  = Integer.parseInt(ts.substring(10, 12),16); if (h  > 127) h  = 0;
            int mi = Integer.parseInt(ts.substring(12, 14),16); if (mi > 127) mi = 0;
            if (y < 2000 || y > 2100 || mo < 1 || mo > 12) return null;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(y, mo - 1, d, h, mi, 0); cal.set(java.util.Calendar.MILLISECOND, 0);
            cal.add(java.util.Calendar.MINUTE, capturePeriodMin);
            return cal.getTime();
        } catch (Exception ignored) { return null; }
    }

    private void abortPendingBlockTransfer(UsbSerialPort port) {
        lpDeadlineMs = 0;
        try {
            int addrOff = 0xff & bytAddMode;
            nPkt[2] = (byte)(addrOff + 19);
            nRetLSH = (byte)(0xff & ((int)nRecvCntr << 5)); nPkt[addrOff + 5] = (byte)((int)nRetLSH | 16);
            nRetLSH = (byte)((int)nSentCntr << 1); nPkt[addrOff + 5] = (byte)((int)nRetLSH | (int)nPkt[addrOff + 5]);
            int wp = addrOff + 8;
            nPkt[wp++]=(byte)0xE6; nPkt[wp++]=(byte)0xE7; nPkt[wp++]=(byte)0x00;
            nPkt[wp++]=(byte)0xC0; nPkt[wp++]=(byte)0x02; nPkt[wp++]=(byte)0x81; nPkt[wp++]=(byte)0x00; nPkt[wp++]=(byte)0x00;
            nPkt[wp++]=(byte)0x00; nPkt[wp++]=(byte)0x00; nPkt[wp++]=(byte)0x00; nPkt[wp++]=(byte)0x00;
            int abortEnd = wp;
            fcs(nPkt, addrOff + 5, (byte) 1); fcs(nPkt, abortEnd - 1, (byte) 1); nPkt[abortEnd + 2] = (byte) 0x7E;
            int abortLen = abortEnd + 3;
            ClearBuffer(); byte[] cmd = new byte[abortLen]; for (int i = 0; i < abortLen; i++) cmd[i] = (byte)(nPkt[i] & 0xff);
            SendPkt(port, cmd, abortLen);
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            DataReceive(port); appendLog("ABORT_BLOCK_TRANSFER nCounter=" + nCounter); ClearBuffer();
        } catch (Exception ex) { appendLog("ABORT_BLOCK_TRANSFER_EX: " + ex.getMessage()); }
    }

    private StringBuilder GetParameter_LS(UsbSerialPort port, byte nClassID, String sOBISCode,
            byte nAttribID, int nWait, byte nTryCount, byte nTimeOut,
            boolean isDLM, StringBuilder strbldDLMdata) {
        StringBuilder SbData = new StringBuilder(); final int addrOff = 0xff & bytAddMode; lastGplsResult = 0;
        try {
            nPkt[2] = (byte)(addrOff + 25);
            nRetLSH = (byte)(0xff & ((int)nRecvCntr << 5)); nPkt[addrOff + 5] = (byte)((int)nRetLSH | 16);
            nRetLSH = (byte)((int)nSentCntr << 1); nPkt[addrOff + 5] = (byte)((int)nRetLSH | (int)nPkt[addrOff + 5]);
            int wi = addrOff + 8;
            nPkt[wi++]=(byte)0xE6; nPkt[wi++]=(byte)0xE7; nPkt[wi++]=(byte)0x00;
            nPkt[wi++]=(byte)0xC0; nPkt[wi++]=(byte)0x01; nPkt[wi++]=(byte)0x81; nPkt[wi++]=(byte)0x00; nPkt[wi++]=(byte)(0xff & nClassID);
            byte[] obisBytes = hexStringToByteArray(sOBISCode.substring(0, 12));
            for (int i = 0; i < 6; i++) nPkt[wi++] = obisBytes[i];
            nPkt[wi++] = nAttribID; nPkt[wi++] = (byte)0x00;
            int payloadEnd = wi;
            fcs(nPkt, addrOff + 5, (byte)1); fcs(nPkt, payloadEnd - 1, (byte)1); nPkt[payloadEnd + 2] = (byte)0x7E;
            int sendLen = payloadEnd + 3;
            if (isDLM) { String clsHex = Integer.toHexString(0xff & nClassID); if (clsHex.length()==1) strbldDLMdata.append("\r\n000").append(clsHex).append(" ").append(sOBISCode).append(" 0").append(nAttribID).append(" "); else strbldDLMdata.append("\r\n00").append(clsHex).append(" ").append(sOBISCode).append(" 0").append(nAttribID).append(" "); }
            byte retries = 0; boolean gotResponse = false;
            do {
                drainPort(port); byte[] sendCmd = new byte[sendLen]; for (int i = 0; i < sendLen; i++) sendCmd[i] = (byte)(nPkt[i] & 0xff);
                SendPkt(port, sendCmd, sendLen); long tStart = System.currentTimeMillis(); long attemptDeadline = tStart + ((int)nTimeOut * 1000L);
                if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) { appendLog("GPLS_ABORT OBIS=" + sOBISCode); SbData.append(strbldDLMdata); return SbData; }
                ClearBuffer(); gotResponse = receiveFrame(port, attemptDeadline);
                if (gotResponse) { retries = 0; FrameType(); } else { appendLog("GPLS_TIMEOUT OBIS=" + sOBISCode + " retry=" + retries); retries++; }
            } while (!gotResponse && (int)retries < (int)nTryCount);
            appendLog("GPLS OBIS=" + sOBISCode + " attr=" + nAttribID + " got=" + gotResponse + " nCounter=" + nCounter);
            if (!gotResponse) { lastGplsResult = 2; SbData.append(strbldDLMdata); return SbData; }
            if ((nRcvPkt[addrOff + 5] & 0xff) == 0x97 || ((nRcvPkt[addrOff + 5] & 0xff) & 1) == 1) { appendLog("GPLS_ERROR_FRAME OBIS=" + sOBISCode); lastGplsResult = 2; SbData.append(strbldDLMdata); return SbData; }
            final int MAX_HDLC_FRAMES = 64; int hdlcFrameCount = 0;
            while (((nRcvPkt[1] & 0xff) & 0xA8) == 0xA8 && hdlcFrameCount < MAX_HDLC_FRAMES) {
                hdlcFrameCount++; appendLog("GPLS_HDLC_SEG frame=" + hdlcFrameCount);
                if (!sendRR(port, addrOff, nTimeOut, nTryCount)) { appendLog("GPLS_HDLC_SEG_FAIL frame=" + hdlcFrameCount); break; }
                for (int i = addrOff + 8; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));
            }
            boolean moreDlmsBlocks = false; long dlmsBlockNum = 1;
            if ((nRcvPkt[addrOff + 11] & 0xff) == 0xC4 && (nRcvPkt[addrOff + 12] & 0xff) == 0x02) {
                boolean lastBlock = IntToBool(0xff & nRcvPkt[addrOff + 14]); moreDlmsBlocks = !lastBlock; dlmsBlockNum = decodeBlockNum(addrOff);
                for (int i = addrOff + 19; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));
                appendLog("GPLS_FIRST_BLOCK lastBlock=" + lastBlock + " blockNum=" + dlmsBlockNum);
            } else if ((nRcvPkt[addrOff + 11] & 0xff) == 0xC4 && (nRcvPkt[addrOff + 12] & 0xff) == 0x01) {
                int resultByte = nRcvPkt[addrOff + 14] & 0xff;
                appendLog("GPLS_NORMAL result=" + resultByte + " pktLen=" + pktLength);
                if (resultByte == 0x00) {
                    int typeTag = (addrOff + 20 < pktLength) ? (nRcvPkt[addrOff + 20] & 0xff) : 0;
                    if (typeTag == 130) { for (int i = addrOff + 23; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
                    else if (typeTag == 129) { for (int i = addrOff + 22; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
                    else { for (int i = addrOff + 15; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
                } else { lastGplsResult = 1; appendLog("GPLS_ACCESS_ERROR OBIS=" + sOBISCode); }
            } else { lastGplsResult = 2; appendLog("GPLS_UNKNOWN_FRAME OBIS=" + sOBISCode); }
            final int MAX_DLMS_BLOCKS = 512; int dlmsBlockCount = 0;
            while (moreDlmsBlocks && dlmsBlockCount < MAX_DLMS_BLOCKS) {
                dlmsBlockCount++; if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) { appendLog("GPLS_BLOCK_ABORT block=" + dlmsBlockNum); break; }
                dlmsBlockNum++; appendLog("GPLS_GETBLOCK req=" + dlmsBlockNum);
                nPkt[2] = (byte)(addrOff + 19);
                nRetLSH = (byte)(0xff & ((int)nRecvCntr << 5)); nPkt[addrOff + 5] = (byte)((int)nRetLSH | 16);
                nRetLSH = (byte)((int)nSentCntr << 1); nPkt[addrOff + 5] = (byte)((int)nRetLSH | (int)nPkt[addrOff + 5]);
                int wp2 = addrOff + 8;
                nPkt[wp2++]=(byte)0xE6; nPkt[wp2++]=(byte)0xE7; nPkt[wp2++]=(byte)0x00;
                nPkt[wp2++]=(byte)0xC0; nPkt[wp2++]=(byte)0x02; nPkt[wp2++]=(byte)0x81; nPkt[wp2++]=(byte)0x00; nPkt[wp2++]=(byte)0x00;
                nPkt[wp2++]=(byte)((dlmsBlockNum>>24)&0xFF); nPkt[wp2++]=(byte)((dlmsBlockNum>>16)&0xFF); nPkt[wp2++]=(byte)((dlmsBlockNum>>8)&0xFF); nPkt[wp2++]=(byte)(dlmsBlockNum&0xFF);
                int gbEnd = wp2; fcs(nPkt, addrOff + 5, (byte)1); fcs(nPkt, gbEnd - 1, (byte)1); nPkt[gbEnd + 2] = (byte)0x7E; int gbSendLen = gbEnd + 3;
                ClearBuffer(); byte[] gbCmd = new byte[gbSendLen]; for (int i = 0; i < gbSendLen; i++) gbCmd[i] = (byte)(nPkt[i] & 0xff);
                SendPkt(port, gbCmd, gbSendLen);
                boolean blockOk = false; byte blockRetries = 0; long tStart = System.currentTimeMillis();
                do {
                    do {
                        if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) { moreDlmsBlocks = false; blockOk = true; break; }
                        DataReceive(port, 20);
                        if (nCounter > 2) { int pLen = parsePktLen(); if (pLen > 0 && (pLen+2) <= nCounter && (pLen+1) < nRcvPkt.length && (nRcvPkt[pLen+1]&0xff)==0x7E && fcs(nRcvPkt, pLen, (byte)0)) { pktLength = pLen; blockOk = true; blockRetries = 0; FrameType(); break; } }
                        long elapsed = System.currentTimeMillis() - tStart;
                        if (elapsed/1000 > (int)nTimeOut && (int)blockRetries < (int)nTryCount) { blockRetries++; tStart = System.currentTimeMillis(); break; }
                    } while ((int)blockRetries != (int)nTryCount);
                } while (!blockOk && (int)blockRetries != (int)nTryCount);
                if (!blockOk) { appendLog("GPLS_BLOCK_FAILED block=" + dlmsBlockNum); break; }
                if (!moreDlmsBlocks) break;
                if ((nRcvPkt[addrOff+5]&0xff)==0x97 || ((nRcvPkt[addrOff+5]&0xff)&1)==1) { appendLog("GPLS_BLOCK_ERR_FRAME"); break; }
                if ((nRcvPkt[addrOff+11]&0xff)!=0xC4 || (nRcvPkt[addrOff+12]&0xff)!=0x02) { appendLog("GPLS_BLOCK_UNEXPECTED"); break; }
                boolean lastBlock = IntToBool(0xff & nRcvPkt[addrOff + 14]); long receivedBlockNum = decodeBlockNum(addrOff); moreDlmsBlocks = !lastBlock;
                appendLog("GPLS_BLOCK_RX num=" + receivedBlockNum + " last=" + lastBlock);
                for (int i = addrOff + 19; i < pktLength - 1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i]));
                int blockHdlc = 0;
                while (((nRcvPkt[1]&0xff)&0xA8)==0xA8 && blockHdlc < MAX_HDLC_FRAMES) { blockHdlc++; if (!sendRR(port, addrOff, nTimeOut, nTryCount)) break; for (int i = addrOff+8; i < pktLength-1; i++) strbldDLMdata.append(Hex2Digit(nRcvPkt[i])); }
            }
            appendLog("GPLS_DONE OBIS=" + sOBISCode + " dlmsBlocks=" + dlmsBlockCount + " totalLen=" + strbldDLMdata.length());
            SbData.append(strbldDLMdata); return SbData;
        } catch (Exception ex) { appendLog("GPLS_EX OBIS=" + sOBISCode + ": " + ex.getMessage()); SbData.append(strbldDLMdata); return SbData; }
    }

    // =========================================================================
    // ReadLoadSurveyData (full version — matches Reading.java exactly)
    // =========================================================================

    private StringBuilder ReadLoadSurveyData(UsbSerialPort port, int lsDays) {
        StringBuilder strbldDLMdata = new StringBuilder(); StringBuilder DLMdata;
        appendLog("RLS_ENTER lsDays=" + lsDays + " intProfilePd=" + intProfilePd);

        DLMdata = ReadScalarUnit("BLOCKLOAD", port);
        if (DLMdata != null && !DLMdata.toString().isEmpty()) strbldDLMdata.append(DLMdata);

        if (lastGplsResult == 2) {
            appendLog("RLS_SCALER_SESSION_DROP — re-establishing before LP reads");
            try {
                AddressInit(); boolean nrmOk = SetNRM(port, bytWait, (byte)2, bytTimOut); appendLog("RLS_SCALER_RECOVER_NRM=" + nrmOk);
                if (nrmOk) { int aarqRes = AARQ(port, (byte)1, currentMeterMake.password, bytWait, (byte)3, bytTimOut); appendLog("RLS_SCALER_RECOVER_AARQ=" + aarqRes); if (aarqRes == 0) { drainPort(port); lastGplsResult = 0; } }
            } catch (Exception recEx) { appendLog("RLS_SCALER_RECOVER_EX: " + recEx.getMessage()); }
        }

        appendLog("RLS_CALL attr=3 (capture_objects)");
        StringBuilder l3 = new StringBuilder();
        DLMdata = GetParameter(port, (byte)7, "0100630100FF", (byte)3, bytWait, (byte)1, bytTimOut, true, l3);
        appendLog("RLS_RET attr=3 len=" + (DLMdata == null ? -1 : DLMdata.length()) + " result=" + lastGplsResult);
        if (lastGplsResult == 0 && DLMdata != null && DLMdata.length() > 23) {
            strbldDLMdata.append(DLMdata); appendLog("RLS_ATTR3_FROM_METER len=" + DLMdata.length());
        } else {
            strbldDLMdata.append("\r\n0007 0100630100FF 03 ").append(getCaptureObjectsForMake());
            appendLog("RLS_ATTR3_HARDCODED make=" + currentMeterMake.displayName);
        }

        boolean lpIncompatible = false;
        if (lastGplsResult == 2) {
            appendLog("RLS_ATTR3_DM — re-establishing for remaining reads");
            try {
                AddressInit(); boolean nrmOk = SetNRM(port, bytWait, (byte)2, bytTimOut); appendLog("RLS_ATTR3_RECOVER_NRM=" + nrmOk);
                if (nrmOk) { int aarqRes = AARQ(port, (byte)1, currentMeterMake.password, bytWait, (byte)3, bytTimOut); appendLog("RLS_ATTR3_RECOVER_AARQ=" + aarqRes); if (aarqRes == 0) { drainPort(port); lastGplsResult = 0; } else { lpIncompatible = true; } } else { lpIncompatible = true; }
            } catch (Exception reEx) { appendLog("RLS_ATTR3_RECOVER_EX: " + reEx.getMessage()); lpIncompatible = true; }
        }
        if (lpIncompatible) {
            appendLog("RLS_SKIP_ALL (lpIncompatible=true)");
            strbldDLMdata.append("\r\n0007 0100630100FF 07 0600000000");
            return strbldDLMdata;
        }

        appendLog("RLS_CALL attr=4 (capture_period_seconds)");
        StringBuilder attr4Sb = new StringBuilder();
        DLMdata = GetParameter(port, (byte)7, "0100630100FF", (byte)4, bytWait, (byte)1, bytTimOut, false, attr4Sb);
        appendLog("RLS_RET attr=4 len=" + (DLMdata == null ? -1 : DLMdata.length()));
        int capturePeriodMin = intProfilePd > 0 ? intProfilePd : 30;
        if (lastGplsResult == 0) {
            try { String raw = attr4Sb.toString(); if (raw.length() >= 8) { long seconds = Long.parseLong(raw.substring(raw.length() - 8), 16); if (seconds > 0 && seconds <= 86400) { capturePeriodMin = (int)(seconds / 60); appendLog("RLS_ATTR4_SECONDS=" + seconds); } } } catch (Exception ignored) {}
        }
        if (capturePeriodMin > 0) intProfilePd = capturePeriodMin;
        appendLog("RLS_CAPTURE_PERIOD_MIN=" + capturePeriodMin);

        appendLog("RLS_CALL attr=7 (entries_in_use)");
        StringBuilder attr7Sb = new StringBuilder();
        DLMdata = GetParameter(port, (byte)7, "0100630100FF", (byte)7, bytWait, (byte)1, bytTimOut, false, attr7Sb);
        appendLog("RLS_RET attr=7 len=" + (DLMdata == null ? -1 : DLMdata.length()));
        int entriesInUse = 0;
        if (lastGplsResult == 0) { try { String raw = attr7Sb.toString(); if (raw.length() >= 8) entriesInUse = (int)Long.parseLong(raw.substring(raw.length() - 8), 16); } catch (Exception ignored) {} }
        appendLog("RLS_ENTRIES_IN_USE=" + entriesInUse);

        int recPerDay = (capturePeriodMin > 0) ? (24 * 60 / capturePeriodMin) : 48;
        int profileEntriesMax = 0;
        appendLog("RLS_CALL attr=8 (profile_entries_max)");
        DLMdata = GetParameter(port, (byte)7, "0100630100FF", (byte)8, bytWait, (byte)1, bytTimOut, true, strbldDLMdata);
        appendLog("RLS_RET attr=8 len=" + (DLMdata == null ? -1 : DLMdata.length()));
        if (lastGplsResult == 0 && DLMdata != null && !DLMdata.toString().isEmpty()) {
            try { String raw = DLMdata.toString().trim(); if (raw.length() >= 2) { int t8 = Integer.parseInt(raw.substring(0, 2), 16); if (t8 == 0x12 && raw.length() >= 6) profileEntriesMax = Integer.parseInt(raw.substring(2, 6), 16); else if (t8 == 0x06 && raw.length() >= 10) profileEntriesMax = (int)Long.parseLong(raw.substring(2, 10), 16); } } catch (Exception ignored) {}
        }

        int maxDaysFromMeter;
        if (entriesInUse > 0 && recPerDay > 0) { maxDaysFromMeter = Math.max(1, (entriesInUse / recPerDay) + 1); appendLog("RLS_DAYS_FROM_EIU: entriesInUse=" + entriesInUse + " recPerDay=" + recPerDay + " → lsDays=" + maxDaysFromMeter); }
        else if (profileEntriesMax > 0 && recPerDay > 0) { maxDaysFromMeter = Math.min(lsDays, profileEntriesMax / recPerDay); appendLog("RLS_DAYS_FROM_ATTR8: profileEntriesMax=" + profileEntriesMax + " → lsDays=" + maxDaysFromMeter); }
        else { maxDaysFromMeter = lsDays; appendLog("RLS_DAYS_FALLBACK → lsDays=" + maxDaysFromMeter); }
        final int MAX_LP_DAYS = 35;
        if (maxDaysFromMeter > MAX_LP_DAYS) { appendLog("RLS_DAYS_CAPPED from=" + maxDaysFromMeter + " to=" + MAX_LP_DAYS); maxDaysFromMeter = MAX_LP_DAYS; }
        lsDays = maxDaysFromMeter;
        appendLog("RLS_RECORDS_NEEDED=" + (recPerDay * lsDays) + " recPerDay=" + recPerDay + " lsDays=" + lsDays);

        boolean bulkReadOk = false; boolean bulkCausedDM = false;
        java.util.List<String> lpPageHexList = new java.util.ArrayList<>();
        int[] lpEntriesDeclared = {entriesInUse};
        java.util.HashSet<String> seenPayloads = new java.util.HashSet<>();
        int totalActualRecords = 0;

        boolean tryBulk = (currentMeterMake == MeterMake.GENUS || currentMeterMake == MeterMake.AVON);
        if (tryBulk) {
            appendLog("RLS_CALL attr=2 bulk (Genus/AVON only)");
            StringBuilder attr2Sb = new StringBuilder(); long attr2Start = System.currentTimeMillis();
            DLMdata = GetParameter_LS(port, (byte)7, "0100630100FF", (byte)2, bytWait, bytTryCnt, bytTimOut, false, attr2Sb);
            long attr2Elapsed = System.currentTimeMillis() - attr2Start;
            appendLog("RLS_RET attr=2 elapsed=" + attr2Elapsed + "ms len=" + (DLMdata == null ? -1 : DLMdata.length()) + " result=" + lastGplsResult);
            bulkReadOk = (DLMdata != null && DLMdata.length() > 30 && lastGplsResult == 0);
            bulkCausedDM = (lastGplsResult == 2);
            appendLog("RLS_BULK_CHECK ok=" + bulkReadOk + " causedDM=" + bulkCausedDM);
        } else { appendLog("RLS_BULK_SKIP make=" + currentMeterMake.displayName); }

        if (bulkReadOk) {
            String lpHex = DLMdata.toString();
            try { byte[] lpBytes = hexStringToByteArray(lpHex.length() > 1 ? lpHex : ""); for (int bi = 0; bi < Math.min(16, lpBytes.length - 2); bi++) { if ((lpBytes[bi]&0xff)==0x82) { int declared = ((lpBytes[bi+1]&0xff)<<8)|(lpBytes[bi+2]&0xff); if (declared > 0) { lpEntriesDeclared[0] = declared; break; } } } } catch (Exception ignored) {}
            int cnt = countLoadProfileRecords(lpHex); totalActualRecords = cnt; lpPageHexList.add(lpHex);
            appendLog("RLS_BULK_OK len=" + lpHex.length() + " declared=" + lpEntriesDeclared[0] + " actualRecords=" + cnt);
            boolean partialBulk = (lpEntriesDeclared[0] > cnt && cnt > 0);
            if (partialBulk) {
                appendLog("RLS_PARTIAL_BULK declared=" + lpEntriesDeclared[0] + " received=" + cnt + " — trying selective");
                abortPendingBlockTransfer(port); drainPort(port);
                Calendar pcal = Calendar.getInstance(); int selOk = 0;
                for (int i = Math.min(lsDays, 7); i >= 0; i--) {
                    if (abortRequested) break;
                    if (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs) break;
                    pcal = Calendar.getInstance(); pcal.add(Calendar.DATE, -i); pcal.set(Calendar.HOUR_OF_DAY, 0); pcal.set(Calendar.MINUTE, 0); pcal.set(Calendar.SECOND, 0); pcal.set(Calendar.MILLISECOND, 0);
                    java.util.Date dayDate = pcal.getTime();
                    DLMdata = GetParameterSelective(port, (byte)7, "0100630100FF", (byte)2, bytWait, bytTryCnt, bytTimOut, false, dayDate, dayDate, capturePeriodMin, new StringBuilder());
                    if (DLMdata != null && !DLMdata.toString().isEmpty()) {
                        String selHex = DLMdata.toString().trim();
                        if (!hasLoadProfileRecords(selHex)) continue; if (seenPayloads.contains(selHex)) continue;
                        if (lpHex.contains(selHex.substring(0, Math.min(40, selHex.length())))) continue;
                        seenPayloads.add(selHex); int selCnt = countLoadProfileRecords(selHex); totalActualRecords += selCnt; lpPageHexList.add(selHex); selOk++;
                        appendLog("RLS_PARTIAL_SEL day=-" + i + " records=" + selCnt);
                    }
                }
                if (selOk > 0) appendLog("RLS_PARTIAL_SUPPLEMENT added " + selOk + " selective days, totalRecords=" + totalActualRecords);
            }
        }

        if (bulkCausedDM) {
            appendLog("RLS_BULK_DM — re-establishing for selective reads");
            try {
                AddressInit(); boolean nrmOk = SetNRM(port, bytWait, (byte)2, bytTimOut); appendLog("RLS_BULK_RECOVER_NRM=" + nrmOk);
                if (nrmOk) { int aarqRes = AARQ(port, (byte)1, currentMeterMake.password, bytWait, (byte)3, bytTimOut); appendLog("RLS_BULK_RECOVER_AARQ=" + aarqRes); if (aarqRes == 0) { drainPort(port); lastGplsResult = 0; } }
            } catch (Exception bulkEx) { appendLog("RLS_BULK_RECOVER_EX: " + bulkEx.getMessage()); }
        }

        if (!bulkReadOk) {
            appendLog("RLS_BULK_FAILED — falling back to selective read for " + lsDays + " days");
            boolean probeHadData = false; boolean bulkFallbackUsed = false;
            if (entriesInUse == 0) {
                if (abortRequested) {
                    appendLog("RLS_PROBE_SKIPPED_ABORT");
                } else {
                    appendLog("RLS_PROBE_EMPTY_METER — probing recent days before skipping");
                    for (int probeDayOffset = 0; probeDayOffset >= -3 && !probeHadData; probeDayOffset--) {
                        java.util.Calendar probeCal = java.util.Calendar.getInstance();
                        probeCal.add(java.util.Calendar.DAY_OF_YEAR, probeDayOffset);
                        probeCal.set(java.util.Calendar.HOUR_OF_DAY, 0); probeCal.set(java.util.Calendar.MINUTE, 0); probeCal.set(java.util.Calendar.SECOND, 0); probeCal.set(java.util.Calendar.MILLISECOND, 0);
                        java.util.Date probeDate = probeCal.getTime();
                        appendLog("RLS_PROBE_DAY offset=" + probeDayOffset);
                        DLMdata = GetParameterSelective(port, (byte)7, "0100630100FF", (byte)2, bytWait, (byte)1, bytTimOut, false, probeDate, probeDate, capturePeriodMin, new StringBuilder());
                        if (DLMdata != null && !DLMdata.toString().isEmpty()) {
                            String probeHex = DLMdata.toString().trim();
                            if (hasLoadProfileRecords(probeHex)) { probeHadData = true; int probeCnt = countLoadProfileRecords(probeHex); totalActualRecords += probeCnt; lpPageHexList.add(probeHex); seenPayloads.add(probeHex); appendLog("RLS_PROBE_HAS_DATA offset=" + probeDayOffset + " records=" + probeCnt); }
                        }
                    }
                    if (!probeHadData) {
                        appendLog("RLS_PROBE_CONFIRMED_EMPTY — no LP data in last 3 days via selective");
                        if (!abortRequested && currentMeterMake != MeterMake.GENUS && currentMeterMake != MeterMake.AVON) {
                            appendLog("RLS_BULK_FALLBACK_PROBE — attempting direct attr=2 bulk read");
                            StringBuilder bulkProbeSb = new StringBuilder();
                            DLMdata = GetParameter_LS(port, (byte)7, "0100630100FF", (byte)2, bytWait, (byte)1, bytTimOut, false, bulkProbeSb);
                            if (DLMdata != null && DLMdata.length() > 30) {
                                String bulkHex = DLMdata.toString().trim();
                                if (hasLoadProfileRecords(bulkHex)) { int bulkCnt = countLoadProfileRecords(bulkHex); probeHadData = true; bulkFallbackUsed = true; totalActualRecords += bulkCnt; lpPageHexList.add(bulkHex); seenPayloads.add(bulkHex); appendLog("RLS_BULK_FALLBACK_HAS_DATA records=" + bulkCnt); }
                                else { appendLog("RLS_BULK_FALLBACK_EMPTY"); }
                            } else { appendLog("RLS_BULK_FALLBACK_NO_RESPONSE"); }
                        }
                        if (!probeHadData) appendLog("RLS_SEL_SKIP_ALL — LP confirmed empty by both selective and bulk probe");
                    }
                }
            }

            if ((entriesInUse > 0 || probeHadData) && !bulkFallbackUsed) {
                Calendar cal = Calendar.getInstance(); int selectiveOk = 0; int targetRecords = recPerDay * lsDays;
                int etaSecs = lsDays * 16; String etaStr = etaSecs < 60 ? etaSecs + "s" : (etaSecs/60) + "m " + (etaSecs%60) + "s";
                appendLog("LP: reading " + lsDays + " days | Target ~" + targetRecords + " records | ETA ~" + etaStr);
                for (int i = lsDays; i >= 0; i--) {
                    if (abortRequested) { appendLog("RLS_SEL_ABORT at day=" + i); break; }
                    if (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs) { appendLog("RLS_SEL_DEADLINE_HIT at day=" + i); break; }
                    cal = Calendar.getInstance(); cal.add(Calendar.DATE, -i);
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
                    java.util.Date dayDate = cal.getTime();
                    DLMdata = GetParameterSelective(port, (byte)7, "0100630100FF", (byte)2, bytWait, bytTryCnt, bytTimOut, false, dayDate, dayDate, capturePeriodMin, new StringBuilder());
                    if (DLMdata != null && !DLMdata.toString().isEmpty()) {
                        String lpHex = DLMdata.toString().trim();
                        if (!hasLoadProfileRecords(lpHex)) { appendLog("RLS_SEL_DAY day=-" + i + " EMPTY_SKIPPED len=" + lpHex.length()); continue; }
                        if (seenPayloads.contains(lpHex)) { appendLog("RLS_SEL_DAY day=-" + i + " DUPLICATE_SKIPPED"); continue; }
                        seenPayloads.add(lpHex);
                        if (entriesInUse > 0 && totalActualRecords > (int)(entriesInUse * 1.2)) { appendLog("RLS_EARLY_STOP totalRecords=" + totalActualRecords + " > entriesInUse×1.2=" + (int)(entriesInUse * 1.2)); break; }
                        if (lpEntriesDeclared[0] == 0) { try { byte[] lpBytes = hexStringToByteArray(lpHex); for (int bi = 0; bi < Math.min(16, lpBytes.length - 2); bi++) { if ((lpBytes[bi]&0xff)==0x82) { int declared = ((lpBytes[bi+1]&0xff)<<8)|(lpBytes[bi+2]&0xff); if (declared > 0) { lpEntriesDeclared[0] = declared; break; } } } } catch (Exception ignored) {} }
                        int cnt = countLoadProfileRecords(lpHex); totalActualRecords += cnt;
                        if (capturePeriodMin == 30 && cnt >= 2) {
                            try {
                                int p1 = lpHex.indexOf("0212090c"); int p2 = lpHex.indexOf("0212090c", p1 + 8);
                                if (p1 >= 0 && p2 >= 0) {
                                    String ts1 = lpHex.substring(p1+8,p1+32); String ts2 = lpHex.substring(p2+8,p2+32);
                                    int h1=Integer.parseInt(ts1.substring(10,12),16); int m1=Integer.parseInt(ts1.substring(12,14),16);
                                    int h2=Integer.parseInt(ts2.substring(10,12),16); int m2=Integer.parseInt(ts2.substring(12,14),16);
                                    int diff=(h2*60+m2)-(h1*60+m1);
                                    if (diff==15||diff==30||diff==60) { if(diff!=capturePeriodMin){capturePeriodMin=diff;intProfilePd=diff;appendLog("RLS_INTERVAL_DETECTED="+diff+"min from timestamps");} }
                                }
                            } catch (Exception ignored) {}
                        }
                        lpPageHexList.add(lpHex); selectiveOk++;
                        appendLog("RLS_SEL_DAY day=-" + i + " len=" + lpHex.length() + " records=" + cnt);
                        int maxPageRetries = 6; int pagesDone = 0;
                        while (cnt > 0 && cnt < recPerDay && pagesDone < maxPageRetries) {
                            if (abortRequested) break;
                            if (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs) { appendLog("RLS_PAGE_DEADLINE day=-" + i); break; }
                            java.util.Date nextStart = extractLastLpTimestamp(lpHex, capturePeriodMin);
                            if (nextStart == null) { appendLog("RLS_PAGE_NO_LAST_TS day=-" + i); break; }
                            appendLog("RLS_PAGE_CONT day=-" + i + " page=" + (pagesDone+1) + " nextStart=" + nextStart);
                            DLMdata = GetParameterSelective(port, (byte)7, "0100630100FF", (byte)2, bytWait, bytTryCnt, bytTimOut, false, nextStart, dayDate, capturePeriodMin, new StringBuilder());
                            if (DLMdata == null || DLMdata.toString().isEmpty()) break;
                            String pageHex = DLMdata.toString().trim();
                            if (!hasLoadProfileRecords(pageHex)) break; if (seenPayloads.contains(pageHex)) break;
                            seenPayloads.add(pageHex); int pageCnt = countLoadProfileRecords(pageHex); if (pageCnt == 0) break;
                            totalActualRecords += pageCnt; cnt += pageCnt; lpHex = pageHex; lpPageHexList.add(pageHex); pagesDone++;
                            appendLog("RLS_PAGE_DONE day=-" + i + " page=" + pagesDone + " pageCnt=" + pageCnt + " dayTotal=" + cnt);
                        }
                        appendLog("LP Day " + (lsDays-i+1) + "/" + (lsDays+1) + " | " + totalActualRecords + " of ~" + targetRecords + " records received");
                    } else { appendLog("RLS_SEL_DAY day=-" + i + " NO_DATA"); }
                }
                appendLog("RLS_SEL_DONE daysWithData=" + selectiveOk + " totalRecords=" + totalActualRecords);
            }
        }

        int attr3Value = lpEntriesDeclared[0] > 0 ? lpEntriesDeclared[0] : totalActualRecords;
        strbldDLMdata.append("\r\n0007 0100630100FF 07 06").append(String.format("%08x", attr3Value));
        appendLog("RLS_ATTR7_WRITTEN entries_in_use=" + attr3Value);
        if (!lpPageHexList.isEmpty()) {
            String mergedLp = mergeLpPageHexList(lpPageHexList);
            if (mergedLp != null) { strbldDLMdata.append("\r\n0007 0100630100FF 02 ").append(mergedLp); appendLog("RLS_LP_MERGED pages=" + lpPageHexList.size() + " totalRecords=" + totalActualRecords); }
        }
        return strbldDLMdata;
    }

    // =========================================================================
    // GetParameterSelective
    // =========================================================================

    private StringBuilder GetParameterSelective(UsbSerialPort port, byte nClassID,
            String sOBIS, byte nAttr, int nWait, byte nTryCount, byte nTimeOut,
            boolean isDLM, Date dS, Date dE, int profPd, StringBuilder outSb) {
        StringBuilder SbData = new StringBuilder(); lastGplsResult = 0;
        try {
            boolean flag1=false; long num1=0L; outSb=new StringBuilder();
            nPkt[2]=(byte)((int)bytAddMode+76);
            nRetLSH=(byte)((int)nRecvCntr<<5); nPkt[(0xff&bytAddMode)+5]=(byte)((int)nRetLSH|16);
            nRetLSH=(byte)((int)nSentCntr<<1); nPkt[(0xff&bytAddMode)+5]=(byte)((int)nRetLSH|(int)nPkt[(0xff&bytAddMode)+5]);
            int wi=(int)(0xff&((byte)((int)bytAddMode+8)));
            nPkt[wi]=(byte)230;wi++; nPkt[wi]=(byte)230;wi++; nPkt[wi]=(byte)0;wi++;
            nPkt[wi]=(byte)192;wi++; nPkt[wi]=(byte)1;wi++; nPkt[wi]=(byte)129;wi++; nPkt[wi]=(byte)0;wi++;
            nPkt[wi]=(byte)nClassID;wi++;
            for(byte b:hexStringToByteArray(sOBIS.substring(0,12))){nPkt[wi]=(byte)b;wi++;}
            nPkt[wi]=(byte)nAttr;wi++; nPkt[wi]=(byte)1;wi++; nPkt[wi]=(byte)1;wi++;
            nPkt[wi]=(byte)2;wi++; nPkt[wi]=(byte)4;wi++;
            // Restricting object
            nPkt[wi]=(byte)2;wi++; nPkt[wi]=(byte)4;wi++;
            nPkt[wi]=(byte)18;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)8;wi++;
            nPkt[wi]=(byte)9;wi++; nPkt[wi]=(byte)6;wi++;
            nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)1;wi++;
            nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)255;wi++;
            nPkt[wi]=(byte)15;wi++; nPkt[wi]=(byte)2;wi++;
            nPkt[wi]=(byte)18;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++;
            // from_value
            nPkt[wi]=(byte)9;wi++; nPkt[wi]=(byte)12;wi++;
            int syY=untilGetYear(dS); nPkt[wi]=(byte)(syY/256);wi++; nPkt[wi]=(byte)(0xff&(syY%256));wi++;
            nPkt[wi]=(byte)(untilGetMonth(dS)+1);wi++; nPkt[wi]=(byte)untilGetDate(dS);wi++;
            nPkt[wi]=(byte)255;wi++; nPkt[wi]=(byte)untilGetHours(dS);wi++;
            nPkt[wi]=(byte)(profPd>0?(untilGetMinutes(dS)/profPd)*profPd:0);wi++;
            nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)128;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++;
            // to_value
            nPkt[wi]=(byte)9;wi++; nPkt[wi]=(byte)12;wi++;
            Date sysNow = new Date();
            boolean sd=untilStringToDateOnly(dE).equals(untilStringToDateOnly(sysNow));
            int eyY;
            if(sd){eyY=untilGetYear(dE);nPkt[wi]=(byte)(eyY/256);wi++;nPkt[wi]=(byte)(0xff&(eyY%256));wi++;nPkt[wi]=(byte)(untilGetMonth(dE)+1);wi++;nPkt[wi]=(byte)untilGetDate(dE);wi++;nPkt[wi]=(byte)255;wi++;nPkt[wi]=(byte)untilGetHours(sysNow);wi++;nPkt[wi]=(byte)((untilGetMinutes(sysNow)/profPd)*profPd);wi++;}
            else{Calendar _cal=Calendar.getInstance();_cal.setTime(dE);_cal.add(Calendar.DAY_OF_MONTH,1);Date nd=_cal.getTime();eyY=untilGetYear(nd);nPkt[wi]=(byte)(eyY/256);wi++;nPkt[wi]=(byte)(0xff&(eyY%256));wi++;nPkt[wi]=(byte)(untilGetMonth(nd)+1);wi++;nPkt[wi]=(byte)untilGetDate(nd);wi++;nPkt[wi]=(byte)255;wi++;nPkt[wi]=(byte)0;wi++;nPkt[wi]=(byte)0;wi++;}
            nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)128;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++;
            // entries_from=1, entries_to=0
            nPkt[wi]=(byte)1;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)1;wi++;
            nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++; nPkt[wi]=(byte)0;wi++;
            // selected_columns empty
            nPkt[wi]=(byte)1;wi++; nPkt[wi]=(byte)0;wi++;
            byte n82=(byte)wi;
            fcs(nPkt,(0xff&bytAddMode)+5,(byte)1); fcs(nPkt,(int)(byte)((int)n82-1),(byte)1); nPkt[(int)n82+2]=(byte)126;
            if(isDLM){String ch=Integer.toHexString(nClassID);if(ch.length()==1)outSb.append("\r\n000"+ch+" "+sOBIS+" 0"+nAttr+" ");else outSb.append("\r\n00"+ch+" "+sOBIS+" 0"+nAttr+" ");}
            byte n84=0; boolean flag2;
            do{ClearBuffer();flag2=false;drainPort(port);int ssLen=(0xff&nPkt[2])+3;byte[] cmd=new byte[ssLen];for(int i=0;i<ssLen;i++)cmd[i]=(byte)(nPkt[i]&0xff);SendPkt(port,cmd,ssLen);long sDl=System.currentTimeMillis()+(int)nTimeOut*1000L;ClearBuffer();flag2=receiveFrame(port,sDl);if(flag2){n84=0;FrameType();}else ++n84;}while(!flag2&&(int)n84!=(int)nTryCount);
            if(!flag2){lastGplsResult=2;return SbData;}
            int aO=(int)bytAddMode;
            if((int)nRcvPkt[aO+11]==196&&(int)nRcvPkt[aO+12]==2){num1=decodeBlockNum(aO);flag1=!IntToBool(nRcvPkt[aO+14]);int off=aO+20;if((int)(0xff&nRcvPkt[off])==130){for(int i=aO+31;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}else if((int)(0xff&nRcvPkt[off])==129){for(int i=aO+30;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}else{for(int i=aO+29;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}}else{for(int i=aO+18;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}
            while(((int)(0xff&nRcvPkt[1])&168)==168){nPkt[2]=(byte)((int)bytAddMode+7);nRetLSH=(byte)(0xff&((int)nRecvCntr<<5));nPkt[(0xff&bytAddMode)+5]=(byte)((int)nRetLSH|0x11);fcs(nPkt,(0xff&bytAddMode)+5,(byte)1);nPkt[(0xff&bytAddMode)+8]=(byte)126;byte xr=0;boolean xf;do{ClearBuffer();xf=false;SendPkt(port,nPkt,(byte)((int)bytAddMode+9));long o2=System.currentTimeMillis();do{if(abortRequested||(lpDeadlineMs>0&&System.currentTimeMillis()>lpDeadlineMs)){xf=false;break;}DataReceive(port);String h3=Integer.toHexString((0xff&nRcvPkt[1])&7);if(h3.length()==1)h3="0"+h3;String h4=Integer.toHexString(0xff&nRcvPkt[2]);if(h4.length()==1)h4="0"+h4;pktLength=Integer.parseInt(h3+h4,16);if(nCounter>2&&pktLength+2<=nCounter&&(int)nRcvPkt[pktLength+1]==126&&fcs(nRcvPkt,pktLength,(byte)0)){xf=true;xr=0;break;}if((System.currentTimeMillis()-o2)/1000>(int)nTimeOut&&(int)xr<(int)nTryCount){xr++;break;}}while(!xf);}while(!xf&&(int)xr!=(int)nTryCount);if(!xf)break;if((int)(0xff&nRcvPkt[aO+11])==0xC4&&(int)(0xff&nRcvPkt[aO+12])==0x02){num1=decodeBlockNum(aO);flag1=!IntToBool(0xff&nRcvPkt[aO+14]);int o3=aO+20;if((int)(0xff&nRcvPkt[o3])==130){for(int i=aO+23;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}else if((int)(0xff&nRcvPkt[o3])==129){for(int i=aO+22;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}else{for(int i=aO+21;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}}else{for(int i=aO+8;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}FrameType();if((int)nRcvPkt[1]==160)break;}
            while(flag1){if(abortRequested||(lpDeadlineMs>0&&System.currentTimeMillis()>lpDeadlineMs))break;flag1=false;if(!sendRR(port,aO,nTimeOut,nTryCount)){lastGplsResult=2;break;}if((int)(0xff&nRcvPkt[aO+11])==0xC4&&(int)(0xff&nRcvPkt[aO+12])==0x02){num1=decodeBlockNum(aO);flag1=!IntToBool(0xff&nRcvPkt[aO+14]);int off2=aO+20;if((int)(0xff&nRcvPkt[off2])==130){for(int i=aO+31;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}else if((int)(0xff&nRcvPkt[off2])==129){for(int i=aO+30;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}else{for(int i=aO+29;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}}else{for(int i=aO+18;i<pktLength-1;i++)outSb.append(Hex2Digit(nRcvPkt[i]));}}
            SbData.append(outSb);
        } catch (Exception e) { appendLog("GetParameterSelective ex: "+e.getMessage()); lastGplsResult=2; }
        return SbData;
    }

    // =========================================================================
    // Date utilities
    // =========================================================================

    private static int untilGetYear(Date d)    { Calendar c=Calendar.getInstance();c.setTime(d);return c.get(Calendar.YEAR);}
    private static int untilGetMonth(Date d)   { Calendar c=Calendar.getInstance();c.setTime(d);return c.get(Calendar.MONTH);}
    private static int untilGetDate(Date d)    { Calendar c=Calendar.getInstance();c.setTime(d);return c.get(Calendar.DAY_OF_MONTH);}
    private static int untilGetHours(Date d)   { Calendar c=Calendar.getInstance();c.setTime(d);return c.get(Calendar.HOUR_OF_DAY);}
    private static int untilGetMinutes(Date d) { Calendar c=Calendar.getInstance();c.setTime(d);return c.get(Calendar.MINUTE);}
    private static String untilStringToDateOnly(Date d){return new SimpleDateFormat("yyyyMMdd",Locale.US).format(d);}

    // =========================================================================
    // getCaptureObjectsForMake
    // =========================================================================

    private String getCaptureObjectsForMake() {
        // Genus / AVON — 12-col verified from KT027829 (3-Phase SMART) and KT328698 (3-Phase NET)
        if (currentMeterMake == MeterMake.GENUS || currentMeterMake == MeterMake.AVON) {
            appendLog("CAPTURE_OBJ_MAP make=" + currentMeterMake.displayName + " cols=12 verified KT027829+KT328698");
            return "010C"
                    + "020412000809060000010000FF0F02120000"  //  1: Clock
                    + "0204120003090601001F1B00FF0F02120000"  //  2: IL1 avg
                    + "020412000309060100331B00FF0F02120000"  //  3: IL2 avg
                    + "020412000309060100471B00FF0F02120000"  //  4: IL3 avg
                    + "020412000309060100201B00FF0F02120000"  //  5: UL1 avg
                    + "020412000309060100341B00FF0F02120000"  //  6: UL2 avg
                    + "020412000309060100481B00FF0F02120000"  //  7: UL3 avg
                    + "020412000309060100011D00FF0F02120000"  //  8: Wh+ cumul
                    + "020412000309060100091D00FF0F02120000"  //  9: VAh cumul
                    + "020412000309060100021D00FF0F02120000"  // 10: Wh- cumul
                    + "0204120003090601000A1D00FF0F02120000"  // 11: VAh export cumul
                    + "020412000109060000600A01FF0F02120000"; // 12: Meter status
        }
        // Secure — 18-col verified from live meter SS09079162
        if (currentMeterMake == MeterMake.SECURE) {
            appendLog("CAPTURE_OBJ_MAP make=Secure cols=18 verified SS09079162");
            return "0112"
                    + "020412000809060000010000ff0f02120000"  //  1: Clock
                    + "020412000509060100010400ff0f03120000"  //  2: kW Block Demand (IC=5)
                    + "020412000509060100090400ff0f03120000"  //  3: kVA Block Demand
                    + "020412000509060100050400ff0f03120000"  //  4: kVAr Block Demand
                    + "020412000309060100030700ff0f02120000"  //  5: Reactive Power inst
                    + "020412000309060100010800ff0f02120000"  //  6: kWh Import cumul
                    + "020412000309060100090800ff0f02120000"  //  7: kVAh cumul
                    + "020412000309060100050800ff0f02120000"  //  8: kVArh Q1
                    + "020412000309060100080800ff0f02120000"  //  9: kVArh Q4
                    + "020412000309060100020800ff0f02120000"  // 10: kWh Export cumul
                    + "020412000309060100060800ff0f02120000"  // 11: kVArh Q2
                    + "020412000309060100070800ff0f02120000"  // 12: kVArh Q3
                    + "020412000309060100010600ff0f02120000"  // 13: MD kW
                    + "020412000309060100090600ff0f02120000"  // 14: MD kVA
                    + "0204120003090600005e5b0eff0f02120000"  // 15: Cumul Power On Duration
                    + "020412000109060000600f00ff0f02120000"  // 16: Phase Sequence
                    + "020412000109060000960500ff0f02120000"  // 17: Power Quality Event
                    + "020412000109060000960200ff0f02120000"; // 18: Programming Count
        }
        // HPL — sub-variant auto-detection from logical device name set in ReadNamePlate()
        if (currentMeterMake == MeterMake.HPL) {
            if (hplLogicalDeviceName.contains("CT")) {
                appendLog("CAPTURE_OBJ_MAP make=HPL sub=CT cols=9");
                return "0109"
                        + "020412000809060000010000FF0F02120000"  //  1: Clock
                        + "0204120003090601000C1B00FF0F02120000"  //  2: Avg Voltage
                        + "0204120003090601000B1B00FF0F02120000"  //  3: Avg Current
                        + "020412000309060100011D00FF0F02120000"  //  4: Active Energy Imp
                        + "020412000309060100021D00FF0F02120000"  //  5: Active Energy Exp
                        + "020412000309060100091D00FF0F02120000"  //  6: Apparent Energy Imp
                        + "0204120003090601000A1D00FF0F02120000"  //  7: Apparent Energy Exp
                        + "0204120003090601000D1D00FF0F02120000"  //  8: PF Lag
                        + "020412000309060100011B00FF0F02120000"; //  9: Active Demand Imp
            }
            if (hplLogicalDeviceName.contains("PPEM")) {
                appendLog("CAPTURE_OBJ_MAP make=HPL sub=PPEM cols=15");
                return "010F"
                        + "020412000809060000010000FF0F02120000"  //  1: Clock
                        + "0204120003090601000C1B00FF0F02120000"  //  2: Avg Voltage
                        + "0204120003090601000B1B00FF0F02120000"  //  3: Avg Current
                        + "0204120003090601005B1B00FF0F02120000"  //  4: Neutral Current
                        + "020412000309060100011D00FF0F02120000"  //  5: Active Energy Imp
                        + "020412000309060100011B00FF0F02120000"  //  6: Active Demand Imp
                        + "020412000309060100091D00FF0F02120000"  //  7: Apparent Energy Imp
                        + "020412000309060100091B00FF0F02120000"  //  8: Apparent Demand Imp
                        + "0204120003090601000D1D00FF0F02120000"  //  9: PF Lag
                        + "020412000309060100021D00FF0F02120000"  // 10: Active Energy Exp
                        + "020412000309060100021B00FF0F02120000"  // 11: Active Demand Exp
                        + "0204120003090601000A1D00FF0F02120000"  // 12: Apparent Energy Exp
                        + "0204120003090601000A1B00FF0F02120000"  // 13: Apparent Demand Exp
                        + "0204120003090601000D1D50FF0F02120000"  // 14: PF Total
                        + "020412000309060100051D00FF0F02120000"; // 15: kVArh Q1 lag
            }
            // SPEM or unknown HPL → 14-col default (verified KT331687)
            appendLog("CAPTURE_OBJ_MAP make=HPL sub="
                    + (hplLogicalDeviceName.isEmpty() ? "UNKNOWN→SPEM_default" : hplLogicalDeviceName)
                    + " cols=14");
            return "010E"
                    + "020412000809060000010000FF0F02120000"  //  1: Clock
                    + "0204120003090601000C1B00FF0F02120000"  //  2: Avg Voltage
                    + "0204120003090601000B1B00FF0F02120000"  //  3: Avg Current
                    + "0204120003090601005B1B00FF0F02120000"  //  4: Neutral Current
                    + "020412000309060100011D00FF0F02120000"  //  5: Active Energy Imp
                    + "020412000309060100011B00FF0F02120000"  //  6: Active Demand Imp
                    + "020412000309060100091D00FF0F02120000"  //  7: Apparent Energy Imp
                    + "020412000309060100091B00FF0F02120000"  //  8: Apparent Demand Imp
                    + "0204120003090601000D1D00FF0F02120000"  //  9: PF Lag
                    + "020412000309060100021D00FF0F02120000"  // 10: Active Energy Exp
                    + "020412000309060100021B00FF0F02120000"  // 11: Active Demand Exp
                    + "0204120003090601000A1D00FF0F02120000"  // 12: Apparent Energy Exp
                    + "0204120003090601000A1B00FF0F02120000"  // 13: Apparent Demand Exp
                    + "0204120003090601000D1D50FF0F02120000"; // 14: PF Total
        }
        // Landis+Gyr / L&G — 11-col verified from KT327122
        if (currentMeterMake == MeterMake.LANDIS || currentMeterMake == MeterMake.LNG) {
            appendLog("CAPTURE_OBJ_MAP make=" + currentMeterMake.displayName + " cols=11 verified KT327122");
            return "010B"
                    + "020412000809060000010000FF0F02120000"  //  1: Clock
                    + "0204120003090601001F1B00FF0F02120000"  //  2: IL1 avg
                    + "020412000309060100331B00FF0F02120000"  //  3: IL2 avg
                    + "020412000309060100471B00FF0F02120000"  //  4: IL3 avg
                    + "020412000309060100201B00FF0F02120000"  //  5: UL1 avg
                    + "020412000309060100341B00FF0F02120000"  //  6: UL2 avg
                    + "020412000309060100481B00FF0F02120000"  //  7: UL3 avg
                    + "020412000309060100011D00FF0F02120000"  //  8: Wh+ cumul
                    + "020412000309060100091D00FF0F02120000"  //  9: VAh cumul
                    + "0204120003090601005B1B00FF0F02120000"  // 10: IN neutral avg
                    + "0204120003090601000D1B00FF0F02120000"; // 11: kW demand avg
        }
        // L&T (Schneider NTD) — 11-col MRI-verified 25165449
        if (currentMeterMake == MeterMake.LNT) {
            appendLog("CAPTURE_OBJ_MAP make=LNT cols=11 NTD layout MRI-verified 25165449");
            return "010B"
                    + "020412000809060000010000FF0F02120000"  //  1: Clock
                    + "0204120003090601001F1B00FF0F02120000"  //  2: IL1 avg
                    + "020412000309060100331B00FF0F02120000"  //  3: IL2 avg
                    + "020412000309060100471B00FF0F02120000"  //  4: IL3 avg
                    + "020412000309060100201B00FF0F02120000"  //  5: UL1 avg
                    + "020412000309060100341B00FF0F02120000"  //  6: UL2 avg
                    + "020412000309060100481B00FF0F02120000"  //  7: UL3 avg
                    + "020412000309060100011D00FF0F02120000"  //  8: Wh+ cumul
                    + "020412000309060100091D00FF0F02120000"  //  9: VAh cumul
                    + "020412000309060100021D00FF0F02120000"  // 10: Wh- cumul
                    + "0204120003090601000A1D00FF0F02120000"; // 11: VAh- cumul
        }
        // Fallback — use Secure 18-col layout
        appendLog("CAPTURE_OBJ_MAP make=UNKNOWN using Secure 18-col fallback");
        return "0112"
                + "020412000809060000010000ff0f02120000"
                + "020412000509060100010400ff0f03120000"
                + "020412000509060100090400ff0f03120000"
                + "020412000509060100050400ff0f03120000"
                + "020412000309060100030700ff0f02120000"
                + "020412000309060100010800ff0f02120000"
                + "020412000309060100090800ff0f02120000"
                + "020412000309060100050800ff0f02120000"
                + "020412000309060100080800ff0f02120000"
                + "020412000309060100020800ff0f02120000"
                + "020412000309060100060800ff0f02120000"
                + "020412000309060100070800ff0f02120000"
                + "020412000309060100010600ff0f02120000"
                + "020412000309060100090600ff0f02120000"
                + "0204120003090600005e5b0eff0f02120000"
                + "020412000109060000600f00ff0f02120000"
                + "020412000109060000960500ff0f02120000"
                + "020412000109060000960200ff0f02120000";
    }

    // =========================================================================
    // Post-process / Validate / Bitmap / dlmsDefaultScalerUnit
    // =========================================================================

    // =========================================================================
    // POST-PROCESS HELPERS (copied from Reading.java)
    // =========================================================================

    private java.util.Map<String, int[]> buildScalerMap(String dataUpper) {
        java.util.Map<String, int[]> map = new java.util.HashMap<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "([0-9A-F]{12}) 03 0202 ?0F([0-9A-F]{2})16([0-9A-F]{2})");
        for (String line : dataUpper.split("[\\r\\n]+")) {
            if (line.startsWith("BSCL")) continue;
            java.util.regex.Matcher m = p.matcher(line.replaceAll("\\s+", " "));
            while (m.find()) {
                String obis = m.group(1);
                int sc = Integer.parseInt(m.group(2), 16);
                if (sc > 127) sc -= 256;
                int uc = Integer.parseInt(m.group(3), 16);
                map.put(obis, new int[]{sc, uc});
            }
        }
        return map;
    }

    private java.util.Map<String, int[]> buildBillingScalerMap(String dataUpper) {
        java.util.Map<String, int[]> map = new java.util.HashMap<>();
        String bspCoHex = null, bspValHex = null;
        for (String line : dataUpper.split("[\\r\\n]+")) {
            if (line.contains("01005E5B06FF 03 ") || line.contains("01005E5B06FF03")) {
                int idx = line.indexOf("01005E5B06FF");
                if (idx >= 0) {
                    String rest = line.substring(idx + 12).trim().replaceAll("\\s+", "");
                    if (rest.startsWith("03")) bspCoHex  = rest.substring(2);
                    if (rest.startsWith("02")) bspValHex = rest.substring(2);
                }
            }
            if (line.contains("01005E5B06FF 02 ") || line.contains("01005E5B06FF02")) {
                int idx = line.indexOf("01005E5B06FF");
                if (idx >= 0) {
                    String rest = line.substring(idx + 12).trim().replaceAll("\\s+", "");
                    if (rest.startsWith("02")) bspValHex = rest.substring(2);
                }
            }
        }
        java.util.regex.Matcher mCo = java.util.regex.Pattern.compile(
                "01005E5B06FF\\s+03\\s+([0-9A-F ]+)").matcher(dataUpper);
        if (mCo.find()) bspCoHex = mCo.group(1).replaceAll("\\s+", "");
        java.util.regex.Matcher mVal = java.util.regex.Pattern.compile(
                "01005E5B06FF\\s+02\\s+([0-9A-F ]+)").matcher(dataUpper);
        if (mVal.find()) bspValHex = mVal.group(1).replaceAll("\\s+", "");
        if (bspCoHex != null && bspValHex != null) {
            java.util.List<String> bspObis = new java.util.ArrayList<>();
            for (int p = 0; p + 16 <= bspCoHex.length(); ) {
                if (bspCoHex.substring(p, p+4).equals("0906") && p + 16 <= bspCoHex.length()) {
                    bspObis.add(bspCoHex.substring(p+4, p+16)); p += 16;
                } else { p += 2; }
            }
            try {
                String sv = bspValHex; int p = 0;
                if (p + 4 > sv.length()) throw new Exception("too short");
                int outerTag = Integer.parseInt(sv.substring(p, p+2), 16); p += 2;
                if (outerTag != 0x01) throw new Exception("not array");
                int outerCb = Integer.parseInt(sv.substring(p, p+2), 16); p += 2;
                if ((outerCb & 0x80) != 0) { int nb = outerCb & 0x7F; p += nb*2; }
                int structTag = Integer.parseInt(sv.substring(p, p+2), 16); p += 2;
                if (structTag != 0x02) throw new Exception("not struct");
                int structCb = Integer.parseInt(sv.substring(p, p+2), 16); p += 2;
                int structCnt;
                if ((structCb & 0x80) != 0) { int nb = structCb & 0x7F; structCnt = Integer.parseInt(sv.substring(p, p+nb*2), 16); p += nb*2; }
                else { structCnt = structCb; }
                for (int i = 0; i < structCnt && p + 12 <= sv.length(); i++) {
                    if (!sv.substring(p, p+4).equals("0202")) break;
                    if (!sv.substring(p+4, p+6).equals("0F"))  break;
                    int scByte = Integer.parseInt(sv.substring(p+6, p+8), 16);
                    int uc = Integer.parseInt(sv.substring(p+10, p+12), 16);
                    int sc = scByte > 127 ? scByte - 256 : scByte;
                    if (i < bspObis.size()) map.put(bspObis.get(i), new int[]{sc, uc});
                    p += 12;
                }
            } catch (Exception ignored) {}
        }
        if (map.isEmpty()) {
            java.util.regex.Pattern pNew = java.util.regex.Pattern.compile(
                    "BSCL ([0-9A-F]{12}) (?:[0-9A-F]+ )*?0202 ?0F([0-9A-F]{2})16([0-9A-F]{2})");
            for (String line : dataUpper.split("[\\r\\n]+")) {
                if (!line.startsWith("BSCL")) continue;
                String norm = line.replaceAll("\\s+", " ");
                java.util.regex.Matcher m = pNew.matcher(norm);
                if (m.find()) {
                    String obis = m.group(1);
                    if (obis.startsWith("0202")) continue;
                    int sc = Integer.parseInt(m.group(2), 16);
                    if (sc > 127) sc -= 256;
                    int uc = Integer.parseInt(m.group(3), 16);
                    map.put(obis, new int[]{sc, uc});
                }
            }
            if (map.isEmpty()) {
                int[] whScaler = null, vahScaler = null;
                java.util.regex.Pattern pOld = java.util.regex.Pattern.compile(
                        "BSCL\\s+[0-9A-F]+\\s+.*?0202 ?0F([0-9A-F]{2})16([0-9A-F]{2})");
                for (String line : dataUpper.split("[\\r\\n]+")) {
                    if (!line.startsWith("BSCL")) continue;
                    java.util.regex.Matcher m = pOld.matcher(line.replaceAll("\\s+", " "));
                    if (m.find()) {
                        int sc = Integer.parseInt(m.group(1), 16); if (sc > 127) sc -= 256;
                        int uc = Integer.parseInt(m.group(2), 16);
                        if (uc == 0x1E) whScaler  = new int[]{sc, uc};
                        if (uc == 0x1F) vahScaler = new int[]{sc, uc};
                    }
                }
                if (whScaler != null) {
                    for (String ob : new String[]{"0100010800FF","0100020800FF","0100010801FF","0100010802FF","0100010803FF","0100010804FF","0100010805FF","0100010806FF","0100010807FF","0100010808FF","0100020801FF","0100020802FF","0100020803FF","0100020804FF","0100020805FF","0100020806FF","0100020807FF","0100020808FF"})
                        map.put(ob, whScaler);
                }
                if (vahScaler != null) {
                    for (String ob : new String[]{"0100090800FF","01000A0800FF","0100090801FF","0100090802FF","0100090803FF","0100090804FF","0100090805FF","0100090806FF","0100090807FF","0100090808FF","01000A0801FF","01000A0802FF","01000A0803FF","01000A0804FF","01000A0805FF","01000A0806FF","01000A0807FF","01000A0808FF","0100050800FF","0100060800FF","0100070800FF","0100080800FF"})
                        map.put(ob, vahScaler);
                }
            }
        }
        return map;
    }

    private java.util.Map<String, Integer> parseBillingCaptureObjects(String dataUpper) {
        java.util.Map<String, Integer> colMap = new java.util.LinkedHashMap<>();
        java.util.Map<String, int[]> colMeta = new java.util.LinkedHashMap<>();
        try {
            String marker = "0100620100FF 03 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return colMap;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps); if (le < 0) le = dataUpper.length();
            String co = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (co.length() < 4) return colMap;
            int pos = 2;
            int lb = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
            int count;
            if ((lb & 0x80) != 0) { int nb = lb & 0x7F; count = Integer.parseInt(co.substring(pos, pos + nb * 2), 16); pos += nb * 2; }
            else { count = lb; }
            for (int col = 0; col < count && pos + 2 <= co.length(); col++) {
                while (pos + 2 <= co.length() && Integer.parseInt(co.substring(pos, pos + 2), 16) != 0x02) pos += 2;
                if (pos + 2 > co.length()) break;
                int st = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (st != 0x02) break;
                int fc = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (pos + 2 > co.length()) break;
                int t0 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                int classId = 0;
                if (t0 == 0x12 && pos + 4 <= co.length()) { classId = Integer.parseInt(co.substring(pos, pos + 4), 16); pos += 4; }
                if (pos + 2 > co.length()) break;
                int t1 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                String obisHex = "";
                if (t1 == 0x09) {
                    int ln = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                    if (ln == 6 && pos + 12 <= co.length()) { obisHex = co.substring(pos, pos + 12); pos += 12; }
                    else pos += ln * 2;
                }
                if (pos + 2 > co.length()) break;
                int t2 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                int attrIdx = 2;
                if (t2 == 0x0F && pos + 2 <= co.length()) { int ab = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2; attrIdx = ab > 127 ? ab - 256 : ab; }
                if (pos + 2 > co.length()) break;
                int t3 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (t3 == 0x12) pos += 4;
                boolean obisValid = obisHex.length() == 12 && !obisHex.substring(4, 6).equalsIgnoreCase("FF");
                if (!obisValid) continue;
                int priority = (attrIdx == 2) ? ((classId == 3) ? 3 : (classId == 1 || classId == 4) ? 2 : 1) : 0;
                int[] existing = colMeta.get(obisHex);
                if (existing == null || priority > existing[2]) colMeta.put(obisHex, new int[]{col, classId, priority});
                if (!colMap.containsKey(obisHex)) colMap.put(obisHex, col);
            }
            for (java.util.Map.Entry<String, int[]> e : colMeta.entrySet()) colMap.put(e.getKey(), e.getValue()[0]);
        } catch (Exception ignored) {}
        return colMap;
    }

    private long extractInstantRaw(String dataUpper, String obisHex) {
        try {
            String marker = obisHex.toUpperCase() + " 02 ";
            int idx = dataUpper.toUpperCase().indexOf(marker);
            if (idx < 0) return -1;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps); if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "").toUpperCase();
            if (payload.length() < 10) return -1;
            int tag = Integer.parseInt(payload.substring(0, 2), 16);
            if (tag == 0x06) return Long.parseLong(payload.substring(2, 10), 16);
            if (tag == 0x05) { long v = Long.parseLong(payload.substring(2, 10), 16); return v > 0x7FFFFFFFL ? v - 0x100000000L : v; }
            if (tag == 0x17) { int fb = (int) Long.parseLong(payload.substring(2, 10), 16); float fv = Float.intBitsToFloat(fb); return Float.isNaN(fv) || Float.isInfinite(fv) ? -1L : (long) Math.abs(fv); }
            return -1;
        } catch (Exception e) { return -1; }
    }

    private long extractBillingRawLong(String dataUpper, String obisHex, java.util.Map<String, Integer> colMap) {
        try {
            Integer colIdx = colMap.get(obisHex.toUpperCase());
            if (colIdx == null) return Long.MIN_VALUE;
            String marker = "0100620100FF 02 ";
            int idx = dataUpper.indexOf(marker); if (idx < 0) return Long.MIN_VALUE;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps); if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 8) return Long.MIN_VALUE;
            int headerEnd = 0;
            if (payload.startsWith("01")) {
                headerEnd = 2;
                int lb2 = Integer.parseInt(payload.substring(2, 4), 16); headerEnd += 2;
                if ((lb2 & 0x80) != 0) { int nb = lb2 & 0x7F; headerEnd += nb * 2; }
            }
            int bestRecordPos = -1; long bestTimestamp = -1; int scanIdx = headerEnd;
            while (scanIdx < payload.length() - 28) {
                int datePos = payload.indexOf("090C07E", scanIdx); if (datePos < 0) break;
                String ts = payload.substring(datePos + 4, Math.min(datePos + 28, payload.length()));
                if (ts.length() < 24) { scanIdx = datePos + 1; continue; }
                try {
                    int y = Integer.parseInt(ts.substring(0, 4), 16);
                    int mo = Integer.parseInt(ts.substring(4, 6), 16); if (mo > 127) mo = 0;
                    int d = Integer.parseInt(ts.substring(6, 8), 16); if (d > 127) d = 0;
                    int h = Integer.parseInt(ts.substring(10, 12), 16); if (h > 127) h = 0;
                    int mi2 = Integer.parseInt(ts.substring(12, 14), 16); if (mi2 > 127) mi2 = 0;
                    if (d == 1 && h == 0 && mi2 == 0 && y > 2000 && y < 2100 && mo >= 1 && mo <= 12) {
                        long tsVal = (long) y * 10000L + mo * 100L + d;
                        if (tsVal > bestTimestamp) {
                            bestTimestamp = tsVal;
                            int recStart = datePos - 2;
                            for (int back = 0; back <= 16; back += 2) {
                                int tryPos = datePos - back; if (tryPos < headerEnd) break;
                                if (tryPos + 2 <= payload.length()) { int t = Integer.parseInt(payload.substring(tryPos, tryPos + 2), 16); if (t == 0x02) { recStart = tryPos; break; } }
                            }
                            bestRecordPos = recStart;
                        }
                    }
                } catch (Exception ignore) {}
                scanIdx = datePos + 1;
            }
            if (bestRecordPos < 0) return Long.MIN_VALUE;
            int pos = bestRecordPos;
            if (pos + 4 > payload.length()) return Long.MIN_VALUE;
            int stTag = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            if (stTag != 0x02) return Long.MIN_VALUE;
            int fieldCount = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            if (colIdx >= fieldCount) return Long.MIN_VALUE;
            for (int c2 = 0; c2 < colIdx && pos + 2 <= payload.length(); c2++) { pos = skipOneDlmsValue(payload, pos); if (pos < 0) return Long.MIN_VALUE; }
            if (pos + 2 > payload.length()) return Long.MIN_VALUE;
            int tag2 = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            if      (tag2 == 0x06) { if (pos + 8 > payload.length()) return Long.MIN_VALUE; return Long.parseLong(payload.substring(pos, pos + 8), 16); }
            else if (tag2 == 0x05) { if (pos + 8 > payload.length()) return Long.MIN_VALUE; long v = Long.parseLong(payload.substring(pos, pos + 8), 16); return v > 0x7FFFFFFFL ? v - 0x100000000L : v; }
            else if (tag2 == 0x12) { if (pos + 4 > payload.length()) return Long.MIN_VALUE; return Long.parseLong(payload.substring(pos, pos + 4), 16); }
            else if (tag2 == 0x11 || tag2 == 0x16) { if (pos + 2 > payload.length()) return Long.MIN_VALUE; return Long.parseLong(payload.substring(pos, pos + 2), 16); }
            else if (tag2 == 0x17) { if (pos + 8 > payload.length()) return Long.MIN_VALUE; int fb = (int) Long.parseLong(payload.substring(pos, pos + 8), 16); float fv = Float.intBitsToFloat(fb); return (Float.isNaN(fv) || Float.isInfinite(fv)) ? Long.MIN_VALUE : (long) Math.abs(fv); }
            return Long.MIN_VALUE;
        } catch (Exception e) { return Long.MIN_VALUE; }
    }

    private int computeBillingEnergyAdj(String dataUpper, java.util.Map<String, Integer> colMap, java.util.Map<String, int[]> scalerMap) {
        try {
            int[] su = scalerMap.get("0100010800FF");
            if (su != null && su[0] != 0) return 0;
            long instantRaw = extractInstantRaw(dataUpper, "0100010800FF");
            if (instantRaw <= 0) return 0;
            long billingRaw = extractBillingRawLong(dataUpper, "0100010800FF", colMap);
            if (billingRaw == Long.MIN_VALUE || billingRaw <= 0) return 0;
            if (billingRaw > instantRaw * 1.05) { appendLog("BILLING_ADJ_WARN: billingRaw=" + billingRaw + " > instantRaw=" + instantRaw + " — possible meter replacement, rollover, or corrupt record. No adj applied."); return 0; }
            if (billingRaw >= instantRaw) return 0;
            double ratio = (double) instantRaw / (double) billingRaw;
            appendLog("BILLING_ADJ_RATIO inst=" + instantRaw + " bill=" + billingRaw + " ratio=" + String.format("%.1f", ratio));
            if (currentMeterMake == MeterMake.HPL && ratio >= 8.0 && ratio <= 12.0) return 1;
            if (ratio >= 80.0 && ratio <= 120.0) return 2;
            if (ratio >= 800.0 && ratio <= 1200.0) return 3;
            return 0;
        } catch (Exception e) { return 0; }
    }

    private java.util.Map<String, Integer> parseCaptureObjects(String dataUpper, String profileObis) {
        java.util.Map<String, Integer> colMap = new java.util.LinkedHashMap<>();
        try {
            String marker = profileObis.toUpperCase() + " 03 ";
            int idx = dataUpper.indexOf(marker); if (idx < 0) return colMap;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps); if (le < 0) le = dataUpper.length();
            String co = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (co.length() < 4) return colMap;
            int pos = 2;
            int lb = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
            int count;
            if ((lb & 0x80) != 0) { int nb = lb & 0x7F; count = Integer.parseInt(co.substring(pos, pos + nb * 2), 16); pos += nb * 2; }
            else { count = lb; }
            for (int col = 0; col < count && pos + 2 <= co.length(); col++) {
                while (pos + 2 <= co.length() && Integer.parseInt(co.substring(pos, pos + 2), 16) != 0x02) pos += 2;
                if (pos + 2 > co.length()) break;
                pos += 2; if (pos + 2 > co.length()) break; pos += 2;
                if (pos + 2 > co.length()) break;
                int t0 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (t0 == 0x12) pos += 4;
                if (pos + 2 > co.length()) break;
                int t1 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                String obisHex = "";
                if (t1 == 0x09) { int ln = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2; if (ln == 6 && pos + 12 <= co.length()) { obisHex = co.substring(pos, pos + 12); pos += 12; } else pos += ln * 2; }
                if (pos + 2 > co.length()) break;
                int t2 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (t2 == 0x0F) pos += 2;
                if (pos + 2 > co.length()) break;
                int t3 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (t3 == 0x12) pos += 4;
                if (!obisHex.isEmpty()) { if (!colMap.containsKey(obisHex)) colMap.put(obisHex, col); }
            }
        } catch (Exception ignored) {}
        return colMap;
    }

    private String patchProfileBuffer(String bufHex, java.util.Map<String, Integer> colMap, int adj) {
        if (adj == 0 || colMap == null || colMap.isEmpty() || bufHex == null || bufHex.length() < 8) return bufHex;
        try {
            long multiplier = (long) Math.pow(10, adj);
            String upper = bufHex.toUpperCase();
            int maxCol = 0;
            for (int c : colMap.values()) maxCol = Math.max(maxCol, c);
            boolean[] isEnergyCol = new boolean[maxCol + 1];
            for (java.util.Map.Entry<String, Integer> e : colMap.entrySet()) {
                String obis = e.getKey().toUpperCase(); int c = e.getValue();
                if (c <= maxCol && obis.length() >= 8 && "08".equals(obis.substring(6, 8))) isEnergyCol[c] = true;
            }
            int arrayStart = -1;
            for (int skip = 0; skip <= 16; skip += 2) {
                if (skip + 2 > upper.length()) break;
                if (Integer.parseInt(upper.substring(skip, skip + 2), 16) == 0x01) { arrayStart = skip; break; }
            }
            if (arrayStart < 0) return bufHex;
            StringBuilder out = new StringBuilder(upper.substring(0, arrayStart));
            int pos = arrayStart;
            out.append("01"); pos += 2;
            int cb = Integer.parseInt(upper.substring(pos, pos + 2), 16); pos += 2;
            int recordCount;
            if ((cb & 0x80) == 0) { recordCount = cb; out.append(String.format("%02X", cb)); }
            else { int nb = cb & 0x7F; String berBytes = upper.substring(pos, pos + nb * 2); pos += nb * 2; recordCount = Integer.parseInt(berBytes, 16); out.append(String.format("%02X", cb)).append(berBytes); }
            for (int r = 0; r < recordCount; r++) {
                if (pos + 4 > upper.length()) break;
                int stTag = Integer.parseInt(upper.substring(pos, pos + 2), 16);
                if (stTag != 0x02) { out.append(upper.substring(pos)); return out.toString(); }
                out.append(upper, pos, pos + 2); pos += 2;
                int fieldCount = Integer.parseInt(upper.substring(pos, pos + 2), 16);
                out.append(upper, pos, pos + 2); pos += 2;
                for (int c = 0; c < fieldCount; c++) {
                    if (pos + 2 > upper.length()) break;
                    int tag = Integer.parseInt(upper.substring(pos, pos + 2), 16);
                    boolean patch = c < isEnergyCol.length && isEnergyCol[c];
                    if (patch && tag == 0x06 && pos + 10 <= upper.length()) {
                        long raw = Long.parseLong(upper.substring(pos + 2, pos + 10), 16);
                        raw = Math.min(raw * multiplier, 0xFFFFFFFFL);
                        out.append(String.format("06%08X", raw)); pos += 10;
                    } else if (patch && tag == 0x05 && pos + 10 <= upper.length()) {
                        long raw = Long.parseLong(upper.substring(pos + 2, pos + 10), 16);
                        if (raw > 0x7FFFFFFFL) raw -= 0x100000000L;
                        raw = Math.max(Math.min(raw * multiplier, 0x7FFFFFFFL), -0x80000000L);
                        if (raw < 0) raw += 0x100000000L;
                        out.append(String.format("05%08X", raw)); pos += 10;
                    } else if (patch && tag == 0x17 && pos + 10 <= upper.length()) {
                        int fb = (int) Long.parseLong(upper.substring(pos + 2, pos + 10), 16);
                        float fv = Float.intBitsToFloat(fb);
                        if (!Float.isNaN(fv) && !Float.isInfinite(fv)) fv *= multiplier;
                        out.append(String.format("17%08X", Float.floatToIntBits(fv))); pos += 10;
                    } else if (patch && tag == 0x12 && pos + 6 <= upper.length()) {
                        long raw = Long.parseLong(upper.substring(pos + 2, pos + 6), 16);
                        raw = Math.min(raw * multiplier, 0xFFFFL);
                        out.append(String.format("12%04X", raw)); pos += 6;
                    } else {
                        int newPos = skipOneDlmsValue(upper, pos);
                        if (newPos < 0 || newPos > upper.length()) { out.append(upper.substring(pos)); return out.toString(); }
                        out.append(upper, pos, newPos); pos = newPos;
                    }
                }
            }
            if (pos < upper.length()) out.append(upper.substring(pos));
            return out.toString();
        } catch (Exception e) { appendLog("PATCH_BUF_ERR: " + e.getMessage()); return bufHex; }
    }

    private void patchAndReplaceBuffer(StringBuilder meterData, String dataUpper, String profileObis, java.util.Map<String, Integer> colMap, int adj) {
        try {
            String marker = profileObis.toUpperCase() + " 02 ";
            int markerIdx = dataUpper.indexOf(marker); if (markerIdx < 0) return;
            int ps = markerIdx + marker.length();
            int le = dataUpper.indexOf('\n', ps); if (le < 0) le = dataUpper.length();
            String bufHex = meterData.substring(ps, le).trim().replaceAll("\\s+", "");
            if (bufHex.length() < 8) return;
            String patched = patchProfileBuffer(bufHex, colMap, adj);
            if (patched.equals(bufHex.toUpperCase())) return;
            meterData.replace(ps, le, patched);
            appendLog("PATCH_BUF_OK obis=" + profileObis + " records adj=x" + (int) Math.pow(10, adj));
        } catch (Exception e) { appendLog("PATCH_BUF_REPLACE_ERR obis=" + profileObis + ": " + e.getMessage()); }
    }

    private void appendMissingScalerLines(StringBuilder sb, String dataUpper, java.util.Map<String, Integer> colMap, java.util.Map<String, int[]> scalerMap) {
        for (String obisHex : colMap.keySet()) {
            String upper = obisHex.toUpperCase();
            if (upper.length() != 12) continue;
            if (scalerMap.containsKey(upper)) continue;
            int sc = 0, uc = 0xFF;
            String todByte = upper.substring(8, 10);
            int todIdx = 0;
            try { todIdx = Integer.parseInt(todByte, 16); } catch (Exception ignored) {}
            if (todIdx >= 1 && todIdx <= 8) {
                String baseObis = upper.substring(0, 8) + "00" + upper.substring(10);
                int[] su = scalerMap.get(baseObis);
                if (su != null) { sc = su[0]; uc = su[1]; }
                else { int[] def = dlmsDefaultScalerUnit(baseObis); if (def != null) { sc = def[0]; uc = def[1]; } }
            } else {
                int[] def = dlmsDefaultScalerUnit(upper); if (def != null) { sc = def[0]; uc = def[1]; }
            }
            if (uc == 0xFF) continue;
            sb.append(String.format("\r\n0007 %s 03 02020F%02X16%02X", upper, sc & 0xFF, uc));
            appendLog("SCALER_EXPLICIT obis=" + upper + " sc=" + sc + " uc=0x" + String.format("%02X", uc));
        }
    }

    private void postProcessMeterData(StringBuilder meterData) {
        try {
            String dataUpper = meterData.toString().toUpperCase();
            java.util.Map<String, int[]> scalerMap = buildScalerMap(dataUpper);
            java.util.Map<String, int[]> billingScalerMapPost = buildBillingScalerMap(dataUpper);
            java.util.Map<String, int[]> adjScalerMap = billingScalerMapPost.isEmpty() ? scalerMap : billingScalerMapPost;
            java.util.Map<String, Integer> billingColMap = parseCaptureObjects(dataUpper, "0100620100FF");
            int adj = computeBillingEnergyAdj(dataUpper, billingColMap, adjScalerMap);
            if (adj != 0) appendLog("POST_PROCESS adj=+" + adj + " (billing energy x" + (int) Math.pow(10, adj) + " patch will be applied)");
            String[] cumulativeProfiles = {"0100620100FF", "0100630200FF"};
            String[] lpProfileOnly = {"0100630100FF"};
            for (String obis : cumulativeProfiles) {
                java.util.Map<String, Integer> colMap = obis.equals("0100620100FF") ? billingColMap : parseCaptureObjects(dataUpper, obis);
                if (colMap.isEmpty()) continue;
                if (adj != 0) { patchAndReplaceBuffer(meterData, dataUpper, obis, colMap, adj); dataUpper = meterData.toString().toUpperCase(); }
                java.util.Map<String, int[]> scalerForProfile = scalerMap;
                if (obis.equals("0100620100FF") && !billingScalerMapPost.isEmpty()) {
                    java.util.Map<String, int[]> merged = new java.util.HashMap<>(scalerMap);
                    merged.putAll(billingScalerMapPost); scalerForProfile = merged;
                }
                appendMissingScalerLines(meterData, dataUpper, colMap, scalerForProfile);
                dataUpper = meterData.toString().toUpperCase();
                scalerMap = buildScalerMap(dataUpper);
            }
            for (String obis : lpProfileOnly) {
                java.util.Map<String, Integer> colMap = parseCaptureObjects(dataUpper, obis);
                if (colMap.isEmpty()) continue;
                if (adj != 0 && currentMeterMake == MeterMake.HPL) {
                    boolean lpHasCumulative = false;
                    for (String k : colMap.keySet()) { if (k.length() == 12 && k.substring(6, 8).equalsIgnoreCase("1D")) { lpHasCumulative = true; break; } }
                    if (lpHasCumulative) { appendLog("POST_PROCESS HPL_CT_LP_CUMULATIVE: applying adj=" + adj + " to LP buffer (.29. columns)"); patchAndReplaceBuffer(meterData, dataUpper, obis, colMap, adj); dataUpper = meterData.toString().toUpperCase(); }
                    else { appendLog("POST_PROCESS HPL_LP_INTERVAL: LP has .27. (interval) columns — adj NOT applied"); }
                }
                appendMissingScalerLines(meterData, dataUpper, colMap, scalerMap);
                dataUpper = meterData.toString().toUpperCase();
                scalerMap = buildScalerMap(dataUpper);
            }
            String[] eventProfiles = {"0000636200FF","0000636201FF","0000636202FF","0000636203FF","0000636204FF","0000636205FF","0000636281FF"};
            for (String obis : eventProfiles) {
                java.util.Map<String, Integer> colMap = parseCaptureObjects(dataUpper, obis);
                if (colMap.isEmpty()) continue;
                if (adj != 0) { patchAndReplaceBuffer(meterData, dataUpper, obis, colMap, adj); dataUpper = meterData.toString().toUpperCase(); }
                appendMissingScalerLines(meterData, dataUpper, colMap, scalerMap);
                dataUpper = meterData.toString().toUpperCase();
                scalerMap = buildScalerMap(dataUpper);
            }
            appendLog("POST_PROCESS_COMPLETE");
        } catch (Exception e) { appendLog("POST_PROCESS_ERROR: " + e.getMessage()); }
    }

    private int skipOneDlmsValue(String payload, int pos) {
        return skipOneDlmsValue(payload, pos, 0);
    }

    private int skipOneDlmsValue(String payload, int pos, int depth) {
        try {
            if (depth >= 3) return -1;
            if (pos + 2 > payload.length()) return -1;
            int tag = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            switch (tag) {
                case 0x06: case 0x05: return pos + 8;
                case 0x17: return pos + 8;
                case 0x18: return pos + 16;
                case 0x14: case 0x15: return pos + 16;
                case 0x12: case 0x10: return pos + 4;
                case 0x11: case 0x16: case 0x0F: return pos + 2;
                case 0x00: return pos;
                case 0x04: return pos;
                case 0xFF: return pos;
                case 0xF1: case 0x03: case 0xC0: return (depth == 0) ? pos : -1;
                case 0x09: case 0x0A: { int ln = Integer.parseInt(payload.substring(pos, pos + 2), 16); return pos + 2 + ln * 2; }
                case 0x01: case 0x02: {
                    if (tag == 0x01 && pos + 2 <= payload.length()) {
                        int compactFX = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                        if ((compactFX & 0x80) != 0) { pos += 2; return pos; }
                    }
                    if (tag == 0x02 && pos + 2 <= payload.length()) {
                        int cbPeek = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                        boolean isRecordHeader = (cbPeek == 0x81) || (cbPeek == 0x82) || (cbPeek >= 20 && cbPeek < 128);
                        if (isRecordHeader) return -1;
                    }
                    int cntByte = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
                    int cnt;
                    if ((cntByte & 0x80) != 0) {
                        int nb = cntByte & 0x7F;
                        if (nb >= 1 && nb <= 4 && pos + nb * 2 <= payload.length()) {
                            int berCnt = Integer.parseInt(payload.substring(pos, pos + nb * 2), 16);
                            if (berCnt * 2 <= payload.length() - pos - nb * 2) { pos += nb * 2; cnt = berCnt; }
                            else { cnt = cntByte; }
                        } else { cnt = cntByte; }
                        if (cnt * 2 > payload.length() - pos) return -1;
                        for (int i = 0; i < cnt; i++) { pos = skipOneDlmsValue(payload, pos, depth + 1); if (pos < 0) return -1; }
                        return pos;
                    } else {
                        cnt = cntByte;
                        if (cnt > 0 && cnt * 2 > payload.length() - pos) return -1;
                        int savedPos = pos;
                        if (tag == 0x02) {
                            for (int i = 0; i < cnt; i++) { int next = skipOneDlmsValue(payload, pos, depth + 1); if (next < 0) return -1; pos = next; }
                            return pos;
                        } else {
                            boolean elementFailed = false;
                            for (int i = 0; i < cnt; i++) { int next = skipOneDlmsValue(payload, pos, depth + 1); if (next < 0 || next > savedPos + cnt * 2) { elementFailed = true; break; } pos = next; }
                            if (elementFailed) return savedPos + cnt * 2;
                            return pos;
                        }
                    }
                }
                default: return -1;
            }
        } catch (Exception e) { return -1; }
    }

    private String extractRtc(String dataUpper) {
        try {
            String marker = "0000010000FF 02 ";
            int idx = dataUpper.indexOf(marker); if (idx < 0) return "NA";
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps); if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 28 || !payload.startsWith("090C")) return "NA";
            String ts = payload.substring(4, 28);
            int y = Integer.parseInt(ts.substring(0, 4), 16);
            int mo = Integer.parseInt(ts.substring(4, 6), 16);
            int d = Integer.parseInt(ts.substring(6, 8), 16);
            int h = Integer.parseInt(ts.substring(10, 12), 16);
            int mi = Integer.parseInt(ts.substring(12, 14), 16);
            int s = Integer.parseInt(ts.substring(14, 16), 16);
            boolean ffSub = (mo == 0xFF || d == 0xFF || h == 0xFF || mi == 0xFF || s == 0xFF);
            if (mo == 0xFF) mo = 1; if (d == 0xFF) d = 1;
            if (h == 0xFF) h = 0; if (mi == 0xFF) mi = 0; if (s == 0xFF) s = 0;
            if (ffSub) appendLog("RTC_WARN: FF bytes substituted in timestamp — meter clock may be unset");
            return String.format("%02d/%02d/%04d %02d:%02d:%02d", d, mo, y, h, mi, s);
        } catch (Exception e) { return "NA"; }
    }

    private java.util.List<String> findDlmsLines(String text, String linePrefix) {
        java.util.ArrayList<String> matches = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return matches;
        for (String line : text.split("\\r?\\n")) { String trimmed = line.trim(); if (trimmed.startsWith(linePrefix)) matches.add(trimmed); }
        return matches;
    }

    private int countLoadProfileRecords(String lpHex) {
        if (lpHex == null || lpHex.isEmpty()) return 0;
        try {
            String lower = lpHex.toLowerCase();
            for (int skip = 0; skip <= 16; skip += 2) {
                if (skip + 4 > lower.length()) break;
                int tagByte = Integer.parseInt(lower.substring(skip, skip + 2), 16);
                if (tagByte == 0x01) {
                    int countByte = Integer.parseInt(lower.substring(skip + 2, skip + 4), 16);
                    if ((countByte & 0x80) == 0 && countByte > 0) return countByte;
                    int nb = countByte & 0x7F;
                    if (nb == 2 && skip + 8 <= lower.length()) { int count = Integer.parseInt(lower.substring(skip + 4, skip + 8), 16); if (count > 0) return count; }
                    else if (nb == 1 && skip + 6 <= lower.length()) { int count = Integer.parseInt(lower.substring(skip + 4, skip + 6), 16); if (count > 0) return count; }
                    break;
                }
            }
        } catch (Exception ignored) {}
        int count = 0; int idx = 0;
        String lowerHex = lpHex.toLowerCase();
        while ((idx = lowerHex.indexOf("0212090c", idx)) >= 0) { count++; idx += 8; }
        if (count > 0) return count;
        idx = 0;
        while ((idx = lowerHex.indexOf("090c", idx)) >= 0) { count++; idx += 4; }
        return count > 1 ? count : 0;
    }

    private String extractPayloadFor(String dataUpper, String obisHex) {
        String marker = obisHex.toUpperCase() + " 02 ";
        int idx = dataUpper.indexOf(marker); if (idx < 0) return "";
        int ps = idx + marker.length();
        int le = dataUpper.indexOf('\n', ps); if (le < 0) le = dataUpper.length();
        String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
        if (payload.isEmpty() || payload.matches("0+") || payload.matches("F+")) return "";
        return payload;
    }

    private void validateMeterDataForXML(StringBuilder meterData, String readingMode) {
        if (meterData == null || meterData.length() == 0) { appendLog("VALIDATION_ERROR: MeterData is empty"); return; }
        String dataStr = meterData.toString();
        appendLog("VALIDATION_START mode=" + readingMode + " dataLen=" + dataStr.length());
        boolean hasInstantVoltage = dataStr.contains("0100010200FF");
        boolean hasInstantCurrent = dataStr.contains("0100020200FF");
        boolean hasInstantPower   = dataStr.contains("0100030700FF");
        if (hasInstantVoltage || hasInstantCurrent || hasInstantPower)
            appendLog("VALIDATION_OK: Instantaneous data FOUND (V=" + hasInstantVoltage + ", I=" + hasInstantCurrent + ", P=" + hasInstantPower + ")");
        else
            appendLog("VALIDATION_WARN: Instantaneous data MAY BE MISSING — check meter response");
        if (readingMode.equals("COMPLETE")) {
            boolean hasLPCaptureObj    = dataStr.contains("0100630100FF 03");
            boolean hasLPCapturePeriod = dataStr.contains("0100630100FF 04");
            boolean hasLPEntriesInUse  = dataStr.contains("0100630100FF 07");
            boolean hasLPBuffer        = dataStr.contains("0100630100FF 02");
            int lpEntriesInUseValue = -1;
            java.util.List<String> euLines = findDlmsLines(dataStr, "0007 0100630100FF 07 ");
            for (String euLine : euLines) {
                String[] p = euLine.trim().split("\\s+", 4);
                if (p.length >= 4 && p[3].length() >= 10) { try { lpEntriesInUseValue = (int) Long.parseLong(p[3].substring(2, 10), 16); } catch (Exception ignored) {} }
            }
            int lpBufferArrayCount = -1;
            java.util.List<String> bufLines = findDlmsLines(dataStr, "0007 0100630100FF 02 ");
            for (String bufLine : bufLines) {
                String[] p = bufLine.trim().split("\\s+", 4);
                if (p.length >= 4 && p[3].length() >= 4) {
                    String hex = p[3].toLowerCase();
                    if (hex.startsWith("01")) { try {
                        int lb = Integer.parseInt(hex.substring(2, 4), 16);
                        if      (lb == 0x82 && hex.length() >= 8) lpBufferArrayCount = Integer.parseInt(hex.substring(4, 8), 16);
                        else if (lb == 0x81 && hex.length() >= 6) lpBufferArrayCount = Integer.parseInt(hex.substring(4, 6), 16);
                        else if (lb < 128)                         lpBufferArrayCount = lb;
                    } catch (Exception ignored) {} }
                }
            }
            appendLog("VALIDATION_LP: CaptureObj=" + hasLPCaptureObj + ", Period=" + hasLPCapturePeriod + ", EntriesInUse=" + hasLPEntriesInUse + "(" + lpEntriesInUseValue + "), Buffer=" + hasLPBuffer + "(arrayCount=" + lpBufferArrayCount + ")");
            if (!hasLPCaptureObj) appendLog("VALIDATION_CRITICAL: LP Capture Objects (attr=3) MISSING — XML parser will fail");
            if (!hasLPEntriesInUse) appendLog("VALIDATION_CRITICAL: LP EntriesInUse (attr=7) MISSING");
            else if (lpEntriesInUseValue == 0) appendLog("VALIDATION_INFO: LP EntriesInUse=0 — meter LP buffer empty at time of read");
            if (!hasLPBuffer) {
                if (lpEntriesInUseValue > 0) appendLog("VALIDATION_CRITICAL: LP Buffer (attr=2) MISSING — entries_in_use=" + lpEntriesInUseValue + " but no data written");
                else appendLog("VALIDATION_INFO: LP Buffer (attr=2) absent — consistent with entries_in_use=0 (confirmed empty)");
            } else if (lpBufferArrayCount == 0) appendLog("VALIDATION_INFO: LP Buffer present, array count=0 — confirmed empty by DLMS structure");
            int lpRecordCount = 0;
            for (String line : bufLines) { String[] parts = line.split("\\s+", 4); if (parts.length >= 4) lpRecordCount += countLoadProfileRecords(parts[3]); }
            appendLog("VALIDATION_LP_RECORDS: " + lpRecordCount + " records found");
            if (lpRecordCount == 0 && lpBufferArrayCount > 0) appendLog("VALIDATION_ERROR: LP Buffer declares " + lpBufferArrayCount + " rows but 0 records parsed — block transfer truncation or row-format mismatch");
            else if (lpRecordCount == 0 && lpEntriesInUseValue == 0) appendLog("VALIDATION_INFO: LP empty — consistent with EntriesInUse=0");
        }
        if (readingMode.contains("BILLING") || readingMode.equals("COMPLETE")) {
            boolean hasBillingCaptureObj = dataStr.contains("0100620100FF 03");
            boolean hasBillingBuffer     = dataStr.contains("0100620100FF 02");
            boolean hasBillingEntries    = dataStr.contains("0100620100FF 07");
            boolean hasBillingObj = hasBillingCaptureObj || hasBillingBuffer || hasBillingEntries;
            appendLog("VALIDATION_BILLING: CaptureObj=" + hasBillingCaptureObj + ", Buffer=" + hasBillingBuffer + ", EntriesInUse=" + hasBillingEntries);
            if (!hasBillingObj) appendLog("VALIDATION_WARN: Billing data (class 7, OBIS 0100620100FF) MISSING");
            if (hasBillingCaptureObj) {
                java.util.Map<String, Integer> bilColMap = parseBillingCaptureObjects(dataStr.toUpperCase());
                String[] mandatoryBillingObis = {"0000000102FF", "0100010800FF", "0100090800FF"};
                java.util.List<String> missingCols = new java.util.ArrayList<>();
                for (String ob : mandatoryBillingObis) if (!bilColMap.containsKey(ob.toUpperCase())) missingCols.add(ob);
                if (!missingCols.isEmpty()) appendLog("VALIDATION_CRITICAL: Billing mandatory columns MISSING: " + missingCols);
                else appendLog("VALIDATION_OK: Billing mandatory columns present (ts+kWh+kVAh) — " + bilColMap.size() + " total cols");
            }
        }
        String rtcVal = extractRtc(dataStr.toUpperCase());
        if ("NA".equals(rtcVal)) appendLog("VALIDATION_WARN: RTC (0.0.1.0.0.255) absent from TXT");
        else {
            try {
                String[] rp = rtcVal.split("[/ :]");
                int rtcYear = Integer.parseInt(rp[2]);
                int rtcMonth = Integer.parseInt(rp[1]);
                if (rtcYear < 2020 || rtcYear > 2030) appendLog("VALIDATION_CRITICAL: RTC year=" + rtcYear + " outside 2020-2030 — meter clock corrupt");
                else if (rtcMonth == 0 || rtcMonth > 12) appendLog("VALIDATION_CRITICAL: RTC month=" + rtcMonth + " invalid");
                else appendLog("VALIDATION_OK: RTC=" + rtcVal);
            } catch (Exception ignored) { appendLog("VALIDATION_WARN: RTC parse failed: " + rtcVal); }
        }
        appendLog("VALIDATION_END");
    }

    private String buildValidationBitmap(String data) {
        String up = data.toUpperCase();
        boolean np  = up.contains("0000600101FF 02") && !extractPayloadFor(up, "0000600101FF").isEmpty();
        boolean ins = up.contains("0100010800FF 02") && up.contains("0100200700FF 02");
        boolean bil = up.contains("0100620100FF 02");
        boolean lp  = up.contains("0100630100FF 02");
        boolean evt = up.contains("0000636200FF 02") || up.contains("0000636201FF 02");
        String rtcStr = extractRtc(up);
        boolean rtcOk = !"NA".equals(rtcStr) && !rtcStr.startsWith("01/01/0000");
        return String.format("NamePlate:%s Instant:%s Billing:%s LP:%s Events:%s RTC:%s",
                np?"OK":"--", ins?"OK":"--", bil?"OK":"--", lp?"OK":"--", evt?"OK":"--", rtcOk?"OK":"WARN");
    }

    private static int[] dlmsDefaultScalerUnit(String o) {
        switch (o) {
            case "0100200700FF": case "0100340700FF": case "0100480700FF": return new int[]{-2,0x23};
            case "01001F0700FF": case "0100330700FF": case "0100470700FF": return new int[]{-3,0x21};
            case "0100010700FF": case "0100020700FF": return new int[]{0,0x1B};
            case "0100030700FF": case "0100040700FF": return new int[]{0,0x1D};
            case "0100090700FF": case "01000A0700FF": return new int[]{0,0x1C};
            case "01000E0700FF": return new int[]{-2,0x2C};
            case "01000D0700FF": return new int[]{-3,0xFF};
            case "0100010800FF": case "0100020800FF": return new int[]{-1,0x1E};
            case "0100090800FF": case "01000A0800FF": return new int[]{-1,0x1F};
            case "0100050800FF": case "0100060800FF":
            case "0100070800FF": case "0100080800FF": return new int[]{-1,0x20};
            case "0100010600FF": case "0100020600FF": return new int[]{1,0x1B};
            case "0100090600FF": case "01000A0600FF": return new int[]{1,0x1C};
            default: return null;
        }
    }
}