# DLMS METER DATA TO XML CONVERSION - TROUBLESHOOTING GUIDE

## Overview
This document explains the DLMS meter data format, why instantaneous and load profile data may be missing from XML conversions, and how to fix the issue.

---

## 1. DATA FORMAT STRUCTURE

### Input Format (RAW DLMS - TXT files from meter)
```
FORMAT:  CLASS OBIS_CODE ATTRIBUTE DATA_HEX

EXAMPLE:
0001 00002A0000FF 02 091048504c43543035585858303220202020
0003 0100010200FF 02 0a0c07ea0310ff112c2b00014a00
0007 0100630100FF 03 013c020412000809060000010000ff0f02120000...
0007 0100630100FF 07 0600000007
0007 0100630100FF 02 0107023c090c07ea0310ff112c0000014a001203dd...
```

### DLMS Class IDs (What each number means)
| Class | Purpose | Example OBIS |
|-------|---------|--------------|
| 0001 | Nameplate / Meter Identity | 00002A0000FF, 0000600100FF |
| 0003 | Register (Instantaneous) | 0100010200FF (Voltage), 0100020200FF (Current) |
| 0004 | Extended Register | 0100011100FF (Power) |
| 0007 | Profile Generic (Billing/Load Profile) | 0100620100FF (Billing), 0100630100FF (Load Profile) |
| 0008 | Clock/RTC | 0000010000FF |
| 0014 | Activity Calendar | 00000D0000FF |

### OBIS CODE MEANINGS
```
0100010200FF  → Voltage (instantaneous)
0100020200FF  → Current (instantaneous)
0100030700FF  → Power (instantaneous)
0100600100FF  → Meter number (nameplate)
0100620100FF  → Billing Profile (Class 7)
0100630100FF  → Load Profile (Class 7)
0100630200FF  → Daily/Midnight Load Profile
```

### Attribute Mapping
Different attributes retrieve different information:
| Attribute | Meaning | Purpose |
|-----------|---------|---------|
| 02 | Value / Buffer | Actual data |
| 03 | Capture Objects | Column definitions (Load Profile) |
| 04 | Capture Period | Time interval between records |
| 07 | Entries In Use | Number of records in profile |

---

## 2. COMMON ISSUES & ROOT CAUSES

### Issue #1: Missing Instantaneous Data in XML
**Symptoms:**
- XML file has no `<Instantaneous>` section
- OBIS entries like `0100010200FF` are absent

**Root Causes:**
1. **Meter refused instantaneous read** - Check meter logs for ACCESS_ERROR
2. **Timeout during instantaneous phase** - Meter unresponsive
3. **Invalid OBIS codes for your meter make** - Different manufacturer OBIS codes

**Solution:**
```java
// Check logs for these patterns:
INSTANT_DONE elapsed=XXms        // Should complete in <500ms
Inst 1, Inst 2, ... Inst N       // Individual OBIS readings
```

---

### Issue #2: Missing Load Profile Data
**Symptoms:**
- XML `<LoadProfile>` section empty or missing
- No `<ProfileBuffer>` with records
- No capture objects (column definitions)

**Root Causes:**
```
1. Capture Objects (attr=3) not read
   └─ Meter rejected the read
   └─ Using hardcoded fallback (check meter make)

2. Entries In Use (attr=7) not read
   └─ No count of available records

3. Profile Buffer (attr=2) not read OR empty
   └─ No historical data transferred
   └─ Meter has no records
   └─ Session dropped during bulk read

4. Timeout on load profile read
   └─ 360s deadline exceeded
   └─ Profile read abandoned
```

**Diagnosis from Log:**
```
RLS_ENTER lsDays=30 intProfilePd=15        // Load Profile read started
RLS_CALL attr=3 (capture_objects)          // Attempting to read column definitions
RLS_RET attr=3 len=XXX result=0            // Success: len > 100 bytes expected
RLS_CALL attr=4 (capture_period_seconds)   // Attempting to read capture period
RLS_CAPTURE_PERIOD_MIN=15                  // Should be 15, 30, or 60
RLS_ENTRIES_IN_USE=200                     // Number of available records
RLS_BULK_CHECK len=XXXX ok=true            // Bulk read succeeded
RLS_LP_RECORDS: 25 records found           // Actual records transferred
```

