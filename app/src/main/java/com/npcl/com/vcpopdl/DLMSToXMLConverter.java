package com.npcl.com.vcpopdl;

import java.io.*;
import java.util.*;
import java.text.SimpleDateFormat;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/**
 * =========================================================================
 * DLMS TEXT DATA TO XML CONVERTER
 * =========================================================================
 * 
 * Purpose:
 *   Converts raw DLMS meter data (TXT format) to structured XML format
 *   Support for Instantaneous, Billing, and Load Profile data
 * 
 * Input Format (TXT):
 *   CLASS OBIS ATTRIBUTE DATA
 *   0001 0000600100FF 02 091048504c43543035585858...
 *   0007 0100630100FF 03 013c020412000809060000...
 * 
 * Output Format (XML):
 *   <MeterData>
 *     <Meter>...</Meter>
 *     <Instantaneous>...</Instantaneous>
 *     <Billing>...</Billing>
 *     <LoadProfile>...</LoadProfile>
 *   </MeterData>
 * 
 * =========================================================================
 */
public class DLMSToXMLConverter {

    private String manufacturerName = "";
    private String meterNumber = "";
    private StringBuilder xmlOutput;
    private int indentLevel = 0;

    public DLMSToXMLConverter() {
        xmlOutput = new StringBuilder();
    }

    /**
     * Convert DLMS TXT data to XML
     * @param txtData Raw DLMS data in text format
     * @return XML string
     */
    public String convertToXML(String txtData) {
        xmlOutput = new StringBuilder();
        indentLevel = 0;

        if (txtData == null || txtData.isEmpty()) {
            appendXML("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
            appendXML("<MeterData><Error>No input data</Error></MeterData>");
            return xmlOutput.toString();
        }

        appendXML("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        appendXML("<MeterData createdAt=\"" + getCurrentTimestamp() + "\">");
        indentLevel++;

        // Parse header information
        parseHeader(txtData);

        // Parse sections
        parseInstantaneousData(txtData);
        parseBillingData(txtData);
        parseLoadProfileData(txtData);
        parseEventData(txtData);

        indentLevel--;
        appendXML("</MeterData>");

        return xmlOutput.toString();
    }

    /**
     * Parse header information (manufacturer, meter number)
     */
    private void parseHeader(String txtData) {
        String[] lines = txtData.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("MANUFACTURER=")) {
                manufacturerName = line.substring("MANUFACTURER=".length());
            } else if (line.startsWith("METERNO=")) {
                meterNumber = line.substring("METERNO=".length());
            }
        }

