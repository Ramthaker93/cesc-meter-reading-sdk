package com.npcl.com.vcpopdl;

/**
 * Utility class for parsing DLMS hex data and extracting meter register values
 */
public class MeterReadingParser {

    /**
     * Parse DLMS encoded kWh value (uint32, big-endian)
     * Expects hex string representing a DLMS integer response
     * Returns value in kWh
     */
    public static String parseKwhValue(String hexData) {
        if (hexData == null || hexData.length() < 8) {
            return "N/A";
        }
        try {
            // DLMS uint32 encoding: may start with type tag (0x06, 0xc6, etc) and length
            // Skip tag and length bytes if present
            String valueHex = hexData;
            
            // If starts with 0x06 (integer tag) or 0xc6, length follows
            if (valueHex.length() >= 10) {
                if (valueHex.startsWith("06") || valueHex.startsWith("c6")) {
                    // Skip first 2 chars (tag) and next 2 chars (length), take 8 chars for uint32
                    int startIdx = 4;
                    if (startIdx + 8 <= valueHex.length()) {
                        valueHex = valueHex.substring(startIdx, startIdx + 8);
                    }
                }
            }
            
            // Take last 8 hex chars if longer
            if (valueHex.length() > 8) {
                valueHex = valueHex.substring(valueHex.length() - 8);
            }
            
            // Convert from big-endian hex to decimal
            long kwhRaw = Long.parseLong(valueHex, 16);
            
            // kWh values are typically in units of 0.001 kWh (1 Wh)
            // So divide by 1000 to get kWh with 3 decimal places
            double kwhValue = kwhRaw / 1000.0;
            
            return String.format("%.3f", kwhValue);
        } catch (Exception e) {
            return "ERR";
        }
    }

    /**
     * Parse DLMS encoded power value (uint32, big-endian)
     * Assumes value is in Watts
     */
    public static String parsePowerValue(String hexData) {
        if (hexData == null || hexData.length() < 8) {
            return "N/A";
        }
        try {
            String valueHex = hexData;
            
            // Skip DLMS tags if present
            if (valueHex.length() >= 10) {
                if (valueHex.startsWith("06") || valueHex.startsWith("c6")) {
                    int startIdx = 4;
                    if (startIdx + 8 <= valueHex.length()) {
                        valueHex = valueHex.substring(startIdx, startIdx + 8);
                    }
                }
            }
            
            if (valueHex.length() > 8) {
                valueHex = valueHex.substring(valueHex.length() - 8);
            }
            
            long powerRaw = Long.parseLong(valueHex, 16);
            
            // Power typically in Watts, divide by 1000 for kW
            double powerValue = powerRaw / 1000.0;
            
            return String.format("%.3f", powerValue);
        } catch (Exception e) {
            return "ERR";
        }
    }