---

## 3. DATA VALIDATION CHECKLIST

Before XML conversion, your TXT file should contain:

### For INSTANT mode:
```
✓ Manufacturer header
✓ Meter number header  
✓ At least 5 instantaneous OBIS codes:
  - 0100010200FF (voltage)
  - 0100020200FF (current)
  - 0100030700FF (power)
  - Additional meter-specific OBISs
```

### For BILLING mode:
```
✓ Everything in INSTANT
✓ Billing Profile data:
  - 0100620100FF 03 (capture objects)
  - 0100620100FF 07 (entries count)
  - 0100620100FF 02 (buffer data)
```

### For LOAD_PROFILE mode:
```
✓ Everything in INSTANT
✓ Load Profile data (CRITICAL):
  - 0100630100FF 03 (capture objects/column definitions) ← REQUIRED
  - 0100630100FF 04 (capture period in seconds) ← REQUIRED
  - 0100630100FF 07 (entries in use count) ← REQUIRED
  - 0100630100FF 02 (profile buffer with historical data) ← REQUIRED IF NO BULK ERROR
```

### For COMPLETE mode:
```
✓ Everything in INSTANT
✓ Everything in BILLING
✓ Everything in LOAD_PROFILE
```

---

## 4. HOW TO IDENTIFY THE ISSUE

### Step 1: Check Activity Log
Run a read and examine `NPCL_OPTICAL_LOG.TXT`:

```bash
# Look for these indicators:

# Good signs:
INFO|Downloading Instantaneous data...
INSTANT_DONE elapsed=250ms
INFO|Downloading Load Profile...
RLS_BULK_OK len=8234 declared=200 actualRecords=142
LP_END elapsed=15234ms

# Bad signs:
RLS_ATTR3_HARDCODED make=HPL      # Using fallback, meter rejected
RLS_LP_INCOMPATIBLE — meter rejects LP after reestablish
RLS_SEL_DAY day=-X EMPTY_SKIPPED len=0
RLS_SEL_DEADLINE_HIT at day=15    # Timed out before reading all LP data
```

### Step 2: Validate TXT File Contents
```bash
# Count instantaneous entries:
grep "^0003\|^0004\|^0008" meter_data.TXT | wc -l
# Should be > 10

# Count load profile entries:
grep "^0007.*630100FF" meter_data.TXT | wc -l
# Should be >= 4 (for attr 3, 4, 7, 2)

# Check LP buffer has records:
grep "0100630100FF 02" meter_data.TXT | grep "0212090c"
# Should show many records with pattern "0212090c"
```

### Step 3: Search for Error Indicators
```bash
# Meter connection issues:
grep "DM\|ACCESS_ERROR\|INVALID_PDU" NPCL_OPTICAL_LOG.TXT

# Session drops:
grep "DM_DETECTED\|REESTABLISH" NPCL_OPTICAL_LOG.TXT

# Load profile specific errors:
grep "RLS_" NPCL_OPTICAL_LOG.TXT
```

---

## 5. FIX STRATEGIES

### Fix #1: Ensure Instantaneous Data is Complete (INSTANT mode)
**Code Location:** `Reading.java` - `ReadInstantData()` method

**Verify:**
```java
// Check that ReadInstantData includes all these OBISs:
0100010200FF  // Voltage
0100020200FF  // Current
0100030700FF  // Power
0100800800FF  // Additional phases
0100960800FF  // Additional measurements
// ... meter-specific OBISs
```

**If missing:** Add additional `GetParameter()` calls for missing OBISs

---

### Fix #2: Ensure Load Profile is Complete (LOAD_PROFILE/COMPLETE)
**Code Location:** `Reading.java` - `ReadLoadSurveyData()` method

**Critical Sequence Required:**
```
✓ STEP 1: ReadScalarUnit("BLOCKLOAD")      // Scalar descriptor
✓ STEP 2: GetParameter_LS(...0100630100FF, attr=3)  // Capture objects
✓ STEP 3: GetParameter_LS(...0100630100FF, attr=4)  // Capture period  
✓ STEP 4: GetParameter_LS(...0100630100FF, attr=7)  // Entries in use
✓ STEP 5: GetParameter_LS(...0100630100FF, attr=2)  // Buffer data (bulk)
```

