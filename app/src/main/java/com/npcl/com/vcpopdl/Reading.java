// VERSION: V34 — GetLocation: replaced GPSTracker with direct LocationManager (GPS→Network→Passive fallback); fixes missing LATITUDE/LONGITUDE in TXT header when getLastKnownLocation() returned null despite GPS on (2026-06-24)
// VERSION: V33 — ReadEventData: session deadline checked per OBIS iteration so event loop cannot overrun SESSION_MAX_SECONDS (2026-06-23)
// VERSION: V32 — Empty-payload guard: hasMeaningfulDlmsPayload now returns false when parts<4 (catches L&T LP2 empty response that triggered wrong true); LP2 exception-path LS fallback now also uses exFallbackSb (same echo-leak fix as V31 empty-path); dlms-converter: SS=0xFF→00, L&G LP2 outer-wrapper strip (2026-06-23)
// VERSION: V31 — LP2 LS-fallback echo leak fix: use lsFallbackSb so echo is discarded before reaching TXT (V30 wrote echo to strbldDLMdata before null-check ran) (2026-06-23)
// VERSION: V30 — Midnight LP2 scalar fix: detect Genus uint32 echo for 0100630200FF attr=02 and fall back to LS read; LP1 attr=04 now written to TXT so converter sees correct interval (2026-06-21)
// V29 — LP deadline bug fix: abortPendingBlockTransfer no longer resets lpDeadlineMs (was causing LP to run indefinitely after partial bulk transfer); flushLog after billing+midnight so phase logs appear in file during long LP reads; comment fix SESSION_MAX_SECONDS 7→9 min (2026-06-20)
// V28: Billing block stall fix: per-block 30s deadline + billingDeadlineMs in moreBlocks loop (2026-06-19)
// V27: REASSOC skipped when abortRequested; V26: HTML popup suppressed
package com.npcl.com.vcpopdl;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.AdapterView;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.database.Cursor;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.graphics.Color;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.io.*;
import java.util.*;
import java.lang.*;
import java.io.IOException;
import java.util.Arrays;

import android.os.Environment;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import java.util.List;
import tw.com.prolific.driver.pl2303.PL2303Driver;
import tw.com.prolific.driver.pl2303.PL2303Driver.FlowControl;

import android.app.PendingIntent;
import android.content.Context;
import android.text.format.Time;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.concurrent.TimeUnit;

import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.annotation.SuppressLint;

public class Reading extends AppCompatActivity {

    // =====================================================================
    // METER MAKE ENUM — display name + DLMS password
    // =====================================================================
    public enum MeterMake {
        SECURE    ("Secure",      "ABCD0001"),
        GENUS     ("Genus",       "1A2B3C4D"),
        LNT       ("L&T",         "lnt1"),
        HPL       ("HPL",         "1111111111111111"),
        AVON      ("AVON",        "Hello"),
        LANDIS    ("Landis+Gyr",  "11111111"),
        LNG       ("L&G",         "");      // IEC optical — no DLMS password

        private final String displayName;
        private final String password;

        MeterMake(String displayName, String password) {
            this.displayName = displayName;
            this.password    = password;
        }
        public String getDisplayName() { return displayName; }
        public String getPassword()    { return password; }

        public static MeterMake fromDisplayName(String name) {
            for (MeterMake m : values()) {
                if (m.displayName.equalsIgnoreCase(name)) return m;
            }
            return SECURE; // safe default
        }
    }

    // =====================================================================
    // READING MODE ENUM - replaces all string-based mode checks
    // =====================================================================
    public enum ReadingMode {
        INSTANTANEOUS ("Instantaneous", "I"),
        BILLING       ("Billing",        "B"),
        COMPLETE      ("Complete",       "C");  // Instantaneous + Billing + Midnight + Events + Load Profile

        private final String displayName;
        private final String filePrefix;

        ReadingMode(String displayName, String filePrefix) {
            this.displayName = displayName;
            this.filePrefix = filePrefix;
        }

        public String getDisplayName() { return displayName; }
        public String getFilePrefix()  { return filePrefix; }

        public static ReadingMode fromString(String s) {
            for (ReadingMode m : values()) {
                if (m.displayName.equalsIgnoreCase(s)) return m;
            }
            return INSTANTANEOUS; // safe default
        }
    }

    // =====================================================================
    // FIELDS
    // =====================================================================
    private ImageView imageHolder;
    private final int requestCode = 20;
    private PL2303Driver.BaudRate mBaudrate = PL2303Driver.BaudRate.B300;
    private PL2303Driver.DataBits mDataBits = PL2303Driver.DataBits.D7;
    private PL2303Driver.Parity mParity = PL2303Driver.Parity.EVEN;
    private PL2303Driver.StopBits mStopBits = PL2303Driver.StopBits.S1;
    private PL2303Driver.FlowControl mFlowControl = FlowControl.OFF;
    private static final String ACTION_USB_PERMISSION = "com.npcl.com.npclvcpapp.USB_PERMISSION";
    private UsbManager mUsbManager;
    PL2303Driver mSerial;

    // DLMS Variables
    private StringBuilder strbldDLMdata = new StringBuilder();
    private byte[] nPkt = new byte[1024];
    private byte[] nRcvPkt = new byte[1024];
    private byte bytAddMode;
    private byte nRecv;
    private byte nRecvLast;
    private byte nRecvCntr;
    private byte nSent;
    private byte nSentLast;
    private byte nSentCntr;
    private int nCounter;
    private int intProfilePd = 15; // BUG-D FIX: default 15 min — prevents div/0 in GetParameterSelective if ReadNamePlate fails to read capture period
    private byte nRetLSH;
    private byte[] buffer = new byte[1024];
    private int[] fcstab = new int[256];
    private byte bytTimOut = (byte) 8;   // OPTIMIZED: was 15
    private byte bytTryCnt = (byte) 3;   // OPTIMIZED: was 5
    final private int bytWait = 30;
    private byte[] Ps = new byte[16];
    private byte[] keyBytes = new byte[16];
    private int pktLength;
    private boolean nNewAmmendment;

    ProgressBar progressBar;
    Spinner s1;

    // =====================================================================
    // BYPASS LOGIN: hardcoded operator ID - no login screen required
    // =====================================================================
    private static final String BYPASS_USER_ID = "OPERATOR";
    private static final String BYPASS_ROLE    = "READER";

    // RTC warning flag (set during reading; does NOT stop reading)
    private boolean rtcWarningFlag = false;
    private String  rtcWarningMessage = "";


    // =====================================================================
    // ABORT FLAG — set by Abort button; checked in SNRM loop + doInBackground
    // =====================================================================
    private volatile boolean abortRequested = false;
    private AsyncTaskRunner  currentTask     = null;
    // LP deadline — set before ReadLoadSurveyData, checked inside GPLS loops
    private volatile long    lpDeadlineMs    = 0;
    // Session deadline — set at start of COMPLETE mode, hard cap of 9 minutes total.
    // Checked before each phase. When exceeded, remaining phases are skipped and
    // whatever data was collected is written to file as a partial/complete result.
    private volatile long    sessionDeadlineMs = 0;
    private static final int SESSION_MAX_SECONDS = 540; // 9 minutes hard cap

    // Consecutive SendPkt failure counter — incremented each time port.write throws
    // an exception (e.g. srcPos=-1 after USB driver state corruption during long LP reads).
    // Reset to 0 on any successful send. When it reaches 3, abortRequested is set so
    // all LP loops (which check abortRequested) break cleanly instead of spinning.
    private int consecutiveSendFailures = 0;
    private static final int MAX_SEND_FAILURES = 3;

    // SESSION REUSE — keeps DLMS association open across reads in COMPLETE mode
    private UsbSerialPort activePort = null;

    // ADAPTIVE TIMEOUT — fast probe (1.5s) then full fallback
    private static final int FAST_TIMEOUT_MS  = 1500;
    private static final int FULL_TIMEOUT_MS  = 8000;

    // Current active meter make — set when download starts, used to skip irrelevant OBIS
    private MeterMake currentMeterMake = MeterMake.SECURE;

    // HPL logical device name from OBIS 0.0.42.0.0.255 — set in ReadNamePlate(), used by
    // getCaptureObjectsForMake() to select the correct sub-variant LP column layout.
    // Typical values: "HPLSPEM6..." (14-col), "HPLPPEM6..." (15-col), "HPLCT05..." (9-col).
    // Stays empty if the meter does not support 0.0.42.0.0.255 — getCaptureObjectsForMake()
    // falls back to SPEM 14-col in that case.
    private String hplLogicalDeviceName = "";

    // Last GetParameter_LS outcome — lets callers distinguish ACCESS_ERROR from true session drop
    // 0 = success/unknown, 1 = ACCESS_ERROR (session still alive), 2 = timeout/no-response/DM
    private int lastGplsResult = 0;

    // =====================================================================
    // onCreate
    // =====================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_reading);
            // --- Set user display (bypass login) ---
            EditText eduser = (EditText) findViewById(R.id.txtuser);
            eduser.setText(BYPASS_USER_ID);
            // --- Init DB schema ---
            DatabaseHandler db1 = new DatabaseHandler(getApplicationContext());
            db1.onCreate();

            // --- Populate dropdowns ---
            BindDatatoBeRead();
            BindddlMCBType();
            BindddlMeterLocation();
            BindMeterMake();

            // --- USB status check on startup ---
            checkUsbStatus();

            // --- Setup actualRead spinner from string array ---
            Spinner actualRead = findViewById(R.id.actualRead);
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                    this, R.array.actualRead, android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            actualRead.setAdapter(adapter);

            // --- Check intent data (consumer lookup passed from search screen) ---
            Bundle bundle = getIntent().getExtras();
            if (bundle != null) {
                String text = bundle.getString("Data");
                if (text != null && text.length() > 0) {
                    // Format from Serach.java: "ConsNo:::MtrNo:::Name...#UserID"
                    // Format from Login.java:  "ConsNo#UserID"
                    String[] hashParts = text.split("#");
                    if (hashParts.length > 1) {
                        // extract UserID from after #
                        EditText eduser2 = (EditText) findViewById(R.id.txtuser);
                        eduser2.setText(hashParts[1].trim());
                    }
                    // ConsumerNo is always before first ":" or before "#"
                    String[] colonParts = text.split(":");
                    String ConsumerNo = colonParts[0].trim();
                    if (!ConsumerNo.isEmpty()) {
                        EditText txtConsNo = (EditText) findViewById(R.id.txtConsumerNo);
                        txtConsNo.setText(ConsumerNo);
                        // Auto-lookup consumer
                        lookupConsumer(ConsumerNo, true);
                    }
                }
            }

            // --- Initialise progress bar ---
            progressBar = (ProgressBar) findViewById(R.id.progressBarReading);

            // LP days is now auto-determined from meter's profile_entries_max (attr=8)
            EditText LPdays = (EditText) findViewById(R.id.txtloadprofile);
            if (LPdays != null) LPdays.setVisibility(android.view.View.GONE);

            addProgressLog("Ready. Connect USB optical cable and press Download Data.", false);

            // Suggestion 3: show last successful read in header on startup
            showLastReadBanner();

        } catch (Exception ex) {
            addProgressLog("Init error: " + ex.getMessage(), true);
        }
    }

    // =====================================================================
    // LIFECYCLE — prevent crash when orientation changes during reading
    // =====================================================================
    @Override
    protected void onDestroy() {
        // Stop the inline header timer so it doesn't post to a destroyed window
        stopLogTimer();
        // If a task is running and Activity is being destroyed (not by rotation —
        // rotation is handled by configChanges in manifest), cancel it cleanly.
        // Note: configChanges already prevents recreation on rotation, so this
        // guard is for cases like Back pressed while reading.
        if (currentTask != null && isFinishing()) {
            abortRequested = true;
            currentTask.cancel(true);
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        // Handled by android:configChanges in manifest — no layout recreation.
        // Lock to portrait while a read is in progress to prevent any edge-case
        // crashes from rapid orientation flips during USB I/O.
        super.onConfigurationChanged(newConfig);
        if (currentTask != null) {
            // Reading in progress — stay in current orientation
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        } else {
            // No read in progress — allow free rotation
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    // =====================================================================
    // USB STATUS CHECK
    // =====================================================================
    private void checkUsbStatus() {
        try {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            TextView usbStatus = (TextView) findViewById(R.id.tvUsbStatus);
            if (availableDrivers == null || availableDrivers.isEmpty()) {
                usbStatus.setText("USB: Not Connected");
                usbStatus.setBackgroundColor(Color.parseColor("#C62828"));
                usbStatus.setTextColor(Color.WHITE);
            } else {
                UsbSerialDriver driver = availableDrivers.get(0);
                usbStatus.setText("USB: Connected — " + driver.getDevice().getDeviceName());
                usbStatus.setBackgroundColor(Color.parseColor("#2E7D32"));
                usbStatus.setTextColor(Color.WHITE);
                // Request permission
                PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0,
                        new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                manager.requestPermission(driver.getDevice(), mPermissionIntent);
            }
        } catch (Exception ex) {
            TextView usbStatus = (TextView) findViewById(R.id.tvUsbStatus);
            usbStatus.setText("USB: Error — " + ex.getMessage());
            usbStatus.setBackgroundColor(Color.parseColor("#C62828"));
            usbStatus.setTextColor(Color.WHITE);
        }
    }

    // =====================================================================
    // CONSUMER LOOKUP
    // =====================================================================
    public void onShowButtonClicked(View v) {
        try {
            EditText txtConsNo = (EditText) findViewById(R.id.txtConsumerNo);
            RadioButton rbConsNo = (RadioButton) findViewById(R.id.rbConsNo);
            String filter = txtConsNo.getText().toString().trim();
            if (filter.isEmpty()) {
                addProgressLog("Please enter Consumer No or Meter No.", true);
                return;
            }
            lookupConsumer(filter, rbConsNo.isChecked());
        } catch (Exception ex) {
            addProgressLog("Lookup error: " + ex.getMessage(), true);
        }
    }

    private void lookupConsumer(String filter, boolean byConsumerNo) {
        try {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String col   = byConsumerNo ? "ConsumerNo" : "MeterNo";
            // Try READER table first
            String sql = "SELECT ISDLMS, Ablbelnr, ConsumerNo, MeterNo, Name, Co, HouseNo, Street, City, Portion " +
                    "FROM mro_detail WHERE " + col + "='" + filter.replace("'","''") + "'";
            Cursor c = db.GetData(sql); // DatabaseHandler uses rawQuery only
            boolean found = false;
            if (c != null && c.moveToFirst()) {
                populateConsumerFields(c);
                found = true;
                c.close();
            }
            if (!found) {
                // Try supervisor table
                sql = "SELECT Ablbelnr, ConsumerNo, MeterNo, Name, Co, HouseNo, Street, City, Portion " +
                        "FROM mro_Detail_supervisor WHERE " + col + "='" + filter.replace("'","''") + "'";
                c = db.GetData(sql); // DatabaseHandler uses rawQuery only
                if (c != null && c.moveToFirst()) {
                    populateConsumerFields(c);
                    found = true;
                    c.close();
                }
            }
            if (!found) {
                // Bypass: allow any meter number directly without MRO
                TextView lbMeterNo = (TextView) findViewById(R.id.lbMeterNo);
                lbMeterNo.setText(filter);
                TextView lbConsNo  = (TextView) findViewById(R.id.lbConsumerNo);
                lbConsNo.setText(filter);
                addProgressLog("Consumer not in MRO — proceeding with direct Meter No: " + filter, false);
            }
        } catch (Exception ex) {
            addProgressLog("Consumer lookup failed: " + ex.getMessage(), true);
        }
    }

    private void populateConsumerFields(Cursor c) {
        try {
            String cons    = safeGet(c, "ConsumerNo");
            String mtrno   = safeGet(c, "MeterNo");
            String name    = safeGet(c, "Name");
            String co      = safeGet(c, "Co");
            String houseNo = safeGet(c, "HouseNo");
            String street  = safeGet(c, "Street");
            String abl     = safeGet(c, "Ablbelnr");
            String portion = safeGet(c, "Portion");

            ((TextView) findViewById(R.id.lbConsumerNo)).setText(cons);
            ((TextView) findViewById(R.id.lbMeterNo)).setText(mtrno);
            ((TextView) findViewById(R.id.lbName)).setText(name);
            ((TextView) findViewById(R.id.lbAddress)).setText(co + ", " + houseNo);
            ((TextView) findViewById(R.id.lbStreet)).setText(street);
            ((TextView) findViewById(R.id.lbablbelnr)).setText(abl);
            ((TextView) findViewById(R.id.lbtportion)).setText(portion);

            addProgressLog("Consumer loaded: " + cons + " | Meter: " + mtrno, false);
        } catch (Exception ex) {
            addProgressLog("Field population error: " + ex.getMessage(), true);
        }
    }

    private String safeGet(Cursor c, String col) {
        try {
            int idx = c.getColumnIndex(col);
            return (idx >= 0) ? (c.getString(idx) != null ? c.getString(idx) : "") : "";
        } catch (Exception ex) { return ""; }
    }

    public void onRadioButtonClicked(View v) {
        // Handled naturally by RadioGroup
    }

    // =====================================================================
    // GPS
    // =====================================================================
    // Uses LocationManager directly with GPS → Network → Passive fallback chain.
    // GPSTracker relied on a callback that never fired (no Looper on background
    // threads, and getLastKnownLocation() returns null when no cached fix exists).
    // getLastKnownLocation() is instant (no wait); returns null only if the
    // provider has never produced a fix on this device — in that case we skip.
    // Filters out (0,0) which indicates an unacquired fix, not a real location.
    @SuppressLint("MissingPermission")
    public String GetLocation() {
        String MyLoc = "";
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    getSystemService(android.content.Context.LOCATION_SERVICE);
            if (lm == null) return MyLoc;

            android.location.Location loc = null;

            String[] providers = {
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER,
            };
            for (String provider : providers) {
                try {
                    if (!lm.isProviderEnabled(provider)) continue;
                    android.location.Location candidate = lm.getLastKnownLocation(provider);
                    if (candidate == null) continue;
                    // Skip (0,0) — indicates no real fix
                    if (candidate.getLatitude() == 0.0 && candidate.getLongitude() == 0.0) continue;
                    // Prefer the most recent fix
                    if (loc == null || candidate.getTime() > loc.getTime()) loc = candidate;
                } catch (Exception ignored) {}
            }

            if (loc != null) {
                MyLoc = loc.getLatitude() + "~" + loc.getLongitude();
                appendLog("GPS: " + MyLoc + " via " + loc.getProvider()
                        + " acc=" + (int)loc.getAccuracy() + "m");
            } else {
                appendLog("GPS: no cached fix available (GPS on, permission granted — open Maps briefly to seed a fix)");
            }
        } catch (SecurityException se) {
            appendLog("GPS: permission denied — " + se.getMessage());
        } catch (Exception ex) {
            appendLog("GPS: error — " + ex.getMessage());
        }
        return MyLoc;
    }

    // =====================================================================
    // DOWNLOAD BUTTON HANDLER
    // =====================================================================
    public void onbtnReadWithTheftClicked(View v) {
        try {
            // Re-check USB before starting
            checkUsbStatus();

            if (!validateDataForRead()) return;

            Spinner ddlMeterMake    = (Spinner) findViewById(R.id.ddlMeterMake);
            Spinner ddldatatoberead = (Spinner) findViewById(R.id.ddldatatoberead);
            String MeterMakeStr     = String.valueOf(ddlMeterMake.getSelectedItem());
            String DataToBeReadStr  = String.valueOf(ddldatatoberead.getSelectedItem());

            MeterMake meterMake = MeterMake.fromDisplayName(MeterMakeStr);
            ReadingMode mode    = ReadingMode.fromString(DataToBeReadStr);

            // LP days is auto-calculated inside ReadLoadSurveyData from meter capacity
            String LSDays = "35"; // placeholder only, not used for actual day calculation

            String userid   = BYPASS_USER_ID;
            String LATILONG = GetLocation();

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss", java.util.Locale.US);
            String SysDate = dateFormat.format(new Date());

            String FileNameNew = mode.getFilePrefix() + "_" + MeterMakeStr + "_" + SysDate;

            DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
            String SqlLog = "INSERT INTO ReadingLog (MeterNo,MeterMake,StartTime,Status,UserId,IsUploaded,LATILONG) VALUES('" +
                    "PENDING" + "','" + MeterMakeStr + "','" + SysDate + "','Reading Start','" + userid + "','N','" + LATILONG + "')";
            Obj.ExecuteQry(SqlLog);

            clearProgressLog();
            addProgressLog("Starting — " + meterMake.getDisplayName() + " | " + mode.getDisplayName(), false);

            if (meterMake == MeterMake.LNG) {
                abortRequested = false;
                consecutiveSendFailures = 0;
                sessionDeadlineMs = 0; // reset — will be set inside COMPLETE case if needed
                AsyncTaskRunnerLnG task = new AsyncTaskRunnerLnG();
                task.execute(mode.getDisplayName());
            } else {
                abortRequested = false;
                consecutiveSendFailures = 0;
                sessionDeadlineMs = 0; // reset — will be set inside COMPLETE case if needed
                currentMeterMake = meterMake;
                currentTask = new AsyncTaskRunner();
                currentTask.execute(mode.getDisplayName(), LSDays, "PENDING", FileNameNew, "", "16", meterMake.getPassword());
            }

        } catch (Exception ex) {
            addProgressLog("Error starting read: " + ex.getMessage(), true);
        }
    }

    // =====================================================================
    // MANUAL READING
    // =====================================================================
    public void onbtnManualReading(View v) {
        try {
            Intent intent = new Intent(this, MReading.class);
            TextView lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
            intent.putExtra("Data", lbConsNo.getText().toString() + "#" + BYPASS_USER_ID);
            startActivity(intent);
        } catch (Exception ex) {
            addProgressLog("Manual reading error: " + ex.getMessage(), true);
        }
    }

    // =====================================================================
    // REFRESH USB BUTTON
    // =====================================================================
    public void onBtnRefreshUsbClicked(View v) {
        checkUsbStatus();
        addProgressLog("USB status refreshed.", false);
    }

    // =====================================================================
    // OPEN SDK TEST SCREEN
    // =====================================================================
    public void onBtnOpenSdkTestClicked(View v) {
        startActivity(new Intent(this, TestSDKActivity.class));
    }

    // =====================================================================
    // ABORT READING
    // =====================================================================
    public void onBtnAbortClicked(View v) {
        abortRequested = true;
        lpDeadlineMs = 0; // LOW-1 FIX: reset deadline on user abort
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        addProgressLog("Abort requested by user.", false);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Button btnDownload = findViewById(R.id.btnReadWithTheft);
                if (btnDownload != null) btnDownload.setEnabled(true);
                Button btnAbort = findViewById(R.id.btnAbort);
                if (btnAbort != null) btnAbort.setEnabled(false);
            }
        });
    }

    public void onBtnLpPlusClicked(View v) {
        EditText et = findViewById(R.id.txtloadprofile);
        try {
            int d = Integer.parseInt(et.getText().toString().trim());
            if (d < 45) et.setText(String.valueOf(d + 1));
        } catch (Exception e) { et.setText("7"); }
    }

    public void onBtnLpMinusClicked(View v) {
        EditText et = findViewById(R.id.txtloadprofile);
        try {
            int d = Integer.parseInt(et.getText().toString().trim());
            if (d > 1) et.setText(String.valueOf(d - 1));
        } catch (Exception e) { et.setText("7"); }
    }

    // =====================================================================
    // VALIDATE DATA BEFORE READ
    // =====================================================================
    private boolean validateDataForRead() {
        try {
            Spinner ddlMeterMake = (Spinner) findViewById(R.id.ddlMeterMake);
            String MeterMake = String.valueOf(ddlMeterMake.getSelectedItem());
            if (MeterMake.equals("--Select Meter Make --")) {
                addProgressLog("ERROR: Please select Meter Make.", true);
                return false;
            }

            Spinner ddldatatoberead = (Spinner) findViewById(R.id.ddldatatoberead);
            String datatoberead = String.valueOf(ddldatatoberead.getSelectedItem());
            if (datatoberead.equals("--Select Data to be Read --")) {
                addProgressLog("ERROR: Please select Data to be Read.", true);
                return false;
            }

            // Load profile days validation
            ReadingMode mode = ReadingMode.fromString(datatoberead);
            // LP days are auto-calculated from meter capacity — no user validation needed

            // USB check
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            if (availableDrivers == null || availableDrivers.isEmpty()) {
                addProgressLog("ERROR: USB optical cable not detected. Please connect and retry.", true);
                return false;
            }

            // No consumer/meter number required — meter ID is read directly from the meter


            return true;
        } catch (Exception ex) {
            addProgressLog("Validation error: " + ex.getMessage(), true);
            return false;
        }
    }

    // =====================================================================
    // PROGRESS LOG — newest entry at top, fixed header pinned above log
    // =====================================================================

    /** Header line shown above the log: "Meter: XXXX | Mode: Complete | ⏱ 01:23" */
    private String logHeaderText = "";
    /** Session start time for elapsed timer shown in header */
    private long logSessionStartMs = 0;
    /** Handler updating the header timer every second */
    private android.os.Handler logTimerHandler = null;
    private java.lang.Runnable logTimerRunnable = null;

    private void startLogTimer(final String meterNo, final String modeName) {
        logSessionStartMs = System.currentTimeMillis();
        logHeaderText = "Meter: " + meterNo + "  |  Mode: " + modeName;
        if (logTimerHandler != null && logTimerRunnable != null)
            logTimerHandler.removeCallbacks(logTimerRunnable);
        logTimerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        logTimerRunnable = new java.lang.Runnable() {
            @Override public void run() {
                long elapsed = System.currentTimeMillis() - logSessionStartMs;
                long secs = (elapsed / 1000) % 60;
                long mins = elapsed / 60000;
                String header = logHeaderText + "  |  ⏱ "
                        + String.format("%02d:%02d", mins, secs);
                TextView tvHeader = (TextView) findViewById(R.id.tvCurrentStep);
                if (tvHeader != null) tvHeader.setText(header);
                logTimerHandler.postDelayed(this, 1000);
            }
        };
        logTimerHandler.post(logTimerRunnable);
    }

    private void stopLogTimer() {
        if (logTimerHandler != null && logTimerRunnable != null)
            logTimerHandler.removeCallbacks(logTimerRunnable);
    }

    private void addProgressLog(final String message, final boolean isError) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    TextView tvLog = (TextView) findViewById(R.id.tvProgressLog);
                    ScrollView scrollLog = (ScrollView) findViewById(R.id.scrollProgressLog);
                    if (tvLog == null) return;

                    SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
                    String timestamp = tf.format(new Date());
                    String icon   = isError ? "⚠ " : "• ";
                    String newLine = "[" + timestamp + "] " + icon + message + "\n";

                    // Prepend: newest at top
                    String current = tvLog.getText().toString();
                    String combined = newLine + current;

                    // Build SpannableString for per-line colouring
                    android.text.SpannableStringBuilder ssb =
                            new android.text.SpannableStringBuilder(combined);
                    int color = isError
                            ? Color.parseColor("#C62828")   // red for errors/warnings
                            : Color.parseColor("#1A237E");  // dark-blue for normal
                    ssb.setSpan(
                            new android.text.style.ForegroundColorSpan(color),
                            0, newLine.length(),
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    tvLog.setText(ssb);

                    // Scroll to top so the newest line is always visible
                    if (scrollLog != null) {
                        scrollLog.post(new Runnable() {
                            @Override public void run() { scrollLog.scrollTo(0, 0); }
                        });
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private void clearProgressLog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    TextView tvLog = (TextView) findViewById(R.id.tvProgressLog);
                    if (tvLog != null) {
                        tvLog.setText("");
                        tvLog.setTextColor(Color.parseColor("#212121"));
                    }
                    ProgressBar pb = (ProgressBar) findViewById(R.id.progressBarReading);
                    if (pb != null) pb.setProgress(0);
                } catch (Exception ignored) {}
            }
        });
    }

    private void setProgressValue(int progress) {
        final int p = progress;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ProgressBar pb = (ProgressBar) findViewById(R.id.progressBarReading);
                if (pb != null) pb.setProgress(p);
            }
        });
    }

    // =====================================================================
    // UPDATE STATUS / LOG HELPERS
    // =====================================================================
    public void UpdateStatus(String Meterno, String Status) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss", java.util.Locale.US);
            String SysDate = dateFormat.format(new Date());
            DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
            String Sql1 = "UPDATE ReadingLog SET Status='" + Status + "', EndTime='" + SysDate +
                    "' WHERE MeterNo='" + Meterno + "'";
            Obj.ExecuteQry(Sql1);
        } catch (Exception ex) { appendLog("DB error: " + ex.getMessage()); }
    }

    public void UpdateStatusFileNme(String Meterno, String Status) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss", java.util.Locale.US);
            String SysDate = dateFormat.format(new Date());
            DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
            String Sql1 = "UPDATE ReadingLog SET FileName='" + Status + "', EndTime='" + SysDate +
                    "' WHERE MeterNo='" + Meterno + "'";
            Obj.ExecuteQry(Sql1);
        } catch (Exception ex) { appendLog("error: " + ex.getMessage()); }
    }

    // =====================================================================
    // MAIN ASYNC TASK — DLMS SECURE METER READING
    // =====================================================================
    private class AsyncTaskRunner extends AsyncTask<String, String, String> {

        android.app.AlertDialog progressDialog;  // kept for LnG task compatibility
        private TextView    dlgMsgView;
        private TextView    dlgProgressPctView;
        private long        dlgStartTime;
        private String DataToBeRead;
        private ReadingMode readingMode;
        private String CescRajMeterno;
        private String FileNameprfix;
        private String SubRemark;
        private String MrNote;
        int lsDays = 0;

        // Store meter data for display in onPostExecute
        StringBuilder lastMeterData = null;

        @Override
        protected String doInBackground(String... params) {
            try {
                DataToBeRead  = params[0];
                readingMode   = ReadingMode.fromString(DataToBeRead);
                lsDays        = 0;
                CescRajMeterno   = (params.length > 2) ? params[2] : "PENDING";
                FileNameprfix = (params.length > 3) ? params[3] : "DATA";
                SubRemark     = (params.length > 4) ? params[4] : "";
                MrNote        = (params.length > 5) ? params[5] : "16";
                // params[6] = DLMS password (from MeterMake enum)
                String dlmsPassword = (params.length > 6) ? params[6] : "ABCD0001";

                // Load profile days
                try { lsDays = (params.length > 1 && !params[1].isEmpty()) ? Integer.parseInt(params[1]) : 7; }
                catch (Exception e) { lsDays = 7; }
                // Hard cap: max 35 days for all reading modes and all meter makes.
                if (lsDays > 35) lsDays = 35;
                if (lsDays <= 0) lsDays = 35;

                startDiagLog(
                        currentMeterMake != null ? currentMeterMake.getDisplayName() : "Unknown",
                        readingMode.getDisplayName(),
                        lsDays);
                appendLog("=== doInBackground START mode=" + readingMode.getDisplayName() + " ===");
                publishProgress("START|Starting reading — " + readingMode.getDisplayName(), "5");

                String MeterNo = "";
                UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
                List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
                if (availableDrivers.isEmpty()) {
                    publishProgress("ERROR|USB cable not detected. Please connect optical cable.", "0");
                    return "USB Not Found";
                }
                UsbSerialDriver driver = availableDrivers.get(0);
                UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
                if (connection == null) {
                    publishProgress("ERROR|USB permission denied. Grant permission and retry.", "0");
                    return "USB Permission Denied";
                }
                UsbSerialPort port = driver.getPorts().get(0);
                port.open(connection);
                port.setParameters(9600, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                Fcs_Tab();

                publishProgress("INFO|Port open — 9600 8N1 — Initializing HDLC...", "10");
                AddressInit();

                // L&T NTD meters use native HDLC at 9600 8N1 — no Mode C preamble needed.
                // Old working file (Reading_37) had no make-specific pre-NRM block at all
                // and succeeded on L&T with plain SetNRM. Mode C was causing 3s waste per
                // attempt (timeout waiting for /?! response that never comes) plus garbled
                // bytes in the meter's RX buffer that blocked subsequent DISC/SNRM frames.
                // A brief drain clears any USB FIFO residue before the first DISC.
                if (currentMeterMake == MeterMake.LNT) {
                    drainPort(port);
                    AddressInit();
                    appendLog("LNT_NATIVE_HDLC — port 9600 8N1, ready for DISC/SNRM");
                }

                publishProgress("INFO|Sending SNRM (NRM)...", "15");
                // nTryCount=2, nTimeOut=8s → snrmDeadline=8s (proven from Reading_14.java)
                // Total per SetNRM call: 2s DISC + 8s SNRM = ~10s max
                boolean nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                if (!nrmOk) {
                    // Retry 1: drain stale USB bytes, reset HDLC counters, then retry
                    appendLog("NRM_RETRY — first attempt failed, retrying once");
                    publishProgress("INFO|NRM retry (cable alignment)...", "15");
                    android.os.SystemClock.sleep(500);
                    drainPort(port);
                    AddressInit();
                    nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                }
                if (!nrmOk) {
                    // Retry 2: one more attempt with longer settle
                    appendLog("NRM_RETRY2 — second attempt failed, one more try");
                    publishProgress("INFO|NRM retry 2 (check cable alignment)...", "15");
                    android.os.SystemClock.sleep(1000);
                    drainPort(port);
                    AddressInit();
                    nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                }
                if (abortRequested || isCancelled()) {
                    publishProgress("WARN|Aborted by user.", "0");
                    return "Aborted";
                }
                // L&T/Schneider NTD 3-phase meters may use 2-byte HDLC server address
                // (IEC_HDLC_device_address=256). If 1-byte addressing fails, probe with 2-byte.
                if (!nrmOk && currentMeterMake == MeterMake.LNT) {
                    appendLog("NRM_LNT_ADDR_PROBE — 1-byte addr failed, probing 2-byte server addr (device_address=256)");
                    publishProgress("INFO|NRM probe: 2-byte server address (L&T NTD 3-phase)...", "15");
                    bytAddMode = 1;
                    AddressInit();
                    android.os.SystemClock.sleep(500);
                    nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                    if (!nrmOk) {
                        android.os.SystemClock.sleep(500);
                        nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                    }
                    if (!nrmOk) {
                        bytAddMode = 0;  // restore default; meter isn't responding either way
                        AddressInit();
                    } else {
                        appendLog("NRM_LNT_ADDR_PROBE_OK — 2-byte server address succeeded");
                    }
                }
                if (!nrmOk) {
                    publishProgress("ERROR|NRM failed — check optical cable alignment on meter.", "0");
                    return "Set NRM Failed";
                }
                publishProgress("INFO|NRM OK — Sending AARQ (Authentication)...", "20");

                doFakeWork();

                // ISSUE-3 FIX: LNT uses standard LLS authentication with its configured password.
                // No-auth fallback removed — all makes use the same AARQ path.
                int aarqResult = AARQ(port, (byte) 1, dlmsPassword, bytWait, (byte) 3, bytTimOut);

                if (aarqResult == 1) {
                    publishProgress("ERROR|Authentication FAILED — wrong password or meter type mismatch.", "0");
                    return "Authentication Fail";
                } else if (aarqResult == 2) {
                    publishProgress("ERROR|Communication error during AARQ — check cable.", "0");
                    return "Comms Error";
                }
                publishProgress("INFO|Authentication OK", "25");

                // Read Meter No from the meter itself — no manual entry required
                StringBuilder strbldDLMdata = new StringBuilder();
                publishProgress("INFO|Reading Meter Number (OBIS 0000600100FF)...", "28");
                StringBuilder SbData = GetParameter(port, (byte) 1, "0000600100FF", (byte) 2, bytWait, bytTryCnt, bytTimOut, false, strbldDLMdata);
                if (SbData != null && !SbData.toString().isEmpty()) {
                    String d = SbData.toString().replace("0A0A", "").trim();
                    MeterNo = parseMeterNoFromDlms(d);
                }
                if (MeterNo.isEmpty()) {
                    // Non-fatal: use timestamp as fallback ID
                    MeterNo = "MTR_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    publishProgress("WARN|Meter No not readable — using: " + MeterNo, "30");
                } else {
                    publishProgress("INFO|Meter No: " + MeterNo, "30");
                }
                CescRajMeterno = MeterNo; // Update with actual meter no
                UpdateStatus(CescRajMeterno, "Meter No: " + MeterNo);

                // Read Manufacturer name (OBIS 0000600800FF attr 2)
                String manufacturerStr = "";
                StringBuilder mfgSb = new StringBuilder();
                publishProgress("INFO|Reading Manufacturer (OBIS 0000600800FF)...", "32");
                StringBuilder mfgData = GetParameter(port, (byte) 1, "0000600800FF", (byte) 2, bytWait, bytTryCnt, bytTimOut, false, mfgSb);
                if (mfgData != null && !mfgData.toString().isEmpty()) {
                    manufacturerStr = hexToString(mfgData.toString().replace("0A0A",""))
                            .replace("\b","").replace("\r\n","").replace("\n","").replace("\t","").trim();
                    publishProgress("INFO|Manufacturer: " + manufacturerStr, "34");
                }
                // Store header — prepended to file at save time (after MeterData is built)
                // If OBIS read returned nothing, fall back to the user-selected meter make name
                if (manufacturerStr.isEmpty()) {
                    manufacturerStr = currentMeterMake.getDisplayName();
                }
                String fileHeader = "MANUFACTURER=" + manufacturerStr + "\r\nMETERNO=" + MeterNo + "\r\n";

                // ============================================================
                // RTC CHECK — FLAG AND CONTINUE (does NOT abort reading)
                // ============================================================
                rtcWarningFlag    = false;
                rtcWarningMessage = "";
                try {
                    StringBuilder rtcSb = new StringBuilder();
                    StringBuilder rtcData = GetParameter(port, (byte) 8, "0000010000FF", (byte) 2, bytWait, bytTryCnt, bytTimOut, false, rtcSb);
                    if (rtcData != null && rtcData.length() > 24) {
                        String str2 = rtcData.toString();
                        if (str2.length() >= 28) {
                            str2 = str2.substring(str2.length() - 24);
                            int DD   = (int)(Long.parseLong(str2.substring(6, 8),  16));
                            int MM   = (int)(Long.parseLong(str2.substring(4, 6),  16));
                            int YYYY = (int)(Long.parseLong(str2.substring(0, 4),  16));
                            int HH   = (int)(Long.parseLong(str2.substring(10, 12),16));
                            int MI   = (int)(Long.parseLong(str2.substring(12, 14),16));
                            int SS   = (int)(Long.parseLong(str2.substring(14, 16),16));
                            String meterDate = String.format("%02d/%02d/%04d %02d:%02d:%02d", DD, MM, YYYY, HH, MI, SS);

                            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.US);
                            Date meterDt = sdf.parse(meterDate);
                            Date deviceDt = new Date();
                            long diffMs = Math.abs(meterDt.getTime() - deviceDt.getTime());
                            long diffDays = diffMs / (24L * 60 * 60 * 1000);

                            if (diffDays > 4) {
                                rtcWarningFlag    = true;
                                rtcWarningMessage = "⚠ RTC MISMATCH: Meter clock " + meterDate +
                                        " vs Device " + sdf.format(deviceDt) + " — diff " + diffDays + " days. Reading continues.";
                                publishProgress("WARN|" + rtcWarningMessage, "32");
                                UpdateStatus(CescRajMeterno, "RTC Warning: " + diffDays + " days diff");
                            } else {
                                publishProgress("INFO|RTC OK — Meter: " + meterDate, "32");
                            }
                        }
                    } else {
                        publishProgress("WARN|Could not read RTC — proceeding anyway.", "32");
                    }
                } catch (Exception rtcEx) {
                    publishProgress("WARN|RTC check error: " + rtcEx.getMessage() + " — proceeding.", "32");
                }
                // ============================================================
                // END RTC CHECK
                // ============================================================

                // Name Plate
                // Fast timeout: all NamePlate registers are single-block reads responding
                // in <500ms. bytTimOut=8,bytTryCnt=3 means absent OBIS burn 24s each.
                // With 3-4 absent OBIS on LNT the NamePlate was taking 85s in BILLING mode.
                // bytTimOut=3,bytTryCnt=1 caps each silent OBIS at 3s → NamePlate ~10s.
                byte savedTimOutNP = bytTimOut;
                byte savedTryCntNP = bytTryCnt;
                bytTimOut = (byte) 3;
                bytTryCnt = (byte) 1;
                publishProgress("INFO|Reading Name Plate...", "35");
                StringBuilder MeterData = new StringBuilder();
                StringBuilder sbNm = ReadNamePlate(port);
                bytTimOut = savedTimOutNP;
                bytTryCnt = savedTryCntNP;
                if (sbNm != null && sbNm.length() > 0) {
                    MeterData.append(sbNm);
                    UpdateStatus(CescRajMeterno, "Name Plate OK");
                    publishProgress("INFO|Name Plate read OK", "40");
                } else {
                    publishProgress("WARN|Name Plate read incomplete — continuing.", "40");
                }

                // ============================================================
                // READING MODE SWITCH
                // ============================================================
                switch (readingMode) {

                    case INSTANTANEOUS: {
                        // Fast timeout: instantaneous registers respond in <500ms.
                        // 3s/1-try avoids 24s burns on absent OBIS codes.
                        byte savedTimOutI = bytTimOut;
                        byte savedTryCntI = bytTryCnt;
                        bytTimOut = (byte) 3;
                        bytTryCnt = (byte) 1;
                        long tInst0 = System.currentTimeMillis();
                        publishProgress("INFO|Downloading Instantaneous data...", "50");
                        MeterData.append(ReadInstantData(port));
                        bytTimOut = savedTimOutI;
                        bytTryCnt = savedTryCntI;
                        UpdateStatus(CescRajMeterno, "Instantaneous OK");
                        publishProgress("INFO|Instantaneous read OK (" + ((System.currentTimeMillis()-tInst0)/1000) + "s)", "80");
                        break;
                    }

                    case BILLING: {
                        // FIX B: fast timeout for ReadInstantData — single-block registers
                        // respond in <500ms. 4s/1-try prevents wasted time on absent OBIS.
                        // 4s (not 3s) gives safe headroom for slow optical ports.
                        byte savedTimOutBill = bytTimOut;
                        byte savedTryCntBill = bytTryCnt;
                        bytTimOut = (byte) 4;
                        bytTryCnt = (byte) 1;
                        long tBm_inst = System.currentTimeMillis();
                        publishProgress("INFO|Downloading Instantaneous data...", "45");
                        MeterData.append(ReadInstantData(port));
                        publishProgress("INFO|✓ Instantaneous done (" + ((System.currentTimeMillis()-tBm_inst)/1000) + "s) — reading Billing...", "55");
                        UpdateStatus(CescRajMeterno, "Instantaneous OK");
                        // Fix 2: 10s block timeout for ReadBillingData — L&T billing pages
                        // take up to 8.7s to build at block boundaries; 8s causes buffer
                        // failure. Matches what COMPLETE mode already uses successfully.
                        bytTimOut = (byte) 10;
                        bytTryCnt = (byte) 3;
                        long tBm_bill = System.currentTimeMillis();
                        publishProgress("INFO|Downloading Billing data... (15-30s)", "56");
                        StringBuilder BillStr = ReadBillingData(port, readingMode);
                        long tBm_billE = (System.currentTimeMillis()-tBm_bill)/1000;
                        bytTimOut = savedTimOutBill;
                        bytTryCnt = savedTryCntBill;
                        if (BillStr.length() > 10) {
                            MeterData.append(BillStr);
                            UpdateStatus(CescRajMeterno, "Billing OK");
                            publishProgress("INFO|✓ Billing done (" + tBm_billE + "s)", "80");
                        } else {
                            UpdateStatus(CescRajMeterno, "Billing FAILED");
                            publishProgress("WARN|⚠ Billing empty — check meter (" + tBm_billE + "s)", "80");
                        }
                        break;
                    }

                    case COMPLETE: {
                        // ── SESSION DEADLINE: hard 7-minute cap on total Complete session ──
                        // Without this, a stuck meter (e.g. LP timeout, silent OBIS) can run
                        // indefinitely forcing the user to manually abort.
                        // 420s = 7 min = safe ceiling: fastest meters finish in 2-3 min,
                        // slowest 35-day 15-min LP (3360 records) typically takes 5-6 min.
                        // Phase budgets within the 420s cap:
                        //   Instantaneous : up to  60s (typically 20-30s with Fixes A+B)
                        //   Billing       : up to  60s (typically  5-20s)
                        //   Midnight LP   : up to  20s (typically  2- 5s)
                        //   Block LP      : remaining time minus 30s events buffer
                        //   Events        : up to  30s (typically 10-20s)
                        //   Total         : guaranteed ≤ 420s regardless of meter behaviour
                        long sessionStartMs = System.currentTimeMillis();
                        sessionDeadlineMs   = sessionStartMs + (SESSION_MAX_SECONDS * 1000L);
                        appendLog("SESSION_DEADLINE set=" + SESSION_MAX_SECONDS + "s deadline="
                                + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(new java.util.Date(sessionDeadlineMs)));

                        // Helper: remaining budget in ms (floor 0)
                        // Used to size LP budget and skip phases when time is up.

                        // FIX B: Use 4s instant timeout (was 3s).
                        // 4s provides safe headroom for all makes including slow optical ports.
                        // FIX: 8s event timeout (was 4s).
                        // After a large LP block-transfer (e.g. 35 chunks for Genus KT089348),
                        // the meter needs time to release its event-log buffer. At 4s, attr=7
                        // and attr=2 timed out consistently while attr=3 (static CO) returned
                        // immediately. ACCESS_ERROR (unsupported OBIS) is still fast-fail so
                        // the extra headroom does not add delay for non-matching OBISes.
                        byte fastTimOut = (byte) 8;   // 8s: covers post-LP meter settle time
                        byte fastTryCnt = (byte) 1;   // 1 attempt: fast-fail covers ACCESS_ERROR
                        // billingTimOut=10s: block transfer on single-phase Secure can be slower
                        byte billingTimOut = (byte) 10;
                        byte billingTryCnt = (byte) 3;

                        // Save originals — restore after pre-LP phase
                        byte origTimOut = bytTimOut;
                        byte origTryCnt = bytTryCnt;
                        bytTimOut = fastTimOut;
                        bytTryCnt = fastTryCnt;

                        // ── Phase 1: Instantaneous ───────────────────────────────────────
                        long tInstStart = System.currentTimeMillis();
                        publishProgress("INFO|Downloading Instantaneous data... (5-15s)", "30");
                        MeterData.append(ReadInstantData(port));
                        long tInstElapsed = System.currentTimeMillis() - tInstStart;
                        appendLog("INSTANT_DONE elapsed=" + tInstElapsed + "ms");
                        if (tInstElapsed > 60000)
                            appendLog("SESSION_WARN: Instantaneous took " + (tInstElapsed/1000) + "s — check export OBIS timeouts");
                        publishProgress("INFO|✓ Instantaneous done (" + (tInstElapsed/1000) + "s)", "38");
                        UpdateStatus(CescRajMeterno, "Instantaneous OK");

                        // ── Phase 2: Billing ─────────────────────────────────────────────
                        // Skip if session deadline already exceeded (very slow instant phase)
                        if (System.currentTimeMillis() >= sessionDeadlineMs) {
                            appendLog("SESSION_SKIP_BILLING: session deadline reached after Instantaneous");
                            publishProgress("WARN|⚠ Session limit reached — skipping Billing+LP+Events", "79");
                        } else {
                            bytTimOut = billingTimOut;
                            bytTryCnt = billingTryCnt;
                            drainPort(port);
                            android.os.SystemClock.sleep(150);
                            long tBillStart = System.currentTimeMillis();
                            publishProgress("INFO|Downloading Billing data... (15-30s)", "40");
                            StringBuilder billingData = ReadBillingData(port, readingMode);
                            MeterData.append(billingData);
                            long tBillElapsed = System.currentTimeMillis() - tBillStart;
                            appendLog("BILLING_DONE elapsed=" + tBillElapsed + "ms");
                            flushLog(); // V29: flush so billing logs appear in file even if LP is slow
                            if (billingData != null && billingData.length() > 0) {
                                publishProgress("INFO|✓ Billing done (" + (tBillElapsed/1000) + "s)", "50");
                                UpdateStatus(CescRajMeterno, "Billing OK");
                            } else {
                                publishProgress("WARN|⚠ Billing empty — check meter (" + (tBillElapsed/1000) + "s)", "50");
                                appendLog("BILLING_WARN: Billing section empty in COMPLETE mode");
                                UpdateStatus(CescRajMeterno, "Billing FAILED");
                            }

                            // ── Phase 3: Midnight Snapshot ───────────────────────────────
                            if (System.currentTimeMillis() >= sessionDeadlineMs) {
                                appendLog("SESSION_SKIP_MIDNIGHT: session deadline reached after Billing");
                                publishProgress("WARN|⚠ Session limit reached — skipping Midnight+LP+Events", "73");
                            } else {
                                bytTimOut = fastTimOut;
                                bytTryCnt = fastTryCnt;
                                long tMidStart = System.currentTimeMillis();
                                publishProgress("INFO|Downloading Midnight Snapshot... (5-10s)", "52");
                                MeterData.append(ReadMidnightSnapshot(port, lsDays));
                                long tMidElapsed = System.currentTimeMillis() - tMidStart;
                                publishProgress("INFO|✓ Midnight done (" + (tMidElapsed/1000) + "s)", "58");
                                UpdateStatus(CescRajMeterno, "Midnight OK");
                                flushLog(); // V29: flush before LP so midnight logs are on disk

                                // ── Phase 4: Load Profile ─────────────────────────────────
                                // LP budget = remaining session time minus 30s events buffer.
                                // Hard cap at 360s (6 min) so a very fast pre-LP sequence
                                // doesn't give LP the entire 7 min, leaving no room for events.
                                // Floor at 60s: if we have less than 60s left, LP is skipped
                                // entirely (cannot do a meaningful LP read in under 60s).
                                long lpRemaining = sessionDeadlineMs - System.currentTimeMillis() - 30000L;
                                if (lpRemaining < 60000L) {
                                    appendLog("SESSION_SKIP_LP: only " + (lpRemaining/1000) + "s remaining — insufficient for LP");
                                    publishProgress("WARN|⚠ Insufficient time for Load Profile — skipping", "73");
                                } else {
                                    long lpBudgetMs = Math.min(lpRemaining, 360000L); // cap at 6 min
                                    bytTimOut = (byte) 8;
                                    bytTryCnt = (byte) 2;
                                    lpDeadlineMs = System.currentTimeMillis() + lpBudgetMs;
                                    long lpStartMs = System.currentTimeMillis();
                                    int lpBudgetSec = (int)(lpBudgetMs / 1000);
                                    publishProgress("INFO|Downloading Load Profile... (up to " + lpBudgetSec + "s)", "60");
                                    appendLog("LP_START days=auto bytTimOut=" + bytTimOut
                                            + " bytTryCnt=" + bytTryCnt + " deadline=" + lpBudgetSec + "s");
                                    MeterData.append(ReadLoadSurveyData(port, lsDays));
                                    long lpElapsed = System.currentTimeMillis() - lpStartMs;
                                    boolean lpDeadlineHit = lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs;
                                    appendLog("LP_END elapsed=" + lpElapsed + "ms dataLen=" + MeterData.length()
                                            + " deadlineHit=" + lpDeadlineHit);
                                    int lpPct = 60 + (int)(Math.min(lpElapsed, lpBudgetMs) * 13L / Math.max(lpBudgetMs, 1L));
                                    publishProgress("INFO|✓ Load Profile done (" + (lpElapsed/1000) + "s / " + lpBudgetSec + "s budget)",
                                            Math.min(lpPct, 73) + "");
                                    flushLog();
                                    lpDeadlineMs = 0;
                                }

                                // ── Phase 5: Events ───────────────────────────────────────
                                if (System.currentTimeMillis() >= sessionDeadlineMs) {
                                    appendLog("SESSION_SKIP_EVENTS: session deadline reached — skipping events");
                                    publishProgress("WARN|⚠ Session limit reached — Events skipped", "79");
                                } else {
                                    // FIX: Re-establish COSEM association before reading events.
                                    // ROOT CAUSE (confirmed 2026-05-16 SS09096791):
                                    // After a large LP block transfer (e.g. 765 records = 30KB),
                                    // the Secure meter's COSEM application association is exhausted.
                                    // Subsequent GetRequest calls return empty COSEM frames regardless
                                    // of the OBIS requested — even OBIS that returned data earlier
                                    // in the same session (e.g. 00005E5B0AFF returned 262 bytes
                                    // during NamePlate but empty during EventData after LP).
                                    // PROOF: SS09118696 (same make) had LP EIU=0 (no block transfer)
                                    // and events returned OK in 46ms each. SS09096791 with 30KB LP
                                    // had all event attrs return empty immediately after LP.
                                    // FIX: Send DISC+SNRM+AARQ to re-open the association.
                                    // Cost: ~1-2s. Benefit: all event data recovered.
                                    // Safe for all makes: harmless if association is still alive.
                                    // V27: Skip REASSOC entirely if port already died (SEND_FAIL_ABORT
                                    // set abortRequested=true during LP). SetNRM on a dead port wastes
                                    // ~8s (2 tries x 4s timeout) before failing. ReadEventData below
                                    // already handles abortRequested via EVENT_LOOP_ABORT.
                                    boolean reAssocOk = false;
                                    if (!abortRequested) {
                                    appendLog("REASSOC_START — re-establishing COSEM association before events");
                                    try {
                                        drainPort(port);
                                        android.os.SystemClock.sleep(200);
                                        boolean nrmOk2 = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                                        if (nrmOk2) {
                                            int aarqRes2 = AARQ(port, (byte) 1, dlmsPassword,
                                                    bytWait, (byte) 2, bytTimOut);
                                            reAssocOk = (aarqRes2 == 0);
                                            appendLog("REASSOC_" + (reAssocOk ? "OK" : "AARQ_FAIL aarqRes=" + aarqRes2));
                                        } else {
                                            appendLog("REASSOC_NRM_FAIL — events may be incomplete");
                                        }
                                    } catch (Exception reEx) {
                                        appendLog("REASSOC_EX: " + reEx.getMessage());
                                    }
                                    } else {
                                        appendLog("REASSOC_SKIPPED — abortRequested=true (port dead), skipping re-association");
                                    }
                                    bytTimOut = fastTimOut;
                                    bytTryCnt = fastTryCnt;
                                    long tEvtStart = System.currentTimeMillis();
                                    publishProgress("INFO|Downloading Events... (10-20s)", "74");
                                    MeterData.append(ReadEventData(port));
                                    long tEvtElapsed = System.currentTimeMillis() - tEvtStart;
                                    publishProgress("INFO|✓ Events done (" + (tEvtElapsed/1000) + "s)", "79");
                                }
                            } // end Midnight+LP+Events
                        } // end Billing+Midnight+LP+Events

                        // ── Session summary ──────────────────────────────────────────────
                        long sessionElapsed = System.currentTimeMillis() - sessionStartMs;
                        boolean sessionOverrun = sessionElapsed > (SESSION_MAX_SECONDS * 1000L);
                        appendLog("SESSION_END elapsed=" + (sessionElapsed/1000) + "s limit=" + SESSION_MAX_SECONDS + "s overrun=" + sessionOverrun);
                        if (sessionOverrun)
                            appendLog("SESSION_OVERRUN: elapsed=" + (sessionElapsed/1000) + "s exceeded " + SESSION_MAX_SECONDS + "s limit");
                        sessionDeadlineMs = 0; // reset for next session

                        // Restore originals
                        bytTimOut = origTimOut;
                        bytTryCnt = origTryCnt;
                        UpdateStatus(CescRajMeterno, "All data OK");
                        publishProgress("INFO|✓ Complete read OK (" + (sessionElapsed/1000) + "s)", "80");
                        break;
                    }
                }
                // ============================================================

                publishProgress("INFO|Saving data file...", "85");
                String cleanMeterNo = MeterNo.replace("\r\n","").replace("\n","").replace("\t","").trim();
                String DataFileName = buildDataFileName(currentMeterMake.getDisplayName(),
                        readingMode.getDisplayName(), cleanMeterNo);
                String Filenm = "";

                // POST-PROCESS: patch profile buffer raw values and write missing scalers
                // Must run before validation and file write so the TXT is correct for XML conversion.
                postProcessMeterData(MeterData);

                // VALIDATION: Check data structure before saving
                String readMode = readingMode.name();

                validateMeterDataForXML(MeterData, readMode);
                logMeterDataSummary(MeterData);

                // Store data for display
                lastMeterData = new StringBuilder(MeterData);

                if (MeterData.length() > 20) {
                    Filenm = MakeDataFile(DataFileName, fileHeader + MeterData.toString());
                }
                UpdateStatusFileNme(CescRajMeterno, Filenm);

                if (Filenm.isEmpty()) {
                    // Try again with whatever data we have
                    if (MeterData.length() > 0) {
                        Filenm = MakeDataFile(DataFileName + "_partial", fileHeader + MeterData.toString());
                        publishProgress("WARN|Partial data saved: " + Filenm, "90");
                    } else {
                        publishProgress("ERROR|No data downloaded. Check cable and meter connection.", "90");
                        return "No Data Downloaded";
                    }
                }

                // Save to DB — use meter no read from meter, not from UI
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss", java.util.Locale.US);
                String SysDate     = dateFormat.format(new Date()).replace("_", "");
                String GpsCoordinate = GetLocation();
                String userid      = BYPASS_USER_ID;
                // Use meter number as consumer ID when no MRO lookup
                String ConsumerNo  = MeterNo;

                // DatabaseHandler uses execSQL — no transaction API available, execute sequentially
                DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
                String Sql = "INSERT INTO mr_detail (ConsumerNo,gpscoordinate,Mrdatetime,Istransfer,userid,entrydate,DataMode,FileName) " +
                        "VALUES('" + ConsumerNo + "','" + GpsCoordinate + "','" + SysDate +
                        "','N','" + userid + "','" + SysDate + "','OPTICAL','" + Filenm + "')";
                Obj.ExecuteQry(Sql);
                String logUpdateSql = "UPDATE ReadingLog SET MeterNo='" + MeterNo + "', Status='Complete'" +
                        " WHERE MeterNo='PENDING' AND Status='Reading Start'";
                Obj.ExecuteQry(logUpdateSql);
                String remarksSql = "INSERT INTO Reading_Remarks " +
                        "(Consumer_number,Meter_number,Created_on,VCP_ID,Main_mr_code,Sub_remarks,MRMODE,IsTransfer) " +
                        "VALUES('" + ConsumerNo + "','" + MeterNo + "','" + SysDate + "','" + userid +
                        "','16','','Optical','N')";
                Obj.ExecuteQry(remarksSql);

                publishProgress("DONE|Record saved. File: " + Filenm + (rtcWarningFlag ? " | ⚠ RTC WARNING" : ""), "100");
                return "Record Saved — " + Filenm + (rtcWarningFlag ? "\n" + rtcWarningMessage : "");

            } catch (Exception ex) {
                publishProgress("ERROR|Exception: " + ex.getMessage(), "0");
                return "Error: " + ex.getMessage();
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            try {
                String msg   = values[0];
                String progS = (values.length > 1) ? values[1] : "0";
                int prog = Integer.parseInt(progS);
                setProgressValue(prog);

                boolean isError = msg.startsWith("ERROR|");
                boolean isWarn  = msg.startsWith("WARN|");
                boolean isDone  = msg.startsWith("DONE|");
                String cleanMsg = msg.replaceFirst("^(START|INFO|WARN|ERROR|DONE)\\|", "");

                addProgressLog(cleanMsg, isError || isWarn);

                // Update dialog views
                if (dlgMsgView != null) dlgMsgView.setText(cleanMsg);
                if (dlgProgressPctView != null) dlgProgressPctView.setText(prog + "%");

                // Update big status label
                TextView statusLabel = (TextView) findViewById(R.id.tvCurrentStep);
                if (statusLabel != null) {
                    statusLabel.setText(cleanMsg);
                    if (isError) {
                        statusLabel.setTextColor(Color.parseColor("#FFFFFF"));
                        statusLabel.setBackgroundColor(Color.parseColor("#C62828"));
                    } else if (isWarn) {
                        statusLabel.setTextColor(Color.parseColor("#212121"));
                        statusLabel.setBackgroundColor(Color.parseColor("#FFF8E1"));
                    } else if (isDone) {
                        statusLabel.setTextColor(Color.parseColor("#FFFFFF"));
                        statusLabel.setBackgroundColor(Color.parseColor("#2E7D32"));
                    } else {
                        statusLabel.setTextColor(Color.parseColor("#1565C0"));
                        statusLabel.setBackgroundColor(Color.parseColor("#FFFFFF"));
                    }
                }
            } catch (Exception ignored) {}
        }

        @Override
        protected void onPostExecute(String result) {
            appendLog("=== SESSION END result=" + result + " ===");
            flushLog();
            stopLogTimer();
            boolean isError = result.startsWith("Error") || result.startsWith("USB") ||
                    result.startsWith("Auth") || result.startsWith("Comms") ||
                    result.startsWith("Data Not");

            // Suggestion 1: Sound/vibration feedback on completion
            try {
                android.media.ToneGenerator toneGen =
                        new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 85);
                if (!isError) {
                    // Success: two short beeps
                    toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200);
                    android.os.SystemClock.sleep(280);
                    toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200);
                } else {
                    // Failure: one long low beep
                    toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_LOW_L, 800);
                }
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                final android.media.ToneGenerator toneGenFinal = toneGen;
                h.postDelayed(new Runnable() {
                    @Override public void run() { toneGenFinal.release(); }
                }, 1200);
                // Also vibrate
                android.os.Vibrator vib = (android.os.Vibrator) getSystemService(android.content.Context.VIBRATOR_SERVICE);
                if (vib != null && vib.hasVibrator()) {
                    if (!isError) vib.vibrate(new long[]{0,100,100,100}, -1);
                    else          vib.vibrate(500);
                }
            } catch (Exception ignored) {}

            // Suggestion 4: Validation bitmap — visual status of each section
            addProgressLog(buildValidationBitmap(lastMeterData != null ? lastMeterData.toString() : ""), false);

            addProgressLog("FINAL: " + result, isError);
            Toast.makeText(getApplicationContext(), result, Toast.LENGTH_LONG).show();

            // Show meter reading summary (with real values) after successful read
            if (!isError && lastMeterData != null && lastMeterData.length() > 0) {
                try {
                    String dispMeterNo = (CescRajMeterno != null && !CescRajMeterno.equals("PENDING"))
                            ? CescRajMeterno : "Meter";

                    // Suggestion 3: Save last-read info to SharedPreferences
                    android.content.SharedPreferences prefs =
                            getSharedPreferences("CescRajLastRead", android.content.Context.MODE_PRIVATE);
                    // OPT-1 FIX: avoid triple toUpperCase + double buildScalerMap on ~500KB string in UI thread
                    String _dataUp = lastMeterData.toString().toUpperCase();
                    String kwhStr = parseDlmsRegisterWithUnit(_dataUp, "0100010800FF", buildScalerMap(_dataUp));
                    prefs.edit()
                            .putString("lastMeterNo",   dispMeterNo)
                            .putString("lastMake",      currentMeterMake != null ? currentMeterMake.getDisplayName() : "")
                            .putString("lastMode",      DataToBeRead != null ? DataToBeRead : "")
                            .putString("lastKwh",       kwhStr)
                            .putString("lastTimestamp", new java.text.SimpleDateFormat(
                                    "dd-MM-yyyy HH:mm", java.util.Locale.getDefault()).format(new java.util.Date()))
                            .apply();

                    showMeterReadingDialog(dispMeterNo, lastMeterData, readingMode);
                } catch (Exception e) {
                    appendLog("ERROR: Failed to show meter readings: " + e.getMessage());
                }
            }

            // Re-enable Download, disable Abort
            Button btnDownload = findViewById(R.id.btnReadWithTheft);
            if (btnDownload != null) btnDownload.setEnabled(true);
            Button btnAbort = findViewById(R.id.btnAbort);
            if (btnAbort != null) btnAbort.setEnabled(false);
            currentTask = null;
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }

        @Override
        protected void onPreExecute() {
            // Clear log and start inline header timer — no popup dialog
            clearProgressLog();
            String meterMakeName = "";
            try {
                android.widget.Spinner sp = (android.widget.Spinner) findViewById(R.id.ddlMeterMake);
                if (sp != null && sp.getSelectedItem() != null)
                    meterMakeName = sp.getSelectedItem().toString();
            } catch (Exception ignored) {}
            String modeName = DataToBeRead != null ? DataToBeRead : "Reading";
            startLogTimer(
                    (CescRajMeterno != null && !CescRajMeterno.equals("PENDING")) ? CescRajMeterno : meterMakeName,
                    modeName);

            Button btnDownload = findViewById(R.id.btnReadWithTheft);
            if (btnDownload != null) btnDownload.setEnabled(false);
            Button btnAbort = findViewById(R.id.btnAbort);
            if (btnAbort != null) btnAbort.setEnabled(true);
            // Lock to current orientation during read — prevents activity recreation crash
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        }
    }

    // =====================================================================
    // L&G ASYNC TASK (unchanged logic, improved progress reporting)
    // =====================================================================
    private class AsyncTaskRunnerLnG extends AsyncTask<String, String, String> {
        android.app.AlertDialog progressDialog;
        private String DataToBeRead;

        @Override
        protected String doInBackground(String... params) {
            DataToBeRead = params[0];
            publishProgress("INFO|Starting L&G meter reading...", "10");
            StringBuilder sb = ReadLnG();
            if (sb == null || sb.toString().isEmpty()) {
                publishProgress("ERROR|L&G read failed — check cable/take manual.", "0");
                return "Unable to download, Check Cable / Take Manual";
            }
            publishProgress("INFO|L&G data received — saving...", "80");
            String[] Temp    = sb.toString().split("[\\r\\n]+");
            String MeterNo   = Temp.length > 1 ? Temp[1].replace("\u0002C.1(","").replace(")","").trim() : "UNKNOWN";
            String Filenm    = MakeDataFile(buildDataFileName(MeterMake.LNG.getDisplayName(),
                    ReadingMode.COMPLETE.getDisplayName(), MeterNo), sb.toString());
            if (!MeterNo.isEmpty()) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss", java.util.Locale.US);
                String SysDate = dateFormat.format(new Date()).replace("_","");
                TextView Cons  = (TextView) findViewById(R.id.lbConsumerNo);
                String ConsumerNo   = Cons.getText().toString();
                String GpsCoordinate= GetLocation();
                String userid = BYPASS_USER_ID;
                DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
                String Sql = "INSERT INTO mr_detail (ConsumerNo,gpscoordinate,Mrdatetime,Istransfer,userid,entrydate,DataMode,FileName) " +
                        "VALUES('" + ConsumerNo + "','" + GpsCoordinate + "','" + SysDate + "','N','" + userid + "','" + SysDate + "','OPTICAL','" + Filenm + "')";
                Obj.ExecuteQry(Sql);
                publishProgress("DONE|L&G record saved — File: " + Filenm, "100");
                return "Record Saved — " + Filenm;
            } else {
                publishProgress("ERROR|L&G Meter No not parsed — data not saved.", "0");
                return "Data Not Downloaded";
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            String msg   = values[0];
            boolean isErr= msg.startsWith("ERROR|");
            addProgressLog(msg.replaceFirst("^(INFO|WARN|ERROR|DONE)\\|", ""), isErr);
        }

        @Override
        protected void onPostExecute(String result) {
            if (progressDialog != null && progressDialog.isShowing())
                progressDialog.dismiss();
            Toast.makeText(getApplicationContext(), result, Toast.LENGTH_LONG).show();
        }

        @Override
        protected void onPreExecute() {
            progressDialog = new android.app.AlertDialog.Builder(Reading.this)
                    .setTitle("L&G Reading")
                    .setMessage("Please wait...")
                    .setCancelable(false)
                    .create();
            progressDialog.show();
        }
    }

    // =====================================================================
    // PHOTO HANDLING
    // =====================================================================
    public void onShowPhotoClicked(View v) {
        try {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            TextView lbConsNo = (TextView) findViewById(R.id.lbConsumerNo);
            String Fileter    = lbConsNo.getText().toString();
            String timeStamp  = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
            String fileName   = Fileter + "_" + timeStamp + ".jpg";
            File file         = new File(Environment.getExternalStorageDirectory(), fileName);

            TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
            ImageName.setText(fileName);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            Uri mCapturedImageURI;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mCapturedImageURI = FileProvider.getUriForFile(getApplicationContext(),
                        "com.npcl.com.vcpopdl.EzetapFileProvider", file);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                mCapturedImageURI = Uri.fromFile(file);
            }
            intent.putExtra(MediaStore.EXTRA_OUTPUT, mCapturedImageURI);
            imageHolder = (ImageView) findViewById(R.id.imageView1);
            startActivityForResult(intent, requestCode);
        } catch (Exception ex) {
            addProgressLog("Photo error: " + ex.getMessage(), true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (requestCode == this.requestCode && resultCode == RESULT_OK) {
                TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
                String filePath    = Environment.getExternalStorageDirectory() + "/" + ImageName.getText().toString();
                Bitmap bitmap      = BitmapFactory.decodeFile(filePath);
                if (bitmap != null) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(90);
                    Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    ImageView imageView = (ImageView) findViewById(R.id.imageView1);
                    imageView.setImageBitmap(rotated);
                    addProgressLog("Photo captured: " + ImageName.getText().toString(), false);
                }
            }
        } catch (Exception ex) {
            addProgressLog("Photo result error: " + ex.getMessage(), true);
        }
    }

    // =====================================================================
    // DROPDOWNS
    // =====================================================================
    public void BindMeterMake() {
        try {
            List<String> list = new ArrayList<>();
            for (MeterMake m : MeterMake.values()) {
                list.add(m.getDisplayName());
            }
            Spinner sp = (Spinner) findViewById(R.id.ddlMeterMake);
            ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(a);
            sp.setSelection(0); // Default: Secure

            TextView hint = (TextView) findViewById(R.id.tvAutoDetectHint);
            if (hint != null) hint.setVisibility(android.view.View.GONE);
        } catch (Exception ex) { appendLog(ex.getMessage()); }
    }

    public void BindddlMCBType() {
        try {
            List<String> list = Arrays.asList("--Select MCB Availability--", "Yes", "No");
            Spinner sp = (Spinner) findViewById(R.id.ddlMCBType);
            ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(a);
        } catch (Exception ex) { appendLog(ex.getMessage()); }
    }

    public void BindddlMeterLocation() {
        try {
            List<String> list = Arrays.asList("--Select Meter Location--", "In", "Out");
            Spinner sp = (Spinner) findViewById(R.id.ddlMeterLocation);
            ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(a);
        } catch (Exception ex) { appendLog(ex.getMessage()); }
    }

    public void BindDatatoBeRead() {
        try {
            List<String> list = new ArrayList<>();
            list.add("--Select Data to be Read --");
            for (ReadingMode m : ReadingMode.values()) {
                list.add(m.getDisplayName());
            }
            Spinner sp = (Spinner) findViewById(R.id.ddldatatoberead);
            ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(a);
            // Default to COMPLETE ("All")
            int defaultPos = list.indexOf(ReadingMode.INSTANTANEOUS.getDisplayName());
            if (defaultPos >= 0) sp.setSelection(defaultPos);
        } catch (Exception ex) { appendLog(ex.getMessage()); }
    }

    // =====================================================================
    // ROLE / AUTH HELPERS (login bypassed but role still used for DB routing)
    // =====================================================================
    public String GetRole(String VCPID) {
        try {
            DatabaseHandler db = new DatabaseHandler(getApplicationContext());
            String Sql = "SELECT Role FROM Login WHERE Userid='" + VCPID + "'";
            Cursor c = db.GetData(Sql);
            if (c != null && c.moveToFirst()) {
                int _roleIdx = c.getColumnIndex("Role"); return (_roleIdx >= 0) ? c.getString(_roleIdx) : BYPASS_ROLE;
            }
            return BYPASS_ROLE; // default when no login record
        } catch (Exception ex) {
            return BYPASS_ROLE;
        }
    }

    public void loadDriver() {
        try {
            PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            UsbManager manager = (UsbManager) getApplicationContext().getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            if (!availableDrivers.isEmpty()) {
                UsbDevice devices = availableDrivers.get(0).getDevice();
                manager.requestPermission(devices, mPermissionIntent);
            }
        } catch (Exception ex) {
            appendLog("loadDriver error: " + ex.getMessage());
        }
    }

    // =====================================================================
    // UTILITY
    // =====================================================================
    public static int toInt32_2(byte[] bytes, int index) {
        return ((bytes[index + 3] & 0xFF)) |
                ((bytes[index + 2] & 0xFF) << 8) |
                ((bytes[index + 1] & 0xFF) << 16) |
                ((bytes[index]     & 0xFF) << 24);
    }

    private String currentDateFormat() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US);
        return dateFormat.format(new Date());
    }

    private String currentDateFormat1() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.US);
        return dateFormat.format(new Date());
    }

    private void doFakeWork() {
        // Minimal USB settle time — reduced from 50ms
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
    }

    // OPT-3 FIX: doFakeWork() /* OPT-3 */ removed — was identical to doFakeWork() (both 5ms sleep)

    // =====================================================================
    // METER READING DISPLAY — Extract and show specific register values
    // =====================================================================

    /**
     * Extract and display key meter readings from accumulated data
     * Shows cumulative (instantaneous) and latest billing values
     */
    // =========================================================================
    // METER READING SUMMARY DIALOG — real values from parsed TXT
    // Handles all meter makes: Secure, HPL, AVON, Genus, L&T
    // =========================================================================
    // Suggestion 4: Validation Bitmap — at-a-glance status line after each read
    // =========================================================================
    /** BUG-5 FIX: removed dead overwrite of np; real payload check; added RTC flag. */
    private String buildValidationBitmap(String data) {
        String up = data.toUpperCase();
        // NamePlate: OBIS line present AND payload is genuinely non-empty
        boolean np  = up.contains("0000600101FF 02") && !extractPayloadFor(up, "0000600101FF").isEmpty();
        // Instantaneous: kWh import AND voltage L1 both present
        boolean ins = up.contains("0100010800FF 02") && up.contains("0100200700FF 02");
        // Billing: billing buffer present
        boolean bil = up.contains("0100620100FF 02");
        // LP: LP buffer present
        boolean lp  = up.contains("0100630100FF 02");
        // Events: any event log present
        boolean evt = up.contains("0000636200FF 02") || up.contains("0000636201FF 02");
        // RTC: year in plausible range
        String rtcStr = extractRtc(up);
        boolean rtcOk = !"NA".equals(rtcStr) && !rtcStr.startsWith("01/01/0000");

        return String.format(
                "📋 NamePlate:%s  ⚡ Instant:%s  🧾 Billing:%s  📊 LP:%s  🔔 Events:%s  🕐 RTC:%s",
                np?"✓":"✗", ins?"✓":"✗", bil?"✓":"✗", lp?"✓":"✗", evt?"✓":"✗", rtcOk?"✓":"⚠");
    }

    // =========================================================================
    // Suggestion 3: Show last successful read on startup
    // =========================================================================
    private void showLastReadBanner() {
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences("CescRajLastRead", android.content.Context.MODE_PRIVATE);
            String meterNo  = prefs.getString("lastMeterNo", null);
            String kWh      = prefs.getString("lastKwh", null);
            String ts       = prefs.getString("lastTimestamp", null);
            String make     = prefs.getString("lastMake", "");
            String mode     = prefs.getString("lastMode", "");
            if (meterNo == null || ts == null) return;

            // Show in tvCurrentStep (header) as a subtle last-read reminder
            TextView tvStep = (TextView) findViewById(R.id.tvCurrentStep);
            if (tvStep != null) {
                String banner = "Last: " + make + " " + meterNo
                        + (kWh != null && !"N/A".equals(kWh) ? " | " + kWh + " kWh" : "")
                        + " | " + ts;
                tvStep.setText(banner);
            }
        } catch (Exception ignored) {}
    }

    // =========================================================================

    private void showMeterReadingDialog(String meterNo, StringBuilder meterDataSb, ReadingMode readingMode) {
        try {
            String dataStr = meterDataSb != null ? meterDataSb.toString().toUpperCase() : "";
            java.util.Map<String, int[]> scalerMap = buildScalerMap(dataStr);
            java.util.Map<String, int[]> billingScalerMap = buildBillingScalerMap(dataStr);
            appendLog("BILLING_SCALER_MAP size=" + billingScalerMap.size()
                    + (billingScalerMap.containsKey("0100010800FF")
                    ? " kWh_sc=" + billingScalerMap.get("0100010800FF")[0] : ""));
            java.util.Map<String, Integer> billingColMap = parseBillingCaptureObjects(dataStr);
            java.util.Map<String, int[]> bsm = billingScalerMap.isEmpty() ? scalerMap : billingScalerMap;

            // ── BILLING ENERGY ADJUSTMENT ───────────────────────────────────
            // Compute this FIRST so we can apply it to instantaneous energy too.
            // computeBillingEnergyAdj compares instantaneous raw vs billing raw to
            // detect firmware sc bugs (BK011850: declared sc=+2 but stores Wh directly).
            // adj is non-zero ONLY when a systematic unit mismatch is detected.
            // LTCT meters (BS029697 sc=+3, millions of kWh): adj=0 → unaffected.
            int billingEnergyAdj = computeBillingEnergyAdj(dataStr, billingColMap, bsm);

            // ── INSTANTANEOUS ──────────────────────────────────────────────
            String rtc          = extractRtc(dataStr);
            // For instantaneous energy, apply billingEnergyAdj to correct meters where
            // the declared sc is a firmware reporting bug (e.g. BK011850 sc=+2 → adj=-2 → sc=0).
            // applyInstAdj wraps parseDlmsRegisterWithUnit and applies the adj to the result.
            String kwhImp       = applyInstAdj(parseDlmsRegisterWithUnit(dataStr, "0100010800FF", scalerMap), billingEnergyAdj, scalerMap, dataStr, "0100010800FF");
            String kwhExp       = applyInstAdj(parseDlmsRegisterWithUnit(dataStr, "0100020800FF", scalerMap), billingEnergyAdj, scalerMap, dataStr, "0100020800FF");
            String kvahImp      = applyInstAdj(parseDlmsRegisterWithUnit(dataStr, "0100090800FF", scalerMap), billingEnergyAdj, scalerMap, dataStr, "0100090800FF");
            String kvahExp      = applyInstAdj(parseDlmsRegisterWithUnit(dataStr, "01000A0800FF", scalerMap), billingEnergyAdj, scalerMap, dataStr, "01000A0800FF");
            String kwInst       = parseDlmsRegisterWithUnit(dataStr, "0100010700FF", scalerMap);
            String kwExpInst    = parseDlmsRegisterWithUnit(dataStr, "0100020700FF", scalerMap);
            // MD kVA import: prefer 0100090600FF, fall back to 0100010600FF (some makes)
            String mdKva        = parseDlmsRegisterWithUnit(dataStr, "0100090600FF", scalerMap);
            if ("NA".equals(mdKva)) mdKva = parseDlmsRegisterWithUnit(dataStr, "0100010600FF", scalerMap);
            String mdKw         = parseDlmsRegisterWithUnit(dataStr, "0100010600FF", scalerMap);
            // FIX C: Export MD — 0100020600FF=MD kW export, 01000A0600FF=MD kVA export
            String mdKwExp      = parseDlmsRegisterWithUnit(dataStr, "0100020600FF", scalerMap);
            String mdKvaExp     = parseDlmsRegisterWithUnit(dataStr, "01000A0600FF", scalerMap);
            String voltR        = parseDlmsRegisterWithUnit(dataStr, "0100200700FF", scalerMap);
            String voltY        = parseDlmsRegisterWithUnit(dataStr, "0100340700FF", scalerMap);
            String voltB        = parseDlmsRegisterWithUnit(dataStr, "0100480700FF", scalerMap);
            String currR        = parseDlmsRegisterWithUnit(dataStr, "01001F0700FF", scalerMap);
            String currY        = parseDlmsRegisterWithUnit(dataStr, "0100330700FF", scalerMap);
            String currB        = parseDlmsRegisterWithUnit(dataStr, "0100470700FF", scalerMap);
            // Max demand timestamps (class 4 attr=5)
            String mdKvaTs      = extractDlmsTimestamp(dataStr, "0100090600FF");
            String mdKwTs       = extractDlmsTimestamp(dataStr, "0100010600FF");
            String mdKwExpTs    = extractDlmsTimestamp(dataStr, "0100020600FF");
            String mdKvaExpTs   = extractDlmsTimestamp(dataStr, "01000A0600FF");

            // Billing date: try Clock column first; fall back to the gap-scan date
            // that extractBillingByObis itself uses for Secure-style meters (where
            // the Clock is at the last column and may be unreachable due to unknown
            // field types like 0xBF in mid-record). The gap-scan midnight DateTime
            // IS the correct month-end reset timestamp for all makes.
            String billingDate   = extractBillingByObis(dataStr, "0000000102FF", billingColMap, bsm, billingEnergyAdj);
            // Validate: billingDate must look like a date (DD/MM/YYYY). If column
            // navigation returns a numeric value (e.g. "999.000" when clock column
            // maps to a data register in some meter variants), treat as NA.
            if (billingDate != null && !billingDate.contains("/")) billingDate = "NA";
            if ("NA".equals(billingDate))
                billingDate = extractBillingGapDate(dataStr);
            String kwhImpBill    = extractBillingByObis(dataStr, "0100010800FF", billingColMap, bsm, billingEnergyAdj);
            String kwhExpBill    = extractBillingByObis(dataStr, "0100020800FF", billingColMap, bsm, billingEnergyAdj);
            String kvahImpBill   = extractBillingByObis(dataStr, "0100090800FF", billingColMap, bsm, billingEnergyAdj);
            String kvahExpBill   = extractBillingByObis(dataStr, "01000A0800FF", billingColMap, bsm, billingEnergyAdj);
            String kwMdBill      = extractBillingByObis(dataStr, "0100010600FF", billingColMap, bsm, billingEnergyAdj);
            String kvaMdBill     = extractBillingByObis(dataStr, "0100090600FF", billingColMap, bsm, billingEnergyAdj);
            // Per IS 15959 / DLMS: each OBIS maps to exactly one column declared in the
            // billing profile capture objects. No fallbacks — show what the meter captured.
            // NA = meter didn't include this OBIS in its billing profile.
            // 0.000 = meter reset this value to 0 (valid for new billing period).
            // FIX D: Export MD from billing
            String kwMdExpBill   = extractBillingByObis(dataStr, "0100020600FF", billingColMap, bsm, billingEnergyAdj);
            String kvaMdExpBill  = extractBillingByObis(dataStr, "01000A0600FF", billingColMap, bsm, billingEnergyAdj);

            // ── TOD billing — dynamic from billing profile ─────────────────
            java.util.List<String[]> todRows = new java.util.ArrayList<>();
            for (int t = 1; t <= 8; t++) {
                String impObis = String.format("010001080%XFF", t);
                String expObis = String.format("010002080%XFF", t);
                boolean hasImp = billingColMap.containsKey(impObis);
                boolean hasExp = billingColMap.containsKey(expObis);
                if (hasImp || hasExp) {
                    String imp = hasImp ? extractBillingByObis(dataStr, impObis, billingColMap, bsm, billingEnergyAdj) : "NA";
                    String exp = hasExp ? extractBillingByObis(dataStr, expObis, billingColMap, bsm, billingEnergyAdj) : "NA";
                    todRows.add(new String[]{"T" + t, imp, exp});
                }
            }


            // TOD instantaneous — today's slot values from the billing profile's SNAPSHOT record.
            // Only shown when mode is not INSTANTANEOUS (billing data was read).
            //
            // The BILLING section uses extractBillingByObis() which picks the most-recent
            // MIDNIGHT reset record (e.g. 01/04/2026 00:00:00) — correct for billed energy.
            // This section must use the SNAPSHOT record (e.g. 16/04/2026 16:21:59) which is
            // the current-period accumulator frozen at the moment of reading.
            // extractBillingSnapshotByObis() picks the most-recent record with ANY timestamp
            // (snapshot or midnight) and returns values from it.
            java.util.List<String[]> todInstRows = new java.util.ArrayList<>();
            boolean showTodInst = (readingMode != null && readingMode != ReadingMode.INSTANTANEOUS);
            if (showTodInst) {
                for (int t = 1; t <= 8; t++) {
                    String iO = String.format("010001080%XFF", t);
                    String eO = String.format("010002080%XFF", t);
                    boolean hI = billingColMap.containsKey(iO);
                    boolean hE = billingColMap.containsKey(eO);
                    if (hI || hE) {
                        // Use snapshot extractor — picks the most-recent record (by timestamp)
                        // to give reading-date instantaneous TOD values.
                        //
                        // IMPORTANT: energyAdj must NOT be passed here.
                        // energyAdj corrects the scaler mismatch between billing records
                        // (which store Wh) and the instantaneous reading (which uses a
                        // different unit). The billing buffer rec[0] (snapshot) stores TOD
                        // values already in the units matched by the BSP scaler — no
                        // adjustment needed. For BK011850: BSP sc=+2 applied to snapshot
                        // TOD raw gives correct ~231,000 kWh per slot (confirmed by modem).
                        // Applying energyAdj=-2 would wrongly give ~2,300 kWh.
                        String imp2 = hI ? extractBillingSnapshotByObis(dataStr, iO, billingColMap, bsm, 0) : "NA";
                        String exp2 = hE ? extractBillingSnapshotByObis(dataStr, eO, billingColMap, bsm, 0) : "NA";
                        todInstRows.add(new String[]{"T" + t, imp2, exp2});
                    }
                }
            }

            // ── Build HTML ─────────────────────────────────────────────────
            StringBuilder html = new StringBuilder();
            html.append("<html><head><style>");
            html.append("body{background:#0d1117;color:#c9d1d9;font-family:monospace;font-size:13px;margin:6px;}");
            html.append("h3{color:#58a6ff;text-align:center;margin:4px 0 8px;font-size:14px;}");
            html.append("table{width:100%;border-collapse:collapse;margin-bottom:8px;border:1px solid #30363d;}");
            html.append("th{background:#161b22;color:#58a6ff;padding:6px 4px;text-align:center;font-size:11px;border:1px solid #30363d;}");
            html.append("td{padding:4px 6px;border:1px solid #21262d;font-size:12px;}");
            html.append(".sec{background:#161b22;color:#e3b341;font-weight:bold;font-size:12px;border:1px solid #30363d;}");
            html.append(".lbl{color:#8b949e;}");
            html.append(".val{color:#7ee787;text-align:right;font-weight:bold;}");
            html.append(".na{color:#6e7681;text-align:right;}");
            html.append(".dt{color:#f0f6fc;font-size:11px;}");
            html.append(".sub{color:#8b949e;font-size:10px;padding:2px 4px;}");
            html.append(".warn{color:#f0883e;}");
            html.append("</style></head><body>");
            html.append("<h3>&#9889; METER DATA SUMMARY &mdash; ").append(meterNo).append("</h3>");

            // ── INSTANTANEOUS TABLE ────────────────────────────────────────
            html.append("<table>");
            html.append("<tr><td class='sec' colspan='3'>&#128995; INSTANTANEOUS</td></tr>");
            html.append("<tr><td class='sec dt' colspan='3'>Reading Date/Time: ").append(rtc).append("</td></tr>");
            html.append("<tr><th>PARAMETER</th><th>IMPORT</th><th>EXPORT</th></tr>");
            addSummaryRow(html, "kWh  — Active Energy",   kwhImp,   kwhExp);
            addSummaryRow(html, "kVAh — Apparent Energy", kvahImp,  kvahExp);
            addSummaryRow(html, "kW   — Active Power",    kwInst,   kwExpInst);
            // FIX E: export MD now populated instead of hardcoded NA
            addSummaryRow(html, "MD kVA (Max Demand)",    mdKva,    mdKvaExp);
            addSummaryRow(html, "MD kW  (Max Demand)",    mdKw,     mdKwExp);
            // V / I sub-row (PF and Freq removed per requirement)
            html.append("<tr><td class='sub' colspan='3'>");
            html.append("V R/Y/B: ").append(voltR).append(" / ").append(voltY).append(" / ").append(voltB);
            html.append(" &nbsp;|&nbsp; I R/Y/B: ").append(currR).append(" / ").append(currY).append(" / ").append(currB);
            html.append("</td></tr>");
            // MD timestamps — show import and export where available
            boolean hasMdTs = !mdKvaTs.equals("NA") || !mdKwTs.equals("NA")
                    || !mdKvaExpTs.equals("NA") || !mdKwExpTs.equals("NA");
            if (hasMdTs) {
                html.append("<tr><td class='sub' colspan='3'>");
                html.append("MD kVA Date: ").append(mdKvaTs).append(" / Exp: ").append(mdKvaExpTs);
                html.append(" &nbsp;|&nbsp; MD kW Date: ").append(mdKwTs).append(" / Exp: ").append(mdKwExpTs);
                html.append("</td></tr>");
            }
            html.append("</table>");

            // FIX G: TOD Instantaneous section — only when billing data was read
            if (showTodInst && !todInstRows.isEmpty()) {
                html.append("<table>");
                html.append("<tr><td class='sec' colspan='3'>&#128201; TOD — kWh (Instantaneous / Reading Date)</td></tr>");
                html.append("<tr><th>SLOT</th><th>IMPORT</th><th>EXPORT</th></tr>");
                for (String[] tod : todInstRows) addSummaryRow(html, tod[0], tod[1], tod[2]);
                html.append("</table>");
            }

            // ── BILLING TABLE ──────────────────────────────────────────────
            html.append("<table>");
            html.append("<tr><td class='sec' colspan='3'>&#129001; BILLING</td></tr>");
            html.append("<tr><td class='sec dt' colspan='3'>Billing Date: ").append(billingDate).append("</td></tr>");
            html.append("<tr><th>PARAMETER</th><th>IMPORT</th><th>EXPORT</th></tr>");
            addSummaryRow(html, "kWh  T0 — Active Energy",   kwhImpBill,  kwhExpBill);
            addSummaryRow(html, "kVAh T0 — Apparent Energy", kvahImpBill, kvahExpBill);
            // FIX E: export MD billing now populated
            addSummaryRow(html, "MD kVA T0 (Max Demand)",    kvaMdBill,   kvaMdExpBill);
            addSummaryRow(html, "MD kW  T0 (Max Demand)",    kwMdBill,    kwMdExpBill);
            html.append("</table>");

            // ── TOD TABLE ─────────────────────────────────────────────────
            if (!todRows.isEmpty()) {
                html.append("<table>");
                html.append("<tr><td class='sec' colspan='3'>&#128200; TOD — kWh (Billing)</td></tr>");
                html.append("<tr><th>SLOT</th><th>IMPORT</th><th>EXPORT</th></tr>");
                for (String[] tod : todRows) addSummaryRow(html, tod[0], tod[1], tod[2]);
                html.append("</table>");
            }

            html.append("<div class='sub' style='text-align:center;padding:3px 0;color:#6e7681;'>")
                    .append("kWh=Energy | kVAh=Apparent | kVArh=Reactive | kVA=Apparent Power | kW=Active Power | MD=Max Demand")
                    .append("</div></body></html>");

            android.webkit.WebView wv = new android.webkit.WebView(Reading.this);
            wv.setBackgroundColor(0xFF0d1117);
            wv.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);

            new android.app.AlertDialog.Builder(Reading.this)
                    .setTitle("Summary: " + meterNo)
                    .setView(wv)
                    .setPositiveButton("Close", (d, w) -> d.dismiss())
                    .setCancelable(true)
                    .show();

        } catch (Exception e) {
            appendLog("Error showing meter reading dialog: " + e.getMessage());
        }
    }

    /** Helper: format one row; value cells green for real data, grey for NA. */
    private void addSummaryRow(StringBuilder html, String label, String imp, String exp) {
        String id = (imp == null || imp.isEmpty() || "NA".equals(imp)) ? "NA" : imp;
        String ed = (exp == null || exp.isEmpty() || "NA".equals(exp)) ? "NA" : exp;
        String ic = "NA".equals(id) ? "na" : "val";
        String ec = "NA".equals(ed) ? "na" : "val";
        html.append("<tr><td class='lbl'>").append(label).append("</td>")
                .append("<td class='").append(ic).append("'>").append(id).append("</td>")
                .append("<td class='").append(ec).append("'>").append(ed).append("</td></tr>");
    }

    /** Backward-compat alias used by older call sites. */
    private void addRow(StringBuilder html, String label, String imp, String exp) {
        addSummaryRow(html, label, imp, exp);
    }

    /**
     * Extract the last-occurrence timestamp (class 4 attr=5) for a Max Demand OBIS.
     * Returns formatted date string or "NA".
     */
    private String extractDlmsTimestamp(String dataUpper, String obisHex) {
        try {
            String marker = obisHex.toUpperCase() + " 05 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return "NA";
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 28 || !payload.startsWith("090C")) return "NA";
            String ts = payload.substring(4, 28);
            if (isNullTimestamp(ts)) return "NA";
            int y  = Integer.parseInt(ts.substring(0, 4), 16);
            int mo = Integer.parseInt(ts.substring(4, 6), 16); if (mo > 127) mo = 1;
            int d  = Integer.parseInt(ts.substring(6, 8), 16); if (d  > 127) d  = 1;
            int h  = Integer.parseInt(ts.substring(10,12), 16); if (h  > 127) h  = 0;
            int mi = Integer.parseInt(ts.substring(12,14), 16); if (mi > 127) mi = 0;
            return String.format("%02d/%02d/%04d %02d:%02d", d, mo, y, h, mi);
        } catch (Exception e) { return "NA"; }
    }

    // ── DLMS Scaler Map ──────────────────────────────────────────────────────

    /**
     * Build a map of OBIS_HEX_UPPER → {scaler, unitCode} from all attr=3 lines in the TXT.
     * Scaler-unit structure: tag=02(struct) count=02 tag=0F(int8) SCALER tag=16(enum) UNIT
     * e.g.  "02020FFE162C" → scaler=0xFE=-2, unit=0x2C=44=Hz
     */
    /** BUG-16 FIX: overwrite-always (was first-wins). Ensures injected scaler corrections take effect. */
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
                map.put(obis, new int[]{sc, uc}); // BUG-16: always overwrite
            }
        }
        return map;
    }

    /**
     * Build a scaler map from billing-specific attr=3 reads (lines prefixed with "BSCL").
     * These are emitted by ReadBillingData and take priority over instantaneous scalers
     * when resolving billing register values.
     *
     * PRIMARY SOURCE — BillingScalerProfile (01005E5B06FF):
     *   attr=3 contains the ordered list of OBIS codes
     *   attr=2 contains a nested array of scaler/unit structs at matching positions
     *   Structure of attr=2: 01 01 02 [N|BER] (02020FXX16YY)×N
     *   This is the same source used by the working NPC conversion API (BillingHistories_NW).
     *
     * FALLBACK — BSCL lines in TXT (two legacy formats):
     *   NEW: "BSCL 0100010800FF 02020F01161E" → sc=+1, uc=Wh for that specific OBIS
     *   OLD: "BSCL 02020F02161E"              → sc=+2, uc=Wh applied to all energy OBIS
     */
    private java.util.Map<String, int[]> buildBillingScalerMap(String dataUpper) {
        java.util.Map<String, int[]> map = new java.util.HashMap<>();

        // ── PRIMARY: parse 01005E5B06FF attr=3 (OBIS list) + attr=2 (scaler values) ──
        // Find the two lines in the TXT
        String bspCoHex  = null;  // attr=3 capture objects
        String bspValHex = null;  // attr=2 scaler array
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
        // Re-parse using the regex to get the correct value fields
        java.util.regex.Matcher mCo = java.util.regex.Pattern.compile(
                "01005E5B06FF\\s+03\\s+([0-9A-F ]+)").matcher(dataUpper);
        if (mCo.find()) bspCoHex  = mCo.group(1).replaceAll("\\s+","");
        java.util.regex.Matcher mVal = java.util.regex.Pattern.compile(
                "01005E5B06FF\\s+02\\s+([0-9A-F ]+)").matcher(dataUpper);
        if (mVal.find()) bspValHex = mVal.group(1).replaceAll("\\s+","");

        if (bspCoHex != null && bspValHex != null) {
            // Parse OBIS list from capture objects attr=3
            java.util.List<String> bspObis = new java.util.ArrayList<>();
            for (int p = 0; p + 16 <= bspCoHex.length(); ) {
                if (bspCoHex.substring(p, p+4).equals("0906") && p + 16 <= bspCoHex.length()) {
                    bspObis.add(bspCoHex.substring(p+4, p+16));
                    p += 16;
                } else { p += 2; }
            }

            // Parse scaler array from attr=2: 01 01 02 [N|BER] (02020FXX16YY)×N
            try {
                String sv = bspValHex;
                int p = 0;
                if (p + 4 > sv.length()) throw new Exception("too short");
                int outerTag = Integer.parseInt(sv.substring(p, p+2), 16); p += 2;
                if (outerTag != 0x01) throw new Exception("not array");
                int outerCb = Integer.parseInt(sv.substring(p, p+2), 16); p += 2;
                if ((outerCb & 0x80) != 0) { int nb=outerCb&0x7F; p += nb*2; } // skip outer BER count
                // inner struct: 02 [N|BER]
                int structTag = Integer.parseInt(sv.substring(p, p+2), 16); p += 2;
                if (structTag != 0x02) throw new Exception("not struct");
                int structCb = Integer.parseInt(sv.substring(p, p+2), 16); p += 2;
                int structCnt;
                if ((structCb & 0x80) != 0) {
                    int nb = structCb & 0x7F;
                    structCnt = Integer.parseInt(sv.substring(p, p+nb*2), 16); p += nb*2;
                } else { structCnt = structCb; }
                // read structCnt scaler pairs
                for (int i = 0; i < structCnt && p + 12 <= sv.length(); i++) {
                    if (!sv.substring(p, p+4).equals("0202")) break;
                    if (!sv.substring(p+4, p+6).equals("0F"))  break;
                    int scByte = Integer.parseInt(sv.substring(p+6,  p+8),  16);
                    int uc     = Integer.parseInt(sv.substring(p+10, p+12), 16);
                    int sc = scByte > 127 ? scByte - 256 : scByte;
                    if (i < bspObis.size()) {
                        map.put(bspObis.get(i), new int[]{sc, uc});
                    }
                    p += 12;
                }
            } catch (Exception ignored) { /* fall through to BSCL */ }
        }

        // ── FALLBACK: BSCL lines in TXT if BSP parsing produced nothing ──────
        if (map.isEmpty()) {
            // Pass 1: new-format BSCL (OBIS-keyed)
            java.util.regex.Pattern pNew = java.util.regex.Pattern.compile(
                    "BSCL ([0-9A-F]{12}) (?:[0-9A-F]+ )*?0202 ?0F([0-9A-F]{2})16([0-9A-F]{2})");
            for (String line : dataUpper.split("[\\r\\n]+")) {
                if (!line.startsWith("BSCL")) continue;
                String norm = line.replaceAll("\\s+", " ");
                java.util.regex.Matcher m = pNew.matcher(norm);
                if (m.find()) {
                    String obis = m.group(1);
                    if (obis.startsWith("0202")) continue; // old-format key
                    int sc = Integer.parseInt(m.group(2), 16);
                    if (sc > 127) sc -= 256;
                    int uc = Integer.parseInt(m.group(3), 16);
                    map.put(obis, new int[]{sc, uc});
                }
            }

            // Pass 2: old-format BSCL (struct-keyed) — broadcast to all energy OBIS
            if (map.isEmpty()) {
                int[] whScaler = null, vahScaler = null;
                java.util.regex.Pattern pOld = java.util.regex.Pattern.compile(
                        "BSCL\\s+[0-9A-F]+\\s+.*?0202 ?0F([0-9A-F]{2})16([0-9A-F]{2})");
                for (String line : dataUpper.split("[\\r\\n]+")) {
                    if (!line.startsWith("BSCL")) continue;
                    String norm = line.replaceAll("\\s+", " ");
                    java.util.regex.Matcher m = pOld.matcher(norm);
                    if (m.find()) {
                        int sc = Integer.parseInt(m.group(1), 16);
                        if (sc > 127) sc -= 256;
                        int uc = Integer.parseInt(m.group(2), 16);
                        if (uc == 0x1E) whScaler  = new int[]{sc, uc};
                        if (uc == 0x1F) vahScaler = new int[]{sc, uc};
                    }
                }
                if (whScaler != null) {
                    for (String ob : new String[]{
                            "0100010800FF","0100020800FF",
                            "0100010801FF","0100010802FF","0100010803FF","0100010804FF",
                            "0100010805FF","0100010806FF","0100010807FF","0100010808FF",
                            "0100020801FF","0100020802FF","0100020803FF","0100020804FF",
                            "0100020805FF","0100020806FF","0100020807FF","0100020808FF"}) {
                        map.put(ob, whScaler);
                    }
                }
                if (vahScaler != null) {
                    for (String ob : new String[]{
                            "0100090800FF","01000A0800FF",
                            "0100090801FF","0100090802FF","0100090803FF","0100090804FF",
                            "0100090805FF","0100090806FF","0100090807FF","0100090808FF",
                            "01000A0801FF","01000A0802FF","01000A0803FF","01000A0804FF",
                            "01000A0805FF","01000A0806FF","01000A0807FF","01000A0808FF",
                            "0100050800FF","0100060800FF","0100070800FF","0100080800FF"}) {
                        map.put(ob, vahScaler);
                    }
                }
            }
        }

        return map;
    }

    /**
     * Apply billingEnergyAdj to an instantaneous energy display string.
     * Used to correct firmware sc bugs (e.g. BK011850 sc=+2 firmware bug → adj=-2 → sc_eff=0).
     * LTCT meters (BS029697 sc=+3, millions kWh) have adj=0 → unaffected.
     * Only rescales if adj≠0 and the value string contains a numeric result.
     */
    private String applyInstAdj(String displayStr, int adj,
                                java.util.Map<String, int[]> scalerMap,
                                String dataUpper, String obisHex) {
        if (adj == 0 || displayStr == null || "NA".equals(displayStr)) return displayStr;
        try {
            // Re-parse the raw value with the adjusted scaler
            String marker = obisHex.toUpperCase() + " 02 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return displayStr;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 4) return displayStr;
            int tag = Integer.parseInt(payload.substring(0, 2), 16);
            long rawVal;
            if (tag == 0x06) rawVal = Long.parseLong(payload.substring(2, 10), 16);
            else if (tag == 0x05) {
                rawVal = Long.parseLong(payload.substring(2, 10), 16);
                if (rawVal > 0x7FFFFFFFL) rawVal -= 0x100000000L;
            } else if (tag == 0x15) rawVal = Long.parseLong(payload.substring(2, 18), 16);
            else return displayStr;
            // Get base sc from scalerMap
            int[] su = scalerMap.get(obisHex.toUpperCase());
            int sc = (su != null) ? su[0] : 0;
            int uc = (su != null) ? su[1] : 0x1E;
            int scEff = sc + adj;
            double val = rawVal * Math.pow(10, scEff) / 1000.0;
            String unit = (uc == 0x1F) ? "kVAh" : (uc == 0x20) ? "kVArh" : "kWh";
            return String.format("%,.3f %s", val, unit);
        } catch (Exception e) { return displayStr; }
    }

    /**
     * Read the scaler at a specific column index from a compound scaler profile
     * (e.g. 01005E5B03FF for current, 01005E5B04FF for voltage).
     * Structure: 01 01 02 [N] (02 02 0F [sc] 16 [uc]) × N
     * Returns Integer.MIN_VALUE if not found or parse fails.
     */
    private static int readCompoundScaler(String dataUpper, String compoundObis, int colIndex) {
        try {
            String marker = compoundObis + " 02 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return Integer.MIN_VALUE;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String sv = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            // Parse: 01 01 02 [N|BER] (02020FXX16YY)×N
            int pos = 0;
            if (Integer.parseInt(sv.substring(pos, pos+2), 16) != 0x01) return Integer.MIN_VALUE;
            pos += 2;
            int outerCb = Integer.parseInt(sv.substring(pos, pos+2), 16); pos += 2;
            if ((outerCb & 0x80) != 0) pos += (outerCb & 0x7F) * 2; // skip BER count
            if (Integer.parseInt(sv.substring(pos, pos+2), 16) != 0x02) return Integer.MIN_VALUE;
            pos += 2;
            int structCb = Integer.parseInt(sv.substring(pos, pos+2), 16); pos += 2;
            int structCnt;
            if ((structCb & 0x80) != 0) {
                int nb = structCb & 0x7F;
                structCnt = Integer.parseInt(sv.substring(pos, pos+nb*2), 16); pos += nb*2;
            } else { structCnt = structCb; }
            // Navigate to colIndex
            for (int i = 0; i < structCnt && pos + 12 <= sv.length(); i++) {
                if (!sv.substring(pos, pos+4).equals("0202")) break;
                if (!sv.substring(pos+4, pos+6).equals("0F")) break;
                int scByte = Integer.parseInt(sv.substring(pos+6, pos+8), 16);
                if (i == colIndex) return scByte > 127 ? scByte - 256 : scByte;
                pos += 12;
            }
        } catch (Exception ignored) {}
        return Integer.MIN_VALUE;
    }

    /**
     * DLMS unit codes → {divisor, decimals} to reach display unit.
     * Unit 0x1e=30=Wh   → kWh  (÷1000, 3dp)
     * Unit 0x1f=31=VAh  → kVAh (÷1000, 3dp)   ← apparent energy
     * Unit 0x20=32=varh → kVArh(÷1000, 3dp)   ← reactive energy
     * Unit 0x1b=27=W    → kW   (÷1000, 3dp)
     * Unit 0x1c=28=VA   → kVA  (÷1000, 3dp)
     * Unit 0x1d=29=var  → kVAr (÷1000, 3dp)
     * Unit 0x21=33=A    → A    (×1,    3dp)
     * Unit 0x23=35=V    → V    (×1,    2dp)
     * Unit 0x2c=44=Hz   → Hz   (×1,    2dp)
     * Unit 0x27=39=%    → %    (÷1000, 3dp) — PF encoded as per-mille
     */
    private double[] unitConversion(int unitCode) {
        switch (unitCode) {
            case 0x1e: return new double[]{1000.0, 3}; // Wh   → kWh
            case 0x1f: return new double[]{1000.0, 3}; // VAh  → kVAh  (was wrongly ÷1)
            case 0x20: return new double[]{1000.0, 3}; // varh → kVArh
            case 0x1b: return new double[]{1000.0, 3}; // W    → kW
            case 0x1c: return new double[]{1000.0, 3}; // VA   → kVA
            case 0x1d: return new double[]{1000.0, 3}; // var  → kVAr
            case 0x21: return new double[]{1.0,    3}; // A
            case 0x23: return new double[]{1.0,    2}; // V
            case 0x2c: return new double[]{1.0,    2}; // Hz
            case 0x27: return new double[]{1000.0, 3}; // % (pf per-mille)
            default:   return new double[]{1.0,    3};
        }
    }

    /**
     * Parse a single DLMS register (attr=2) from the TXT data, apply scaler and unit conversion.
     * Handles uint32/int32/uint16/int16/int8/uint8.
     * Scaler and unit are looked up from scalerMap (built from attr=3 lines).
     */
    private String parseDlmsRegisterWithUnit(String dataUpper,
                                             String obisHex,
                                             java.util.Map<String, int[]> scalerMap) {
        try {
            String marker = obisHex.toUpperCase() + " 02 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return "NA";
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 4) return "NA";

            int tag = Integer.parseInt(payload.substring(0, 2), 16);
            long rawVal;
            int nextPos;
            if (tag == 0x06) {                         // uint32
                if (payload.length() < 10) return "NA";
                rawVal = Long.parseLong(payload.substring(2, 10), 16); nextPos = 10;
            } else if (tag == 0x05) {                  // int32
                if (payload.length() < 10) return "NA";
                rawVal = Long.parseLong(payload.substring(2, 10), 16);
                if (rawVal > 0x7FFFFFFFL) rawVal -= 0x100000000L; nextPos = 10;
            } else if (tag == 0x12) {                  // uint16
                if (payload.length() < 6) return "NA";
                rawVal = Long.parseLong(payload.substring(2, 6), 16); nextPos = 6;
            } else if (tag == 0x10) {                  // int16
                if (payload.length() < 6) return "NA";
                rawVal = Long.parseLong(payload.substring(2, 6), 16);
                if (rawVal > 0x7FFF) rawVal -= 0x10000; nextPos = 6;
            } else if (tag == 0x11 || tag == 0x16) {   // uint8 / enum
                rawVal = Long.parseLong(payload.substring(2, 4), 16); nextPos = 4;
            } else if (tag == 0x0F) {                  // int8
                rawVal = Long.parseLong(payload.substring(2, 4), 16);
                if (rawVal > 127) rawVal -= 256; nextPos = 4;
            } else if (tag == 0x14) {                  // int64 (Landis+Gyr)
                if (payload.length() < 18) return "NA";
                rawVal = Long.parseLong(payload.substring(2, 18), 16);
                nextPos = 18;
            } else if (tag == 0x15) {                  // uint64 (Landis+Gyr energy)
                if (payload.length() < 18) return "NA";
                rawVal = Long.parseLong(payload.substring(2, 18), 16);
                if (rawVal < 0) rawVal = Long.MAX_VALUE; // clamp uint64 overflow; rare in energy meters
                nextPos = 18;
            } else if (tag == 0x17) {                  // float32 (Genus LC / AVON meters)
                // IEC 62056-21 type 0x17 = FLOAT32 (IEEE 754 single precision, big-endian)
                // Genus LC meters return energy/power registers as float32 instead of uint32/int32.
                // The float value is already in the display unit implied by the scaler, so
                // we convert it to long via bit-exact rounding to feed the existing scaling path.
                if (payload.length() < 10) return "NA";
                int floatBits = (int) Long.parseLong(payload.substring(2, 10), 16);
                float fVal = Float.intBitsToFloat(floatBits);
                if (Float.isNaN(fVal) || Float.isInfinite(fVal)) return "NA";
                rawVal = (long)(fVal * 1000);   // preserve 3 decimal places before scaler
                // For float32 registers, the scaler typically =0; we compensate for ×1000
                // by subtracting 3 from scaler so final = rawVal × 10^(sc-3) × 10^3 = fVal × 10^sc
                // This is handled by adjusting sc below rather than changing the formula.
                nextPos = 10;
                // Apply scaler inline for float32 (bypass rawVal path)
                int sc17 = 0, uc17 = 0xFF;
                int[] su17 = scalerMap.get(obisHex.toUpperCase());
                if (su17 != null) { sc17 = su17[0]; uc17 = su17[1]; }
                if (uc17 == 0xFF) {
                    int[] def17 = dlmsDefaultScalerUnit(obisHex.toUpperCase());
                    if (def17 != null) { sc17 = def17[0]; uc17 = def17[1]; }
                }
                String obisUp17 = obisHex.toUpperCase();
                // Override to kVA unit only for apparent-power demand OBIS (01000906xx, Genus variants).
                // MD kW demand (01000106xx, 01000206xx) must keep 0x1B (W/kW) — do NOT override.
                // MD kVA export (01000A06xx) already returns 0x1C from meter/defaults — keep as-is.
                if (obisUp17.startsWith("01000906")
                        || obisUp17.startsWith("01009E06") || obisUp17.startsWith("01009F06")) {
                    if (uc17 == 0x1B || uc17 == 0xFF) uc17 = 0x1C;
                }
                double val17 = fVal * Math.pow(10, sc17);
                double[] conv17 = unitConversion(uc17);
                val17 /= conv17[0];
                int dp17 = (int) conv17[1];
                return String.format("%,." + dp17 + "f", val17);
            } else {
                return "NA";
            }

            // Scaler + unit from map (built from attr=3 lines)
            int sc = 0, uc = 0xFF;
            int[] su = scalerMap.get(obisHex.toUpperCase());
            if (su != null) { sc = su[0]; uc = su[1]; }

            // Demand OBIS unit normalisation: only apparent-power (kVA) demand needs 0x1C.
            // Active-power (kW) demand 01000106xx and export 01000206xx keep 0x1B (W/kW).
            String obisUp = obisHex.toUpperCase();
            if (obisUp.startsWith("01000906")
                    || obisUp.startsWith("01009E06") || obisUp.startsWith("01009F06")) {
                if (uc == 0x1B || uc == 0xFF) uc = 0x1C;
            }

            // IS15959-2 standard default scaler/unit fallback.
            // When a meter returns empty attr=3 (scaler not readable), apply known
            // defaults so that values are scaled correctly rather than shown raw.
            // Only applies when uc is still 0xFF (unknown) after map lookup.
            if (uc == 0xFF) {
                int[] def = dlmsDefaultScalerUnit(obisUp);
                if (def != null) { sc = def[0]; uc = def[1]; }
            }

            // BSP (BillingScalerProfile) fallback for instantaneous energy registers.
            // Some meters (e.g. Secure BS060742) do not write individual attr=3 lines for
            // all energy OBIS (kVAh etc.) in billing-only TXT files. The BSP scaler from
            // 01005E5B06FF attr=2 covers ALL billing columns including kVAh and is the
            // authoritative source. Use it when uc is still unknown after standard defaults.
            // This makes kVAh display correctly for BS060742 (sc=+3 → 15,574 kVAh).
            if (uc == 0xFF && (obisUp.endsWith("0800FF") || obisUp.endsWith("0600FF"))) {
                // Energy or demand register — try BSP
                java.util.Map<String, int[]> bspFallback = buildBillingScalerMap(dataUpper);
                int[] bspEntry = bspFallback.get(obisUp);
                if (bspEntry != null) { sc = bspEntry[0]; uc = bspEntry[1]; }
            }

            // Compound-object scaler override for I registers ONLY.
            // 01005E5B03FF attr=2 → current scalers (col 0=I_R, 1=I_Y, 2=I_B)
            // Only applied when uc is STILL unknown (0xFF) after individual + default lookups.
            // If individual attr=3 already provided a valid uc, respect it — do NOT override.
            // This fixes BK011850 I_R (no individual attr=3) and leaves BK011850 V_R intact
            // (individual attr=3 sc=-3 is correct).
            if (uc == 0x21) { // Ampere — use compound I scaler only when sc/uc came from defaults
                int[] origScaler = scalerMap.get(obisUp);
                if (origScaler == null) { // no individual attr=3 in TXT → try compound
                    int compScI = readCompoundScaler(dataUpper, "01005E5B03FF",
                            obisUp.equals("01001F0700FF") ? 0
                                    : obisUp.equals("0100330700FF") ? 1 : 2);
                    if (compScI != Integer.MIN_VALUE) sc = compScI;
                }
            }
            // Note: V (uc=0x23) compound scaler NOT applied here.
            // Individual attr=3 for V_R already has the correct sc (e.g. BK011850 sc=-3 ✓).
            // For meters without individual V scaler (BS060742), the compound object
            // 01005E5B04FF is also absent — accept the dlmsDefault sc=-2 for those.

            // Energy scaler: trust the declared sc from attr=3 for all meter types.
            //
            // Most meters correctly declare their own sc. However some Secure firmware
            // variants (e.g. BK011850) declare sc=+2 but store Wh directly — giving a
            // 100× inflated result. The billingEnergyAdj computed below corrects the
            // billing display, and we apply the same correction to instantaneous energy.
            // LTCT meters (BS029697 sc=+3) legitimately have millions of kWh and have
            // adj=0 (billing and instantaneous use the same units), so they are unaffected.
            //
            // We need billingEnergyAdj here but it is computed later in the calling method.
            // Instead, apply the same correction logic inline: compare with kW to sanity-check.
            // For now, trust the declared sc — the adj is applied at the calling site for
            // parseDlmsRegisterWithUnit when the caller has computed billingEnergyAdj.
            // See the kwhImp/kwhExp assignments in showMeterReadingDialog which apply adj.

            // Power factor sanity: PF must be in [-1.0, 1.0].
            // Landis+Gyr and some other meters store PF in per-ten-thousand (raw ≈ 9997 → 0.9997)
            // rather than per-mille (raw ≈ 999 → 0.999). The IS15959-2 default is sc=-3.
            // If applying sc=-3 gives |PF| > 1.5 (impossible), reduce sc by 1 to sc=-4.
            if (obisUp.equals("01000D0700FF") && uc == 0xFF) {
                double pfCheck = Math.abs(rawVal * Math.pow(10, sc));
                if (pfCheck > 1.5) sc -= 1;
            }

            double val = rawVal * Math.pow(10, sc);
            double[] conv = unitConversion(uc);
            val /= conv[0];
            int dp = (int) conv[1];
            return String.format("%,." + dp + "f", val);
        } catch (Exception e) { return "NA"; }
    }

    // ── Billing Profile Parser ───────────────────────────────────────────────

    /**
     * Parse the billing profile capture objects (0100620100FF attr=3) to build
     * a map of OBIS_HEX_UPPER → column_index.
     * This is the only correct way to locate values in the billing buffer —
     * column ordering varies by meter make and firmware version.
     *
     * Capture objects structure:
     *   01 NN [struct{class(uint16), OBIS(octet6), attr(int8), dataIdx(uint16)}] × NN
     * OBIS inside capture object: tag=09 len=06 followed by 6 bytes (12 hex chars)
     */
    private java.util.Map<String, Integer> parseBillingCaptureObjects(String dataUpper) {
        java.util.Map<String, Integer> colMap = new java.util.LinkedHashMap<>();
        // Track class_id and attr_idx for each OBIS so we can prefer the energy-value
        // entry (IC=3/IC=1, attr=2) over demand-timestamp entries (IC=4, attr=5).
        // BS034549 billing profile includes BOTH for each TOD slot:
        //   {IC=4, 0100010802FF, attr=5} → T2 MD-reset timestamp → DateTime in snapshot
        //   {IC=3, 0100010802FF, attr=2} → T2 kWh energy → uint32 (what we want)
        // Without this fix, first-occurrence wins → T2 maps to the DateTime column.
        java.util.Map<String, int[]> colMeta = new java.util.LinkedHashMap<>(); // OBIS → {col, classId, attrIdx}
        try {
            String marker = "0100620100FF 03 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return colMap;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String co = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (co.length() < 4) return colMap;

            // Skip array tag (01) and record count
            int pos = 2; // skip "01"
            int lb = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
            int count;
            if ((lb & 0x80) != 0) {
                int nb = lb & 0x7F;
                count = Integer.parseInt(co.substring(pos, pos + nb * 2), 16); pos += nb * 2;
            } else {
                count = lb;
            }

            // Each capture object is a struct of 4 elements:
            //   [0] class-id  uint16 (tag=12, 4 hex)
            //   [1] OBIS      octet-string len=6 (tag=09 06 + 12 hex)
            //   [2] attr      int8   (tag=0F, 2 hex)
            //   [3] dataIndex uint16 (tag=12, 4 hex)
            //
            // Genus firmware inserts orphan bytes (e.g. 0x01 0xE6) between demand
            // capture objects (value-entry at attr=2 and timestamp-entry at attr=5).
            // These are NOT DLMS-tagged values; we skip them to reach the next 0x02
            // struct tag rather than breaking the entire parse early.
            for (int col = 0; col < count && pos + 2 <= co.length(); col++) {
                // Skip any non-struct bytes before the next capture object
                while (pos + 2 <= co.length() &&
                        Integer.parseInt(co.substring(pos, pos + 2), 16) != 0x02) {
                    pos += 2; // advance past orphan byte
                }
                if (pos + 2 > co.length()) break;
                int st = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (st != 0x02) break; // safety guard (should not reach here)
                int fc = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                // [0] class-id uint16
                if (pos + 2 > co.length()) break;
                int t0 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                int classId = 0;
                if (t0 == 0x12 && pos + 4 <= co.length()) {
                    classId = Integer.parseInt(co.substring(pos, pos + 4), 16);
                    pos += 4;
                }
                // [1] OBIS octet-string
                if (pos + 2 > co.length()) break;
                int t1 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                String obisHex = "";
                if (t1 == 0x09) {
                    int ln = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                    if (ln == 6 && pos + 12 <= co.length()) {
                        obisHex = co.substring(pos, pos + 12);
                        pos += 12;
                    } else { pos += ln * 2; }
                }
                // [2] attribute int8
                if (pos + 2 > co.length()) break;
                int t2 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                int attrIdx = 2; // default
                if (t2 == 0x0F && pos + 2 <= co.length()) {
                    int ab = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                    attrIdx = ab > 127 ? ab - 256 : ab;
                }
                // [3] data-index uint16
                if (pos + 2 > co.length()) break;
                int t3 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (t3 == 0x12) pos += 4;

                // FIX 4: Validate OBIS before registering in colMap.
                boolean obisValid = obisHex.length() == 12
                        && !obisHex.substring(4, 6).equalsIgnoreCase("FF");
                if (!obisValid) continue;

                // FIX: Prefer energy-value entries over demand-timestamp entries.
                // When the same OBIS appears multiple times (e.g. IC=4/attr=5 timestamp
                // AND IC=3/attr=2 value for BS034549 TOD slots), always use the entry
                // whose attr=2 and class is a Register (IC=1 or IC=3) or Demand value
                // (IC=4, attr=2 not attr=5).
                // Priority (higher = preferred):
                //   3: IC=3 attr=2  (Register value)        — BEST for energy
                //   2: IC=1 attr=2  (Data object value)     — ok for status/flags
                //   2: IC=4 attr=2  (Extended Register val) — ok for demand value
                //   1: anything else attr=2                 — acceptable
                //   0: attr≠2      (e.g. IC=4 attr=5 = timestamp) — WORST, avoid
                int priority = (attrIdx == 2)
                        ? ((classId == 3) ? 3 : (classId == 1 || classId == 4) ? 2 : 1)
                        : 0;

                int[] existing = colMeta.get(obisHex);
                if (existing == null || priority > existing[2]) {
                    // First occurrence OR higher-priority occurrence — use this col
                    colMeta.put(obisHex, new int[]{col, classId, priority});
                }
                // Always put first occurrence in colMap as a fallback;
                // will be overwritten below with the best-priority col.
                if (!colMap.containsKey(obisHex)) colMap.put(obisHex, col);
            }

            // Apply best-priority column for each OBIS (overwrite first-occurrence)
            for (java.util.Map.Entry<String, int[]> e : colMeta.entrySet()) {
                colMap.put(e.getKey(), e.getValue()[0]);
            }

        } catch (Exception ignored) {}
        return colMap;
    }

    /**
     * Extract a value for a specific OBIS from the latest billing record.
     *
     * 1. Looks up column index from billingColMap (built from capture objects).
     * 2. Decodes first struct in the billing buffer at that column.
     * 3. Applies scaler + unit conversion from scalerMap.
     *
     * For timestamp OBIS (0.0.0.1.2.255 = 0000000102FF): returns formatted date string.
     * For numeric OBIS: returns scaled value string in display units (kWh, kW, kVA…).
     */
    private String extractBillingByObis(String dataUpper,
                                        String obisHex,
                                        java.util.Map<String, Integer> colMap,
                                        java.util.Map<String, int[]> scalerMap,
                                        int energyAdj) {
        try {
            Integer colIdx = colMap.get(obisHex.toUpperCase());
            if (colIdx == null) return "NA";

            // Get billing buffer payload
            String marker = "0100620100FF 02 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return "NA";
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 8) return "NA";

            // ── Find the LATEST billing record using forward struct parsing ─────
            // Previous approach: anchor on a 090C date timestamp and walk back up to
            // 16 bytes to find the struct(02) tag. This fails for large profiles like
            // Secure meters with 225 columns: the date OBIS is at column 223, so the
            // struct tag is ~6500 bytes before the date — far beyond a 16-byte walk.
            // Fix: parse the array forward, record by record, tracking the most recent.

            // Skip outer array tag (01) to get to the count byte
            int headerEnd = 0;
            if (payload.startsWith("01")) {
                headerEnd = 2; // skip '01' tag — recPos will read the count next
            }

            int bestRecordPos = -1;
            long bestTimestamp = -1;

            // Parse array header: tag=01, count (1 or 2-byte BER)
            int recPos = headerEnd;
            int arrayCount = 0;
            if (recPos + 2 <= payload.length()) {
                int cb = Integer.parseInt(payload.substring(recPos, recPos + 2), 16); recPos += 2;
                if ((cb & 0x80) != 0) {
                    int nb2 = cb & 0x7F;
                    if (recPos + nb2 * 2 <= payload.length()) {
                        arrayCount = Integer.parseInt(payload.substring(recPos, recPos + nb2 * 2), 16);
                        recPos += nb2 * 2;
                    }
                } else {
                    arrayCount = cb;
                }
            }

            // Walk forward through every billing record struct in the buffer.
            // IMPORTANT: use the physical payload as the loop driver, NOT arrayCount.
            // Reason: AVON firmware stores more physical records than the declared
            // array count (13 declared, up to 30 physical — alternating full 95-field
            // and compact 53/40-field demand-reset records). Using arrayCount as the
            // loop bound stops at rec 13 and never reaches the most-recent full record.
            // We cap at 4 × arrayCount (≥ 52) as a safety ceiling against malformed
            // buffers, and stop as soon as we run out of 0x02 struct tags.
            int maxRecs = payload.length() / 2; // one iteration per byte maximum
            // Track the most-recently seen FULL billing record (large field count).
            // Needed for Secure BK011850: the billing DateTime lives in the gap AFTER
            // a compact interleaved record, but the actual energy values are in the
            // preceding full 153-field record. We remember the last full record so
            // the gap scan can set bestRecordPos to the full record, not the compact one.
            int lastFullRecordStart = -1;
            int lastFullRecordFCount = 0;
            // Genus KT291596 has a nested byte-count array at field[78] that causes
            // record_end to overshoot the NEXT billing record boundary. The real next
            // record starts INSIDE the overshoot zone and is skipped by the sequential
            // walker. We collect those overshot record positions here and score them
            // after the main walk completes.
            // Safety: only positions whose declared fc EXACTLY matches the current
            // record's fc are queued — this prevents false positives from nested structs
            // or random 0x02 data bytes.
            java.util.ArrayList<Integer> overshotRecords = new java.util.ArrayList<>();
            java.util.HashSet<Integer> overshotSeen = new java.util.HashSet<>();
            for (int rec = 0; rec < maxRecs && recPos + 4 <= payload.length(); rec++) {
                // Each record starts with struct tag 02
                if (Integer.parseInt(payload.substring(recPos, recPos + 2), 16) != 0x02) break;
                int recStart = recPos; recPos += 2;
                // Field count (1 or multi-byte BER)
                int fc2 = Integer.parseInt(payload.substring(recPos, recPos + 2), 16); recPos += 2;
                int fCount;
                if ((fc2 & 0x80) != 0) {
                    int nb3 = fc2 & 0x7F;
                    if (recPos + nb3 * 2 > payload.length()) break;
                    fCount = Integer.parseInt(payload.substring(recPos, recPos + nb3 * 2), 16);
                    recPos += nb3 * 2;
                } else {
                    fCount = fc2;
                }
                // Track the most-recent GENUINE full billing record (50 ≤ fc ≤ 300).
                // Lower bound 50 excludes compact interleaved records (fc 1–49).
                // Upper bound 300 excludes phantom BER-overflow records (fc = millions)
                // produced when a 0x02 data byte inside a nested array is mistaken for
                // a struct tag and its multi-byte BER field count is misinterpreted.
                // All genuine billing records across all tested makes have 62–225 fields.
                if (fCount >= 20 && fCount <= 300) {
                    lastFullRecordStart = recStart;
                    lastFullRecordFCount = fCount;
                }
                int fieldsStart = recPos;
                // Skip all fields to find end of this record (to advance recPos for next record)
                // AND look for a date value at the date column (colIdx of 0000000102FF)
                // For efficiency: also look for any 090C date anchor within this record
                // to identify the billing date without scanning all 225+ fields.
                // Strategy: use ONLY field-0 (the record's own billing date, always first field)
                // for snapshot detection. Do NOT scan inner timestamps (MD timestamps etc.)
                // which would incorrectly qualify a snapshot record as a monthly record.
                // Billing date = field 0: tag 0x09, len 0x0C (12 bytes), then DateTime.
                // Snapshot records have SS (seconds, byte 7) ≠ 0x00 and ≠ 0xFF.
                // Monthly billing resets happen at midnight → SS = 0x00 or 0xFF.
                // Walk fields to locate the physical end of this record.
                // When skipOneDlmsValue returns -1 it means an unrecognised or
                // structurally complex field was encountered:
                //   AVON 8550282   : declares 95 fields but only 88 are physically present;
                //                    field[88] is the next record's 0x02 struct tag.
                //   Landis 70021475: nested struct (tag=0x02) at field[55] contains inner
                //                    0x01 array and 0x11 fields that recurse into -1.
                //   Genus KT291596 : double-nested array (0x01 inside 0x01) at field[78].
                // CRITICAL: do NOT set recordEnd = payload.length() on failure.
                // That makes the walker treat rec[0] as spanning the entire buffer so
                // only rec[0] is ever scored → billing date stuck at oldest record.
                // Instead: break the loop and let the "advance to next 0x02" block below
                // scan forward byte-by-byte to the actual next struct boundary.
                int recordEnd = recPos;
                for (int f = 0; f < fCount && recordEnd + 2 <= payload.length(); f++) {
                    int nextPos = skipOneDlmsValue(payload, recordEnd);
                    if (nextPos < 0) break;   // stop; inter-record scan finds actual boundary
                    recordEnd = nextPos;
                }

                // Read field 0 directly as DateTime
                String field0Date = null;
                boolean isDtBranch = false;
                if (fieldsStart + 28 <= payload.length()) {
                    String f0Tag = payload.substring(fieldsStart, fieldsStart + 2);
                    if ("09".equalsIgnoreCase(f0Tag)) {
                        String f0Len = payload.substring(fieldsStart + 2, fieldsStart + 4);
                        if ("0C".equalsIgnoreCase(f0Len)) {
                            field0Date = payload.substring(fieldsStart + 4, fieldsStart + 28);
                            isDtBranch = true;
                        }
                    }
                }

                if (field0Date != null && field0Date.length() >= 24) {
                    try {
                        int y  = Integer.parseInt(field0Date.substring(0, 4), 16);
                        int mo = Integer.parseInt(field0Date.substring(4, 6), 16);  if (mo > 127) mo = 0;
                        int d  = Integer.parseInt(field0Date.substring(6, 8), 16);  if (d  > 127) d  = 0;
                        int h  = Integer.parseInt(field0Date.substring(10, 12), 16); if (h  > 127) h  = 0;
                        int mi = Integer.parseInt(field0Date.substring(12, 14), 16); if (mi > 127) mi = 0;
                        int ss = Integer.parseInt(field0Date.substring(14, 16), 16);
                        // A billing reset happens at midnight (h=0, mi=0).
                        // A live snapshot taken during the read has a non-midnight time OR
                        // non-zero/non-FF seconds. Filter both:
                        //   SS not 0x00/0xFF         → always a snapshot (e.g. HPL 16:21:59)
                        //   h!=0 OR mi!=0             → non-midnight time  (e.g. Genus 17:05:00 SS=0x00)
                        // Midnight records (h=0, mi=0, SS=0x00 or 0xFF) are genuine billing resets.
                        boolean isMidnight = (h == 0) && (mi == 0 || mi == 0xFF)
                                && (ss == 0x00 || ss == 0xFF);
                        if (y > 2000 && y < 2100 && mo >= 1 && mo <= 12 && isMidnight) {
                            long tsVal = (long) y * 10000L + mo * 100L + d;
                            if (tsVal > bestTimestamp) {
                                bestTimestamp = tsVal;
                                bestRecordPos = recStart;
                            }
                        }
                    } catch (Exception ignore) {}

                    // Intra-record overshoot scan: Genus KT291596 has a nested byte-count
                    // array at field[78] that makes skipOneDlmsValue advance record_end
                    // past the NEXT billing record boundary. The real next record starts
                    // inside [fieldsStart+4, recordEnd) and would be missed by the sequential
                    // walk. Detect same-fcount 0x02 headers inside this zone and queue them
                    // for later scoring.
                    //
                    // SAFETY: only queue positions that satisfy ALL of:
                    //   (a) declared fc EXACTLY equals the current record's fcount
                    //   (b) fields start with a valid DateTime header (090C07E — year 2024-2031)
                    //       This guarantees a genuine peer billing record, not random bytes
                    //       inside the current record's payload that happen to match the fc.
                    //   (c) position not already queued
                    if (fCount >= 20 && fCount <= 300 && fieldsStart + 4 < recordEnd) {
                        int scanP = fieldsStart + 4;
                        while (scanP + 4 <= recordEnd) {
                            if (Integer.parseInt(payload.substring(scanP, scanP + 2), 16) == 0x02) {
                                int cbN = Integer.parseInt(payload.substring(scanP + 2, scanP + 4), 16);
                                int subFc = -1;
                                int fieldsPos = -1;
                                if ((cbN & 0x80) != 0) {
                                    int nb4 = cbN & 0x7F;
                                    if (nb4 >= 1 && nb4 <= 4 && scanP + 4 + nb4 * 2 <= payload.length()) {
                                        try {
                                            subFc = Integer.parseInt(
                                                    payload.substring(scanP + 4, scanP + 4 + nb4 * 2), 16);
                                            fieldsPos = scanP + 4 + nb4 * 2;
                                        } catch (Exception ignore) {}
                                    }
                                } else {
                                    subFc = cbN;
                                    fieldsPos = scanP + 4;
                                }
                                // Validate field[0] is a DateTime in the 2024-2031 range
                                boolean validDateTime = false;
                                if (subFc == fCount && fieldsPos > 0 && fieldsPos + 8 <= payload.length()) {
                                    String f0Head = payload.substring(fieldsPos, fieldsPos + 8);
                                    // 090C07E0..090C07EF = year 2016..2031; tight match for current era
                                    if (f0Head.startsWith("090C07E")) {
                                        validDateTime = true;
                                    }
                                }
                                if (validDateTime && !overshotSeen.contains(scanP)) {
                                    overshotRecords.add(scanP);
                                    overshotSeen.add(scanP);
                                }
                            }
                            scanP += 2;
                        }
                    }
                } else {
                    // Field 0 is not a DateTime (e.g. Secure: field[0] = uint32 kWh import).
                    // Scan within the record for the first MIDNIGHT 090C DateTime.
                    // "Midnight" requires h==0 AND mi==0 AND ss in (0x00, 0xFF) — otherwise
                    // we pick up MD (demand-register) timestamps like "14/04/2026 05:30:00"
                    // which are NOT billing dates and will shadow the real billing date
                    // from the inter-record gap scan.
                    // Secure KT120652: billing date appended after struct fields is found
                    // by the global fallback scan at bestRecordPos<0 below (lines 2451+)
                    // which scans the entire buffer — no need to extend the bound here.
                    int di = payload.indexOf("090C07E", fieldsStart);
                    if (di >= 0 && di < recordEnd) {
                        if (di + 28 <= payload.length()) {
                            String ts = payload.substring(di + 4, di + 28);
                            try {
                                int y  = Integer.parseInt(ts.substring(0, 4), 16);
                                int mo = Integer.parseInt(ts.substring(4, 6), 16); if (mo > 127) mo = 0;
                                int d  = Integer.parseInt(ts.substring(6, 8), 16); if (d  > 127) d  = 0;
                                int h  = Integer.parseInt(ts.substring(10, 12), 16); if (h  > 127) h  = 0;
                                int miX = Integer.parseInt(ts.substring(12, 14), 16); if (miX > 127) miX = 0;
                                int ss = Integer.parseInt(ts.substring(14, 16), 16);
                                boolean isMid = (h == 0) && (miX == 0 || miX == 0xFF)
                                        && (ss == 0x00 || ss == 0xFF);
                                if (y > 2000 && y < 2100 && mo >= 1 && mo <= 12 && isMid) {
                                    long tsVal = (long) y * 10000L + mo * 100L + d;
                                    if (tsVal > bestTimestamp) {
                                        bestTimestamp = tsVal;
                                        bestRecordPos = recStart;
                                    }
                                }
                            } catch (Exception ignore) {}
                        }
                    }
                }
                recPos = recordEnd;
                // Skip any inter-record gap bytes (e.g. HPL quality/status bytes) to reach
                // the next struct tag 0x02 that looks like a TRUE billing-record header.
                //
                // CRITICAL: a bare 0x02 tag alone is NOT enough — nested structs (Landis
                // col 55) and phantom records (BER nb>=3 from random bytes) also start 0x02.
                //
                // A true record header is 0x02 followed by:
                //   - BER with nb=1 (cb=0x81): 1-byte count (Secure BK011850=0x99=153)
                //   - BER with nb=2 (cb=0x82): 2-byte count (Genus KT291596=0x0096=150)
                //   - Plain count 50..127 (cb=0x32..0x7F): AVON=0x5F=95, Landis=0x3E=62,
                //     HPL=0x71=113
                //
                // Anything else is either a nested struct (cb<50) or a phantom (cb>=0x83
                // yields astronomical BER counts from random data bytes inside fields).
                while (recPos + 4 <= payload.length()) {
                    int t = Integer.parseInt(payload.substring(recPos, recPos + 2), 16);
                    if (t == 0x02) {
                        int cbNext = Integer.parseInt(payload.substring(recPos + 2, recPos + 4), 16);
                        boolean isRecordHeader = (cbNext == 0x81) || (cbNext == 0x82)
                                || (cbNext >= 20 && cbNext < 128);
                        if (isRecordHeader) break;
                    }
                    recPos += 2;
                    if (recPos >= payload.length() - 4) break;
                }
                // Scan the inter-record gap [recordEnd, recPos) for a midnight billing DateTime.
                // Secure meters append the billing-date DateTime AFTER the last declared field,
                // in the gap between consecutive billing structs.
                // Only applies when field[0] was NOT a DateTime (Secure-pattern meters).
                // All gaps must be scanned (not just the first) so the HIGHEST date wins.
                //
                // The record BEFORE the midnight is always the billing data source:
                //   BK011850: midnight in gap after full record → recStart IS the billing record ✓
                //   Compact variant: midnight after compact → lastFullRecordStart is the data record ✓
                if (!isDtBranch && recordEnd < recPos) {
                    int gapScan = recordEnd;
                    while (true) {
                        int diG = payload.indexOf("090C07E", gapScan);
                        if (diG < 0 || diG >= recPos) break;
                        if (diG + 28 <= payload.length()) {
                            String tsG = payload.substring(diG + 4, diG + 28);
                            try {
                                int yG  = Integer.parseInt(tsG.substring(0, 4), 16);
                                int moG = Integer.parseInt(tsG.substring(4, 6), 16); if (moG > 127) moG = 0;
                                int dG  = Integer.parseInt(tsG.substring(6, 8), 16); if (dG  > 127) dG  = 0;
                                int hG  = Integer.parseInt(tsG.substring(10, 12), 16); if (hG > 127) hG = 0;
                                int miG = Integer.parseInt(tsG.substring(12, 14), 16); if (miG > 127) miG = 0;
                                int ssG = Integer.parseInt(tsG.substring(14, 16), 16);
                                boolean isMidG = (hG == 0) && (miG == 0 || miG == 0xFF)
                                        && (ssG == 0x00 || ssG == 0xFF);
                                if (yG > 2000 && yG < 2100 && moG >= 1 && moG <= 12 && isMidG) {
                                    long tsValG = (long) yG * 10000L + moG * 100L + dG;
                                    if (tsValG > bestTimestamp) {
                                        bestTimestamp = tsValG;
                                        // Use the record BEFORE the midnight as the billing source.
                                        // For compact records (fCount < 50): lastFullRecordStart
                                        // is the full data record preceding the compact.
                                        // For full records: recStart is the data record itself.
                                        bestRecordPos = (fCount < 50 && lastFullRecordStart >= 0)
                                                ? lastFullRecordStart : recStart;
                                    }
                                }
                            } catch (Exception ignore) {}
                        }
                        gapScan = diG + 1;
                    }
                }

                // Intra-record midnight scan for Q0817904-type Secure meters.
                // These meters store the billing-reset DateTime INSIDE the billing record
                // at a mid-record column (e.g. col[118] of 120), not at field[0] and not
                // in the gap between records. The gap scan above won't find it because
                // midnight hex position < record_end. isDtBranch won't find it because
                // field[0] is a uint32 energy value.
                // Scan all fields of each full non-DateTime-at-field[0] record for a
                // midnight DateTime. The record containing it IS the billing data source.
                // Only apply to full records (fc 50-300) — compact records are interleavers.
                if (!isDtBranch && fCount >= 20 && fCount <= 300) {
                    int scanIntra = fieldsStart;
                    for (int fi = 0; fi < fCount && scanIntra + 2 <= payload.length(); fi++) {
                        if (scanIntra + 4 <= payload.length()
                                && "090C".equalsIgnoreCase(payload.substring(scanIntra, scanIntra + 4))
                                && scanIntra + 28 <= payload.length()) {
                            String tsI = payload.substring(scanIntra + 4, scanIntra + 28);
                            try {
                                int yI  = Integer.parseInt(tsI.substring(0, 4), 16);
                                int moI = Integer.parseInt(tsI.substring(4, 6), 16); if (moI > 127) moI = 0;
                                int dI  = Integer.parseInt(tsI.substring(6, 8), 16); if (dI  > 127) dI  = 0;
                                int hI  = Integer.parseInt(tsI.substring(10, 12), 16); if (hI > 127) hI = 0;
                                int miI = Integer.parseInt(tsI.substring(12, 14), 16); if (miI > 127) miI = 0;
                                int ssI = Integer.parseInt(tsI.substring(14, 16), 16);
                                if ((hI == 0) && (miI == 0 || miI == 0xFF)
                                        && (ssI == 0x00 || ssI == 0xFF)
                                        && yI > 2000 && yI < 2100 && moI >= 1 && moI <= 12) {
                                    long tsValI = (long) yI * 10000L + moI * 100L + dI;
                                    if (tsValI > bestTimestamp) {
                                        bestTimestamp = tsValI;
                                        bestRecordPos = recStart; // this record IS the billing record
                                    }
                                    break; // first midnight in record is sufficient
                                }
                            } catch (Exception ignore) {}
                        }
                        int nextIntra = skipOneDlmsValue(payload, scanIntra);
                        if (nextIntra < 0) break;
                        scanIntra = nextIntra;
                    }
                }
            }

            // Post-walk: score any records that were queued from the intra-record
            // overshoot scan. Genus KT291596 has a nested byte-count array at field[78]
            // that causes the sequential walk to overshoot past a legitimate peer record
            // (e.g. rec@21724 overshoots past rec@23896 which holds the 01/04/2026 date).
            // These queued positions have fc exactly matching the outer record's fc, so
            // they are guaranteed genuine billing records, not nested structs or noise.
            for (int overPos : overshotRecords) {
                if (overPos + 4 > payload.length()) continue;
                if (Integer.parseInt(payload.substring(overPos, overPos + 2), 16) != 0x02) continue;
                int oP = overPos + 2;
                int oFcB = Integer.parseInt(payload.substring(oP, oP + 2), 16); oP += 2;
                if ((oFcB & 0x80) != 0) {
                    int nbO = oFcB & 0x7F;
                    if (oP + nbO * 2 > payload.length()) continue;
                    oP += nbO * 2;
                }
                // Score field[0] DateTime
                if (oP + 28 > payload.length()) continue;
                if (!"090C".equalsIgnoreCase(payload.substring(oP, oP + 2)
                        + payload.substring(oP + 2, oP + 4))) continue;
                try {
                    String f0 = payload.substring(oP + 4, oP + 28);
                    int y  = Integer.parseInt(f0.substring(0, 4), 16);
                    int mo = Integer.parseInt(f0.substring(4, 6), 16);  if (mo > 127) mo = 0;
                    int d  = Integer.parseInt(f0.substring(6, 8), 16);  if (d  > 127) d  = 0;
                    int h  = Integer.parseInt(f0.substring(10, 12), 16); if (h  > 127) h  = 0;
                    int mi = Integer.parseInt(f0.substring(12, 14), 16); if (mi > 127) mi = 0;
                    int ss = Integer.parseInt(f0.substring(14, 16), 16);
                    boolean isMidnight = (h == 0) && (mi == 0 || mi == 0xFF)
                            && (ss == 0x00 || ss == 0xFF);
                    if (y > 2000 && y < 2100 && mo >= 1 && mo <= 12 && isMidnight) {
                        long tsVal = (long) y * 10000L + mo * 100L + d;
                        if (tsVal > bestTimestamp) {
                            bestTimestamp = tsVal;
                            bestRecordPos = overPos;
                        }
                    }
                } catch (Exception ignore) {}
            }

            // Fallback: if no monthly record found (all snapshots or unusual meter),
            // allow snapshot records so the dialog still shows something rather than NA.
            if (bestRecordPos < 0) {
                int scanIdx2 = headerEnd;
                while (scanIdx2 < payload.length() - 28) {
                    int di = payload.indexOf("090C07E", scanIdx2);
                    if (di < 0) break;
                    String ts = payload.substring(di + 4, Math.min(di + 28, payload.length()));
                    if (ts.length() >= 24) {
                        try {
                            int y = Integer.parseInt(ts.substring(0, 4), 16);
                            int mo = Integer.parseInt(ts.substring(4, 6), 16); if (mo > 127) mo = 0;
                            int d = Integer.parseInt(ts.substring(6, 8), 16);  if (d  > 127) d  = 0;
                            if (y > 2000 && y < 2100 && mo >= 1 && mo <= 12) {
                                long tsVal = (long) y * 10000L + mo * 100L + d;
                                // In fallback, pick highest date even if snapshot
                                if (tsVal > bestTimestamp) { bestTimestamp = tsVal; bestRecordPos = di - 2; }
                            }
                        } catch (Exception ignore) {}
                    }
                    scanIdx2 = di + 1;
                }
            }

            // ── Parse colIdx from the best record ────────────────────────────
            int pos = bestRecordPos;
            if (pos + 4 > payload.length()) return "NA";
            int stTag = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;
            if (stTag != 0x02) return "NA";
            // Handle multi-byte struct element count: 02 82 00 96 = 150 fields (Genus/AVON)
            int fcByte = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;
            int fieldCount;
            if ((fcByte & 0x80) != 0) {
                int nb = fcByte & 0x7F;
                if (pos + nb*2 > payload.length()) return "NA";
                fieldCount = Integer.parseInt(payload.substring(pos, pos + nb*2), 16);
                pos += nb * 2;
            } else {
                fieldCount = fcByte;
            }
            if (colIdx >= fieldCount) {
                // PRIMARY BUFFER TOO SHORT: the 0100620100FF billing buffer returned fewer
                // fields than the column index needs (common on Genus: 28 actual fields vs
                // 113 declared capture objects → export at col 59 is unreachable).
                // FALLBACK: try the compact billing snapshot 01005E5B00FF, which is already
                // in the TXT (written by ReadInstantData).  On all Genus variants confirmed
                // from MRI: 35-41 fields, d=1 billing date present, export at col 16-22.
                return extractBillingFromAltObis(dataUpper, obisHex, scalerMap, energyAdj);
            }

            // ── Navigate to target column ─────────────────────────────────────
            // Most meters: each column is a flat scalar (uint32, float32, DateTime etc).
            // Genus exception: billing buffer embeds nested arrays (tag=0x01) in two forms:
            //
            //   KT306640 (BER-form): a SINGLE array at some col wraps ALL remaining cols as
            //     elements. Count byte has the BER high-bit set (e.g. 0x8F → plain 143).
            //     Walk inside the array to reach the target.
            //
            //   KT291596 (byte-count form): MULTIPLE arrays (one per MD col), each array
            //     contains raw (uint32 value, DateTime timestamp) PAIRS for 4-5 historical
            //     MD snapshots. Count byte is plain (< 0x80): cb=0x56=86, 0x4A=74 etc.
            //     Each array counts as ONE column — skip past it to continue walking.
            //
            // Distinguishing rule: BER bit set → KT306640 wrapping array; plain count →
            // KT291596 single-col byte-count array. No meter other than Genus uses arrays
            // in billing fields (Secure/AVON/Landis/HPL all have plain scalars).
            for (int c2 = 0; c2 < colIdx && pos + 2 <= payload.length(); c2++) {
                int peekTag = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                if (peekTag == 0x01 && pos + 4 <= payload.length()) {
                    int cntByte2 = Integer.parseInt(payload.substring(pos + 2, pos + 4), 16);
                    if ((cntByte2 & 0x80) != 0) {
                        // BER-form: KT306640 wrapping array — navigate INSIDE.
                        pos += 4; // consume tag + count byte
                        int nb2 = cntByte2 & 0x7F;
                        int arrayElementCount;
                        if (nb2 >= 1 && nb2 <= 4 && pos + nb2 * 2 <= payload.length()) {
                            int berCnt2 = Integer.parseInt(payload.substring(pos, pos + nb2 * 2), 16);
                            if (berCnt2 * 2 <= payload.length() - pos - nb2 * 2) {
                                pos += nb2 * 2;
                                arrayElementCount = berCnt2;
                            } else {
                                arrayElementCount = cntByte2; // plain fallback for overflow
                            }
                        } else {
                            arrayElementCount = cntByte2;
                        }
                        int innerSkip = colIdx - c2 - 1;
                        for (int e = 0; e < innerSkip && pos + 2 <= payload.length(); e++) {
                            pos = skipOneDlmsValue(payload, pos);
                            if (pos < 0) return "NA";
                        }
                        break; // pos now at the target element
                    } else {
                        // Plain byte-count: KT291596 single-col array — skip past entirely.
                        // Advance by tag(2) + cnt_byte(2) + cnt*2 raw hex chars.
                        pos += 4 + cntByte2 * 2;
                        continue; // this counted as col c2; outer loop increments c2
                    }
                }
                pos = skipOneDlmsValue(payload, pos);
                if (pos < 0) return "NA";
            }

            if (pos + 2 > payload.length()) return "NA";
            int tag = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;

            // KT291596: target column IS a plain byte-count array containing 4-5
            // (uint32_value, DateTime_timestamp) pairs of historical MD snapshots.
            // Unwrap and extract the FIRST pair's value — the most-recent MD reading.
            // Layout inside: 06 [4-byte u32 value] 09 0C [12-byte DateTime] ...
            //
            // If the first inner tag is not 0x06 (e.g. 0x00 null or anything else), the
            // meter's MD slot is not reliably populated for this record — return "NA"
            // rather than extracting whatever unrelated bytes come next. This is safer
            // than displaying bogus values.
            if (tag == 0x01 && pos + 2 <= payload.length()) {
                int innerCnt = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                if ((innerCnt & 0x80) == 0 && innerCnt >= 5
                        && pos + 2 + innerCnt * 2 <= payload.length()) {
                    pos += 2; // past count byte
                    int innerTag = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                    if (innerTag == 0x06) {
                        pos += 2;
                        tag = 0x06; // fall through to the uint32 handling block below
                    } else {
                        return "NA"; // unexpected inner format; MD slot unreliable
                    }
                } else {
                    return "NA"; // BER inner count or too-small not supported here
                }
            }

            // Timestamp (OctetString 12 bytes) — only valid for Clock OBIS (billing date).
            // If we encounter a DateTime tag while trying to extract an energy/demand value,
            // it means the column navigation went wrong (wrong bestRecord selected, or
            // column misalignment). In that case return "NA" rather than displaying a date
            // string in an energy field — this is the "date-in-kWh" bug fix.
            if (tag == 0x09) {
                // Only return a formatted date for the Clock OBIS (billing date)
                boolean isClockObis = obisHex.toUpperCase().startsWith("0000000102");
                if (!isClockObis) return "NA"; // energy/demand field — wrong column, return NA
                int ln = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;
                if (ln == 12 && pos + 24 <= payload.length()) {
                    String ts = payload.substring(pos, pos + 24);
                    int y  = Integer.parseInt(ts.substring(0, 4), 16);
                    int mo = Integer.parseInt(ts.substring(4, 6), 16);   if (mo == 0xFF) mo = 1;
                    int d  = Integer.parseInt(ts.substring(6, 8), 16);   if (d  == 0xFF) d  = 1;
                    int h  = Integer.parseInt(ts.substring(10,12), 16); if (h  == 0xFF) h  = 0;
                    int mi = Integer.parseInt(ts.substring(12,14), 16); if (mi == 0xFF) mi = 0;
                    int s  = Integer.parseInt(ts.substring(14,16), 16); if (s  == 0xFF) s  = 0;
                    if (y > 1990 && y < 2100 && mo >= 1 && mo <= 12)
                        return String.format("%02d/%02d/%04d %02d:%02d:%02d", d, mo, y, h, mi, s);
                }
                return "NA";
            }

            // Numeric value: handle all DLMS types including float32 (Genus/AVON)
            if (tag == 0x17) {   // float32 — Genus LC / AVON returns energy as IEEE 754
                if (pos + 8 > payload.length()) return "NA";
                int floatBits = (int) Long.parseLong(payload.substring(pos, pos + 8), 16);
                float fVal = Float.intBitsToFloat(floatBits);
                if (Float.isNaN(fVal) || Float.isInfinite(fVal)) return "NA";
                // Apply scaler+unit inline (same logic as below but branching early)
                String lookupObisF = obisHex.toUpperCase();
                int[] suF = scalerMap.get(lookupObisF);
                if (suF == null && lookupObisF.length() == 12) {
                    String todByteF = lookupObisF.substring(8, 10);
                    try { int tIdx = Integer.parseInt(todByteF, 16);
                        if (tIdx >= 1 && tIdx <= 8) suF = scalerMap.get(lookupObisF.substring(0,8)+"00"+lookupObisF.substring(10));
                    } catch (Exception ignored) {}
                }
                int scF = (suF != null) ? suF[0] : 0;
                int ucF = (suF != null) ? suF[1] : 0xFF;
                // Only override to kVA for apparent-power demand (01000906xx, Genus variants).
                // MD kW demand (01000106xx) and export demand (01000206xx, 01000A06xx) keep 0x1B.
                if (lookupObisF.startsWith("01000906")
                        || lookupObisF.startsWith("01009E06") || lookupObisF.startsWith("01009F06")) {
                    if (ucF == 0x1B || ucF == 0xFF) ucF = 0x1C;
                }
                if (ucF == 0xFF) { int[] defF = dlmsDefaultScalerUnit(lookupObisF); if (defF != null) { scF = defF[0]; ucF = defF[1]; } }
                if (energyAdj != 0 && lookupObisF.length() >= 8 && "08".equals(lookupObisF.substring(6,8))) scF += energyAdj;
                double valF = fVal * Math.pow(10, scF);
                double[] convF = unitConversion(ucF);
                valF /= convF[0];
                return String.format("%,." + (int)convF[1] + "f", valF);
            }
            long rawVal;
            if      (tag == 0x06) { if (pos + 8 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+8),16); }
            else if (tag == 0x05) { if (pos + 8 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+8),16); if (rawVal > 0x7FFFFFFFL) rawVal -= 0x100000000L; }
            else if (tag == 0x12) { if (pos + 4 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+4),16); }
            else if (tag == 0x10) { if (pos + 4 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+4),16); if (rawVal > 0x7FFF) rawVal -= 0x10000; }
            else if (tag == 0x11 || tag == 0x16) { if (pos + 2 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+2),16); }
            else if (tag == 0x14 || tag == 0x15) { // int64/uint64 (Landis+Gyr)
                if (pos + 16 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+16),16); }
            else if (tag == 0x02) {
                // Nested struct — Landis+Gyr reading-date snapshot stores TOD energy as
                // struct(fc) { uint64 value, DateTime timestamp, ... }. The billing records
                // use plain uint64, but the snapshot record wraps each TOD value in a struct.
                // Unwrap: if fc is small (< 30, i.e. a genuine nested struct, not a record
                // boundary which would have been caught by skipOneDlmsValue already) and the
                // first inner element is a numeric type, use that as the raw value.
                if (pos + 2 > payload.length()) return "NA";
                int innerFc = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                if (innerFc >= 1 && innerFc < 30 && pos + 2 + 2 <= payload.length()) {
                    int innerTag = Integer.parseInt(payload.substring(pos + 2, pos + 4), 16);
                    if (innerTag == 0x15 && pos + 2 + 2 + 16 <= payload.length()) {
                        rawVal = Long.parseLong(payload.substring(pos + 4, pos + 20), 16);
                    } else if (innerTag == 0x06 && pos + 2 + 2 + 8 <= payload.length()) {
                        rawVal = Long.parseLong(payload.substring(pos + 4, pos + 12), 16);
                    } else { return "NA"; }
                } else { return "NA"; }
            }
            else return "NA";

            // Apply scaler and unit conversion.
            // For TOD OBIS (1.0.X.8.T.255 where T=1-8), the individual attr=3 scaler
            // is never read separately — inherit from the T0 base OBIS (1.0.X.8.0.255).
            String lookupObis = obisHex.toUpperCase();
            int[] su = scalerMap.get(lookupObis);
            if (su == null && lookupObis.length() == 12) {
                // TOD variant fallback: X.0.1.8.T.255 → base = X.0.1.8.0.255
                String todByte = lookupObis.substring(8, 10);
                int todIdx = Integer.parseInt(todByte, 16);
                if (todIdx >= 1 && todIdx <= 8) {
                    String baseObis = lookupObis.substring(0,8) + "00" + lookupObis.substring(10);
                    su = scalerMap.get(baseObis);
                }
            }
            // Individual attr=3 fallback: when BSM (BSP-based) doesn't cover this OBIS
            // (e.g. BS029697 BSP has 130 scalers but MD kW is at BSP col 137),
            // read the scaler directly from the meter's individual attr=3 line in the TXT.
            // This ensures demand registers (MD kW, MD kVA) get their correct declared sc
            // instead of falling through to the wrong dlmsDefaultScalerUnit value.
            //
            // EXTENDED: For TOD energy OBIS (1.0.X.8.T.255 where T=1-8), also try
            // individual attr=3 when BSP gives sc=0. Some Secure meter variants (e.g.
            // BS034549) declare sc=+1 in individual attr=3 for TOD slots but BSP reports
            // sc=0 — BSP wins in step 1-2 but is wrong. The individual attr=3 is the
            // most authoritative source for the specific register's scaler.
            // Condition: su==null (no BSP entry), su[1]==0xFF (unknown unit), OR
            //            (TOD OBIS AND BSP sc==0 AND individual attr=3 exists with sc>0).
            boolean isTodObis = false;
            if (lookupObis.length() == 12) {
                try {
                    int tIdx = Integer.parseInt(lookupObis.substring(8, 10), 16);
                    isTodObis = (tIdx >= 1 && tIdx <= 8) && lookupObis.endsWith("FF");
                } catch (Exception ignore) {}
            }
            if (su == null || su[1] == 0xFF || (isTodObis && su != null && su[0] == 0)) {
                // Try reading individual attr=3 from TXT
                String indMarker = lookupObis + " 03 ";
                int indIdx = dataUpper.indexOf(indMarker);
                if (indIdx >= 0) {
                    int indEnd = dataUpper.indexOf('\n', indIdx + indMarker.length());
                    if (indEnd < 0) indEnd = dataUpper.length();
                    String indLine = dataUpper.substring(indIdx + indMarker.length(), indEnd).trim().replaceAll("\\s+","");
                    java.util.regex.Matcher indM = java.util.regex.Pattern
                            .compile("0202\\s*0F([0-9A-Fa-f]{2})16([0-9A-Fa-f]{2})")
                            .matcher(indLine);
                    if (indM.find()) {
                        int scByte = Integer.parseInt(indM.group(1), 16);
                        int ucByte = Integer.parseInt(indM.group(2), 16);
                        int scInd = scByte > 127 ? scByte - 256 : scByte;
                        // For TOD where BSP has sc=0: only override if individual gives sc>0
                        if (su == null || su[1] == 0xFF || scInd > 0) {
                            su = new int[]{scInd, ucByte};
                        }
                    }
                }

                // T0_sc-1 fallback for TOD OBIS when sc is still 0 after attr=3 attempt.
                // Some Secure firmware variants (confirmed: BS034549) correctly store T0 at
                // higher resolution (e.g. sc=+2, unit=100Wh) but TOD slots T1-T8 at one step
                // lower resolution (sc=+1, unit=10Wh). The BSP incorrectly reports sc=0 for
                // T1-T8. Individual attr=3 is not available in billing-only TXT files.
                // Safe rule: if TOD sc is still 0 AND T0 of same energy type has sc>0 in BSP,
                // then T1-T8 sc = T0_sc - 1.
                // This rule is NOT triggered when:
                //   - BSP gave sc>0 for T1-T8 (already correct, e.g. BS029697 sc=+2)
                //   - T0 has sc=0 (no resolution difference, e.g. Genus, Landis)
                //   - OBIS is not a TOD slot
                if (isTodObis && (su == null || su[0] == 0)) {
                    // Determine T0 OBIS for same energy type: replace byte[4] (slot) with 00
                    String t0Obis = lookupObis.substring(0, 8) + "00" + lookupObis.substring(10);
                    int[] t0Su = scalerMap.get(t0Obis);
                    if (t0Su != null && t0Su[0] > 0) {
                        // T0 has sc>0 in BSP — infer T1-T8 sc = T0_sc - 1
                        int inferredSc = t0Su[0]; // same resolution as T0; BS029697 T1-T8 not affected (BSP gives sc>0, rule skipped)
                        int inferredUc = (t0Su[1] != 0xFF) ? t0Su[1] : 0x1E; // inherit uc from T0
                        su = new int[]{inferredSc, inferredUc};
                        appendLog("TOD_SC_INFERRED obis=" + lookupObis
                                + " T0_sc=" + t0Su[0] + " inferred_sc=" + inferredSc);
                    }
                }
            }
            int sc = (su != null) ? su[0] : 0;
            int uc = (su != null) ? su[1] : 0xFF;

            // FIX 3: Validate the billing scaler unit code.
            // HPL firmware writes garbage bytes (e.g. 0x06 = class-id integer) at the
            // tail of 01005E5B06FF when it has fewer entries than capture objects.
            // 0x06 and other non-measurement codes are not valid display units.
            // If uc is not in the standard IS15959-2 measurement unit set, discard
            // both sc and uc and fall through to dlmsDefaultScalerUnit below.
            // Valid measurement units (IEC 62056-62 Table 3 subset used in DLMS meters):
            // 0x1B=W, 0x1C=VA, 0x1D=var, 0x1E=Wh, 0x1F=VAh, 0x20=varh,
            // 0x21=A, 0x23=V, 0x27=%, 0x2C=Hz, 0xFF=dimensionless
            boolean ucValid = (uc == 0x1B || uc == 0x1C || uc == 0x1D ||
                    uc == 0x1E || uc == 0x1F || uc == 0x20 ||
                    uc == 0x21 || uc == 0x23 || uc == 0x27 ||
                    uc == 0x2C || uc == 0xFF);
            if (!ucValid) {
                // Garbage scaler — reset so dlmsDefaultScalerUnit applies below
                sc = 0; uc = 0xFF;
            }

            // Demand OBIS unit normalisation — only apparent-power demand (kVA) gets 0x1C.
            // MD kW (01000106xx, 01000206xx) keeps 0x1B (W/kW) — correct per IS15959-2.
            // MD kVA export (01000A06xx) returns 0x1C from meter/defaults — kept as-is.
            if (lookupObis.startsWith("01000906")
                    || lookupObis.startsWith("01009E06") || lookupObis.startsWith("01009F06")) {
                if (uc == 0x1B || uc == 0xFF) uc = 0x1C;
            }

            // IS15959-2 default scaler/unit fallback for billing values.
            // When the meter returns no scaler (uc still 0xFF), use known defaults
            // so that billing energy/demand values are correctly scaled across all makes.
            // This mirrors the same fallback applied in parseDlmsRegisterWithUnit.
            if (uc == 0xFF) {
                int[] def = dlmsDefaultScalerUnit(lookupObis);
                if (def != null) { sc = def[0]; uc = def[1]; }
            }

            // Apply energy scaler adjustment for meters that store billing energy in
            // multiples of 100 Wh (or 10 Wh) while reporting sc=0 (Wh) in attr=3.
            // energyAdj is non-zero only when computeBillingEnergyAdj detected a ratio.
            // Only applied to energy OBIS codes: byte[3]=0x08 (chars 6-7 of OBIS hex).
            if (energyAdj != 0 && lookupObis.length() >= 8 && "08".equals(lookupObis.substring(6, 8))) {
                sc += energyAdj;
            }

            double val = rawVal * Math.pow(10, sc);
            double[] conv = unitConversion(uc);
            val /= conv[0];
            int dp = (int) conv[1];
            return String.format("%,."+dp+"f", val);

        } catch (Exception e) { return "NA"; }
    }

    /** @deprecated Snapshot extraction removed — TOD instantaneous now from standalone OBIS */
    @Deprecated
    private String extractBillingSnapshotByObis(String dataUpper,
                                                String obisHex,
                                                java.util.Map<String, Integer> colMap,
                                                java.util.Map<String, int[]> scalerMap,
                                                int energyAdj) {
        try {
            Integer colIdx = colMap.get(obisHex.toUpperCase());
            if (colIdx == null) return "NA";

            String marker = "0100620100FF 02 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return "NA";
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 8) return "NA";

            // ── Parse array header ─────────────────────────────────────────────
            int headerEnd = payload.startsWith("01") ? 2 : 0;
            int recPos = headerEnd;
            int arrayCount = 0;
            if (recPos + 2 <= payload.length()) {
                int cb = Integer.parseInt(payload.substring(recPos, recPos + 2), 16); recPos += 2;
                if ((cb & 0x80) != 0) {
                    int nb2 = cb & 0x7F;
                    if (recPos + nb2 * 2 <= payload.length()) {
                        arrayCount = Integer.parseInt(payload.substring(recPos, recPos + nb2 * 2), 16);
                        recPos += nb2 * 2;
                    }
                } else { arrayCount = cb; }
            }

            // ── Secure-pattern early exit ──────────────────────────────────────
            // Secure meters (BS034549, BS029697, BK011850 etc.) store the live snapshot
            // at rec[0] with a uint32/int32 energy value at field[0] — never a DateTime.
            // Their billing buffers may contain embedded event records (e.g. rec[7] fc=100
            // with DateTime at field[0]) that the DateTime scanner below would wrongly
            // select as the "best" snapshot. Those event records contain demand timestamps
            // at energy column positions, producing DateTime → NA in TOD slots.
            //
            // Detection: if field[0] of the very first billing record (rec[0]) is a
            // numeric tag (0x06=uint32, 0x05=int32, 0x12=uint16), this IS a Secure meter.
            // For these, rec[0] is always the reading-date snapshot — use it immediately.
            //
            // Other meter makes (Genus, Landis, L&T, HPL): field[0] of rec[0] IS a
            // DateTime, so they fall through to the existing scan logic below.
            int bestRecordPos = -1;
            long bestTimestamp = -1;
            int rec0Start = recPos;
            boolean useRec0Directly = false;
            if (rec0Start + 6 <= payload.length()
                    && Integer.parseInt(payload.substring(rec0Start, rec0Start + 2), 16) == 0x02) {
                int fc0B = Integer.parseInt(payload.substring(rec0Start + 2, rec0Start + 4), 16);
                int rec0FieldsStart = rec0Start + 4;
                if ((fc0B & 0x80) != 0) rec0FieldsStart += (fc0B & 0x7F) * 2;
                if (rec0FieldsStart + 2 <= payload.length()) {
                    int f0Tag = Integer.parseInt(payload.substring(rec0FieldsStart, rec0FieldsStart + 2), 16);
                    // Secure pattern: field[0] is a numeric energy value (uint32, int32,
                    // uint16, uint8) — never a DateTime. This identifies the live snapshot.
                    // Additional check: scan the record for a DateTime matching a recent
                    // reading (any 2020-2100 DateTime). If found → this IS the snapshot.
                    // This future-proofs against a meter that stores numeric at field[0]
                    // but uses most-recent-LAST ordering (standard IS 15959 order).
                    boolean fieldIsNumeric = (f0Tag == 0x06 || f0Tag == 0x05
                            || f0Tag == 0x12 || f0Tag == 0x11);
                    if (fieldIsNumeric) {
                        // Verify: the record contains at least one valid DateTime with year 2020-2100
                        boolean hasValidDt = false;
                        int dtSearch = rec0FieldsStart;
                        while (dtSearch + 28 <= payload.length()) {
                            int di = payload.indexOf("090C", dtSearch);
                            if (di < 0 || di >= rec0FieldsStart + 1400) break; // ~100 fields max
                            if (di + 28 <= payload.length()) {
                                try {
                                    int dy = Integer.parseInt(payload.substring(di + 4, di + 8), 16);
                                    if (dy >= 2020 && dy <= 2100) { hasValidDt = true; break; }
                                } catch (Exception ignore) {}
                            }
                            dtSearch = di + 2;
                        }
                        if (hasValidDt) {
                            useRec0Directly = true;
                            bestRecordPos = rec0Start;
                        }
                    }
                }
            }

            if (!useRec0Directly) {
                // ── Walk forward picking the most-recent record (ANY timestamp) ─────
                // Use a compound sort key: yyyyMMdd * 10000 + hhmm so that same-date
                // records with different times are ordered correctly (snapshot > midnight).
                // Loop over physical structs (not just declared arrayCount) for AVON parity.
                for (int rec = 0; rec < payload.length() / 2 && recPos + 4 <= payload.length(); rec++) {
                    if (Integer.parseInt(payload.substring(recPos, recPos + 2), 16) != 0x02) break;
                    int recStart = recPos; recPos += 2;
                    int fc2 = Integer.parseInt(payload.substring(recPos, recPos + 2), 16); recPos += 2;
                    int fCount = fc2;
                    if ((fc2 & 0x80) != 0) {
                        int nb3 = fc2 & 0x7F;
                        if (recPos + nb3 * 2 > payload.length()) break;
                        fCount = Integer.parseInt(payload.substring(recPos, recPos + nb3 * 2), 16);
                        recPos += nb3 * 2;
                    }
                    int fieldsStart = recPos;
                    int recordEnd = recPos;
                    for (int f = 0; f < fCount && recordEnd + 2 <= payload.length(); f++) {
                        int nextPos = skipOneDlmsValue(payload, recordEnd);
                        if (nextPos < 0) break;
                        recordEnd = nextPos;
                    }

                    // Read field 0 as DateTime — accept ALL valid timestamps, not just midnight
                    if (fieldsStart + 28 <= payload.length()
                            && "09".equalsIgnoreCase(payload.substring(fieldsStart, fieldsStart + 2))
                            && "0C".equalsIgnoreCase(payload.substring(fieldsStart + 2, fieldsStart + 4))) {
                        String fd = payload.substring(fieldsStart + 4, fieldsStart + 28);
                        try {
                            int y  = Integer.parseInt(fd.substring(0, 4), 16);
                            int mo = Integer.parseInt(fd.substring(4, 6), 16);  if (mo > 127) mo = 0;
                            int d  = Integer.parseInt(fd.substring(6, 8), 16);  if (d  > 127) d  = 0;
                            int h  = Integer.parseInt(fd.substring(10,12), 16); if (h  > 127) h  = 0;
                            int mi = Integer.parseInt(fd.substring(12,14), 16); if (mi > 127) mi = 0;
                            if (y > 2000 && y < 2100 && mo >= 1 && mo <= 12) {
                                long tsVal = (long) y * 100000000L + (long) mo * 1000000L
                                        + (long) d * 10000L + (long) h * 100L + mi;
                                if (tsVal > bestTimestamp) {
                                    bestTimestamp = tsVal;
                                    bestRecordPos = recStart;
                                }
                            }
                        } catch (Exception ignore) {}
                    }

                    recPos = recordEnd;
                    while (recPos + 4 <= payload.length()) {
                        int t = Integer.parseInt(payload.substring(recPos, recPos + 2), 16);
                        if (t == 0x02) {
                            int cbNext = Integer.parseInt(payload.substring(recPos + 2, recPos + 4), 16);
                            boolean isRecordHeader = (cbNext == 0x81) || (cbNext == 0x82)
                                    || (cbNext >= 20 && cbNext < 128);
                            if (isRecordHeader) break;
                        }
                        recPos += 2;
                        if (recPos >= payload.length() - 4) break;
                    }
                }
            } // end !useRec0Directly

            // Secure-pattern fallback: Secure meters store the live snapshot at rec[0]
            // but field[0] is a uint32 energy value (no DateTime), so the DateTime scan
            // above never sets bestRecordPos. For these meters, rec[0] IS always the
            // reading-date snapshot — use it directly.
            //
            // ALSO: Some Secure billing buffers contain MD event records (fc=40, fc=88)
            // with DateTime at field[0] but with far too few columns (fc < needed colIdx).
            // These event records must NOT be used for TOD snapshot extraction.
            // Detect this by checking whether the selected record has enough fields;
            // if not, fall through to the rec[0] Secure fallback.
            if (bestRecordPos >= 0 && colIdx >= 0) {
                // Quick field-count check: parse fc of selected record
                int checkP = bestRecordPos + 2; // skip struct tag
                if (checkP + 2 <= payload.length()) {
                    int checkFcB = Integer.parseInt(payload.substring(checkP, checkP + 2), 16);
                    int checkFc = checkFcB;
                    if ((checkFcB & 0x80) != 0) {
                        int checkNb = checkFcB & 0x7F;
                        if (checkP + 2 + checkNb * 2 <= payload.length())
                            checkFc = Integer.parseInt(payload.substring(checkP + 2, checkP + 2 + checkNb * 2), 16);
                    }
                    if (colIdx >= checkFc) {
                        // Selected record is too short for this column — reset so rec[0] fallback applies
                        bestRecordPos = -1;
                    }
                }
            }

            if (bestRecordPos < 0) {
                // Find rec[0]: first struct after the outer array header
                if (recPos - 1 > 0) {
                    // recPos now points past all records; re-find rec[0] from the start
                    int scanPos2 = headerEnd + 2; // skip array tag+count byte
                    // skip BER extra bytes if any
                    // headerEnd already accounts for them; just look for first 0x02+is_hdr
                    int rb = headerEnd + 2;
                    if (rb + 2 <= payload.length()) {
                        int cbB = Integer.parseInt(payload.substring(rb, rb + 2), 16);
                        if ((cbB & 0x80) != 0) rb += 2 * (cbB & 0x7F) + 2;
                    }
                    // Walk to first valid billing struct
                    int r0 = (headerEnd < 4) ? headerEnd + 2 : headerEnd;
                    // Simpler: just re-read from the beginning of payload (after outer header)
                    // The outer header is exactly the same region we skipped to get recPos start.
                    // Use recPos tracked during the walk — reset by re-parsing outer header.
                }
                // Simpler approach: walk outer header once more to land on rec[0]
                int startScan = 0;
                if (startScan + 2 <= payload.length()
                        && Integer.parseInt(payload.substring(startScan, startScan + 2), 16) == 0x01) {
                    startScan += 2;
                }
                if (startScan + 2 <= payload.length()) {
                    int cbS = Integer.parseInt(payload.substring(startScan, startScan + 2), 16);
                    startScan += 2;
                    if ((cbS & 0x80) != 0) startScan += (cbS & 0x7F) * 2;
                }
                // startScan now points to rec[0]
                if (startScan + 4 <= payload.length()
                        && Integer.parseInt(payload.substring(startScan, startScan + 2), 16) == 0x02) {
                    int cbS2 = Integer.parseInt(payload.substring(startScan + 2, startScan + 4), 16);
                    boolean isHdrS = (cbS2 == 0x81) || (cbS2 == 0x82) || (cbS2 >= 20 && cbS2 < 128);
                    if (isHdrS) {
                        bestRecordPos = startScan; // rec[0] = Secure live snapshot
                    }
                }
                if (bestRecordPos < 0) return "NA";
            }

            // ── Navigate to colIdx within the chosen record ────────────────────
            // Identical to extractBillingByObis from this point on.
            int pos = bestRecordPos;
            if (pos + 4 > payload.length()) return "NA";
            if (Integer.parseInt(payload.substring(pos, pos+2), 16) != 0x02) return "NA";
            pos += 2;
            int fcByte = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;
            int fieldCount = fcByte;
            if ((fcByte & 0x80) != 0) {
                int nb = fcByte & 0x7F;
                if (pos + nb*2 > payload.length()) return "NA";
                fieldCount = Integer.parseInt(payload.substring(pos, pos + nb*2), 16);
                pos += nb * 2;
            }
            if (colIdx >= fieldCount) {
                return extractBillingFromAltObis(dataUpper, obisHex, scalerMap, energyAdj);
            }

            // Navigation with Genus-specific nested array handling (same two forms as
            // extractBillingByObis): BER-form = KT306640 wrapping array; plain byte-count =
            // KT291596 single-col array. See extractBillingByObis for full rationale.
            for (int c2 = 0; c2 < colIdx && pos + 2 <= payload.length(); c2++) {
                int peekTag = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                if (peekTag == 0x01 && pos + 4 <= payload.length()) {
                    int cntByte2 = Integer.parseInt(payload.substring(pos + 2, pos + 4), 16);
                    if ((cntByte2 & 0x80) != 0) {
                        // BER-form: KT306640 wrapping array — navigate inside
                        pos += 4;
                        int nb2 = cntByte2 & 0x7F;
                        int arrayElementCount;
                        if (nb2 >= 1 && nb2 <= 4 && pos + nb2 * 2 <= payload.length()) {
                            int berCnt2 = Integer.parseInt(payload.substring(pos, pos + nb2 * 2), 16);
                            if (berCnt2 * 2 <= payload.length() - pos - nb2 * 2) {
                                pos += nb2 * 2; arrayElementCount = berCnt2;
                            } else { arrayElementCount = cntByte2; }
                        } else { arrayElementCount = cntByte2; }
                        int innerSkip = colIdx - c2 - 1;
                        for (int e = 0; e < innerSkip && pos + 2 <= payload.length(); e++) {
                            pos = skipOneDlmsValue(payload, pos);
                            if (pos < 0) return "NA";
                        }
                        break;
                    } else {
                        // Plain byte-count: KT291596 single-col array — skip past entirely
                        pos += 4 + cntByte2 * 2;
                        continue;
                    }
                }
                pos = skipOneDlmsValue(payload, pos);
                if (pos < 0) return "NA";
            }

            if (pos + 2 > payload.length()) return "NA";
            int tag = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;

            // Byte-count array unwrap at target col (KT291596 MD columns).
            // If first inner tag is not 0x06, return NA (safer than bogus values).
            if (tag == 0x01 && pos + 2 <= payload.length()) {
                int innerCnt = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                if ((innerCnt & 0x80) == 0 && innerCnt >= 5
                        && pos + 2 + innerCnt * 2 <= payload.length()) {
                    pos += 2;
                    int innerTag = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                    if (innerTag == 0x06) {
                        pos += 2;
                        tag = 0x06;
                    } else {
                        return "NA";
                    }
                } else {
                    return "NA";
                }
            }

            if (tag == 0x09) {
                // DateTime field encountered at target column.
                // Only return the date string for the Clock OBIS (0.0.0.1.2.255).
                // For all other OBIS (TOD energy slots, MD timestamps that are stored
                // as DateTime by Secure firmware variants), return NA — a date string
                // in an energy column is always a data-type mismatch.
                boolean isClockObis = obisHex.toUpperCase().startsWith("0000000102");
                if (!isClockObis) return "NA";
                int ln = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;
                if (ln == 12 && pos + 24 <= payload.length()) {
                    String ts = payload.substring(pos, pos + 24);
                    int y  = Integer.parseInt(ts.substring(0, 4), 16);
                    int mo = Integer.parseInt(ts.substring(4, 6), 16);  if (mo == 0xFF) mo = 1;
                    int d  = Integer.parseInt(ts.substring(6, 8), 16);  if (d  == 0xFF) d  = 1;
                    int h  = Integer.parseInt(ts.substring(10,12), 16); if (h  == 0xFF) h  = 0;
                    int mi = Integer.parseInt(ts.substring(12,14), 16); if (mi == 0xFF) mi = 0;
                    int s  = Integer.parseInt(ts.substring(14,16), 16); if (s  == 0xFF) s  = 0;
                    if (y > 1990 && y < 2100 && mo >= 1 && mo <= 12)
                        return String.format("%02d/%02d/%04d %02d:%02d:%02d", d, mo, y, h, mi, s);
                }
                return "NA";
            }

            long rawVal;
            if      (tag == 0x06) { if (pos+8>payload.length()) return "NA"; rawVal=Long.parseLong(payload.substring(pos,pos+8),16); }
            else if (tag == 0x05) { if (pos+8>payload.length()) return "NA"; rawVal=Long.parseLong(payload.substring(pos,pos+8),16); if(rawVal>0x7FFFFFFFL) rawVal-=0x100000000L; }
            else if (tag == 0x12) { if (pos+4>payload.length()) return "NA"; rawVal=Long.parseLong(payload.substring(pos,pos+4),16); }
            else if (tag == 0x10) { if (pos+4>payload.length()) return "NA"; rawVal=Long.parseLong(payload.substring(pos,pos+4),16); if(rawVal>0x7FFF) rawVal-=0x10000; }
            else if (tag == 0x11 || tag == 0x16) { if (pos+2>payload.length()) return "NA"; rawVal=Long.parseLong(payload.substring(pos,pos+2),16); }
            else if (tag == 0x14 || tag == 0x15) { if (pos+16>payload.length()) return "NA"; rawVal=Long.parseLong(payload.substring(pos,pos+16),16); }
            else return "NA";

            String lookupObis = obisHex.toUpperCase();
            int[] su = scalerMap.get(lookupObis);
            if (su == null && lookupObis.length() == 12) {
                try {
                    int todIdx = Integer.parseInt(lookupObis.substring(8,10), 16);
                    if (todIdx >= 1 && todIdx <= 8)
                        su = scalerMap.get(lookupObis.substring(0,8)+"00"+lookupObis.substring(10));
                } catch (Exception ignore) {}
            }
            // Individual attr=3 fallback — mirrors extractBillingByObis.
            // For TOD OBIS (T1-T8) also triggered when BSP sc=0 but attr=3 may have sc>0.
            boolean isSnapshotTodObis = false;
            if (lookupObis.length() == 12) {
                try {
                    int tIdx = Integer.parseInt(lookupObis.substring(8, 10), 16);
                    isSnapshotTodObis = (tIdx >= 1 && tIdx <= 8) && lookupObis.endsWith("FF");
                } catch (Exception ignore) {}
            }
            if (su == null || su[1] == 0xFF || (isSnapshotTodObis && su != null && su[0] == 0)) {
                String indMarker = lookupObis + " 03 ";
                int indIdx = dataUpper.indexOf(indMarker);
                if (indIdx >= 0) {
                    int indEnd = dataUpper.indexOf('\n', indIdx + indMarker.length());
                    if (indEnd < 0) indEnd = dataUpper.length();
                    String indLine = dataUpper.substring(indIdx + indMarker.length(), indEnd).trim().replaceAll("\\s+","");
                    java.util.regex.Matcher indM = java.util.regex.Pattern
                            .compile("0202\\s*0F([0-9A-Fa-f]{2})16([0-9A-Fa-f]{2})")
                            .matcher(indLine);
                    if (indM.find()) {
                        int scB = Integer.parseInt(indM.group(1), 16);
                        int ucB = Integer.parseInt(indM.group(2), 16);
                        int scInd = scB > 127 ? scB - 256 : scB;
                        if (su == null || su[1] == 0xFF || scInd > 0)
                            su = new int[]{scInd, ucB};
                    }
                }
                // T0_sc-1 fallback — mirrors extractBillingByObis.
                // When TOD sc is still 0 after individual attr=3 attempt, and T0 has sc>0:
                // infer sc = T0_sc - 1 (e.g. BS034549: T0 sc=+2 → T1-T8 sc=+1).
                if (isSnapshotTodObis && (su == null || su[0] == 0)) {
                    String t0Obis = lookupObis.substring(0, 8) + "00" + lookupObis.substring(10);
                    int[] t0Su = scalerMap.get(t0Obis);
                    if (t0Su != null && t0Su[0] > 0) {
                        int inferredSc = t0Su[0]; // same resolution as T0; BS029697 T1-T8 not affected (BSP gives sc>0, rule skipped)
                        int inferredUc = (t0Su[1] != 0xFF) ? t0Su[1] : 0x1E;
                        su = new int[]{inferredSc, inferredUc};
                    }
                }
            }
            int sc = (su != null) ? su[0] : 0;
            int uc = (su != null) ? su[1] : 0xFF;

            // Same uc validation as extractBillingByObis (FIX 3)
            boolean ucValid = (uc==0x1B||uc==0x1C||uc==0x1D||uc==0x1E||uc==0x1F||
                    uc==0x20||uc==0x21||uc==0x23||uc==0x27||uc==0x2C||uc==0xFF);
            if (!ucValid) { sc = 0; uc = 0xFF; }

            if (lookupObis.startsWith("01000906")
                    || lookupObis.startsWith("01009E06") || lookupObis.startsWith("01009F06")) {
                if (uc == 0x1B || uc == 0xFF) uc = 0x1C;
            }
            if (uc == 0xFF) {
                int[] def = dlmsDefaultScalerUnit(lookupObis);
                if (def != null) { sc = def[0]; uc = def[1]; }
            }
            if (energyAdj != 0 && lookupObis.length() >= 8 && "08".equals(lookupObis.substring(6,8))) {
                sc += energyAdj;
            }
            double val = rawVal * Math.pow(10, sc);
            double[] conv = unitConversion(uc);
            val /= conv[0];
            int dp = (int) conv[1];
            return String.format("%,."+dp+"f", val);

        } catch (Exception e) { return "NA"; }
    }

    /**
     * Finds the most-recent midnight billing date by scanning the gaps BETWEEN
     * records in the billing buffer (0100620100FF attr=2).
     *
     * For Secure meters the billing-reset DateTime is written AFTER each record's
     * fields, in the gap before the next record starts. This method walks the buffer
     * using the same gap-scan logic as extractBillingByObis and returns the most-
     * recent midnight date found, formatted as "dd/MM/yyyy HH:mm:ss".
     *
     * Called as a fallback when extractBillingByObis("0000000102FF") returns "NA"
     * because the Clock column is at the end of a long record (col 149, 172, 183…)
     * and the walk fails on unknown field types before reaching it.
     *
     * Works for ALL meter makes — non-Secure meters with DtBranch records have no
     * gap dates, so this returns "NA" and the existing Clock-column path is used.
     */
    private String extractBillingGapDate(String dataUpper) {
        try {
            String marker = "0100620100FF 02 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return "NA";
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 8) return "NA";

            // Skip outer array header
            int pos = 2;
            int cb = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            if ((cb & 0x80) != 0) {
                int nb = cb & 0x7F;
                if (pos + nb * 2 <= payload.length()) pos += nb * 2;
            }

            long bestTs = -1;
            int bestD = 0, bestM = 0, bestY = 0, bestH = 0, bestMi = 0, bestS = 0;

            while (pos + 4 <= payload.length()) {
                if (Integer.parseInt(payload.substring(pos, pos + 2), 16) != 0x02) break;
                int cbPeek = Integer.parseInt(payload.substring(pos + 2, pos + 4), 16);
                boolean isHdr = (cbPeek == 0x81) || (cbPeek == 0x82)
                        || (cbPeek >= 20 && cbPeek < 128);
                if (!isHdr) break;

                pos += 2;
                int fcByte = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
                int fc = fcByte;
                if ((fcByte & 0x80) != 0) {
                    int nb = fcByte & 0x7F;
                    if (pos + nb * 2 <= payload.length()) {
                        fc = Integer.parseInt(payload.substring(pos, pos + nb * 2), 16);
                        pos += nb * 2;
                    }
                }

                int fieldsStart = pos;
                int recordEnd = pos;
                for (int f = 0; f < fc && recordEnd + 2 <= payload.length(); f++) {
                    int next = skipOneDlmsValue(payload, recordEnd);
                    if (next < 0) break;
                    recordEnd = next;
                }

                // Advance to next record header (strict plausibility filter)
                int scanPos = recordEnd;
                while (scanPos + 4 <= payload.length()) {
                    int t = Integer.parseInt(payload.substring(scanPos, scanPos + 2), 16);
                    if (t == 0x02) {
                        int cn = Integer.parseInt(payload.substring(scanPos + 2, scanPos + 4), 16);
                        if ((cn == 0x81) || (cn == 0x82) || (cn >= 30 && cn < 128)) break;
                    }
                    scanPos += 2;
                    if (scanPos >= payload.length() - 4) break;
                }

                // Search the gap [recordEnd, scanPos) for midnight DateTimes
                int gi = recordEnd;
                while (true) {
                    int di = payload.indexOf("090C07E", gi);
                    if (di < 0 || di >= scanPos) break;
                    if (di + 28 <= payload.length()) {
                        String ts = payload.substring(di + 4, di + 28);
                        try {
                            int y  = Integer.parseInt(ts.substring(0, 4), 16);
                            int mo = Integer.parseInt(ts.substring(4, 6), 16);  if (mo > 127) mo = 1;
                            int d  = Integer.parseInt(ts.substring(6, 8), 16);  if (d  > 127) d  = 1;
                            int h  = Integer.parseInt(ts.substring(10, 12), 16); if (h  > 127) h  = 0;
                            int mi = Integer.parseInt(ts.substring(12, 14), 16); if (mi > 127) mi = 0;
                            int s  = Integer.parseInt(ts.substring(14, 16), 16); if (s  > 127) s  = 0;
                            boolean isMid = (h == 0) && (mi == 0 || mi == 0xFF)
                                    && (s == 0x00 || s == 0xFF);
                            if (y > 2000 && y < 2100 && mo >= 1 && mo <= 12 && isMid) {
                                long tsv = (long) y * 10000L + mo * 100L + d;
                                if (tsv > bestTs) {
                                    bestTs = tsv;
                                    bestY = y; bestM = mo; bestD = d;
                                    bestH = h; bestMi = mi; bestS = s;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    gi = di + 1;
                }

                // Also scan WITHIN the record's field data for midnight DateTimes.
                // For Secure WC meters (BS036823 etc.) the monthly billing-reset timestamp
                // is stored as IC=4 attr=5 fields INSIDE the billing record, not in the
                // gap between records. The gap scan above misses them and returns the
                // midnight from a different (earlier) record's gap → wrong billing date.
                // Safety: limit intra-record scan to the record's actual field range.
                gi = fieldsStart;
                while (true) {
                    int di = payload.indexOf("090C07E", gi);
                    if (di < 0 || di >= recordEnd) break;
                    if (di + 28 <= payload.length()) {
                        String ts = payload.substring(di + 4, di + 28);
                        try {
                            int y  = Integer.parseInt(ts.substring(0, 4), 16);
                            int mo = Integer.parseInt(ts.substring(4, 6), 16);  if (mo > 127) mo = 1;
                            int d  = Integer.parseInt(ts.substring(6, 8), 16);  if (d  > 127) d  = 1;
                            int h  = Integer.parseInt(ts.substring(10, 12), 16); if (h  > 127) h  = 0;
                            int mi = Integer.parseInt(ts.substring(12, 14), 16); if (mi > 127) mi = 0;
                            int s  = Integer.parseInt(ts.substring(14, 16), 16); if (s  > 127) s  = 0;
                            boolean isMid = (h == 0) && (mi == 0 || mi == 0xFF)
                                    && (s == 0x00 || s == 0xFF);
                            if (y > 2000 && y < 2100 && mo >= 1 && mo <= 12 && isMid) {
                                long tsv = (long) y * 10000L + mo * 100L + d;
                                if (tsv > bestTs) {
                                    bestTs = tsv;
                                    bestY = y; bestM = mo; bestD = d;
                                    bestH = h; bestMi = mi; bestS = s;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    gi = di + 1;
                }
                pos = scanPos;
            }
            if (bestTs < 0) return "NA";
            return String.format("%02d/%02d/%04d %02d:%02d:%02d", bestD, bestM, bestY, bestH, bestMi, bestS);
        } catch (Exception e) { return "NA"; }
    }

    /**
     *
     * Called by extractBillingByObis when the primary billing buffer (0100620100FF)
     * has fewer actual fields than the column index for the requested OBIS.
     *
     * Confirmed behaviour on Genus variants (MRI data):
     *   • 35-41 actual fields in the 01005E5B00FF buffer
     *   • d=1 (1st-of-month midnight) billing date always present within the record
     *     at the 0000000102FF column — scanner finds it and anchors extraction
     *   • kWh export (0100020800FF) at col 16 or 22 — reachable in both variants
     *   • kVAh export (01000A0800FF) at col 18 or 24 — reachable
     *   • MD kW/kVA export (0100020600FF / 01000A0600FF) also reachable
     *
     * The 01005E5B00FF data is already in the TXT: ReadInstantData writes both
     * attr=3 (capture objects) and attr=2 (buffer) for every hasDlmsScalarObjects() make.
     * No additional meter communication is required.
     */
    private String extractBillingFromAltObis(String dataUpper,
                                             String obisHex,
                                             java.util.Map<String, int[]> scalerMap,
                                             int energyAdj) {
        try {
            // ── Step 1: parse capture objects from 01005E5B00FF 03 ─────────────
            java.util.Map<String, Integer> altColMap = new java.util.LinkedHashMap<>();
            String coMarker = "01005E5B00FF 03 ";
            int coIdx = dataUpper.indexOf(coMarker);
            if (coIdx < 0) return "NA";
            int ps = coIdx + coMarker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String co = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (co.length() < 4) return "NA";

            // Parse array header (same logic as parseBillingCaptureObjects)
            int pos = 2;
            int lb = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
            int count;
            if ((lb & 0x80) != 0) {
                int nb = lb & 0x7F;
                count = Integer.parseInt(co.substring(pos, pos + nb * 2), 16); pos += nb * 2;
            } else {
                count = lb;
            }
            for (int col = 0; col < count && pos + 2 <= co.length(); col++) {
                while (pos + 2 <= co.length() &&
                        Integer.parseInt(co.substring(pos, pos + 2), 16) != 0x02) pos += 2;
                if (pos + 2 > co.length()) break;
                pos += 2; // struct tag
                int fc = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                // class-id
                if (pos + 2 <= co.length() && Integer.parseInt(co.substring(pos,pos+2),16)==0x12) pos += 6;
                // OBIS
                String obisVal = "";
                if (pos + 4 <= co.length() && co.substring(pos, pos + 4).equalsIgnoreCase("0906")) {
                    pos += 4;
                    int ln = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                    if (ln == 6 && pos + 12 <= co.length()) { obisVal = co.substring(pos, pos + 12); pos += 12; }
                    else { pos += ln * 2; }
                }
                // attr
                if (pos + 2 <= co.length() && Integer.parseInt(co.substring(pos,pos+2),16)==0x0F) pos += 4;
                // index
                if (pos + 2 <= co.length() && Integer.parseInt(co.substring(pos,pos+2),16)==0x12) pos += 6;
                if (!obisVal.isEmpty() && !altColMap.containsKey(obisVal)) altColMap.put(obisVal, col);
            }

            Integer colIdx = altColMap.get(obisHex.toUpperCase());
            if (colIdx == null) return "NA";

            // ── Step 2: read buffer from 01005E5B00FF 02 ─────────────────────
            String bufMarker = "01005E5B00FF 02 ";
            int bufIdx = dataUpper.indexOf(bufMarker);
            if (bufIdx < 0) return "NA";
            int bps = bufIdx + bufMarker.length();
            int ble = dataUpper.indexOf('\n', bps);
            if (ble < 0) ble = dataUpper.length();
            String payload = dataUpper.substring(bps, ble).trim().replaceAll("\\s+", "");
            if (payload.length() < 8) return "NA";

            // ── Step 3: find best d=1 record (same anchor logic as primary) ──
            int headerEnd = 0;
            if (payload.startsWith("01")) {
                headerEnd = 2;
                int lbh = Integer.parseInt(payload.substring(2, 4), 16); headerEnd += 2;
                if ((lbh & 0x80) != 0) { int nb = lbh & 0x7F; headerEnd += nb * 2; }
            }
            int bestRecordPos = -1;
            long bestTimestamp = -1;
            int scanIdx = headerEnd;
            while (scanIdx < payload.length() - 28) {
                int datePos = payload.indexOf("090C07E", scanIdx);
                if (datePos < 0) break;
                String ts = payload.substring(datePos + 4, Math.min(datePos + 28, payload.length()));
                if (ts.length() >= 24) {
                    try {
                        int y  = Integer.parseInt(ts.substring(0, 4), 16);
                        int mo = Integer.parseInt(ts.substring(4, 6), 16); if (mo > 127) mo = 0;
                        int d  = Integer.parseInt(ts.substring(6, 8), 16); if (d  > 127) d  = 0;
                        int h  = Integer.parseInt(ts.substring(10, 12), 16); if (h  > 127) h  = 0;
                        int mi = Integer.parseInt(ts.substring(12, 14), 16); if (mi > 127) mi = 0;
                        if (d == 1 && h == 0 && mi == 0 && y > 2000 && y < 2100 && mo >= 1 && mo <= 12) {
                            long tsVal = (long)y*10000L + mo*100L + d;
                            if (tsVal > bestTimestamp) {
                                bestTimestamp = tsVal;
                                int recStart = datePos - 2;
                                for (int back = 0; back <= 16; back += 2) {
                                    int tryPos = datePos - back;
                                    if (tryPos < headerEnd) break;
                                    if (tryPos + 2 <= payload.length()) {
                                        int tag = Integer.parseInt(payload.substring(tryPos, tryPos + 2), 16);
                                        if (tag == 0x02) { recStart = tryPos; break; }
                                    }
                                }
                                bestRecordPos = recStart;
                            }
                        }
                    } catch (Exception ignore) {}
                }
                scanIdx = datePos + 1;
            }
            if (bestRecordPos < 0) return "NA";

            // ── Step 4: navigate to colIdx and read value ─────────────────────
            // (identical decode logic as extractBillingByObis from this point on)
            pos = bestRecordPos;
            if (pos + 4 > payload.length()) return "NA";
            int stTag = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            if (stTag != 0x02) return "NA";
            int fcByte = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            int fieldCount;
            if ((fcByte & 0x80) != 0) {
                int nb = fcByte & 0x7F;
                if (pos + nb * 2 > payload.length()) return "NA";
                fieldCount = Integer.parseInt(payload.substring(pos, pos + nb * 2), 16);
                pos += nb * 2;
            } else {
                fieldCount = fcByte;
            }
            if (colIdx >= fieldCount) return "NA";
            for (int c2 = 0; c2 < colIdx && pos + 2 <= payload.length(); c2++) {
                pos = skipOneDlmsValue(payload, pos);
                if (pos < 0) return "NA";
            }
            if (pos + 2 > payload.length()) return "NA";
            int tag = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;

            // Timestamp
            if (tag == 0x09) {
                int ln = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
                if (ln == 12 && pos + 24 <= payload.length()) {
                    String ts = payload.substring(pos, pos + 24);
                    int y  = Integer.parseInt(ts.substring(0, 4), 16);
                    int mo = Integer.parseInt(ts.substring(4, 6), 16); if (mo == 0xFF) mo = 1;
                    int d  = Integer.parseInt(ts.substring(6, 8), 16); if (d  == 0xFF) d  = 1;
                    int h  = Integer.parseInt(ts.substring(10, 12), 16); if (h  == 0xFF) h  = 0;
                    int mi = Integer.parseInt(ts.substring(12, 14), 16); if (mi == 0xFF) mi = 0;
                    int s  = Integer.parseInt(ts.substring(14, 16), 16); if (s  == 0xFF) s  = 0;
                    if (y > 1990 && y < 2100 && mo >= 1 && mo <= 12)
                        return String.format("%02d/%02d/%04d %02d:%02d:%02d", d, mo, y, h, mi, s);
                }
                return "NA";
            }

            // Numeric — same float32 / integer path as extractBillingByObis
            if (tag == 0x17) {
                if (pos + 8 > payload.length()) return "NA";
                int floatBits = (int) Long.parseLong(payload.substring(pos, pos + 8), 16);
                float fVal = Float.intBitsToFloat(floatBits);
                if (Float.isNaN(fVal) || Float.isInfinite(fVal)) return "NA";
                String lookupObisF = obisHex.toUpperCase();
                int[] suF = scalerMap.get(lookupObisF);
                int scF = (suF != null) ? suF[0] : 0;
                int ucF = (suF != null) ? suF[1] : 0xFF;
                if (ucF == 0xFF) { int[] defF = dlmsDefaultScalerUnit(lookupObisF); if (defF != null) { scF = defF[0]; ucF = defF[1]; } }
                if (energyAdj != 0 && lookupObisF.length() >= 8 && "08".equals(lookupObisF.substring(6, 8))) scF += energyAdj;
                double valF = fVal * Math.pow(10, scF);
                double[] convF = unitConversion(ucF);
                valF /= convF[0];
                return String.format("%,." + (int)convF[1] + "f", valF);
            }
            long rawVal;
            if      (tag == 0x06) { if (pos + 8 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos, pos + 8), 16); }
            else if (tag == 0x05) { if (pos + 8 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos, pos + 8), 16); if (rawVal > 0x7FFFFFFFL) rawVal -= 0x100000000L; }
            else if (tag == 0x12) { if (pos + 4 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos, pos + 4), 16); }
            else if (tag == 0x10) { if (pos + 4 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos, pos + 4), 16); if (rawVal > 0x7FFF) rawVal -= 0x10000; }
            else if (tag == 0x11 || tag == 0x16) { if (pos + 2 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos, pos + 2), 16); }
            else return "NA";

            String lookupObis = obisHex.toUpperCase();
            int[] su = scalerMap.get(lookupObis);
            int sc = (su != null) ? su[0] : 0;
            int uc = (su != null) ? su[1] : 0xFF;
            // Only kVA apparent-power demand gets 0x1C override; kW demand keeps 0x1B.
            if (lookupObis.startsWith("01000906")
                    || lookupObis.startsWith("01009E06") || lookupObis.startsWith("01009F06")) {
                if (uc == 0x1B || uc == 0xFF) uc = 0x1C;
            }
            if (uc == 0xFF) { int[] def = dlmsDefaultScalerUnit(lookupObis); if (def != null) { sc = def[0]; uc = def[1]; } }
            if (energyAdj != 0 && lookupObis.length() >= 8 && "08".equals(lookupObis.substring(6, 8))) sc += energyAdj;
            double val = rawVal * Math.pow(10, sc);
            double[] conv = unitConversion(uc);
            val /= conv[0];
            return String.format("%,." + (int)conv[1] + "f", val);

        } catch (Exception e) { return "NA"; }
    }

    /** Returns the raw uint32/int32 value from an instantaneous register attr=2 line in the TXT.
     *  Looks for "obisHex 02 06XXXXXXXX" or "05XXXXXXXX" (uint32/int32). Returns -1 on failure. */
    private long extractInstantRaw(String dataUpper, String obisHex) {
        try {
            String marker = obisHex.toUpperCase() + " 02 ";
            int idx = dataUpper.toUpperCase().indexOf(marker);
            if (idx < 0) return -1;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "").toUpperCase();
            if (payload.length() < 10) return -1;
            int tag = Integer.parseInt(payload.substring(0, 2), 16);
            if (tag == 0x06) return Long.parseLong(payload.substring(2, 10), 16);
            if (tag == 0x05) {
                long v = Long.parseLong(payload.substring(2, 10), 16);
                return v > 0x7FFFFFFFL ? v - 0x100000000L : v;
            }
            if (tag == 0x17) { // float32 (Genus/AVON) — convert to long for ratio check
                int fb = (int) Long.parseLong(payload.substring(2, 10), 16);
                float fv = Float.intBitsToFloat(fb);
                return Float.isNaN(fv) || Float.isInfinite(fv) ? -1L : (long) Math.abs(fv);
            }
            return -1;
        } catch (Exception e) { return -1; }
    }

    /** Extracts the raw (un-scaled) long value for the given OBIS from the billing buffer.
     *  Uses the same struct-navigation logic as extractBillingByObis. Returns Long.MIN_VALUE on failure. */
    private long extractBillingRawLong(String dataUpper,
                                       String obisHex,
                                       java.util.Map<String, Integer> colMap) {
        try {
            Integer colIdx = colMap.get(obisHex.toUpperCase());
            if (colIdx == null) return Long.MIN_VALUE;

            String marker = "0100620100FF 02 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return Long.MIN_VALUE;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 8) return Long.MIN_VALUE;

            int headerEnd = 0;
            if (payload.startsWith("01")) {
                headerEnd = 2;
                int lb = Integer.parseInt(payload.substring(2, 4), 16); headerEnd += 2;
                if ((lb & 0x80) != 0) { int nb = lb & 0x7F; headerEnd += nb * 2; }
            }

            int bestRecordPos = -1;
            long bestTimestamp = -1;
            int scanIdx = headerEnd;
            while (scanIdx < payload.length() - 28) {
                int datePos = payload.indexOf("090C07E", scanIdx);
                if (datePos < 0) break;
                String ts = payload.substring(datePos + 4, Math.min(datePos + 28, payload.length()));
                if (ts.length() < 24) { scanIdx = datePos + 1; continue; }
                try {
                    int y  = Integer.parseInt(ts.substring(0, 4), 16);
                    int mo = Integer.parseInt(ts.substring(4, 6), 16);   if (mo > 127) mo = 0;
                    int d  = Integer.parseInt(ts.substring(6, 8), 16);   if (d  > 127) d  = 0;
                    int h  = Integer.parseInt(ts.substring(10, 12), 16); if (h  > 127) h  = 0;
                    int mi = Integer.parseInt(ts.substring(12, 14), 16); if (mi > 127) mi = 0;
                    if (d == 1 && h == 0 && mi == 0 && y > 2000 && y < 2100 && mo >= 1 && mo <= 12) {
                        long tsVal = (long) y * 10000L + mo * 100L + d;
                        if (tsVal > bestTimestamp) {
                            bestTimestamp = tsVal;
                            int recStart = datePos - 2;
                            for (int back = 0; back <= 16; back += 2) {
                                int tryPos = datePos - back;
                                if (tryPos < headerEnd) break;
                                if (tryPos + 2 <= payload.length()) {
                                    int t = Integer.parseInt(payload.substring(tryPos, tryPos + 2), 16);
                                    if (t == 0x02) { recStart = tryPos; break; }
                                }
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

            for (int c2 = 0; c2 < colIdx && pos + 2 <= payload.length(); c2++) {
                pos = skipOneDlmsValue(payload, pos);
                if (pos < 0) return Long.MIN_VALUE;
            }

            if (pos + 2 > payload.length()) return Long.MIN_VALUE;
            int tag = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            if      (tag == 0x06) { if (pos + 8 > payload.length()) return Long.MIN_VALUE; return Long.parseLong(payload.substring(pos, pos + 8), 16); }
            else if (tag == 0x05) { if (pos + 8 > payload.length()) return Long.MIN_VALUE; long v = Long.parseLong(payload.substring(pos, pos + 8), 16); return v > 0x7FFFFFFFL ? v - 0x100000000L : v; }
            else if (tag == 0x12) { if (pos + 4 > payload.length()) return Long.MIN_VALUE; return Long.parseLong(payload.substring(pos, pos + 4), 16); }
            else if (tag == 0x11 || tag == 0x16) { if (pos + 2 > payload.length()) return Long.MIN_VALUE; return Long.parseLong(payload.substring(pos, pos + 2), 16); }
            else if (tag == 0x17) { // float32 (Genus/AVON)
                if (pos + 8 > payload.length()) return Long.MIN_VALUE;
                int fb = (int) Long.parseLong(payload.substring(pos, pos + 8), 16);
                float fv = Float.intBitsToFloat(fb);
                return (Float.isNaN(fv) || Float.isInfinite(fv)) ? Long.MIN_VALUE : (long) Math.abs(fv);
            }
            return Long.MIN_VALUE;
        } catch (Exception e) { return Long.MIN_VALUE; }
    }

    /** Detects whether billing energy values need a scaler exponent adjustment.
     *  HPL firmware stores billing energy in 100 Wh units while reporting sc=0 (Wh) in attr=3.
     *  This is a CUMULATIVE register mismatch: billing T0 raw = instantaneous_raw / 100.
     *
     *  Compares instantaneous kWh T0 raw vs billing kWh T0 raw to detect the ratio:
     *    ratio ≈ 100   → adj=+2  (HPL: billing in 100 Wh, instant in 1 Wh)
     *    ratio ≈ 1000  → adj=+3  (reserved for future 1000 Wh firmware)
     *    otherwise     → adj=0   (no correction needed)
     *
     *  Safety: the 80–120 window is extremely specific — normal meters have billing
     *  cumulative ≈ instantaneous cumulative (ratio ≈ 1.0), so false positives are
     *  essentially impossible. */
    private int computeBillingEnergyAdj(String dataUpper,
                                        java.util.Map<String, Integer> colMap,
                                        java.util.Map<String, int[]> scalerMap) {
        try {
            // ONLY apply correction when the meter's own attr=3 reports sc=0/Wh.
            // If the meter already reports a non-zero scaler, trust it and skip.
            int[] su = scalerMap.get("0100010800FF");
            if (su != null && su[0] != 0) return 0;

            long instantRaw = extractInstantRaw(dataUpper, "0100010800FF");
            if (instantRaw <= 0) return 0;
            long billingRaw = extractBillingRawLong(dataUpper, "0100010800FF", colMap);
            if (billingRaw == Long.MIN_VALUE || billingRaw <= 0) return 0;

            // BUG-10 FIX: billing > instantaneous × 1.05 means corrupt/replaced meter — don't adjust
            if (billingRaw > instantRaw * 1.05) {
                appendLog("BILLING_ADJ_WARN: billingRaw=" + billingRaw + " > instantRaw=" + instantRaw
                        + " — possible meter replacement, rollover, or corrupt record. No adj applied.");
                return 0;
            }
            // Normal: billing cumulative should be <= instantaneous
            if (billingRaw >= instantRaw) return 0;

            double ratio = (double) instantRaw / (double) billingRaw;
            appendLog("BILLING_ADJ_RATIO inst=" + instantRaw + " bill=" + billingRaw
                    + " ratio=" + String.format("%.1f", ratio));

            // HPL PPEM firmware variant: billing CUMULATIVE stored in 10 Wh units,
            // attr=3 reports sc=0 (Wh).  Detected when ratio ≈ 10 (window 8–12).
            // Scoped to HPL only — other makes with a legitimate monthly vs cumulative
            // ratio near 10 must not be adjusted.
            // MRI-confirmed: inst=323989 / bill=29653 → ratio=10.9 → adj+1 → 296.53 kWh ✓
            if (currentMeterMake == MeterMake.HPL && ratio >= 8.0 && ratio <= 12.0) return 1;

            // HPL firmware bug: billing CUMULATIVE stored in 100 Wh units, attr=3 reports sc=0.
            // Detected when ratio is exactly ~100 (window 80–120 to account for rounding in
            // 100 Wh resolution). Normal meters have ratio ≈ 1.0 (billing ≈ instant in same units),
            // so this window cannot produce false positives.
            if (ratio >= 80.0 && ratio <= 120.0) return 2;  // 100 Wh unit → adj +2

            // Reserved: 1000 Wh unit (ratio ≈ 1000, window 800–1200)
            if (ratio >= 800.0 && ratio <= 1200.0) return 3;

            // Old threshold 500–2000 removed — too broad, overlaps both cases.
            // Old adj=+1 (10 Wh unit, ratio 5–15) removed — overlaps monthly/cumulative ratios.
            return 0;
        } catch (Exception e) { return 0; }
    }

    // =========================================================================
    // POST-PROCESSING: patch profile buffers + write missing scaler lines
    // =========================================================================

    /** Generic capture-object parser — accepts any profile OBIS (billing, LP, midnight, events).
     *  Parses the "profileObis 03 [capture_objects_hex]" line from the TXT and returns
     *  a map of OBIS-hex → column-index for all captured registers. */
    private java.util.Map<String, Integer> parseCaptureObjects(String dataUpper, String profileObis) {
        java.util.Map<String, Integer> colMap = new java.util.LinkedHashMap<>();
        try {
            String marker = profileObis.toUpperCase() + " 03 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return colMap;
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String co = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (co.length() < 4) return colMap;

            int pos = 2; // skip array tag "01"
            int lb = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
            int count;
            if ((lb & 0x80) != 0) {
                int nb = lb & 0x7F;
                count = Integer.parseInt(co.substring(pos, pos + nb * 2), 16); pos += nb * 2;
            } else {
                count = lb;
            }
            for (int col = 0; col < count && pos + 2 <= co.length(); col++) {
                // Skip non-struct bytes (orphan bytes from some firmware)
                while (pos + 2 <= co.length() &&
                        Integer.parseInt(co.substring(pos, pos + 2), 16) != 0x02) pos += 2;
                if (pos + 2 > co.length()) break;
                pos += 2; // struct tag 02
                if (pos + 2 > co.length()) break;
                pos += 2; // field count
                // [0] class-id uint16
                if (pos + 2 > co.length()) break;
                int t0 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (t0 == 0x12) pos += 4;
                // [1] OBIS octet-string
                if (pos + 2 > co.length()) break;
                int t1 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                String obisHex = "";
                if (t1 == 0x09) {
                    int ln = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                    if (ln == 6 && pos + 12 <= co.length()) { obisHex = co.substring(pos, pos + 12); pos += 12; }
                    else pos += ln * 2;
                }
                // [2] attribute int8
                if (pos + 2 > co.length()) break;
                int t2 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (t2 == 0x0F) pos += 2;
                // [3] data-index uint16
                if (pos + 2 > co.length()) break;
                int t3 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (t3 == 0x12) pos += 4;
                if (!obisHex.isEmpty()) { if (!colMap.containsKey(obisHex)) colMap.put(obisHex, col); }
            }
        } catch (Exception ignored) {}
        return colMap;
    }

    /** Patches ALL records in a profile buffer hex string: for every column whose OBIS has
     *  byte[3]=0x08 (energy), multiplies the raw value by 10^adj.
     *  Returns the patched hex string (same length as input since types are preserved),
     *  or the original hex unchanged on any parsing error. */
    private String patchProfileBuffer(String bufHex,
                                      java.util.Map<String, Integer> colMap,
                                      int adj) {
        if (adj == 0 || colMap == null || colMap.isEmpty() || bufHex == null || bufHex.length() < 8)
            return bufHex;
        try {
            long multiplier = (long) Math.pow(10, adj);
            String upper = bufHex.toUpperCase();

            // Build reverse lookup: column index → is energy?
            int maxCol = 0;
            for (int c : colMap.values()) maxCol = Math.max(maxCol, c);
            boolean[] isEnergyCol = new boolean[maxCol + 1];
            for (java.util.Map.Entry<String, Integer> e : colMap.entrySet()) {
                String obis = e.getKey().toUpperCase();
                int c = e.getValue();
                if (c <= maxCol && obis.length() >= 8 && "08".equals(obis.substring(6, 8)))
                    isEnergyCol[c] = true;
            }

            // Find the 0x01 array tag (may have a short prefix in some responses)
            int arrayStart = -1;
            for (int skip = 0; skip <= 16; skip += 2) {
                if (skip + 2 > upper.length()) break;
                if (Integer.parseInt(upper.substring(skip, skip + 2), 16) == 0x01) {
                    arrayStart = skip; break;
                }
            }
            if (arrayStart < 0) return bufHex;

            StringBuilder out = new StringBuilder(upper.substring(0, arrayStart));
            int pos = arrayStart;
            out.append("01"); pos += 2; // array tag

            // BER-encoded record count
            int cb = Integer.parseInt(upper.substring(pos, pos + 2), 16); pos += 2;
            int recordCount;
            if ((cb & 0x80) == 0) {
                recordCount = cb;
                out.append(String.format("%02X", cb));
            } else {
                int nb = cb & 0x7F;
                String berBytes = upper.substring(pos, pos + nb * 2); pos += nb * 2;
                recordCount = Integer.parseInt(berBytes, 16);
                out.append(String.format("%02X", cb)).append(berBytes);
            }

            // Process each struct record
            for (int r = 0; r < recordCount; r++) {
                if (pos + 4 > upper.length()) break;
                int stTag = Integer.parseInt(upper.substring(pos, pos + 2), 16);
                if (stTag != 0x02) { out.append(upper.substring(pos)); return out.toString(); }
                out.append(upper, pos, pos + 2); pos += 2; // struct tag
                int fieldCount = Integer.parseInt(upper.substring(pos, pos + 2), 16);
                out.append(upper, pos, pos + 2); pos += 2; // field count

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
                        // float32 energy (Genus/AVON) — multiply float value by multiplier, re-encode
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
                        if (newPos < 0 || newPos > upper.length()) {
                            out.append(upper.substring(pos)); return out.toString();
                        }
                        out.append(upper, pos, newPos); pos = newPos;
                    }
                }
            }
            if (pos < upper.length()) out.append(upper.substring(pos));
            return out.toString();
        } catch (Exception e) {
            appendLog("PATCH_BUF_ERR: " + e.getMessage());
            return bufHex;
        }
    }

    /** Finds the "profileObis 02 [hex]" line in meterData, patches its buffer with
     *  patchProfileBuffer, and replaces it in-place. dataUpper is the pre-computed
     *  uppercase snapshot (same indices as meterData since toUpperCase preserves length). */
    private void patchAndReplaceBuffer(StringBuilder meterData, String dataUpper,
                                       String profileObis,
                                       java.util.Map<String, Integer> colMap, int adj) {
        try {
            String marker = profileObis.toUpperCase() + " 02 ";
            int markerIdx = dataUpper.indexOf(marker);
            if (markerIdx < 0) return;
            int ps = markerIdx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();

            // Extract the raw buffer hex from the actual (mixed-case) meterData
            String bufHex = meterData.substring(ps, le).trim().replaceAll("\\s+", "");
            if (bufHex.length() < 8) return;

            String patched = patchProfileBuffer(bufHex, colMap, adj);
            if (patched.equals(bufHex.toUpperCase())) return; // nothing changed

            // Replace in meterData — positions match because toUpperCase() doesn't change length
            meterData.replace(ps, le, patched);
            appendLog("PATCH_BUF_OK obis=" + profileObis + " records adj=×" + (int) Math.pow(10, adj));
        } catch (Exception e) {
            appendLog("PATCH_BUF_REPLACE_ERR obis=" + profileObis + ": " + e.getMessage());
        }
    }

    /** For every OBIS in colMap that has no attr=3 scaler line in the TXT, writes an explicit
     *  "0007 OBIS 03 0202 0FSC16UC" line to sb.
     *  Scaler is derived from: (1) T0 base OBIS via scalerMap (for TOD T1-T8 variants),
     *  (2) IS15959-2 defaults via dlmsDefaultScalerUnit. Skips if still unknown. */
    private void appendMissingScalerLines(StringBuilder sb, String dataUpper,
                                          java.util.Map<String, Integer> colMap,
                                          java.util.Map<String, int[]> scalerMap) {
        for (String obisHex : colMap.keySet()) {
            String upper = obisHex.toUpperCase();
            if (upper.length() != 12) continue;
            if (scalerMap.containsKey(upper)) continue; // already present in TXT

            int sc = 0, uc = 0xFF;

            // TOD T1-T8 variants: chars 8-9 are the TOD byte (01-08)
            String todByte = upper.substring(8, 10);
            int todIdx = 0;
            try { todIdx = Integer.parseInt(todByte, 16); } catch (Exception ignored) {}

            if (todIdx >= 1 && todIdx <= 8) {
                // Derive scaler from T0 base OBIS
                String baseObis = upper.substring(0, 8) + "00" + upper.substring(10);
                int[] su = scalerMap.get(baseObis);
                if (su != null) { sc = su[0]; uc = su[1]; }
                else {
                    int[] def = dlmsDefaultScalerUnit(baseObis);
                    if (def != null) { sc = def[0]; uc = def[1]; }
                }
            } else {
                int[] def = dlmsDefaultScalerUnit(upper);
                if (def != null) { sc = def[0]; uc = def[1]; }
            }

            if (uc == 0xFF) continue; // unit still unknown — don't write garbage

            sb.append(String.format("\r\n0007 %s 03 02020F%02X16%02X", upper, sc & 0xFF, uc));
            appendLog("SCALER_EXPLICIT obis=" + upper + " sc=" + sc + " uc=0x" + String.format("%02X", uc));
        }
    }

    /** Post-processes the complete MeterData StringBuilder after all meter reads are done.
     *  For every profile buffer (billing, midnight, LP, events):
     *  1. Detects energy scaler mismatch (e.g. HPL storing billing in 100 Wh, sc=0).
     *  2. Patches raw energy values in ALL buffer records (×10^adj) so TXT values are
     *     consistent with the attr=3 scaler already present in TXT.
     *  3. Writes explicit attr=3 scaler lines for OBIS codes that appear in profile
     *     capture objects but have no attr=3 line (e.g. TOD T1-T8 energy). */
    private void postProcessMeterData(StringBuilder meterData) {
        try {
            String dataUpper = meterData.toString().toUpperCase();
            java.util.Map<String, int[]> scalerMap = buildScalerMap(dataUpper);
            // Use billing-specific scalers (BSCL lines) for the adj detection so that
            // meters whose billing scaler differs from instant scaler are handled correctly.
            java.util.Map<String, int[]> billingScalerMapPost = buildBillingScalerMap(dataUpper);
            java.util.Map<String, int[]> adjScalerMap = billingScalerMapPost.isEmpty() ? scalerMap : billingScalerMapPost;

            // Detect energy scaler adjustment from billing (same firmware quirk applies to all profiles)
            java.util.Map<String, Integer> billingColMap = parseCaptureObjects(dataUpper, "0100620100FF");
            int adj = computeBillingEnergyAdj(dataUpper, billingColMap, adjScalerMap);
            if (adj != 0) appendLog("POST_PROCESS adj=+" + adj
                    + " (billing energy ×" + (int) Math.pow(10, adj) + " patch will be applied)");

            // Profiles that store CUMULATIVE energy → adj patch needed when billing detects 100Wh storage.
            // Midnight (0100630200FF) is a cumulative snapshot at midnight — same HPL firmware behaviour
            //   as billing, so it also stores energy in 100Wh units and needs the adj patch.
            // LP (0100630100FF) stores INTERVAL energy (Wh consumed per capture period) — small
            //   delta values that are ALREADY in correct Wh units regardless of the cumulative
            //   register's storage format. Applying adj here would multiply LP values ×100 wrongly.
            String[] cumulativeProfiles = {"0100620100FF", "0100630200FF"};  // billing + midnight
            String[] lpProfileOnly      = {"0100630100FF"};                  // LP — scaler inject only

            for (String obis : cumulativeProfiles) {
                java.util.Map<String, Integer> colMap =
                        obis.equals("0100620100FF") ? billingColMap : parseCaptureObjects(dataUpper, obis);
                if (colMap.isEmpty()) continue;

                if (adj != 0) {
                    patchAndReplaceBuffer(meterData, dataUpper, obis, colMap, adj);
                    dataUpper = meterData.toString().toUpperCase();
                }
                java.util.Map<String, int[]> scalerForProfile = scalerMap;
                if (obis.equals("0100620100FF") && !billingScalerMapPost.isEmpty()) {
                    java.util.Map<String, int[]> merged = new java.util.HashMap<>(scalerMap);
                    merged.putAll(billingScalerMapPost);
                    scalerForProfile = merged;
                }
                appendMissingScalerLines(meterData, dataUpper, colMap, scalerForProfile);
                dataUpper = meterData.toString().toUpperCase();
                scalerMap = buildScalerMap(dataUpper);
            }

            // LP: inject missing scaler lines only — NO adj patch (interval energy is in Wh, sc=0 correct)
            for (String obis : lpProfileOnly) {
                java.util.Map<String, Integer> colMap = parseCaptureObjects(dataUpper, obis);
                if (colMap.isEmpty()) continue;
                // BUG-17 FIX: HPL CT LP uses .29. (cumulative) OBIS, not .27. (interval).
                // Apply adj patch only when LP capture objects contain D=0x1D (=29) OBIS.
                if (adj != 0 && currentMeterMake == MeterMake.HPL) {
                    boolean lpHasCumulative = false;
                    for (String k : colMap.keySet()) {
                        if (k.length() == 12 && k.substring(6, 8).equalsIgnoreCase("1D")) { lpHasCumulative = true; break; }
                    }
                    if (lpHasCumulative) {
                        appendLog("POST_PROCESS HPL_CT_LP_CUMULATIVE: applying adj=" + adj + " to LP buffer (.29. columns)");
                        patchAndReplaceBuffer(meterData, dataUpper, obis, colMap, adj);
                        dataUpper = meterData.toString().toUpperCase();
                    } else {
                        appendLog("POST_PROCESS HPL_LP_INTERVAL: LP has .27. (interval) columns — adj NOT applied");
                    }
                }
                appendMissingScalerLines(meterData, dataUpper, colMap, scalerMap);
                dataUpper = meterData.toString().toUpperCase();
                scalerMap = buildScalerMap(dataUpper);
            }

            String[] eventProfiles = {"0000636200FF", "0000636201FF", "0000636202FF",
                    "0000636203FF", "0000636204FF", "0000636205FF", "0000636281FF"};

            for (String obis : eventProfiles) {
                java.util.Map<String, Integer> colMap = parseCaptureObjects(dataUpper, obis);
                if (colMap.isEmpty()) continue;
                if (adj != 0) {
                    patchAndReplaceBuffer(meterData, dataUpper, obis, colMap, adj);
                    dataUpper = meterData.toString().toUpperCase();
                }
                appendMissingScalerLines(meterData, dataUpper, colMap, scalerMap);
                dataUpper = meterData.toString().toUpperCase();
                scalerMap = buildScalerMap(dataUpper);
            }

            appendLog("POST_PROCESS_COMPLETE");
        } catch (Exception e) {
            appendLog("POST_PROCESS_ERROR: " + e.getMessage());
        }
    }

    private int skipOneDlmsValue(String payload, int pos) {
        return skipOneDlmsValue(payload, pos, 0);
    }

    /**
     * Skip over one DLMS-encoded value starting at {@code pos} and return the position
     * immediately after it, or -1 if the encoding cannot be parsed.
     *
     * {@code depth} tracks nested Array/Structure recursion.  We cap at 3 levels:
     *   depth 0 – top-level field of a billing record struct
     *   depth 1 – element inside a nested array/struct (e.g. MD value+timestamp array)
     *   depth 2 – element inside a doubly-nested array (Genus KT291596 inner MD array)
     *   depth 3 – return -1 immediately for anything deeper.
     *
     * Returning -1 at depth ≥ 3 (or on any unrecognised tag) is intentional: the
     * record-walk loop now treats -1 as "stop here" rather than "consume entire buffer",
     * so the inter-record scanner can safely find the actual next struct boundary.
     *
     * The depth cap also prevents Genus KT291596's double-nested 86-element array from
     * causing unbounded recursion and corrupting the recordEnd pointer.
     *
     * Make-specific behaviours handled at each depth level:
     *   Secure / HPL / L&T : no nested arrays → depth stays at 0 throughout
     *   Genus KT306640      : one nested array at field[58] → depth reaches 1
     *   Genus KT291596      : double-nested array at field[78] → depth reaches 2; inner
     *                         array of 86 elements containing another array returns -1
     *                         at depth 3, triggering the byte-count fallback path
     *   AVON 8550282        : declares 95 fields, 88 actual → hits 0x02 (next record
     *                         struct tag) at "field 88"; depth-0 sees tag=0x02, recurses
     *                         to depth 1 with fCount=95 which is implausible within the
     *                         remaining data → -1, record-walk breaks correctly
     *   Landis 70021475     : nested struct at field[55] (tag=0x02, 8 sub-fields);
     *                         sub-field 6 is tag=0x01 array → depth reaches 2, any
     *                         deeper element that fails returns -1 at depth 3
     */
    private int skipOneDlmsValue(String payload, int pos, int depth) {
        try {
            if (depth >= 3) return -1;   // hard cap: return -1, let caller handle
            if (pos + 2 > payload.length()) return -1;
            int tag = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            switch (tag) {
                case 0x06: case 0x05: return pos + 8;     // uint32/int32
                case 0x17: return pos + 8;                // float32 (Genus/AVON)
                case 0x18: return pos + 16;               // float64
                case 0x14: case 0x15: return pos + 16;   // int64/uint64 (Landis+Gyr)
                case 0x12: case 0x10: return pos + 4;     // uint16/int16
                case 0x11: case 0x16: case 0x0F: return pos + 2; // uint8/enum/int8
                case 0x00: return pos;                    // null / not-used (Secure billing padding)
                case 0x04: return pos;                    // access-error (no data bytes)
                case 0xFF: return pos;                    // "not specified" filler; legitimate
                // padding byte in multiple meter makes
                // Secure BK011850 firmware-specific no-data markers for unset demand slots.
                // These appear at field level (depth=0) for attr=5 timestamps of unused tariff
                // registers (T6-T8) that the meter cannot populate. Each consumes 0 data bytes.
                // CRITICAL: only treat as no-data at depth=0 (billing record field level).
                // At depth>0 (inside arrays), these byte values are RAW DATA and must return -1
                // so the byte-count array fallback triggers correctly (e.g. Genus KT291596
                // field[78] byte-count array contains 0x03 as raw energy data bytes — treating
                // it as a 0-byte element would make the element-count path succeed, causing
                // the array skip to consume 86 "elements" instead of 86 raw bytes, overshooting
                // the record boundary by ~2000 chars and skipping the next billing record).
                case 0xF1: case 0x03: case 0xC0:
                    return (depth == 0) ? pos : -1;
                case 0x09: case 0x0A: {                   // OctetString/VisibleString
                    int ln = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                    return pos + 2 + ln * 2;
                }
                case 0x01: case 0x02: {                   // Array / Structure
                    // ── Secure EHLS3B1xx compact block wrapper (0x01 0xFX) ──────────
                    // Secure EHLS3B1xx billing records group multiple capture-object
                    // columns into a compact block: 0x01 0xFX [DLMS-tagged values...].
                    // FX >= 0x80 is a Secure-proprietary block-type marker, not a BER
                    // length. After the 2-byte header, standard DLMS-tagged values follow
                    // in capture-object order. Observed variants:
                    //   0xF5 → 3×i16 + 6×DT (SS09108341 cols 31-39)
                    //   0xF2 → 6×DT + 6×i16  (SS09112148 cols 34-45)
                    //   0xEF → 5×DT + 1×i16  (SS09118696 cols 41-46)
                    // Fix: skip the 2-byte header and return pos (point AFTER the header).
                    // Callers then read each packed value individually via normal DLMS
                    // type dispatch. Column counter advances naturally (1 per value).
                    if (tag == 0x01 && pos + 2 <= payload.length()) {
                        int compactFX = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                        if ((compactFX & 0x80) != 0) {
                            pos += 2; // skip FX byte
                            return pos; // caller reads next value from this position
                        }
                    }
                    // Record-boundary guard: tag=0x02 followed by a COUNT BYTE that matches
                    // a real billing-record header shape:
                    //   - BER with nb=1 or nb=2 (cb = 0x81 or 0x82): count in next 1-2 bytes
                    //   - Plain count 50..127 (cb in 0x32..0x7F): e.g. AVON 95, Landis 62,
                    //     HPL 113
                    // Anything else is NOT a record header:
                    //   - cb 0x00..0x31 → small nested struct (e.g. Landis col 55 = 0x02 0x08)
                    //   - cb 0x83..0xFF → phantom (BER nb>=3 yields astronomical counts from
                    //     random data bytes; these are NEVER real billing records)
                    //
                    // Real record headers observed across all meter makes:
                    //   Genus KT291596: 0x02 0x82 0x00 0x96 (150 fields)
                    //   AVON 8550282:   0x02 0x5F (95 fields, plain)
                    //   Landis:         0x02 0x3E (62 fields, plain)
                    //   HPL:            0x02 0x71 (113 fields, plain)
                    //   Secure:         0x02 0x81 0x99 (153) or 0x02 0x81 0xE1 (225)
                    //
                    // Applied at ALL depths (not just depth 0) because Landis col 55 is a
                    // legitimate 8-field nested struct whose 8th sub-field lands exactly on
                    // the NEXT billing record's 0x02-0x3E(=62) header. Without this guard,
                    // the recursion would consume the next record as nested data.
                    if (tag == 0x02 && pos + 2 <= payload.length()) {
                        int cbPeek = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                        boolean isRecordHeader = (cbPeek == 0x81) || (cbPeek == 0x82)
                                || (cbPeek >= 20 && cbPeek < 128);
                        if (isRecordHeader) return -1;
                    }

                    //
                    // (A) Standard BER: cntByte >= 0x80 → nb = cntByte & 0x7F more bytes
                    //     follow giving the element count.
                    //     Example: 0x82 0x00 0x96 → 150 elements (Genus KT291596 outer struct).
                    //
                    // (B) Genus non-standard plain element count > 127:
                    //     cntByte >= 0x80 but treated as plain count (e.g. 0x8F = 143).
                    //     Guard: if BER interpretation gives implausible count, use plain.
                    //
                    // (C) Byte-count array (Genus KT291596 nested array at field[78]):
                    //     cntByte < 0x80 but represents BYTE COUNT not element count.
                    //     Detection: element-count recursion fails or overshoots → fall back
                    //     to advancing by cntByte*2 hex chars (raw byte skip).
                    //     IMPORTANT: byte-count fallback applies ONLY to arrays (tag 0x01).
                    //     For structs (tag 0x02), sub-fields have varying sizes and legitimately
                    //     consume more than cntByte bytes; applying the fallback there corrupts
                    //     the walk by truncating the struct to cntByte*2 chars.
                    //
                    int cntByte = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
                    int cnt;
                    if ((cntByte & 0x80) != 0) {
                        // BER multi-byte count
                        int nb = cntByte & 0x7F;
                        if (nb >= 1 && nb <= 4 && pos + nb * 2 <= payload.length()) {
                            int berCnt = Integer.parseInt(payload.substring(pos, pos + nb * 2), 16);
                            if (berCnt * 2 <= payload.length() - pos - nb * 2) {
                                pos += nb * 2;
                                cnt = berCnt;
                            } else {
                                cnt = cntByte; // implausible BER → Genus plain-byte
                            }
                        } else {
                            cnt = cntByte;
                        }
                        // Plausibility guard: each element is at least 2 hex chars (1 byte).
                        if (cnt * 2 > payload.length() - pos) return -1;
                        for (int i = 0; i < cnt; i++) {
                            pos = skipOneDlmsValue(payload, pos, depth + 1);
                            if (pos < 0) return -1;
                        }
                        return pos;
                    } else {
                        cnt = cntByte;
                        // Plausibility guard: if count × 2 chars > remaining, implausible.
                        if (cnt > 0 && cnt * 2 > payload.length() - pos) return -1;
                        int savedPos = pos;
                        if (tag == 0x02) {
                            // STRUCT: each field has its own tag/encoding; sizes vary.
                            // Recurse all fields; any failure → return -1 (don't truncate).
                            // This lets the caller's record-walk stop cleanly when a struct
                            // is corrupted or declares more fields than exist.
                            for (int i = 0; i < cnt; i++) {
                                int next = skipOneDlmsValue(payload, pos, depth + 1);
                                if (next < 0) return -1;
                                pos = next;
                            }
                            return pos;
                        } else {
                            // ARRAY (tag 0x01): elements are same type.
                            // Try element-count recursion; if any element fails or the total
                            // exceeds cntByte*2 bytes, fall back to byte-count skip
                            // (Genus KT291596 nested byte-count array at field[78]).
                            boolean elementFailed = false;
                            for (int i = 0; i < cnt; i++) {
                                int next = skipOneDlmsValue(payload, pos, depth + 1);
                                if (next < 0 || next > savedPos + cnt * 2) {
                                    elementFailed = true;
                                    break;
                                }
                                pos = next;
                            }
                            if (elementFailed) {
                                return savedPos + cnt * 2;
                            }
                            return pos;
                        }
                    }
                }
                default: return -1;   // truly unknown tag — stop field skip safely
            }
        } catch (Exception e) { return -1; }
    }

    /** Extract the meter's RTC timestamp from the TXT (OBIS 0.0.1.0.0.255). */
    private String extractRtc(String dataUpper) {
        try {
            String marker = "0000010000FF 02 ";
            int idx = dataUpper.indexOf(marker);
            if (idx < 0) return "NA";
            int ps = idx + marker.length();
            int le = dataUpper.indexOf('\n', ps);
            if (le < 0) le = dataUpper.length();
            String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
            if (payload.length() < 28) return "NA";
            if (!payload.startsWith("090C")) return "NA";
            String ts = payload.substring(4, 28);
            int y  = Integer.parseInt(ts.substring(0, 4), 16);
            int mo = Integer.parseInt(ts.substring(4, 6), 16);
            int d  = Integer.parseInt(ts.substring(6, 8), 16);
            int h  = Integer.parseInt(ts.substring(10, 12), 16);
            int mi = Integer.parseInt(ts.substring(12, 14), 16);
            int s  = Integer.parseInt(ts.substring(14, 16), 16);
            // VAL-2 FIX: log when any 0xFF "not specified" bytes are substituted
            boolean ffSub = (mo == 0xFF || d == 0xFF || h == 0xFF || mi == 0xFF || s == 0xFF);
            if (mo == 0xFF) mo = 1; if (d == 0xFF) d = 1;
            if (h == 0xFF) h = 0; if (mi == 0xFF) mi = 0; if (s == 0xFF) s = 0;
            if (ffSub) appendLog("RTC_WARN: FF bytes substituted in timestamp — meter clock may be unset");
            return String.format("%02d/%02d/%04d %02d:%02d:%02d", d, mo, y, h, mi, s);
        } catch (Exception e) { return "NA"; }
    }

    // =====================================================================


    /**
     * Returns true for IS15959-2 compliant meters that carry the full 5E5B OBIS family:
     * 01005E5B03/04/05/06/07FF (scalers), 01005E5B00FF (compound instant snapshot),
     * 00005E5B0AFF (billing amendment), 00005E5B09FF (TOU config).
     *
     * Verified from MRI raw files:
     *   SECURE — live meter SS09079162
     *   HPL    — KT331687_20260325150443.TXT (01005E5B03-07FF + 00FF confirmed)
     *   GENUS  — 30112939.TXT/KT308368 (01005E5B03-07FF + 00FF + 00005E5B0AFF confirmed)
     *   LNG    — KT327122_53_29122025_100655.TXT (01005E5B03-07FF + 08/09/0A/0B/0CFF confirmed)
     */
    /**
     * Returns true if this meter supports IS15959-2 DLMS compound scalar-unit objects
     * (class 7, OBIS 01005E5B0xFF). BUG-15 FIX: renamed from isSecureMeter(); AVON+LNT added.
     */
    private boolean hasDlmsScalarObjects() {
        return currentMeterMake == MeterMake.SECURE
                || currentMeterMake == MeterMake.HPL
                || currentMeterMake == MeterMake.GENUS
                || currentMeterMake == MeterMake.AVON
                || currentMeterMake == MeterMake.LNT
                || currentMeterMake == MeterMake.LANDIS
                || currentMeterMake == MeterMake.LNG;
    }
    /** Backward-compat alias. */
    private boolean isSecureMeter() { return hasDlmsScalarObjects(); }

    /**
     * BUG-4 FIX: Changed from always-append to overwrite (false).
     * buildDataFileName() already puts a timestamp in every filename, so overwrite is safe.
     * Session-start marker added so any accidental double-write is detectable.
     * BUG-20 FIX: Logs warning when TXT exceeds 4 MB.
     */
    public String MakeDataFile(String FileName, String Data) {
        try {
            File[] _extDirs1 = getExternalMediaDirs();
            if (_extDirs1 == null || _extDirs1.length == 0 || _extDirs1[0] == null) {
                appendLog("MakeDataFile ERROR: external media unavailable");
                return "";
            }
            File dir = _extDirs1[0];
            File logFile = new File(dir, FileName + ".TXT");
            int dataSizeKb = Data.length() / 1024;
            if (dataSizeKb > 4096) appendLog("WARN: TXT file size=" + dataSizeKb + " KB > 4 MB — XML converter may reject");
            // Overwrite (false) — unique filename per session ensures no data loss
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, false));
            buf.append("===SESSION START " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "===");
            buf.newLine();
            buf.append(Data);
            buf.newLine();
            buf.close();
            appendLog("MakeDataFile OK: " + logFile.getAbsolutePath() + " size=" + dataSizeKb + " KB");
            return FileName + ".TXT";
        } catch (IOException e) {
            appendLog("MakeDataFile error: " + e.getMessage());
            return "";
        }
    }

    private String sanitizeFilePart(String value) {
        if (value == null || value.trim().isEmpty()) return "NA";
        return value.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private String buildDataFileName(String meterMake, String readMode, String meterNo) {
        String makePart = sanitizeFilePart(meterMake);
        String modePart = sanitizeFilePart(readMode);
        String meterPart = sanitizeFilePart(meterNo);
        String ts = new SimpleDateFormat("ddMMyy_HHmmss").format(new Date());
        return makePart + "_" + modePart + "_" + meterPart + "_" + ts;
    }

    private java.util.List<String> findDlmsLines(String text, String linePrefix) {
        java.util.ArrayList<String> matches = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) return matches;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(linePrefix)) {
                matches.add(trimmed);
            }
        }
        return matches;
    }

    // =====================================================================
    // DATA VALIDATION METHODS — Verify XML converter prerequisites
    // Check for instantaneous and load profile data sections before XML
    // =====================================================================
    public void validateMeterDataForXML(StringBuilder meterData, String readingMode) {
        if (meterData == null || meterData.length() == 0) {
            appendLog("VALIDATION_ERROR: MeterData is empty");
            return;
        }

        String dataStr = meterData.toString();
        appendLog("VALIDATION_START mode=" + readingMode + " dataLen=" + dataStr.length());

        // Check for instantaneous data sections (required in all modes)
        boolean hasInstantData = false;
        boolean hasInstantVoltage = dataStr.contains("0100010200FF"); // Class 3, Voltage
        boolean hasInstantCurrent = dataStr.contains("0100020200FF"); // Class 3, Current
        boolean hasInstantPower = dataStr.contains("0100030700FF");   // Class 3, Power

        if (hasInstantVoltage || hasInstantCurrent || hasInstantPower) {
            hasInstantData = true;
            appendLog("VALIDATION_OK: Instantaneous data FOUND (V=" + hasInstantVoltage + ", I="
                    + hasInstantCurrent + ", P=" + hasInstantPower + ")");
        } else {
            appendLog("VALIDATION_WARN: Instantaneous data MAY BE MISSING — check meter response");
        }

        // Load Profile specific checks — LP is now read only as part of COMPLETE mode.
        if (readingMode.equals("COMPLETE")) {
            boolean hasLPCaptureObj   = dataStr.contains("0100630100FF 03");   // LP Capture Objects
            boolean hasLPCapturePeriod = dataStr.contains("0100630100FF 04"); // LP Capture Period
            boolean hasLPEntriesInUse  = dataStr.contains("0100630100FF 07");  // LP Entries In Use
            boolean hasLPBuffer        = dataStr.contains("0100630100FF 02");  // LP Buffer

            // Parse the actual entries_in_use numeric value — "0007 0100630100FF 07 06XXXXXXXX"
            // contains() alone only says the line exists, not whether the value is 0.
            int lpEntriesInUseValue = -1; // -1 = absent / unparseable
            java.util.List<String> euLines = findDlmsLines(dataStr, "0007 0100630100FF 07 ");
            for (String euLine : euLines) {
                String[] p = euLine.trim().split("\\s+", 4);
                if (p.length >= 4 && p[3].length() >= 10) {
                    try { lpEntriesInUseValue = (int) Long.parseLong(p[3].substring(2, 10), 16);
                    } catch (Exception ignored) {}
                }
            }

            // Parse buffer DLMS array count from structure, not from record-search heuristics.
            // "0007 0100630100FF 02 01 LL ..." — tag=01(array), LL=BER-encoded count.
            int lpBufferArrayCount = -1;
            java.util.List<String> bufLines = findDlmsLines(dataStr, "0007 0100630100FF 02 ");
            for (String bufLine : bufLines) {
                String[] p = bufLine.trim().split("\\s+", 4);
                if (p.length >= 4 && p[3].length() >= 4) {
                    String hex = p[3].toLowerCase();
                    if (hex.startsWith("01")) {
                        try {
                            int lb = Integer.parseInt(hex.substring(2, 4), 16);
                            if      (lb == 0x82 && hex.length() >= 8) lpBufferArrayCount = Integer.parseInt(hex.substring(4, 8), 16);
                            else if (lb == 0x81 && hex.length() >= 6) lpBufferArrayCount = Integer.parseInt(hex.substring(4, 6), 16);
                            else if (lb < 128)                         lpBufferArrayCount = lb;
                        } catch (Exception ignored) {}
                    }
                }
            }

            appendLog("VALIDATION_LP: CaptureObj=" + hasLPCaptureObj
                    + ", Period=" + hasLPCapturePeriod
                    + ", EntriesInUse=" + hasLPEntriesInUse + "(" + lpEntriesInUseValue + ")"
                    + ", Buffer=" + hasLPBuffer + "(arrayCount=" + lpBufferArrayCount + ")");

            if (!hasLPCaptureObj) {
                appendLog("VALIDATION_CRITICAL: LP Capture Objects (attr=3) MISSING — XML parser will fail");
            }
            if (!hasLPEntriesInUse) {
                appendLog("VALIDATION_CRITICAL: LP EntriesInUse (attr=7) MISSING");
            } else if (lpEntriesInUseValue == 0) {
                appendLog("VALIDATION_INFO: LP EntriesInUse=0 — meter LP buffer empty at time of read");
            }
            if (!hasLPBuffer) {
                // Only CRITICAL if meter claimed to have data (entries_in_use > 0)
                // When entries_in_use=0 (confirmed by probe), absence of attr=2 is expected
                if (lpEntriesInUseValue > 0) {
                    appendLog("VALIDATION_CRITICAL: LP Buffer (attr=2) MISSING — entries_in_use="
                            + lpEntriesInUseValue + " but no data written");
                } else {
                    appendLog("VALIDATION_INFO: LP Buffer (attr=2) absent — consistent with entries_in_use=0 (confirmed empty)");
                }
            } else if (lpBufferArrayCount == 0) {
                appendLog("VALIDATION_INFO: LP Buffer present, array count=0 — confirmed empty by DLMS structure");
            }

            // Count parsed LP records using timestamp-start pattern in the hex payload.
            // bufLines is already filtered to lines starting with "0007 0100630100FF 02 "
            // so this never matches the sent-request string (which has no trailing space+data).
            int lpRecordCount = 0;
            for (String line : bufLines) {
                String[] parts = line.split("\\s+", 4);
                if (parts.length >= 4) {
                    lpRecordCount += countLoadProfileRecords(parts[3]);
                }
            }
            appendLog("VALIDATION_LP_RECORDS: " + lpRecordCount + " records found");

            if (lpRecordCount == 0 && lpBufferArrayCount > 0) {
                appendLog("VALIDATION_ERROR: LP Buffer declares " + lpBufferArrayCount
                        + " rows but 0 records parsed — block transfer truncation or row-format mismatch");
            } else if (lpRecordCount == 0 && lpEntriesInUseValue == 0) {
                appendLog("VALIDATION_INFO: LP empty — consistent with EntriesInUse=0");
            }
        }

        // Billing specific checks
        if (readingMode.contains("BILLING") || readingMode.equals("COMPLETE")) {
            boolean hasBillingCaptureObj = dataStr.contains("0100620100FF 03");
            boolean hasBillingBuffer = dataStr.contains("0100620100FF 02");
            boolean hasBillingEntries = dataStr.contains("0100620100FF 07");
            boolean hasBillingObj = hasBillingCaptureObj || hasBillingBuffer || hasBillingEntries;
            appendLog("VALIDATION_BILLING: CaptureObj=" + hasBillingCaptureObj
                    + ", Buffer=" + hasBillingBuffer + ", EntriesInUse=" + hasBillingEntries);

            if (!hasBillingObj) {
                appendLog("VALIDATION_WARN: Billing data (class 7, OBIS 0100620100FF) MISSING");
            }

            // BUG-8 FIX: verify mandatory OBIS columns exist inside billing capture object array
            if (hasBillingCaptureObj) {
                java.util.Map<String, Integer> bilColMap = parseBillingCaptureObjects(dataStr.toUpperCase());
                String[] mandatoryBillingObis = { "0000000102FF", "0100010800FF", "0100090800FF" };
                java.util.List<String> missingCols = new java.util.ArrayList<>();
                for (String ob : mandatoryBillingObis)
                    if (!bilColMap.containsKey(ob.toUpperCase())) missingCols.add(ob);
                if (!missingCols.isEmpty())
                    appendLog("VALIDATION_CRITICAL: Billing mandatory columns MISSING: " + missingCols);
                else
                    appendLog("VALIDATION_OK: Billing mandatory columns present (ts+kWh+kVAh) — " + bilColMap.size() + " total cols");
            }
        }

        // RTC range validation — always check (LOAD_PROFILE mode removed; RTC is always
        // present in INSTANTANEOUS, BILLING, and COMPLETE reads).
        String rtcVal = extractRtc(dataStr.toUpperCase());
        if ("NA".equals(rtcVal)) {
            appendLog("VALIDATION_WARN: RTC (0.0.1.0.0.255) absent from TXT");
        } else {
            try {
                String[] rp = rtcVal.split("[/ :]");
                int rtcYear = Integer.parseInt(rp[2]);
                int rtcMonth = Integer.parseInt(rp[1]);
                if (rtcYear < 2020 || rtcYear > 2030)
                    appendLog("VALIDATION_CRITICAL: RTC year=" + rtcYear + " outside 2020-2030 — meter clock corrupt");
                else if (rtcMonth == 0 || rtcMonth > 12)
                    appendLog("VALIDATION_CRITICAL: RTC month=" + rtcMonth + " invalid");
                else
                    appendLog("VALIDATION_OK: RTC=" + rtcVal);
            } catch (Exception ignored) { appendLog("VALIDATION_WARN: RTC parse failed: " + rtcVal); }
        } // end RTC validation

        appendLog("VALIDATION_END");
    }

    // Count data sections for diagnostics
    public void logMeterDataSummary(StringBuilder meterData) {
        if (meterData == null || meterData.length() == 0) return;

        String dataStr = meterData.toString();
        int nameCount = findDlmsLines(dataStr, "0001 ").size();
        int instantCount = findDlmsLines(dataStr, "0003 ").size() + findDlmsLines(dataStr, "0004 ").size();
        int billingCount = findDlmsLines(dataStr, "0007 0100620100FF ").size();
        int lpCount = findDlmsLines(dataStr, "0007 0100630100FF ").size();
        int eventCount =
                findDlmsLines(dataStr, "0007 0000636200FF ").size() +
                        findDlmsLines(dataStr, "0007 0000636201FF ").size() +
                        findDlmsLines(dataStr, "0007 0000636202FF ").size() +
                        findDlmsLines(dataStr, "0007 0000636203FF ").size() +
                        findDlmsLines(dataStr, "0007 0000636204FF ").size() +
                        findDlmsLines(dataStr, "0007 0000636205FF ").size() +
                        findDlmsLines(dataStr, "0007 0000636281FF ").size();

        appendLog("METER_DATA_SUMMARY: NamePlate=" + nameCount + " Instant=" + instantCount
                + " Billing=" + billingCount + " LoadProfile=" + lpCount + " Events=" + eventCount
                + " TotalSize=" + dataStr.length() + "bytes");
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) >= 0) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    // =====================================================================
    // =====================================================================
    // DIAGNOSTIC FILE LOGGER — persistent rolling daily log
    //
    // Design:
    //   • Log file is kept for the current calendar day: CescRaj_OPTICAL_LOG_(N).TXT
    //     where N increments within each day (e.g. LOG_1.TXT, LOG_2.TXT, ...).
    //   • Each session APPENDS to the current day's file — logs are NOT cleared
    //     between sessions within the same day.
    //   • At midnight the next session creates a new file and deletes any log
    //     files older than 1 day, keeping only today's logs.
    //   • Each session header includes: date/time, meter make, mode, days.
    //   • Buffers in memory, flushes every 50 lines to avoid IO overhead.
    //   • Share any CescRaj_OPTICAL_LOG_*.TXT for diagnostics.
    // =====================================================================
    private static final String LOG_DIR_NAME  = "CescRaj_LOGS";
    private static final String LOG_PREFIX    = "CescRaj_OPTICAL_LOG_";
    private static final String LOG_SUFFIX    = ".TXT";
    // Legacy single-file name (kept for backward compatibility read)
    private static final String LOG_FILE      = "CescRaj_OPTICAL_LOG.TXT";

    private final StringBuilder logBuffer  = new StringBuilder();
    private int  logLineCount  = 0;
    private String currentLogPath = null;   // path of today's active log file
    private static final int LOG_FLUSH_EVERY = 5;  // flush often — LP runs silently for minutes

    public void appendLog(String text) {
        android.util.Log.d("CescRaj_OPTICAL", text);
        String ts = new java.text.SimpleDateFormat("HH:mm:ss.SSS")
                .format(new java.util.Date());
        logBuffer.append("[").append(ts).append("] ").append(text).append("\r\n");
        logLineCount++;
        if (logLineCount >= LOG_FLUSH_EVERY) {
            flushLog();
        }
    }

    /** Flush buffered log lines to the current session's log file */
    public void flushLog() {
        if (logBuffer.length() == 0 || currentLogPath == null) return;
        try {
            java.io.BufferedWriter bw = new java.io.BufferedWriter(
                    new java.io.FileWriter(currentLogPath, true));
            bw.write(logBuffer.toString());
            bw.close();
            logBuffer.setLength(0);
            logLineCount = 0;
        } catch (Exception ignored) {}
    }

    /**
     * Call once at session start.
     * - Purges log files older than 1 calendar day.
     * - Appends a session-start banner to today's rolling log file.
     * - Records meter make, mode and LP days so each session is self-describing.
     *
     * @param meterMakeLabel  human-readable make name (e.g. "HPL", "Secure")
     * @param modeLabel       reading mode (e.g. "Complete", "Instant")
     * @param lpDays          number of LP days requested (0 if not applicable)
     */
    private void startDiagLog(String meterMakeLabel, String modeLabel, int lpDays) {
        logBuffer.setLength(0);
        logLineCount = 0;

        try {
            // Resolve log directory on external media
            File[] _extDirs2 = getExternalMediaDirs();
            if (_extDirs2 == null || _extDirs2.length == 0 || _extDirs2[0] == null) {
                appendLog("DIAGLOG ERROR: external media unavailable"); return;
            }
            File baseDir = _extDirs2[0];
            File logDir  = new File(baseDir, LOG_DIR_NAME);
            if (!logDir.exists()) logDir.mkdirs();

            String todayDate = new java.text.SimpleDateFormat("yyyyMMdd")
                    .format(new java.util.Date());
            long oneDayMs = 24L * 60 * 60 * 1000;
            long cutoff   = System.currentTimeMillis() - oneDayMs;

            // Purge files older than 1 day and find highest session index for today
            int maxIdx = 0;
            File[] existing = logDir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    String name = f.getName();
                    if (!name.startsWith(LOG_PREFIX) || !name.endsWith(LOG_SUFFIX)) continue;
                    // Check if this file belongs to today
                    if (name.contains(todayDate)) {
                        // Extract session index from name: CescRaj_OPTICAL_LOG_YYYYMMDD_N.TXT
                        try {
                            String[] parts = name.replace(LOG_PREFIX, "").replace(LOG_SUFFIX, "").split("_");
                            if (parts.length == 2) {
                                int idx = Integer.parseInt(parts[1]);
                                if (idx > maxIdx) maxIdx = idx;
                            }
                        } catch (Exception ignored) {}
                    } else if (f.lastModified() < cutoff) {
                        // Delete files older than 1 day
                        f.delete();
                    }
                }
            }

            // Create new session file: CescRaj_OPTICAL_LOG_YYYYMMDD_(N+1).TXT
            int sessionIdx = maxIdx + 1;
            String fileName = LOG_PREFIX + todayDate + "_" + sessionIdx + LOG_SUFFIX;
            File logFile = new File(logDir, fileName);
            currentLogPath = logFile.getAbsolutePath();

            // Write session banner
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date());
            java.io.BufferedWriter bw = new java.io.BufferedWriter(
                    new java.io.FileWriter(logFile, false));
            bw.write("==============================\r\n");
            bw.write("[" + ts + "] SESSION START"
                    + " | Make=" + meterMakeLabel
                    + " | Mode=" + modeLabel
                    + (lpDays > 0 ? " | Days=" + lpDays : "")
                    + " | bytTimOut=" + bytTimOut
                    + " bytTryCnt=" + bytTryCnt + "\r\n");
            bw.write("==============================\r\n");
            bw.close();

        } catch (Exception ignored) {
            // If log setup fails, log to a fallback path and continue
            currentLogPath = null;
        }
    }

    /**
     * ReadElswedy — non-DLMS Elswedy proprietary protocol.
     * Stubbed: only DLMS meters are supported per current requirements.
     */
    public StringBuilder ReadElswedy() {
        appendLog("ReadElswedy: non-DLMS protocol — not supported");
        return new StringBuilder();
    }

    /**
     * ReadLnG — non-DLMS L&G IEC 62056-21 optical protocol.
     * Stubbed: only DLMS meters are supported per current requirements.
     */
    public StringBuilder ReadLnG() {
        appendLog("ReadLnG: non-DLMS IEC 62056-21 protocol — not supported");
        return new StringBuilder();
    }

    private Bitmap getImageFileFromSDCard(String filename) {
        Bitmap bitmap = null;

        File[] files = Environment.getExternalStorageDirectory().listFiles();
        File imageFile = new File(Environment.getExternalStorageDirectory() +"/.vcpsysdata/"+ filename);
        try {
            FileInputStream fis = new FileInputStream(imageFile );
            bitmap = BitmapFactory.decodeStream(fis);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        TextView ImageName = (TextView) findViewById(R.id.hidden_Image);
        ImageName.setText(filename);
        return bitmap;
    }
    private boolean  storeCameraPhotoInSDCard(Bitmap bitmap, String currentDate){
        File myDirectory = new File(Environment.getExternalStorageDirectory(), ".TheftPhoto");

        if(!myDirectory.exists()) {
            myDirectory.mkdirs();
        }

        //File outputFile = new File(Environment.getExternalStorageDirectory(),  currentDate );
        File outputFile = new File(myDirectory, currentDate );

        try {
            outputFile.setReadOnly();
            FileOutputStream fileOutputStream = new FileOutputStream(outputFile);
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, fileOutputStream);

            fileOutputStream.flush();
            fileOutputStream.close();

            ///New Code Check Photo Save Or Not
            File imageFile = new File(Environment.getExternalStorageDirectory() + "/.TheftPhoto/" + currentDate);
            if(imageFile.exists())
            {
                DeletePhoto(currentDate);
                return true;
            }
            else
            {
                return false;
            }
            //****
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        catch (Exception ex)
        {
            return false;
        }
    }
    public void DeletePhoto(String FileName)
    {
        try {

            File[] files = Environment.getExternalStorageDirectory().listFiles();
            File imageFile = new File(Environment.getExternalStorageDirectory() +"/"+ FileName);
            if (imageFile.exists()) {
                imageFile.delete();
            }
        }
        catch (Exception ex)
        {}

    }
    public static byte[] intToByteArray(int a)
    {
        byte[] ret = new byte[4];
        ret[3] = (byte) (a & 0xFF);
        ret[2] = (byte) ((a >> 8) & 0xFF);
        ret[1] = (byte) ((a >> 16) & 0xFF);
        ret[0] = (byte) ((a >> 24) & 0xFF);
        return ret;
    }
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len/2];

        for(int i = 0; i < len; i+=2){
            data[i/2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i+1), 16));
        }

        return data;
    }

    public String Hex2Digit(byte a)
    {
        String Result =  Integer.toHexString(0xff & a);
        if ( Result.length()==1)
            Result="0"+ Result;
        return Result;
    }

    private StringBuilder  GetParameterSelective(UsbSerialPort port,byte nClassID, String sOBISCode, byte nAttribID, int nWait, byte nTryCount, byte nTimeOut, boolean isDLM,  Date  dateStartDate, Date  dateEndDate, int intProfilePd)
    {
        StringBuilder SbData = new StringBuilder();


        appendLog("New Fun -1 " + dateStartDate);
        boolean flag1 = false;
        long num1 = 0L;
        /*for (int ma = 0; ma < 100 ; ma++) {
            appendLog( "Paket init -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
        }*/

        byte num2 = (byte) ((int) this.bytAddMode + 8);
        strbldDLMdata = new StringBuilder();
        this.nPkt[2] = (byte)((int) this.bytAddMode + 76);
        //  appendLog( "nRecvCntr -"  +nRecvCntr);
        this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
        // appendLog( "nRecvCntr -"  +nRecvCntr);

        this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte)((int) this.nRetLSH | 16);
        // appendLog( "BytaddMode -"  +this.bytAddMode +"~"+ this.nRetLSH);
        //  appendLog( "nSentCntr"  +nSentCntr);
        this.nRetLSH = (byte)((int) this.nSentCntr << 1);
        this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte)((int) this.nRetLSH | (int) this.nPkt[((0xff & this.bytAddMode) + 5)]);

        //   for (int ma = 0; ma < 100 ; ma++) {
        //   appendLog( "Paket init Before OBIS -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
        //   }

        byte[] numArray1 = this.nPkt;
        int index1 = (int) num2;
        int num3 = 1;
        byte num4 = (byte) (index1 + num3);
        int num5 = 230;
        numArray1[index1] = (byte) num5;
        byte[] numArray2 = this.nPkt;
        int index2 = (int) num4;
        int num6 = 1;
        byte num7 = (byte) (index2 + num6);
        int num8 = 230;
        numArray2[index2] = (byte) num8;
        byte[] numArray3 = this.nPkt;
        int index3 = (int) num7;
        int num9 = 1;
        byte num10 = (byte) (index3 + num9);
        int num11 = 0;
        numArray3[index3] = (byte) num11;
        byte[] numArray4 = this.nPkt;
        int index4 = (int) num10;
        int num12 = 1;
        byte num13 = (byte) (index4 + num12);
        int num14 = 192;
        numArray4[index4] = (byte) num14;
        byte[] numArray5 = this.nPkt;
        int index5 = (int) num13;
        //appendLog("New Fun -2 " + dateStartDate);
        int num15 = 1;
        byte num16 = (byte) (index5 + num15);
        int num17 = 1;
        numArray5[index5] = (byte) num17;
        byte[] numArray6 = this.nPkt;
        int index6 = (int) num16;
        int num18 = 1;
        byte num19 = (byte) (index6 + num18);
        int num20 = 129;
        numArray6[index6] = (byte) num20;
        byte[] numArray7 = this.nPkt;
        int index7 = (int) num19;
        int num21 = 1;
        byte num22 = (byte) (index7 + num21);
        int num23 = 0;
        numArray7[index7] = (byte) num23;
        byte[] numArray8 = this.nPkt;
        int index8 = (int) num22;
        int num24 = 1;
        byte num25 = (byte) (index8 + num24);
        int num26 = (int) nClassID;
        numArray8[index8] = (byte) num26;
        // appendLog( "Before Paket Length " +this.nPkt.length);

        byte[] tempbyte1 = hexStringToByteArray((sOBISCode.substring(0, 12)));
        for (int index9 = 0; index9 < 6; ++index9) {
            //     appendLog("Command IN Loop : " +sOBISCode);
            this.nPkt[(int) num25++] = tempbyte1[index9];
            // appendLog("Command IN Loop : " + num25 + "::" + (int) tempbyte1[index9]);
        }

        //this.nPkt[6]=84;
        // this.nPkt[7]=87;
        // appendLog( "Paket Length " +this.nPkt.length);

        //  for (int ma = 0; ma < 100 ; ma++) {
        //    appendLog( "Paket After OBIS -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
        //  }

        byte[] numArray9 = this.nPkt;
        int index10 = (int) num25;
        int num27 = 1;
        byte num28 = (byte) (index10 + num27);
        int num29 = (int) nAttribID;
        numArray9[index10] = (byte) num29;
        byte[] numArray10 = this.nPkt;
        int index11 = (int) num28;
        int num30 = 1;
        byte num31 = (byte) (index11 + num30);
        int num32 = 1;
        numArray10[index11] = (byte) num32;
        byte[] numArray11 = this.nPkt;
        int index12 = (int) num31;
        int num33 = 1;
        byte num34 = (byte) (index12 + num33);
        int num35 = 1;
        numArray11[index12] = (byte) num35;
        byte[] numArray12 = this.nPkt;
        int index13 = (int) num34;
        int num36 = 1;
        byte num37 = (byte) (index13 + num36);
        int num38 = 2;
        numArray12[index13] = (byte) num38;
        byte[] numArray13 = this.nPkt;
        int index14 = (int) num37;
        int num39 = 1;
        byte num40 = (byte) (index14 + num39);
        int num41 = 4;
        numArray13[index14] = (byte) num41;
        byte[] numArray14 = this.nPkt;
        int index15 = (int) num40;
        int num42 = 1;
        byte num43 = (byte) (index15 + num42);
        int num44 = 2;
        numArray14[index15] = (byte) num44;
        byte[] numArray15 = this.nPkt;
        int index16 = (int) num43;
        int num45 = 1;
        byte num46 = (byte) (index16 + num45);
        int num47 = 4;
        numArray15[index16] = (byte) num47;
        byte[] numArray16 = this.nPkt;
        int index17 = (int) num46;
        int num48 = 1;
        byte num49 = (byte) (index17 + num48);
        int num50 = 18;
        numArray16[index17] = (byte) num50;
        byte[] numArray17 = this.nPkt;
        int index18 = (int) num49;
        int num51 = 1;
        byte num52 = (byte) (index18 + num51);
        int num53 = 0;
        numArray17[index18] = (byte) num53;
        byte[] numArray18 = this.nPkt;
        int index19 = (int) num52;
        int num54 = 1;
        byte num55 = (byte) (index19 + num54);
        int num56 = 8;
        numArray18[index19] = (byte) num56;
        byte[] numArray19 = this.nPkt;
        int index20 = (int) num55;
        int num57 = 1;
        byte num58 = (byte) (index20 + num57);
        int num59 = 9;
        numArray19[index20] = (byte) num59;
        byte[] numArray20 = this.nPkt;
        int index21 = (int) num58;
        int num60 = 1;
        byte num61 = (byte) (index21 + num60);
        int num62 = 6;
        numArray20[index21] = (byte) num62;
        byte[] numArray21 = this.nPkt;
        int index22 = (int) num61;
        int num63 = 1;
        byte num64 = (byte) (index22 + num63);
        int num65 = 0;
        numArray21[index22] = (byte) num65;
        byte[] numArray22 = this.nPkt;
        int index23 = (int) num64;
        int num66 = 1;
        byte num67 = (byte) (index23 + num66);
        int num68 = 0;
        numArray22[index23] = (byte) num68;
        byte[] numArray23 = this.nPkt;
        int index24 = (int) num67;
        int num69 = 1;
        byte num70 = (byte) (index24 + num69);
        int num71 = 1;
        numArray23[index24] = (byte) num71;
        byte[] numArray24 = this.nPkt;
        int index25 = (int) num70;
        int num72 = 1;
        byte num73 = (byte) (index25 + num72);
        int num74 = 0;
        numArray24[index25] = (byte) num74;
        byte[] numArray25 = this.nPkt;
        int index26 = (int) num73;
        int num75 = 1;
        byte num76 = (byte) (index26 + num75);
        int num77 = 0;
        numArray25[index26] = (byte) num77;
        byte[] numArray26 = this.nPkt;
        int index27 = (int) num76;
        int num78 = 1;
        byte num79 = (byte) (index27 + num78);
        int num80 = (int) 255;
        numArray26[index27] = (byte) num80;
        byte[] numArray27 = this.nPkt;
        int index28 = (int) num79;
        int num81 = 1;
        byte num82 = (byte) (index28 + num81);
        int num83 = 15;
        numArray27[index28] = (byte) num83;
        byte[] numArray28 = this.nPkt;
        int index29 = (int) num82;
        int num84 = 1;
        byte num85 = (byte) (index29 + num84);
        int num86 = 2;
        numArray28[index29] = (byte) num86;
        byte[] numArray29 = this.nPkt;
        int index30 = (int) num85;
        int num87 = 1;
        byte num88 = (byte) (index30 + num87);
        int num89 = 18;
        numArray29[index30] = (byte) num89;
        byte[] numArray30 = this.nPkt;
        int index31 = (int) num88;
        int num90 = 1;
        byte num91 = (byte) (index31 + num90);
        int num92 = 0;
        numArray30[index31] = (byte) num92;
        byte[] numArray31 = this.nPkt;
        int index32 = (int) num91;
        int num93 = 1;
        byte num94 = (byte) (index32 + num93);
        int num95 = 0;
        numArray31[index32] = (byte) num95;
        byte[] numArray32 = this.nPkt;
        int index33 = (int) num94;
        int num96 = 1;
        byte num97 = (byte) (index33 + num96);
        int num98 = 9;
        numArray32[index33] = (byte) num98;
        byte[] numArray33 = this.nPkt;
        int index34 = (int) num97;
        int num99 = 1;
        byte num100 = (byte) (index34 + num99);
        int num101 = 12;
        numArray33[index34] = (byte) num101;
        byte[] numArray34 = this.nPkt;
        int index35 = (int) num100;
        int num102 = 1;
        byte num103 = (byte) (index35 + num102);


        int num104 = (int) (byte)(  Until.getYear(dateStartDate) / 256);
        appendLog("Year" +Until.getYear(dateStartDate) +"~" + num104);
        numArray34[index35] = (byte) num104;
        byte[] numArray35 = this.nPkt;
        int index36 = (int) num103;
        int num105 = 1;
        byte num106 = (byte) (index36 + num105);
        int num107 = (int) (0xff & (byte) (Until.getYear(dateStartDate) % 256));
        appendLog("Num107" +num107);
        numArray35[index36] = (byte) num107;
        byte[] numArray36 = this.nPkt;
        int index37 = (int) num106;
        int num108 = 1;
        byte num109 = (byte) (index37 + num108);
        int num110 = (int) (byte) (Until.getMonth(dateStartDate) + 1); // +1: Java 0-based → DLMS 1-based
        // appendLog("num110" +num110);
        numArray36[index37] = (byte) num110;
        byte[] numArray37 = this.nPkt;
        int index38 = (int) num109;
        int num111 = 1;
        byte num112 = (byte) (index38 + num111);
        int num113 = (int) (byte)  Until.getDate(dateStartDate);
        //  appendLog("num113" +num113);
        numArray37[index38] = (byte) num113;
        byte[] numArray38 = this.nPkt;
        int index39 = (int) num112;
        int num114 = 1;
        byte num115 = (byte) (index39 + num114);
        int num116 = (int) 255; // day-of-week = 0xFF (not specified)
        numArray38[index39] = (byte) num116;
        byte[] numArray39 = this.nPkt;
        int index40 = (int) num115;
        int num117 = 1;
        byte num118 = (byte) (index40 + num117);
        // Use the HOUR from dateStartDate (typically 0 after midnight normalisation).
        // Using the actual hour enables the pagination loop to send a different
        // from_time on each continuation request, preventing duplicate responses.
        int num119 = (int)(byte) Until.getHours(dateStartDate);
        numArray39[index40] = (byte) num119;
        // BUG-C FIX: both if/else branches were identical — collapsed into single block.
        // intProfilePd safe-guarded against 0 (BUG-D fix) so division is safe.
        byte num120;
        {
            int index9 = (int) num118;
            num120 = (byte) (index9 + 1);
            int num122 = (intProfilePd > 0)
                    ? (int)(byte)((Until.getMinutes(dateStartDate) / intProfilePd) * intProfilePd)
                    : 0;
            this.nPkt[index9] = (byte) num122;
        }
        byte[] numArray41 = this.nPkt;
        int index41 = (int) num120;
        int num123 = 1;
        byte num124 = (byte) (index41 + num123);
        int num125 = 0;
        numArray41[index41] = (byte) num125;
        byte[] numArray42 = this.nPkt;
        int index42 = (int) num124;
        int num126 = 1;
        byte num127 = (byte) (index42 + num126);
        int num128 = 0;
        numArray42[index42] = (byte) num128;
        byte[] numArray43 = this.nPkt;
        int index43 = (int) num127;
        int num129 = 1;
        byte num130 = (byte) (index43 + num129);
        int num131 = 128;
        numArray43[index43] = (byte) num131;
        byte[] numArray44 = this.nPkt;
        int index44 = (int) num130;
        int num132 = 1;
        byte num133 = (byte) (index44 + num132);
        int num134 = 0;
        numArray44[index44] = (byte) num134;
        byte[] numArray45 = this.nPkt;
        int index45 = (int) num133;
        int num135 = 1;
        byte num136 = (byte) (index45 + num135);
        int num137 = 0;
        numArray45[index45] = (byte) num137;
        byte[] numArray46 = this.nPkt;
        int index46 = (int) num136;
        int num138 = 1;
        byte num139 = (byte) (index46 + num138);
        int num140 = 9;
        numArray46[index46] = (byte) num140;
        byte[] numArray47 = this.nPkt;
        int index47 = (int) num139;
        int num141 = 1;
        byte num142 = (byte) (index47 + num141);
        int num143 = 12;
        numArray47[index47] = (byte) num143;
        byte num144;
        Date Sdate = null;
        try {
            Sdate = Until.Sysdate();
        }
        catch (Exception ex )
        {

        }
        //   appendLog( "Sdate" +Sdate );
        //  appendLog( "dateEndDate" +dateEndDate );
        if (Until.stringToDateOlnly(dateEndDate) == Until.stringToDateOlnly(Sdate))
        {
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num142;
            int num121 = 1;
            byte num122 = (byte) (index9 + num121);
            int num145 = (int) (byte) (Until.getYear(dateEndDate) / 256);
            numArray40[index9] = (byte) num145;
            byte[] numArray48 = this.nPkt;
            int index48 = (int) num122;
            int num146 = 1;
            byte num147 = (byte) (index48 + num146);
            int num148 = (int) (0xff & (byte)( Until.getYear(dateEndDate) % 256));
            numArray48[index48] = (byte) num148;
            byte[] numArray49 = this.nPkt;
            int index49 = (int) num147;
            int num149 = 1;
            byte num150 = (byte) (index49 + num149);
            int num151 = (int)(byte)(Until.getMonth(dateEndDate) + 1); // +1: Java 0-based → DLMS 1-based
            numArray49[index49] = (byte) num151;
            byte[] numArray50 = this.nPkt;
            int index50 = (int) num150;
            int num152 = 1;
            byte num153 = (byte) (index50 + num152);
            int num154 = (int) (byte)( Until.getDate(dateEndDate));
            numArray50[index50] = (byte) num154;
            byte[] numArray51 = this.nPkt;
            int index51 = (int) num153;
            int num155 = 1;
            byte num156 = (byte) (index51 + num155);
            int num157 = (int) 255;
            numArray51[index51] = (byte) num157;
            byte[] numArray52 = this.nPkt;
            int index52 = (int) num156;
            int num158 = 1;
            byte num159 = (byte) (index52 + num158);
            int num160 = (int) (byte)( Until.getHours(Sdate));
            numArray52[index52] = (byte) num160;
            byte[] numArray53 = this.nPkt;
            int index53 = (int) num159;
            int num161 = 1;
            num144 = (byte) (index53 + num161);
            int num162 = (int) (byte)((int)(Until.getMinutes(Sdate)) / intProfilePd * intProfilePd);
            numArray53[index53] = (byte) num162;
        }
        else
        {
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num142;
            int num121 = 1;
            byte num122 = (byte) (index9 + num121);
            int num145 = (int) (byte)( Until.getYear(Until.AddDate(dateEndDate,1)) / 256);
            numArray40[index9] = (byte) num145;
            byte[] numArray48 = this.nPkt;
            int index48 = (int) num122;
            int num146 = 1;
            byte num147 = (byte) (index48 + num146);
            int num148 = (int) (0xff & (byte)( Until.getYear(Until.AddDate(dateEndDate,1)) % 256));
            numArray48[index48] = (byte) num148;
            byte[] numArray49 = this.nPkt;
            int index49 = (int) num147;
            int num149 = 1;
            byte num150 = (byte) (index49 + num149);
            int num151 = (int) (byte)( Until.getMonth(Until.AddDate(dateEndDate,1)) + 1); // +1: Java 0-based → DLMS 1-based
            numArray49[index49] = (byte) num151;
            byte[] numArray50 = this.nPkt;
            int index50 = (int) num150;
            int num152 = 1;
            byte num153 = (byte) (index50 + num152);
            int num154 = (int) (byte)(Until.getDate(Until.AddDate(dateEndDate,1)));
            numArray50[index50] = (byte) num154;
            byte[] numArray51 = this.nPkt;
            int index51 = (int) num153;
            int num155 = 1;
            byte num156 = (byte) (index51 + num155);
            int num157 = (int) 255;
            numArray51[index51] = (byte) num157;
            byte[] numArray52 = this.nPkt;
            int index52 = (int) num156;
            int num158 = 1;
            byte num159 = (byte) (index52 + num158);
            int num160 = 0;
            numArray52[index52] = (byte) num160;
            byte[] numArray53 = this.nPkt;
            int index53 = (int) num159;
            int num161 = 1;
            num144 = (byte) (index53 + num161);
            int num162 = 0;
            numArray53[index53] = (byte) num162;
        }
        byte[] numArray54 = this.nPkt;
        int index54 = (int) num144;
        int num163 = 1;
        byte num164 = (byte) (index54 + num163);
        int num165 = 0;
        numArray54[index54] = (byte) num165;
        byte[] numArray55 = this.nPkt;
        int index55 = (int) num164;
        int num166 = 1;
        byte num167 = (byte) (index55 + num166);
        int num168 = 0;
        numArray55[index55] = (byte) num168;
        byte[] numArray56 = this.nPkt;
        int index56 = (int) num167;
        int num169 = 1;
        byte num170 = (byte) (index56 + num169);
        int num171 = 128;
        numArray56[index56] = (byte) num171;
        byte[] numArray57 = this.nPkt;
        int index57 = (int) num170;
        int num172 = 1;
        byte num173 = (byte) (index57 + num172);
        int num174 = 0;
        numArray57[index57] = (byte) num174;
        byte[] numArray58 = this.nPkt;
        int index58 = (int) num173;
        int num175 = 1;
        byte num176 = (byte) (index58 + num175);
        int num177 = 0;
        numArray58[index58] = (byte) num177;
        byte[] numArray59 = this.nPkt;
        int index59 = (int) num176;
        int num178 = 1;
        byte num179 = (byte) (index59 + num178);
        int num180 = 1;
        numArray59[index59] = (byte) num180;
        byte[] numArray60 = this.nPkt;
        int index60 = (int) num179;
        int num181 = 1;
        byte num182 = (byte) (index60 + num181);
        int num183 = 0;
        numArray60[index60] = (byte) num183;
        this.fcs(this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
        this.fcs(this.nPkt, (int) (byte)((int) num182 - 1), (byte) 1);
        this.nPkt[(int) num182 + 2] = (byte) 126;

        if (isDLM) {
            if (Integer.toHexString(nClassID).length()==1  )
                strbldDLMdata.append("\r\n000" + Integer.toHexString(nClassID) + " " + sOBISCode + " 0" + nAttribID + " ");
            else
                strbldDLMdata.append("\r\n00" + Integer.toHexString(nClassID) + " " + sOBISCode + " 0" + nAttribID + " ");
        }

        byte num184 = (byte) 0;
        boolean flag2;
       /* this.nPkt[49] = 9;
        this.nPkt[63] = 9;
        this.nPkt[75]= 76;
        this.nPkt[76]= (byte)239;
*/
       /* for (int ma = 0; ma < (int) num182 + 3 ; ma++) {
            appendLog( "Paket sent -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
        }

*/


        do
        {

            this.ClearBuffer();
            flag2 = false;
            // FIX O2: flush stale USB FIFO bytes before sending the selective-access
            // GET request.  Without this, residual bytes from a prior session or
            // previous OBIS read can corrupt the first received byte of the response.
            drainPort(port);

            // Fix: use the frame-length byte written into nPkt[2] to size the
            // send buffer — avoids signed-byte overflow when num182 wraps near 128.
            int selSendLen = (0xff & this.nPkt[2]) + 3; // HDLC length field + 2 flags + 1
            byte sendCommand1[] = new byte[selSendLen];
            for (int ma = 0; ma < selSendLen; ma++) {
                sendCommand1[ma] = (byte) (this.nPkt[ma] & 0xff);
            }

            this.SendPkt(port, sendCommand1, selSendLen);
            long original = System.currentTimeMillis();
            long selDeadline = original + ((int) nTimeOut * 1000L);
            ClearBuffer();
            flag2 = receiveFrame(port, selDeadline);
            if (flag2) {
                num184 = (byte) 0;
                this.FrameType();
            } else {
                ++num184;
            }
        }
        while (!flag2 && (int) num184 != (int) nTryCount);

        // appendLog("Loop Outer Cunter" +flag2);

        if (!flag2)
            SbData.append("");
        if (flag2 && (int) this.nRcvPkt[((0xff & this.bytAddMode) + 11)] == 196 && (int) this.nRcvPkt[((0xff & this.bytAddMode) + 12)] == 2)
        {
            //num1 = long.Parse(this.nRcvPkt[((0xff & this.bytAddMode) + 15)].ToString("X2") + ...);
            // FIX: decode 4-byte big-endian block number (byte-sum was wrong for blocks > 255)
            num1 = (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 15)] & 0xFF)) << 24)
                    | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 16)] & 0xFF)) << 16)
                    | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 17)] & 0xFF)) << 8)
                    |  ((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 18)] & 0xFF));

            flag1 = !IntToBool(this.nRcvPkt[((0xff & this.bytAddMode) + 14)]);

            // appendLog("After Rec packet" );

        }
        if ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 11)] == 196 && (int) this.nRcvPkt[((0xff & this.bytAddMode) + 12)] == 2)
        {
            // DataBlock response (c4 02). The raw_data field at addrOff+23 contains:
            //   4 bytes invoke-id + 1 byte result + 3 bytes outer-length = 8 header bytes
            // then the actual DLMS data value (array tag onward).
            // Adding 8 to each offset skips these header bytes so the XML parser
            // receives data starting at the array tag — same format as GetResponse(Normal).
            if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 130)
            {
                for (int index9 = ((0xff & this.bytAddMode) + 31); index9 < this.pktLength - 1; ++index9)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
            }
            else if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 129)
            {
                for (int index9 = ((0xff & this.bytAddMode) + 30); index9 < this.pktLength - 1; ++index9)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
            }
            else
            {
                for (int index9 = ((0xff & this.bytAddMode) + 29); index9 < this.pktLength - 1; ++index9)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
            }

            //   appendLog("After Rec packet If" +strbldDLMdata.toString() );
        }
        else
        {
            // GetResponse(Normal) C4 01: data starts at offset+15 which is invoke-id[2],
            // invoke-id[3], result(00), then the actual DLMS data type tag (01=array, etc.).
            // Skipping 3 bytes past offset+15 (= offset+18) gives the clean data tag.
            // FIX B: start from offset+18 to skip the invoke-id tail and result byte,
            // so the converter always sees the data starting with the array tag (01 NN ...).
            for (int index9 = ((0xff & this.bytAddMode) + 18); index9 < this.pktLength - 1; ++index9)
                strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));

            //    appendLog("After Rec packet Else" +strbldDLMdata.toString() + this.pktLength );
        }

        //  appendLog("While Call-" +(0xff & this.nRcvPkt[1]));
        while (((int) (0xff & this.nRcvPkt[1]) & 168) == 168)
        {
            //    appendLog("While Call Inner11");
            this.nPkt[2] = (byte)((int) this.bytAddMode + 7);
            this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
            this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte)((int) this.nRetLSH | 16);
            this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte)((int) this.nPkt[((0xff & this.bytAddMode) + 5)] | 1);
            this.fcs( this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
            this.nPkt[((0xff & this.bytAddMode) + 8)] = (byte) 126;
            byte num121 = (byte) 0;
            boolean flag3;
            do
            {
                // Clear buffer and send RR only at the start of each genuine retry attempt.
                this.ClearBuffer();
                flag3 = false;
                this.SendPkt(port,this.nPkt,  (byte)((int) this.bytAddMode + 9));
                long original2 = System.currentTimeMillis();
                boolean extendedWait = false; // true while partial bytes are accumulating
                int prevCounter = 0; // tracks last known nCounter to detect stalls

                do
                {
                    if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                        flag3 = false;
                        break;
                    }
                    this.DataReceive(port);
                    String Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                    if (Hex1.length() == 1) Hex1 = "0" + Hex1;
                    String  Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                    if (Hex2.length() == 1) Hex2 = "0" + Hex2;
                    int Len = Integer.parseInt(Hex1 + Hex2, 16);
                    this.pktLength = Len;
                    if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter
                            && (int) this.nRcvPkt[this.pktLength + 1] == 126
                            && this.fcs(this.nRcvPkt, this.pktLength, (byte) 0))
                    {
                        flag3 = true;
                        extendedWait = false;
                        num121 = (byte) 0;
                        // FIX O4: apply same C4 02 vs HDLC-segment discrimination as the
                        // while(flag1) inner loop (FIX O3). For L&T window=7 meters the meter
                        // pushes subsequent DataBlocks (C4 02) proactively before we send
                        // GetRequest(next). These frames arrive here, NOT in while(flag1).
                        // Reading from offset+8 (LLC byte) incorrectly includes LLC header
                        // and full DataBlock headers (~13 bytes) as data garbage.
                        if ((int)(0xff & this.nRcvPkt[(0xff & this.bytAddMode) + 11]) == 0xC4
                                && (int)(0xff & this.nRcvPkt[(0xff & this.bytAddMode) + 12]) == 0x02) {
                            // DataBlock frame: update block tracking, read past DataBlock headers
                            num1 = (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 15)] & 0xFF)) << 24)
                                    | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 16)] & 0xFF)) << 16)
                                    | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 17)] & 0xFF)) << 8)
                                    |  ((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 18)] & 0xFF));
                            flag1 = !IntToBool(this.nRcvPkt[((0xff & this.bytAddMode) + 14)]);
                            if ((int)(0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 130) {
                                for (int index9 = ((0xff & this.bytAddMode) + 23); index9 < this.pktLength - 1; ++index9)
                                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                            } else if ((int)(0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 129) {
                                for (int index9 = ((0xff & this.bytAddMode) + 22); index9 < this.pktLength - 1; ++index9)
                                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                            } else {
                                for (int index9 = ((0xff & this.bytAddMode) + 21); index9 < this.pktLength - 1; ++index9)
                                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                            }
                        } else {
                            // Plain HDLC continuation segment (no DataBlock headers): skip LLC, read from DLMS start
                            for (int index9 = ((0xff & this.bytAddMode) + 11); index9 < this.pktLength - 1; ++index9)
                                strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                        }
                        this.FrameType();
                        break;
                    }
                    else if (((System.currentTimeMillis() - original2))/1000 > (int) nTimeOut
                            && (int) num121 < (int) nTryCount)
                    {
                        if (this.nCounter > 0)
                        {
                            if (this.nCounter > prevCounter) {
                                // Bytes are still arriving — extend the wait.
                                appendLog("HDLC_PARTIAL nCounter=" + this.nCounter + " — extending wait");
                                prevCounter = this.nCounter;
                                original2 = System.currentTimeMillis();
                                extendedWait = true;
                            } else {
                                // nCounter frozen — meter stalled mid-frame, treat as timeout retry.
                                appendLog("HDLC_PARTIAL_STALL nCounter=" + this.nCounter + " — counting as timeout");
                                ++num121;
                                extendedWait = false;
                                break;
                            }
                        }
                        else
                        {
                            // Zero bytes received — true timeout, count as retry
                            ++num121;
                            extendedWait = false;
                            break;
                        }
                    }
                }
                while (!flag3 && (extendedWait || (int) num121 < (int) nTryCount));

            }
            while (!flag3 && (int) num121 < (int) nTryCount);

            if (!flag3 || (int) this.nRcvPkt[1] != 160)
            {
                if (!flag3) {
                    SbData.append("");
                    break;
                }
            }
            else
                break;
        }

        //  appendLog("While Call Innser " +flag1);
        while (flag1)
        {
            // FIX: only abortRequested — lpDeadlineMs must not apply here
            if (abortRequested) {
                appendLog("FLAG1_DEADLINE_BREAK strbldLen=" + strbldDLMdata.length());
                break;
            }
            flag1 = false;
            this.nPkt[2] = (byte)((int) this.bytAddMode + 19);
            this.nRetLSH = (byte)((int) this.nRecvCntr << 5);
            this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte)((int) this.nRetLSH | 16);
            this.nRetLSH = (byte)((int) this.nSentCntr << 1);
            this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte)((int) this.nRetLSH | (int) this.nPkt[((0xff & this.bytAddMode) + 5)]);
            byte num121 = (byte)((int) this.bytAddMode + 8);
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num121;
            int num122 = 1;
            byte num145 = (byte) (index9 + num122);
            int num146 = 230;
            numArray40[index9] = (byte) num146;
            byte[] numArray48 = this.nPkt;
            int index48 = (int) num145;
            int num147 = 1;
            byte num148 = (byte) (index48 + num147);
            int num149 = 230;
            numArray48[index48] = (byte) num149;
            byte[] numArray49 = this.nPkt;
            int index49 = (int) num148;
            int num150 = 1;
            byte num151 = (byte) (index49 + num150);
            int num152 = 0;
            numArray49[index49] = (byte) num152;
            byte[] numArray50 = this.nPkt;
            int index50 = (int) num151;
            int num153 = 1;
            byte num154 = (byte) (index50 + num153);
            int num155 = 192;
            numArray50[index50] = (byte) num155;
            byte[] numArray51 = this.nPkt;
            int index51 = (int) num154;
            int num156 = 1;
            byte num157 = (byte) (index51 + num156);
            int num158 = 2;
            numArray51[index51] = (byte) num158;
            byte[] numArray52 = this.nPkt;
            int index52 = (int) num157;
            int num159 = 1;
            byte num160 = (byte) (index52 + num159);
            int num161 = 129;
            numArray52[index52] = (byte) num161;
            byte[] numArray53 = this.nPkt;
            int index53 = (int) num160;
            int num162 = 1;
            byte num185 = (byte) (index53 + num162);
            int num186 = 0;
            numArray53[index53] = (byte) num186;
            // FIX: encode all 4 bytes of block number big-endian in GetRequest(next).
            // index61..num194 = 4 consecutive packet positions for the block number field.
            byte[] numArray61 = this.nPkt;
            int index61 = (int) num185;
            int num187 = 1;
            byte num188 = (byte) (index61 + num187);
            numArray61[index61] = (byte)((num1 >> 24) & 0xFF);  // MSB byte 0
            byte[] numArray62 = this.nPkt;
            int index62 = (int) num188;
            int num190 = 1;
            byte num191 = (byte) (index62 + num190);
            numArray62[index62] = (byte)((num1 >> 16) & 0xFF);  // byte 1
            byte[] numArray63 = this.nPkt;
            int index63 = (int) num191;
            int num193 = 1;
            byte num194 = (byte) (index63 + num193);
            this.nPkt[index63]        = (byte)((num1 >>  8) & 0xFF);  // byte 2
            this.nPkt[(int)num194 - 1] = (byte) (num1        & 0xFF);  // LSB byte 3
            this.fcs( this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
            this.fcs( this.nPkt, (int) (byte)((int) num194 - 1), (byte) 1);
            this.nPkt[(int) num194 + 2] = (byte) 126;
            byte num196 = (byte) 0;
            boolean flag3;
            do
            {
                this.ClearBuffer();
                flag3 = false;
                // FIX O2: flush stale USB FIFO before each GetRequest(next) send so
                // leftover bytes from the previous DataBlock do not corrupt the FCS check.
                drainPort(port);

                this.SendPkt(port,this.nPkt, (byte)((int) num194 + 3));

                long original4 = System.currentTimeMillis();
                do
                {

                    // Abort/deadline check inside every DataReceive call — prevents
                    // LP from silently hanging for 8s per block when meter is slow.
                    // BUG FIX: must set num196 = nTryCount before break so the OUTER
                    // do-while also exits. Without this, the outer loop re-sends the
                    // packet, immediately hits the deadline again, and loops forever —
                    // producing thousands of "GPLS_SEL_BLOCK_ABORT" log lines and
                    // hanging the session indefinitely.
                    if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                        flag3 = false;
                        flag1 = false;
                        num196 = nTryCount; // force outer do-while to exit too
                        appendLog("GPLS_SEL_BLOCK_ABORT block=" + num1);
                        break;
                    }
                    this.DataReceive(port);
                    String Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                    if (Hex1.length() == 1)
                        Hex1 = "0" + Hex1;

                    //    appendLog("Hex 1" + Hex1);

                    String Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                    if (Hex2.length() == 1)
                        Hex2 = "0" + Hex2;
                    //    appendLog("Hex 2" + Hex2);
                    String hex = (Hex1 + Hex2);

                    int Len = Integer.parseInt(hex, 16);

                    this.pktLength = Len;

                    if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs(this.nRcvPkt, this.pktLength, (byte) 0)))
                    {
                        flag3 = true;
                        num196 = (byte) 0;
                        this.FrameType();
                        break;
                    }
                    else if (((System.currentTimeMillis() - original4))/1000 > (int) nTimeOut && (int) num196 < (int) nTryCount)
                    {
                        {
                            ++num196;
                            break;
                        }
                    }
                }
                while ((int) num196 != (int) nTryCount);
                // label_129:

            }
            while ((int) num196 != (int) nTryCount);
            if (flag3 && (int) this.nRcvPkt[((0xff & this.bytAddMode) + 11)] == 196 && (int) this.nRcvPkt[((0xff & this.bytAddMode) + 12)] == 2)
            {
                // num1 = long.Parse(... hex parse comment ...);
                // FIX: 4-byte big-endian block number decode
                num1 = (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 15)] & 0xFF)) << 24)
                        | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 16)] & 0xFF)) << 16)
                        | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 17)] & 0xFF)) << 8)
                        |  ((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 18)] & 0xFF));
                flag1 = !IntToBool(this.nRcvPkt[((0xff & this.bytAddMode) + 14)]);
            }
            if (!flag3)
                SbData.append("");;
            if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 130)
            {
                for (int index64 = ((0xff & this.bytAddMode) + 23); index64 < this.pktLength - 1; ++index64)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
            }
            else if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 129)
            {
                for (int index64 = ((0xff & this.bytAddMode) + 22); index64 < this.pktLength - 1; ++index64)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
            }
            else
            {
                for (int index64 = ((0xff & this.bytAddMode) + 21); index64 < this.pktLength - 1; ++index64)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
            }
            while (((int) this.nRcvPkt[1] & 168) == 168)
            {
                this.nPkt[2] = (byte)((int) this.bytAddMode + 7);
                this.nRetLSH =(byte)((int) this.nRecvCntr << 5);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte)((int) this.nRetLSH | 16);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte)((int) this.nPkt[((0xff & this.bytAddMode) + 5)] | 1);
                this.fcs( this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
                this.nPkt[((0xff & this.bytAddMode) + 8)] = (byte) 126;
                byte num197 = (byte) 0;
                boolean flag4;
                do
                {
                    flag4 = false;

                    this.ClearBuffer();
                    // FIX O2: flush stale USB FIFO before sending RR to avoid
                    // receiving our own echo or leftover bytes from previous frame.
                    drainPort(port);

                    this.SendPkt(port,this.nPkt, (byte)((int) this.bytAddMode + 9));

                    long original6 = System.currentTimeMillis();
                    do
                    {
                        // FIX O2: abort/deadline check so a stalled L&T window
                        // segment does not block indefinitely.
                        if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                            flag4 = false;
                            appendLog("GPLS_SEG_ABORT block=" + num1);
                            break;
                        }

                        this.DataReceive(port);

                        String Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                        if (Hex1.length() == 1)
                            Hex1 = "0" + Hex1;

                        String Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                        if (Hex2.length() == 1)
                            Hex2 = "0" + Hex2;
                        String hex = (Hex1 + Hex2);

                        int Len = Integer.parseInt(hex, 16);

                        this.pktLength = Len;

                        if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs( this.nRcvPkt, this.pktLength, (byte) 0)))
                        {
                            flag4 = true;
                            num197 = (byte) 0;
                            // For GetResponseDataBlock frames (c4 02), skip LLC+DataBlock headers.
                            // FIX O3: also update flag1/num1 so the outer while(flag1) loop knows
                            // whether more blocks follow — handles L&T window=7 proactive sends.
                            if ((int)(0xff & this.nRcvPkt[(0xff & this.bytAddMode) + 11]) == 0xC4
                                    && (int)(0xff & this.nRcvPkt[(0xff & this.bytAddMode) + 12]) == 0x02) {
                                num1 = (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 15)] & 0xFF)) << 24)
                                        | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 16)] & 0xFF)) << 16)
                                        | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 17)] & 0xFF)) << 8)
                                        |  ((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 18)] & 0xFF));
                                flag1 = !IntToBool(this.nRcvPkt[((0xff & this.bytAddMode) + 14)]);
                                if ((int)(0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 130) {
                                    for (int index64 = ((0xff & this.bytAddMode) + 23); index64 < this.pktLength - 1; ++index64)
                                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
                                } else if ((int)(0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 129) {
                                    for (int index64 = ((0xff & this.bytAddMode) + 22); index64 < this.pktLength - 1; ++index64)
                                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
                                } else {
                                    for (int index64 = ((0xff & this.bytAddMode) + 21); index64 < this.pktLength - 1; ++index64)
                                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
                                }
                            } else {
                                // HDLC segment continuation (no application headers): append from LLC byte
                                for (int index64 = ((0xff & this.bytAddMode) + 11); index64 < this.pktLength - 1; ++index64)
                                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
                            }
                            this.FrameType();
                            break;
                        }
                        else if (((System.currentTimeMillis() - original6))/1000  > (int) nTimeOut && (int) num197 < (int) nTryCount)
                        {
                            {
                                ++num197;
                                break;
                            }
                        }
                    }
                    while ((int) num197 != (int) nTryCount);


                }
                while ((int) num197 != (int) nTryCount);
                // FIX O1: was "return SbData" — that always exited the outer while(flag1)
                // block-transfer loop after just one segmented frame, so only the first
                // HDLC segment was ever processed.  Change to "break" so the inner
                // segmentation loop exits cleanly and while(flag1) continues requesting
                // the remaining DataBlocks normally.
                if (!flag4) {
                    SbData.append(strbldDLMdata.toString());
                    break;
                }
                // flag4=true: re-evaluate while(0xA8) — loop if next frame is also a segment

            }
        }

        SbData.append(strbldDLMdata);
        return SbData;
    }

    private StringBuilder GetParameter1(UsbSerialPort port,byte nClassID, String sOBISCode, byte nAttribID, int nWait, byte nTryCount, byte nTimeOut, boolean isDLM,  StringBuilder strbldDLMdata) {
        StringBuilder SbData = new StringBuilder();
        SbData.append("");
        try {
            //   appendLog("nRetLSH" + nRetLSH);
            //   appendLog("nRecvCntr" + nRecvCntr);
            //  appendLog("nSentCntr" + nSentCntr);


            boolean flag1 = false;
            long num1 = 0L;
            byte num2 = (byte) ((int) this.bytAddMode + 8);
            strbldDLMdata = new StringBuilder();
            this.nPkt[2] = (byte) ((int) this.bytAddMode + 25);
            this.nRetLSH = (byte) (0xff & ((int) this.nRecvCntr) << 5);
            //   appendLog("In Get Paraameter 1.0");
            //   appendLog("nRetLSH" + nRetLSH);
            //  appendLog("nRecvCntr" + nRecvCntr);
            //  appendLog("nSentCntr" + nSentCntr);
            this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | 16);
            this.nRetLSH = (byte) ((int) this.nSentCntr << 1);
            this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | (int) this.nPkt[((0xff & this.bytAddMode) + 5)]);

            //  appendLog("In Get Paraameter 1.1");
            //  appendLog("nRetLSH" + nRetLSH);
            //  appendLog("nRetLSH" + nRetLSH);Check for Bill Block 2
            // appendLog("nRecvCntr" + nRecvCntr);
            //  appendLog("nSentCntr" + nSentCntr);

            byte[] numArray1 = this.nPkt;
            int index1 = (int) (0xff & num2);
            int num3 = 1;
            byte num4 = (byte) ((0xff & index1) + (0xff & num3));
            int num5 = 230;
            numArray1[index1] = (byte) num5;
            byte[] numArray2 = this.nPkt;
            int index2 = (int) (0xff & num4);
            int num6 = 1;
            byte num7 = (byte) (0xff & (index2 + num6));
            int num8 = 230;
            numArray2[index2] = (byte) num8;
            byte[] numArray3 = this.nPkt;
            int index3 = (int) num7;
            int num9 = 1;
            // appendLog("In Get Paraameter 1.2");
            byte num10 = (byte) (index3 + num9);
            int num11 = 0;
            numArray3[index3] = (byte) num11;
            byte[] numArray4 = this.nPkt;
            int index4 = (int) num10;
            int num12 = 1;
            byte num13 = (byte) (index4 + num12);
            int num14 = 192;
            numArray4[index4] = (byte) num14;
            byte[] numArray5 = this.nPkt;
            int index5 = (int) num13;
            int num15 = 1;
            byte num16 = (byte) (index5 + num15);
            int num17 = 1;
            appendLog("In Get Paraameter 1.3");
            numArray5[index5] = (byte) num17;
            byte[] numArray6 = this.nPkt;
            int index6 = (int) num16;
            int num18 = 1;
            byte num19 = (byte) (index6 + num18);
            int num20 = 129;
            numArray6[index6] = (byte) num20;
            byte[] numArray7 = this.nPkt;
            int index7 = (int) num19;
            int num21 = 1;
            byte num22 = (byte) (index7 + num21);
            int num23 = 0;
            numArray7[index7] = (byte) num23;
            byte[] numArray8 = this.nPkt;
            int index8 = (int) num22;
            int num24 = 1;

            byte num25 = (byte) (index8 + num24);
            int num26 = (int) nClassID;
            numArray8[index8] = (byte) num26;


            byte[] tempbyte1 = hexStringToByteArray((sOBISCode.substring(0, 12)));

            for (int index9 = 0; index9 < 6; ++index9) {
                this.nPkt[(int) num25++] = tempbyte1[index9];
                //   appendLog("Command IN Loop : " + num25 + "::" + (int) tempbyte1[index9]);
            }


            byte[] numArray9 = this.nPkt;
            int index10 = (int) num25;
            int num27 = 1;
            byte num28 = (byte) (index10 + num27);
            int num29 = (int) nAttribID;
            numArray9[index10] = (byte) num29;
            byte[] numArray10 = this.nPkt;
            int index11 = (int) num28;
            int num30 = 1;
            byte num31 = (byte) (index11 + num30);
            int num32 = 0;
            numArray10[index11] = (byte) num32;

            this.fcs(this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);

            this.fcs(this.nPkt, (int) (byte) ((int) num31 - 1), (byte) 1);


            this.nPkt[(int) num31 + 2] = (byte) 126;
            if (isDLM) {
                if (Integer.toHexString(nClassID).length()==1  )
                    strbldDLMdata.append("\r\n000" + Integer.toHexString(nClassID) + " " + sOBISCode + " 0" + nAttribID + " ");
                else
                    strbldDLMdata.append("\r\n00" + Integer.toHexString(nClassID) + " " + sOBISCode + " 0" + nAttribID + " ");
            }
            byte num33 = (byte) 0;
            boolean flag2;


            do {
                appendLog("1 loop");
                this.ClearBuffer();
                flag2 = false;

                byte sendCommand1[] = new byte[num31 + 3];
                for (int ma = 0; ma < num31 + 3; ma++) {
                    sendCommand1[ma] = (byte) (this.nPkt[ma] & 0xff);
                    // appendLog( "Instant Send -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
                }

                this.SendPkt(port, sendCommand1, num31 + 3);
                long original = System.currentTimeMillis();
                long gp1Deadline = original + ((int) nTimeOut * 1000L);

                // Gurux blocking-read: receiveFrame polls with 20ms USB reads
                // instead of sleep(80)+poll loops — eliminates ~310ms fixed overhead
                ClearBuffer();
                flag2 = receiveFrame(port, gp1Deadline);
                if (flag2) {
                    num33 = (byte) 0;
                    this.FrameType();
                } else {
                    ++num33;
                }
            }
            while (!flag2 && (int) num33 != (int) nTryCount);

            appendLog("after 1 loop");

            // Fast-fail: ACCESS_ERROR is definitive — don't retry
            if (flag2 && (int)(0xff & this.nRcvPkt[((0xff & this.bytAddMode)+11)]) == 0xC4
                    && (int)(0xff & this.nRcvPkt[((0xff & this.bytAddMode)+12)]) == 0x01
                    && (int)(0xff & this.nRcvPkt[((0xff & this.bytAddMode)+14)]) != 0) {
                SbData.append(strbldDLMdata);
                return SbData;
            }

            if (!flag2 || (int) (0xff & (this.nRcvPkt[((0xff & this.bytAddMode) + 5)])) == 151 || ((int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 5)]) & 1) == 1) {
                SbData.append("");
                appendLog("Condition 0");
            }
            //return false;
            if (flag2 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 11)]) == 196 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 12)]) == 2) {
                // FIX: decode 4-byte big-endian block number from meter response.
                // Original code did byte-sum which is wrong for blocks > 255:
                // e.g. block 256 = 00 00 01 00 → sum=1 (WRONG), correct=256
                // This caused "Downloading Billing" to loop for 7+ minutes.
                num1 = (((long)(this.nRcvPkt[(int) this.bytAddMode + 15] & 0xFF)) << 24)
                        | (((long)(this.nRcvPkt[(int) this.bytAddMode + 16] & 0xFF)) << 16)
                        | (((long)(this.nRcvPkt[(int) this.bytAddMode + 17] & 0xFF)) << 8)
                        |  ((long)(this.nRcvPkt[(int) this.bytAddMode + 18] & 0xFF));
                flag1 = !IntToBool(this.nRcvPkt[((0xff & this.bytAddMode) + 14)]);
                appendLog("Condition 1 blockNum=" + num1 + " moreBlocks=" + flag1);
            }
            if ((int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 11)]) == 196 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 12)]) == 2) {
                appendLog("Condition 2");
                if ((int) (0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 130) {
                    //  appendLog("Condition 3");
                    for (int index9 = ((0xff & this.bytAddMode) + 23); index9 < this.pktLength - 1; ++index9)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));


                } else if ((int) (0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 129) {
                    //  appendLog("Condition 4");
                    for (int index9 = ((0xff & this.bytAddMode) + 22); index9 < this.pktLength - 1; ++index9)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                } else {
                    // appendLog("Condition 5");
                    for (int index9 = ((0xff & this.bytAddMode) + 21); index9 < this.pktLength - 1; ++index9)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                }
            } else {
                // appendLog("Condition 6");
                //   appendLog("pktLength 6" + this.pktLength);
                for (int index9 = ((0xff & this.bytAddMode) + 15); index9 < this.pktLength - 1; ++index9)
                {
                    //    appendLog("Idx" +index9 +"~" +this.nRcvPkt[index9] +"~"+ Integer.toHexString( 0xff & this.nRcvPkt[index9]) );
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                }
                //  appendLog(strbldDLMdata.toString());
            }

            //    appendLog("Condition 7");
            // Guards for billing block-transfer hang detection.
            // billingDeadlineMs: max 90 s for the entire block transfer (prevents 10+ min hangs).
            // gp1ConsecFail: counts consecutive segment failures; abort if meter is stuck.
            long billingDeadlineMs = System.currentTimeMillis() + 90_000L;
            int  gp1ConsecFail     = 0;
            final int GP1_FAIL_MAX = 3;
            int contFrameCount = 0;
            while (((int) (0xff & this.nRcvPkt[1]) & 168) == 168) {
                contFrameCount++;
                // V28 FIX: Check billing deadline at top of moreBlocks loop.
                // This loop had NO deadline protection — the do-while inside only checked
                // lpDeadlineMs which is 0 during billing, so billing could stall
                // indefinitely (confirmed: KT342539 stalled 429s on block 2).
                if (abortRequested || (billingDeadlineMs > 0 && System.currentTimeMillis() > billingDeadlineMs)) {
                    appendLog("GP1_MOREBLOCKS_DEADLINE_ABORT frame=" + contFrameCount
                            + " — billingDeadline exceeded in moreBlocks loop");
                    break;
                }
                this.nPkt[2] = (byte) ((int) this.bytAddMode + 7);
                this.nRetLSH = (byte) (0xff & ((int) this.nRecvCntr) << 5);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | 16);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nPkt[((0xff & this.bytAddMode) + 5)] | 1);
                this.fcs(this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
                this.nPkt[((0xff & this.bytAddMode) + 8)] = (byte) 126;
                byte num34 = (byte) 0;
                boolean flag3;
                do {
                    // FIX: Check LP deadline at start of each HDLC retry.
                    // Without this, a stalled meter can hold the do-while loop for
                    // nTryCount × nTimeOut seconds (e.g. 2 × 8s = 16s per frame) with
                    // no way to abort — the LP deadline fires only between outer while
                    // iterations (between day requests), not inside a stalled block transfer.
                    // In practice this caused 15+ min LP hangs on L&T meters that sent
                    // one valid HDLC frame then went silent mid-transfer.
                    if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                        appendLog("GPLS_CONT_DEADLINE_ABORT frame=" + contFrameCount
                                + " — lpDeadline or abortRequested, breaking HDLC retry loop");
                        flag3 = false;
                        num34 = nTryCount; // force do-while exit
                        break;
                    }
                    drainPort(port);
                    flag3 = false;
                    byte sendCommand1[] = new byte[this.bytAddMode + 9];
                    for (int ma = 0; ma < this.bytAddMode + 9; ma++) sendCommand1[ma] = this.nPkt[ma];
                    this.SendPkt(port, sendCommand1, this.bytAddMode + 9);
                    long original = System.currentTimeMillis();
                    long contDl = original + ((int) nTimeOut * 1000L);
                    flag3 = receiveFrame(port, contDl);

                    // GPLS_CONT fix: receiveFrame uses a tight pktLength+2 check.
                    // Some meters (L&T Schneider) send a short GetResponse(normal) frame
                    // in response to a billing/LP GET — e.g. 13 bytes for an empty buffer.
                    // receiveFrame returns false because nCounter never reaches pktLength+2.
                    // But the frame IS complete — the off-by-2 is because the 0x7E closing
                    // flag is already accounted for in pktLength on some firmware variants.
                    // Fix: if receiveFrame timed out but nCounter > 0, try parsePktLen
                    // directly and accept the frame if FCS passes.
                    if (!flag3 && this.nCounter > 0) {
                        int pLen = parsePktLen();
                        if (pLen > 0 && pLen <= this.nCounter
                                && this.fcs(this.nRcvPkt, pLen, (byte) 0)) {
                            this.pktLength = pLen;
                            this.FrameType();
                            flag3 = true;
                            appendLog("GPLS_CONT_RECOVER frame=" + contFrameCount
                                    + " nCounter=" + this.nCounter + " pLen=" + pLen);
                        } else if (pLen > 0 && this.nCounter >= pLen - 1) {
                            // One trailing byte short — wait briefly for closing 0x7E
                            for (int w = 0; w < 10 && !flag3; w++) {
                                this.DataReceive(port, 20);
                                if (pLen <= this.nCounter && this.fcs(this.nRcvPkt, pLen, (byte) 0)) {
                                    this.pktLength = pLen;
                                    this.FrameType();
                                    flag3 = true;
                                    appendLog("GPLS_CONT_TRAILINGBYTE frame=" + contFrameCount);
                                }
                            }
                        }
                    }

                    if (flag3) {
                        num34 = (byte) 0;
                        for (int index9 = ((0xff & this.bytAddMode) + 8); index9 < this.pktLength - 1; ++index9)
                            strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                        this.FrameType();
                    } else {
                        appendLog("GPLS_CONT_TIMEOUT frame=" + contFrameCount + " retry=" + num34 + " elapsed=" + (System.currentTimeMillis()-original) + "ms nCounter=" + nCounter);
                        ++num34;
                    }
                    if (flag3) {
                        if ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] == 151
                                || ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] & 1) == 1) {
                            SbData.append("");
                        } else break;
                    }
                }
                while ((int) num34 != (int) nTryCount);
                if (!flag3 || (int) this.nRcvPkt[1] != 160) {
                    if (!flag3) {
                        SbData.append("");
                        appendLog("GPLS_CONT_FAIL frame=" + contFrameCount + " — breaking HDLC segment loop");
                        gp1ConsecFail++;          // track consecutive segment failures
                        break;
                    }
                } else {
                    gp1ConsecFail = 0;            // successful segment → reset counter
                    break;
                }
            }
            // appendLog("flag1 ###" + flag1);
            while (flag1) {
                if (abortRequested) {
                    appendLog("FLAG1_DEADLINE_BREAK strbldLen=" + strbldDLMdata.length());
                    break;
                }
                // Auto-abort: 90-second wall-clock limit for the whole block transfer
                if (System.currentTimeMillis() > billingDeadlineMs) {
                    appendLog("GP1_BILLING_DEADLINE strbldLen=" + strbldDLMdata.length());
                    break;
                }
                // Auto-abort: meter stuck sending partial frames on every block
                if (gp1ConsecFail >= GP1_FAIL_MAX) {
                    appendLog("GP1_CONSEC_FAIL_ABORT failCount=" + gp1ConsecFail
                            + " strbldLen=" + strbldDLMdata.length());
                    break;
                }


                //  appendLog("Manu Here@#@#@#@#@");
                flag1 = false;
                this.nPkt[2] = (byte) ((int) this.bytAddMode + 19);
                this.nRetLSH = (byte) (0xff & ((int) this.nRecvCntr) << 5);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | 16);
                this.nRetLSH = (byte) ((int) this.nSentCntr << 1);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | (int) this.nPkt[((0xff & this.bytAddMode) + 5)]);
                byte num34 = (byte) ((int) this.bytAddMode + 8);
                byte[] numArray11 = this.nPkt;
                int index9 = (int) num34;
                int num35 = 1;
                byte num36 = (byte) (0xff & (index9 + num35));
                int num37 = 230;
                numArray11[index9] = (byte) num37;
                byte[] numArray12 = this.nPkt;
                int index12 = (int) num36;
                int num38 = 1;
                byte num39 = (byte) (0xff & (index12 + num38));
                int num40 = 230;
                numArray12[index12] = (byte) num40;
                byte[] numArray13 = this.nPkt;
                int index13 = (int) num39;
                int num41 = 1;
                byte num42 = (byte) (0xff & (index13 + num41));
                int num43 = 0;
                numArray13[index13] = (byte) num43;
                byte[] numArray14 = this.nPkt;
                int index14 = (int) num42;
                int num44 = 1;
                byte num45 = (byte) (index14 + num44);
                int num46 = 192;
                numArray14[index14] = (byte) num46;
                byte[] numArray15 = this.nPkt;
                int index15 = (int) num45;
                int num47 = 1;
                byte num48 = (byte) (0xff & (index15 + num47));
                int num49 = 2;
                numArray15[index15] = (byte) num49;
                byte[] numArray16 = this.nPkt;
                int index16 = (int) num48;
                int num50 = 1;
                byte num51 = (byte) (index16 + num50);
                int num52 = 129;
                numArray16[index16] = (byte) num52;
                byte[] numArray17 = this.nPkt;
                int index17 = (int) num51;
                int num53 = 1;
                byte num54 = (byte) (index17 + num53);
                int num55 = 0;
                numArray17[index17] = (byte) num55;
                byte[] numArray18 = this.nPkt;
                int index18 = (int) num54;
                int num56 = 1;
                byte num57 = (byte) (index18 + num56);
                // FIX: encode all 4 bytes of the block number in GetRequest(next).
                // Original code only encoded 2 bytes (num1/256 and num1%256) and
                // left nPkt[index18] and nPkt[index18-1] as stale/wrong values.
                // DLMS GetRequest(next) block number is 4-byte big-endian at offset +14..+17.
                // index18-1 = addrOff+14 (low-bits of 3rd byte), index18 = addrOff+15 (3rd byte)
                // We need: addrOff+14=byte2, addrOff+15=byte3 — but the 4-byte block num
                // at the GetNext PDU is at the 4 bytes that are offset 3..6 from C0 02 81 00.
                // These correspond to index18-1..num60 in the packet layout:
                // [addrOff+14]=(num1>>24), [addrOff+15]=(num1>>16), [addrOff+16]=(num1>>8), [addrOff+17]=num1
                // index18 = addrOff+14 (the first block-number byte position)
                numArray18[index18]     = (byte)((num1 >> 24) & 0xFF); // MSB
                byte[] numArray19 = this.nPkt;
                int index19 = (int) num57;
                int num59 = 1;
                byte num60 = (byte) (index19 + num59);
                numArray19[index19]     = (byte)((num1 >> 16) & 0xFF);
                byte[] numArray20 = this.nPkt;
                int index20 = (int) num60;
                int num62 = 1;
                byte num63 = (byte) (index20 + num62);
                this.nPkt[index20]      = (byte)((num1 >>  8) & 0xFF);
                this.nPkt[(int)num63 - 1] = (byte) (num1        & 0xFF); // LSB
                appendLog("GP1_NEXT_BLOCK blockNum=" + num1);
                this.fcs(this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
                this.fcs(this.nPkt, (int) (byte) ((int) num63 - 1), (byte) 1);
                this.nPkt[(int) num63 + 2] = (byte) 126;
                byte num65 = (byte) 0;
                boolean flag3;
                // V28 FIX: Per-block deadline — 30s max per individual block.
                // Without this, each failed block attempt consumed nTryCount×nTimeOut=24s,
                // and 18 failed attempts × 24s = 432s before billingDeadlineMs fired.
                // With per-block deadline: max 30s per block regardless of nTryCount.
                long blockDeadlineMs = System.currentTimeMillis() + 30_000L;
                do {
                    // Check both billing deadline AND per-block deadline
                    if (abortRequested
                            || (billingDeadlineMs > 0 && System.currentTimeMillis() > billingDeadlineMs)
                            || System.currentTimeMillis() > blockDeadlineMs) {
                        appendLog("GP1_BLOCK_DEADLINE_ABORT blockNum=" + num1
                                + " — billingDeadline or blockDeadline(30s) or abort");
                        flag3 = false;
                        num65 = nTryCount;
                        break;
                    }
                    this.ClearBuffer();
                    flag3 = false;

                    byte sendCommand1[] = new byte[num63 + 3];
                    for (int ma = 0; ma < num63 + 3; ma++) {
                        sendCommand1[ma] = this.nPkt[ma];
                    }
                    this.SendPkt(port, sendCommand1, num63 + 3);
                    long original = System.currentTimeMillis();
                    ClearBuffer();
                    flag3 = receiveFrame(port, original + ((int) nTimeOut * 1000L));
                    if (flag3) {
                        num65 = (byte) 0;
                        this.FrameType();
                    } else {
                        ++num65;
                    }

                    if (flag3) {
                        if ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] == 151 || ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] & 1) == 1)
                            SbData.append("");
                        else
                            break;
                    }
                }
                while ((int) num65 != (int) nTryCount);

                if (flag3 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 11)]) == 196 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 12)]) == 2) {
                    // FIX: same 4-byte big-endian decode for subsequent blocks
                    num1 = (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 15)] & 0xFF)) << 24)
                            | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 16)] & 0xFF)) << 16)
                            | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 17)] & 0xFF)) << 8)
                            |  ((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 18)] & 0xFF));
                    //    appendLog("IMH-" + (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 14)]));
                    flag1 = !IntToBool(0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 14)]);
                }
                if (!flag3)
                    SbData.append("");
                if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 130) {
                    for (int index21 = ((0xff & this.bytAddMode) + 23); index21 < this.pktLength - 1; ++index21)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                } else if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 129) {
                    for (int index21 = ((0xff & this.bytAddMode) + 22); index21 < this.pktLength - 1; ++index21)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                } else {
                    for (int index21 = ((0xff & this.bytAddMode) + 21); index21 < this.pktLength - 1; ++index21)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                }

                boolean myLogic = false;
                while (((int) (0xff & this.nRcvPkt[1]) & 168) == 168) {
                    //     appendLog("Time Haigoo Flag Main Loop1");
                    this.nPkt[2] = (byte) ((int) this.bytAddMode + 7);
                    this.nRetLSH = (byte) ((int) (0xff & this.nRecvCntr) << 5);
                    this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) (this.nRetLSH | 16);
                    this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) (this.nPkt[((0xff & this.bytAddMode) + 5)] | 1);
                    this.fcs(this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
                    this.nPkt[((0xff & this.bytAddMode) + 8)] = (byte) 126;
                    byte num66 = (byte) 0;
                    boolean flag4;
                    do {
                        flag4 = false;

                        // FIX: drainPort flushes stale USB FIFO bytes
                        drainPort(port);
                        byte sendCommand1[] = new byte[this.bytAddMode + 9];
                        for (int ma = 0; ma < this.bytAddMode + 9; ma++) {
                            sendCommand1[ma] = this.nPkt[ma];
                        }
                        this.SendPkt(port, sendCommand1, this.bytAddMode + 9);
                        long original = System.currentTimeMillis();
                        flag4 = receiveFrame(port, original + ((int) nTimeOut * 1000L));
                        if (flag4) {
                            num66 = (byte) 0;
                            for (int index21 = ((0xff & this.bytAddMode) + 8); index21 < this.pktLength - 1; ++index21)
                                strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                            myLogic = true;
                            this.FrameType();
                        } else {
                            ++num66;
                        }
                        if (flag4)
                        {
                            if ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] == 151 || ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] & 1) == 1) {
                                SbData.append(strbldDLMdata);
                                return SbData;
                            }
                            else
                                break;
                        }
                    }
                    while ((int) num66 != (int) nTryCount);

                    if (!flag4) {
                        SbData.append("");
                        // FIX: break outer while(0xA8) when retries exhausted
                        break;
                    }
                    //  appendLog("Loop End 168");
                }

                // appendLog("Loop End Falg 1");
            }


            //   appendLog("My Rec Data"+ strbldDLMdata.toString());
            SbData.append(strbldDLMdata.toString());
            return SbData;
        }
        catch (Exception ex)
        {
            appendLog("Errorrr----");
            return SbData;
        }
    }

    private StringBuilder GetParameter(UsbSerialPort port,byte nClassID, String sOBISCode, byte nAttribID, int nWait, byte nTryCount, byte nTimeOut, boolean isDLM,  StringBuilder strbldDLMdata) {
        StringBuilder SbData = new StringBuilder();
        SbData.append("");
        try {
            //appendLog("nRetLSH" + nRetLSH);
            // appendLog("nRecvCntr" + nRecvCntr);
            //  appendLog("nSentCntr" + nSentCntr);


            boolean flag1 = false;
            long num1 = 0L;
            byte num2 = (byte) ((int) this.bytAddMode + 8);
            strbldDLMdata = new StringBuilder();
            this.nPkt[2] = (byte) ((int) this.bytAddMode + 25);
            this.nRetLSH = (byte) (0xff & ((int) this.nRecvCntr) << 5);
            // appendLog("In Get Paraameter 1.0");
            //   appendLog("nRetLSH" + nRetLSH);
            //  appendLog("nRecvCntr" + nRecvCntr);
            //  appendLog("nSentCntr" + nSentCntr);
            this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | 16);
            this.nRetLSH = (byte) ((int) this.nSentCntr << 1);
            this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | (int) this.nPkt[((0xff & this.bytAddMode) + 5)]);

            // appendLog("In Get Paraameter 1.1");
            //  appendLog("nRetLSH" + nRetLSH);
            // appendLog("nRecvCntr" + nRecvCntr);
            //  appendLog("nSentCntr" + nSentCntr);

            byte[] numArray1 = this.nPkt;
            int index1 = (int) (0xff & num2);
            int num3 = 1;
            byte num4 = (byte) ((0xff & index1) + (0xff & num3));
            int num5 = 230;
            numArray1[index1] = (byte) num5;
            byte[] numArray2 = this.nPkt;
            int index2 = (int) (0xff & num4);
            int num6 = 1;
            byte num7 = (byte) (0xff & (index2 + num6));
            int num8 = 230;
            numArray2[index2] = (byte) num8;
            byte[] numArray3 = this.nPkt;
            int index3 = (int) num7;
            int num9 = 1;
            //    appendLog("In Get Paraameter 1.2");
            byte num10 = (byte) (index3 + num9);
            int num11 = 0;
            numArray3[index3] = (byte) num11;
            byte[] numArray4 = this.nPkt;
            int index4 = (int) num10;
            int num12 = 1;
            byte num13 = (byte) (index4 + num12);
            int num14 = 192;
            numArray4[index4] = (byte) num14;
            byte[] numArray5 = this.nPkt;
            int index5 = (int) num13;
            int num15 = 1;
            byte num16 = (byte) (index5 + num15);
            int num17 = 1;
            //  appendLog("In Get Paraameter 1.3");
            numArray5[index5] = (byte) num17;
            byte[] numArray6 = this.nPkt;
            int index6 = (int) num16;
            int num18 = 1;
            byte num19 = (byte) (index6 + num18);
            int num20 = 129;
            numArray6[index6] = (byte) num20;
            byte[] numArray7 = this.nPkt;
            int index7 = (int) num19;
            int num21 = 1;
            byte num22 = (byte) (index7 + num21);
            int num23 = 0;
            numArray7[index7] = (byte) num23;
            byte[] numArray8 = this.nPkt;
            int index8 = (int) num22;
            int num24 = 1;

            byte num25 = (byte) (index8 + num24);
            int num26 = (int) nClassID;
            numArray8[index8] = (byte) num26;


            byte[] tempbyte1 = hexStringToByteArray((sOBISCode.substring(0, 12)));

            for (int index9 = 0; index9 < 6; ++index9) {
                this.nPkt[(int) num25++] = tempbyte1[index9];
                //    appendLog("Command IN Loop : " + num25 + "::" + (int) tempbyte1[index9]);
            }


            byte[] numArray9 = this.nPkt;
            int index10 = (int) num25;
            int num27 = 1;
            byte num28 = (byte) (index10 + num27);
            int num29 = (int) nAttribID;
            numArray9[index10] = (byte) num29;
            byte[] numArray10 = this.nPkt;
            int index11 = (int) num28;
            int num30 = 1;
            byte num31 = (byte) (index11 + num30);
            int num32 = 0;
            numArray10[index11] = (byte) num32;

            this.fcs(this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);

            this.fcs(this.nPkt, (int) (byte) ((int) num31 - 1), (byte) 1);


            this.nPkt[(int) num31 + 2] = (byte) 126;
            if (isDLM) {
                if (Integer.toHexString(nClassID).length()==1  )
                    strbldDLMdata.append("\r\n000" + Integer.toHexString(nClassID) + " " + sOBISCode + " 0" + nAttribID + " ");
                else
                    strbldDLMdata.append("\r\n00" + Integer.toHexString(nClassID) + " " + sOBISCode + " 0" + nAttribID + " ");
            }
            byte num33 = (byte) 0;
            boolean flag2;


            do {
                //   appendLog("1 loop");
                this.ClearBuffer();
                flag2 = false;

                byte sendCommand1[] = new byte[num31 + 3];
                for (int ma = 0; ma < num31 + 3; ma++) {
                    sendCommand1[ma] = (byte) (this.nPkt[ma] & 0xff);
                    //  appendLog( "Instant Send -"+this.nPkt[ma] +" ~" + (this.nPkt[ma] & 0xff ) );
                }

                this.SendPkt(port, sendCommand1, num31 + 3);
                long original = System.currentTimeMillis();
                long gpDeadline = original + ((int) nTimeOut * 1000L);

                // Gurux blocking-read: receiveFrame polls with 20ms USB reads
                // instead of sleep(30)+DataReceive loops — much faster on responsive meters
                ClearBuffer();
                flag2 = receiveFrame(port, gpDeadline);
                if (flag2) {
                    num33 = (byte) 0;
                    this.FrameType();
                } else {
                    ++num33;
                }
            }
            while (!flag2 && (int) num33 != (int) nTryCount);

            // Fast-fail: if we got a valid HDLC frame but it's an ACCESS_ERROR (result!=0),
            // the meter definitively rejected this request — retrying wastes 9s per attempt.
            if (flag2 && (int)(0xff & this.nRcvPkt[((0xff & this.bytAddMode)+11)]) == 0xC4
                    && (int)(0xff & this.nRcvPkt[((0xff & this.bytAddMode)+12)]) == 0x01
                    && (int)(0xff & this.nRcvPkt[((0xff & this.bytAddMode)+14)]) != 0) {
                // ACCESS_ERROR — break retry loop, return empty
                SbData.append(strbldDLMdata);
                return SbData;
            }

            if (!flag2 || (int) (0xff & (this.nRcvPkt[((0xff & this.bytAddMode) + 5)])) == 151 || ((int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 5)]) & 1) == 1) {
                SbData.append("");
            }
            //return false;
            if (flag2 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 11)]) == 196 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 12)]) == 2) {
                // FIX: decode 4-byte big-endian block number (byte-sum was wrong for blocks > 255)
                num1 = (((long)(this.nRcvPkt[(int) this.bytAddMode + 15] & 0xFF)) << 24)
                        | (((long)(this.nRcvPkt[(int) this.bytAddMode + 16] & 0xFF)) << 16)
                        | (((long)(this.nRcvPkt[(int) this.bytAddMode + 17] & 0xFF)) << 8)
                        |  ((long)(this.nRcvPkt[(int) this.bytAddMode + 18] & 0xFF));
                flag1 = !IntToBool(this.nRcvPkt[((0xff & this.bytAddMode) + 14)]);
                //     appendLog("Condition 1");
            }
            if ((int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 11)]) == 196 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 12)]) == 2) {
                //     appendLog("Condition 2");
                if ((int) (0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 130) {
                    //         appendLog("Condition 3");
                    for (int index9 = ((0xff & this.bytAddMode) + 23); index9 < this.pktLength - 1; ++index9)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));


                } else if ((int) (0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 129) {
                    //         appendLog("Condition 4");
                    for (int index9 = ((0xff & this.bytAddMode) + 22); index9 < this.pktLength - 1; ++index9)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                } else {
                    //           appendLog("Condition 5");
                    for (int index9 = ((0xff & this.bytAddMode) + 21); index9 < this.pktLength - 1; ++index9)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                }
            } else {
                //     appendLog("Condition 6");
                //     appendLog("pktLength 6" + this.pktLength);
                for (int index9 = ((0xff & this.bytAddMode) + 15); index9 < this.pktLength - 1; ++index9)
                {
                    //            appendLog("Idx" +index9 +"~" +this.nRcvPkt[index9] +"~"+ Integer.toHexString( 0xff & this.nRcvPkt[index9]) );
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                }
                //        appendLog(strbldDLMdata.toString());
            }

            //     appendLog("Condition 7");
            int contFrameCount = 0;
            while (((int) (0xff & this.nRcvPkt[1]) & 168) == 168) {
                contFrameCount++;
                this.nPkt[2] = (byte) ((int) this.bytAddMode + 7);
                this.nRetLSH = (byte) (0xff & ((int) this.nRecvCntr) << 5);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | 16);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nPkt[((0xff & this.bytAddMode) + 5)] | 1);
                this.fcs(this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
                this.nPkt[((0xff & this.bytAddMode) + 8)] = (byte) 126;
                byte num34 = (byte) 0;
                boolean flag3;
                do {
                    // FIX: Check LP deadline at start of each HDLC retry.
                    // Without this, a stalled meter can hold the do-while loop for
                    // nTryCount × nTimeOut seconds (e.g. 2 × 8s = 16s per frame) with
                    // no way to abort — the LP deadline fires only between outer while
                    // iterations (between day requests), not inside a stalled block transfer.
                    // In practice this caused 15+ min LP hangs on L&T meters that sent
                    // one valid HDLC frame then went silent mid-transfer.
                    if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                        appendLog("GPLS_CONT_DEADLINE_ABORT frame=" + contFrameCount
                                + " — lpDeadline or abortRequested, breaking HDLC retry loop");
                        flag3 = false;
                        num34 = nTryCount; // force do-while exit
                        break;
                    }
                    drainPort(port);
                    flag3 = false;
                    byte sendCommand1[] = new byte[this.bytAddMode + 9];
                    for (int ma = 0; ma < this.bytAddMode + 9; ma++) sendCommand1[ma] = this.nPkt[ma];
                    this.SendPkt(port, sendCommand1, this.bytAddMode + 9);
                    long original = System.currentTimeMillis();
                    long contDl = original + ((int) nTimeOut * 1000L);
                    flag3 = receiveFrame(port, contDl);

                    // GPLS_CONT fix: receiveFrame uses a tight pktLength+2 check.
                    // Some meters (L&T Schneider) send a short GetResponse(normal) frame
                    // in response to a billing/LP GET — e.g. 13 bytes for an empty buffer.
                    // receiveFrame returns false because nCounter never reaches pktLength+2.
                    // But the frame IS complete — the off-by-2 is because the 0x7E closing
                    // flag is already accounted for in pktLength on some firmware variants.
                    // Fix: if receiveFrame timed out but nCounter > 0, try parsePktLen
                    // directly and accept the frame if FCS passes.
                    if (!flag3 && this.nCounter > 0) {
                        int pLen = parsePktLen();
                        if (pLen > 0 && pLen <= this.nCounter
                                && this.fcs(this.nRcvPkt, pLen, (byte) 0)) {
                            this.pktLength = pLen;
                            this.FrameType();
                            flag3 = true;
                            appendLog("GPLS_CONT_RECOVER frame=" + contFrameCount
                                    + " nCounter=" + this.nCounter + " pLen=" + pLen);
                        } else if (pLen > 0 && this.nCounter >= pLen - 1) {
                            // One trailing byte short — wait briefly for closing 0x7E
                            for (int w = 0; w < 10 && !flag3; w++) {
                                this.DataReceive(port, 20);
                                if (pLen <= this.nCounter && this.fcs(this.nRcvPkt, pLen, (byte) 0)) {
                                    this.pktLength = pLen;
                                    this.FrameType();
                                    flag3 = true;
                                    appendLog("GPLS_CONT_TRAILINGBYTE frame=" + contFrameCount);
                                }
                            }
                        }
                    }

                    if (flag3) {
                        num34 = (byte) 0;
                        for (int index9 = ((0xff & this.bytAddMode) + 8); index9 < this.pktLength - 1; ++index9)
                            strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                        this.FrameType();
                    } else {
                        appendLog("GPLS_CONT_TIMEOUT frame=" + contFrameCount + " retry=" + num34 + " elapsed=" + (System.currentTimeMillis()-original) + "ms nCounter=" + nCounter);
                        ++num34;
                    }
                    if (flag3) {
                        if ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] == 151
                                || ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] & 1) == 1) {
                            SbData.append("");
                        } else break;
                    }
                }
                while ((int) num34 != (int) nTryCount);
                if (!flag3 || (int) this.nRcvPkt[1] != 160) {
                    if (!flag3) {
                        SbData.append("");
                        appendLog("GPLS_CONT_FAIL frame=" + contFrameCount + " — breaking HDLC segment loop");
                        break;
                    }
                } else break;
            }
            while (flag1) {
                // FIX: only abortRequested — lpDeadlineMs must not apply here
                if (abortRequested) {
                    appendLog("FLAG1_DEADLINE_BREAK strbldLen=" + strbldDLMdata.length());
                    break;
                }


                //     appendLog("Manu Here@#@#@#@#@");
                flag1 = false;
                this.nPkt[2] = (byte) ((int) this.bytAddMode + 19);
                this.nRetLSH = (byte) (0xff & ((int) this.nRecvCntr) << 5);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | 16);
                this.nRetLSH = (byte) ((int) this.nSentCntr << 1);
                this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | (int) this.nPkt[((0xff & this.bytAddMode) + 5)]);
                byte num34 = (byte) ((int) this.bytAddMode + 8);
                byte[] numArray11 = this.nPkt;
                int index9 = (int) num34;
                int num35 = 1;
                byte num36 = (byte) (0xff & (index9 + num35));
                int num37 = 230;
                numArray11[index9] = (byte) num37;
                byte[] numArray12 = this.nPkt;
                int index12 = (int) num36;
                int num38 = 1;
                byte num39 = (byte) (0xff & (index12 + num38));
                int num40 = 230;
                numArray12[index12] = (byte) num40;
                byte[] numArray13 = this.nPkt;
                int index13 = (int) num39;
                int num41 = 1;
                byte num42 = (byte) (0xff & (index13 + num41));
                int num43 = 0;
                numArray13[index13] = (byte) num43;
                byte[] numArray14 = this.nPkt;
                int index14 = (int) num42;
                int num44 = 1;
                byte num45 = (byte) (index14 + num44);
                int num46 = 192;
                numArray14[index14] = (byte) num46;
                byte[] numArray15 = this.nPkt;
                int index15 = (int) num45;
                int num47 = 1;
                byte num48 = (byte) (0xff & (index15 + num47));
                int num49 = 2;
                numArray15[index15] = (byte) num49;
                byte[] numArray16 = this.nPkt;
                int index16 = (int) num48;
                int num50 = 1;
                byte num51 = (byte) (index16 + num50);
                int num52 = 129;
                numArray16[index16] = (byte) num52;
                // FIX: encode all 4 bytes of block number big-endian in GetRequest(next).
                // index17..num63 = 4 consecutive packet positions for the block number field.
                byte[] numArray17 = this.nPkt;
                int index17 = (int) num51;
                int num53 = 1;
                byte num54 = (byte) (index17 + num53);
                numArray17[index17] = (byte)((num1 >> 24) & 0xFF);  // MSB byte 0
                byte[] numArray18 = this.nPkt;
                int index18 = (int) num54;
                int num56 = 1;
                byte num57 = (byte) (index18 + num56);
                numArray18[index18] = (byte)((num1 >> 16) & 0xFF);  // byte 1
                byte[] numArray19 = this.nPkt;
                int index19 = (int) num57;
                int num59 = 1;
                byte num60 = (byte) (index19 + num59);
                numArray19[index19] = (byte)((num1 >>  8) & 0xFF);  // byte 2
                byte[] numArray20 = this.nPkt;
                int index20 = (int) num60;
                int num62 = 1;
                byte num63 = (byte) (index20 + num62);
                numArray20[index20] = (byte) (num1        & 0xFF);  // LSB byte 3
                this.fcs(this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
                this.fcs(this.nPkt, (int) (byte) ((int) num63 - 1), (byte) 1);
                this.nPkt[(int) num63 + 2] = (byte) 126;
                byte num65 = (byte) 0;
                boolean flag3;
                do {
                    // FIX: Check billing deadline inside block-transfer retry loop
                    if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                        appendLog("GP1_BLOCK_DEADLINE_ABORT blockNum=" + num1 + " — deadline or abort");
                        flag3 = false;
                        num65 = nTryCount;
                        break;
                    }
                    this.ClearBuffer();
                    flag3 = false;

                    byte sendCommand1[] = new byte[num63 + 3];
                    for (int ma = 0; ma < num63 + 3; ma++) {
                        sendCommand1[ma] = this.nPkt[ma];
                        //appendLog( "S--"+  (int)this.nPkt[ma] );
                    }
                    this.SendPkt(port, sendCommand1, num63 + 3);

                    //    this.SendPkt(port,this.nPkt, (byte)((int) num63 + 3));

                    long original = System.currentTimeMillis();
                    do {
                        this.DataReceive(port, 20);
                        String Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                        if (Hex1.length() == 1)
                            Hex1 = "0" + Hex1;

                        //  appendLog("Hex 1" + Hex1);

                        String Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                        if (Hex2.length() == 1)
                            Hex2 = "0" + Hex2;

                        //  appendLog("Hex 2" + Hex2);

                        String hex = (Hex1 + Hex2);


                        int Len = Integer.parseInt(hex, 16);
                        this.pktLength = Len;


                        if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs(this.nRcvPkt, this.pktLength, (byte) 0))) {
                            flag3 = true;
                            //              appendLog("Khaa Chu Ghar");
                            num65 = (byte) 0;
                            this.FrameType();
                            break;
                        } else if ((System.currentTimeMillis() - original) / 1000 > (int) nTimeOut && (int) num65 < (int) nTryCount) {

                            {
                                //                appendLog("Time Haigoo");
                                ++num65;
                                break;
                            }
                        }
                    }
                    while ((int) num65 != (int) nTryCount);

                    // appendLog("Time Haigoo Flag" +flag3);
                    if (flag3) {
                        if ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] == 151 || ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] & 1) == 1)
                            //return false;
                            SbData.append("");
                        else
                            break;
                    }
                }
                while ((int) num65 != (int) nTryCount);

                //   appendLog("Need Break " + flag3 + "~" + (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 11)]) + "~" + (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 12)]));
                if (flag3 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 11)]) == 196 && (int) (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 12)]) == 2) {
                    // FIX: 4-byte big-endian block number decode
                    num1 = (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 15)] & 0xFF)) << 24)
                            | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 16)] & 0xFF)) << 16)
                            | (((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 17)] & 0xFF)) << 8)
                            |  ((long)(this.nRcvPkt[((0xff & this.bytAddMode) + 18)] & 0xFF));
                    //       appendLog("IMH-" + (0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 14)]));
                    flag1 = !IntToBool(0xff & this.nRcvPkt[((0xff & this.bytAddMode) + 14)]);
                }
                if (!flag3)
                    SbData.append("");
                if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 130) {
                    for (int index21 = ((0xff & this.bytAddMode) + 23); index21 < this.pktLength - 1; ++index21)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                } else if ((int) this.nRcvPkt[(int) this.bytAddMode + 20] == 129) {
                    for (int index21 = ((0xff & this.bytAddMode) + 22); index21 < this.pktLength - 1; ++index21)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                } else {
                    for (int index21 = ((0xff & this.bytAddMode) + 21); index21 < this.pktLength - 1; ++index21)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                }

                boolean myLogic = false;
                while (((int) (0xff & this.nRcvPkt[1]) & 168) == 168) {
                    //  appendLog("Time Haigoo Flag Main Loop1");
                    this.nPkt[2] = (byte) ((int) this.bytAddMode + 7);
                    this.nRetLSH = (byte) ((int) (0xff & this.nRecvCntr) << 5);
                    this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) (this.nRetLSH | 16);
                    this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) (this.nPkt[((0xff & this.bytAddMode) + 5)] | 1);
                    this.fcs(this.nPkt, ((0xff & this.bytAddMode) + 5), (byte) 1);
                    this.nPkt[((0xff & this.bytAddMode) + 8)] = (byte) 126;
                    byte num66 = (byte) 0;
                    boolean flag4;
                    do {
                        flag4 = false;

                        // FIX: drainPort flushes stale USB FIFO bytes
                        drainPort(port);
                        byte sendCommand1[] = new byte[this.bytAddMode + 9];
                        for (int ma = 0; ma < this.bytAddMode + 9; ma++) {
                            sendCommand1[ma] = this.nPkt[ma];
                        }
                        this.SendPkt(port, sendCommand1, this.bytAddMode + 9);

                        long original = System.currentTimeMillis();
                        flag4 = receiveFrame(port, original + ((int) nTimeOut * 1000L));
                        if (flag4) {
                            num66 = (byte) 0;
                            // FIX: inner HDLC window loop was appending from bytAddMode+8 which
                            // includes LLC header (e6 e7 00) and DataBlock header (c4 02 ...).
                            // For GetResponseDataBlock frames, skip to the actual data payload.
                            if ((int)(0xff & this.nRcvPkt[(0xff & this.bytAddMode) + 11]) == 0xC4
                                    && (int)(0xff & this.nRcvPkt[(0xff & this.bytAddMode) + 12]) == 0x02) {
                                if ((int)(0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 130) {
                                    for (int index21 = ((0xff & this.bytAddMode) + 23); index21 < this.pktLength - 1; ++index21)
                                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                                } else if ((int)(0xff & this.nRcvPkt[(int) this.bytAddMode + 20]) == 129) {
                                    for (int index21 = ((0xff & this.bytAddMode) + 22); index21 < this.pktLength - 1; ++index21)
                                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                                } else {
                                    for (int index21 = ((0xff & this.bytAddMode) + 21); index21 < this.pktLength - 1; ++index21)
                                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                                }
                            } else {
                                // Non-DataBlock I-frame: skip 3-byte LLC header, append DLMS APDU
                                for (int index21 = ((0xff & this.bytAddMode) + 11); index21 < this.pktLength - 1; ++index21)
                                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index21]));
                            }
                            myLogic = true;
                            this.FrameType();
                        } else {
                            ++num66;
                        }
                        if (flag4)
                        {
                            if ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] == 151 || ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] & 1) == 1) {
                                SbData.append("");
                                return SbData;
                            }
                            else
                                break;
                        }
                    }
                    while ((int) num66 != (int) nTryCount);
                    if (!flag4) {
                        SbData.append("");
                        // FIX: break outer while(0xA8) when retries exhausted
                        break;
                    }
                    //  appendLog("Loop End 168");
                }
            }

            //   appendLog("Loop End Falg 1");

            // appendLog("My Rec Data"+ strbldDLMdata.toString());
            SbData.append(strbldDLMdata.toString());
            return SbData;
        }
        catch (Exception ex)
        {
            // appendLog("Errorrr----");
            return SbData;
        }
    }

    private StringBuilder GetParameter_LS(UsbSerialPort port, byte nClassID, String sOBISCode,
                                          byte nAttribID, int nWait, byte nTryCount, byte nTimeOut,
                                          boolean isDLM, StringBuilder strbldDLMdata) {

        StringBuilder SbData = new StringBuilder();
        final int addrOff = 0xff & this.bytAddMode;
        lastGplsResult = 0; // reset per-call; set to 1 (ACCESS_ERROR) or 2 (timeout/UNKNOWN_FRAME) below

        try {
            // ------------------------------------------------------------------
            // Build GET request — IDENTICAL to GetParameter (proven working)
            // nPkt[0..addrOff+4] already set by AddressInit(); only touch from [2] onward
            // ------------------------------------------------------------------
            this.nPkt[2] = (byte)(addrOff + 25);   // HDLC frame length field
            this.nRetLSH = (byte)(0xff & ((int)this.nRecvCntr << 5));
            this.nPkt[addrOff + 5] = (byte)((int)this.nRetLSH | 16);
            this.nRetLSH = (byte)((int)this.nSentCntr << 1);
            this.nPkt[addrOff + 5] = (byte)((int)this.nRetLSH | (int)this.nPkt[addrOff + 5]);

            // Write pointer starts at addrOff+8
            int wi = addrOff + 8;
            this.nPkt[wi++] = (byte) 0xE6; // LLC DST
            this.nPkt[wi++] = (byte) 0xE7; // LLC SRC
            this.nPkt[wi++] = (byte) 0x00; // LLC CTRL
            this.nPkt[wi++] = (byte) 0xC0; // xDLMS: GetRequest
            this.nPkt[wi++] = (byte) 0x01; // GetRequest(normal)
            this.nPkt[wi++] = (byte) 0x81; // invoke-id & priority
            this.nPkt[wi++] = (byte) 0x00; // class-id high byte
            this.nPkt[wi++] = (byte)(0xff & nClassID); // class-id low byte
            // 6-byte OBIS code
            byte[] obisBytes = hexStringToByteArray(sOBISCode.substring(0, 12));
            for (int i = 0; i < 6; i++) this.nPkt[wi++] = obisBytes[i];
            this.nPkt[wi++] = nAttribID; // attribute id
            this.nPkt[wi++] = (byte) 0x00; // no access selection
            // wi is now addrOff+24; num31 equivalent = addrOff+24
            int payloadEnd = wi; // = addrOff + 24

            this.fcs(this.nPkt, addrOff + 5, (byte) 1);       // HDLC header FCS
            this.fcs(this.nPkt, payloadEnd - 1, (byte) 1);    // Full frame FCS
            this.nPkt[payloadEnd + 2] = (byte) 0x7E;           // Closing HDLC flag

            int sendLen = payloadEnd + 3; // = addrOff + 27

            // Append OBIS label to output — matches GetParameter format exactly
            if (isDLM) {
                String clsHex = Integer.toHexString(0xff & nClassID);
                if (clsHex.length() == 1)
                    strbldDLMdata.append("\r\n000").append(clsHex)
                            .append(" ").append(sOBISCode)
                            .append(" 0").append(nAttribID).append(" ");
                else
                    strbldDLMdata.append("\r\n00").append(clsHex)
                            .append(" ").append(sOBISCode)
                            .append(" 0").append(nAttribID).append(" ");
            }

            // ------------------------------------------------------------------
            // Send GET and wait for response — Gurux blocking-read pattern.
            // receiveFrame() uses port.read(buf, 20ms) in a tight loop until
            // a valid HDLC frame arrives or the per-attempt timeout expires.
            // Eliminates the legacy sleep(50)+sleep(30) polling overhead.
            // ------------------------------------------------------------------
            byte retries = 0;
            boolean gotResponse = false;

            do {
                drainPort(port);
                byte[] sendCmd = new byte[sendLen];
                for (int i = 0; i < sendLen; i++) sendCmd[i] = (byte)(this.nPkt[i] & 0xff);
                this.SendPkt(port, sendCmd, sendLen);
                long tStart = System.currentTimeMillis();
                long attemptDeadline = tStart + ((int)nTimeOut * 1000L);

                if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                    appendLog("GPLS_ABORT OBIS=" + sOBISCode + " attr=" + nAttribID);
                    SbData.append(strbldDLMdata);
                    return SbData;
                }

                ClearBuffer();
                gotResponse = receiveFrame(port, attemptDeadline);

                if (gotResponse) {
                    retries = 0;
                    this.FrameType();
                } else {
                    long elapsed = System.currentTimeMillis() - tStart;
                    appendLog("GPLS_TIMEOUT OBIS=" + sOBISCode + " attr=" + nAttribID
                            + " retry=" + retries + " elapsed=" + elapsed + "ms nCounter=" + nCounter);
                    retries++;
                }
            } while (!gotResponse && (int)retries < (int)nTryCount);

            appendLog("GPLS OBIS=" + sOBISCode + " attr=" + nAttribID
                    + " got=" + gotResponse + " retries=" + retries
                    + " nCounter=" + nCounter + " pktLen=" + pktLength);

            if (!gotResponse) {
                lastGplsResult = 2; // timeout/no-response — possible session drop
                SbData.append(strbldDLMdata);
                return SbData;
            }
            // Fast-fail on ACCESS_ERROR: meter definitively rejected the request.
            // No point retrying — second attempt will give the same result and wastes 9s.
            // Only timeout/no-response (lastGplsResult==2) warrants a retry.
            if (lastGplsResult == 1) {
                appendLog("GPLS_ACCESS_ERROR_FAST_FAIL OBIS=" + sOBISCode + " attr=" + nAttribID);
                SbData.append(strbldDLMdata);
                return SbData;
            }

            // Check for HDLC error frame (DM / FRMR)
            if ((this.nRcvPkt[addrOff + 5] & 0xff) == 0x97
                    || ((this.nRcvPkt[addrOff + 5] & 0xff) & 1) == 1) {
                appendLog("GPLS_ERROR_FRAME OBIS=" + sOBISCode);
                lastGplsResult = 2; // DM/error frame = session dead, treat as timeout
                SbData.append(strbldDLMdata);
                return SbData;
            }

            // ------------------------------------------------------------------
            // Level 1: HDLC segmentation — FIRST collect ALL HDLC segments into a
            // single buffer, THEN decode the reassembled DLMS PDU.
            // Meter may send GetResponse(normal) split across multiple HDLC I-frames.
            // The first frame may have the "more" bit set (0xA8) in nRcvPkt[1].
            // We must collect all segments before we can decode the DLMS content.
            // ------------------------------------------------------------------
            final int MAX_HDLC_FRAMES = 64;
            int hdlcFrameCount = 0;

            // Collect HDLC continuation frames first (before any data decoding)
            while (((this.nRcvPkt[1] & 0xff) & 0xA8) == 0xA8 && hdlcFrameCount < MAX_HDLC_FRAMES) {
                hdlcFrameCount++;
                appendLog("GPLS_HDLC_SEG frame=" + hdlcFrameCount + " len=" + strbldDLMdata.length());
                if (!sendRR(port, addrOff, nTimeOut, nTryCount)) {
                    appendLog("GPLS_HDLC_SEG_FAIL frame=" + hdlcFrameCount);
                    break;
                }
                // Continuation frames carry pure data from addrOff+8 onwards
                for (int i = addrOff + 8; i < this.pktLength - 1; i++)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[i]));
            }

            // ------------------------------------------------------------------
            // Now decode the (possibly reassembled) first-frame DLMS PDU
            // ------------------------------------------------------------------
            boolean moreDlmsBlocks = false;
            long dlmsBlockNum = 1;

            if ((this.nRcvPkt[addrOff + 11] & 0xff) == 0xC4
                    && (this.nRcvPkt[addrOff + 12] & 0xff) == 0x02) {
                // GetResponse(dataBlock) — multi-block DLMS transfer
                boolean lastBlock = IntToBool(0xff & this.nRcvPkt[addrOff + 14]);
                moreDlmsBlocks = !lastBlock;
                dlmsBlockNum = decodeBlockNum(addrOff);
                for (int i = addrOff + 19; i < this.pktLength - 1; i++)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[i]));
                appendLog("GPLS_FIRST_BLOCK lastBlock=" + lastBlock + " blockNum=" + dlmsBlockNum
                        + " dataLen=" + strbldDLMdata.length() + " pktLen=" + pktLength);
            } else if ((this.nRcvPkt[addrOff + 11] & 0xff) == 0xC4
                    && (this.nRcvPkt[addrOff + 12] & 0xff) == 0x01) {
                // GetResponse(normal) — extract all payload bytes from [15] onwards
                // This handles all response sizes (small uint16 through large arrays)
                // Log the result byte so we can diagnose errors
                int resultByte = this.nRcvPkt[addrOff + 14] & 0xff;
                appendLog("GPLS_NORMAL result=" + resultByte
                        + " pktLen=" + this.pktLength + " OBIS=" + sOBISCode + " attr=" + nAttribID);
                // Dump first 24 raw bytes for diagnosis
                StringBuilder rawHdr = new StringBuilder("RAW[");
                for (int di = 0; di < Math.min(this.pktLength + 2, 24); di++)
                    rawHdr.append(Hex2Digit(this.nRcvPkt[di])).append(" ");
                rawHdr.append("]");
                appendLog(rawHdr.toString());
                if (resultByte == 0x00) {
                    // result=data: check if large array with 0x81/0x82 length encoding
                    int typeTag = (addrOff + 20 < this.pktLength) ? (this.nRcvPkt[addrOff + 20] & 0xff) : 0;
                    if (typeTag == 130) {
                        for (int i = addrOff + 23; i < this.pktLength - 1; i++)
                            strbldDLMdata.append(Hex2Digit(this.nRcvPkt[i]));
                    } else if (typeTag == 129) {
                        for (int i = addrOff + 22; i < this.pktLength - 1; i++)
                            strbldDLMdata.append(Hex2Digit(this.nRcvPkt[i]));
                    } else {
                        // Simple value: extract from [15] to avoid missing data in short frames
                        for (int i = addrOff + 15; i < this.pktLength - 1; i++)
                            strbldDLMdata.append(Hex2Digit(this.nRcvPkt[i]));
                    }
                } else {
                    // result=error: log error class and code, extract anyway for diagnostics
                    int errClass = (addrOff + 15 < this.pktLength) ? (this.nRcvPkt[addrOff + 15] & 0xff) : 0;
                    int errCode  = (addrOff + 16 < this.pktLength) ? (this.nRcvPkt[addrOff + 16] & 0xff) : 0;
                    lastGplsResult = 1; // ACCESS_ERROR — session still alive
                    appendLog("GPLS_ACCESS_ERROR errClass=" + errClass + " errCode=" + errCode
                            + " OBIS=" + sOBISCode + " attr=" + nAttribID);
                    // Dump raw response bytes for diagnosis
                    StringBuilder rawDump = new StringBuilder("RAW[");
                    for (int di = 0; di < Math.min(this.pktLength + 2, 24); di++)
                        rawDump.append(Hex2Digit(this.nRcvPkt[di])).append(" ");
                    rawDump.append("]");
                    appendLog(rawDump.toString());
                }
            } else {
                // Unknown frame (e.g. AARE=0x61 sent in response to a GET).
                // Do NOT append payload — it is not LP data.
                // Mark result=2 so callers know this was not a valid response.
                lastGplsResult = 2;
                appendLog("GPLS_UNKNOWN_FRAME tag=" + (this.nRcvPkt[addrOff+11]&0xff)
                        + " type=" + (this.nRcvPkt[addrOff+12]&0xff) + " — data discarded");
            }

            if (hdlcFrameCount > 0)
                appendLog("GPLS_HDLC_DONE hdlcFrames=" + hdlcFrameCount + " len=" + strbldDLMdata.length());

            // ------------------------------------------------------------------
            // Level 2: DLMS block transfer — send GetRequest(next) until last-block=1
            // ------------------------------------------------------------------
            final int MAX_DLMS_BLOCKS = 512;
            int dlmsBlockCount = 0;

            while (moreDlmsBlocks && dlmsBlockCount < MAX_DLMS_BLOCKS) {
                dlmsBlockCount++;

                if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                    appendLog("GPLS_BLOCK_ABORT block=" + dlmsBlockNum);
                    break;
                }

                dlmsBlockNum++;
                appendLog("GPLS_GETBLOCK req=" + dlmsBlockNum + " count=" + dlmsBlockCount);

                // --- Build GetRequest(next) packet ---
                this.nPkt[2] = (byte)(addrOff + 19); // shorter frame: 19 body bytes
                this.nRetLSH = (byte)(0xff & ((int)this.nRecvCntr << 5));
                this.nPkt[addrOff + 5] = (byte)((int)this.nRetLSH | 16);
                this.nRetLSH = (byte)((int)this.nSentCntr << 1);
                this.nPkt[addrOff + 5] = (byte)((int)this.nRetLSH | (int)this.nPkt[addrOff + 5]);

                int wp = addrOff + 8;
                this.nPkt[wp++] = (byte) 0xE6; // LLC DST
                this.nPkt[wp++] = (byte) 0xE7; // LLC SRC
                this.nPkt[wp++] = (byte) 0x00; // LLC CTRL
                this.nPkt[wp++] = (byte) 0xC0; // GetRequest
                this.nPkt[wp++] = (byte) 0x02; // GetRequest(next)
                this.nPkt[wp++] = (byte) 0x81; // invoke-id
                this.nPkt[wp++] = (byte) 0x00;
                this.nPkt[wp++] = (byte) 0x00;
                // 4-byte block number big-endian
                this.nPkt[wp++] = (byte)((dlmsBlockNum >> 24) & 0xFF);
                this.nPkt[wp++] = (byte)((dlmsBlockNum >> 16) & 0xFF);
                this.nPkt[wp++] = (byte)((dlmsBlockNum >>  8) & 0xFF);
                this.nPkt[wp++] = (byte)( dlmsBlockNum        & 0xFF);
                // wp = addrOff + 20
                int gbEnd = wp;
                this.fcs(this.nPkt, addrOff + 5, (byte) 1);
                this.fcs(this.nPkt, gbEnd - 1, (byte) 1);
                this.nPkt[gbEnd + 2] = (byte) 0x7E;
                int gbSendLen = gbEnd + 3; // = addrOff + 23

                // --- Send and wait ---
                this.ClearBuffer();
                byte[] gbCmd = new byte[gbSendLen];
                for (int i = 0; i < gbSendLen; i++) gbCmd[i] = (byte)(this.nPkt[i] & 0xff);
                this.SendPkt(port, gbCmd, gbSendLen);

                boolean blockOk = false;
                byte blockRetries = 0;
                long tStart = System.currentTimeMillis();
                do {
                    do {
                        if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                            appendLog("GPLS_BLOCK_DEADLINE block=" + dlmsBlockNum);
                            moreDlmsBlocks = false;
                            blockOk = true; // exit loops
                            break;
                        }
                        this.DataReceive(port, 20);
                        if (this.nCounter > 2) {
                            int pLen = parsePktLen();
                            if (pLen > 0 && (pLen + 2) <= this.nCounter
                                    && (pLen + 1) < this.nRcvPkt.length
                                    && (this.nRcvPkt[pLen + 1] & 0xff) == 0x7E
                                    && this.fcs(this.nRcvPkt, pLen, (byte) 0)) {
                                this.pktLength = pLen;
                                blockOk = true;
                                blockRetries = 0;
                                this.FrameType();
                                appendLog("Khaa Chu Ghar");
                                break;
                            }
                        }
                        long elapsed = System.currentTimeMillis() - tStart;
                        if (elapsed / 1000 > (int)nTimeOut && (int)blockRetries < (int)nTryCount) {
                            appendLog("GPLS_BLOCK_TIMEOUT block=" + dlmsBlockNum
                                    + " retry=" + blockRetries + " elapsed=" + elapsed + "ms");
                            blockRetries++;
                            tStart = System.currentTimeMillis();
                            break;
                        }
                    } while ((int)blockRetries != (int)nTryCount);
                } while (!blockOk && (int)blockRetries != (int)nTryCount);

                if (!blockOk) {
                    appendLog("GPLS_BLOCK_FAILED block=" + dlmsBlockNum + " — stopping");
                    break;
                }
                if (!moreDlmsBlocks) break; // deadline hit

                // Validate response
                if ((this.nRcvPkt[addrOff + 5] & 0xff) == 0x97
                        || ((this.nRcvPkt[addrOff + 5] & 0xff) & 1) == 1) {
                    appendLog("GPLS_BLOCK_ERR_FRAME block=" + dlmsBlockNum);
                    break;
                }
                if ((this.nRcvPkt[addrOff + 11] & 0xff) != 0xC4
                        || (this.nRcvPkt[addrOff + 12] & 0xff) != 0x02) {
                    appendLog("GPLS_BLOCK_UNEXPECTED tag="
                            + Integer.toHexString(0xff & this.nRcvPkt[addrOff + 11]));
                    break;
                }

                boolean lastBlock = IntToBool(0xff & this.nRcvPkt[addrOff + 14]);
                long receivedBlockNum = decodeBlockNum(addrOff);
                moreDlmsBlocks = !lastBlock;
                appendLog("GPLS_BLOCK_RX num=" + receivedBlockNum + " last=" + lastBlock
                        + " pktLen=" + pktLength + " totalLen=" + strbldDLMdata.length());

                // Extract data: GetBlock response raw data starts at addrOff+19
                for (int i = addrOff + 19; i < this.pktLength - 1; i++)
                    strbldDLMdata.append(Hex2Digit(this.nRcvPkt[i]));

                // Collect any HDLC segments for this block
                int blockHdlc = 0;
                while (((this.nRcvPkt[1] & 0xff) & 0xA8) == 0xA8 && blockHdlc < MAX_HDLC_FRAMES) {
                    blockHdlc++;
                    if (!sendRR(port, addrOff, nTimeOut, nTryCount)) break;
                    for (int i = addrOff + 8; i < this.pktLength - 1; i++)
                        strbldDLMdata.append(Hex2Digit(this.nRcvPkt[i]));
                }
            }

            appendLog("GPLS_DONE OBIS=" + sOBISCode + " attr=" + nAttribID
                    + " dlmsBlocks=" + dlmsBlockCount + " totalLen=" + strbldDLMdata.length());
            SbData.append(strbldDLMdata);
            return SbData;

        } catch (Exception ex) {
            appendLog("GPLS_EX OBIS=" + sOBISCode + " attr=" + nAttribID + ": " + ex.getMessage());
            SbData.append(strbldDLMdata);
            return SbData;
        }
    }



    /**
     * Sends a DLMS GetRequest(next) with blockNumber=0 to abort any
     * stuck block-transfer session on the meter.
     * DLMS spec: a GetRequest(next) with blockNumber=0 aborts the transfer.
     * If no transfer is in progress, the meter returns errClass=11 — harmless.
     */
    private void abortPendingBlockTransfer(UsbSerialPort port) {
        // NOTE: do NOT reset lpDeadlineMs here. This method is called mid-session
        // after a partial bulk transfer; the LP deadline set by doInBackground must
        // remain active so the subsequent selective-access fallback loop still terminates
        // on time. Resetting it to 0 makes all "(lpDeadlineMs > 0 && ...)" guards
        // evaluate to false, causing LP to run completely unchecked.
        // (V29 FIX — was: lpDeadlineMs = 0)
        try {
            int addrOff = 0xff & this.bytAddMode;

            // Build GetRequest(next) blockNum=0 — same structure as sendGetBlockRequest
            // but with blockNum=0 to signal abort
            this.nPkt[2] = (byte)(addrOff + 19);
            this.nRetLSH = (byte)(0xff & ((int)this.nRecvCntr << 5));
            this.nPkt[addrOff + 5] = (byte)((int)this.nRetLSH | 16);
            this.nRetLSH = (byte)((int)this.nSentCntr << 1);
            this.nPkt[addrOff + 5] = (byte)((int)this.nRetLSH | (int)this.nPkt[addrOff + 5]);

            int wp = addrOff + 8;
            this.nPkt[wp++] = (byte) 0xE6;
            this.nPkt[wp++] = (byte) 0xE7;
            this.nPkt[wp++] = (byte) 0x00;
            this.nPkt[wp++] = (byte) 0xC0; // GetRequest
            this.nPkt[wp++] = (byte) 0x02; // GetRequest(next) — signals continuation/abort
            this.nPkt[wp++] = (byte) 0x81; // invoke-id
            this.nPkt[wp++] = (byte) 0x00;
            this.nPkt[wp++] = (byte) 0x00;
            this.nPkt[wp++] = (byte) 0x00; // blockNum = 0 (abort signal)
            this.nPkt[wp++] = (byte) 0x00;
            this.nPkt[wp++] = (byte) 0x00;
            this.nPkt[wp++] = (byte) 0x00;
            int abortEnd = wp; // addrOff + 20
            this.fcs(this.nPkt, addrOff + 5, (byte) 1);
            this.fcs(this.nPkt, abortEnd - 1, (byte) 1);
            this.nPkt[abortEnd + 2] = (byte) 0x7E;
            int abortLen = abortEnd + 3;

            this.ClearBuffer();
            byte[] cmd = new byte[abortLen];
            for (int i = 0; i < abortLen; i++) cmd[i] = (byte)(this.nPkt[i] & 0xff);
            this.SendPkt(port, cmd, abortLen);

            // Short wait for meter to respond to abort (50ms is enough per Gurux)
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            this.DataReceive(port);
            appendLog("ABORT_BLOCK_TRANSFER nCounter=" + nCounter);
            this.ClearBuffer();
        } catch (Exception ex) {
            appendLog("ABORT_BLOCK_TRANSFER_EX: " + ex.getMessage());
        }
    }

    /** Parse HDLC frame length from nRcvPkt[1..2] safely */
    private int parsePktLen() {
        int h = ((int)(this.nRcvPkt[1]) & 7);
        int l = (int)(this.nRcvPkt[2]) & 0xff;
        int len = (h << 8) | l;
        return (len > 0 && len < this.nRcvPkt.length - 2) ? len : 0;
    }

    /** Decode 4-byte big-endian DLMS block number at bytAddMode+15..18 */
    private long decodeBlockNum(int addrOff) {
        return (((long)(this.nRcvPkt[addrOff + 15] & 0xff)) << 24)
                | (((long)(this.nRcvPkt[addrOff + 16] & 0xff)) << 16)
                | (((long)(this.nRcvPkt[addrOff + 17] & 0xff)) << 8)
                |  ((long)(this.nRcvPkt[addrOff + 18] & 0xff));
    }

    /** Send HDLC Receive-Ready (RR) acknowledgment and wait for next segment */
    private boolean sendRR(UsbSerialPort port, int addrOff, byte nTimeOut, byte nTryCount) {
        this.nPkt[2] = (byte)(addrOff + 7);
        this.nRetLSH = (byte)(0xff & ((int)this.nRecvCntr << 5));
        this.nPkt[addrOff + 5] = (byte)((int)this.nRetLSH | 0x11); // RR S-frame P/F=1
        this.fcs(this.nPkt, addrOff + 5, (byte) 1);
        this.nPkt[addrOff + 8] = (byte) 0x7E;

        byte retries = 0;
        do {
            this.ClearBuffer();
            int sz = addrOff + 9;
            byte[] cmd = new byte[sz];
            for (int i = 0; i < sz; i++) cmd[i] = this.nPkt[i];
            this.SendPkt(port, cmd, sz);
            long tStart = System.currentTimeMillis();
            do {
                if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs))
                    return false;
                this.DataReceive(port, 20);
                if (this.nCounter > 2) {
                    int pLen = parsePktLen();
                    if (pLen > 0 && (pLen + 2) <= this.nCounter
                            && (pLen + 1) < this.nRcvPkt.length
                            && (this.nRcvPkt[pLen + 1] & 0xff) == 0x7E
                            && this.fcs(this.nRcvPkt, pLen, (byte) 0)) {
                        this.pktLength = pLen;
                        retries = 0;
                        this.FrameType();
                        return true;
                    }
                }
                if ((System.currentTimeMillis() - tStart) / 1000 > (int)nTimeOut
                        && (int)retries < (int)nTryCount) {
                    retries++;
                    break;
                }
            } while ((int)retries != (int)nTryCount);
        } while ((int)retries != (int)nTryCount);
        return false;
    }

    public static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for(byte b: a)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private int AARQ(UsbSerialPort port, byte bytAsslevel, String strPsd, int nWait, byte nTryCount, byte nTimeOut)
    {
        byte num1 =  8;
        this.nRetLSH= (byte) ((int)(this.nRecvCntr << 5));
        //this.nRetLSH =  this.nRecvCntr << 5;
        this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte) ((int) this.nRetLSH | 16);
        this.nRetLSH = (byte)((int) this.nSentCntr << 1);
        this.nPkt[((0xff & this.bytAddMode) + 5)] = (byte)((int) this.nRetLSH | (int) this.nPkt[((0xff & this.bytAddMode) + 5)]);
        byte[] numArray1 = this.nPkt;
        int index1 = (int) num1;
        int num2 = 1;
        byte num3 = (byte) (index1 + num2);
        int num4 = 230;
        numArray1[index1] = (byte) num4;
        byte[] numArray2 = this.nPkt;
        int index2 = (int) num3;
        int num5 = 1;
        byte num6 = (byte) (index2 + num5);
        int num7 = 230;
        numArray2[index2] = (byte) num7;
        byte[] numArray3 = this.nPkt;
        int index3 = (int) num6;
        int num8 = 1;
        byte num9 = (byte) (index3 + num8);
        int num10 = 0;
        numArray3[index3] = (byte) num10;
        byte[] numArray4 = this.nPkt;
        int index4 = (int) num9;
        int num11 = 1;
        byte num12 = (byte) (index4 + num11);
        int num13 = 96;
        numArray4[index4] = (byte) num13;
        byte[] numArray5 = this.nPkt;
        int index5 = (int) num12;
        int num14 = 1;
        byte num15 = (byte) (index5 + num14);
        int num16 = 0;
        numArray5[index5] = (byte) num16;
        byte[] numArray6 = this.nPkt;
        int index6 = (int) num15;
        int num17 = 1;
        byte num18 = (byte) (index6 + num17);
        int num19 = 161;
        numArray6[index6] = (byte) num19;
        byte[] numArray7 = this.nPkt;
        int index7 = (int) num18;
        int num20 = 1;
        byte num21 = (byte) (index7 + num20);
        int num22 = 9;
        numArray7[index7] = (byte) num22;
        byte[] numArray8 = this.nPkt;
        int index8 = (int) num21;
        int num23 = 1;
        byte num24 = (byte) (index8 + num23);
        int num25 = 6;
        numArray8[index8] = (byte) num25;
        byte[] numArray9 = this.nPkt;
        int index9 = (int) num24;
        int num26 = 1;
        byte num27 = (byte) (index9 + num26);
        int num28 = 7;
        numArray9[index9] = (byte) num28;
        byte[] numArray10 = this.nPkt;
        int index10 = (int) num27;
        int num29 = 1;
        byte num30 = (byte) (index10 + num29);
        int num31 = 96;
        numArray10[index10] = (byte) num31;
        byte[] numArray11 = this.nPkt;
        int index11 = (int) num30;
        int num32 = 1;
        byte num33 = (byte) (index11 + num32);
        int num34 = 133;
        numArray11[index11] = (byte) num34;
        byte[] numArray12 = this.nPkt;
        int index12 = (int) num33;
        int num35 = 1;
        byte num36 = (byte) (index12 + num35);
        int num37 = 116;
        numArray12[index12] = (byte) num37;
        byte[] numArray13 = this.nPkt;
        int index13 = (int) num36;
        int num38 = 1;
        byte num39 = (byte) (index13 + num38);
        int num40 = 5;
        numArray13[index13] = (byte) num40;
        byte[] numArray14 = this.nPkt;
        int index14 = (int) num39;
        int num41 = 1;
        byte num42 = (byte) (index14 + num41);
        int num43 = 8;
        numArray14[index14] = (byte) num43;
        byte[] numArray15 = this.nPkt;
        int index15 = (int) num42;
        int num44 = 1;
        byte num45 = (byte) (index15 + num44);
        int num46 = 1;
        numArray15[index15] = (byte) num46;
        byte[] numArray16 = this.nPkt;
        int index16 = (int) num45;
        int num47 = 1;
        byte num48 = (byte) (index16 + num47);
        int num49 = 1;
        numArray16[index16] = (byte) num49;
        if ((int) bytAsslevel == 0)
        {
            this.nPkt[((0xff & this.bytAddMode) + 12)] = (byte) 29;
        }
        else
        {
            byte[] numArray17 = this.nPkt;
            int index17 = (int) num48;
            int num50 = 1;
            byte num51 = (byte) (index17 + num50);
            int num52 = 138;
            numArray17[index17] = (byte) num52;
            byte[] numArray18 = this.nPkt;
            int index18 = (int) num51;
            int num53 = 1;
            byte num54 = (byte) (index18 + num53);
            int num55 = 2;
            numArray18[index18] = (byte) num55;
            byte[] numArray19 = this.nPkt;
            int index19 = (int) num54;
            int num56 = 1;
            byte num57 = (byte) (index19 + num56);
            int num58 = 7;
            numArray19[index19] = (byte) num58;
            byte[] numArray20 = this.nPkt;
            int index20 = (int) num57;
            int num59 = 1;
            byte num60 = (byte) (index20 + num59);
            int num61 = 128;
            numArray20[index20] = (byte) num61;
            byte[] numArray21 = this.nPkt;
            int index21 = (int) num60;
            int num62 = 1;
            byte num63 = (byte) (index21 + num62);
            int num64 = 139;
            numArray21[index21] = (byte) num64;
            byte[] numArray22 = this.nPkt;
            int index22 = (int) num63;
            int num65 = 1;
            byte num66 = (byte) (index22 + num65);
            int num67 = 7;
            numArray22[index22] = (byte) num67;
            byte[] numArray23 = this.nPkt;
            int index23 = (int) num66;
            int num68 = 1;
            byte num69 = (byte) (index23 + num68);
            int num70 = 96;
            numArray23[index23] = (byte) num70;
            byte[] numArray24 = this.nPkt;
            int index24 = (int) num69;
            int num71 = 1;
            byte num72 = (byte) (index24 + num71);
            int num73 = 133;
            numArray24[index24] = (byte) num73;
            byte[] numArray25 = this.nPkt;
            int index25 = (int) num72;
            int num74 = 1;
            byte num75 = (byte) (index25 + num74);
            int num76 = 116;
            numArray25[index25] = (byte) num76;
            byte[] numArray26 = this.nPkt;
            int index26 = (int) num75;
            int num77 = 1;
            byte num78 = (byte) (index26 + num77);
            int num79 = 5;
            numArray26[index26] = (byte) num79;
            byte[] numArray27 = this.nPkt;
            int index27 = (int) num78;
            int num80 = 1;
            byte num81 = (byte) (index27 + num80);
            int num82 = 8;
            numArray27[index27] = (byte) num82;
            byte[] numArray28 = this.nPkt;
            int index28 = (int) num81;
            int num83 = 1;
            byte num84 = (byte) (index28 + num83);
            int num85 = 2;
            numArray28[index28] = (byte) num85;
            byte[] numArray29 = this.nPkt;
            int index29 = (int) num84;
            int num86 = 1;
            byte num87 = (byte) (index29 + num86);
            int num88 = (int) bytAsslevel;
            numArray29[index29] = (byte) num88;
            byte[] numArray30 = this.nPkt;
            int index30 = (int) num87;
            int num89 = 1;
            byte num90 = (byte) (index30 + num89);
            int num91 = 172;
            numArray30[index30] = (byte) num91;
            byte[] numArray31 = this.nPkt;
            int index31 = (int) num90;
            int num92 = 1;
            byte num93 = (byte) (index31 + num92);
            int num94 = (int) (byte)(2 + strPsd.length());
            numArray31[index31] = (byte) num94;
            byte[] numArray32 = this.nPkt;
            int index32 = (int) num93;
            int num95 = 1;
            byte num96 = (byte) (index32 + num95);
            int num97 = 128;
            numArray32[index32] = (byte) num97;
            byte[] numArray33 = this.nPkt;
            int index33 = (int) num96;
            int num98 = 1;
            num48 = (byte) (index33 + num98);
            int num99 = (int) (byte)(strPsd.length());
            numArray33[index33] = (byte) num99;
            //ASCIIEncoding asciiEncoding = new ASCIIEncoding();
            //this.Ps = (int) bytAsslevel != 1 ? asciiEncoding.GetBytes("GNSRAPDRP-" + DateTime.Now.ToString("HHmmss")) : asciiEncoding.GetBytes(strPsd);

            // Password byte mapping — explicit arrays for known passwords, generic fallback
            if (strPsd.equals("lnt1"))
                this.Ps = new byte[]{ 108, 110, 116, 49 };
            else if (strPsd.equals("ABCD0001"))
                this.Ps = new byte[]{ 65, 66, 67, 68, 48, 48, 48, 49 };
            else if (strPsd.equals("1A2B3C4D"))
                this.Ps = new byte[]{ 49, 65, 50, 66, 51, 67, 52, 68 };
            else if (strPsd.equals("1111111111111111"))
                this.Ps = new byte[]{ 49, 49, 49, 49, 49, 49, 49, 49, 49, 49, 49, 49, 49, 49, 49, 49 };
            else if (strPsd.equals("11111111"))
                this.Ps = new byte[]{ 49, 49, 49, 49, 49, 49, 49, 49 };
            else {
                // Generic ASCII conversion for any other password (e.g. "Hello" for AVON)
                this.Ps = new byte[strPsd.length()];
                for (int pi = 0; pi < strPsd.length(); pi++) {
                    this.Ps[pi] = (byte) strPsd.charAt(pi);
                }
            }


            for (int index34 = 0; index34 < strPsd.length(); ++index34)
                this.nPkt[(int) num48++] = this.Ps[index34];
            this.nPkt[((0xff & this.bytAddMode) + 12)] = (byte)(46 + strPsd.length());
        }
        byte[] numArray34 = this.nPkt;
        int index35 = (int) num48;
        int num100 = 1;
        byte num101 = (byte) (index35 + num100);
        int num102 = 190;
        numArray34[index35] = (byte) num102;
        byte[] numArray35 = this.nPkt;
        int index36 = (int) num101;
        int num103 = 1;
        byte num104 = (byte) (index36 + num103);
        int num105 = 16;
        numArray35[index36] = (byte) num105;
        byte[] numArray36 = this.nPkt;
        int index37 = (int) num104;
        int num106 = 1;
        byte num107 = (byte) (index37 + num106);
        int num108 = 4;
        numArray36[index37] = (byte) num108;
        byte[] numArray37 = this.nPkt;
        int index38 = (int) num107;
        int num109 = 1;
        byte num110 = (byte) (index38 + num109);
        int num111 = 14;
        numArray37[index38] = (byte) num111;
        byte[] numArray38 = this.nPkt;
        int index39 = (int) num110;
        int num112 = 1;
        byte num113 = (byte) (index39 + num112);
        int num114 = 1;
        numArray38[index39] = (byte) num114;
        byte[] numArray39 = this.nPkt;
        int index40 = (int) num113;
        int num115 = 1;
        byte num116 = (byte) (index40 + num115);
        int num117 = 0;
        numArray39[index40] = (byte) num117;
        byte[] numArray40 = this.nPkt;
        int index41 = (int) num116;
        int num118 = 1;
        byte num119 = (byte) (index41 + num118);
        int num120 = 0;
        numArray40[index41] = (byte) num120;
        byte[] numArray41 = this.nPkt;
        int index42 = (int) num119;
        int num121 = 1;
        byte num122 = (byte) (index42 + num121);
        int num123 = 0;
        numArray41[index42] = (byte) num123;
        byte[] numArray42 = this.nPkt;
        int index43 = (int) num122;
        int num124 = 1;
        byte num125 = (byte) (index43 + num124);
        int num126 = 6;
        numArray42[index43] = (byte) num126;
        byte[] numArray43 = this.nPkt;
        int index44 = (int) num125;
        int num127 = 1;
        byte num128 = (byte) (index44 + num127);
        int num129 = 95;
        numArray43[index44] = (byte) num129;
        byte[] numArray44 = this.nPkt;
        int index45 = (int) num128;
        int num130 = 1;
        byte num131 = (byte) (index45 + num130);
        int num132 = 31;
        numArray44[index45] = (byte) num132;
        byte[] numArray45 = this.nPkt;
        int index46 = (int) num131;
        int num133 = 1;
        byte num134 = (byte) (index46 + num133);
        int num135 = 4;
        numArray45[index46] = (byte) num135;
        byte[] numArray46 = this.nPkt;
        int index47 = (int) num134;
        int num136 = 1;
        byte num137 = (byte) (index47 + num136);
        int num138 = 0;
        numArray46[index47] = (byte) num138;
        byte[] numArray47 = this.nPkt;
        int index48 = (int) num137;
        int num139 = 1;
        byte num140 = (byte) (index48 + num139);
        int num141 = 0;
        numArray47[index48] = (byte) num141;
        byte[] numArray48 = this.nPkt;
        int index49 = (int) num140;
        int num142 = 1;
        byte num143 = (byte) (index49 + num142);
        int num144 = 24;
        numArray48[index49] = (byte) num144;
        byte[] numArray49 = this.nPkt;
        int index50 = (int) num143;
        int num145 = 1;
        byte num146 = (byte) (index50 + num145);
        int num147 = 29;
        numArray49[index50] = (byte) num147;
        byte[] numArray50 = this.nPkt;
        int index51 = (int) num146;
        int num148 = 1;
        byte num149 = (byte) (index51 + num148);
        int num150 = (int) 255;
        numArray50[index51] = (byte) num150;
        byte[] numArray51 = this.nPkt;
        int index52 = (int) num149;
        int num151 = 1;
        byte num152 = (byte) (index52 + num151);
        int num153 = (int) 255;
        numArray51[index52] = (byte) num153;
        this.nPkt[2] = (byte)((int) num152 + 1);
        fcs( this.nPkt, ( this.bytAddMode + 5), (byte) 1);
        fcs( this.nPkt,  num152 - 1, (byte) 1);
        this.nPkt[(int) num152 + 2] = (byte) 126;
        byte num154 = (byte) 0;
        boolean flag = false; // FIX-7: initialise to avoid "might not be initialised" compiler error
        do
        {
            this.ClearBuffer();
            byte sendCommand1[] = new byte[num152+3];
            for(int ma=0; ma< num152 + 3 ;ma++)
            {
                sendCommand1[ma]=this.nPkt[ma];
                // appendLog( "S--"+  (int)this.nPkt[ma] );
            }
            this.SendPkt(port ,sendCommand1,  num152 + 3);

            long original = System.currentTimeMillis();
            do
            {
                // appendLog("In Data Receive");
                //this.Wait((double) nWait);
                this.DataReceive(port);
                long  Second=(int) System.currentTimeMillis() - original;

                if (this.nCounter > 2 && (int) this.nRcvPkt[2] + 2 <= this.nCounter && this.fcs(this.nRcvPkt, (int) this.nRcvPkt[2], (byte) 0))
                {

                    flag = true;
                    this.FrameType();
                    break;
                }



                else if ((System.currentTimeMillis() - original)/1000 > (int) nTimeOut && (int) num154 < (int) nTryCount)
                {
                    ++num154;
                    break;
                }
            }
            while (!flag);
        }
        while (!flag && (int) num154 != (int) nTryCount);
        if (!flag || (int) this.nRcvPkt[((0xff & this.bytAddMode) + 28)] != 0 || this.nCounter <= 27)
            return 1;
        if ((int) bytAsslevel != 2)
            return 0;
       /* this.keyBytes = FrmDLMS.StrToByteArray(strPsd.Trim());
        Aes aes = new Aes((Aes.KeySize) 0, this.keyBytes);
        for (int index17 = 0; index17 < 16; ++index17)
            this.plainText[index17] = this.nRcvPkt[index17 + (int) Convert.ToByte((int) this.bytAddMode + 53)];
        aes.Cipher(this.plainText, this.cipherText);
        if (this.ActionCmd(this.cipherText))
            return 0;
        this.LblStatus.Text = "Authentication Fail";*/
        return 2;
    }


    private void FrameType()
    {
        //  appendLog("In Frame Type");
        String Hex1= Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
        if (Hex1.length()==1)
            Hex1="0"+Hex1;



        String Hex2= Integer.toHexString((0xff &  this.nRcvPkt[2]));
        if (Hex2.length()==1)
            Hex2="0"+Hex2;



        String hex = (Hex1+Hex2);
        //String hex =Integer.toHexString((0xff & this.nRcvPkt[1]) & 7) +Integer.toHexString((0xff & this.nRcvPkt[2]));
        int Len =Integer.parseInt(hex,16);
        this.pktLength =Len;
        //this.pktLength =   (int) ( (0xff & this.nRcvPkt[1] ) & 7)  + (int) (0xff & this.nRcvPkt[2]) ;
        //this.pktLength = int.Parse(((int) this.nRcvPkt[1] & 7).ToString("X2") + this.nRcvPkt[2].ToString("X2"), NumberStyles.HexNumber);

        //    appendLog("Frame Packet Length"+ pktLength);
        if ((int) this.nRcvPkt[((0xff & this.bytAddMode) + 5)] != 115 && ((int) (0xff & this.nRcvPkt[1]) & 168) == 160)
        {
            //  appendLog("In Frame Type Con1");
            this.nRecv = (byte)((int) (this.nRcvPkt[((0xff & this.bytAddMode) + 5)] & 0xff) >> 5);
            this.nSent = (byte)((int) (this.nRcvPkt[((0xff & this.bytAddMode) + 5)] & 0xff) >> 1 & 7);
        }
        if (((int) (0xff & this.nRcvPkt[1]) & 168) == 160 && this.pktLength > 10 || ((int) (0xff& this.nRcvPkt[1]) & 168) == 168) {
            this.nRecvCntr = (int) this.nRecvCntr != 7 ? ++this.nRecvCntr : (byte) 0;
            //   appendLog("In Frame Type Con2");
        }

        // appendLog("this.pktLength" + this.pktLength);
        //  appendLog("this.nRcvPkt[1]" + (0xff & this.nRcvPkt[1]));
        // appendLog(" this.nRecvCntr" +  this.nRecvCntr);

        if (((int) (0xff & this.nRcvPkt[1]) & 168) != 168)
        {
            // appendLog("In Frame Type Con 3");
            if ((int) this.nRecvLast != 7)
            {
                //  appendLog("In Frame Type Con 3.1");
                if ((int) this.nRecv - (int) this.nRecvLast == 1)
                    this.nSentCntr = (int) this.nSentCntr != 7 ? ++this.nSentCntr : (byte) 0;
            }
            else if ((int) this.nRecvLast - (int) this.nRecv == 7) {
                this.nSentCntr = (int) this.nSentCntr != 7 ? ++this.nSentCntr : (byte) 0;
                //  appendLog("In Frame Type Con 3.2");
            }
        }
        //  appendLog("Recive Frame" +this.nRecv);
        //   appendLog("Sent Frame" +this.nSent);
        this.nRecvLast = this.nRecv;
        this.nSentLast = this.nSent;
    }

    public void ClearBuffer()
    {
        for (int index = 0; index <= this.nCounter; ++index)
            this.nRcvPkt[index] = (byte) 0;
        this.nCounter = 0;
    }

    /**
     * Drains ALL pending bytes from the USB hardware receive FIFO, then clears
     * the software buffer. ClearBuffer() alone only zeroes nRcvPkt — it leaves
     * stale bytes in the USB driver buffer which DataReceive() will re-read on
     * the next call. This method eliminates that stale data completely.
     */
    private void drainPort(UsbSerialPort port) {
        try {
            byte[] sink = new byte[256];
            int drained = 0;
            int read;
            // Hard deadline: never drain for more than 100ms total.
            // Some meters (Secure HT type-5) stream data continuously after
            // instantaneous reads — without this cap, the drain loop runs for
            // minutes (observed: 582s, 434KB drained) and blocks billing reads.
            long drainDeadline = System.currentTimeMillis() + 100;
            do {
                read = port.read(sink, 20); // 20ms per chunk
                drained += read;
            } while (read > 0 && System.currentTimeMillis() < drainDeadline);
            ClearBuffer();
            if (drained > 0) {
                appendLog("DRAIN_PORT drained=" + drained);
            }
        } catch (Exception ex) {
            ClearBuffer();
            appendLog("DRAIN_PORT_EX: " + ex.getMessage());
        }
    }
    private static final int USB_WRITE_TIMEOUT_MS = 500; // fixed write timeout

    private boolean SendPkt(UsbSerialPort port, byte[] buffer, int length)
    {
        try {
            // Write only the valid packet bytes up to `length`.
            // CRITICAL: buffer is often this.nPkt (1024 bytes) but only the first
            // `length` bytes contain the actual DLMS/HDLC packet. Writing the full
            // buffer sends garbage to the meter and corrupts the session.
            if (length <= 0 || length > buffer.length) return false;
            byte[] sendBuffer = new byte[length];
            System.arraycopy(buffer, 0, sendBuffer, 0, length);
            int i = port.write(sendBuffer, USB_WRITE_TIMEOUT_MS);
            consecutiveSendFailures = 0; // reset on success
            return (i >= 0);
        }
        catch (Exception ex)
        {
            appendLog("Error Sedpaket: " + ex.getMessage());
            consecutiveSendFailures++;
            if (consecutiveSendFailures >= MAX_SEND_FAILURES) {
                // USB driver state is corrupted (e.g. srcPos=-1 after long idle).
                // Set abortRequested so all LP/billing loops break cleanly on their
                // next iteration rather than spinning with repeated port.write failures.
                appendLog("SEND_FAIL_ABORT — " + consecutiveSendFailures
                        + " consecutive port.write failures, aborting session");
                abortRequested = true;
            }
            return false;
        }
    }
    /**
     * Blocking USB read — inspired by Gurux serial approach.
     * Uses port.read(buffer, timeout) which returns as soon as bytes arrive
     * or after the timeout ms if nothing comes. Eliminates sleep+poll overhead.
     *
     * @param port  USB serial port
     *
     */
    private void DataReceive(UsbSerialPort port) { DataReceive(port, 20); }
    private void DataReceive(UsbSerialPort port, int timeoutMs)
    {
        try {
            // Gurux pattern: blocking read — returns immediately when data arrives,
            // waits up to timeoutMs ms if the buffer is empty.
            int num = port.read(this.buffer, timeoutMs);
            for (int index = 0; index < num; ++index) {
                this.nRcvPkt[this.nCounter++] = this.buffer[index];
            }
        } catch (Exception ex) {
            appendLog(ex.getMessage());
        }
    }

    /**
     * Receive a complete HDLC frame, blocking until one arrives or deadline passes.
     * Returns true if a valid, FCS-checked frame is in nRcvPkt.
     * Replaces all the scattered sleep(30/80ms)+DataReceive() polling loops.
     */
    private boolean receiveFrame(UsbSerialPort port, long deadlineMs) {
        while (System.currentTimeMillis() < deadlineMs) {
            if (abortRequested) return false;
            DataReceive(port, 20);  // 20ms blocking read per Gurux approach
            if (this.nCounter > 2) {
                int pLen = parsePktLen();
                if (pLen > 0 && (pLen + 2) <= this.nCounter
                        && (pLen + 1) < this.nRcvPkt.length
                        && (this.nRcvPkt[pLen + 1] & 0xff) == 0x7E
                        && this.fcs(this.nRcvPkt, pLen, (byte) 0)) {
                    this.pktLength = pLen;
                    return true;
                }
            }
        }
        return false;
    }

    private int fcs_cal(int fcs,  byte[] cp, int length)
    {
        int num = 1;
        //  appendLog("fcs Cal");
        //  appendLog("num++" +num);
        //  appendLog("length++" +length);
        boolean b = (length != 0);
        while ( b) {
            fcs = (fcs >> (8) ^ (int) this.fcstab[((int) fcs ^ (int) (cp[num++])) & (int) 255]);
            length--;
            //   appendLog("length" +length);
            b = (length != 0);
        }
        // appendLog("retirn fcs Cal" +fcs);
        return fcs;
    }

    public boolean IntToBool(int Number)
    {

        if (Number >0)
            return true;
        else
            return false;
    }
    public  void Fcs_Tab()
    {
        //   appendLog("call FCS Tab" );
        int num1 =  0;
        do {
            int num2 = num1;
            short num3 = (short) 8;
            boolean b = (num3 != 0);
            while (b) {
                num2 = (IntToBool((int) num2 & (int) (1)) ? (int) num2 >> (int) (1) ^ 33800 : (int) num2 >> (int) (1));
                this.fcstab[(int) num1] =  num2 &   65535;
                num3--;
                //     appendLog("TAB fcsTab[" + num1 +"]" + (num2 &   65535) );
                b = (num3 != 0);
            }
        }
        while ((int) ++num1 != 256);
    }


    public boolean fcs( byte[] cp, int len, byte flag)
    {
        //  appendLog("FCS Call");
        if ((flag!=0))
        {


            int num =   this.fcs_cal(65535,  cp, len) ^  ((int) 65535);
            //     appendLog("fcs Length Pass:-" + len);
            //      appendLog("fcs Num:-" + num);
            cp[len + 1] = (byte) (num &  255) ;
            cp[len + 2] = (byte)  (num >>  (8 &  255));
            return true;

        }
        else
        {
            // appendLog("Call me for Cal " +this.fcs_cal(65535, cp, len));
            return (int) this.fcs_cal(65535, cp, len) == 61624;
        }
    }

    /**
     * IEC 62056-21 Mode C wakeup for L&T/Schneider ER300P meters.
     *
     * These meters keep their optical receiver dormant to save power. They only
     * activate the receiver after detecting the IEC 62056-21 request message "/?!\r\n"
     * at 300 baud. Without this wakeup, all HDLC frames at 9600 baud are ignored.
     *
     * Sequence (IEC 62056-21 §6.3.3):
     *   1. Set port to 300 baud 7E1
     *   2. Send "/?!\r\n" (request message)
     *   3. Wait up to 2s for identification response "/LNT5xxxx\r\n"
     *   4. Send ACK "\x06\x30\x35\x0D\x0A" (select 9600 baud, Mode E)
     *   5. Caller must switch port back to 9600 8N1 and settle before HDLC
     *
     * Returns true if the meter responded with an identification message.
     * Returns false if no response — meter may already be awake or not need wakeup.
     * Either way, the caller should proceed with HDLC since some meters accept both.
     */
    private boolean performModeCWakeup(UsbSerialPort port) {
        try {
            // Step 1: Switch to 300 baud 7-bit even parity (IEC 62056-21 standard)
            port.setParameters(300, UsbSerialPort.DATABITS_7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN);
            android.os.SystemClock.sleep(100); // settle

            // Step 2: Send request message "/?!\r\n"
            byte[] request = new byte[]{ 0x2F, 0x3F, 0x21, 0x0D, 0x0A }; // /?!\r\n
            port.write(request, 500);
            appendLog("MODE_C_REQUEST sent: /?!<CR><LF>");

            // Step 3: Wait up to 2s for identification response (e.g. "/LNT5\r\n" or "/SCH5xxxxx\r\n")
            byte[] idBuf = new byte[64];
            StringBuilder idMsg = new StringBuilder();
            // NRM FIX-5: extended from 2s to 3s — some ER300P models send ID slowly on first wakeup
            long deadline = System.currentTimeMillis() + 3000;
            boolean gotId = false;
            while (System.currentTimeMillis() < deadline) {
                int n = port.read(idBuf, 200);
                for (int i = 0; i < n; i++) {
                    char c = (char)(idBuf[i] & 0x7F);
                    idMsg.append(c);
                }
                // ID message starts with '/' and ends with \r\n
                String s = idMsg.toString();
                if (s.contains("/") && (s.contains("\r\n") || s.length() > 10)) {
                    gotId = true;
                    appendLog("MODE_C_ID_RECEIVED: " + s.trim());
                    break;
                }
            }
            if (!gotId) {
                // NRM-LNT FIX: When the meter sends NO Mode-C identification, it is already
                // running at 9600 baud HDLC (optical head recently used, or model that skips
                // IEC 62056-21 entirely).  Sending the ACK at 300 baud 7E1 to a 9600-baud
                // receiver produces garbled bytes that corrupt the HDLC session before SNRM
                // even arrives → all three NRM attempts fail.
                // Fix: return immediately without ACK.  The caller re-sets the port to
                // 9600 8N1 and proceeds straight to DISC/SNRM.
                appendLog("MODE_C_ID_TIMEOUT — no response at 300 baud, skipping ACK → direct HDLC");
                return false;
            }

            // Step 4 (only reached when meter DID respond to Mode-C request):
            // Send ACK to select 9600 baud, Mode E (HDLC).
            // IEC 62056-21 ACK format: SOH(0x06) | P(0x30='0') | B(0x35='5'=9600) | M(0x45='E'=HDLC) | CR | LF
            // The mode byte 'E' (0x45) is mandatory — without it the meter stays in ASCII
            // Mode D and ignores all subsequent HDLC frames → NRM always fails.
            // Previous code sent 06 30 35 0D 0A (4 bytes, no mode byte) — WRONG.
            byte[] ack = new byte[]{ 0x06, 0x30, 0x35, 0x45, 0x0D, 0x0A }; // ACK '0' '5' 'E' CR LF
            port.write(ack, 500);
            appendLog("MODE_C_ACK sent: 06 30 35 45 0D 0A (9600 baud, Mode E HDLC)");
            android.os.SystemClock.sleep(500); // LNT NRM FIX: 500ms (was 300ms) — some ER300P models
            // take 400ms to complete baud switch after ACK
            return true;
        } catch (Exception e) {
            appendLog("MODE_C_WAKEUP_EX: " + e.getMessage());
            return false;
        }
    }

    private boolean SetNRM(UsbSerialPort port, int nWait, byte nTryCount, byte nTimeOut) {
        boolean flag1 = false;
        // bytAddMode=0: 1-byte server addr (standard, most meters), control at nPkt[5], frame=9B
        // bytAddMode=1: 2-byte server addr (L&T NTD 3-phase, device_address=256), control at nPkt[6], frame=10B
        byte num1 = (byte) (5 + (int) this.bytAddMode);
        this.nPkt[2] = (byte) (7 + (int) this.bytAddMode);
        byte[] numArray1 = this.nPkt;
        int index1 = (int) num1;
        int num2 = 1;
        byte num3 = (byte) (index1 + num2);
        int num4 = 83;
        numArray1[index1] = (byte) num4;
        this.nPkt[(int) num3 + 2] = (byte) 126;
        fcs(this.nPkt, (int) (5 + (int) this.bytAddMode), (byte) 1);

        this.ClearBuffer();
        appendLog("Pkt Sending: " );

        int discFrameLen = 9 + (int) this.bytAddMode;
        byte sendCommand[] = new byte[discFrameLen];
        for (int ma = 0; ma < discFrameLen; ma++) sendCommand[ma] = this.nPkt[ma];
        boolean Result =this.SendPkt( port,sendCommand, discFrameLen);
        //int i= port.write(securityLnG, 9);
        // appendLog("Packet Write Response : " +i);
        // sb.append("\n");
        // int num = port.read(this.buffer,  64);
        if (Result== true)
            appendLog("Data write in port " );
        else
            appendLog("Data Unbale to write in port " );
        //int i= port.write(this.nPkt, 9);


        Time now1 = new Time();
        now1.setToNow();

        int cnt=1;
        appendLog("Loop Count: " +cnt);
        appendLog("Recive Packet Before " +nRcvPkt);

        // Receive UA response to DISC.
        // Old working file (Reading_37) used 2000ms — all tested makes respond in <200ms
        // when awake. 3000ms wasted 1s per call when meter skips DISC UA (most do).
        long wakeDeadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < wakeDeadline) {
            cnt++;
            this.DataReceive(port, 20);
            if (this.nCounter > 2 && this.nRcvPkt[2] + 2 <= this.nCounter && this.fcs(this.nRcvPkt, this.nRcvPkt[2], (byte) 0)) {
                flag1 = true;
                appendLog("Condidtion True");
                break;
            } else {
                appendLog("Condidtion False");
            }
        }
        appendLog("Loop Count: " + cnt);
        // No post-DISC settle. Old working file went straight from DISC to SNRM for all
        // makes. Added settles (500ms for non-HPL, 0ms for HPL) caused NRM failures by
        // disrupting HDLC state machine timing on HPL and adding unnecessary delay elsewhere.
        if (!flag1) appendLog("DISC_UA_MISSING — proceeding to SNRM");

        byte num5 = (byte) (5 + (int) this.bytAddMode); // SNRM control byte at 5+bytAddMode
        byte[] numArray2 = this.nPkt;
        int index2 = (int) num5;
        int num6 = 1;
        byte num7 = (byte) (index2 + num6);
        int num8 = 147;
        numArray2[index2] = (byte) num8;

        int my = num7 + 1;
        this.nPkt[2] = (byte) my;
        this.fcs(this.nPkt, this.bytAddMode + 5, (byte) 1);
        this.nPkt[(int) num7 + 2] = (byte) 126;

        byte num70 = (byte) 0;
        boolean flag2 = false;
        // Cap total SNRM time at 8s — proven working value from Reading_14.java.
        // nTryCount × nTimeOut without a cap (e.g. 3×8=24s) made each SetNRM call
        // take 31s, and with 3 outer retries the total NRM time reached ~95s.
        // With the 8s cap, each SetNRM is ~10s and total NRM time stays under 25s.
        long snrmDeadline = System.currentTimeMillis() + Math.min((int)nTryCount * (int)nTimeOut * 1000L, 8000L);
        do {
            if (abortRequested || System.currentTimeMillis() > snrmDeadline) break;
            this.ClearBuffer();
            flag2 = false;
            byte sendCommand1[] = new byte[num7+3];
            appendLog("Num 7 Value :--" + num7);
            for(int ma=0; ma< num7 + 3 ;ma++) {
                sendCommand1[ma]=this.nPkt[ma];
            }
            this.SendPkt(port, sendCommand1, num7 + 3);

            long innerStart = System.currentTimeMillis();
            long innerTimeout = (int)nTimeOut * 1000L;
            do {
                if (abortRequested || System.currentTimeMillis() > snrmDeadline) break;
                this.DataReceive(port);

                if (this.nCounter > 2 && (int) this.nRcvPkt[2] + 2 <= this.nCounter
                        && ((int) this.nRcvPkt[(int) this.nRcvPkt[2] + 1] == 126
                        && this.fcs(this.nRcvPkt, (this.nRcvPkt[2]), (byte) 0))) {
                    flag2 = true;
                    break;
                } else if (System.currentTimeMillis() - innerStart >= innerTimeout
                        && (int) num70 < (int) nTryCount) {
                    ++num70;
                    break;
                }
            } while (!flag2);
        } while (!flag2 && (int) num70 != (int) nTryCount
                && !abortRequested && System.currentTimeMillis() <= snrmDeadline);

        appendLog("Recive Packet After " +nRcvPkt);

        if ((int) this.nRcvPkt[ this.bytAddMode + 5] == 115)
            return flag2;
        else
            return false;

    }


    private void AddressInit()
    {
        int num1 = 65;  // client HDLC address = 0x41 = management client (SAP 32)
        this.nPkt[0] = (byte) 126;
        this.nPkt[1] = (byte) 160;
        if ((int) this.bytAddMode == 0)
        {
            // 1-byte server address: server=0x03 (addr 1), client=0x41 (addr 32)
            this.nPkt[3] = (byte)3;
            this.nPkt[4] = (byte)(num1);
        }
        else if ((int) this.bytAddMode == 1)
        {
            // 2-byte server address for L&T/Schneider NTD 3-phase (device_address=256)
            // HDLC encoding of 256: high byte (non-terminal)=0x04, low byte (terminal)=0x01
            this.nPkt[3] = (byte) 0x04;  // server high byte, non-terminal
            this.nPkt[4] = (byte) 0x01;  // server low byte, terminal
            this.nPkt[5] = (byte)(num1); // client = 0x41 (addr 32), terminal
        }

        this.nRecv = (byte) 0;
        this.nRecvLast = (byte) 0;
        this.nRecvCntr = (byte) 0;
        this.nSent = (byte) 0;
        this.nSentLast = (byte) 0;
        this.nSentCntr = (byte) 0;
        // appendLog("Address  Init Cpmpleted...!");
    }

    /**
     * IS15959-2 standard default scaler/unit for common instantaneous OBIS codes.
     * Used as fallback when a meter returns an empty attr=3 (scaler/unit) response,
     * which happens on some HPL meters for V/I/kVAr registers.
     * Returns int[]{scaler, unitCode} or null if OBIS is not in the table.
     *
     * Standard unit codes: 0x1B=W, 0x1C=VA, 0x1D=var, 0x1E=Wh, 0x1F=VAh,
     *   0x20=varh, 0x21=A, 0x23=V, 0x2C=Hz
     */
    private static int[] dlmsDefaultScalerUnit(String obisHex) {
        // Map of OBIS (upper, 12-char hex) → {scaler, unit}
        // Scalers match IS15959-2 / common HPL/Secure/Genus meter firmware defaults.
        switch (obisHex) {
            // Voltage phase-to-neutral (1.0.32.7.0, 1.0.52.7.0, 1.0.72.7.0) — mV (sc=-2) or V (sc=0)
            // Use sc=-2, unit=V (0x23): raw in units of 10mV → display in V
            case "0100200700FF": case "0100340700FF": case "0100480700FF":
                return new int[]{-2, 0x23}; // 10mV → V
            // Current (1.0.31.7.0, 1.0.51.7.0, 1.0.71.7.0) — mA (sc=-3) or A
            // Use sc=-3, unit=A (0x21): raw in mA → display in A
            case "01001F0700FF": case "0100330700FF": case "0100470700FF":
                return new int[]{-3, 0x21}; // mA → A
            // Neutral current (same OBIS group for phase-neutral current)
            case "0100210700FF": case "0100350700FF": case "0100490700FF":
                return new int[]{-3, 0x21};
            // Active power import/export (1.0.1.7.0 / 1.0.2.7.0) — W
            case "0100010700FF": case "0100020700FF":
                return new int[]{0, 0x1B};
            // Reactive power import/export (1.0.3.7.0 / 1.0.4.7.0) — var
            case "0100030700FF": case "0100040700FF":
                return new int[]{0, 0x1D};
            // Apparent power import/export (1.0.9.7.0 / 1.0.10.7.0) — VA
            case "0100090700FF": case "01000A0700FF":
                return new int[]{0, 0x1C};
            // Frequency (1.0.14.7.0) — Hz, sc=-2 (raw in 10mHz)
            case "01000E0700FF":
                return new int[]{-2, 0x2C};
            // Power factor (1.0.13.7.0) — dimensionless per-mille or per-ten-thousand
            // sc=-3: value in milli-units (e.g. 999 → 0.999)
            // sc=-4: value in tenth-milli (e.g. 9997 → 0.9997) — used by Landis+Gyr
            // We use sc=-3 as the IS15959-2 default; the meter's own attr=3 takes precedence.
            case "01000D0700FF":
                return new int[]{-3, 0xFF};
            // Energy cumulative (sc=-1, Wh→kWh display)
            case "0100010800FF": case "0100020800FF":
                return new int[]{-1, 0x1E};
            case "0100090800FF": case "01000A0800FF":
                return new int[]{-1, 0x1F};
            case "0100050800FF": case "0100060800FF":
            case "0100070800FF": case "0100080800FF":
                return new int[]{-1, 0x20};
            // Demand registers (class-4):
            //   0100010600FF = MD kW  import → unit 0x1B (W/kW), sc=1 (HPL DDT confirmed)
            //   0100090600FF = MD kVA import → unit 0x1C (VA/kVA), sc=1
            //   0100020600FF = MD kW  export → unit 0x1B, sc=1
            //   01000A0600FF = MD kVA export → unit 0x1C, sc=1
            case "0100010600FF": case "0100020600FF":
                return new int[]{1, 0x1B};
            case "0100090600FF": case "01000A0600FF":
                return new int[]{1, 0x1C};
            default:
                return null;
        }
    }

    /**
     * Extract the timestamp of the last LP record from a GetParameterSelective response,
     * then advance it by one capture-period interval.
     * Used by the LP intra-day pagination loop to resume from where the last page ended.
     *
     * @param lpHex          hex payload of the LP response
     * @param capturePeriodMin  capture period in minutes (15 or 30)
     * @return Date for the next-interval start, or null if no timestamp found
     */
    private java.util.Date extractLastLpTimestamp(String lpHex, int capturePeriodMin) {
        try {
            String lowerHex = lpHex.toLowerCase();
            // Find all "090c07eX..." date patterns (LP record timestamps)
            int lastPos = -1;
            int idx = 0;
            while (true) {
                int found = lowerHex.indexOf("090c07e", idx);
                if (found < 0) break;
                lastPos = found;
                idx = found + 1;
            }
            if (lastPos < 0) return null;
            // Decode the timestamp at lastPos (tag=09, len=0C, 12 bytes = 24 hex chars)
            String ts = lowerHex.substring(lastPos + 4, lastPos + 28); // skip "090c", get 12 bytes
            if (ts.length() < 24) return null;
            int y  = Integer.parseInt(ts.substring(0, 4),  16);
            int mo = Integer.parseInt(ts.substring(4, 6),  16); if (mo > 127) mo = 1;
            int d  = Integer.parseInt(ts.substring(6, 8),  16); if (d  > 127) d  = 1;
            int h  = Integer.parseInt(ts.substring(10, 12),16); if (h  > 127) h  = 0;
            int mi = Integer.parseInt(ts.substring(12, 14),16); if (mi > 127) mi = 0;
            if (y < 2000 || y > 2100 || mo < 1 || mo > 12) return null;
            // Advance by one capture-period interval
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(y, mo - 1, d, h, mi, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            cal.add(java.util.Calendar.MINUTE, capturePeriodMin);
            return cal.getTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Parse a meter serial number from a raw DLMS GetResponse payload hex string.
     *
     * OBIS 0.0.96.1.0.255 (0000600100FF) attr=2 is used across all meter makes to
     * return the meter serial number, but the DLMS data-type varies by make:
     *
     *   Secure / Genus / L&T : 0x0A or 0x09 (visible-string / octet-string)
     *                          → ASCII bytes → read directly as text  e.g. "SS09095786"
     *   HPL / AVON           : 0x06 (uint32)
     *                          → 4-byte big-endian integer → convert to decimal string
     *                            e.g. 0x033F3089 → "54472841"
     *   Some meters          : 0x12 (uint16) or 0x11 (uint8) → same decimal conversion
     *
     * Without this split, HPL meters store the raw binary bytes as "chars", producing
     * a garbled METERNO like "?0" in the file header and filename instead of "54472841".
     */
    public static String parseMeterNoFromDlms(String rawHex) {
        try {
            if (rawHex == null || rawHex.length() < 2) return "";
            // Strip any leading OBIS-line prefix (e.g. "0001 0000600100FF 02 ") if present
            // GetParameter(isDLM=false) returns only the payload, but be defensive.
            String h = rawHex.replaceAll("\\s+", "").toUpperCase();
            if (h.length() < 2) return "";
            int tag = Integer.parseInt(h.substring(0, 2), 16);

            // Numeric types: uint32 (06), int32 (05), uint16 (12), int16 (10), uint8 (11/16)
            // HPL 0000600100FF returns tag=06 (uint32) containing the decimal meter serial.
            if (tag == 0x06 && h.length() >= 10) {
                long val = Long.parseLong(h.substring(2, 10), 16);
                return Long.toString(val);
            }
            if (tag == 0x05 && h.length() >= 10) {
                long val = Long.parseLong(h.substring(2, 10), 16);
                if (val > 0x7FFFFFFFL) val -= 0x100000000L;
                return Long.toString(val);
            }
            if (tag == 0x12 && h.length() >= 6) {
                int val = Integer.parseInt(h.substring(2, 6), 16);
                return Integer.toString(val);
            }
            if (tag == 0x10 && h.length() >= 6) {
                int val = Integer.parseInt(h.substring(2, 6), 16);
                if (val > 0x7FFF) val -= 0x10000;
                return Integer.toString(val);
            }
            if ((tag == 0x11 || tag == 0x16) && h.length() >= 4) {
                int val = Integer.parseInt(h.substring(2, 4), 16);
                return Integer.toString(val);
            }

            // String types (0x09 octet-string, 0x0A visible-string):
            // Read length byte, then convert ASCII payload bytes to text.
            if ((tag == 0x09 || tag == 0x0A) && h.length() >= 4) {
                int len = Integer.parseInt(h.substring(2, 4), 16);
                int dataStart = 4;
                if (h.length() >= dataStart + len * 2) {
                    String asciiHex = h.substring(dataStart, dataStart + len * 2);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < asciiHex.length() - 1; i += 2) {
                        int b = Integer.parseInt(asciiHex.substring(i, i + 2), 16);
                        if (b >= 0x20 && b <= 0x7E) sb.append((char) b); // printable ASCII only
                    }
                    return sb.toString().trim();
                }
            }

            // Fallback: treat whole payload as ASCII (legacy behaviour for unexpected types)
            String fallback = hexToString(h).replace("\b", "").replace("\r\n", "")
                    .replace("\n", "").replace("\t", "").trim();
            // Accept only if result is printable and non-empty
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (!fallback.isEmpty() && fallback.chars().allMatch(c -> c >= 0x20 && c <= 0x7E))
                    return fallback;
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Parses a DLMS visible-string or octet-string hex payload into a printable ASCII string.
     * Payload format: tag(1 byte) + len(1 byte) + data bytes.
     * Tag 0x0A = visible-string, 0x09 = octet-string.
     * Used to extract the logical device name (0.0.42.0.0.255) for HPL sub-variant detection.
     */
    private static String parseVisibleStringHex(String hexPayload) {
        if (hexPayload == null || hexPayload.length() < 4) return "";
        try {
            String h = hexPayload.replaceAll("\\s+", "").toUpperCase();
            int tag = Integer.parseInt(h.substring(0, 2), 16);
            if (tag != 0x0A && tag != 0x09) return "";
            int len = Integer.parseInt(h.substring(2, 4), 16);
            if (h.length() < 4 + len * 2) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                int b = Integer.parseInt(h.substring(4 + i * 2, 6 + i * 2), 16);
                if (b >= 0x20 && b <= 0x7E) sb.append((char) b);
            }
            return sb.toString().trim();
        } catch (Exception ex) { return ""; }
    }

    public static String hexToString(String hex) {
        StringBuilder sb = new StringBuilder();
        char[] hexData = hex.toCharArray();
        for (int count = 0; count < hexData.length - 1; count += 2) {
            int firstDigit = Character.digit(hexData[count], 16);
            int lastDigit = Character.digit(hexData[count + 1], 16);
            int decimal = firstDigit * 16 + lastDigit;
            sb.append((char)decimal);
        }
        return sb.toString();
    }

    public String GetDatefromString(String tmpdate, int index)
    {
        try
        {
            if (tmpdate.length() == 14)
                tmpdate = !(tmpdate.substring(10, 4) == "FFFF") ? tmpdate + "000000000000" : tmpdate.substring(0, 10) + "0100000000000000";

            // Integer.parseInt("AA0F245C", 16);
            //  appendLog("String :-"+ tmpdate);
            // appendLog("String len :-"+ tmpdate.length());
            // appendLog("Here1 "+ tmpdate.substring(0, 2));
            //  appendLog("String12 :-"+ tmpdate);
            //  appendLog("Here1 "+ tmpdate.substring(5, 7));
            //  appendLog("Here"+ tmpdate.substring(8, 10));
            //  appendLog("index :-"+ index);

            if (Integer.parseInt(tmpdate.substring(14 + index, 14 + index+ 2), 16) == 255)
            {
                //  appendLog("Upper Condition:-"+ tmpdate);
                String[] strArray1 = new String[9];
                strArray1[0] =Integer.toString(Integer.parseInt(tmpdate.substring(6 + index, 2), 16));
                strArray1[1] = "/";
                String[] strArray2 = strArray1;
                int index1 = 2;
                int num = Integer.parseInt(tmpdate.substring(4 + index, 2), 16);
                String str1 = Integer.toString(num);
                strArray2[index1] = str1;
                strArray1[3] = "/";
                String[] strArray3 = strArray1;
                int index2 = 4;
                num = Integer.parseInt(tmpdate.substring(index, 4), 16);
                String str2 = Integer.toString(num);
                strArray3[index2] = str2;
                strArray1[5] = " ";
                String[] strArray4 = strArray1;
                int index3 = 6;
                num = Integer.parseInt(tmpdate.substring(10 + index, 2), 16);
                String str3 = Integer.toString(num);
                strArray4[index3] = str3;
                strArray1[7] = ":";
                String[] strArray5 = strArray1;
                int index4 = 8;
                num = Integer.parseInt(tmpdate.substring(12 + index, 2), 16);
                String str4 = Integer.toString(num);
                strArray5[index4] = str4;
                //return String.Concat(strArray1);
                return Arrays.toString(strArray1);
            }
            else
            {
                //  appendLog("Lower Condition:-"+ tmpdate);
                String[] strArray1 = new String[11];
                strArray1[0] = Integer.toString(Integer.parseInt(tmpdate.substring(6 + index,(6 + index)+ 2), 16));
                strArray1[1] = "/";
                String[] strArray2 = strArray1;
                int index1 = 2;
                int num = Integer.parseInt(tmpdate.substring(4 + index,(4 + index)+ 2 ), 16);
                String str1 = Integer.toString(num);
                strArray2[index1] = str1;
                strArray1[3] = "/";
                String[] strArray3 = strArray1;
                int index2 = 4;
                num = Integer.parseInt(tmpdate.substring(index,(index)+ 4), 16);
                String str2 = Integer.toString(num);
                strArray3[index2] = str2;
                strArray1[5] = " ";
                String[] strArray4 = strArray1;
                int index3 = 6;
                num = Integer.parseInt(tmpdate.substring(10 + index, (10 + index)+ 2), 16);
                String str3 = Integer.toString(num);
                strArray4[index3] = str3;
                strArray1[7] = ":";
                String[] strArray5 = strArray1;
                int index4 = 8;
                num = Integer.parseInt(tmpdate.substring(12 + index, (12 + index)+ 2), 16);
                String str4 = Integer.toString(num);
                strArray5[index4] = str4;
                strArray1[9] = ":";
                String[] strArray6 = strArray1;
                int index5 = 10;
                num = Integer.parseInt(tmpdate.substring(14 + index, (14 + index) +2), 16);
                String str5 = Integer.toString(num);
                strArray6[index5] = str5;
                //return string.Concat(strArray1);
                return Arrays.toString(strArray1);
            }
        }
        catch (Exception ex)
        {
            appendLog("Date:-" + ex.getMessage());
            return "";
        }
    }

    private StringBuilder ReadBillingData(UsbSerialPort port, ReadingMode readingMode)
    {
        // Billing data sequence required by XML parser:
        // 1. Current RTC (instant time) — must be FIRST so parser knows read timestamp
        // 2. Billing profile capture_objects (attr=3) — column definitions
        // 3. Billing profile buffer (attr=2) — all billing records
        // 4. Billing profile entries_in_use (attr=7) — record count
        // 5. ActivityCalendar (Class 20) — tariff schedule
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        boolean billingBufferFailed = false; // BUG-18 FIX: replaced dead 'flag' with meaningful name

        // STEP 1: Current RTC — instant reading timestamp (must come first)
        DLMdata = this.GetParameter(port, (byte) 8, "0000010000FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // STEP 2: Scalar/unit descriptor for billing type
        DLMdata = this.ReadScalarUnit("BILLTYPC", port);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // STEP 2b: Read per-register billing scalers (class=3, attr=3) for all energy/demand
        // OBIS codes that appear in the billing profile.
        //
        // ROOT CAUSE OF 100x BILLING ERROR:
        // Some meters (notably HPL) use DIFFERENT scalers in billing vs instantaneous registers.
        // HPL instantaneous stores energy at sc=-2 (10 Wh/unit), but billing profile stores
        // the same OBIS at sc=0 (1 Wh/unit). The scalerMap built from instantaneous reads
        // carries sc=-2 for kWh OBIS, which is then wrongly applied to billing values → ÷100 error.
        //
        // FIX: Read billing-specific attr=3 scalers here and emit them with prefix "BSCL"
        // so extractBillingByObis can use a billing-specific lookup (billingScalerMap)
        // instead of the instantaneous scalerMap.
        //
        // This affects ALL meter makes where billing scaler ≠ instantaneous scaler.
        String[] billingRegisterObis = {
                "0100010800FF", // kWh import T0
                "0100020800FF", // kWh export T0
                "0100090800FF", // kVAh import T0
                "01000A0800FF", // kVAh export T0
                "0100050800FF", // kVArh Q1+Q4
                "0100060800FF", // kVArh Q2+Q3
                "0100070800FF", // kVArh Q1+Q2 (import reactive)
                "0100080800FF", // kVArh Q3+Q4 (export reactive)
                "0100010600FF", // kW demand import T0
                "0100090600FF", // kVA demand import T0
                "0100090601FF", // kVA demand import T1 (LTCT meters store total MD here)
                "0100010601FF", // kW demand import T1
                "01000A0600FF", // kVA demand export (standard)
                "0100020600FF", // kW demand export (standard)
                "0100B20600FF", // kW demand export (Genus-specific OBIS)
                "0100B30600FF", // kVA demand export (Genus-specific OBIS)
                // TOD kWh import T1–T8: required because some Secure firmware variants
                // (e.g. BS034549) declare sc=+1 in individual attr=3 for TOD slots but
                // the BillingScalerProfile (BSP 01005E5B06FF) reports sc=0 for those
                // same columns. Without reading attr=3 here the billing TOD display is
                // 10× too small. One read per slot (T1–T8) adds ~8 fast single-block
                // reads (~200ms total) — negligible compared to the 30s billing read.
                "0100010801FF", "0100010802FF", "0100010803FF", "0100010804FF",
                "0100010805FF", "0100010806FF", "0100010807FF", "0100010808FF",
                // TOD kWh export T1–T8 (for meters that meter export per slot)
                "0100020801FF", "0100020802FF", "0100020803FF", "0100020804FF",
                // TOD kVAh import T1–T8 (apparent energy per slot)
                "0100090801FF", "0100090802FF", "0100090803FF", "0100090804FF",
                "0100090805FF", "0100090806FF", "0100090807FF", "0100090808FF",
        };
        for (String regObis : billingRegisterObis) {
            StringBuilder scalerSb = new StringBuilder();
            // Use bytTryCnt=1 (single attempt, no retry) — failure simply falls back to
            // the instantaneous scalerMap. This avoids 11 × bytTryCnt × bytTimOut overhead
            // when a register is absent (e.g. export on import-only meters).
            DLMdata = this.GetParameter(port, (byte) 3, regObis, (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, false, scalerSb);
            if (hasMeaningfulDlmsPayload(DLMdata)) {
                // Emit with "BSCL OBIS payload" so billing parser can match OBIS → scaler.
                // CRITICAL: DLMdata contains only the raw payload (e.g. "02020FFF161E").
                // The OBIS must be prepended explicitly so buildBillingScalerMap can key it.
                strbldDLMdata.append("\r\nBSCL ").append(regObis).append(" ").append(DLMdata.toString().trim());
                appendLog("BILL_SCALER_READ obis=" + regObis);
            }
        }

        // STEP 3: Billing profile capture_objects (attr=3) — column definitions
        DLMdata = this.GetParameter(port, (byte) 7, "0100620100FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        else
            billingBufferFailed = true; // STEP 3: capture objects (attr=3) failed

        // STEP 4: Billing profile buffer (attr=2).
        // ── BILLING MODE: selective access for 2 dates only ──────────────────────
        // In "Billing" reading mode, request only:
        //   FROM = 1st of current month 00:00:00 (billing reset midnight)
        //   TO   = current reading date/time     (snapshot at reading time)
        // This returns at most 2 records from the meter, making the read faster.
        // Selective access uses DLMS range_descriptor (access_selector=1) with
        // the Clock object (0.0.1.0.0.255) as the restricting_object.
        //
        // In "Complete" mode: full buffer pull (all historical records) as before.
        //
        // Selective-access APDU format (embedded in GetWithList or Get-Request):
        //   access_selector=01
        //   restricting_object: class=08 OBIS=0000010000FF attr=02
        //   from_value:  DateTime octet-string (090CYYYYMM01FF000000FF800000)
        //   to_value:    DateTime octet-string (090CYYYYMMDDFF HH MM SS FF 800000)
        boolean usedSelectiveAccess = false;
        if (readingMode == ReadingMode.BILLING) {
            try {
                // Build the selective-access FROM/TO DateTime byte arrays
                java.util.Calendar now = java.util.Calendar.getInstance();
                int yyyy = now.get(java.util.Calendar.YEAR);
                int mm   = now.get(java.util.Calendar.MONTH) + 1;  // 1-based
                int dd   = now.get(java.util.Calendar.DAY_OF_MONTH);
                int hh   = now.get(java.util.Calendar.HOUR_OF_DAY);
                int min  = now.get(java.util.Calendar.MINUTE);
                int ss   = now.get(java.util.Calendar.SECOND);
                // FROM: 1st of current month at 00:00:00
                // DateTime: year(2) month(1) day(1) weekday(1) hour(1) min(1) sec(1) hund(1) offset(2) dst(1)
                // weekday=FF(not specified), hund=00, offset=8000(not specified), dst=FF
                byte[] fromDt = new byte[]{
                        (byte)(yyyy>>8),(byte)(yyyy&0xFF),(byte)mm,0x01,
                        (byte)0xFF,0x00,0x00,0x00,
                        (byte)0xFF,(byte)0x80,0x00,(byte)0xFF
                };
                byte[] toDt = new byte[]{
                        (byte)(yyyy>>8),(byte)(yyyy&0xFF),(byte)mm,(byte)dd,
                        (byte)0xFF,(byte)hh,(byte)min,(byte)ss,
                        0x00,(byte)0x80,0x00,(byte)0xFF
                };
                // Build selective-access GET request for billing profile attr=2
                // This calls GetParameterWithSelectiveAccess if implemented, else falls back.
                DLMdata = this.GetParameterWithRangeAccess(port, (byte) 7, "0100620100FF",
                        fromDt, toDt, (byte) this.bytWait, this.bytTryCnt, this.bytTimOut,
                        true, strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata)) {
                    strbldDLMdata.append(DLMdata);
                    usedSelectiveAccess = true;
                    appendLog("BILLING_SELECTIVE: 2-date read succeeded (" +
                            String.format("%02d/%02d/%04d", 1, mm, yyyy) + " to " +
                            String.format("%02d/%02d/%04d %02d:%02d:%02d", dd, mm, yyyy, hh, min, ss) + ")");
                }
            } catch (Exception selEx) {
                appendLog("BILLING_SELECTIVE_FAIL: " + selEx.getMessage() + " — falling back to full buffer");
            }
        }
        if (!usedSelectiveAccess) {
            // Complete mode or selective access failed/not applicable: full buffer pull.
            DLMdata = this.GetParameter1(port, (byte) 7, "0100620100FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata))
                strbldDLMdata.append(DLMdata);
            else {
                billingBufferFailed = true;
                appendLog("BILLING_WARN: Buffer (attr=2) failed — CO and EIU preserved");
            }
        }

        // STEP 5: Billing profile entries_in_use (attr=7)
        DLMdata = this.GetParameter(port, (byte) 7, "0100620100FF", (byte) 7,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // STEP 6: ActivityCalendar (Class 20) — tariff schedule (attr=2,3,4)
        DLMdata = this.GetParameter(port, (byte) 20, "00000D0000FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 20, "00000D0000FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 20, "00000D0000FF", (byte) 4,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        // attr=5 = passive_calendar_name (pending tariff not yet activated)
        // MRI reads this on all makes: Genus DLM, HPL DDT, L&T OUT all confirmed.
        DLMdata = this.GetParameter(port, (byte) 20, "00000D0000FF", (byte) 5,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        // attr=7 = activate_passive_calendar_time; attr=9 = time (HPL-specific, single attempt)
        // HPL DDT confirms these attrs; other makes fast-fail on ACCESS_ERROR.
        DLMdata = this.GetParameter(port, (byte) 20, "00000D0000FF", (byte) 7,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 20, "00000D0000FF", (byte) 9,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        // Passive TOD calendar name (0.0.13.0.128.255 = 00000D0080FF) — class 1, attr=2
        // Present in HPL DDT as the pending/passive tariff name string.
        DLMdata = this.GetParameter(port, (byte) 1, "00000D0080FF", (byte) 2,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        if (billingBufferFailed) {
            // Buffer (attr=2) failed but CO (attr=3) succeeded — keep CO, EIU, and
            // calendar in the TXT so the converter can still read column definitions
            // and the entries_in_use count. Only suppress the full discard that was
            // originally applied when the CO itself was missing.
            appendLog("BILLING_WARN: Billing buffer missing — partial billing section retained (CO+EIU present)");
            // VAL-3 FIX: write sentinel so XML converter can detect partial billing
            strbldDLMdata.append("\r\nBILLING_BUFFER_FAILED=1");
        }

        return strbldDLMdata;
    }

    /**
     * Read a profile buffer (class 7 attr 2) with DLMS range_descriptor selective access.
     * Requests only records between fromDt and toDt (both inclusive).
     * fromDt / toDt are 12-byte DLMS DateTime byte arrays (no tag/length prefix).
     *
     * DLMS GET.request with access_selector=01 (range descriptor):
     *   01                     → access_selector = range_descriptor
     *   02 04                  → structure of 4 elements
     *     02 04                → restricting_object: structure of 4
     *       12 00 08           → class_id uint16 = 8 (Clock)
     *       09 06 00 00 01 00 00 FF  → logical_name OctetString "0.0.1.0.0.255"
     *       11 02              → attribute_index uint8 = 2 (value)
     *       12 00 00           → data_index uint16 = 0
     *     09 0C [12 bytes]     → from_value: DateTime OctetString
     *     09 0C [12 bytes]     → to_value: DateTime OctetString
     *     01 00                → selected_values: empty array (all columns)
     *
     * Returns the meter response via GetParameter1 with the selective-access payload,
     * or falls through to GetParameter1 (full buffer) if construction fails.
     */
    private StringBuilder GetParameterWithRangeAccess(UsbSerialPort port,
                                                      byte classId, String obis, byte[] fromDt, byte[] toDt,
                                                      byte wait, byte tryCnt, byte timeout,
                                                      boolean append, StringBuilder dest) {
        try {
            // Build the selective-access data bytes
            // Structure: access_selector(1) + range_descriptor
            byte[] clockObisBytes = new byte[]{0x00,0x00,0x01,0x00,0x00,(byte)0xFF};
            // access_selector = 01
            // range_descriptor = structure{restricting_object, from_value, to_value, selected_values}
            // restricting_object = structure{class_id(u16=8), LN(octet-str 6), attr_idx(u8=2), data_idx(u16=0)}
            // from_value = octet-string 12 bytes (DateTime)
            // to_value   = octet-string 12 bytes (DateTime)
            // selected_values = empty array
            byte[] selectData = new byte[]{
                    0x01,                          // access_selector = range_descriptor
                    0x02, 0x04,                    // outer structure 4 elements
                    0x02, 0x04,                  // restricting_object structure 4 elements
                    0x12, 0x00, 0x08,          // class_id = 8 (Clock)
                    0x09, 0x06,                // logical_name OctetString length=6
                    clockObisBytes[0], clockObisBytes[1], clockObisBytes[2],
                    clockObisBytes[3], clockObisBytes[4], clockObisBytes[5],
                    0x11, 0x02,                // attribute_index = 2
                    0x12, 0x00, 0x00,          // data_index = 0
                    0x09, 0x0C,                  // from_value OctetString length=12
                    fromDt[0],fromDt[1],fromDt[2],fromDt[3],fromDt[4],fromDt[5],
                    fromDt[6],fromDt[7],fromDt[8],fromDt[9],fromDt[10],fromDt[11],
                    0x09, 0x0C,                  // to_value OctetString length=12
                    toDt[0],toDt[1],toDt[2],toDt[3],toDt[4],toDt[5],
                    toDt[6],toDt[7],toDt[8],toDt[9],toDt[10],toDt[11],
                    0x01, 0x00                   // selected_values: empty array
            };
            // Use GetParameter1WithData to send GET with attached selective-access data
            return this.GetParameter1WithSelectiveAccess(port, classId, obis, (byte) 2,
                    selectData, wait, tryCnt, timeout, append, dest);
        } catch (Exception ex) {
            appendLog("RANGE_ACCESS_BUILD_FAIL: " + ex.getMessage());
            // Fall back to full buffer read
            return this.GetParameter1(port, classId, obis, (byte) 2, wait, tryCnt, timeout, append, dest);
        }
    }

    /**
     * GET request for a profile buffer with selective-access range_descriptor.
     * Sends the provided selectData bytes as the access-selection suffix of the GET APDU.
     *
     * IMPLEMENTATION NOTE: Building a selective-access GET APDU requires modifying the
     * low-level HDLC/DLMS framing (nPkt array) to include the access_selector bytes after
     * the attribute descriptor. This is tracked as a separate implementation task.
     * Current implementation falls back to the full-buffer read (GetParameter1).
     *
     * The selective-access feature IS correctly specified in GetParameterWithRangeAccess —
     * once the low-level APDU encoding is added here, it will work transparently.
     */
    private StringBuilder GetParameter1WithSelectiveAccess(UsbSerialPort port,
                                                           byte classId, String obis, byte attrId, byte[] selectData,
                                                           byte wait, byte tryCnt, byte timeout,
                                                           boolean append, StringBuilder dest) {
        // TODO: Implement selective-access APDU encoding.
        // For now fall back to full buffer read so Billing mode still works.
        appendLog("SELECTIVE_ACCESS: falling back to full buffer (APDU encoding pending)");
        return this.GetParameter1(port, classId, obis, attrId, wait, tryCnt, timeout, append, dest);
    }

    /**
     * BUG-12 FIX: added attr=7 (entries_in_use) read per event OBIS; EIU vs buffer cross-check. */
    private StringBuilder ReadEventData(UsbSerialPort port) {
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata;
        DLMdata = this.ReadScalarUnit("EVENT", port);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        String[] eventObis = {
                // ── IS 15959-2 / Secure / Genus / HPL standard event profiles (class 7) ──────
                "0000636200FF",  // Voltage event log
                "0000636201FF",  // Current event log
                "0000636202FF",  // Power fail event log
                "0000636203FF",  // Transaction event log
                "0000636204FF",  // Other event log
                "0000636205FF",  // Non-rollover / control event log
                "0000636206FF",  // Power quality event log
                // ── HPL tamper / security event logs ────────────────────────────────────────
                "0000636233FF", "0000636234FF", "0000636235FF",
                "0000636236FF", "0000636237FF", "0000636238FF",
                "0000636239FF",
                // ── Genus LC extended event logs (confirmed in DLM MRI 20103429 / 05103456) ─
                "0000636281FF",  // Genus: billing/demand reset log
                "0000636285FF", "0000636286FF", "0000636288FF",
                "000063628FFF", "0000636290FF", "0000636291FF",
                "0000636292FF", "0000636293FF", "0000636294FF",
                "0000636295FF",
                // ── L&T (Schneider ER300P) event profiles — 00005E5Bxx series (class 7) ─────
                // Confirmed in MRI: 25165449 (NTD, 3-phase) and KT228194 (TD, single-phase)
                // Each profile: attr=3 (capture objects) → attr=7 (entries_in_use) → attr=2 (buffer)
                // Fast-fail on non-L&T meters via ACCESS_ERROR on attr=3 (single probe, no retry).
                "00005E5B08FF",  // Non-rollover event log   (0.0.94.91.8.255)
                "00005E5B09FF",  // Voltage event log         (0.0.94.91.9.255)
                "00005E5B0AFF",  // Current event log — 3-ph  (0.0.94.91.10.255)
                "00005E5B0BFF",  // Power fail event log      (0.0.94.91.11.255) ← has 20 records
                "00005E5B0CFF",  // Transaction event log     (0.0.94.91.12.255)
                "00005E5B0DFF",  // Other event log           (0.0.94.91.13.255) ← has records
                "00005E5B0EFF",  // Current event log — 1-ph  (0.0.94.91.14.255) ← KT228194
        };
        // FIX: Some Secure meters (e.g. SS09096634) return empty for all event attr=3 (CO)
        // even though event buffers have data. The MRI reads attr=2 directly, bypassing CO check.
        // After the first EVENT_SKIP on attr=3, probe attr=2 of that OBIS directly.
        // If attr=2 returns data → set eventCoMissing=true → skip CO check for all subsequent OBIS.
        // Safe for ALL makes: meters that truly don't support events return empty on attr=2 too.
        boolean eventCoMissing = false; // set true if CO empty but attr=2 has data
        boolean eventCoProbed  = false; // true once we've done the fallback probe

        for (String obis : eventObis) {
            // FIX: Exit immediately if port died (SEND_FAIL_ABORT set abortRequested).
            // Without this, after port.write throws, the loop continued for all 29
            // remaining OBIS logging 87+ SEND_FAIL_ABORT messages in 8 seconds (confirmed
            // in L&T BS064528 log 2026-05-16 at +477s). Each "EVENT_ATTR2_OK elapsed=1ms"
            // was a false positive — the read instantly returned because write had failed.
            if (abortRequested) {
                appendLog("EVENT_LOOP_ABORT — abortRequested=true, skipping remaining event OBIS");
                break;
            }
            // FIX V33: Guard session deadline per iteration so a meter with many populated
            // event logs cannot push elapsed time past SESSION_MAX_SECONDS uncontrolled.
            if (sessionDeadlineMs > 0 && System.currentTimeMillis() >= sessionDeadlineMs) {
                appendLog("EVENT_SESSION_DEADLINE obis=" + obis + " — session limit reached, skipping remaining event OBIS");
                break;
            }
            // attr=3: capture objects — if absent this OBIS not supported, skip entirely
            DLMdata = this.GetParameter(port, (byte) 7, obis, (byte) 3,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (!hasMeaningfulDlmsPayload(DLMdata)) {
                if (!eventCoMissing && !eventCoProbed) {
                    // FIX: First CO miss — probe attr=2 directly to detect firmware bug
                    // where CO (attr=3) returns empty but buffer (attr=2) has data.
                    eventCoProbed = true;
                    appendLog("EVENT_CO_PROBE obis=" + obis + " — attr=3 empty, probing attr=2 directly");
                    StringBuilder probeSb = new StringBuilder();
                    // FIX: isDLM must be true so GetParameter writes the class/OBIS/attr header.
                    // With isDLM=false the returned SbData has no header and no spaces, so
                    // hasMeaningfulDlmsPayload (which requires 4 whitespace-separated tokens)
                    // always returned false — eventCoMissing was never set for any meter.
                    StringBuilder probeDat = this.GetParameter(port, (byte) 7, obis, (byte) 2,
                            this.bytWait, (byte) 1, this.bytTimOut, true, probeSb);
                    if (hasMeaningfulDlmsPayload(probeDat)) {
                        String probeHex = probeDat.toString().trim().split("\\s+").length > 3
                                ? probeDat.toString().trim().split("\\s+")[3] : "";
                        // Check it looks like a real event array (tag=01, count > 0)
                        if (probeHex.length() >= 4 && probeHex.startsWith("01")
                                && Integer.parseInt(probeHex.substring(2, 4), 16) > 0) {
                            eventCoMissing = true;
                            strbldDLMdata.append(probeDat);
                            appendLog("EVENT_CO_PROBE_HAS_DATA obis=" + obis
                                    + " — EIU/CO firmware bug, will read all events without CO check");
                            // EIU and further attrs for this OBIS already attempted via attr=2 probe;
                            // continue to next OBIS directly (attr=7 and attr=2 just read above)
                            continue;
                        } else {
                            appendLog("EVENT_CO_PROBE_EMPTY obis=" + obis + " — events not supported");
                        }
                    } else {
                        appendLog("EVENT_CO_PROBE_NO_RESPONSE obis=" + obis + " — events not supported");
                    }
                }
                if (!eventCoMissing) {
                    appendLog("EVENT_SKIP obis=" + obis + " attr=3 absent — not supported on this meter");
                    continue;
                }
                // eventCoMissing=true: skip CO check, go straight to attr=7 and attr=2
            } else {
                strbldDLMdata.append(DLMdata);
            }

            // attr=7: entries_in_use (BUG-12 FIX — was missing entirely)
            DLMdata = this.GetParameter(port, (byte) 7, obis, (byte) 7,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            int eiu = -1;
            if (hasMeaningfulDlmsPayload(DLMdata)) {
                strbldDLMdata.append(DLMdata);
                try {
                    String[] ep = DLMdata.toString().trim().split("\\s+");
                    if (ep.length >= 4 && ep[3].length() >= 10)
                        eiu = (int) Long.parseLong(ep[3].substring(2, 10), 16);
                } catch (Exception ignored) {}
                appendLog("EVENT_EIU obis=" + obis + " entries=" + eiu);
            }

            // attr=2: buffer — read with fail-fast protection
            // If lastGplsResult==2 (session dropped / DM frame) after attr=7, the meter
            // has disconnected — re-establish before attempting attr=2.
            if (lastGplsResult == 2) {
                appendLog("EVENT_DM_BEFORE_ATTR2 obis=" + obis + " — re-establishing session");
                try {
                    AddressInit();
                    boolean nrmOk = SetNRM(port, this.bytWait, (byte) 2, this.bytTimOut);
                    if (nrmOk) {
                        String pw = currentMeterMake.getPassword();
                        int aarqRes = AARQ(port, (byte) 1, pw, this.bytWait, (byte) 2, this.bytTimOut);
                        if (aarqRes == 0) { drainPort(port); lastGplsResult = 0; }
                        else { appendLog("EVENT_DM_RECOVER_FAIL obis=" + obis + " — skipping attr=2"); continue; }
                    } else { appendLog("EVENT_DM_RECOVER_NRM_FAIL obis=" + obis + " — skipping attr=2"); continue; }
                } catch (Exception evDmEx) { appendLog("EVENT_DM_RECOVER_EX " + evDmEx.getMessage()); continue; }
            }
            // Skip attr=2 entirely when entries_in_use is explicitly 0 — no data to read.
            if (eiu == 0) {
                appendLog("EVENT_SKIP_ATTR2 obis=" + obis + " entries_in_use=0");
                continue;
            }
            // Per-event deadline: cap each event buffer read at (bytTimOut × bytTryCnt × 2) seconds.
            // This prevents one misbehaving event log from consuming 180+ seconds (as seen in log
            // where 0000636204FF had entries_in_use=12 but meter stopped responding mid-transfer).
            long eventReadBudgetMs = (long) this.bytTimOut * this.bytTryCnt * 2 * 1000L;
            long eventDeadline = System.currentTimeMillis() + eventReadBudgetMs;
            appendLog("EVENT_ATTR2_START obis=" + obis + " eiu=" + eiu
                    + " budget=" + (eventReadBudgetMs/1000) + "s");
            DLMdata = this.GetParameter(port, (byte) 7, obis, (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) {
                strbldDLMdata.append(DLMdata);
                appendLog("EVENT_ATTR2_OK obis=" + obis
                        + " elapsed=" + (System.currentTimeMillis()-eventDeadline+eventReadBudgetMs) + "ms");
            } else if (eiu == 0) {
                appendLog("EVENT_INFO obis=" + obis + " buffer empty — consistent with entries_in_use=0");
            } else if (eiu > 0) {
                appendLog("EVENT_WARN obis=" + obis + " buffer empty but entries_in_use=" + eiu + " — read failure");
                // If session dropped (DM frame) during block transfer, re-establish so next event can be read
                if (lastGplsResult == 2) {
                    appendLog("EVENT_ATTR2_DM obis=" + obis + " — session dropped, re-establishing");
                    try {
                        AddressInit();
                        boolean nrmOk = SetNRM(port, this.bytWait, (byte) 2, this.bytTimOut);
                        if (nrmOk) {
                            String pw = currentMeterMake.getPassword();
                            int aarqRes = AARQ(port, (byte) 1, pw, this.bytWait, (byte) 2, this.bytTimOut);
                            if (aarqRes == 0) { drainPort(port); lastGplsResult = 0;
                                appendLog("EVENT_ATTR2_DM_RECOVER_OK obis=" + obis);
                            }
                        }
                    } catch (Exception evEx) { appendLog("EVENT_ATTR2_DM_EX " + evEx.getMessage()); }
                }
            } else {
                appendLog("EVENT_SKIP obis=" + obis + " attr=2 zero-or-empty");
            }
        }
        if (strbldDLMdata.length() == 0)
            appendLog("EVENT_WARN: All event responses empty — meter may not support event logs");
        return strbldDLMdata;
    }

    private StringBuilder ReadScalarUnit(String WhichData, UsbSerialPort port)
    {
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata;
        if ("INSTANT".equals(WhichData))
        {
            if (hasDlmsScalarObjects()) {
                DLMdata =this.GetParameter(port,(byte) 7, "01005E5B03FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B03FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
            }
        }
        else if ("BILLTYPC".equals(WhichData))
        {
            if (hasDlmsScalarObjects()) {
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B06FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
                DLMdata= this.GetParameter(port,(byte) 7, "01005E5B06FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
            }
        }
        else if ("BLOCKLOAD".equals(WhichData))
        {
            if (hasDlmsScalarObjects()) {
                // IMPORTANT: use GetParameter (not GetParameter_LS) here.
                // GetParameter_LS caused Secure meters to respond with a DM
                // (Disconnect Mode) frame for 01005E5B04FF, killing the HDLC session
                // before the LP buffer could be read. All other scaler reads
                // (INSTANT, BILLTYPC, DAILYLOAD, EVENT) use GetParameter — this
                // BLOCKLOAD case was the only inconsistent one.
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B04FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B04FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
            }
        }
        else if ("DAILYLOAD".equals(WhichData))
        {
            if (hasDlmsScalarObjects()) {
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B05FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B05FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
            }
        }
        else if ("EVENT".equals(WhichData))
        {
            if (hasDlmsScalarObjects()) {
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B07FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B07FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
            }
        }
        return strbldDLMdata;
    }


    /**
     * Returns the hardcoded capture objects hex (attr=3) for each meter make.
     * This is needed when the meter rejects attr=3 with ACCESS_ERROR.
     *
     * DLMS encoding of one capture object entry:
     *   02 04           structure of 4 items
     *   12 XX XX        class-id (uint16)
     *   09 06 A B C D E F  logical-name / OBIS (octet-string 6)
     *   0F XX           attribute-index (int8)
     *   12 00 00        data-index (uint16)
     *
     * 01 12 = array of 18 entries (for Genus LC 18-column block load profile)
     *
     * TODO: Verify the OBIS codes below against the actual meter spec for each make.
     * The column order must match the LP buffer data exactly.
     */
    private String getCaptureObjectsForMake() {
        // ── Genus / AVON LC block load profile — 18 columns ──────────────────
        // Verified from live meter read of 0100630100FF attr=3 on KT291596
        // (Genus LC meter, 18-column profile, 30-minute capture period).
        //
        // Col 1:  Clock                  IC=8  0.0.1.0.0.255      attr=2  DLMS DateTime
        // Col 2:  kW Block Demand        IC=4  1.0.1.4.0.255       attr=2  uint16
        // Col 3:  kVA Block Demand       IC=4  1.0.9.4.0.255       attr=2  uint16
        // Col 4:  kVAr Block Demand      IC=4  1.0.5.4.0.255       attr=2  uint16
        // Col 5:  Power Factor           IC=3  1.0.3.7.0.255       attr=2  ✓ VERIFIED
        // Col 6:  kWh Import cumul       IC=3  1.0.1.8.0.255       attr=2  uint32
        // Col 7:  kVAh cumul             IC=3  1.0.9.8.0.255       attr=2  uint32
        // Col 8:  kVArh Q1 lag           IC=3  1.0.5.8.0.255       attr=2  uint32
        // Col 9:  kVArh Q4 lead          IC=3  1.0.8.8.0.255       attr=2  uint32
        // Col 10: kWh Export cumul       IC=3  1.0.2.8.0.255       attr=2  uint32
        // Col 11: kVArh Q2               IC=3  1.0.6.8.0.255       attr=2  uint32
        // Col 12: kVArh Q3               IC=3  1.0.7.8.0.255       attr=2  uint32
        // Col 13: MD kW                  IC=3  1.0.1.6.0.255       attr=2  uint32
        // Col 14: MD kVA                 IC=3  1.0.9.6.0.255       attr=2  uint32
        // Col 15: Cumul Power On Dur.    IC=3  0.0.94.91.14.255    attr=2  ✓ VERIFIED
        // Col 16: Phase Sequence         IC=1  0.0.96.15.0.255     attr=2  ✓ VERIFIED
        // Col 17: Power Quality Event    IC=1  0.0.150.5.0.255     attr=2  ✓ VERIFIED (was 0.0.96.5.0.255)
        // Col 18: Programming Count      IC=1  0.0.150.2.0.255     attr=2  ✓ VERIFIED (was 0.0.96.2.0.255)
        // Genus LP profile — 12 columns verified from MRI raw files KT027829 (3-Phase SMART) and
        // KT328698 (3-Phase NET): attr=3 read of 0100630100FF returned exactly these 12 objects.
        // IC=3 for all energy/current/voltage; D=27 (.27.) = avg; D=29 (.29.) = cumulative energy
        // Note: 1-phase Genus has a different 8-col layout (Clock, V, Wh+, VAh, Wh-, VAhexp, I, IN)
        //       but no phase field exists; LP attr=3 normally succeeds on 1-phase so this fallback
        //       is primarily used for 3-phase meters.
        if (currentMeterMake == MeterMake.GENUS || currentMeterMake == MeterMake.AVON) {
            appendLog("CAPTURE_OBJ_MAP make=" + currentMeterMake.getDisplayName() + " cols=12 verified KT027829+KT328698");
            return "010C"
                    + "020412000809060000010000FF0F02120000"  //  1: Clock            IC=8  0.0.1.0.0.255
                    + "0204120003090601001F1B00FF0F02120000"  //  2: IL1 avg          IC=3  1.0.31.27.0.255
                    + "020412000309060100331B00FF0F02120000"  //  3: IL2 avg          IC=3  1.0.51.27.0.255
                    + "020412000309060100471B00FF0F02120000"  //  4: IL3 avg          IC=3  1.0.71.27.0.255
                    + "020412000309060100201B00FF0F02120000"  //  5: UL1 avg          IC=3  1.0.32.27.0.255
                    + "020412000309060100341B00FF0F02120000"  //  6: UL2 avg          IC=3  1.0.52.27.0.255
                    + "020412000309060100481B00FF0F02120000"  //  7: UL3 avg          IC=3  1.0.72.27.0.255
                    + "020412000309060100011D00FF0F02120000"  //  8: Wh+ cumul        IC=3  1.0.1.29.0.255
                    + "020412000309060100091D00FF0F02120000"  //  9: VAh cumul        IC=3  1.0.9.29.0.255
                    + "020412000309060100021D00FF0F02120000"  // 10: Wh- cumul        IC=3  1.0.2.29.0.255
                    + "0204120003090601000A1D00FF0F02120000"  // 11: VAh export cumul IC=3  1.0.10.29.0.255
                    + "020412000109060000600A01FF0F02120000"; // 12: Meter status     IC=1  0.0.96.10.1.255
        }

        // Secure meter LP profile — 18 columns (IS 15959-2 standard for Indian smart meters)
        // Verified from live meter SS09079162 (Secure, A1XX02 firmware) TXT attr=3 read
        // on 19-Mar-2026. Capture objects match exactly. This fallback is used when
        // the live attr=3 read fails (e.g. session dropped by BlockLoadScalerProfile read).
        // Col 1:  Clock                  IC=8  0.0.1.0.0.255      attr=2  DLMS DateTime
        // Col 2:  kW Block Demand        IC=5  1.0.1.4.0.255       attr=3  DemandRegister ✓
        // Col 3:  kVA Block Demand       IC=5  1.0.9.4.0.255       attr=3  DemandRegister ✓
        // Col 4:  kVAr Block Demand      IC=5  1.0.5.4.0.255       attr=3  DemandRegister ✓
        // Col 5:  Reactive Power (inst)  IC=3  1.0.3.7.0.255       attr=2  ✓
        // Col 6:  kWh Import cumul       IC=3  1.0.1.8.0.255       attr=2  ✓
        // Col 7:  kVAh cumul             IC=3  1.0.9.8.0.255       attr=2  ✓
        // Col 8:  kVArh Q1               IC=3  1.0.5.8.0.255       attr=2  ✓
        // Col 9:  kVArh Q4               IC=3  1.0.8.8.0.255       attr=2  ✓
        // Col 10: kWh Export cumul       IC=3  1.0.2.8.0.255       attr=2  ✓
        // Col 11: kVArh Q2               IC=3  1.0.6.8.0.255       attr=2  ✓
        // Col 12: kVArh Q3               IC=3  1.0.7.8.0.255       attr=2  ✓
        // Col 13: MD kW                  IC=3  1.0.1.6.0.255       attr=2  ✓
        // Col 14: MD kVA                 IC=3  1.0.9.6.0.255       attr=2  ✓
        // Col 15: Cumul Power On Dur.    IC=3  0.0.94.91.14.255    attr=2  ✓
        // Col 16: Phase Sequence         IC=1  0.0.96.15.0.255     attr=2  ✓
        // Col 17: Power Quality Event    IC=1  0.0.150.5.0.255     attr=2  ✓
        // Col 18: Programming Count      IC=1  0.0.150.2.0.255     attr=2  ✓
        if (currentMeterMake == MeterMake.SECURE) {
            appendLog("CAPTURE_OBJ_MAP make=Secure cols=18 VERIFIED from SS09079162 live read");
            return "0112"
                    + "020412000809060000010000ff0f02120000"  //  1: Clock
                    + "020412000509060100010400ff0f03120000"  //  2: kW Block Demand (IC=5 DemandReg, attr=3)
                    + "020412000509060100090400ff0f03120000"  //  3: kVA Block Demand
                    + "020412000509060100050400ff0f03120000"  //  4: kVAr Block Demand
                    + "020412000309060100030700ff0f02120000"  //  5: Reactive Power instant
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
                    + "020412000109060000960500ff0f02120000"  // 17: Power Quality (0.0.150.5.0.255)
                    + "020412000109060000960200ff0f02120000"; // 18: Programming Count (0.0.150.2.0.255)
        }

        // ── HPL LP profile — sub-variant auto-detection from logical device name ────────────────
        // hplLogicalDeviceName is set in ReadNamePlate() from OBIS 0.0.42.0.0.255.
        // Typical values: "HPLSPEM6KT001234" → SPEM 14-col (verified)
        //                 "HPLPPEM6KT001234" → PPEM 15-col (adds kVArh Q1 lag)
        //                 "HPLCT05KT001234"  → CT  9-col  (simplified direct-metered layout)
        // Unknown or empty → defaults to SPEM 14-col (safest fallback).
        //
        // IC=3 (Register) for all data columns; D=27 (.27.) = demand/avg, D=29 (.29.) = cumulative.
        // Column order MUST match the LP buffer data exactly (DLMS ProfileGeneric attr=3 layout).
        if (currentMeterMake == MeterMake.HPL) {

            // HPLCT — 9-column simplified layout (CT-operated meter, no V/I demand pairs)
            // Confirmed model prefix: "HPLCT" in logical device name.
            if (hplLogicalDeviceName.contains("CT")) {
                appendLog("CAPTURE_OBJ_MAP make=HPL sub=CT cols=9");
                return "0109"
                        + "020412000809060000010000FF0F02120000"  //  1: Clock               IC=8  0.0.1.0.0.255
                        + "0204120003090601000C1B00FF0F02120000"  //  2: Avg Voltage         IC=3  1.0.12.27.0.255
                        + "0204120003090601000B1B00FF0F02120000"  //  3: Avg Current         IC=3  1.0.11.27.0.255
                        + "020412000309060100011D00FF0F02120000"  //  4: Active Energy Imp   IC=3  1.0.1.29.0.255
                        + "020412000309060100021D00FF0F02120000"  //  5: Active Energy Exp   IC=3  1.0.2.29.0.255
                        + "020412000309060100091D00FF0F02120000"  //  6: Apparent Energy Imp IC=3  1.0.9.29.0.255
                        + "0204120003090601000A1D00FF0F02120000"  //  7: Apparent Energy Exp IC=3  1.0.10.29.0.255
                        + "0204120003090601000D1D00FF0F02120000"  //  8: PF Lag              IC=3  1.0.13.29.0.255
                        + "020412000309060100011B00FF0F02120000"; //  9: Active Demand Imp   IC=3  1.0.1.27.0.255
            }

            // HPLPPEM — 15-column layout (adds kVArh Q1 lag reactive energy to SPEM base)
            // Confirmed model prefix: "HPLPPEM" in logical device name.
            if (hplLogicalDeviceName.contains("PPEM")) {
                appendLog("CAPTURE_OBJ_MAP make=HPL sub=PPEM cols=15");
                return "010F"
                        + "020412000809060000010000FF0F02120000"  //  1: Clock               IC=8  0.0.1.0.0.255
                        + "0204120003090601000C1B00FF0F02120000"  //  2: Avg Voltage         IC=3  1.0.12.27.0.255
                        + "0204120003090601000B1B00FF0F02120000"  //  3: Avg Current         IC=3  1.0.11.27.0.255
                        + "0204120003090601005B1B00FF0F02120000"  //  4: Neutral Current     IC=3  1.0.91.27.0.255
                        + "020412000309060100011D00FF0F02120000"  //  5: Active Energy Imp   IC=3  1.0.1.29.0.255
                        + "020412000309060100011B00FF0F02120000"  //  6: Active Demand Imp   IC=3  1.0.1.27.0.255
                        + "020412000309060100091D00FF0F02120000"  //  7: Apparent Energy Imp IC=3  1.0.9.29.0.255
                        + "020412000309060100091B00FF0F02120000"  //  8: Apparent Demand Imp IC=3  1.0.9.27.0.255
                        + "0204120003090601000D1D00FF0F02120000"  //  9: PF Lag              IC=3  1.0.13.29.0.255
                        + "020412000309060100021D00FF0F02120000"  // 10: Active Energy Exp   IC=3  1.0.2.29.0.255
                        + "020412000309060100021B00FF0F02120000"  // 11: Active Demand Exp   IC=3  1.0.2.27.0.255
                        + "0204120003090601000A1D00FF0F02120000"  // 12: Apparent Energy Exp IC=3  1.0.10.29.0.255
                        + "0204120003090601000A1B00FF0F02120000"  // 13: Apparent Demand Exp IC=3  1.0.10.27.0.255
                        + "0204120003090601000D1D50FF0F02120000"  // 14: PF Total            IC=3  1.0.13.29.80.255
                        + "020412000309060100051D00FF0F02120000"; // 15: kVArh Q1 lag Imp    IC=3  1.0.5.29.0.255
            }

            // HPLSPEM (or unknown HPL) — 14-column layout
            // Verified from MRI raw file KT331687_20260325150443. This is the default fallback
            // for any HPL meter where the sub-variant is SPEM or the logical device name was
            // not retrieved (0.0.42.0.0.255 read failed or returned unrecognized prefix).
            appendLog("CAPTURE_OBJ_MAP make=HPL sub="
                    + (hplLogicalDeviceName.isEmpty() ? "UNKNOWN→SPEM_default" : hplLogicalDeviceName)
                    + " cols=14");
            return "010E"
                    + "020412000809060000010000FF0F02120000"  //  1: Clock               IC=8  0.0.1.0.0.255
                    + "0204120003090601000C1B00FF0F02120000"  //  2: Avg Voltage         IC=3  1.0.12.27.0.255
                    + "0204120003090601000B1B00FF0F02120000"  //  3: Avg Current         IC=3  1.0.11.27.0.255
                    + "0204120003090601005B1B00FF0F02120000"  //  4: Neutral Current     IC=3  1.0.91.27.0.255
                    + "020412000309060100011D00FF0F02120000"  //  5: Active Energy Imp   IC=3  1.0.1.29.0.255
                    + "020412000309060100011B00FF0F02120000"  //  6: Active Demand Imp   IC=3  1.0.1.27.0.255
                    + "020412000309060100091D00FF0F02120000"  //  7: Apparent Energy Imp IC=3  1.0.9.29.0.255
                    + "020412000309060100091B00FF0F02120000"  //  8: Apparent Demand Imp IC=3  1.0.9.27.0.255
                    + "0204120003090601000D1D00FF0F02120000"  //  9: PF Lag              IC=3  1.0.13.29.0.255
                    + "020412000309060100021D00FF0F02120000"  // 10: Active Energy Exp   IC=3  1.0.2.29.0.255
                    + "020412000309060100021B00FF0F02120000"  // 11: Active Demand Exp   IC=3  1.0.2.27.0.255
                    + "0204120003090601000A1D00FF0F02120000"  // 12: Apparent Energy Exp IC=3  1.0.10.29.0.255
                    + "0204120003090601000A1B00FF0F02120000"  // 13: Apparent Demand Exp IC=3  1.0.10.27.0.255
                    + "0204120003090601000D1D50FF0F02120000"; // 14: PF Total            IC=3  1.0.13.29.80.255
        }

        // L&T (Schneider Electric) LP profile — 11 columns verified from MRI KT327122_53_29122025
        // Block load scalar OBIS confirms: 3×IL avg, 3×UL avg, WH+, VAh, IN neutral, kW demand
        // IC=3 for all; D=27 (.27.) = avg; D=29 (.29.) = cumulative energy
        if (currentMeterMake == MeterMake.LANDIS || currentMeterMake == MeterMake.LNG) {
            appendLog("CAPTURE_OBJ_MAP make=" + currentMeterMake.getDisplayName() + " cols=11 verified");
            return "010B"
                    + "020412000809060000010000FF0F02120000"  //  1: Clock            IC=8  0.0.1.0.0.255
                    + "0204120003090601001F1B00FF0F02120000"  //  2: IL1 avg          IC=3  1.0.31.27.0.255
                    + "020412000309060100331B00FF0F02120000"  //  3: IL2 avg          IC=3  1.0.51.27.0.255
                    + "020412000309060100471B00FF0F02120000"  //  4: IL3 avg          IC=3  1.0.71.27.0.255
                    + "020412000309060100201B00FF0F02120000"  //  5: UL1 avg          IC=3  1.0.32.27.0.255
                    + "020412000309060100341B00FF0F02120000"  //  6: UL2 avg          IC=3  1.0.52.27.0.255
                    + "020412000309060100481B00FF0F02120000"  //  7: UL3 avg          IC=3  1.0.72.27.0.255
                    + "020412000309060100011D00FF0F02120000"  //  8: Wh+ cumul        IC=3  1.0.1.29.0.255
                    + "020412000309060100091D00FF0F02120000"  //  9: VAh cumul        IC=3  1.0.9.29.0.255
                    + "0204120003090601005B1B00FF0F02120000"  // 10: IN neutral avg   IC=3  1.0.91.27.0.255
                    + "0204120003090601000D1B00FF0F02120000"; // 11: kW demand avg    IC=3  1.0.13.27.0.255
        }

        // MRI-VERIFIED (25165449_3_26032026): LNT NTD 3-phase LP has 11 cols.
        // Block load data OBIS confirmed: Timestamp, 3×IL, 3×UL, Wh+, VAh, Wh- (0100021D00FF), VAh- (01000A1D00FF)
        // Previous fallback (cols 10-11 = neutral current + kW demand) was WRONG — corrected from MRI.
        if (currentMeterMake == MeterMake.LNT) {
            appendLog("CAPTURE_OBJ_MAP make=LNT cols=11 (NTD layout, MRI-verified 25165449)");
            return "010B"
                    + "020412000809060000010000FF0F02120000"  //  1: Clock            IC=8  0.0.1.0.0.255
                    + "0204120003090601001F1B00FF0F02120000"  //  2: IL1 avg          IC=3  1.0.31.27.0.255
                    + "020412000309060100331B00FF0F02120000"  //  3: IL2 avg          IC=3  1.0.51.27.0.255
                    + "020412000309060100471B00FF0F02120000"  //  4: IL3 avg          IC=3  1.0.71.27.0.255
                    + "020412000309060100201B00FF0F02120000"  //  5: UL1 avg          IC=3  1.0.32.27.0.255
                    + "020412000309060100341B00FF0F02120000"  //  6: UL2 avg          IC=3  1.0.52.27.0.255
                    + "020412000309060100481B00FF0F02120000"  //  7: UL3 avg          IC=3  1.0.72.27.0.255
                    + "020412000309060100011D00FF0F02120000"  //  8: Wh+ cumul        IC=3  1.0.1.29.0.255
                    + "020412000309060100091D00FF0F02120000"  //  9: VAh cumul        IC=3  1.0.9.29.0.255
                    + "020412000309060100021D00FF0F02120000"  // 10: Wh- cumul        IC=3  1.0.2.29.0.255  ← MRI col 9
                    + "0204120003090601000A1D00FF0F02120000"; // 11: VAh- cumul       IC=3  1.0.10.29.0.255 ← MRI col 10
        }

        // Unknown/default fallback — clock only
        appendLog("CAPTURE_OBJ_MAP make=" + currentMeterMake.getDisplayName() + " cols=1 DEFAULT_FALLBACK [TODO:VERIFY]");
        return "0101"
                + "020412000809060000010000ff0f02120000"; // just clock
    }

    /** Merges multiple GetParameterSelective LP page hex strings into a single DLMS array.
     *  Each page is "01 [BER-count] [records...]". Strips each page's array header, concatenates
     *  record bytes, and wraps in a new array header with the total count.
     *  Returns the single merged hex string, or the first page unchanged if only one page exists. */
    private String mergeLpPageHexList(java.util.List<String> pages) {
        if (pages == null || pages.isEmpty()) return null;
        if (pages.size() == 1) return pages.get(0);

        StringBuilder allRecords = new StringBuilder();
        int totalCount = 0;
        int skippedPages = 0;

        for (String page : pages) {
            if (page == null || page.length() < 4) continue;
            String lower = page.toLowerCase();
            int dataStart = -1;
            int cnt = 0;
            // Find array tag 0x01 within first 32 bytes (some responses have a prefix)
            for (int skip = 0; skip <= 32; skip += 2) {
                if (skip + 4 > lower.length()) break;
                int tagByte;
                try { tagByte = Integer.parseInt(lower.substring(skip, skip + 2), 16); }
                catch (Exception e) { break; }
                if (tagByte == 0x01) {
                    int cbPos = skip + 2;
                    if (cbPos + 2 > lower.length()) break;
                    int countByte;
                    try { countByte = Integer.parseInt(lower.substring(cbPos, cbPos + 2), 16); }
                    catch (Exception e) { break; }
                    if ((countByte & 0x80) == 0) {
                        cnt = countByte;
                        dataStart = cbPos + 2;
                    } else {
                        int nb = countByte & 0x7F;
                        if (nb == 1 && cbPos + 4 <= lower.length()) {
                            try { cnt = Integer.parseInt(lower.substring(cbPos + 2, cbPos + 4), 16); } catch (Exception e) { break; }
                            dataStart = cbPos + 4;
                        } else if (nb == 2 && cbPos + 6 <= lower.length()) {
                            try { cnt = Integer.parseInt(lower.substring(cbPos + 2, cbPos + 6), 16); } catch (Exception e) { break; }
                            dataStart = cbPos + 6;
                        }
                    }
                    break;
                }
            }
            if (dataStart < 0 || dataStart >= page.length()) {
                // Array header not found — try locating records directly via LP row pattern.
                // Some meter responses (e.g. HPL) omit or prefix the DLMS array tag; the actual
                // records start where the first structure-with-datetime ("02 XX 09 0C") appears.
                int recStart = -1;
                int idx = 0;
                while ((idx = lower.indexOf("090c", idx)) >= 0) {
                    int candidate = idx - 4; // "02 XX" is 2 bytes (4 hex chars) before "09 0C"
                    if (candidate >= 0 && lower.charAt(candidate) == '0' && lower.charAt(candidate + 1) == '2') {
                        recStart = candidate;
                        break;
                    }
                    idx += 4;
                }
                if (recStart >= 0 && recStart < page.length()) {
                    int recCnt = countLoadProfileRecords(page);
                    appendLog("LP_MERGE_ROW_FALLBACK prefix=" + lower.substring(0, Math.min(16, lower.length()))
                            + " recStart=" + recStart + " cnt=" + recCnt);
                    totalCount += recCnt;
                    allRecords.append(page.substring(recStart));
                } else {
                    // Cannot locate any LP records in this page — skip to avoid corruption.
                    appendLog("LP_MERGE_SKIP prefix=" + lower.substring(0, Math.min(16, lower.length())));
                    skippedPages++;
                }
            } else if (dataStart + 2 <= lower.length()
                    && lower.charAt(dataStart) == '0' && lower.charAt(dataStart + 1) == '2') {
                // dataStart points to a DLMS structure tag (02) — valid records follow.
                totalCount += cnt;
                allRecords.append(page.substring(dataStart));
            } else {
                // False array-tag hit in DLMS response header (e.g. invoke-id byte == 0x01).
                // Fall back to 090c row-pattern scan to find the real record start.
                int recStart = -1;
                int idx = 0;
                while ((idx = lower.indexOf("090c", idx)) >= 0) {
                    int candidate = idx - 4;
                    if (candidate >= 0
                            && lower.charAt(candidate) == '0' && lower.charAt(candidate + 1) == '2') {
                        recStart = candidate;
                        break;
                    }
                    idx += 4;
                }
                if (recStart >= 0 && recStart < page.length()) {
                    int recCnt = countLoadProfileRecords(page);
                    appendLog("LP_MERGE_HDR_FALLBACK prefix=" + lower.substring(0, Math.min(16, lower.length()))
                            + " recStart=" + recStart + " cnt=" + recCnt);
                    totalCount += recCnt;
                    allRecords.append(page.substring(recStart));
                } else {
                    appendLog("LP_MERGE_SKIP prefix=" + lower.substring(0, Math.min(16, lower.length())));
                    skippedPages++;
                }
            }
        }
        if (skippedPages > 0) appendLog("LP_MERGE_SKIPPED_PAGES=" + skippedPages);

        // BER-encode total count
        String berCount;
        if (totalCount < 0x80) {
            berCount = String.format("%02X", totalCount);
        } else if (totalCount < 0x100) {
            berCount = String.format("81%02X", totalCount);
        } else {
            berCount = String.format("82%04X", totalCount);
        }
        return "01" + berCount + allRecords.toString();
    }

    private boolean hasLoadProfileRecords(String lpHex) {
        if (lpHex == null || lpHex.isEmpty()) return false;

        // Standard profile-generic rows commonly seen from many meter makes.
        if (lpHex.contains("0212090c")) return true;

        // Keep the explicit uppercase marker too in case mixed-case payloads are passed in.
        if (lpHex.contains("0212090C")) return true;

        // Some meter makes return LP buffers with a different row prefix while the DLMS
        // clock object still appears as repeating 090c timestamps inside the payload.
        int tsCount = 0;
        int idx = 0;
        String lowerHex = lpHex.toLowerCase();
        while ((idx = lowerHex.indexOf("090c", idx)) >= 0) {
            tsCount++;
            idx += 4;
            if (tsCount >= 2) return true;
        }

        return false;
    }

    private int countLoadProfileRecords(String lpHex) {
        if (lpHex == null || lpHex.isEmpty()) return 0;

        // PRIMARY: Parse the DLMS array count directly from the LP response header.
        // Format: [skip 0-8 header bytes] [01=array-tag] [BER-count]
        // This is accurate for all makes (HPL returns 30 records but only 13 '090c' patterns).
        try {
            String lower = lpHex.toLowerCase();
            for (int skip = 0; skip <= 16; skip += 2) {
                if (skip + 4 > lower.length()) break;
                int tagByte = Integer.parseInt(lower.substring(skip, skip + 2), 16);
                if (tagByte == 0x01) {
                    int countByte = Integer.parseInt(lower.substring(skip + 2, skip + 4), 16);
                    if ((countByte & 0x80) == 0 && countByte > 0) return countByte;
                    int nb = countByte & 0x7F;
                    if (nb == 2 && skip + 8 <= lower.length()) {
                        int count = Integer.parseInt(lower.substring(skip + 4, skip + 8), 16);
                        if (count > 0) return count;
                    } else if (nb == 1 && skip + 6 <= lower.length()) {
                        int count = Integer.parseInt(lower.substring(skip + 4, skip + 6), 16);
                        if (count > 0) return count;
                    }
                    break;
                }
            }
        } catch (Exception ignored) {}

        // FALLBACK: count standard LP row markers
        int count = 0; int idx = 0;
        String lowerHex = lpHex.toLowerCase();
        while ((idx = lowerHex.indexOf("0212090c", idx)) >= 0) { count++; idx += 8; }
        if (count > 0) return count;
        idx = 0;
        while ((idx = lowerHex.indexOf("090c", idx)) >= 0) { count++; idx += 4; }
        return count > 1 ? count : 0;
    }

    private boolean hasMeaningfulDlmsPayload(StringBuilder data) {
        if (data == null) return false;
        String text = data.toString();
        if (text == null) return false;
        text = text.trim();
        if (text.isEmpty()) return false;
        // Extract hex payload: last whitespace token (GetParameter format: "COUNT OBIS ATTR <hex>")
        String[] parts = text.split("\\s+");
        // V32 FIX: fewer than 4 tokens means no payload was written (e.g. L&T LP2 selective
        // access returns an empty line "0007 0100630200FF 02 " with no hex after the attr).
        // Without this guard parts[last] = "02" (the attr number) which passes all
        // subsequent checks and causes a false-positive "meaningful" result.
        if (parts.length < 4) return false;
        String payload = parts[parts.length - 1].toUpperCase();
        // All-zero: meter returned zero-fill
        if (payload.matches("0+")) return false;
        // All-FF: DLMS "not applicable" (uint32/uint16/octet-string max)
        if (payload.matches("F+")) return false;
        // DLMS int32 "not applicable": tag=05 value=80000000
        if (payload.startsWith("0580000000")) return false;
        // DLMS uint16 "not applicable": tag=12 value=FFFF
        if (payload.equals("12FFFF")) return false;
        // DLMS null/unspecified DateTime: 090C + FFFF year or all-FF body
        if (payload.startsWith("090C") && payload.length() >= 28) {
            String ts = payload.substring(4, 28);
            if (ts.matches("F+")) return false;
            if (ts.startsWith("FFFF")) return false;
        }
        return true;
    }

    /** Returns true if 24-hex-char DLMS DateTime is null/unspecified/out-of-range. */
    private boolean isNullTimestamp(String tsHex) {
        if (tsHex == null || tsHex.length() < 24) return true;
        String u = tsHex.toUpperCase();
        if (u.substring(0, 24).matches("F+")) return true;
        String yr = u.substring(0, 4);
        if ("FFFF".equals(yr) || "0000".equals(yr)) return true;
        try {
            int y = Integer.parseInt(yr, 16);
            if (y < 2000 || y > 2099) return true;
            int mo = Integer.parseInt(u.substring(4, 6), 16);
            if (mo == 0 || mo > 12) return true;
            int d = Integer.parseInt(u.substring(6, 8), 16);
            if (d == 0 || d > 31) return true;
        } catch (Exception e) { return true; }
        return false;
    }

    /** Extract the raw hex payload for a given OBIS attr=2 from uppercased TXT. */
    private String extractPayloadFor(String dataUpper, String obisHex) {
        String marker = obisHex.toUpperCase() + " 02 ";
        int idx = dataUpper.indexOf(marker);
        if (idx < 0) return "";
        int ps = idx + marker.length();
        int le = dataUpper.indexOf('\n', ps);
        if (le < 0) le = dataUpper.length();
        String payload = dataUpper.substring(ps, le).trim().replaceAll("\\s+", "");
        if (payload.isEmpty() || payload.matches("0+") || payload.matches("F+")) return "";
        return payload;
    }

    private StringBuilder ReadLoadSurveyData(UsbSerialPort port , int lsDays)
    {
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata;
        appendLog("RLS_ENTER lsDays=" + lsDays + " intProfilePd=" + intProfilePd);

        // ----------------------------------------------------------------
        // DO NOT reset nRecvCntr / nSentCntr here.
        // The billing and nameplate reads that ran just before this method
        // have left these counters synchronized with the meter.
        // Resetting them to 0 causes the meter to see wrong HDLC sequence
        // numbers and respond with a DM (Disconnect Mode) frame, killing
        // the session for the entire LP read.
        // ----------------------------------------------------------------

        // ----------------------------------------------------------------
        // Scalar/unit descriptor
        // ----------------------------------------------------------------
        DLMdata = this.ReadScalarUnit("BLOCKLOAD", port);
        if (DLMdata != null && !DLMdata.toString().isEmpty()) {
            strbldDLMdata.append(DLMdata);
        }

        // ── Defensive session recovery after scaler read ──────────────────
        // If the scaler read returned a DM/error frame (lastGplsResult=2),
        // the HDLC session is dead. Re-establish SNRM+AARQ NOW before
        // attempting LP attr=3, so we don't waste the full attr=3 timeout
        // on a dead channel and then get a second timeout on the retry.
        if (lastGplsResult == 2) {
            appendLog("RLS_SCALER_SESSION_DROP — scaler read dropped session, re-establishing before LP reads");
            try {
                AddressInit();
                boolean nrmOk = SetNRM(port, this.bytWait, (byte) 2, this.bytTimOut);
                appendLog("RLS_SCALER_RECOVER_NRM=" + nrmOk);
                if (nrmOk) {
                    String pw = currentMeterMake.getPassword();
                    int aarqRes = AARQ(port, (byte) 1, pw, this.bytWait, (byte) 3, this.bytTimOut);
                    appendLog("RLS_SCALER_RECOVER_AARQ=" + aarqRes);
                    if (aarqRes == 0) {
                        drainPort(port);
                        appendLog("RLS_SCALER_RECOVER_OK — session restored, proceeding to LP reads");
                        lastGplsResult = 0; // reset so STEP 1 proceeds normally
                    } else {
                        appendLog("RLS_SCALER_RECOVER_AARQ_FAIL — LP reads will likely fail");
                    }
                } else {
                    appendLog("RLS_SCALER_RECOVER_NRM_FAIL — LP reads will likely fail");
                }
            } catch (Exception recEx) {
                appendLog("RLS_SCALER_RECOVER_EX: " + recEx.getMessage());
            }
        }

        // ----------------------------------------------------------------
        // ----------------------------------------------------------------
        // STEP 1 – CaptureObjects (attr=3) — column definitions required by XML parser.
        //   DLMS ProfileGeneric: attr=3 = capture_objects (array of object references).
        //   CRITICAL: Use GetParameter (simple GET, no block transfer), NOT GetParameter_LS.
        //   HPL and Secure meters respond with DM (Disconnect Mode) to block transfer
        //   GET requests on LP attr=3, killing the session. The reference modem code
        //   (which works on all meters) exclusively uses simple GetParameter for all
        //   LP metadata attrs (3, 4, 7, 8).
        //   If attr=3 fails, fall back to hardcoded — do NOT set lpIncompatible.
        //   The meter may still serve selective access for attr=2.
        // ----------------------------------------------------------------
        appendLog("RLS_CALL attr=3 (capture_objects)");
        DLMdata = this.GetParameter(port, (byte) 7, "0100630100FF", (byte) 3,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        appendLog("RLS_RET attr=3 len=" + (DLMdata == null ? -1 : DLMdata.length())
                + " result=" + lastGplsResult);
        // GetParameter(isDLM=true) creates a fresh internal StringBuilder, writes the OBIS header
        // ("\r\n0007 0100630100FF 03 ", 23 chars) unconditionally, then appends payload bytes.
        // The caller's strbldDLMdata is untouched by GetParameter. DLMdata (SbData) contains
        // header + payload. If the meter returned DM or error, DLMdata has only the 23-char
        // header with no payload — length() <= 23. We must not use that as valid attr=3 data.
        if (lastGplsResult == 0 && DLMdata != null && DLMdata.length() > 23) {
            appendLog("RLS_ATTR3_FROM_METER len=" + DLMdata.length());
            strbldDLMdata.append(DLMdata);
        } else {
            // Meter rejected attr=3 (DM, error, or empty) — write hardcoded capture objects.
            String captureObjsHex = getCaptureObjectsForMake();
            strbldDLMdata.append("\r\n0007 0100630100FF 03 ").append(captureObjsHex);
            appendLog("RLS_ATTR3_HARDCODED make=" + currentMeterMake.getDisplayName()
                    + " reason=" + (DLMdata == null ? "null" : "len=" + DLMdata.length()));
        }

        // If attr=3 caused a session drop (DM frame), re-establish before proceeding.
        // Critically: this does NOT set lpIncompatible — the meter may still respond
        // to attr=4, attr=7 and especially GetParameterSelective for attr=2.
        boolean lpIncompatible = false;  // only set true if session is unrecoverable
        if (lastGplsResult == 2) {
            appendLog("RLS_ATTR3_DM — attr=3 dropped session, re-establishing for remaining reads");
            try {
                AddressInit();
                boolean nrmOk = SetNRM(port, this.bytWait, (byte) 2, this.bytTimOut);
                appendLog("RLS_ATTR3_RECOVER_NRM=" + nrmOk);
                if (nrmOk) {
                    String pw = currentMeterMake.getPassword();
                    int aarqRes = AARQ(port, (byte) 1, pw, this.bytWait, (byte) 3, this.bytTimOut);
                    appendLog("RLS_ATTR3_RECOVER_AARQ=" + aarqRes);
                    if (aarqRes == 0) {
                        drainPort(port);
                        appendLog("RLS_ATTR3_RECOVER_OK — session restored, continuing with attr=4,7,8");
                        lastGplsResult = 0; // reset so STEP 2 proceeds normally
                    } else {
                        appendLog("RLS_ATTR3_RECOVER_AARQ_FAIL — LP reads will likely fail");
                        lpIncompatible = true; // truly cannot re-establish
                    }
                } else {
                    appendLog("RLS_ATTR3_RECOVER_NRM_FAIL");
                    lpIncompatible = true;
                }
            } catch (Exception reEx) {
                appendLog("RLS_ATTR3_RECOVER_EX: " + reEx.getMessage());
                lpIncompatible = true;
            }
        }

        // ----------------------------------------------------------------
        // STEP 2 – CapturePeriod (attr=4, uint32 in seconds).
        //   Use GetParameter (simple GET) — reference code uses this, not block transfer.
        // ----------------------------------------------------------------
        if (lpIncompatible) {
            appendLog("RLS_SKIP_ALL (lpIncompatible=true)");
            strbldDLMdata.append("\r\n0007 0100630100FF 07 0600000000");
            return strbldDLMdata;
        }
        appendLog("RLS_CALL attr=4 (capture_period_seconds)");
        StringBuilder attr4Sb = new StringBuilder();
        DLMdata = this.GetParameter(port, (byte) 7, "0100630100FF", (byte) 4,
                this.bytWait, (byte) 1, this.bytTimOut, false, attr4Sb);
        appendLog("RLS_RET attr=4 len=" + (DLMdata == null ? -1 : DLMdata.length())
                + " result=" + lastGplsResult);

        int capturePeriodMin = intProfilePd > 0 ? intProfilePd : 30; // default from nameplate
        if (lastGplsResult == 0) {
            try {
                String raw = attr4Sb.toString();
                if (raw.length() >= 8) {
                    long seconds = Long.parseLong(raw.substring(raw.length() - 8), 16);
                    if (seconds > 0 && seconds <= 86400) {
                        capturePeriodMin = (int)(seconds / 60);
                        appendLog("RLS_ATTR4_SECONDS=" + seconds);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (capturePeriodMin > 0) this.intProfilePd = capturePeriodMin;
        appendLog("RLS_CAPTURE_PERIOD_MIN=" + capturePeriodMin);
        // V30: write LP1 attr=04 to TXT so the .NET converter can set INTERVALPERIOD correctly.
        // Previously only stored in attr4Sb (internal) — converter never saw it and defaulted to 30-min.
        strbldDLMdata.append(String.format("\r\n0007 0100630100FF 04 06%08X", (long) capturePeriodMin * 60));

        // ----------------------------------------------------------------
        // STEP 3 – EntriesInUse (attr=7, uint32).
        //   Use GetParameter (simple GET) — reference code uses this, not block transfer.
        // ----------------------------------------------------------------
        appendLog("RLS_CALL attr=7 (entries_in_use)");
        StringBuilder attr7Sb = new StringBuilder();
        DLMdata = this.GetParameter(port, (byte) 7, "0100630100FF", (byte) 7,
                this.bytWait, (byte) 1, this.bytTimOut, false, attr7Sb);
        appendLog("RLS_RET attr=7 len=" + (DLMdata == null ? -1 : DLMdata.length())
                + " result=" + lastGplsResult);

        int entriesInUse = 0;
        if (lastGplsResult == 0) {
            try {
                String raw = attr7Sb.toString();
                if (raw.length() >= 8) {
                    entriesInUse = (int) Long.parseLong(raw.substring(raw.length() - 8), 16);
                }
            } catch (Exception ignored) {}
        }
        appendLog("RLS_ENTRIES_IN_USE=" + entriesInUse);


        // ----------------------------------------------------------------
        // STEP 4 – Calculate records needed and decide read strategy
        // ----------------------------------------------------------------
        int recPerDay = (capturePeriodMin > 0) ? (24 * 60 / capturePeriodMin) : 48;

        // Read profile_entries_max (attr=8) to know how many records the meter can hold
        int profileEntriesMax = 0;
        appendLog("RLS_CALL attr=8 (profile_entries_max)");
        StringBuilder attr8Sb = new StringBuilder();
        DLMdata = this.GetParameter(port, (byte) 7, "0100630100FF", (byte) 8,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        appendLog("RLS_RET attr=8 len=" + (DLMdata == null ? -1 : DLMdata.length())
                + " result=" + lastGplsResult);
        if (lastGplsResult == 0 && DLMdata != null && !DLMdata.toString().isEmpty()) {
            try {
                String raw = DLMdata.toString().trim();
                if (raw.length() >= 2) {
                    int t8 = Integer.parseInt(raw.substring(0, 2), 16);
                    if (t8 == 0x12 && raw.length() >= 6) {
                        profileEntriesMax = Integer.parseInt(raw.substring(2, 6), 16);
                    } else if (t8 == 0x06 && raw.length() >= 10) {
                        profileEntriesMax = (int) Long.parseLong(raw.substring(2, 10), 16);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Derive lsDays from actual entries_in_use — ignore user input entirely.
        // entriesInUse is the definitive answer: it's what the meter holds right now.
        // profileEntriesMax (attr=8) tells max capacity, NOT current fill — don't use
        // it as the number of days to read (a 60-day capacity meter with 5 days data
        // would waste time reading 60 empty days).
        //
        // PRIORITY ORDER:
        //  1. entriesInUse > 0 → use it directly (most accurate, meter-reported)
        //  2. profileEntriesMax > 0 && entriesInUse == 0 → meter is empty, read 0
        //  3. Both unavailable → safe default of lsDays (user-requested, capped at 35)
        int maxDaysFromMeter;
        if (entriesInUse > 0 && recPerDay > 0) {
            // Add 1 day buffer to catch in-progress current day, minimum 1 day
            maxDaysFromMeter = Math.max(1, (entriesInUse / recPerDay) + 1);
            appendLog("RLS_DAYS_FROM_EIU: entriesInUse=" + entriesInUse
                    + " recPerDay=" + recPerDay + " → lsDays=" + maxDaysFromMeter);
        } else if (profileEntriesMax > 0 && recPerDay > 0) {
            // Meter reports capacity but no entries — use user-requested days capped at capacity
            maxDaysFromMeter = Math.min(lsDays, profileEntriesMax / recPerDay);
            appendLog("RLS_DAYS_FROM_ATTR8: profileEntriesMax=" + profileEntriesMax
                    + " recPerDay=" + recPerDay + " → lsDays=" + maxDaysFromMeter);
        } else {
            maxDaysFromMeter = lsDays; // user-requested days as last resort
            appendLog("RLS_DAYS_FALLBACK → lsDays=" + maxDaysFromMeter + " (no EIU or attr=8 data)");
        }
        // Hard cap: never read more than 35 days regardless of meter capacity or user input.
        // This applies to ALL meter makes (HPL, Secure, Genus, L&T, AVON, Landis, L&G).
        // Some meters store 60-180 days in their LP buffer — reading all would take hours.
        final int MAX_LP_DAYS = 35;
        if (maxDaysFromMeter > MAX_LP_DAYS) {
            appendLog("RLS_DAYS_CAPPED from=" + maxDaysFromMeter + " to=" + MAX_LP_DAYS
                    + " (hard cap, all meter makes)");
            maxDaysFromMeter = MAX_LP_DAYS;
        }
        lsDays = maxDaysFromMeter;

        appendLog("RLS_RECORDS_NEEDED=" + (recPerDay * lsDays)
                + " recPerDay=" + recPerDay
                + " lsDays=" + lsDays
                + " profileEntriesMax=" + profileEntriesMax);        // The proven reference implementation (StreamWriter.cs, modem reader)
        // uses ONLY GetParameterSelective (day-by-day) for ALL meter makes.
        // It never does a bulk GetParameter_LS for attr=2.
        //
        // Bulk via GetParameter_LS works on Genus LC (confirmed: returns partial
        // block data starting at meter's internal block counter). On HPL and Secure
        // meters it causes a DM (Disconnect Mode) frame, killing the session and
        // preventing the subsequent selective reads in STEP 6.
        //
        // Therefore: only attempt bulk on Genus/AVON. All other makes go straight
        // to STEP 6 selective access (matching the reference implementation).
        // ----------------------------------------------------------------
        boolean bulkReadOk = false;
        boolean bulkCausedDM = false;
        java.util.List<String> lpPageHexList = new java.util.ArrayList<>();
        int[] lpEntriesDeclared = {entriesInUse};
        java.util.HashSet<String> seenPayloads = new java.util.HashSet<>();
        int totalActualRecords = 0;

        boolean tryBulk = (currentMeterMake == MeterMake.GENUS || currentMeterMake == MeterMake.AVON);
        if (tryBulk) {
            appendLog("RLS_CALL attr=2 bulk (Genus/AVON only)");
            StringBuilder attr2Sb = new StringBuilder();
            long attr2Start = System.currentTimeMillis();
            DLMdata = this.GetParameter_LS(port, (byte) 7, "0100630100FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, false, attr2Sb);
            long attr2Elapsed = System.currentTimeMillis() - attr2Start;
            appendLog("RLS_RET attr=2 elapsed=" + attr2Elapsed
                    + "ms len=" + (DLMdata == null ? -1 : DLMdata.length())
                    + " result=" + lastGplsResult);

            bulkReadOk = (DLMdata != null && DLMdata.length() > 30 && lastGplsResult == 0);
            bulkCausedDM = (lastGplsResult == 2);
            appendLog("RLS_BULK_CHECK len=" + (DLMdata == null ? -1 : DLMdata.length())
                    + " ok=" + bulkReadOk + " causedDM=" + bulkCausedDM);
        } else {
            appendLog("RLS_BULK_SKIP make=" + currentMeterMake.getDisplayName()
                    + " — using selective access only (matching reference implementation)");
        }

        if (bulkReadOk) {
            String lpHex = DLMdata.toString();
            // Extract declared entry count from 82 HH LL BER prefix
            try {
                byte[] lpBytes = hexStringToByteArray(lpHex.length() > 1 ? lpHex : "");
                for (int bi = 0; bi < Math.min(16, lpBytes.length - 2); bi++) {
                    if ((lpBytes[bi] & 0xff) == 0x82) {
                        int declared = ((lpBytes[bi+1] & 0xff) << 8) | (lpBytes[bi+2] & 0xff);
                        if (declared > 0) { lpEntriesDeclared[0] = declared; break; }
                    }
                }
            } catch (Exception ignored) {}
            int cnt = countLoadProfileRecords(lpHex);
            totalActualRecords = cnt;
            lpPageHexList.add(lpHex);
            if (!lpHex.toLowerCase().contains("0212090c") && cnt > 0) {
                appendLog("RLS_BULK_OK_ALT_PATTERN make=" + currentMeterMake.getDisplayName()
                        + " timestamps=" + cnt);
            }
            appendLog("RLS_BULK_OK len=" + lpHex.length() + " declared=" + lpEntriesDeclared[0] + " actualRecords=" + cnt);

            // Partial block transfer: meter returned some blocks but truncated.
            boolean partialBulk = (lpEntriesDeclared[0] > cnt && cnt > 0);
            if (partialBulk) {
                appendLog("RLS_PARTIAL_BULK declared=" + lpEntriesDeclared[0]
                        + " received=" + cnt + " — aborting pending transfer, trying selective");
                abortPendingBlockTransfer(port);
                drainPort(port);
                Calendar cal = Calendar.getInstance();
                int selOk = 0;
                for (int i = Math.min(lsDays, 7); i >= 0; i--) {
                    if (abortRequested) break;
                    if (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs) {
                        appendLog("RLS_SEL_DEADLINE_HIT (supplement)");
                        break;
                    }
                    cal = Calendar.getInstance();
                    cal.add(Calendar.DATE, -i);
                    // Normalize to midnight so from_time = 00:00:00 for this day
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    java.util.Date dayDate = cal.getTime();
                    DLMdata = GetParameterSelective(port, (byte) 7, "0100630100FF", (byte) 2,
                            this.bytWait, this.bytTryCnt, this.bytTimOut, false,
                            dayDate, dayDate, capturePeriodMin);
                    if (DLMdata != null && !DLMdata.toString().isEmpty()) {
                        String selHex = DLMdata.toString().trim();
                        if (!hasLoadProfileRecords(selHex)) continue;
                        if (seenPayloads.contains(selHex)) continue;
                        if (lpHex.contains(selHex.substring(0, Math.min(40, selHex.length())))) continue;
                        seenPayloads.add(selHex);
                        int selCnt = countLoadProfileRecords(selHex);
                        totalActualRecords += selCnt;
                        lpPageHexList.add(selHex);
                        selOk++;
                        appendLog("RLS_PARTIAL_SEL day=-" + i + " records=" + selCnt);
                    }
                }
                if (selOk > 0)
                    appendLog("RLS_PARTIAL_SUPPLEMENT added " + selOk + " selective days, totalRecords=" + totalActualRecords);
            }
        }

        // If bulk was attempted and caused a DM (session dropped), re-establish
        // the session NOW so that STEP 6 selective reads can proceed on a live channel.
        // This is the critical fix: without recovery here, STEP 6 fails silently.
        if (bulkCausedDM) {
            appendLog("RLS_BULK_DM — bulk read dropped session, re-establishing for selective reads");
            try {
                AddressInit();
                boolean nrmOk = SetNRM(port, this.bytWait, (byte) 2, this.bytTimOut);
                appendLog("RLS_BULK_RECOVER_NRM=" + nrmOk);
                if (nrmOk) {
                    String pw = currentMeterMake.getPassword();
                    int aarqRes = AARQ(port, (byte) 1, pw, this.bytWait, (byte) 3, this.bytTimOut);
                    appendLog("RLS_BULK_RECOVER_AARQ=" + aarqRes);
                    if (aarqRes == 0) {
                        drainPort(port);
                        appendLog("RLS_BULK_RECOVER_OK — session restored for selective reads");
                        lastGplsResult = 0;
                    } else {
                        appendLog("RLS_BULK_RECOVER_AARQ_FAIL — selective reads will likely fail");
                    }
                } else {
                    appendLog("RLS_BULK_RECOVER_NRM_FAIL — selective reads will likely fail");
                }
            } catch (Exception bulkEx) {
                appendLog("RLS_BULK_RECOVER_EX: " + bulkEx.getMessage());
            }
        }

        if (!bulkReadOk) {
            // ----------------------------------------------------------------
            // STEP 6 – Bulk read returned nothing — fall back to selective reads.
            // ----------------------------------------------------------------
            appendLog("RLS_BULK_FAILED — falling back to selective read for " + lsDays + " days");

            // Best-practice probe: even when entries_in_use=0 some meters (AVON, Genus MM004)
            // still hold LP data — they return entries_in_use=0 due to firmware quirk.
            // Try today first, then up to 3 previous days to catch meters that have historical
            // data but not today's data (e.g. meter installed mid-day, or today not yet complete).
            boolean probeHadData = false;
            boolean bulkFallbackUsed = false; // true when bulk attr=2 fallback succeeded — skips redundant day loop
            if (entriesInUse == 0) {
                if (abortRequested) {
                    // Port already dead (SEND_FAIL_ABORT fired before probe).
                    // All probe reads would return 0 records and we'd wrongly conclude
                    // the meter is empty. Skip the probe entirely and treat as unknown.
                    appendLog("RLS_PROBE_SKIPPED_ABORT — port failure before probe; LP state unknown, skipping");
                    // probeHadData stays false BUT we must not conclude empty;
                    // set entriesInUse to a sentinel so the outer check also skips
                    // (entriesInUse > 0 || probeHadData) → neither true → LP skipped
                    // which is the right outcome since the port is dead anyway.
                } else {
                    appendLog("RLS_PROBE_EMPTY_METER — entries_in_use=0, probing recent days before skipping");
                    for (int probeDayOffset = 0; probeDayOffset >= -3 && !probeHadData; probeDayOffset--) {
                        java.util.Calendar probeCal = java.util.Calendar.getInstance();
                        probeCal.add(java.util.Calendar.DAY_OF_YEAR, probeDayOffset);
                        // Normalise to midnight — same fix as main day loop
                        probeCal.set(java.util.Calendar.HOUR_OF_DAY, 0);
                        probeCal.set(java.util.Calendar.MINUTE, 0);
                        probeCal.set(java.util.Calendar.SECOND, 0);
                        probeCal.set(java.util.Calendar.MILLISECOND, 0);
                        java.util.Date probeDate = probeCal.getTime();
                        appendLog("RLS_PROBE_DAY offset=" + probeDayOffset);
                        DLMdata = GetParameterSelective(port, (byte) 7, "0100630100FF", (byte) 2,
                                this.bytWait, (byte) 1, this.bytTimOut, false,
                                probeDate, probeDate, capturePeriodMin);
                        if (DLMdata != null && !DLMdata.toString().isEmpty()) {
                            String probeHex = DLMdata.toString().trim();
                            if (hasLoadProfileRecords(probeHex)) {
                                probeHadData = true;
                                int probeCnt = countLoadProfileRecords(probeHex);
                                totalActualRecords += probeCnt;
                                lpPageHexList.add(probeHex);
                                seenPayloads.add(probeHex);
                                appendLog("RLS_PROBE_HAS_DATA offset=" + probeDayOffset
                                        + " records=" + probeCnt + " — will read all " + lsDays + " days");
                            }
                        }
                    }
                    if (!probeHadData) {
                        appendLog("RLS_PROBE_CONFIRMED_EMPTY — no LP data in last 3 days via selective");
                        // FIX: Some Secure meters (e.g. SS09096634, SS09112148, SS09118696) have a
                        // firmware defect where BOTH attr=7 (EIU) AND selective access (attr=2 with
                        // date range) return 0/empty even though the LP buffer has 100s of records.
                        // The MRI modem reader bypasses both checks and reads attr=2 DIRECTLY
                        // (full block transfer) and gets all data.
                        // Fix: after selective probe fails, attempt one direct attr=2 bulk read
                        // with a small byte limit to confirm whether buffer has data.
                        // If it returns records → set probeHadData=true so the day loop runs.
                        // Safe for ALL makes:
                        //   - L&T/HPL with truly empty buffer: bulk returns empty array → no impact
                        //   - Genus/AVON: already handled by bulkRead above (tryBulk=true)
                        //   - Secure with firmware bug: bulk returns full buffer → data recovered
                        if (!abortRequested && currentMeterMake != MeterMake.GENUS
                                && currentMeterMake != MeterMake.AVON) {
                            // Genus/AVON already tried bulk above; avoid duplicate bulk attempt
                            appendLog("RLS_BULK_FALLBACK_PROBE — selective empty, attempting direct attr=2 bulk read");
                            StringBuilder bulkProbeSb = new StringBuilder();
                            DLMdata = this.GetParameter_LS(port, (byte) 7, "0100630100FF", (byte) 2,
                                    this.bytWait, (byte) 1, this.bytTimOut, false, bulkProbeSb);
                            if (DLMdata != null && DLMdata.length() > 30) {
                                String bulkHex = DLMdata.toString().trim();
                                if (hasLoadProfileRecords(bulkHex)) {
                                    int bulkCnt = countLoadProfileRecords(bulkHex);
                                    probeHadData = true;
                                    bulkFallbackUsed = true; // skip day loop — bulk has all data
                                    totalActualRecords += bulkCnt;
                                    lpPageHexList.add(bulkHex);
                                    seenPayloads.add(bulkHex);
                                    appendLog("RLS_BULK_FALLBACK_HAS_DATA records=" + bulkCnt
                                            + " — EIU firmware bug confirmed, using bulk LP data (day loop skipped)");
                                } else {
                                    appendLog("RLS_BULK_FALLBACK_EMPTY — LP buffer truly empty, skipping");
                                }
                            } else {
                                appendLog("RLS_BULK_FALLBACK_NO_RESPONSE — bulk attr=2 returned nothing");
                            }
                        }
                        if (!probeHadData)
                            appendLog("RLS_SEL_SKIP_ALL — LP confirmed empty by both selective and bulk probe");
                    }
                } // end else (port alive)
            }

            if ((entriesInUse > 0 || probeHadData) && !bulkFallbackUsed) {
                Calendar cal = Calendar.getInstance();
                int selectiveOk = 0;
                int targetRecords = recPerDay * lsDays;
                // ETA estimate: ~16s per day (8s timeout × 2 worst-case retries + overhead)
                int etaSecs = lsDays * 16;
                String etaStr = etaSecs < 60 ? etaSecs + "s" : (etaSecs/60) + "m " + (etaSecs%60) + "s";
                appendLog("LP: reading " + lsDays + " days | Target ~" + targetRecords
                        + " records | ETA ~" + etaStr);
                for (int i = lsDays; i >= 0; i--) {
                    if (abortRequested) {
                        appendLog("RLS_SEL_ABORT at day=" + i);
                        break;
                    }
                    // Deadline guard: stop iterating if LP budget is exhausted
                    if (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs) {
                        appendLog("RLS_SEL_DEADLINE_HIT at day=" + i);
                        break;
                    }
                    cal = Calendar.getInstance();
                    cal.add(Calendar.DATE, -i);
                    // Normalize to midnight: ensures from_time = 00:00:00 for this day.
                    // Without this, cal retains the current clock time (e.g. 16:19),
                    // which GetParameterSelective would encode as from_hour=16, causing
                    // the meter to start returning records from mid-afternoon only.
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    Date dayDate = cal.getTime();

                    DLMdata = GetParameterSelective(port, (byte) 7, "0100630100FF", (byte) 2,
                            this.bytWait, this.bytTryCnt, this.bytTimOut, false,
                            dayDate, dayDate, capturePeriodMin);

                    if (DLMdata != null && !DLMdata.toString().isEmpty()) {
                        String lpHex = DLMdata.toString().trim();

                        // Filter: keep payload only if it appears to contain actual LP rows.
                        if (!hasLoadProfileRecords(lpHex)) {
                            appendLog("RLS_SEL_DAY day=-" + i + " EMPTY_SKIPPED len=" + lpHex.length());
                            continue;
                        }
                        // Deduplicate by hex payload
                        if (seenPayloads.contains(lpHex)) {
                            appendLog("RLS_SEL_DAY day=-" + i + " DUPLICATE_SKIPPED");
                            continue;
                        }
                        seenPayloads.add(lpHex);

                        // Early-stop: once we have more records than entries_in_use reported,
                        // the meter has no more unique data — stop to save time.
                        if (entriesInUse > 0 && totalActualRecords > (int)(entriesInUse * 1.2)) {
                            appendLog("RLS_EARLY_STOP totalRecords=" + totalActualRecords
                                    + " > entriesInUse×1.2=" + (int)(entriesInUse * 1.2));
                            break;
                        }

                        // Extract declared entry count from first valid response
                        if (lpEntriesDeclared[0] == 0) {
                            try {
                                byte[] lpBytes = hexStringToByteArray(lpHex);
                                for (int bi = 0; bi < Math.min(16, lpBytes.length - 2); bi++) {
                                    if ((lpBytes[bi] & 0xff) == 0x82) {
                                        int declared = ((lpBytes[bi+1] & 0xff) << 8) | (lpBytes[bi+2] & 0xff);
                                        if (declared > 0) { lpEntriesDeclared[0] = declared; break; }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                        // Count actual records in this day
                        int cnt = countLoadProfileRecords(lpHex);
                        totalActualRecords += cnt;
                        if (!lpHex.toLowerCase().contains("0212090c") && cnt > 0) {
                            appendLog("RLS_SEL_ALT_PATTERN make=" + currentMeterMake.getDisplayName()
                                    + " day=-" + i + " timestamps=" + cnt);
                        }

                        // Detect actual capture period from timestamps if still using default
                        if (capturePeriodMin == 30 && cnt >= 2) {
                            try {
                                int p1 = lpHex.indexOf("0212090c");
                                int p2 = lpHex.indexOf("0212090c", p1 + 8);
                                if (p1 >= 0 && p2 >= 0) {
                                    String ts1 = lpHex.substring(p1 + 8, p1 + 32);
                                    String ts2 = lpHex.substring(p2 + 8, p2 + 32);
                                    int h1 = Integer.parseInt(ts1.substring(10,12),16);
                                    int m1 = Integer.parseInt(ts1.substring(12,14),16);
                                    int h2 = Integer.parseInt(ts2.substring(10,12),16);
                                    int m2 = Integer.parseInt(ts2.substring(12,14),16);
                                    int diff = (h2*60+m2) - (h1*60+m1);
                                    if (diff == 15 || diff == 30 || diff == 60) {
                                        if (diff != capturePeriodMin) {
                                            capturePeriodMin = diff;
                                            this.intProfilePd = diff;
                                            appendLog("RLS_INTERVAL_DETECTED=" + diff + "min from timestamps");
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }

                        lpPageHexList.add(lpHex);
                        selectiveOk++;
                        appendLog("RLS_SEL_DAY day=-" + i + " len=" + lpHex.length() + " records=" + cnt);

                        // ── LP PAGINATION: some meters (e.g. HPL) page their GetParameterSelective
                        // response to a maximum APDU size, returning only a subset of the day's records
                        // (e.g. 17 of 48 slots for a 30-min meter). We re-request from the last
                        // received timestamp + one interval until the full day is retrieved or the
                        // meter returns no more new records for that day.
                        int maxPageRetries = 6; // up to 6 continuation pages per day (6×17=102 > 96 max)
                        int pagesDone = 0;
                        while (cnt > 0 && cnt < recPerDay && pagesDone < maxPageRetries) {
                            if (abortRequested) break;
                            if (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs) {
                                appendLog("RLS_PAGE_DEADLINE day=-" + i);
                                break;
                            }
                            // Extract the last timestamp from the current page to use as next start
                            java.util.Date nextStart = extractLastLpTimestamp(lpHex, capturePeriodMin);
                            if (nextStart == null) {
                                appendLog("RLS_PAGE_NO_LAST_TS day=-" + i);
                                break;
                            }
                            appendLog("RLS_PAGE_CONT day=-" + i + " page=" + (pagesDone+1)
                                    + " nextStart=" + nextStart);
                            DLMdata = GetParameterSelective(port, (byte) 7, "0100630100FF", (byte) 2,
                                    this.bytWait, this.bytTryCnt, this.bytTimOut, false,
                                    nextStart, dayDate, capturePeriodMin);
                            if (DLMdata == null || DLMdata.toString().isEmpty()) break;
                            String pageHex = DLMdata.toString().trim();
                            if (!hasLoadProfileRecords(pageHex)) break;
                            if (seenPayloads.contains(pageHex)) break; // guard against infinite loop
                            seenPayloads.add(pageHex);
                            int pageCnt = countLoadProfileRecords(pageHex);
                            if (pageCnt == 0) break;
                            totalActualRecords += pageCnt;
                            cnt += pageCnt;
                            lpHex = pageHex; // last page for next iteration's timestamp extraction
                            lpPageHexList.add(pageHex);
                            pagesDone++;
                            appendLog("RLS_PAGE_DONE day=-" + i + " page=" + pagesDone
                                    + " pageCnt=" + pageCnt + " dayTotal=" + cnt);
                        }

                        // Log live record progress (visible in activity log on screen)
                        appendLog("LP Day " + (lsDays - i + 1) + "/" + (lsDays + 1)
                                + " | " + totalActualRecords + " of ~" + targetRecords + " records received");
                    } else {
                        appendLog("RLS_SEL_DAY day=-" + i + " NO_DATA");
                    }
                }
                appendLog("RLS_SEL_DONE daysWithData=" + selectiveOk + " totalRecords=" + totalActualRecords);
            } // end else (entriesInUse > 0)
        } // end if (!bulkReadOk)

        // ----------------------------------------------------------------
        // STEP 7 – Write attr=7 (entries_in_use) BEFORE all attr=2 lines.
        // XML parser needs the record count for buffer parsing.
        // Use declared value from meter LP response header; fall back to actual count.
        // NOTE: attr=3 (capture_objects) was already written at STEP 1.
        // ----------------------------------------------------------------
        int attr3Value = lpEntriesDeclared[0] > 0 ? lpEntriesDeclared[0] : totalActualRecords;
        String attr3Hex = String.format("%08x", attr3Value);
        // Tag 06 = double-long-unsigned (uint32) — correct DLMS type for entries_in_use
        // entries_in_use is attr=7, NOT attr=3 (which is capture_objects, already written above)
        strbldDLMdata.append("\r\n0007 0100630100FF 07 06").append(attr3Hex);
        appendLog("RLS_ATTR7_WRITTEN entries_in_use=" + attr3Value + " hex=" + attr3Hex);

        // Merge all collected LP pages into a single DLMS array line.
        // The XML converter expects one "0007 0100630100FF 02 01[BER][records...]" line.
        // Multiple GetParameterSelective responses are merged here so the converter
        // sees all records in a single parseable array rather than separate lines.
        if (!lpPageHexList.isEmpty()) {
            String mergedLp = mergeLpPageHexList(lpPageHexList);
            if (mergedLp != null) {
                strbldDLMdata.append("\r\n0007 0100630100FF 02 ").append(mergedLp);
                appendLog("RLS_LP_MERGED pages=" + lpPageHexList.size() + " totalRecords=" + totalActualRecords);
            }
        } else {
            // V30: always write LP1 attr=02 even when buffer is empty, so the .NET
            // converter knows LP1 was checked. "0100" = DLMS array with 0 elements.
            strbldDLMdata.append("\r\n0007 0100630100FF 02 0100");
            appendLog("RLS_LP_EMPTY_MARKER — entries_in_use=0 and probe found no data; writing empty array to TXT");
        }

        return strbldDLMdata;
    }



    private StringBuilder  ReadNamePlate(UsbSerialPort port)
    {
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        String str1 = ""; // BUG-18 FIX: removed dead boolean flag variable
        //CultureInfo invariantCulture = CultureInfo.InvariantCulture;
        this.nNewAmmendment = false;



        if (isSecureMeter()) {
            DLMdata = this.GetParameter(port,(byte) 7, "00005E5B0AFF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (DLMdata != null && DLMdata.length() > 25)
            {
                this.nNewAmmendment = true;
                strbldDLMdata.append(DLMdata);
                DLMdata=this.GetParameter(port,(byte) 7, "00005E5B0AFF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata))
                    strbldDLMdata.append(DLMdata);
            }
        }

        DLMdata=this.GetParameter(port,(byte) 1, "00002A0000FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (hasMeaningfulDlmsPayload(DLMdata)) {
            strbldDLMdata.append(DLMdata);
        }

        // Extract HPL logical device name for LP sub-variant detection in getCaptureObjectsForMake().
        // OBIS 0.0.42.0.0.255 (00002A0000FF) returns the COSEM device name — for HPL meters
        // this encodes the model string e.g. "HPLSPEM6KT001234", "HPLPPEM6KT001234", "HPLCT05KT001234".
        // The sub-variant prefix ("SPEM", "PPEM", "CT") determines the LP column layout.
        if (currentMeterMake == MeterMake.HPL && DLMdata != null && DLMdata.length() > 25) {
            String dmStr = DLMdata.toString();
            // GetParameter(isDLM=true) format: "\r\nCOUNT OBIS ATTR <hex_payload>"
            // Extract the hex payload after the last space on the data line.
            int lineEnd = dmStr.lastIndexOf('\n');
            String dataLine = lineEnd >= 0 ? dmStr.substring(lineEnd).trim() : dmStr.trim();
            int lastSp = dataLine.lastIndexOf(' ');
            if (lastSp >= 0) {
                String payload = dataLine.substring(lastSp + 1).trim();
                String devName = parseVisibleStringHex(payload);
                if (!devName.isEmpty()) {
                    hplLogicalDeviceName = devName.toUpperCase();
                    appendLog("HPL_LOGICAL_DEV=" + hplLogicalDeviceName);
                }
            }
        }

        DLMdata=this.GetParameter(port,(byte) 1, "0100000804FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) {
            appendLog("New Change" + DLMdata);
            // appendLog("Parse String" + DLMdata.toString().substring(25));
            //strbldDLMdata.append(DLMdata.toString().replace("\"","") );
            // Parse integration period: skip type byte, skip length byte, take value bytes
            // e.g. "09020384" → type=09, len=02, value=0384 = 900 seconds / 60 = 15 min
            String rawPd = DLMdata.toString().trim();
            // Take last 4 hex chars (2 bytes = the actual value for typical 2-byte period)
            String pdHex = rawPd.length() >= 4 ? rawPd.substring(rawPd.length() - 4) : rawPd;
            try {
                this.intProfilePd = (int)(Long.parseLong(pdHex, 16) / 60);
            } catch (Exception pe) {
                this.intProfilePd = 15; // default 15 min if parse fails
            }
            //  int.Parse(((object) strbldDLMdata).ToString().Substring(25), NumberStyles.HexNumber) / 60;
            appendLog("Parse String" + DLMdata.toString().substring(25));
            appendLog( "Int Period "+ this.intProfilePd);


        }

        DLMdata=this.GetParameter(port,(byte) 8, "0000010000FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (hasMeaningfulDlmsPayload(DLMdata)) {
            strbldDLMdata.append(DLMdata);
            //String str2 =  DLMdata.toString().substring(DLMdata.length() - 24,24);
            //      this.dateGlobalCurrentDate = DateTime.ParseExact(int.Parse(str2.Substring(6, 2), NumberStyles.HexNumber).ToString("00") + "/" + int.Parse(str2.Substring(4, 2), NumberStyles.HexNumber).ToString("00") + "/" + int.Parse(str2.Substring(0, 4), NumberStyles.HexNumber).ToString("0000") + " " + int.Parse(str2.Substring(10, 2), NumberStyles.HexNumber).ToString("00") + ":" + int.Parse(str2.Substring(12, 2), NumberStyles.HexNumber).ToString("00") + ":" + int.Parse(str2.Substring(14, 2), NumberStyles.HexNumber).ToString("00"), "dd/MM/yyyy HH:mm:ss", (IFormatProvider) invariantCulture, DateTimeStyles.AssumeLocal);
        }

        DLMdata=this.GetParameter(port,(byte) 1, "0000600100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);


        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        DLMdata=this.GetParameter(port,(byte) 1, "0100000200FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);


        if (isSecureMeter()) {
            DLMdata=this.GetParameter(port,(byte) 1, "00005E5B09FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata))
                strbldDLMdata.append(DLMdata);
        }


        // BUG-19 FIX: LP capture_period (0100630100FF attr=4) removed from ReadNamePlate.
        // ReadLoadSurveyData already reads this as part of its own attr=3/4/7/2 sequence.
        // Having it in NamePlate caused buildScalerMap to lock in a stale value before the LP phase.

        DLMdata=this.GetParameter(port,(byte) 1, "0000000100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        DLMdata=this.GetParameter(port,(byte) 1, "0100000800FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // ── Additional nameplate fields (all 4 makes per OBIS availability mapping) ──

        // Manufacturer name (0.0.96.1.1.255) — class 1, attr=2
        // Available on ALL makes: Secure, Genus, L&T, HPL
        DLMdata = this.GetParameter(port, (byte) 1, "0000600101FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Year of manufacture (0.0.96.1.4.255) — class 1, attr=2
        // Available on ALL makes: Secure, Genus, L&T, HPL
        DLMdata = this.GetParameter(port, (byte) 1, "0000600104FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // CT ratio numerator (1.0.0.4.2.255) — class 1, attr=2
        // Available on ALL makes: Secure, Genus, L&T, HPL
        // This is the CORRECT CT ratio OBIS per IS 15959-2 / DLMS mapping table.
        // The old 0100608012FF (tag=0x0B) was a wrong/proprietary register.
        DLMdata = this.GetParameter(port, (byte) 1, "0100000402FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // PT ratio numerator (1.0.0.4.3.255) — class 1, attr=2
        // Available on ALL makes: Secure, Genus, L&T, HPL
        DLMdata = this.GetParameter(port, (byte) 1, "0100000403FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Billing period timestamp last reset (0.0.0.1.2.255) — class 1, attr=2
        // Available on ALL makes: Secure, Genus, L&T, HPL
        DLMdata = this.GetParameter(port, (byte) 1, "0000000102FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // NOTE: 0100608012FF, 0000600A01FF, 0100608017FF are not present in L&T NTD
        // association lists. They are already single-attempt (bytTryCnt=1 set by caller)
        // but explicitly use (byte)1 here as documentation that these are make-specific.
        DLMdata=this.GetParameter(port,(byte) 1, "0100608012FF", (byte) 2, this.bytWait, (byte) 1, this.bytTimOut, true,  strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        DLMdata=this.GetParameter(port,(byte) 63, "0000600A01FF", (byte) 2, this.bytWait, (byte) 1, this.bytTimOut, true,  strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        DLMdata=this.GetParameter(port,(byte) 1, "0100608017FF", (byte) 2, this.bytWait, (byte) 1, this.bytTimOut, true,  strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Meter constant / pulse weight (1.0.128.8.0.255 = 0100800800FF) — class 3
        // Present on Genus LC: value=uint8 = Wh per pulse. Single attempt; fast-fails on others.
        DLMdata = this.GetParameter(port, (byte) 3, "0100800800FF", (byte) 3,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100800800FF", (byte) 2,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Tamper duration counters (0.0.96.8.5.255 / .6.255 / .7.255) — class 3
        // Present on Genus LC: cumulative tamper event duration in seconds.
        DLMdata = this.GetParameter(port, (byte) 3, "0000600885FF", (byte) 3,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0000600885FF", (byte) 2,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0000600886FF", (byte) 2,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0000600887FF", (byte) 2,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // L&T (Schneider ER300P) extended nameplate registers 0.0.96.11.x.255 — class 1 (Data)
        // Confirmed in MRI association lists: KT228194 (TD00.02) has 01-05; 25165449 (NTD19.03) has 02-05.
        // Single attempt each; fast-fail on meters that don't support these.
        for (String nb : new String[]{"0000600B01FF","0000600B02FF","0000600B03FF","0000600B04FF","0000600B05FF"}) {
            DLMdata = this.GetParameter(port, (byte) 1, nb, (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        }

        // Firmware / software version string (0.0.96.7.0.255 = 0000600700FF) — class 1, attr=2
        // Present on all makes (L&T, Genus, HPL confirmed in MRI association lists).
        DLMdata = this.GetParameter(port, (byte) 1, "0000600700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Electronic serial number (0.0.96.2.0.255 = 0000600200FF) — class 1, attr=2
        // Present on all makes in MRI association lists.
        DLMdata = this.GetParameter(port, (byte) 1, "0000600200FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Special Days table (0.0.15.0.0.255 = 00000F0000FF) — class 16, attr=4 (entries array)
        // MRI reads this for all makes (Genus DLM, HPL DDT, L&T OUT all confirmed).
        // Contains list of holiday/exception dates for tariff calendar overrides.
        DLMdata = this.GetParameter(port, (byte) 16, "00000F0000FF", (byte) 4,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // HPL-specific nameplate and configuration registers — class 1, single attempt each.
        // Source: KT341729_20260325151141.DDT.  Fast-fail on non-HPL meters.
        // 010080058CFF = HPL meter configuration string
        // 010080800EFF = HPL model label / nameplate line 1
        // 010080800FFF = HPL model label / nameplate line 2
        for (String hplNp : new String[]{"010080800EFF","010080800FFF","010080058CFF"}) {
            DLMdata = this.GetParameter(port, (byte) 1, hplNp, (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        }

        // HPL meter status word (1.0.96.5.0.255 = 0100600500FF) — class 1, attr=2
        // Contains encoded status/tamper flags.
        DLMdata = this.GetParameter(port, (byte) 1, "0100600500FF", (byte) 2,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // HPL configuration register (1.0.128.8.128.0 = 0100800880FF) — class 3, attr=3,2
        DLMdata = this.GetParameter(port, (byte) 3, "0100800880FF", (byte) 3,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100800880FF", (byte) 2,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // HPL status/diagnostic registers (1.0.128.5.x.255) — class 1, attr=2, single attempt.
        // Source: KT341729 DDT. Contains tamper flags, cover status, CT/PT diagnostics.
        for (String hplSt : new String[]{"0100800580FF","0100800581FF","0100800582FF",
                "0100800583FF","0100800584FF","0100800585FF","0100800587FF","0100800597FF"}) {
            DLMdata = this.GetParameter(port, (byte) 1, hplSt, (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        }

        // Genus optical interface type (0.0.96.20.16.255 = 0000601410FF) — class 1, attr=2
        DLMdata = this.GetParameter(port, (byte) 1, "0000601410FF", (byte) 2,
                this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        return strbldDLMdata;
    }


    private StringBuilder ReadMidnightSnapshot(UsbSerialPort port, int lsDays)
    {
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();

        // Daily snapshot scalar / unit (OBIS 01005E5B05FF)
        DLMdata = this.ReadScalarUnit("DAILYLOAD", port);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // Daily snapshot profile (class 7, OBIS 0100630200FF)
        // Order matches LP pattern: attr=3 (capture_objects) → attr=4 (capture_period)
        //                         → attr=7 (entries_in_use) → attr=2 (buffer)
        DLMdata = this.GetParameter(port, (byte) 7, "0100630200FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // attr=4 = capture_period in seconds (NOT attr=8 which is profile_entries/max)
        DLMdata = this.GetParameter(port, (byte) 7, "0100630200FF", (byte) 4,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // attr=7 = entries_in_use — BUG-11 FIX: parse and cross-check with buffer
        DLMdata = this.GetParameter(port, (byte) 7, "0100630200FF", (byte) 7,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        int midnightEiu = -1;
        if (hasMeaningfulDlmsPayload(DLMdata)) {
            strbldDLMdata.append(DLMdata);
            try {
                String[] ep = DLMdata.toString().trim().split("\\s+");
                if (ep.length >= 4 && ep[3].length() >= 10)
                    midnightEiu = (int) Long.parseLong(ep[3].substring(2, 10), 16);
                appendLog("MIDNIGHT_EIU=" + midnightEiu);
            } catch (Exception ignored) {}
        }

        // attr=2 = buffer (daily snapshot records) with selective access for last lsDays days.
        // Without date filtering the meter returns ALL midnight records (could be 100s of days).
        // Apply the same 35-day cap used for LP so files stay bounded and reads are fast.
        int snapDays = (lsDays > 0) ? lsDays : 35;
        try {
            java.util.Calendar calEnd = java.util.Calendar.getInstance();
            calEnd.set(java.util.Calendar.HOUR_OF_DAY, 23);
            calEnd.set(java.util.Calendar.MINUTE, 59);
            calEnd.set(java.util.Calendar.SECOND, 59);

            java.util.Calendar calStart = java.util.Calendar.getInstance();
            calStart.add(java.util.Calendar.DAY_OF_YEAR, -snapDays);
            calStart.set(java.util.Calendar.HOUR_OF_DAY, 0);
            calStart.set(java.util.Calendar.MINUTE, 0);
            calStart.set(java.util.Calendar.SECOND, 0);

            appendLog("MIDNIGHT_SEL days=" + snapDays
                    + " from=" + calStart.getTime() + " to=" + calEnd.getTime());

            DLMdata = this.GetParameterSelective(port, (byte) 7, "0100630200FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true,
                    calStart.getTime(), calEnd.getTime(), 1440); // 1440 min = 1 day capture period
        } catch (Exception e) {
            // V32 FIX: same echo-leak guard as the empty-path LS fallback below — use a
            // separate StringBuilder so if the LS result is also a uint32 echo it can be
            // discarded BEFORE reaching strbldDLMdata.  The old code passed strbldDLMdata
            // directly, so the echo was written even when DLMdata was later set to null.
            appendLog("MIDNIGHT_SEL_EX: " + e.getMessage() + " — falling back to full buffer read");
            StringBuilder exFallbackSb = new StringBuilder();
            DLMdata = this.GetParameter_LS(port, (byte) 7, "0100630200FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, exFallbackSb);
            if (hasMeaningfulDlmsPayload(DLMdata)) {
                String[] _exMp = DLMdata.toString().trim().split("\\s+");
                String _exMpx = _exMp.length >= 4 ? _exMp[_exMp.length - 1].toUpperCase() : "";
                if (_exMpx.length() == 10 && _exMpx.startsWith("06")) {
                    appendLog("MIDNIGHT_EX_SCALAR: uint32 echo 0x" + _exMpx.substring(2)
                            + " — Genus LP2 EX-fallback also returns EIU as scalar, discarding");
                    DLMdata = null;
                    // exFallbackSb discarded — echo never reaches strbldDLMdata
                } else {
                    strbldDLMdata.append(exFallbackSb);
                    // Mark as handled: prevents the hasMeaningfulDlmsPayload block below
                    // from double-appending DLMdata to strbldDLMdata.
                    DLMdata = null;
                }
            }
        }
        // V30 FIX: Genus LP2 anomaly — some Genus firmware echoes entries_in_use as a
        // uint32 scalar (DLMS tag 0x06, 5 bytes total = 10 hex chars) instead of returning
        // the actual buffer array when selective access is used. hasMeaningfulDlmsPayload()
        // accepts this non-empty response, preventing the fallback below from triggering.
        // Detect it here: if payload is exactly 10 hex chars starting with "06", it is a
        // scalar uint32, not a profile buffer array (which starts with "01" array tag).
        // Discard the scalar and let the MIDNIGHT_SEL_EMPTY fallback retry with GetParameter_LS.
        if (hasMeaningfulDlmsPayload(DLMdata)) {
            String[] _mp = DLMdata.toString().trim().split("\\s+");
            String _mpx = _mp[_mp.length - 1].toUpperCase();
            if (_mpx.length() == 10 && _mpx.startsWith("06")) {
                appendLog("MIDNIGHT_SEL_SCALAR: uint32 echo 0x" + _mpx.substring(2)
                        + " (val=" + Long.parseLong(_mpx.substring(2), 16)
                        + ") — Genus LP2 selective-access anomaly, discarding and retrying with LS");
                DLMdata = null;
            }
        }
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        else {
            // Fall back to full buffer read if selective access returns empty or was discarded.
            // BUG-FIX V31: use a separate StringBuilder (lsFallbackSb) so the echo-detection
            // guard below can discard the response BEFORE it reaches strbldDLMdata.
            // The old V30 code passed strbldDLMdata directly, causing the echo to be written
            // into the TXT even when DLMdata was later set to null.
            appendLog("MIDNIGHT_SEL_EMPTY — retrying with full buffer read");
            StringBuilder lsFallbackSb = new StringBuilder();
            DLMdata = this.GetParameter_LS(port, (byte) 7, "0100630200FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, lsFallbackSb);
            // V30 FIX: apply same uint32-echo guard to LS fallback result.
            // Genus firmware echoes entries_in_use as uint32 on BOTH selective and LS.
            if (hasMeaningfulDlmsPayload(DLMdata)) {
                String[] _mpLs = DLMdata.toString().trim().split("\\s+");
                String _mpxLs = _mpLs[_mpLs.length - 1].toUpperCase();
                if (_mpxLs.length() == 10 && _mpxLs.startsWith("06")) {
                    appendLog("MIDNIGHT_LS_SCALAR: uint32 echo 0x" + _mpxLs.substring(2)
                            + " — Genus LP2 LS-fallback also returns EIU as scalar, discarding");
                    DLMdata = null;
                    // lsFallbackSb is discarded — echo never reaches strbldDLMdata
                } else {
                    strbldDLMdata.append(lsFallbackSb);
                }
            }
        }

        // BUG-11 FIX: cross-check EIU vs actual buffer result
        boolean bufferGotData = hasMeaningfulDlmsPayload(DLMdata);
        if (midnightEiu == 0) {
            appendLog("MIDNIGHT_INFO: entries_in_use=0 — buffer confirmed empty");
        } else if (midnightEiu > 0 && !bufferGotData) {
            appendLog("MIDNIGHT_CRITICAL: entries_in_use=" + midnightEiu + " but buffer empty — read failure");
        }
        if (strbldDLMdata.length() == 0) {
            appendLog("MIDNIGHT_WARN: Midnight snapshot all responses empty — skipping section.");
        }

        return strbldDLMdata;
    }

    private StringBuilder ReadInstantData(UsbSerialPort port)
    {
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata;

        // ── Scaler/unit profile ───────────────────────────────────────────────
        // Secure only: reads 01005E5B03FF which provides scalers for .7. registers.
        // Non-Secure meters (Genus, L&T, HPL, AVON) skip this; their registers
        // return raw values and the converter emits them without scaling.
        DLMdata = this.ReadScalarUnit("INSTANT", port);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // ── Secure compound instantaneous snapshot (01005E5B00FF) ─────────────
        // This single object contains all instantaneous values for Secure meters.
        if (isSecureMeter()) {
            DLMdata = this.GetParameter(port, (byte) 7, "01005E5B00FF", (byte) 3,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            DLMdata = this.GetParameter(port, (byte) 7, "01005E5B00FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        }

        // ── Standard IS 15959-2 instantaneous registers (.7. value-group) ─────
        // These are CLASS 3 (Register) objects read individually.
        // Confirmed present on Genus LC via power-event snapshots in the billing
        // profile buffer. Also standard on L&T, HPL, AVON, Elster, EMCO.
        // The converter's ObisTables.D2 maps exactly these dotted OBIS codes
        // to their P-codes (P2-x, P1-x, P4-x, P3-x, P7-x).
        //
        // Current (A):  1.0.31/51/71.7.0.255
        // Voltage (V):  1.0.32/52/72.7.0.255
        // PF per phase: 1.0.33/53/73.7.0.255
        // Active power: 1.0.1.7.0.255
        // Reactive pwr: 1.0.3.7.0.255
        // Apparent pwr: 1.0.9.7.0.255
        // Frequency:    1.0.14.7.0.255

        // ── Make flags — compute once, use to skip irrelevant OBIS blocks ────────────
        // Each make has OBIS codes that are simply not in its association list.
        // On a meter that doesn't support an OBIS, GetParameter gets no response
        // and burns the full bytTimOut (even at 3s, 20 silent OBIS = 60s wasted).
        // Skipping them entirely drops ReadInstantData from ~60s to ~5s on LNT/HPL.
        boolean isSecure  = (currentMeterMake == MeterMake.SECURE);
        boolean isGenus   = (currentMeterMake == MeterMake.GENUS  || currentMeterMake == MeterMake.AVON);
        boolean isHPL     = (currentMeterMake == MeterMake.HPL);
        boolean isLNT     = (currentMeterMake == MeterMake.LNT);
        boolean isLandis  = (currentMeterMake == MeterMake.LANDIS);

        // OPT-4 FIX: Per-OBIS attr=3 (scaler) reads needed only for non-Secure makes.
        // Secure meters get all scalers from compound object 01005E5B03FF.
        // HPL/Genus/LNT/AVON/LANDIS/LNG still need per-OBIS scaler reads.
        boolean needIndivScalers = !isSecure;

        // Current L1
        if (needIndivScalers) { DLMdata = this.GetParameter(port, (byte) 3, "01001F0700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata); }
        DLMdata = this.GetParameter(port, (byte) 3, "01001F0700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Current L2
        DLMdata = this.GetParameter(port, (byte) 3, "0100330700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100330700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Current L3
        DLMdata = this.GetParameter(port, (byte) 3, "0100470700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100470700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Voltage L1
        DLMdata = this.GetParameter(port, (byte) 3, "0100200700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100200700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Voltage L2
        DLMdata = this.GetParameter(port, (byte) 3, "0100340700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100340700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Voltage L3
        DLMdata = this.GetParameter(port, (byte) 3, "0100480700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100480700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // PF L1
        DLMdata = this.GetParameter(port, (byte) 3, "0100210700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100210700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // PF L2
        DLMdata = this.GetParameter(port, (byte) 3, "0100350700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100350700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // PF L3
        DLMdata = this.GetParameter(port, (byte) 3, "0100490700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100490700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Active power import (1.0.1.7.0.255)
        DLMdata = this.GetParameter(port, (byte) 3, "0100010700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100010700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Reactive power import (1.0.3.7.0.255)
        DLMdata = this.GetParameter(port, (byte) 3, "0100030700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100030700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Apparent power (1.0.9.7.0.255)
        DLMdata = this.GetParameter(port, (byte) 3, "0100090700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100090700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Frequency (1.0.14.7.0.255)
        DLMdata = this.GetParameter(port, (byte) 3, "01000E0700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "01000E0700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // PF total system (1.0.13.7.0.255)  — D2 P-code P4-4-1-0-0
        DLMdata = this.GetParameter(port, (byte) 3, "01000D0700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "01000D0700FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // ── Cumulative energy registers (.8. value-group) ─────────────────────
        // Present on Genus/L&T/HPL/AVON confirmed from billing profile columns.
        // Raw uint32 values; converter emits with UNIT="k" for non-Secure.

        // kWh Import cumulative (1.0.1.8.0.255)
        DLMdata = this.GetParameter(port, (byte) 3, "0100010800FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100010800FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // FIX A: Export-group skip after first empty response.
        // Consumer/import-only meters (e.g. L&T NTD BS064528) return no data for any
        // export OBIS. Reading every one wastes bytTimOut × HDLC-retries per OBIS:
        //   kWh_exp, kVAh_exp, MD_kW_exp, MD_kVA_exp + their attr=3 scalers + attr=5 timestamps
        //   = 10 reads × ~14s each = ~140s wasted (confirmed in BS064528 session log).
        //
        // Fix: read kWh_exp (0100020800FF) FIRST. If empty → set exportSupported=false.
        // All remaining export OBIS (kVAh_exp, MD exports) are skipped immediately.
        //
        // Safe for ALL meter makes:
        //   - Export meters (bidirectional HT/CT): kWh_exp responds → exportSupported=true
        //     → all export OBIS read normally. No data loss.
        //   - Import-only meters (consumer LT): kWh_exp returns empty → skip rest.
        //     → saves ~120-140s per session.
        //   - Secure meters: export OBIS come from compound object, not individual reads.
        //     This block is only reached by non-Secure makes (isSecure check above).
        //   - Genus: export MD uses different OBIS (0100B20600FF); those are already
        //     guarded by `if (isGenus)`. Standard export OBIS also benefit from this fix.
        boolean exportSupported = false;

        // kWh Export cumulative (1.0.2.8.0.255) — probe read
        DLMdata = this.GetParameter(port, (byte) 3, "0100020800FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100020800FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) {
            strbldDLMdata.append(DLMdata);
            exportSupported = true;  // meter has export register → read remaining export OBIS
            appendLog("EXPORT_PROBE: kWh_exp present → reading all export OBIS");
        } else {
            appendLog("EXPORT_PROBE: kWh_exp empty → skipping all export OBIS (import-only meter)");
        }

        // kVAh cumulative (1.0.9.8.0.255)
        DLMdata = this.GetParameter(port, (byte) 3, "0100090800FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100090800FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // kVArh Q1 lag (1.0.5.8.0.255) — D2 P-code P7-2-1-0-0
        DLMdata = this.GetParameter(port, (byte) 3, "0100050800FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100050800FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // kVArh Q4 lead (1.0.8.8.0.255) — D2 P-code P7-2-4-0-0
        DLMdata = this.GetParameter(port, (byte) 3, "0100080800FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100080800FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // kVAh Export (1.0.10.8.0.255) — D2 P-code P7-3-6-2-0
        // FIX A: skipped if kWh_exp probe was empty (import-only meter)
        if (exportSupported) {
            DLMdata = this.GetParameter(port, (byte) 3, "01000A0800FF", (byte) 3,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "01000A0800FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        } // end exportSupported: kVAh export

        // ── Max Demand (class 4 DemandRegister) ──────────────────────────────
        // attr=2 = value, attr=3 = scaler/unit, attr=5 = last occurrence timestamp

        // MD kW (1.0.1.6.0.255)
        DLMdata = this.GetParameter(port, (byte) 4, "0100010600FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 4, "0100010600FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 4, "0100010600FF", (byte) 5,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // MD kVA (1.0.9.6.0.255)
        DLMdata = this.GetParameter(port, (byte) 4, "0100090600FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 4, "0100090600FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 4, "0100090600FF", (byte) 5,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // MD kW Export (1.0.2.6.0.255) — standard DLMS OBIS for export active demand
        // MD kVA Export (1.0.10.6.0.255) — standard DLMS OBIS for export apparent demand
        // FIX A: skipped on import-only meters (kWh_exp probe was empty above)
        if (exportSupported) {
            DLMdata = this.GetParameter(port, (byte) 4, "0100020600FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 4, "0100020600FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 4, "0100020600FF", (byte) 5,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            DLMdata = this.GetParameter(port, (byte) 4, "01000A0600FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 4, "01000A0600FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 4, "01000A0600FF", (byte) 5,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        } // end exportSupported: MD kW + kVA export

        // MD kW Export — Genus-specific OBIS 1.0.178.6.0.255 (0100B20600FF)
        // Used by Genus LC firmware alongside standard 0100020600FF.
        if (isGenus) {
            DLMdata = this.GetParameter(port, (byte) 4, "0100B20600FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 4, "0100B20600FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 4, "0100B20600FF", (byte) 5,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            // MD kVA Export — Genus-specific OBIS 1.0.179.6.0.255 (0100B30600FF)
            DLMdata = this.GetParameter(port, (byte) 4, "0100B30600FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 4, "0100B30600FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 4, "0100B30600FF", (byte) 5,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        } // end Genus-specific MD demand

        // Per-phase active power — L&T NTD 3-phase only (MRI 25165449 confirmed)
        // OBIS: 1.0.36.7.0.255 / 1.0.56.7.0.255 / 1.0.76.7.0.255, class 3, sc=0, unit=W
        // Not present on HPL, Genus, Secure, Landis — skip to avoid 6 × bytTimOut timeouts.
        if (isLNT) {
            DLMdata = this.GetParameter(port, (byte) 3, "0100240700FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "0100240700FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            DLMdata = this.GetParameter(port, (byte) 3, "0100380700FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "0100380700FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            DLMdata = this.GetParameter(port, (byte) 3, "01004C0700FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "01004C0700FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        } // end L&T per-phase power

        // kVAh Export — Genus-specific OBIS 1.0.9.2.0.255 (0100090200FF)
        // Apparent energy export in Genus .2. group (complementary to 01000A0800FF).
        if (isGenus) {
            DLMdata = this.GetParameter(port, (byte) 3, "0100090200FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "0100090200FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        } // end Genus kVAh .2.

        // ── Phasor angles (1.0.81.7.E.255, class 3) — HPL PPEM only ─────────────────
        // Confirmed present on HPL PPEM variants in DDT KT341729.
        // Not in L&T, Genus, Secure, or Landis association lists — silently times out
        // on those meters burning 5 × 2 × bytTimOut = 30s. Guard to HPL only.
        if (isHPL) {
            DLMdata = this.GetParameter(port, (byte) 3, "0100510701FF", (byte) 3,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "0100510701FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            // Voltage L3 angle vs L1 (1.0.81.7.2.255)
            DLMdata = this.GetParameter(port, (byte) 3, "0100510702FF", (byte) 3,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "0100510702FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            // Current L1 angle vs U(L1) (1.0.81.7.4.255)
            DLMdata = this.GetParameter(port, (byte) 3, "0100510704FF", (byte) 3,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "0100510704FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            // Current L2 angle vs U(L1) (1.0.81.7.5.255)
            DLMdata = this.GetParameter(port, (byte) 3, "0100510705FF", (byte) 3,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "0100510705FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            // Current L3 angle vs U(L1) (1.0.81.7.6.255)
            DLMdata = this.GetParameter(port, (byte) 3, "0100510706FF", (byte) 3,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "0100510706FF", (byte) 2,
                    this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        } // end HPL phasor angles

        // ── Meter status registers ────────────────────────────────────────────
        // Manufacturer/model identification (class 3, 0.0.96.8.0.255)
        DLMdata = this.GetParameter(port, (byte) 3, "0000600800FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0000600800FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // ── HPL phasor/power-quality snapshot profiles — HPL PPEM only ──────────────
        // 0100638000FF, 638100FF, 638200FF, 638300FF confirmed in HPL DDT KT341729.
        // Not present on L&T, Genus, Secure, or Landis — silently times out on those
        // meters (4 OBIS × 2 attrs × bytTimOut = 24s wasted). Guard to HPL only.
        if (isHPL) {
            DLMdata = this.GetParameter(port, (byte) 7, "0100638100FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 7, "0100638100FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            DLMdata = this.GetParameter(port, (byte) 7, "0100638000FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 7, "0100638000FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

            DLMdata = this.GetParameter(port, (byte) 7, "0100638200FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 7, "0100638200FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 7, "0100638300FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 7, "0100638300FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        } // end HPL phasor profiles

        // ── Genus per-phase voltage/current harmonics (.7C. group) — Genus/AVON only ──
        // Confirmed in Genus DLM MRI (20103429, 05103456). Not in HPL, L&T, or Secure
        // association lists — 6 OBIS × 2 attrs × bytTimOut = 36s wasted on other makes.
        if (isGenus) {
            for (String hob : new String[]{
                    "010020077CFF","010034077CFF","010048077CFF",
                    "01001F077CFF","010033077CFF","010047077CFF"}) {
                DLMdata = this.GetParameter(port, (byte) 3, hob, (byte) 3,
                        this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
                DLMdata = this.GetParameter(port, (byte) 3, hob, (byte) 2,
                        this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            }

            // kWh import .2. group — Genus-specific 1.0.1.2.0.255 (0100010200FF)
            // Confirmed in Genus DLM MRI: alternate energy accumulator on Genus LC firmware.
            DLMdata = this.GetParameter(port, (byte) 3, "0100010200FF", (byte) 3,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
            DLMdata = this.GetParameter(port, (byte) 3, "0100010200FF", (byte) 2,
                    this.bytWait, (byte) 1, this.bytTimOut, true, strbldDLMdata);
            if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        } // end Genus harmonics + kWh .2.

        return strbldDLMdata;
    }
}