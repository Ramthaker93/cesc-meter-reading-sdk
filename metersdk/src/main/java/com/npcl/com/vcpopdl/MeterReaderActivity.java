package com.npcl.com.vcpopdl;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.npcl.com.vcpopdl.metersdk.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * MeterReaderActivity — self-contained reading screen bundled inside the metersdk AAR.
 *
 * Launch from any Activity:
 *   Intent i = new Intent(this, MeterReaderActivity.class);
 *   i.putExtra(MeterReaderActivity.EXTRA_METER_MAKE, "Secure");   // optional
 *   i.putExtra(MeterReaderActivity.EXTRA_READING_MODE, "Complete"); // optional
 *   i.putExtra(MeterReaderActivity.EXTRA_FILE_PREFIX, "MyPrefix");  // optional
 *   startActivityForResult(i, REQUEST_CODE);
 *
 * Result (RESULT_OK on success, RESULT_CANCELED on abort/error):
 *   String meterNo   = data.getStringExtra(MeterReaderActivity.RESULT_METER_NO);
 *   String filePath  = data.getStringExtra(MeterReaderActivity.RESULT_FILE_PATH);
 *   String mfr       = data.getStringExtra(MeterReaderActivity.RESULT_MANUFACTURER);
 *   String validation= data.getStringExtra(MeterReaderActivity.RESULT_VALIDATION);
 */
public class MeterReaderActivity extends AppCompatActivity {

    // =========================================================================
    // INTENT EXTRAS — INPUT
    // =========================================================================
    public static final String EXTRA_METER_MAKE   = "mr_meter_make";
    public static final String EXTRA_READING_MODE = "mr_reading_mode";
    public static final String EXTRA_FILE_PREFIX  = "mr_file_prefix";

    // =========================================================================
    // INTENT EXTRAS — OUTPUT (returned in result Intent)
    // =========================================================================
    public static final String RESULT_METER_NO     = "mr_result_meter_no";
    public static final String RESULT_FILE_PATH    = "mr_result_file_path";
    public static final String RESULT_MANUFACTURER = "mr_result_manufacturer";
    public static final String RESULT_VALIDATION   = "mr_result_validation";

    // =========================================================================
    // VIEWS
    // =========================================================================
    private Spinner      spinnerMake;
    private Spinner      spinnerMode;
    private Button       btnStart;
    private Button       btnAbort;
    private Button       btnClearLog;
    private Button       btnDone;
    private TextView     tvStatus;
    private ProgressBar  progressBar;
    private TextView     tvLog;
    private ScrollView   scrollLog;
    private LinearLayout resultCard;
    private TextView     tvResult;
    private TextView     tvUsbStatus;
    private LinearLayout configCard;
    private LinearLayout badgeCard;
    private TextView     tvBadge;

    // =========================================================================
    // STATE
    // =========================================================================
    private MeterReadingSDK sdk;
    private MeterReadingSDK.MeterReadingResult lastResult = null;

