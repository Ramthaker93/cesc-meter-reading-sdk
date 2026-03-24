package com.npcl.com.vcpopdl;

import android.app.Activity;
import android.os.AsyncTask;
import android.util.Log;
import java.io.*;

/**
 * =========================================================================
 * ANDROID INTEGRATION: Using DLMSToXMLConverter in Reading Activity
 * =========================================================================
 * 
 * This class demonstrates how to integrate the XML converter into
 * the Reading activity to automatically convert meter data to XML
 * right after reading is complete.
 * 
 * =========================================================================
 */
public class XMLConversionTask extends AsyncTask<String, String, Boolean> {

    private static final String TAG = "XMLConversion";
    private Activity activity;
    private String meterNumber;
    private String filePath;

    /**
     * Initialize conversion task
     * @param activity The hosting activity
     * @param meterNumber Meter identification
     * @param dataFilePath Path to TXT data file to convert
     */
    public XMLConversionTask(Activity activity, String meterNumber, String dataFilePath) {
        this.activity = activity;
        this.meterNumber = meterNumber;
        this.filePath = dataFilePath;
    }

    @Override
    protected Boolean doInBackground(String... params) {
        try {
            // Read the TXT data file
            File inputFile = new File(filePath);
            if (!inputFile.exists()) {
                publishProgress("ERROR|Input file not found: " + filePath);
                return false;
            }

            // Read file contents
            StringBuilder txtData = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(inputFile));
            String line;
            while ((line = reader.readLine()) != null) {
                txtData.append(line).append("\n");
            }
            reader.close();

            publishProgress("INFO|Converting " + filePath + " to XML...");

            // Convert to XML
            DLMSToXMLConverter converter = new DLMSToXMLConverter();
            String xmlData = converter.convertToXML(txtData.toString());

            // Generate output filename
            String outputPath = filePath.replace(".TXT", ".xml")
                                       .replace(".txt", ".xml");

            // Write XML to file
            File outputFile = new File(outputPath);
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));
            writer.write(xmlData);
            writer.close();

            publishProgress("INFO|XML conversion complete: " + outputFile.getName());
            Log.d(TAG, "Conversion successful: " + outputPath);

            return true;

        } catch (Exception ex) {
            publishProgress("ERROR|Conversion failed: " + ex.getMessage());
            Log.e(TAG, "Conversion error: " + ex.getMessage(), ex);
            return false;
        }
    }

    @Override
    protected void onProgressUpdate(String... values) {
        if (values.length > 0) {
            String msg = values[0];
            Log.i(TAG, msg);
            // Optionally update UI with progress message
            // if (progressCallback != null) {
            //     progressCallback.onProgress(msg);
            // }
        }
    }

    @Override
    protected void onPostExecute(Boolean success) {
        if (success) {
            Log.i(TAG, "XML conversion task completed successfully");
        } else {
            Log.e(TAG, "XML conversion task failed");
        }
    }
}


/**
 * =========================================================================
 * HOW TO INTEGRATE INTO Reading.java
 * =========================================================================
 */
public class ReadingXMLIntegrationExample {

    /**
     * Call this method after meter data is saved but before database update
     * (in AsyncTaskRunner.doInBackground)
     */
    public void integrateXMLConversion() {

        // STEP 1: In Reading.java, after MakeDataFile() call (around line 1002):

        /*
        if (MeterData.length() > 20) {
            Filenm = MakeDataFile(DataFileName, fileHeader + MeterData.toString());
        }
        
        // NEW: Validate data
        validateMeterDataForXML(MeterData, readMode);
        logMeterDataSummary(MeterData);
        
        // NEW: Try to convert to XML
        if (Filenm != null && !Filenm.isEmpty()) {
            String basePath = getExternalMediaDirs()[0] + "/" + Filenm;
            XMLConversionTask xmlTask = new XMLConversionTask(this, NPCLMeterno, basePath);
            try {
                xmlTask.execute(); // Async conversion
                appendLog("XMLConversionTask started for " + Filenm);
            } catch (Exception ex) {
                appendLog("XMLConversion error: " + ex.getMessage());
            }
        }
        */

        // STEP 2: Or use synchronous conversion in a background thread:

        /*
        // In onPostExecute() of AsyncTaskRunner, after showing success:
        new Thread(() -> {
            try {
                String txtFile = getExternalMediaDirs()[0] + "/" + Filenm;
                BufferedReader reader = new BufferedReader(new FileReader(txtFile));
                StringBuilder txtData = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    txtData.append(line).append("\n");
                }
                reader.close();

                DLMSToXMLConverter converter = new DLMSToXMLConverter();
                String xmlData = converter.convertToXML(txtData.toString());

                String xmlFile = txtFile.replace(".TXT", ".xml");
                BufferedWriter writer = new BufferedWriter(new FileWriter(xmlFile));
                writer.write(xmlData);
                writer.close();

                appendLog("XML conversion complete: " + xmlFile);
            } catch (Exception ex) {
                appendLog("XML conversion failed: " + ex.getMessage());
            }
        }).start();
        */
    }