        if (!manufacturerName.isEmpty() || !meterNumber.isEmpty()) {
            appendXML("<Header>");
            indentLevel++;
            if (!manufacturerName.isEmpty())
                appendXML("<Manufacturer>" + escapeXML(manufacturerName) + "</Manufacturer>");
            if (!meterNumber.isEmpty())
                appendXML("<MeterNumber>" + escapeXML(meterNumber) + "</MeterNumber>");
            indentLevel--;
            appendXML("</Header>");
        }
    }

    /**
     * Parse instantaneous data sections
     * Class 1: Nameplate/Meter Identity
     * Class 3: Register
     * Class 4: Extended Register
     */
    private void parseInstantaneousData(String txtData) {
        appendXML("<Instantaneous>");
        indentLevel++;

        String[] lines = txtData.split("\n");
        boolean hasInstantData = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            DLMSRecord record = parseDLMSLine(line);
            if (record == null) continue;

            // Instantaneous data indicators
            if ((record.classId == 1 ||
                 record.classId == 3 ||
                 record.classId == 4 ||
                 record.classId == 8) &&
                record.attribute != null &&
                !record.obisCode.contains("630100FF") &&  // Exclude Load Profile
                !record.obisCode.contains("620100FF")) {  // Exclude Billing Profile

                if (!hasInstantData) {
                    hasInstantData = true;
                }

                appendXML("<!-- OBIS: " + record.obisCode + " Attr: " + record.attribute +
                         " Class: " + record.classId + " -->");
                addDataNode(record.obisCode, record.attribute, record.dataHex);
            }
        }

        if (!hasInstantData) {
            appendXML("<!-- WARNING: No instantaneous data found in input -->");
            appendXML("<Warning>No instantaneous data detected. Check meter response.</Warning>");
        }

        indentLevel--;
        appendXML("</Instantaneous>");
    }

    /**
     * Parse billing profile data
     * Class 7, OBIS 0100620100FF
     */
    private void parseBillingData(String txtData) {
        appendXML("<Billing>");
        indentLevel++;

        String[] lines = txtData.split("\n");
        boolean hasBillingData = false;

        // Look for billing profile (class 7, OBIS 0100620100FF)
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            DLMSRecord record = parseDLMSLine(line);
            if (record == null) continue;

            if (record.classId == 7 && record.obisCode.contains("620100FF")) {
                hasBillingData = true;

                appendXML("<!-- Billing Profile OBIS " + record.obisCode +
                         " Attribute " + record.attribute + " -->");

                if ("3".equals(record.attribute)) {
                    appendXML("<CaptureObjects>" +
                             decodeCaptureObjects(record.dataHex) +
                             "</CaptureObjects>");
                } else if ("2".equals(record.attribute)) {
                    appendXML("<Buffer><!-- " + record.dataHex.length() + " bytes --></Buffer>");
                } else if ("7".equals(record.attribute)) {
                    try {
                        long entries = Long.parseLong(record.dataHex.substring(
                                record.dataHex.length() - 8), 16);
                        appendXML("<EntriesInUse>" + entries + "</EntriesInUse>");
                    } catch (Exception e) {
                        appendXML("<EntriesInUse>0</EntriesInUse>");
                    }
                }
            }
        }

        if (!hasBillingData) {
            appendXML("<!-- No billing profile data found -->");
        }

        indentLevel--;
        appendXML("</Billing>");
    }

    /**
     * Parse load profile data
     * Class 7, OBIS 0100630100FF and 0100630200FF
     */
    private void parseLoadProfileData(String txtData) {
        appendXML("<LoadProfile>");
        indentLevel++;

        String[] lines = txtData.split("\n");
        boolean hasLPData = false;

        // Parse Load Profile structures
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            DLMSRecord record = parseDLMSLine(line);
            if (record == null) continue;

            if (record.classId == 7 && record.obisCode.contains("630100FF")) {
                hasLPData = true;

                appendXML("<!-- Load Profile OBIS " + record.obisCode +
                         " Attribute " + record.attribute + " -->");

                if ("3".equals(record.attribute)) {
                    // Capture Objects - Column Definitions
                    appendXML("<CaptureObjects>");
                    indentLevel++;
                    appendXML(decodeCaptureObjects(record.dataHex));
                    indentLevel--;
                    appendXML("</CaptureObjects>");
                } else if ("4".equals(record.attribute)) {
                    // Capture Period
                    try {
                        long period = Long.parseLong(record.dataHex.substring(
                                record.dataHex.length() - 8), 16);
                        appendXML("<CapturePeriod unit=\"seconds\">" + period +
                                 "</CapturePeriod>");
                    } catch (Exception e) {
                        appendXML("<CapturePeriod>Unknown</CapturePeriod>");
                    }
                } else if ("7".equals(record.attribute)) {
                    // Entries In Use
                    try {
                        long entries = Long.parseLong(record.dataHex.substring(
                                record.dataHex.length() - 8), 16);
                        appendXML("<EntriesInUse>" + entries + "</EntriesInUse>");
                    } catch (Exception e) {
                        appendXML("<EntriesInUse>0</EntriesInUse>");
                    }
                } else if ("2".equals(record.attribute)) {
                    // Profile Buffer Data
                    int recordCount = countRecords(record.dataHex);
                    appendXML("<ProfileBuffer recordCount=\"" + recordCount + "\">");
                    indentLevel++;
                    appendXML("<!-- " + record.dataHex.length() +
                             " bytes of encoded profile data -->");
                    indentLevel--;
                    appendXML("</ProfileBuffer>");
                }
            }
        }

        if (!hasLPData) {
            appendXML("<!-- WARNING: No load profile data found in input -->");
            appendXML("<Warning>No load profile detected. Check meter response for OBIS 0100630100FF.</Warning>");
        }

        indentLevel--;
        appendXML("</LoadProfile>");
    }

    /**
     * Parse event log data
     * Class 7, OBIS 0000630300FF
     */
    private void parseEventData(String txtData) {
        String[] lines = txtData.split("\n");
        boolean hasEventData = false;

        for (String line : lines) {
            if (line.contains("0000630300FF")) {
                hasEventData = true;
                break;
            }
        }

        if (hasEventData) {
            appendXML("<Events>");
            indentLevel++;
            appendXML("<!-- Event log data present -->");
            indentLevel--;
            appendXML("</Events>");
        }
    }

    /**
     * Parse a single DLMS line
     * Format: CLASS OBIS ATTRIBUTE DATA
     */
    private DLMSRecord parseDLMSLine(String line) {
        String[] parts = line.split("\\s+", 4);

        if (parts.length < 4) return null;

        try {
            DLMSRecord record = new DLMSRecord();
            record.classId = Integer.parseInt(parts[0], 16);
            record.obisCode = parts[1];
            record.attribute = parts[2];
            record.dataHex = parts[3];
            return record;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decode capture objects array
     */
    private String decodeCaptureObjects(String dataHex) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(indent()).append("<!-- Array of object references (capture_objects) -->\n");
        sb.append(indent()).append("<Objects hexData=\"").append(dataHex).append("\" />\n");
        return sb.toString();
    }

    /**
     * Count profile records in buffer
     */
    private int countRecords(String dataHex) {
        int count = 0;
        int idx = 0;
        // Look for structure pattern (0212090c = struct tag + timestamp tag)
        while ((idx = dataHex.indexOf("0212090c", idx)) >= 0) {
            count++;
            idx += 8;
        }
        return count;
    }

    /**
     * Add a data node with value
     */
    private void addDataNode(String obisCode, String attribute, String dataHex) {
        String nodeName = "Data_" + obisCode.replace("FF", "").replace(".", "_");
        appendXML("<" + nodeName + " attr=\"" + attribute + "\" hex=\"" +
                 dataHex + "\" />");
    }

    /**
     * Escape special XML characters
     */
    private String escapeXML(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    /**
     * Get current timestamp in ISO format
     */
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        return sdf.format(new Date());
    }

    /**
     * Append XML with proper indentation
     */
    private void appendXML(String text) {
        xmlOutput.append(indent()).append(text).append("\n");
    }

    /**
     * Get indentation string
     */
    private String indent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indentLevel * 2; i++) {
            sb.append(" ");
        }
        return sb.toString();
    }

    /**
     * Inner class to represent a DLMS data record
     */
    private static class DLMSRecord {
        int classId;
        String obisCode;
        String attribute;
        String dataHex;
    }

    /**
     * Main method for testing
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java DLMSToXMLConverter <input.txt> [output.xml]");
            return;
        }

        try {
            // Read input TXT file
            BufferedReader reader = new BufferedReader(new FileReader(args[0]));
            StringBuilder txtData = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                txtData.append(line).append("\n");
            }
            reader.close();

            // Convert to XML
            DLMSToXMLConverter converter = new DLMSToXMLConverter();
            String xml = converter.convertToXML(txtData.toString());

            // Write output
            String outputFile = args.length > 1 ? args[1] :
                               args[0].replace(".TXT", ".xml").replace(".txt", ".xml");
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));
            writer.write(xml);
            writer.close();

            System.out.println("Conversion complete: " + outputFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