    /** When both make+mode arrive via intent, spinners are hidden and these are used directly. */
    private MeterReadingSDK.MeterMake   lockedMake = null;
    private MeterReadingSDK.ReadingMode lockedMode = null;

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meter_reader);

        bindViews();
        applyIntentExtras();
        populateSpinners();
        checkUsbStatus();

        btnStart.setOnClickListener(v -> startReading());
        btnAbort.setOnClickListener(v -> abortReading());
        btnClearLog.setOnClickListener(v -> clearLog());
        btnDone.setOnClickListener(v -> returnResult());
        findViewById(R.id.mr_btnRefreshUsb).setOnClickListener(v -> {
            checkUsbStatus();
            appendLog("USB status refreshed.", false);
        });

        appendLog("Ready. Connect USB optical cable and press START READING.", false);
    }

    @Override
    protected void onDestroy() {
        if (sdk != null && sdk.isReading()) sdk.abort();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (sdk != null && sdk.isReading()) {
            sdk.abort();
            appendLog("Back pressed — reading aborted.", true);
        }
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    // =========================================================================
    // SETUP
    // =========================================================================

    private void bindViews() {
        spinnerMake  = findViewById(R.id.mr_spinnerMeterMake);
        spinnerMode  = findViewById(R.id.mr_spinnerReadMode);
        btnStart     = findViewById(R.id.mr_btnStart);
        btnAbort     = findViewById(R.id.mr_btnAbort);
        btnClearLog  = findViewById(R.id.mr_btnClearLog);
        btnDone      = findViewById(R.id.mr_btnDone);
        tvStatus     = findViewById(R.id.mr_tvStatus);
        progressBar  = findViewById(R.id.mr_progressBar);
        tvLog        = findViewById(R.id.mr_tvLog);
        scrollLog    = findViewById(R.id.mr_scrollLog);
        resultCard   = findViewById(R.id.mr_resultCard);
        tvResult     = findViewById(R.id.mr_tvResult);
        tvUsbStatus  = findViewById(R.id.mr_tvUsbStatus);
        configCard   = findViewById(R.id.mr_configCard);
        badgeCard    = findViewById(R.id.mr_badgeCard);
        tvBadge      = findViewById(R.id.mr_tvBadge);
    }

    private void applyIntentExtras() {
        String makeStr = getIntent().getStringExtra(EXTRA_METER_MAKE);
        String modeStr = getIntent().getStringExtra(EXTRA_READING_MODE);
        String prefix  = getIntent().getStringExtra(EXTRA_FILE_PREFIX);

        if (makeStr != null) {
            for (MeterReadingSDK.MeterMake m : MeterReadingSDK.MeterMake.values()) {
                if (m.displayName.equalsIgnoreCase(makeStr)) { lockedMake = m; break; }
            }
        }
        if (modeStr != null) {
            for (MeterReadingSDK.ReadingMode m : MeterReadingSDK.ReadingMode.values()) {
                if (m.displayName.equalsIgnoreCase(modeStr)) { lockedMode = m; break; }
            }
        }

        // If both are provided, hide the config card and show a badge instead
        if (lockedMake != null && lockedMode != null) {
            configCard.setVisibility(View.GONE);
            badgeCard.setVisibility(View.VISIBLE);
            tvBadge.setText(lockedMake.displayName + "  |  " + lockedMode.displayName
                    + (prefix != null ? "  |  " + prefix : ""));
        }
    }

    private void populateSpinners() {
        List<String> makes = new ArrayList<>();
        for (MeterReadingSDK.MeterMake m : MeterReadingSDK.MeterMake.values())
            makes.add(m.displayName);
        ArrayAdapter<String> makeAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, makes);
        makeAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMake.setAdapter(makeAd);
        spinnerMake.setSelection(0);

        List<String> modes = new ArrayList<>();
        for (MeterReadingSDK.ReadingMode m : MeterReadingSDK.ReadingMode.values())
            modes.add(m.displayName);
        ArrayAdapter<String> modeAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, modes);
        modeAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(modeAd);
        spinnerMode.setSelection(2); // default: Complete

        // Pre-select from locked values if partial extras were given
        if (lockedMake != null) {
            for (int i = 0; i < makes.size(); i++) {
                if (makes.get(i).equals(lockedMake.displayName)) { spinnerMake.setSelection(i); break; }
            }
        }
        if (lockedMode != null) {
            for (int i = 0; i < modes.size(); i++) {
                if (modes.get(i).equals(lockedMode.displayName)) { spinnerMode.setSelection(i); break; }
            }
        }
    }

    private void checkUsbStatus() {
        try {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            if (drivers == null || drivers.isEmpty()) {
                tvUsbStatus.setText("USB: Not Connected");
                tvUsbStatus.setBackgroundColor(Color.parseColor("#C62828"));
                tvUsbStatus.setTextColor(Color.WHITE);
            } else {
                String dev = drivers.get(0).getDevice().getDeviceName();
                tvUsbStatus.setText("USB: Connected — " + dev);
                tvUsbStatus.setBackgroundColor(Color.parseColor("#2E7D32"));
                tvUsbStatus.setTextColor(Color.WHITE);
            }
        } catch (Exception ex) {
            tvUsbStatus.setText("USB: Error — " + ex.getMessage());
            tvUsbStatus.setBackgroundColor(Color.parseColor("#C62828"));
            tvUsbStatus.setTextColor(Color.WHITE);
        }
    }

    // =========================================================================
    // READING
    // =========================================================================

    private void startReading() {
        checkUsbStatus();
        resultCard.setVisibility(View.GONE);
        lastResult = null;
        clearLog();

        // Resolve make and mode
        MeterReadingSDK.MeterMake make = lockedMake;
        MeterReadingSDK.ReadingMode mode = lockedMode;

        if (make == null) {
            String sel = spinnerMake.getSelectedItem().toString();
            for (MeterReadingSDK.MeterMake m : MeterReadingSDK.MeterMake.values()) {
                if (m.displayName.equals(sel)) { make = m; break; }
            }
            if (make == null) make = MeterReadingSDK.MeterMake.SECURE;
        }
        if (mode == null) {
            String sel = spinnerMode.getSelectedItem().toString();
            for (MeterReadingSDK.ReadingMode m : MeterReadingSDK.ReadingMode.values()) {
                if (m.displayName.equals(sel)) { mode = m; break; }
            }
            if (mode == null) mode = MeterReadingSDK.ReadingMode.COMPLETE;
        }

        String prefix = getIntent().getStringExtra(EXTRA_FILE_PREFIX);
        String filePrefix = (prefix != null && !prefix.trim().isEmpty())
                ? prefix.trim()
                : (make.displayName + "_" + mode.filePrefix + "_"
                   + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));

        appendLog("=== START: " + make.displayName + " | " + mode.displayName + " ===", false);
        setBusy(true);

        sdk = new MeterReadingSDK(this);
        final MeterReadingSDK.MeterMake  finalMake = make;
        final MeterReadingSDK.ReadingMode finalMode = mode;

        sdk.startReading(finalMake, finalMode, filePrefix, new MeterReadingSDK.ReadingCallback() {

            @Override
            public void onProgress(String message, int pct) {
                progressBar.setProgress(pct);
                boolean warn = message.startsWith("WARN") || message.contains("⚠");
                updateStatus(message, warn ? "#212121" : "#1565C0",
                        warn ? "#FFF8E1" : "#FFFFFF");
                appendLog(message, warn);
            }

            @Override
            public void onComplete(MeterReadingSDK.MeterReadingResult result) {
                setBusy(false);
                progressBar.setProgress(100);
                lastResult = result;

                String summary = "SUCCESS\n\n"
                        + "Meter No:     " + result.meterNo + "\n"
                        + "Manufacturer: " + result.manufacturer + "\n"
                        + "File saved:   " + result.filePath + "\n\n"
                        + result.validationSummary;
                tvResult.setText(summary);
                tvResult.setTextColor(Color.parseColor("#1B5E20"));
                resultCard.setBackgroundColor(Color.parseColor("#E8F5E9"));
                resultCard.setVisibility(View.VISIBLE);

                updateStatus("Reading complete — " + result.meterNo,
                        "#FFFFFF", "#2E7D32");
                appendLog("=== COMPLETE: " + result.meterNo + " | " + result.filePath + " ===", false);
                Toast.makeText(MeterReaderActivity.this,
                        "Done — " + result.meterNo, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String errorMessage) {
                setBusy(false);
                progressBar.setProgress(0);

                tvResult.setText("ERROR\n\n" + errorMessage);
                tvResult.setTextColor(Color.parseColor("#B71C1C"));
                resultCard.setBackgroundColor(Color.parseColor("#FFEBEE"));
                resultCard.setVisibility(View.VISIBLE);
                btnDone.setText("CLOSE");
                btnDone.setBackgroundColor(Color.parseColor("#C62828"));

                updateStatus("Failed: " + errorMessage, "#FFFFFF", "#C62828");
                appendLog("ERROR: " + errorMessage, true);
                Toast.makeText(MeterReaderActivity.this,
                        "Error: " + errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void abortReading() {
        if (sdk != null) sdk.abort();
        appendLog("Abort requested by user.", true);
        btnAbort.setEnabled(false);
    }

    // =========================================================================
    // RETURN RESULT
    // =========================================================================

    private void returnResult() {
        if (lastResult != null) {
            Intent data = new Intent();
            data.putExtra(RESULT_METER_NO,     lastResult.meterNo);
            data.putExtra(RESULT_FILE_PATH,    lastResult.filePath);
            data.putExtra(RESULT_MANUFACTURER, lastResult.manufacturer);
            data.putExtra(RESULT_VALIDATION,   lastResult.validationSummary);
            setResult(RESULT_OK, data);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    // =========================================================================
    // UI HELPERS
    // =========================================================================

    private void setBusy(boolean busy) {
        btnStart.setEnabled(!busy);
        btnAbort.setEnabled(busy);
        spinnerMake.setEnabled(!busy);
        spinnerMode.setEnabled(!busy);
        if (!busy) {
            tvStatus.setTextColor(Color.parseColor("#1565C0"));
            tvStatus.setBackgroundColor(Color.WHITE);
        }
    }

    private void updateStatus(String msg, String textHex, String bgHex) {
        tvStatus.setText(msg);
        try {
            tvStatus.setTextColor(Color.parseColor(textHex));
            tvStatus.setBackgroundColor(Color.parseColor(bgHex));
        } catch (Exception ignored) {}
    }

    private void appendLog(final String message, final boolean isError) {
        runOnUiThread(() -> {
            try {
                String ts   = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                String icon = isError ? "⚠ " : "• ";
                String line = "[" + ts + "] " + icon + message + "\n";

                String combined = line + tvLog.getText().toString();
                SpannableStringBuilder ssb = new SpannableStringBuilder(combined);
                ssb.setSpan(new ForegroundColorSpan(
                                isError ? Color.parseColor("#C62828") : Color.parseColor("#1A237E")),
                        0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvLog.setText(ssb);
                scrollLog.post(() -> scrollLog.scrollTo(0, 0));
            } catch (Exception ignored) {}
        });
    }

    private void clearLog() {
        tvLog.setText("");
        progressBar.setProgress(0);
        updateStatus("Ready — connect USB optical cable and press START READING.",
                "#1565C0", "#FFFFFF");
        resultCard.setVisibility(View.GONE);
        btnDone.setText("DONE — RETURN TO APP");
        btnDone.setBackgroundColor(Color.parseColor("#2E7D32"));
        lastResult = null;
        checkUsbStatus();
    }
}