    /**
     * Check if XML conversion was successful
     */
    public boolean validateXMLFile(String xmlFilePath) {
        try {
            File xmlFile = new File(xmlFilePath);
            if (!xmlFile.exists()) {
                Log.e(TAG, "XML file not found: " + xmlFilePath);
                return false;
            }

            // Check file size
            if (xmlFile.length() < 100) {
                Log.w(TAG, "XML file appears empty or too small");
                return false;
            }

            // Read and check content
            BufferedReader reader = new BufferedReader(new FileReader(xmlFile));
            String firstLine = reader.readLine();
            reader.close();

            if (firstLine != null && firstLine.contains("<?xml")) {
                Log.i(TAG, "XML file structure is valid");
                return true;
            } else {
                Log.w(TAG, "XML file missing XML declaration");
                return false;
            }

        } catch (Exception ex) {
            Log.e(TAG, "XML validation error: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Extract warnings from XML file
     */
    public String[] checkXMLWarnings(String xmlFilePath) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(xmlFilePath));
            StringBuilder warnings = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.contains("<Warning>")) {
                    // Extract warning message
                    int start = line.indexOf("<Warning>") + 9;
                    int end = line.indexOf("</Warning>");
                    if (end > start) {
                        warnings.append(line.substring(start, end)).append("\n");
                    }
                }
            }
            reader.close();

            if (warnings.length() > 0) {
                Log.w(TAG, "XML warnings found:\n" + warnings.toString());
                return warnings.toString().split("\n");
            }
            return new String[0];

        } catch (Exception ex) {
            Log.e(TAG, "Warning extraction error: " + ex.getMessage());
            return new String[]{ex.getMessage()};
        }
    }
}


/**
 * =========================================================================
 * USAGE EXAMPLE: Complete Integration
 * =========================================================================
 */
public class IntegrationExample {

    /*
    // In Reading.java AsyncTaskRunner.doInBackground():

    protected String doInBackground(String... params) {
        // ... existing code ...

        try {
            // ... meter read code ...

            MeterData.append(ReadInstantData(port));
            MeterData.append(ReadLoadSurveyData(port, lsDays));

            // Data validation
            validateMeterDataForXML(MeterData, readMode);
            logMeterDataSummary(MeterData);

            // Save TXT file
            String cleanMeterNo = MeterNo.replace("\r\n","").replace("\n","").trim();
            String fileTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss")
                                    .format(new Date());
            String DataFileName = cleanMeterNo + "_" + fileTimestamp;
            String Filenm = MakeDataFile(DataFileName, fileHeader + MeterData.toString());

            if (!Filenm.isEmpty()) {
                // Convert to XML
                try {
                    String txtPath = getExternalMediaDirs()[0] + "/" + Filenm;
                    BufferedReader reader = new BufferedReader(new FileReader(txtPath));
                    StringBuilder txtData = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        txtData.append(line).append("\n");
                    }
                    reader.close();

                    // Perform conversion
                    DLMSToXMLConverter converter = new DLMSToXMLConverter();
                    String xmlData = converter.convertToXML(txtData.toString());

                    // Save XML
                    String xmlPath = txtPath.replace(".TXT", ".xml");
                    BufferedWriter writer = new BufferedWriter(new FileWriter(xmlPath));
                    writer.write(xmlData);
                    writer.close();

                    appendLog("XML conversion complete: " + xmlPath);
                    publishProgress("INFO|XML file created: " + xmlPath, "95");

                } catch (Exception xmlEx) {
                    appendLog("XMLConversion error: " + xmlEx.getMessage());
                    publishProgress("WARN|XML conversion failed: " + xmlEx.getMessage(), "95");
                }
            }

            // Continue with database inserts, etc...
            // ...

        } catch (Exception ex) {
            publishProgress("ERROR|" + ex.getMessage(), "0");
            return "Error: " + ex.getMessage();
        }
    }
    */
}


/**
 * =========================================================================
 * OPTIONAL: Add Menu Item for Manual XML Conversion
 * =========================================================================
 * 
 * If you want users to manually convert existing TXT files to XML:
 */
public class ManualXMLConversionMenu {

    /*
    // Add to Reading.java menu_reading.xml or activity menu:

    <item
        android:id="@+id/menu_convert_xml"
        android:title="Convert to XML"
        android:icon="@drawable/ic_convert"
        app:showAsAction="ifRoom" />

    // Handle in onOptionsItemSelected():

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_convert_xml) {
            showXMLFileSelectionDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Implementation:

    private void showXMLFileSelectionDialog() {
        File mediaDir = getExternalMediaDirs()[0];
        File[] files = mediaDir.listFiles((dir, name) -> name.endsWith(".TXT"));

        if (files == null || files.length == 0) {
            Toast.makeText(this, "No TXT files found", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] fileNames = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            fileNames[i] = files[i].getName();
        }

        new AlertDialog.Builder(this)
            .setTitle("Select file to convert")
            .setItems(fileNames, (dialog, which) -> {
                convertTXTToXML(files[which]);
            })
            .show();
    }

    private void convertTXTToXML(File txtFile) {
        new Thread(() -> {
            try {
                // Read TXT
                BufferedReader reader = new BufferedReader(new FileReader(txtFile));
                StringBuilder txtData = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    txtData.append(line).append("\n");
                }
                reader.close();

                // Convert
                DLMSToXMLConverter converter = new DLMSToXMLConverter();
                String xmlData = converter.convertToXML(txtData.toString());

                // Save
                String xmlPath = txtFile.getAbsolutePath()
                                       .replace(".TXT", ".xml");
                BufferedWriter writer = new BufferedWriter(new FileWriter(xmlPath));
                writer.write(xmlData);
                writer.close();

                // Notify
                runOnUiThread(() -> {
                    Toast.makeText(Reading.this,
                        "Converted: " + new File(xmlPath).getName(),
                        Toast.LENGTH_SHORT).show();
                });

            } catch (Exception ex) {
                runOnUiThread(() -> {
                    Toast.makeText(Reading.this,
                        "Conversion failed: " + ex.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    */
}
