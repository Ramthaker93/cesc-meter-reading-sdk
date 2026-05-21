package com.npcl.com.vcpopdl;

import android.content.Context;
import android.graphics.Color;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TestSDKActivity — minimal test harness for ReadingSDK.
 *
 * How to use:
 *   1. Connect USB optical cable to the Android device.
 *   2. Aim the optical head at a Secure meter.
 *   3. Select Meter Make = "Secure" and Reading Mode = "Complete" (defaults).
 *   4. Press START READING.
 *   5. Watch the Activity Log for progress.
 *   6. On success the result card shows file path, meter no, and validation summary.
 */
public class TestSDKActivity extends AppCompatActivity {

    private ReadingSDK sdk;

    private Spinner spinnerMake;
    private Spinner spinnerMode;
    private EditText etFileName;
    private Button btnStart;
    private Button btnAbort;
    private Button btnClearLog;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private TextView tvLog;
    private ScrollView scrollLog;
    private LinearLayout resultCard;
    private TextView tvResult;
    private TextView tvUsbStatus;

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_sdk);

        bindViews();
        populateSpinners();
        checkUsbStatus();
        appendLog("SDK ready. Connect USB optical cable and press START READING.", false);

        btnStart.setOnClickListener(v -> startReading());
        btnAbort.setOnClickListener(v -> abortReading());
        btnClearLog.setOnClickListener(v -> clearLog());
    }

    @Override
    protected void onDestroy() {
        if (sdk != null && sdk.isReading()) sdk.abort();
        super.onDestroy();
    }

    // =========================================================================
    // UI SETUP
    // =========================================================================

    private void bindViews() {
        spinnerMake  = findViewById(R.id.sdk_spinnerMeterMake);
        spinnerMode  = findViewById(R.id.sdk_spinnerReadMode);
        etFileName   = findViewById(R.id.sdk_etFileName);
        btnStart     = findViewById(R.id.sdk_btnStart);
        btnAbort     = findViewById(R.id.sdk_btnAbort);
        btnClearLog  = findViewById(R.id.sdk_btnClearLog);
        tvStatus     = findViewById(R.id.sdk_tvStatus);
        progressBar  = findViewById(R.id.sdk_progressBar);
        tvLog        = findViewById(R.id.sdk_tvLog);
        scrollLog    = findViewById(R.id.sdk_scrollLog);
        resultCard   = findViewById(R.id.sdk_resultCard);
        tvResult     = findViewById(R.id.sdk_tvResult);
        tvUsbStatus  = findViewById(R.id.sdk_tvUsbStatus);
    }

    private void populateSpinners() {
        // Meter Make spinner — values from ReadingSDK enum
        List<String> makes = new ArrayList<>();
        for (ReadingSDK.MeterMake m : ReadingSDK.MeterMake.values())
            makes.add(m.getDisplayName());
        ArrayAdapter<String> makeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, makes);
        makeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMake.setAdapter(makeAdapter);
        spinnerMake.setSelection(0); // default: Secure

        // Reading Mode spinner
        List<String> modes = new ArrayList<>();
        for (ReadingSDK.ReadingMode m : ReadingSDK.ReadingMode.values())
            modes.add(m.getDisplayName());
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, modes);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(modeAdapter);
        spinnerMode.setSelection(2); // default: Complete
    }

    private void checkUsbStatus() {
        try {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            if (drivers == null || drivers.isEmpty()) {
                tvUsbStatus.setText("USB: Not Connected");
                tvUsbStatus.setBackgroundColor(Color.parseColor("#C62828"));
            } else {
                String devName = drivers.get(0).getDevice().getDeviceName();
                tvUsbStatus.setText("USB: Connected — " + devName);
                tvUsbStatus.setBackgroundColor(Color.parseColor("#2E7D32"));
                tvUsbStatus.setTextColor(Color.WHITE);
            }
        } catch (Exception ex) {
            tvUsbStatus.setText("USB: Error — " + ex.getMessage());
            tvUsbStatus.setBackgroundColor(Color.parseColor("#C62828"));
        }
    }

    // =========================================================================
    // SDK INTERACTION
    // =========================================================================

    private void startReading() {
        checkUsbStatus();
        clearLog();
        resultCard.setVisibility(View.GONE);

        String makeStr = spinnerMake.getSelectedItem().toString();
        String modeStr = spinnerMode.getSelectedItem().toString();
        String fileName = etFileName.getText().toString().trim();
        if (fileName.isEmpty()) fileName = "SDK_TEST";

        // Map spinner selection to enum
        ReadingSDK.MeterMake meterMake = ReadingSDK.MeterMake.SECURE; // default
        for (ReadingSDK.MeterMake m : ReadingSDK.MeterMake.values()) {
            if (m.getDisplayName().equals(makeStr)) { meterMake = m; break; }
        }
        ReadingSDK.ReadingMode readingMode = ReadingSDK.ReadingMode.COMPLETE; // default
        for (ReadingSDK.ReadingMode m : ReadingSDK.ReadingMode.values()) {
            if (m.getDisplayName().equals(modeStr)) { readingMode = m; break; }
        }

        appendLog("=== TEST START: Make=" + meterMake.getDisplayName()
                + " Mode=" + readingMode.getDisplayName()
                + " File=" + fileName + " ===", false);

        setBusy(true);

        sdk = new ReadingSDK(this);

        final ReadingSDK.MeterMake finalMake = meterMake;
        final ReadingSDK.ReadingMode finalMode = readingMode;
        final String finalFile = fileName;

        sdk.startReading(finalMake, finalMode, finalFile, new ReadingSDK.ReadingCallback() {
            @Override
            public void onProgress(String message, int progressPercent) {
                progressBar.setProgress(progressPercent);
                tvStatus.setText(progressPercent + "% — " + message);
                boolean warn = message.startsWith("WARN") || message.contains("WARNING");
                appendLog(message, warn);
            }

            @Override
            public void onComplete(ReadingSDK.MeterReadingResult result) {
                setBusy(false);
                progressBar.setProgress(100);

                // Show green result card
                String summary = "✓ SUCCESS\n\n"
                        + "Meter No:   " + result.meterNo + "\n"
                        + "Mfr:        " + result.manufacturer + "\n"
                        + "File:       " + result.filePath + "\n\n"
                        + "Validation: " + result.validationSummary;
                tvResult.setText(summary);
                resultCard.setVisibility(View.VISIBLE);
                resultCard.setBackgroundColor(Color.parseColor("#E8F5E9"));
                tvResult.setTextColor(Color.parseColor("#1B5E20"));

                tvStatus.setText("✓ Reading complete — " + result.meterNo);
                tvStatus.setBackgroundColor(Color.parseColor("#2E7D32"));
                tvStatus.setTextColor(Color.WHITE);

                appendLog("=== COMPLETE: meterNo=" + result.meterNo
                        + " file=" + result.filePath + " ===", false);
                appendLog(result.validationSummary, false);

                Toast.makeText(TestSDKActivity.this,
                        "Done — " + result.meterNo, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String errorMessage) {
                setBusy(false);
                progressBar.setProgress(0);

                // Show red result card
                tvResult.setText("✗ ERROR\n\n" + errorMessage);
                resultCard.setVisibility(View.VISIBLE);
                resultCard.setBackgroundColor(Color.parseColor("#FFEBEE"));
                tvResult.setTextColor(Color.parseColor("#B71C1C"));

                tvStatus.setText("✗ Failed: " + errorMessage);
                tvStatus.setBackgroundColor(Color.parseColor("#C62828"));
                tvStatus.setTextColor(Color.WHITE);

                appendLog("ERROR: " + errorMessage, true);

                Toast.makeText(TestSDKActivity.this,
                        "Error: " + errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void abortReading() {
        if (sdk != null) sdk.abort();
        appendLog("Abort requested by user.", false);
        // Do NOT call setBusy(false) here — the SDK will fire onError() from the
        // background thread when it stops, which calls setBusy(false) on the main thread.
        btnAbort.setEnabled(false); // disable Abort to prevent double-tap
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
            tvStatus.setBackgroundColor(Color.parseColor("#FFFFFF"));
            tvStatus.setTextColor(Color.parseColor("#1565C0"));
        }
    }

    private void appendLog(final String message, final boolean isError) {
        runOnUiThread(() -> {
            try {
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                String icon = isError ? "⚠ " : "• ";
                String line = "[" + ts + "] " + icon + message + "\n";

                String current = tvLog.getText().toString();
                String combined = line + current;  // newest at top

                SpannableStringBuilder ssb = new SpannableStringBuilder(combined);
                int color = isError
                        ? Color.parseColor("#C62828")
                        : Color.parseColor("#1A237E");
                ssb.setSpan(new ForegroundColorSpan(color), 0, line.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvLog.setText(ssb);

                scrollLog.post(() -> scrollLog.scrollTo(0, 0));
            } catch (Exception ignored) {}
        });
    }

    private void clearLog() {
        tvLog.setText("");
        progressBar.setProgress(0);
        tvStatus.setText("Ready — connect USB optical cable and press START READING.");
        tvStatus.setBackgroundColor(Color.WHITE);
        tvStatus.setTextColor(Color.parseColor("#1565C0"));
        resultCard.setVisibility(View.GONE);
        checkUsbStatus();
    }
}
