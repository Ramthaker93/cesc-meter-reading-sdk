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
        BILLING_EVENT ("Billing+Event",  "BE"),
        LOAD_PROFILE  ("Load Profile",   "L"),
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
    private int intProfilePd;
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
    // LP deadline — set before ReadLoadSurveyData, checked inside
    private volatile long    lpDeadlineMs    = 0;

    // SESSION REUSE — keeps DLMS association open across reads in COMPLETE mode
    private UsbSerialPort activePort = null;

    // ADAPTIVE TIMEOUT — fast probe (1.5s) then full fallback
    private static final int FAST_TIMEOUT_MS  = 1500;
    private static final int FULL_TIMEOUT_MS  = 8000;

    // Current active meter make — set when download starts, used to skip irrelevant OBIS
    private MeterMake currentMeterMake = MeterMake.SECURE;

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
                    "FROM mro_detail WHERE " + col + "='" + filter + "'";
            Cursor c = db.GetData(sql);
            boolean found = false;
            if (c != null && c.moveToFirst()) {
                populateConsumerFields(c);
                found = true;
                c.close();
            }
            if (!found) {
                // Try supervisor table
                sql = "SELECT Ablbelnr, ConsumerNo, MeterNo, Name, Co, HouseNo, Street, City, Portion " +
                        "FROM mro_Detail_supervisor WHERE " + col + "='" + filter + "'";
                c = db.GetData(sql);
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
    public String GetLocation() {
        String MyLoc = "";
        try {
            GPSTracker gps = new GPSTracker(this);
            if (gps.canGetLocation()) {
                double latitude  = gps.getLatitude();
                double longitude = gps.getLongitude();
                MyLoc = Double.toString(latitude) + "~" + Double.toString(longitude);
            } else {
                gps.showSettingsAlert();
            }
            return MyLoc;
        } catch (Exception ex) {
            return MyLoc;
        }
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

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
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
                AsyncTaskRunnerLnG task = new AsyncTaskRunnerLnG();
                task.execute(mode.getDisplayName());
            } else {
                abortRequested = false;
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
    // ABORT READING
    // =====================================================================
    public void onBtnAbortClicked(View v) {
        abortRequested = true;
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
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
            String SysDate = dateFormat.format(new Date());
            DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
            String Sql1 = "UPDATE ReadingLog SET Status='" + Status + "', EndTime='" + SysDate +
                    "' WHERE MeterNo='" + Meterno + "'";
            Obj.ExecuteQry(Sql1);
        } catch (Exception ex) { appendLog("DB error: " + ex.getMessage()); }
    }

    public void UpdateStatusFileNme(String Meterno, String Status) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
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

                publishProgress("INFO|Sending SNRM (NRM)...", "15");
                boolean nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                if (!nrmOk) {
                    // Suggestion 2: one automatic retry — optical alignment often needs 2 attempts
                    appendLog("NRM_RETRY — first attempt failed, retrying once");
                    publishProgress("INFO|NRM retry (cable alignment)...", "15");
                    android.os.SystemClock.sleep(500);
                    nrmOk = SetNRM(port, bytWait, (byte) 2, bytTimOut);
                }
                if (abortRequested || isCancelled()) {
                    publishProgress("WARN|Aborted by user.", "0");
                    return "Aborted";
                }
                if (!nrmOk) {
                    publishProgress("ERROR|NRM failed — check optical cable alignment on meter.", "0");
                    return "Set NRM Failed";
                }
                publishProgress("INFO|NRM OK — Sending AARQ (Authentication)...", "20");

                doFakeWork();

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
                    String d = SbData.toString().replace("0A0A", "");
                    MeterNo = hexToString(d).replace("\b", "").replace("\r\n", "").replace("\n", "").replace("\t", "").trim();
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

                            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
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
                publishProgress("INFO|Reading Name Plate...", "35");
                StringBuilder MeterData = new StringBuilder();
                StringBuilder sbNm = ReadNamePlate(port);
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

                    case INSTANTANEOUS:
                        publishProgress("INFO|Downloading Instantaneous data...", "50");
                        MeterData.append(ReadInstantData(port));
                        UpdateStatus(CescRajMeterno, "Instantaneous OK");
                        publishProgress("INFO|Instantaneous read OK", "80");
                        break;

                    case BILLING:
                        publishProgress("INFO|Downloading Instantaneous data...", "48");
                        MeterData.append(ReadInstantData(port));
                        UpdateStatus(CescRajMeterno, "Instantaneous OK");
                        publishProgress("INFO|Instantaneous done — downloading Billing...", "55");
                        StringBuilder BillStr = ReadBillingData(port);
                        if (BillStr.length() > 10) {
                            MeterData.append(BillStr);
                            UpdateStatus(CescRajMeterno, "Billing OK");
                            publishProgress("INFO|Billing data read OK", "80");
                        } else {
                            UpdateStatus(CescRajMeterno, "Billing FAILED");
                            publishProgress("WARN|Billing data empty — check meter.", "80");
                        }
                        break;

                    case BILLING_EVENT:
                        publishProgress("INFO|Downloading Instantaneous data...", "44");
                        MeterData.append(ReadInstantData(port));
                        UpdateStatus(CescRajMeterno, "Instantaneous OK");
                        publishProgress("INFO|Instantaneous done — downloading Billing...", "52");
                        MeterData.append(ReadBillingData(port));
                        UpdateStatus(CescRajMeterno, "Billing OK");
                        publishProgress("INFO|Billing done — downloading Midnight Snapshot...", "62");
                        MeterData.append(ReadMidnightSnapshot(port));
                        UpdateStatus(CescRajMeterno, "Midnight OK");
                        publishProgress("INFO|Midnight done — downloading Events...", "72");
                        MeterData.append(ReadEventData(port));
                        UpdateStatus(CescRajMeterno, "Billing+Event OK");
                        publishProgress("INFO|Billing + Midnight + Event read OK", "80");
                        break;

                    case LOAD_PROFILE: {
                        // Instantaneous data is required in ALL reading modes
                        publishProgress("INFO|Downloading Instantaneous data...", "40");
                        MeterData.append(ReadInstantData(port));
                        publishProgress("INFO|Downloading Load Profile (all available data)...", "50");
                        byte lpSavedTimOut2 = bytTimOut;
                        byte lpSavedTryCnt2 = bytTryCnt;
                        bytTimOut = (byte) 8;
                        bytTryCnt = (byte) 2;
                        lpDeadlineMs = System.currentTimeMillis() + 1440000L; // 24 min max
                        long lpStart2 = System.currentTimeMillis();
                        appendLog("LP_START days=auto bytTimOut=" + bytTimOut + " bytTryCnt=" + bytTryCnt + " deadline=1440s");
                        MeterData.append(ReadLoadSurveyData(port, lsDays));
                        appendLog("LP_END elapsed=" + (System.currentTimeMillis()-lpStart2) + "ms dataLen=" + MeterData.length());
                        flushLog();
                        lpDeadlineMs = 0;
                        bytTimOut = lpSavedTimOut2;
                        bytTryCnt = lpSavedTryCnt2;
                        UpdateStatus(CescRajMeterno, "Load Profile OK");
                        publishProgress("INFO|Load Profile read OK", "80");
                        break;
                    }

                    case COMPLETE: {
                        // ── Optimised complete sequence ──────────────────────────────────
                        // Target: Instant + Billing within 20s, total complete < 5 min.
                        //
                        // Strategy:
                        //  1. Use reduced timeout (3s) + single attempt for instant/billing:
                        //     responsive meters reply in <500ms; 3s gives headroom without
                        //     wasting 8s per dead OBIS.
                        //  2. Fast-fail (already in GetParameter) handles ACCESS_ERROR
                        //     instantly — no wasted retries.
                        //  3. Events moved AFTER LP: billing data is more important than
                        //     event logs for MR operations.
                        //  4. LP uses its own dedicated timeout/retry settings (8s/2).
                        //  5. If LP cannot start (meter rejects ALL LP attrs after reestablish),
                        //     the 360s deadline still caps the damage at 6 minutes.
                        //
                        byte fastTimOut = (byte) 3;   // 3s: sufficient for DLMS single-block
                        byte fastTryCnt = (byte) 1;   // 1 attempt: fast-fail covers ACCESS_ERROR
                        byte billingTimOut = (byte) 8; // Billing profile buffers can take longer than instant data
                        byte billingTryCnt = (byte) 2;

                        // Save originals — restore after pre-LP phase
                        byte origTimOut = bytTimOut;
                        byte origTryCnt = bytTryCnt;
                        bytTimOut = fastTimOut;
                        bytTryCnt = fastTryCnt;

                        publishProgress("INFO|Downloading Instantaneous data...", "30");
                        publishProgress("INFO|Instantaneous: ~3-5s expected", "30");
                        long tInstStart = System.currentTimeMillis();
                        MeterData.append(ReadInstantData(port));
                        appendLog("INSTANT_DONE elapsed=" + (System.currentTimeMillis()-tInstStart) + "ms");
                        UpdateStatus(CescRajMeterno, "Instantaneous OK");

                        publishProgress("INFO|Downloading Billing...", "40");
                        publishProgress("INFO|Billing: ~15-20s expected (block transfer)", "40");
                        bytTimOut = billingTimOut;
                        bytTryCnt = billingTryCnt;
                        long tBillStart = System.currentTimeMillis();
                        StringBuilder billingData = ReadBillingData(port);
                        MeterData.append(billingData);
                        appendLog("BILLING_DONE elapsed=" + (System.currentTimeMillis()-tBillStart) + "ms");
                        if (billingData != null && billingData.length() > 0) {
                            UpdateStatus(CescRajMeterno, "Billing OK");
                        } else {
                            appendLog("BILLING_WARN: Billing section empty in COMPLETE mode");
                            UpdateStatus(CescRajMeterno, "Billing FAILED");
                        }

                        publishProgress("INFO|Downloading Midnight Snapshot...", "50");
                        publishProgress("INFO|Midnight: ~5-8s expected", "50");
                        bytTimOut = fastTimOut;
                        bytTryCnt = fastTryCnt;
                        MeterData.append(ReadMidnightSnapshot(port));
                        UpdateStatus(CescRajMeterno, "Midnight OK");

                        // Restore for LP (needs longer timeout — meter can be slow building block)
                        bytTimOut = (byte) 8;
                        bytTryCnt = (byte) 2;
                        // lsDays is now determined inside ReadLoadSurveyData from profile_entries_max
                        // Set deadline generously: 90 days × 2s/day = 180s min, 90 days × 16s = 1440s max
                        // In practice most reads finish in 30-60s (HDLC partial fix removed the retries)
                        long lpBudgetMs = Math.min(Math.max((long) lsDays * 3000L, 180000L), 1440000L);
                        lpDeadlineMs = System.currentTimeMillis() + lpBudgetMs;
                        long lpStartMs = System.currentTimeMillis();
                        publishProgress("INFO|Downloading Load Profile (all available data)...", "60");
                        appendLog("LP_START days=auto bytTimOut=" + bytTimOut
                                + " bytTryCnt=" + bytTryCnt + " deadline=" + (lpBudgetMs/1000) + "s");
                        MeterData.append(ReadLoadSurveyData(port, lsDays));
                        long lpElapsed = System.currentTimeMillis() - lpStartMs;
                        appendLog("LP_END elapsed=" + lpElapsed + "ms dataLen=" + MeterData.length() + " deadlineHit=" + (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs));
                        flushLog();
                        lpDeadlineMs = 0;

                        // Events last — least critical for MR, moved after LP
                        bytTimOut = fastTimOut;
                        bytTryCnt = fastTryCnt;
                        publishProgress("INFO|Downloading Events...", "75");
                        publishProgress("INFO|Events: ~10-15s expected", "75");
                        MeterData.append(ReadEventData(port));

                        // Restore originals
                        bytTimOut = origTimOut;
                        bytTryCnt = origTryCnt;
                        UpdateStatus(CescRajMeterno, "All data OK");
                        publishProgress("INFO|Complete read OK", "80");
                        break;
                    }
                }
                // ============================================================

                publishProgress("INFO|Saving data file...", "85");
                String cleanMeterNo = MeterNo.replace("\r\n","").replace("\n","").replace("\t","").trim();
                String DataFileName = buildDataFileName(currentMeterMake.getDisplayName(),
                        readingMode.getDisplayName(), cleanMeterNo);
                String Filenm = "";

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
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
                String SysDate     = dateFormat.format(new Date()).replace("_", "");
                String GpsCoordinate = GetLocation();
                String userid      = BYPASS_USER_ID;
                // Use meter number as consumer ID when no MRO lookup
                String ConsumerNo  = MeterNo;

                DatabaseHandler Obj = new DatabaseHandler(getApplicationContext());
                String Sql = "INSERT INTO mr_detail (ConsumerNo,gpscoordinate,Mrdatetime,Istransfer,userid,entrydate,DataMode,FileName) " +
                        "VALUES('" + ConsumerNo + "','" + GpsCoordinate + "','" + SysDate +
                        "','N','" + userid + "','" + SysDate + "','OPTICAL','" + Filenm + "')";
                Obj.ExecuteQry(Sql);

                // Save reading log entry with actual meter number
                String logUpdateSql = "UPDATE ReadingLog SET MeterNo='" + MeterNo + "', Status='Complete'" +
                        " WHERE MeterNo='PENDING' AND Status='Reading Start'";
                Obj.ExecuteQry(logUpdateSql);

                // Save remarks
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
                h.postDelayed(toneGen::release, 1200);
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
                    String kwhStr = parseDlmsRegisterWithUnit(
                            lastMeterData.toString().toUpperCase(), "0100010800FF", buildScalerMap(lastMeterData.toString().toUpperCase()));
                    prefs.edit()
                            .putString("lastMeterNo",   dispMeterNo)
                            .putString("lastMake",      currentMeterMake != null ? currentMeterMake.getDisplayName() : "")
                            .putString("lastMode",      DataToBeRead != null ? DataToBeRead : "")
                            .putString("lastKwh",       kwhStr)
                            .putString("lastTimestamp", new java.text.SimpleDateFormat(
                                    "dd-MM-yyyy HH:mm", java.util.Locale.getDefault()).format(new java.util.Date()))
                            .apply();

                    showMeterReadingDialog(dispMeterNo, lastMeterData);
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
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HH_mm_ss");
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

    /**
     * Suggestion 5: Auto-detect meter make by reading 0000600800FF (manufacturer string).
     * Maps known manufacturer IDs to MeterMake enum.
     * Returns null if detection fails — caller falls back to selected make.
     */
    private MeterMake autoDetectMeterMake(UsbSerialPort port) {
        try {
            StringBuilder sb = new StringBuilder();
            StringBuilder result = this.GetParameter(port, (byte) 1, "0000600101FF", (byte) 2,
                    this.bytWait, (byte) 1, (byte) 5, false, sb);
            String raw = (result != null ? result.toString() : "") + sb.toString();
            String upper = raw.toUpperCase();
            appendLog("AUTO_DETECT raw=" + raw.substring(0, Math.min(raw.length(), 60)));
            if (upper.contains("SECURE") || upper.contains("534543")) return MeterMake.SECURE;
            if (upper.contains("HPL")   || upper.contains("48504c")) return MeterMake.HPL;
            // Try firmware ID object as fallback
            sb.setLength(0);
            result = this.GetParameter(port, (byte) 1, "0000600100FF", (byte) 2,
                    this.bytWait, (byte) 1, (byte) 5, false, sb);
            raw = (result != null ? result.toString() : "") + sb.toString();
            upper = raw.toUpperCase();
            // Genus meters have meter-no prefix KT, SS Secure, MgE HPL
            if (upper.contains("4b54") || upper.contains("KT")) {
                // KT prefix used by L&T and Genus — check manufacturer name
                sb.setLength(0);
                result = this.GetParameter(port, (byte) 1, "0000600101FF", (byte) 2,
                        this.bytWait, (byte) 1, (byte) 5, false, sb);
                String mfr = (result != null ? result.toString() : "") + sb.toString();
                if (mfr.toUpperCase().contains("4c26540069") || mfr.contains("L&T") || mfr.contains("ENERTECH"))
                    return MeterMake.LNT;
                if (mfr.toUpperCase().contains("474c4f42") || mfr.contains("GENUS"))
                    return MeterMake.GENUS;
            }
            if (upper.contains("4d674e") || upper.contains("MgN")) return MeterMake.HPL;
            if (upper.contains("4d674c") || upper.contains("MgL")) return MeterMake.HPL;
            if (upper.contains("4d674d") || upper.contains("MgM")) return MeterMake.HPL;
            if (upper.contains("4d674f") || upper.contains("MgO")) return MeterMake.HPL;
            if (upper.contains("4d674e") || upper.contains("AVON")) return MeterMake.AVON;
        } catch (Exception ex) {
            appendLog("AUTO_DETECT_EX: " + ex.getMessage());
        }
        return null; // detection failed
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
                return c.getString(c.getColumnIndex("Role"));
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
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmm");
        return dateFormat.format(new Date());
    }

    private String currentDateFormat1() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        return dateFormat.format(new Date());
    }

    private void doFakeWork() {
        // Minimal USB settle time — reduced from 50ms
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
    }

    private void doFakeWorked() {
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
    }

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
    private String buildValidationBitmap(String data) {
        String up = data.toUpperCase();
        // NamePlate: manufacturer present
        boolean np  = up.contains("0000600101FF 02") && up.contains("SECURE") || up.contains("HPL") ||
                up.contains("GENUS") || up.contains("ENERTECH") || up.contains("L&T");
        np = up.contains("0000600101FF 02");
        // Instantaneous: kWh import value present
        boolean ins = up.contains("0100010800FF 02") && up.contains("0100200700FF 02");
        // Billing: billing buffer present
        boolean bil = up.contains("0100620100FF 02");
        // LP: LP buffer present
        boolean lp  = up.contains("0100630100FF 02");
        // Events: any event log present
        boolean evt = up.contains("0000636200FF 02") || up.contains("0000636201FF 02");

        return String.format(
                "📋 NamePlate:%s  ⚡ Instant:%s  🧾 Billing:%s  📊 LP:%s  🔔 Events:%s",
                np?"✓":"✗", ins?"✓":"✗", bil?"✓":"✗", lp?"✓":"✗", evt?"✓":"✗");
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

    private void showMeterReadingDialog(String meterNo, StringBuilder meterDataSb) {
        try {
            String dataStr = meterDataSb != null ? meterDataSb.toString().toUpperCase() : "";
            java.util.Map<String, int[]> scalerMap = buildScalerMap(dataStr);
            java.util.Map<String, Integer> billingColMap = parseBillingCaptureObjects(dataStr);

            // ── INSTANTANEOUS ──────────────────────────────────────────────
            String kwhImportCum  = parseDlmsRegisterWithUnit(dataStr, "0100010800FF", scalerMap);
            String kwhExportCum  = parseDlmsRegisterWithUnit(dataStr, "0100020800FF", scalerMap);
            String kvahImportCum = parseDlmsRegisterWithUnit(dataStr, "0100090800FF", scalerMap);
            String kvahExportCum = parseDlmsRegisterWithUnit(dataStr, "01000A0800FF", scalerMap);
            String kvamdCum      = parseDlmsRegisterWithUnit(dataStr, "0100090600FF", scalerMap);
            if ("N/A".equals(kvamdCum)) kvamdCum = parseDlmsRegisterWithUnit(dataStr, "0100010600FF", scalerMap);
            String kwInst     = parseDlmsRegisterWithUnit(dataStr, "0100010700FF", scalerMap);
            String kvarImport = parseDlmsRegisterWithUnit(dataStr, "0100030700FF", scalerMap);
            String voltR = parseDlmsRegisterWithUnit(dataStr, "0100200700FF", scalerMap);
            String voltY = parseDlmsRegisterWithUnit(dataStr, "0100340700FF", scalerMap);
            String voltB = parseDlmsRegisterWithUnit(dataStr, "0100480700FF", scalerMap);
            String currR = parseDlmsRegisterWithUnit(dataStr, "01001F0700FF", scalerMap);
            String currY = parseDlmsRegisterWithUnit(dataStr, "0100330700FF", scalerMap);
            String currB = parseDlmsRegisterWithUnit(dataStr, "0100470700FF", scalerMap);
            String freq  = parseDlmsRegisterWithUnit(dataStr, "01000E0700FF", scalerMap);
            String pf    = parseDlmsRegisterWithUnit(dataStr, "01000D0700FF", scalerMap);
            String rtc   = extractRtc(dataStr);

            // ── BILLING ────────────────────────────────────────────────────
            String billingDate  = extractBillingByObis(dataStr, "0000000102FF", billingColMap, scalerMap);
            String kwhImpBill   = extractBillingByObis(dataStr, "0100010800FF", billingColMap, scalerMap);
            String kwhExpBill   = extractBillingByObis(dataStr, "0100020800FF", billingColMap, scalerMap);
            String kvahImpBill  = extractBillingByObis(dataStr, "0100090800FF", billingColMap, scalerMap);
            String kvahExpBill  = extractBillingByObis(dataStr, "01000A0800FF", billingColMap, scalerMap);
            // kVA T0 (Demand) — try each known demand OBIS in priority order.
            // Different meter makes use different OBIS for billing demand:
            //   0100090600FF — standard IS15959-2 kVA MD (Secure, L&T, HPL, Genus)
            //   0100010600FF — active-power MD fallback (Genus, AVON, HPL when above absent)
            //   01009E0600FF — non-standard Genus/AVON alternate apparent demand OBIS
            String kvaMdBill = extractBillingByObis(dataStr, "0100090600FF", billingColMap, scalerMap);
            if ("N/A".equals(kvaMdBill))
                kvaMdBill = extractBillingByObis(dataStr, "0100010600FF", billingColMap, scalerMap);
            if ("N/A".equals(kvaMdBill))
                kvaMdBill = extractBillingByObis(dataStr, "01009E0600FF", billingColMap, scalerMap);

            // ── TOD — dynamic: only slots in this meter billing profile ────
            java.util.List<String[]> todRows = new java.util.ArrayList<>();
            for (int t = 1; t <= 8; t++) {
                String impObis = String.format("010001080%XFF", t);
                String expObis = String.format("010002080%XFF", t);
                boolean hasImp = billingColMap.containsKey(impObis);
                boolean hasExp = billingColMap.containsKey(expObis);
                if (hasImp || hasExp) {
                    String imp = hasImp ? extractBillingByObis(dataStr, impObis, billingColMap, scalerMap) : "NA";
                    String exp = hasExp ? extractBillingByObis(dataStr, expObis, billingColMap, scalerMap) : "NA";
                    todRows.add(new String[]{"T" + t, imp, exp});
                }
            }

            // ── Build HTML table ────────────────────────────────────────────
            StringBuilder html = new StringBuilder();
            html.append("<html><head><style>");
            html.append("body{background:#0d1117;color:#c9d1d9;font-family:monospace;font-size:13px;margin:6px;}");
            html.append("h3{color:#58a6ff;text-align:center;margin:4px 0 8px;font-size:14px;}");
            html.append("table{width:100%;border-collapse:collapse;margin-bottom:8px;border:1px solid #30363d;}");
            html.append("th{background:#161b22;color:#58a6ff;padding:6px 4px;text-align:center;font-size:12px;border:1px solid #30363d;}");
            html.append("td{padding:5px 7px;border:1px solid #21262d;font-size:12px;}");
            html.append(".sec{background:#161b22;color:#e3b341;font-weight:bold;font-size:12px;border:1px solid #30363d;}");
            html.append(".lbl{color:#8b949e;}");
            html.append(".val{color:#7ee787;text-align:right;font-weight:bold;}");
            html.append(".dt{color:#f0f6fc;font-size:11px;}");
            html.append(".sub{color:#8b949e;font-size:10px;}");
            html.append("</style></head><body>");
            html.append("<h3>&#9889; METER DATA SUMMARY &mdash; ").append(meterNo).append("</h3>");

            // Instantaneous
            html.append("<table><tr><td class='sec' colspan='3'>&#128995; INSTANTANEOUS</td></tr>");
            html.append("<tr><td class='sec dt' colspan='3'>Date: ").append(rtc).append("</td></tr>");
            html.append("<tr><th>PARAMETER</th><th>IMPORT</th><th>EXPORT</th></tr>");
            addRow(html,"kWh  (Energy)",    kwhImportCum,  kwhExportCum);
            addRow(html,"kVAh (Apparent)",  kvahImportCum, kvahExportCum);
            addRow(html,"kVA  (Demand)",    kvamdCum,      "NA");
            addRow(html,"kW   (Active)",    kwInst,        "NA");
            addRow(html,"kVAr (Reactive)",  kvarImport,    "NA");
            html.append("<tr><td class='sub' colspan='3'>V R/Y/B: ")
                    .append(voltR).append(" / ").append(voltY).append(" / ").append(voltB)
                    .append(" &nbsp; I R/Y/B: ").append(currR).append(" / ").append(currY).append(" / ").append(currB)
                    .append(" &nbsp; Hz: ").append(freq).append(" &nbsp; PF: ").append(pf)
                    .append("</td></tr></table>");

            // Billing
            html.append("<table><tr><td class='sec' colspan='3'>&#129001; BILLING</td></tr>");
            html.append("<tr><td class='sec dt' colspan='3'>Date: ").append(billingDate).append("</td></tr>");
            html.append("<tr><th>PARAMETER</th><th>IMPORT</th><th>EXPORT</th></tr>");
            addRow(html,"kWh  T0 (Energy)",   kwhImpBill,  kwhExpBill);
            addRow(html,"kVAh T0 (Apparent)", kvahImpBill, kvahExpBill);
            addRow(html,"kVA  T0 (Demand)",   kvaMdBill,   "NA");
            html.append("</table>");

            // TOD
            if (!todRows.isEmpty()) {
                html.append("<table><tr><td class='sec' colspan='3'>&#128200; TOD (kWh Import / Export)</td></tr>");
                html.append("<tr><th>SLOT</th><th>IMPORT</th><th>EXPORT</th></tr>");
                for (String[] tod : todRows) addRow(html, tod[0], tod[1], tod[2]);
                html.append("</table>");
            }

            html.append("<div class='sub' style='text-align:center;padding:3px 0;'>")
                    .append("kWh = Energy &nbsp;|&nbsp; kVAh = Apparent &nbsp;|&nbsp; kVA = Demand &nbsp;|&nbsp; kVAr = Reactive")
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

    private void addRow(StringBuilder html, String label, String imp, String exp) {
        // Show "NA" for truly missing data (exp is null/empty/"NA")
        // Keep "—" as a legitimate displayed value only when caller explicitly passes it
        String impDisplay = (imp == null || imp.isEmpty()) ? "NA" : imp;
        String expDisplay = (exp == null || exp.isEmpty() || "NA".equals(exp)) ? "NA" : exp;
        html.append("<tr><td class='lbl'>").append(label).append("</td>")
                .append("<td class='val'>").append(impDisplay).append("</td>")
                .append("<td class='val'>").append(expDisplay).append("</td></tr>");
    }
    // ── DLMS Scaler Map ──────────────────────────────────────────────────────

    /**
     * Build a map of OBIS_HEX_UPPER → {scaler, unitCode} from all attr=3 lines in the TXT.
     * Scaler-unit structure: tag=02(struct) count=02 tag=0F(int8) SCALER tag=16(enum) UNIT
     * e.g.  "02020FFE162C" → scaler=0xFE=-2, unit=0x2C=44=Hz
     */
    private java.util.Map<String, int[]> buildScalerMap(String dataUpper) {
        java.util.Map<String, int[]> map = new java.util.HashMap<>();
        // Find every " OBIS 03 0202 0F SC 16 UC " pattern
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "([0-9A-F]{12}) 03 0202 ?0F([0-9A-F]{2})16([0-9A-F]{2})");
        java.util.regex.Matcher m = p.matcher(dataUpper.replaceAll("\\s+", " "));
        while (m.find()) {
            String obis = m.group(1);
            int sc = Integer.parseInt(m.group(2), 16);
            if (sc > 127) sc -= 256;
            int uc = Integer.parseInt(m.group(3), 16);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                map.putIfAbsent(obis, new int[]{sc, uc});
            }
        }
        return map;
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
                if (rawVal < 0) rawVal = Long.parseUnsignedLong(payload.substring(2,18),16);
                nextPos = 18;
            } else if (tag == 0x15) {                  // uint64 (Landis+Gyr energy)
                if (payload.length() < 18) return "NA";
                rawVal = Long.parseUnsignedLong(payload.substring(2, 18), 16);
                nextPos = 18;
            } else {
                return "NA";
            }

            // Scaler + unit from map (built from attr=3 lines)
            int sc = 0, uc = 0xFF;
            int[] su = scalerMap.get(obisHex.toUpperCase());
            if (su != null) { sc = su[0]; uc = su[1]; }

            // Demand OBIS unit normalisation (matches extractBillingByObis Fix B).
            // Ensures instant kVA (Demand) row also shows kVA correctly across all makes.
            String obisUp = obisHex.toUpperCase();
            if (obisUp.startsWith("01000106") || obisUp.startsWith("01000906")
                    || obisUp.startsWith("01009E06") || obisUp.startsWith("01009F06")) {
                if (uc == 0x1B || uc == 0xFF) uc = 0x1C;
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
                if (t0 == 0x12) pos += 4;
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
                if (t2 == 0x0F) pos += 2;
                // [3] data-index uint16
                if (pos + 2 > co.length()) break;
                int t3 = Integer.parseInt(co.substring(pos, pos + 2), 16); pos += 2;
                if (t3 == 0x12) pos += 4;

                if (!obisHex.isEmpty()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        colMap.putIfAbsent(obisHex, col);
                    }
                }
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
                                        java.util.Map<String, int[]> scalerMap) {
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

            // ── Find the LATEST billing cycle record using date anchors ────────
            // Struct parsing fails on large AVON/Genus records (94-150 columns).
            // Instead: locate all DLMS DateTime OctetStrings (090C) in the buffer,
            // filter to 1st-of-month midnight records, pick the most recent date,
            // then re-parse from the nearest struct(02) tag before that date.
            // This works for ALL meter makes regardless of column count.

            // Skip array header (01 NN or 01 82 NN NN)
            int headerEnd = 0;
            if (payload.startsWith("01")) {
                headerEnd = 2;
                int lb = Integer.parseInt(payload.substring(2,4),16); headerEnd+=2;
                if ((lb & 0x80) != 0) { int nb = lb & 0x7F; headerEnd += nb*2; }
            }

            // Scan for all 09 0C 07Exx date anchors
            int bestRecordPos = -1;
            long bestTimestamp = -1;

            int scanIdx = headerEnd;
            while (scanIdx < payload.length() - 28) {
                // Look for 090C followed by year 07E8-07EF (2024-2031)
                int datePos = payload.indexOf("090C07E", scanIdx);
                if (datePos < 0) break;

                String ts = payload.substring(datePos + 4, Math.min(datePos + 28, payload.length()));
                if (ts.length() < 24) { scanIdx = datePos + 1; continue; }

                try {
                    int y  = Integer.parseInt(ts.substring(0, 4), 16);
                    int mo = Integer.parseInt(ts.substring(4, 6), 16);   if (mo > 127) mo = 0;
                    int d  = Integer.parseInt(ts.substring(6, 8), 16);   if (d  > 127) d  = 0;
                    int h  = Integer.parseInt(ts.substring(10,12), 16); if (h  > 127) h  = 0;
                    int mi = Integer.parseInt(ts.substring(12,14), 16); if (mi > 127) mi = 0;

                    // Only billing cycle records: 1st of month at 00:00
                    if (d == 1 && h == 0 && mi == 0 && y > 2000 && y < 2100 && mo >= 1 && mo <= 12) {
                        long tsVal = (long)y*10000L + mo*100L + d;
                        if (tsVal > bestTimestamp) {
                            bestTimestamp = tsVal;
                            // Walk backwards to find the struct(02) tag that starts this record
                            // Records always start with 02 NN (struct tag + field count)
                            int recStart = datePos - 2; // step back past struct tag
                            // Search back up to 16 bytes for the 02 NN struct header
                            // (widened from 8: Genus records have struct-tag 4 bytes before
                            //  the first field, so back=4 is needed; 16 gives future headroom)
                            for (int back = 0; back <= 16; back += 2) {
                                int tryPos = datePos - back;
                                if (tryPos < headerEnd) break;
                                if (tryPos + 2 <= payload.length()) {
                                    int tag = Integer.parseInt(payload.substring(tryPos, tryPos+2), 16);
                                    if (tag == 0x02) { recStart = tryPos; break; }
                                }
                            }
                            bestRecordPos = recStart;
                        }
                    }
                } catch (Exception ignore) {}
                scanIdx = datePos + 1;
            }

            if (bestRecordPos < 0) return "NA";

            // ── Parse colIdx from the best record ────────────────────────────
            int pos = bestRecordPos;
            if (pos + 4 > payload.length()) return "NA";
            int stTag = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;
            if (stTag != 0x02) return "NA";
            int fieldCount = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;
            if (colIdx >= fieldCount) return "NA";

            for (int c2 = 0; c2 < colIdx && pos + 2 <= payload.length(); c2++) {
                pos = skipOneDlmsValue(payload, pos);
                if (pos < 0) return "NA";
            }

            if (pos + 2 > payload.length()) return "NA";
            int tag = Integer.parseInt(payload.substring(pos, pos+2), 16); pos += 2;

            // Timestamp (OctetString 12 bytes) — treat 0xFF as 0
            if (tag == 0x09) {
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

            // Numeric value (uint32/int32/uint16/int16/uint8/int8)
            long rawVal;
            if      (tag == 0x06) { if (pos + 8 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+8),16); }
            else if (tag == 0x05) { if (pos + 8 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+8),16); if (rawVal > 0x7FFFFFFFL) rawVal -= 0x100000000L; }
            else if (tag == 0x12) { if (pos + 4 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+4),16); }
            else if (tag == 0x10) { if (pos + 4 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+4),16); if (rawVal > 0x7FFF) rawVal -= 0x10000; }
            else if (tag == 0x11 || tag == 0x16) { if (pos + 2 > payload.length()) return "NA"; rawVal = Long.parseLong(payload.substring(pos,pos+2),16); }
            else return "NA";

            // Apply scaler and unit conversion.
            // For TOD OBIS (1.0.X.8.T.255 where T=1-8), the individual attr=3 scaler
            // is never read separately — inherit from the T0 base OBIS (1.0.X.8.0.255).
            String lookupObis = obisHex.toUpperCase();
            // If TOD byte (position 4 in OBIS) is 1-8, derive base OBIS with byte=0
            int[] su = scalerMap.get(lookupObis);
            if (su == null && lookupObis.length() == 12) {
                // Check if this is a TOD variant: X.0.1.8.T.255 → base = X.0.1.8.0.255
                // OBIS hex: CC CC CC CC CC CC where byte 4 (chars 8-9) is the TOD index
                String todByte = lookupObis.substring(8, 10);
                int todIdx = Integer.parseInt(todByte, 16);
                if (todIdx >= 1 && todIdx <= 8) {
                    // Replace TOD byte with 0 to get base OBIS
                    String baseObis = lookupObis.substring(0,8) + "00" + lookupObis.substring(10);
                    su = scalerMap.get(baseObis);
                }
            }
            int sc = (su != null) ? su[0] : 0;
            int uc = (su != null) ? su[1] : 0xFF;

            // Demand OBIS unit normalisation — applied for all meter makes:
            //   0100010600FF / 0100090600FF (standard) and 01009E0600FF / 01009F0600FF (Genus/AVON)
            // Some meters (Genus, AVON, HPL) report unit 0x1B (W) instead of 0x1C (VA)
            // for demand class-4 registers, or 0xFF (unknown) when no scaler was read.
            // Override both 0x1B and 0xFF to 0x1C so kVA T0 (Demand) displays correctly
            // on all meter makes.
            if (lookupObis.startsWith("01000106") || lookupObis.startsWith("01000906")
                    || lookupObis.startsWith("01009E06") || lookupObis.startsWith("01009F06")) {
                if (uc == 0x1B || uc == 0xFF) uc = 0x1C;
            }

            double val = rawVal * Math.pow(10, sc);
            double[] conv = unitConversion(uc);
            val /= conv[0];
            int dp = (int) conv[1];
            return String.format("%,."+dp+"f", val);

        } catch (Exception e) { return "NA"; }
    }

    private int skipOneDlmsValue(String payload, int pos) {
        try {
            if (pos + 2 > payload.length()) return -1;
            int tag = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
            switch (tag) {
                case 0x06: case 0x05: return pos + 8;     // uint32/int32
                case 0x12: case 0x10: return pos + 4;     // uint16/int16
                case 0x11: case 0x16: case 0x0F: return pos + 2; // uint8/enum/int8
                case 0x09: case 0x0A: {                   // OctetString/VisibleString
                    int ln = Integer.parseInt(payload.substring(pos, pos + 2), 16);
                    return pos + 2 + ln * 2;
                }
                case 0x01: case 0x02: {                   // Array/Structure
                    int cnt = Integer.parseInt(payload.substring(pos, pos + 2), 16); pos += 2;
                    for (int i = 0; i < cnt; i++) {
                        pos = skipOneDlmsValue(payload, pos);
                        if (pos < 0) return -1;
                    }
                    return pos;
                }
                default: return -1;
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
            int mo = Integer.parseInt(ts.substring(4, 6), 16);   if (mo == 0xFF) mo = 1;
            int d  = Integer.parseInt(ts.substring(6, 8), 16);   if (d  == 0xFF) d  = 1;
            int h  = Integer.parseInt(ts.substring(10, 12), 16); if (h  == 0xFF) h  = 0;
            int mi = Integer.parseInt(ts.substring(12, 14), 16); if (mi == 0xFF) mi = 0;
            int s  = Integer.parseInt(ts.substring(14, 16), 16); if (s  == 0xFF) s  = 0;
            return String.format("%02d/%02d/%04d %02d:%02d:%02d", d, mo, y, h, mi, s);
        } catch (Exception e) { return "NA"; }
    }

    /**
     * Extract specific meter values for display
     * This connects to the data collections from ReadInstantData and ReadBillingData
     * NOTE: Requires that MeterData contains the raw DLMS hex responses
     */
    private java.util.Map<String, String> extractMeterReadings(StringBuilder meterData) {
        java.util.Map<String, String> readings = new java.util.HashMap<>();

        if (meterData == null || meterData.length() == 0) {
            appendLog("ERROR: MeterData is empty — cannot extract readings");
            readings.put("error", "No meter data available");
            return readings;
        }

        String dataStr = meterData.toString();

        // These are the key OBIS codes to extract:
        // Cumulative (from instantaneous reading):
        String kwhImportCum = extractAndParse(dataStr, "0100010800FF", MeterReadingParser::parseKwhValue);
        String kwhExportCum = extractAndParse(dataStr, "0100020800FF", MeterReadingParser::parseKwhValue);
        String kvamdCum = extractAndParse(dataStr, "0100030700FF", MeterReadingParser::parsePowerValue);

        readings.put("kwhImportCum", kwhImportCum.isEmpty() ? "N/A" : kwhImportCum);
        readings.put("kwhExportCum", kwhExportCum.isEmpty() ? "N/A" : kwhExportCum);
        readings.put("kvamdCum", kvamdCum.isEmpty() ? "N/A" : kvamdCum);

        // Billing data (from latest record in OBIS 0100620100FF):
        // This would require parsing the billing profile buffer to get the latest record
        readings.put("kwhImportBill", "N/A");  // TODO: Extract from billing buffer
        readings.put("kwhExportBill", "N/A");  // TODO: Extract from billing buffer
        readings.put("kvamdbill", "N/A");      // TODO: Extract from billing buffer

        // TOD rates (meter-make specific OBIS codes):
        readings.put("tod1", "N/A");  // TODO: Identify correct OBIS for each meter make
        readings.put("tod2", "N/A");
        readings.put("tod3", "N/A");
        readings.put("tod4", "N/A");
        readings.put("tod5", "N/A");
        readings.put("tod6", "N/A");

        return readings;
    }

    /**
     * Helper to extract hex data for OBIS and parse it with given parser function
     */
    private String extractAndParse(String meterData, String obisCode,
                                   java.util.function.Function<String, String> parser) {
        try {
            String hexData = MeterReadingParser.extractObisData(meterData, obisCode);
            if (hexData.isEmpty()) {
                appendLog("DEBUG: OBIS " + obisCode + " not found in meter data");
                return "";
            }
            String result = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                result = parser.apply(hexData);
            }
            appendLog("DEBUG: OBIS " + obisCode + " → hex='" + hexData + "' → value='" + result + "'");
            return result;
        } catch (Exception e) {
            appendLog("ERROR parsing OBIS " + obisCode + ": " + e.getMessage());
            return "";
        }
    }

    // =====================================================================


    /** Returns true only for Secure meters — only they have 5E5B OBIS objects */
    private boolean isSecureMeter() {
        return currentMeterMake == MeterMake.SECURE;
    }

    public String MakeDataFile(String FileName, String Data) {
        try {
            File logFile = new File(getExternalMediaDirs()[0] + "/" + FileName + ".TXT");
            if (!logFile.exists()) logFile.createNewFile();
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
            buf.append(Data);
            buf.newLine();
            buf.close();
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

        // Load Profile specific checks
        if (readingMode.equals("LOAD_PROFILE") || readingMode.equals("COMPLETE")) {
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
        }

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
            File baseDir = getExternalMediaDirs()[0];
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
        int num116 = (int) 255;
        numArray38[index39] = (byte) num116;
        byte[] numArray39 = this.nPkt;
        int index40 = (int) num115;
        int num117 = 1;
        byte num118 = (byte) (index40 + num117);
        int num119 = 0;
        numArray39[index40] = (byte) num119;
        byte num120;
        if (!this.nNewAmmendment)
        {
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num118;
            int num121 = 1;
            num120 = (byte) (index9 + num121);
            int num122 = 0xFF; // end_time minute = wildcard → full day selected
            numArray40[index9] = (byte) num122;
        }
        else
        {
            byte[] numArray40 = this.nPkt;
            int index9 = (int) num118;
            int num121 = 1;
            num120 = (byte) (index9 + num121);
            // FIX: was intProfilePd (e.g. 15 for 15-min LP), which set end_time minute=15
            // meaning only 00:00→00:15 of each day was requested — almost no data returned.
            // 0xFF = DLMS "not specified" wildcard → selects the full day regardless of interval.
            int num122 = 0xFF;
            numArray40[index9] = (byte) num122;
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
            //   appendLog("Before Packet Sent...---1");

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
            for (int index9 = ((0xff & this.bytAddMode) + 15); index9 < this.pktLength - 1; ++index9)
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
                        for (int index9 = ((0xff & this.bytAddMode) + 8); index9 < this.pktLength - 1; ++index9)
                            strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index9]));
                        this.FrameType();
                        break;
                    }
                    else if (((System.currentTimeMillis() - original2))/1000 > (int) nTimeOut
                            && (int) num121 < (int) nTryCount)
                    {
                        if (this.nCounter > 0)
                        {
                            // Bytes are arriving but the frame is not yet complete.
                            // The meter is transmitting slowly at 9600 baud (~960 B/s).
                            // DO NOT clear the buffer. DO NOT resend RR. DO NOT count as retry.
                            // Simply reset the clock and keep reading — the rest will arrive.
                            // This eliminates the 18s-per-segment loop that blocked LP for 7+ min.
                            appendLog("HDLC_PARTIAL nCounter=" + this.nCounter + " — extending wait");
                            original2 = System.currentTimeMillis();
                            extendedWait = true;
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

                this.SendPkt(port,this.nPkt, (byte)((int) num194 + 3));

                long original4 = System.currentTimeMillis();
                do
                {

                    // Abort/deadline check inside every DataReceive call — prevents
                    // LP from silently hanging for 8s per block when meter is slow.
                    if (abortRequested || (lpDeadlineMs > 0 && System.currentTimeMillis() > lpDeadlineMs)) {
                        flag3 = false;
                        flag1 = false;
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

                    this.SendPkt(port,this.nPkt, (byte)((int) this.bytAddMode + 9));

                    long original6 = System.currentTimeMillis();
                    do
                    {


                        this.DataReceive(port);

                        String Hex1 = Integer.toHexString((0xff & this.nRcvPkt[1]) & 7);
                        if (Hex1.length() == 1)
                            Hex1 = "0" + Hex1;

                        //     appendLog("Hex 1" + Hex1);

                        String Hex2 = Integer.toHexString((0xff & this.nRcvPkt[2]));
                        if (Hex2.length() == 1)
                            Hex2 = "0" + Hex2;
                        //  appendLog("Hex 2" + Hex2);
                        String hex = (Hex1 + Hex2);

                        int Len = Integer.parseInt(hex, 16);

                        this.pktLength = Len;

                        if (this.nCounter > 2 && this.pktLength + 2 <= this.nCounter && ((int) this.nRcvPkt[this.pktLength + 1] == 126 && this.fcs( this.nRcvPkt, this.pktLength, (byte) 0)))
                        {
                            flag4 = true;
                            num197 = (byte) 0;
                            for (int index64 = ((0xff & this.bytAddMode) + 8); index64 < this.pktLength - 1; ++index64)
                                strbldDLMdata.append(Hex2Digit(this.nRcvPkt[index64]));
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
                if (!flag4)
                    SbData.append(strbldDLMdata.toString());
                return SbData;

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
            // appendLog("flag1 ###" + flag1);
            while (flag1) {
                // FIX: Only check abortRequested here — NOT lpDeadlineMs.
                // GetParameter1 is called for billing (0100620100FF) where lpDeadlineMs
                // is always 0 (LP hasn't started). But it is also called for LP bulk
                // reads where lpDeadlineMs is set. The lpDeadlineMs guard was causing
                // FLAG1_DEADLINE_BREAK to fire immediately on the second retry when
                // abortRequested=true (set by user during the 372s billing hang), cutting
                // off the block transfer mid-way. Billing must be allowed to complete
                // regardless of LP timing constraints.
                if (abortRequested) {
                    appendLog("FLAG1_DEADLINE_BREAK strbldLen=" + strbldDLMdata.length());
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
                do {
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
        boolean flag;
        do
        {
            this.ClearBuffer();
            flag = false;
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
            // (Manifested as "Error Sedpaket: srcPos=-1" in the USB driver's
            //  internal arraycopy when it tried to write beyond the buffer bounds.)
            if (length <= 0 || length > buffer.length) return false;
            byte[] sendBuffer = new byte[length];
            System.arraycopy(buffer, 0, sendBuffer, 0, length);
            int i = port.write(sendBuffer, USB_WRITE_TIMEOUT_MS);
            return (i >= 0);
        }
        catch (Exception ex)
        {
            appendLog("Error Sedpaket: " + ex.getMessage());
            return false;
        }
    }
    /**
     * Blocking USB read — inspired by Gurux serial approach.
     * Uses port.read(buffer, timeoutMs) which returns as soon as bytes arrive
     * or after timeoutMs if nothing comes. Eliminates sleep+poll overhead.
     *
     * @param port       USB serial port
     *
     */
    private void DataReceive(UsbSerialPort port) { DataReceive(port, 20); }
    private void DataReceive(UsbSerialPort port, int timeoutMs)
    {
        try {
            // Gurux pattern: blocking read — returns immediately when data arrives,
            // waits up to timeoutMs if the buffer is empty.
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

    private boolean SetNRM(UsbSerialPort port, int nWait, byte nTryCount, byte nTimeOut) {
        boolean flag1 = false;
        byte num1 = (byte) 5;
        this.nPkt[2] = (byte) 7;
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

        byte sendCommand[] = new byte[]{nPkt[0] ,nPkt[1],nPkt[2],nPkt[3],nPkt[4],nPkt[5],nPkt[6],nPkt[7],nPkt[8]};
        boolean Result =this.SendPkt( port,sendCommand, 9);
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

        // Receive UA response to DISC — use blocking receiveFrame (Gurux pattern)
        // Up to 2s; most meters respond in <200ms
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

        byte num5 = 5;//(byte) 5 +  this.bytAddMode;
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
        // Cap total SNRM time: nTryCount retries × nTimeOut seconds, hard max 8s total
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
        int num1 = 65;
        this.nPkt[0] = (byte) 126;
        this.nPkt[1] = (byte) 160;
        if ((int) this.bytAddMode == 0)
        {
            this.nPkt[3] = (byte)3;
            this.nPkt[4] = (byte)(num1);
        }

        this.nRecv = (byte) 0;
        this.nRecvLast = (byte) 0;
        this.nRecvCntr = (byte) 0;
        this.nSent = (byte) 0;
        this.nSentLast = (byte) 0;
        this.nSentCntr = (byte) 0;
        // appendLog("Address  Init Cpmpleted...!");
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

    private StringBuilder ReadBillingData(UsbSerialPort port)
    {
        // Billing data sequence required by XML parser:
        // 1. Current RTC (instant time) — must be FIRST so parser knows read timestamp
        // 2. Billing profile capture_objects (attr=3) — column definitions
        // 3. Billing profile buffer (attr=2) — all billing records
        // 4. Billing profile entries_in_use (attr=7) — record count
        // 5. ActivityCalendar (Class 20) — tariff schedule
        boolean flag = false;
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();

        // STEP 1: Current RTC — instant reading timestamp (must come first)
        DLMdata = this.GetParameter(port, (byte) 8, "0000010000FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // STEP 2: Scalar/unit descriptor for billing type
        DLMdata = this.ReadScalarUnit("BILLTYPC", port);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // STEP 3: Billing profile capture_objects (attr=3) — column definitions
        DLMdata = this.GetParameter(port, (byte) 7, "0100620100FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        else
            flag = true;

        // STEP 4: Billing profile buffer (attr=2) — all historical billing records
        DLMdata = this.GetParameter1(port, (byte) 7, "0100620100FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);
        else
            flag = true;

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

        if (flag == true) {
            appendLog("BILLING_WARN: Billing profile incomplete or zero-filled. Discarding invalid billing section.");
            strbldDLMdata.setLength(0);
        }

        return strbldDLMdata;
    }

    private StringBuilder ReadEventData(UsbSerialPort port)
    {
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        DLMdata=this.ReadScalarUnit("EVENT", port);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        String[] eventObis = {
                "0000636200FF", "0000636201FF", "0000636202FF",
                "0000636203FF", "0000636204FF", "0000636205FF",
                "0000636281FF"
        };
        byte[] attrs = {3, 2};
        for (String obis : eventObis) {
            for (byte attr : attrs) {
                DLMdata = this.GetParameter(port, (byte) 7, obis, attr,
                        this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (hasMeaningfulDlmsPayload(DLMdata)) {
                    strbldDLMdata.append(DLMdata);
                } else {
                    appendLog("EVENT_SKIP obis=" + obis + " attr=" + attr + " zero-or-empty");
                }
            }
        }

        if (strbldDLMdata.length() == 0) {
            appendLog("EVENT_WARN: Event profile responses were empty or zero-filled; skipping event section.");
        }

        return strbldDLMdata;
    }

    private StringBuilder ReadScalarUnit(String WhichData, UsbSerialPort port)
    {
        String str ="";
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        boolean flag = false;
        if (WhichData == "INSTANT")
        {
            if (isSecureMeter()) {
                flag = false;
                DLMdata =this.GetParameter(port,(byte) 7, "01005E5B03FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
                flag = false;
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B03FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
            }
        }
        else if (WhichData == "BILLTYPC")
        {
            if (isSecureMeter()) {
                flag = false;
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B06FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
                flag = false;
                DLMdata= this.GetParameter(port,(byte) 7, "01005E5B06FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
            }
        }
        else if (WhichData == "BLOCKLOAD")
        {
            if (isSecureMeter()) {
                // IMPORTANT: use GetParameter (not GetParameter_LS) here.
                // GetParameter_LS caused Secure meters to respond with a DM
                // (Disconnect Mode) frame for 01005E5B04FF, killing the HDLC session
                // before the LP buffer could be read. All other scaler reads
                // (INSTANT, BILLTYPC, DAILYLOAD, EVENT) use GetParameter — this
                // BLOCKLOAD case was the only inconsistent one.
                flag = false;
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B04FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
                flag = false;
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B04FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
            }
        }
        else if (WhichData == "DAILYLOAD")
        {
            if (isSecureMeter()) {
                flag = false;
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B05FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
                flag = false;
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B05FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
            }
        }
        else if (WhichData == "EVENT")
        {
            if (isSecureMeter()) {
                flag = false;
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B07FF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
                flag = false;
                DLMdata=this.GetParameter(port,(byte) 7, "01005E5B07FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
                if (DLMdata.toString()!="")
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
        if (currentMeterMake == MeterMake.GENUS || currentMeterMake == MeterMake.AVON) {
            appendLog("CAPTURE_OBJ_MAP make=" + currentMeterMake.getDisplayName() + " cols=18 VERIFIED from live read");
            return "0112"
                    + "020412000809060000010000ff0f02120000"  //  1: Clock
                    + "020412000409060100010400ff0f02120000"  //  2: kW Block Demand
                    + "020412000409060100090400ff0f02120000"  //  3: kVA Block Demand
                    + "020412000409060100050400ff0f02120000"  //  4: kVAr Block Demand
                    + "020412000309060100030700ff0f02120000"  //  5: Power Factor (verified)
                    + "020412000309060100010800ff0f02120000"  //  6: kWh Import cumul
                    + "020412000309060100090800ff0f02120000"  //  7: kVAh cumul
                    + "020412000309060100050800ff0f02120000"  //  8: kVArh Q1
                    + "020412000309060100080800ff0f02120000"  //  9: kVArh Q4
                    + "020412000309060100020800ff0f02120000"  // 10: kWh Export cumul
                    + "020412000309060100060800ff0f02120000"  // 11: kVArh Q2
                    + "020412000309060100070800ff0f02120000"  // 12: kVArh Q3
                    + "020412000309060100010600ff0f02120000"  // 13: MD kW
                    + "020412000309060100090600ff0f02120000"  // 14: MD kVA
                    + "0204120003090600005e5b0eff0f02120000"  // 15: Power On Duration (verified)
                    + "020412000109060000600f00ff0f02120000"  // 16: Phase Sequence (verified)
                    + "020412000109060000960500ff0f02120000"  // 17: Power Quality Event (verified: 0.0.150.5.0.255)
                    + "020412000109060000960200ff0f02120000"; // 18: Programming Count (verified: 0.0.150.2.0.255)
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

        // HPL meter LP profile [TODO:VERIFY from HPL meter specification]
        if (currentMeterMake == MeterMake.HPL) {
            appendLog("CAPTURE_OBJ_MAP make=HPL cols=18 NEEDS_VERIFICATION [TODO:VERIFY]");
            appendLog("CAPTURE_OBJ_COL 01 IC=8  OBIS=0.0.1.0.0.255   attr=2 Clock");
            appendLog("CAPTURE_OBJ_COL 02-18 [TODO:VERIFY from HPL meter specification]");
            // Using Genus mapping as placeholder — update when HPL spec is available
            return "0112"
                    + "020412000809060000010000ff0f02120000"  // 1: Clock
                    + "020412000409060100010400ff0f02120000"  // 2-18: [TODO:VERIFY HPL spec]
                    + "020412000409060100090400ff0f02120000"
                    + "020412000409060100050400ff0f02120000"
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

        // Landis+Gyr / L&G meter LP profile [TODO:VERIFY from L&G meter specification]
        if (currentMeterMake == MeterMake.LANDIS || currentMeterMake == MeterMake.LNG) {
            appendLog("CAPTURE_OBJ_MAP make=" + currentMeterMake.getDisplayName() + " cols=18 NEEDS_VERIFICATION [TODO:VERIFY]");
            appendLog("CAPTURE_OBJ_COL 01 IC=8  OBIS=0.0.1.0.0.255   attr=2 Clock");
            appendLog("CAPTURE_OBJ_COL 02-18 [TODO:VERIFY from Landis+Gyr meter specification]");
            return "0112"
                    + "020412000809060000010000ff0f02120000"  // 1: Clock
                    + "020412000409060100010400ff0f02120000"  // 2-18: [TODO:VERIFY L&G spec]
                    + "020412000409060100090400ff0f02120000"
                    + "020412000409060100050400ff0f02120000"
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

        // Unknown/default fallback — clock only
        appendLog("CAPTURE_OBJ_MAP make=" + currentMeterMake.getDisplayName() + " cols=1 DEFAULT_FALLBACK [TODO:VERIFY]");
        return "0101"
                + "020412000809060000010000ff0f02120000"; // just clock
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
        return !text.matches("0+");
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
        if (lastGplsResult == 0 && DLMdata != null && !DLMdata.toString().isEmpty()) {
            appendLog("RLS_ATTR3_FROM_METER");
            // FIX: GetParameter with isDLM=true writes only the header line
            // ("0007 0100630100FF 03 ") to strbldDLMdata. The actual payload
            // bytes are returned in DLMdata (SbData) — NOT in strbldDLMdata.
            // Without this explicit append the TXT has the header but no payload,
            // so the converter cannot parse column definitions and D4 is empty.
            strbldDLMdata.append(DLMdata);
        } else {
            // Meter rejected attr=3 — write hardcoded capture objects per meter make.
            String captureObjsHex = getCaptureObjectsForMake();
            strbldDLMdata.append("\r\n0007 0100630100FF 03 ").append(captureObjsHex);
            appendLog("RLS_ATTR3_HARDCODED make=" + currentMeterMake.getDisplayName());
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

        // Derive lsDays from meter capacity — ignore user input entirely.
        // This avoids under-reading (user selects 10 days when meter has 30)
        // and over-reading (user selects 30 days when meter only has 7 days stored).
        int maxDaysFromMeter = (recPerDay > 0 && profileEntriesMax > 0)
                ? (profileEntriesMax / recPerDay) : 0;
        // If attr=8 failed, fall back to entries_in_use / recPerDay
        if (maxDaysFromMeter <= 0 && entriesInUse > 0 && recPerDay > 0)
            maxDaysFromMeter = (entriesInUse / recPerDay) + 1;
        // Hard cap: maximum 35 days regardless of meter capacity.
        // When attr=8 parsing fails (complex type) AND entries_in_use=0,
        // fall back to 35 days (safe default for any interval).
        if (maxDaysFromMeter <= 0 || maxDaysFromMeter > 35) {
            maxDaysFromMeter = 35;
            appendLog("RLS_DAYS_CAP_FALLBACK → lsDays=35 (hard max)");
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
        StringBuilder lpSb = new StringBuilder();
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
            lpSb.append("\r\n0007 0100630100FF 02 ").append(lpHex);
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
                        lpSb.append("\r\n0007 0100630100FF 02 ").append(selHex);
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
            if (entriesInUse == 0) {
                appendLog("RLS_PROBE_EMPTY_METER — entries_in_use=0, probing recent days before skipping");
                for (int probeDayOffset = 0; probeDayOffset >= -3 && !probeHadData; probeDayOffset--) {
                    java.util.Calendar probeCal = java.util.Calendar.getInstance();
                    probeCal.add(java.util.Calendar.DAY_OF_YEAR, probeDayOffset);
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
                            lpSb.append("\r\n0007 0100630100FF 02 ").append(probeHex);
                            seenPayloads.add(probeHex);
                            appendLog("RLS_PROBE_HAS_DATA offset=" + probeDayOffset
                                    + " records=" + probeCnt + " — will read all " + lsDays + " days");
                        }
                    }
                }
                if (!probeHadData) {
                    appendLog("RLS_PROBE_CONFIRMED_EMPTY — no LP data in last 3 days");
                    appendLog("RLS_SEL_SKIP_ALL — entries_in_use=0 confirmed by probe");
                }
            }

            if (entriesInUse > 0 || probeHadData) {
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

                        lpSb.append("\r\n0007 0100630100FF 02 ").append(lpHex);
                        selectiveOk++;
                        appendLog("RLS_SEL_DAY day=-" + i + " len=" + lpHex.length() + " records=" + cnt);
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

        // Append all collected LP data after attr=3
        strbldDLMdata.append(lpSb);

        return strbldDLMdata;
    }



    private StringBuilder  ReadNamePlate(UsbSerialPort port)
    {
        boolean flag = false;
        StringBuilder strbldDLMdata = new StringBuilder();
        StringBuilder DLMdata = new StringBuilder();
        String str1 = "";
        //CultureInfo invariantCulture = CultureInfo.InvariantCulture;
        this.nNewAmmendment = false;



        if (isSecureMeter()) {
            DLMdata = this.GetParameter(port,(byte) 7, "00005E5B0AFF", (byte) 3, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (DLMdata != null && DLMdata.length() > 25)
            {
                this.nNewAmmendment = true;
                strbldDLMdata.append(DLMdata);
                flag = false;
                DLMdata=this.GetParameter(port,(byte) 7, "00005E5B0AFF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
                if (DLMdata.toString()!="")
                    strbldDLMdata.append(DLMdata);
            }
        }

        DLMdata=this.GetParameter(port,(byte) 1, "00002A0000FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append(DLMdata);
            //    this.chrMeterType = Convert.ToChar(byte.Parse(((object) strbldDLMdata).ToString().Substring(strbldDLMdata.Length - 2, 2), NumberStyles.HexNumber));

        }

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100000804FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (DLMdata.toString()!="") {
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

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 8, "0000010000FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append(DLMdata);
            //String str2 =  DLMdata.toString().substring(DLMdata.length() - 24,24);
            //      this.dateGlobalCurrentDate = DateTime.ParseExact(int.Parse(str2.Substring(6, 2), NumberStyles.HexNumber).ToString("00") + "/" + int.Parse(str2.Substring(4, 2), NumberStyles.HexNumber).ToString("00") + "/" + int.Parse(str2.Substring(0, 4), NumberStyles.HexNumber).ToString("0000") + " " + int.Parse(str2.Substring(10, 2), NumberStyles.HexNumber).ToString("00") + ":" + int.Parse(str2.Substring(12, 2), NumberStyles.HexNumber).ToString("00") + ":" + int.Parse(str2.Substring(14, 2), NumberStyles.HexNumber).ToString("00"), "dd/MM/yyyy HH:mm:ss", (IFormatProvider) invariantCulture, DateTimeStyles.AssumeLocal);
        }

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0000600100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);


        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100000200FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);


        if (isSecureMeter()) {
            flag = false;
            DLMdata=this.GetParameter(port,(byte) 1, "00005E5B09FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);
            if (DLMdata.toString()!="")
                strbldDLMdata.append(DLMdata);
        }


        flag = false;
        DLMdata=this.GetParameter(port,(byte) 7, "0100630100FF", (byte) 4, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);


        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0000000100FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="")
            strbldDLMdata.append(DLMdata);

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100000800FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="")
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

        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100608012FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append("\r\n0001 0100608012FF 02 0B");
        }
        else
            strbldDLMdata.append(DLMdata);


        flag = false;
        DLMdata=this.GetParameter(port,(byte) 63, "0000600A01FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append("\r\n003F 0000600A01FF 02 0B");
        }
        else
            strbldDLMdata.append(DLMdata);


        flag = false;
        DLMdata=this.GetParameter(port,(byte) 1, "0100608017FF", (byte) 2, this.bytWait, this.bytTryCnt, this.bytTimOut, true,  strbldDLMdata);

        if (DLMdata.toString()!="") {
            strbldDLMdata.append("\r\n0001 0100608012FF 02 0B");
        }
        else
            strbldDLMdata.append(DLMdata);

        return strbldDLMdata;
    }


    private StringBuilder ReadMidnightSnapshot(UsbSerialPort port)
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

        // attr=7 = entries_in_use
        DLMdata = this.GetParameter(port, (byte) 7, "0100630200FF", (byte) 7,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        // attr=2 = buffer (daily snapshot records)
        DLMdata = this.GetParameter(port, (byte) 7, "0100630200FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata))
            strbldDLMdata.append(DLMdata);

        if (strbldDLMdata.length() == 0) {
            appendLog("MIDNIGHT_WARN: Midnight snapshot responses were empty or zero-filled; skipping section.");
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

        // Current L1
        DLMdata = this.GetParameter(port, (byte) 3, "01001F0700FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
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

        // kWh Export cumulative (1.0.2.8.0.255)
        DLMdata = this.GetParameter(port, (byte) 3, "0100020800FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100020800FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

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
        DLMdata = this.GetParameter(port, (byte) 3, "01000A0800FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "01000A0800FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

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

        // ── Phasor angles (IEC 62056-21, OBIS family 1.0.81.7.E.255, class 3) ──────
        // Source: Gurux DLMS COSEM library — Unit.java enum value 8 = PHASE_ANGLE_DEGREE
        // C=81 (=0x51) = relative angle between two phasors
        // D=7 = instantaneous value-group
        // E selects the angle pair:
        //   E=0 : U(L1) vs U(L1) = 0° (reference — not read, always zero)
        //   E=1 : U(L2) vs U(L1) — voltage L2 angle rel. to L1
        //   E=2 : U(L3) vs U(L1) — voltage L3 angle rel. to L1
        //   E=4 : I(L1) vs U(L1) — current L1 angle (≈ PF angle for L1)
        //   E=5 : I(L2) vs U(L1) — current L2 angle rel. to U(L1)
        //   E=6 : I(L3) vs U(L1) — current L3 angle rel. to U(L1)
        // Scaler/unit: uc=8 (degrees), typical sc=-2 → value/100 = degrees
        // Together with V_L1/L2/L3 and I_L1/L2/L3 already read above,
        // these 5 angles provide the complete 3Ph-4W phasor diagram dataset.
        // Note: these may return "not supported" on older firmware — filtered by
        // hasMeaningfulDlmsPayload(), so no harm if the meter returns null.

        // Voltage L2 angle vs L1 (1.0.81.7.1.255 = 0x01 00 51 07 01 FF)
        DLMdata = this.GetParameter(port, (byte) 3, "0100510701FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100510701FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Voltage L3 angle vs L1 (1.0.81.7.2.255 = 0x01 00 51 07 02 FF)
        DLMdata = this.GetParameter(port, (byte) 3, "0100510702FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100510702FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Current L1 angle vs U(L1) (1.0.81.7.4.255 = 0x01 00 51 07 04 FF)
        DLMdata = this.GetParameter(port, (byte) 3, "0100510704FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100510704FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Current L2 angle vs U(L1) (1.0.81.7.5.255 = 0x01 00 51 07 05 FF)
        DLMdata = this.GetParameter(port, (byte) 3, "0100510705FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100510705FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // Current L3 angle vs U(L1) (1.0.81.7.6.255 = 0x01 00 51 07 06 FF)
        DLMdata = this.GetParameter(port, (byte) 3, "0100510706FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0100510706FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        // ── Meter status registers ────────────────────────────────────────────
        // Manufacturer/model identification (class 3, 0.0.96.8.0.255)
        DLMdata = this.GetParameter(port, (byte) 3, "0000600800FF", (byte) 3,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);
        DLMdata = this.GetParameter(port, (byte) 3, "0000600800FF", (byte) 2,
                this.bytWait, this.bytTryCnt, this.bytTimOut, true, strbldDLMdata);
        if (hasMeaningfulDlmsPayload(DLMdata)) strbldDLMdata.append(DLMdata);

        return strbldDLMdata;
    }
}