    /**
     * Parse DLMS encoded unsigned integer value (16-bit or 32-bit)
     */
    public static long parseUintValue(String hexData) {
        if (hexData == null || hexData.isEmpty()) {
            return 0;
        }
        try {
            String valueHex = hexData.trim();
            
            // Skip DLMS tags
            if (valueHex.startsWith("06") || valueHex.startsWith("c6")) {
                if (valueHex.length() >= 4) {
                    valueHex = valueHex.substring(4);
                }
            }
            
            // Take up to 8 hex chars for uint32
            if (valueHex.length() > 8) {
                valueHex = valueHex.substring(valueHex.length() - 8);
            }
            
            return Long.parseLong(valueHex, 16);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Format meter reading display string with cumulative and billing sections.
     * todValues is a List of present TOD kWh tariff values (T1..TN, only non-N/A slots).
     * If the meter has 5 TODs only 5 rows show; if it has 8 then 8 rows show.
     */
    public static String formatMeterReadingDisplay(
            String kwhImportCum, String kwhExportCum, String kvamdCum,
            String kwhImportBill, String kwhExportBill, String kvamdbill,
            java.util.List<String> todValues) {

        StringBuilder sb = new StringBuilder();
        sb.append("╔════════════════════════════════════╗\n");
        sb.append("║      METER READING SUMMARY         ║\n");
        sb.append("╠════════════════════════════════════╣\n");
        sb.append("║ CUMULATIVE (Instantaneous)         ║\n");
        sb.append("╟────────────────────────────────────╢\n");
        sb.append(String.format("║ kWh Import     : %-19s║\n", kwhImportCum));
        sb.append(String.format("║ kWh Export     : %-19s║\n", kwhExportCum));
        sb.append(String.format("║ KVA MD         : %-19s║\n", kvamdCum));
        sb.append("╠════════════════════════════════════╣\n");
        sb.append("║ BILLING (Latest Record)            ║\n");
        sb.append("╟────────────────────────────────────╢\n");
        sb.append(String.format("║ kWh Import T0  : %-19s║\n", kwhImportBill));
        sb.append(String.format("║ kWh Export T0  : %-19s║\n", kwhExportBill));
        sb.append(String.format("║ KVA MD T0      : %-19s║\n", kvamdbill));

        // TOD rates — show only the tariff slots that actually exist in this meter
        if (todValues != null && !todValues.isEmpty()) {
            sb.append("╟────────────────────────────────────╢\n");
            sb.append("║ TOD Energy Rates (kWh Import)      ║\n");
            // Show two per row when ≥4 TODs, one per row when fewer
            boolean twoPerRow = todValues.size() >= 4;
            if (twoPerRow) {
                for (int i = 0; i < todValues.size(); i += 2) {
                    String t1 = todValues.get(i);
                    String t2 = (i + 1 < todValues.size()) ? todValues.get(i + 1) : "";
                    String lbl1 = "T" + (i + 1);
                    String lbl2 = (i + 1 < todValues.size()) ? "T" + (i + 2) : "";
                    if (!t2.isEmpty()) {
                        sb.append(String.format("║ %s:%-11s %s:%-10s║\n", lbl1, t1, lbl2, t2));
                    } else {
                        sb.append(String.format("║ %s: %-32s║\n", lbl1, t1));
                    }
                }
            } else {
                for (int i = 0; i < todValues.size(); i++) {
                    sb.append(String.format("║ TOD T%d         : %-19s║\n", i + 1, todValues.get(i)));
                }
            }
        }
        sb.append("╚════════════════════════════════════╝\n");
        return sb.toString();
    }

    /**
     * Backward-compatible overload — kept so old callers still compile.
     * Converts the 6 fixed TOD strings into a list (skipping N/A).
     */
    public static String formatMeterReadingDisplay(
            String kwhImportCum, String kwhExportCum, String kvamdCum,
            String kwhImportBill, String kwhExportBill, String kvamdbill,
            String tod1, String tod2, String tod3, String tod4, String tod5, String tod6) {
        java.util.List<String> tods = new java.util.ArrayList<>();
        for (String t : new String[]{tod1, tod2, tod3, tod4, tod5, tod6})
            if (t != null && !t.equals("N/A") && !t.isEmpty()) tods.add(t);
        return formatMeterReadingDisplay(kwhImportCum, kwhExportCum, kvamdCum,
                kwhImportBill, kwhExportBill, kvamdbill, tods);
    }

    /**
     * Extract hex data for a specific OBIS code from accumulated meter data
     * This is a simple heuristic parser - actual format depends on how GetParameter stores data
     */
    public static String extractObisData(String meterData, String obisCode) {
        if (meterData == null || obisCode == null) {
            return "";
        }
        
        // The data may contain the OBIS code or may just be hex values
        // This is a simple search - more sophisticated parsing may be needed
        // based on actual data format
        
        String searchStr = obisCode.toUpperCase();
        int idx = meterData.indexOf(searchStr);
        
        if (idx < 0) {
            // Try lowercase
            searchStr = obisCode.toLowerCase();
            idx = meterData.indexOf(searchStr);
        }
        
        if (idx >= 0) {
            // Found OBIS code - extract data after it
            int startIdx = idx + obisCode.length();
            // Skip space or delimiter if present
            while (startIdx < meterData.length() && 
                   (meterData.charAt(startIdx) == ' ' || meterData.charAt(startIdx) == '-')) {
                startIdx++;
            }
            // Extract hex data until next space/delimiter or end
            int endIdx = startIdx;
            while (endIdx < meterData.length() && 
                   meterData.charAt(endIdx) != ' ' && meterData.charAt(endIdx) != '\n' && 
                   meterData.charAt(endIdx) != '\r') {
                endIdx++;
            }
            return meterData.substring(startIdx, endIdx);
        }
        
        return "";
    }
}