**If bulk read fails:** Falls back to `GetParameterSelective()` for day-by-day reads

---

### Fix #3: Verify Capture Objects (Column Definitions)
**Problem:** XML converter cannot parse load profile without capture objects definition

**Solution:** Ensure the meter is providing attr=3 data:
```java
// In ReadLoadSurveyData():
if (lastGplsResult == 0 && DLMdata != null && !DLMdata.toString().isEmpty()) {
    strbldDLMdata.append("\r\n0007 0100630100FF 03 ").append(DLMdata);
    // ✓ Store capture objects before buffer data
} else {
    // Fallback to hardcoded based on meter make
    String captureObjsHex = getCaptureObjectsForMake();
    strbldDLMdata.append("\r\n0007 0100630100FF 03 ").append(captureObjsHex);
}
```

---

## 6. XML CONVERSION PROCESS

### Using the Built-in Converter:
```java
DLMSToXMLConverter converter = new DLMSToXMLConverter();
String txtData = readTXTFile("meter_data.TXT");
String xmlData = converter.convertToXML(txtData);
saveTo XMLFile("meter_data.xml", xmlData);
```

### Validate Sections Present:
```java
// After conversion, ensure XML contains:
if (xml.contains("<Instantaneous>") &&  
    xml.contains("<LoadProfile>")) {
    // ✓ Conversion likely successful
} else {
    // ✗ Check TXT file - likely missing data source sections
}
```

---

## 7. METER-SPECIFIC NOTES

### HPL Meters:
- May reject `0100630100FF attr=3` (uses hardcoded capture objects)
- Typically support load profile in 30-minute intervals
- Verify OBIS codes in meter's manufacturer documentation

### Secure Meters:
- Support secure meter-specific OBIS: `01005E5B00FF`
- Capture period may be 15 minutes (900 seconds = 0x00000384)
- Verify amendments configuration

### Genus Meters:
- Access tier restrictions - may reject certain reads
- Load profile requires authentication level 1+
- Recommended: Use secure DLMS connection

---

## 8. DEBUGGING STEPS

### For Each Read Mode:
```
1. Check meter connection
   └─ Look for "Meters are communicating properly" in logs
   
2. Verify instantaneous data collected
   └─ Search log for "INSTANT_DONE"
   └─ Check TXT file for 0003/0004/0008 entries
   
3. Verify load profile collected (if applicable)
   └─ Search log for "LP_END" or "RLS_" entries
   └─ Check TXT file for "0100630100FF" entries
   
4. Run validation
   └─ appendLog("VALIDATION_START...") will appear in logs
   └─ Look for VALIDATION_OK vs VALIDATION_WARN/ERROR
   
5. Convert to XML
   └─ Use DLMSToXMLConverter
   └─ Check for "<Warning>" tags indicating missing sections
```

---

## 9. SAMPLE DATA PATTERNS TO LOOK FOR

### Good Load Profile Data in TXT:
```
0007 0100630100FF 03 [200+ bytes] <- Capture objects
0007 0100630100FF 04 06000015180  <- Period: 86400 seconds/day = 0x15180
0007 0100630100FF 07 0600000032  <- 50 records in use (0x32)
0007 0100630100FF 02 [thousands of bytes with 0212090c patterns] <- Records
```

###Bad Load Profile Data in TXT:
```
0007 0100630100FF 03 [only 10 bytes or empty] <- Incomplete/rejected
0007 0100630100FF 02 0B  <- Just error code, no data
# Missing attr=7 and attr=4 entirely
```

---

## 10. NEXT STEPS

1. **Check Your TXT File:** 
   - Verify sections required for your read mode are present

2. **Review Logs:**
   - Search for validation messages to identify missing data

3. **Apply Fixes:**
   - If instantaneous missing: Check `ReadInstantData()`
   - If load profile missing: Check `ReadLoadSurveyData()`

4. **Re-test:**
   - Do another meter read
   - Validate results with DLMSToXMLConverter

5. **XML Conversion:**
   - Use provided `DLMSToXMLConverter.java`
   - Check for `<Warning>` tags in output XML

---

## KEY TAKEAWAY

The XML conversion failures are usually caused by **incomplete data collection at the source**, not the converter. The validation methods added to Reading.java will help identify exactly which data sections are missing so you can fix the meter communication issue.
