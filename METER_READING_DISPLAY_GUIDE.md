# Meter Reading Display Feature — Implementation Guide

## Overview
This feature displays key meter register values immediately after a successful meter read completion. It shows:
- **Cumulative values** (from instantaneous reading): kWh Import, kWh Export, KVA MD
- **Billing values** (from latest billing record): kWh Import, kWh Export, KVA MD, TOD rates 1-6

## Architecture

### New Files Added
1. **MeterReadingParser.java** — Utility class for DLMS hex decoding:
   - `parseKwhValue()` — Converts hex to kWh decimal
   - `parsePowerValue()` — Converts hex to kW/kVA decimal
   - `formatMeterReadingDisplay()` — Formats display output
   - `extractObisData()` — Parses hex data from meter data string

2. **Reading.java modifications**:
   - `extractMeterReadings()` — Extracts specific OBIS values from accumulated meter data
   - `showMeterReadingDialog()` — Creates and displays AlertDialog with readings
   - `lastMeterData` — Class variable to store MeterData for onPostExecute access

### Data Flow
```
ReadInstantData() + ReadBillingData()
    ↓
AsyncTask.doInBackground() accumulates into MeterData StringBuilder
    ↓
MeterData saved to file & copied to lastMeterData instance variable
    ↓
onPostExecute() called on success
    ↓
extractMeterReadings(lastMeterData) parses hex → decimal values
    ↓
showMeterReadingDialog() displays AlertDialog with formatted readings
```

## Currently Implemented Values

### Cumulative Section (from instantaneous read)
| Value | OBIS Code | Attribute | Status | Notes |
|-------|-----------|-----------|--------|-------|
| kWh Import | 0100010800FF | 2 | ✅ Implemented | uint32, divide by 1000 for kWh |
| kWh Export | 0100020800FF | 2 | ✅ Implemented | uint32, divide by 1000 for kWh |
| KVA MD | 0100030700FF | varies | ⚠️ Placeholder | Power apparent values |

### Billing Section (from latest record in 0100620100FF)
| Value | Source | Status | Notes |
|-------|--------|--------|-------|
| kWh Import | Billing Profile (Class 7) | ⚠️ Placeholder | Requires parsing 0100620100FF attr=2 buffer |
| kWh Export | Billing Profile (Class 7) | ⚠️ Placeholder | Requires parsing 0100620100FF attr=2 buffer |
| KVA MD | Billing Profile (Class 7) | ⚠️ Placeholder | Requires parsing 0100620100FF attr=2 buffer |

### TOD Rates (1-6)
| TOD Rate | Status | Action Required |
|----------|--------|-----------------|
| TOD1-TOD6 | ⚠️ Placeholder | **IDENTIFY OBIS CODES FOR YOUR METER MAKES** |

## TOD Rate OBIS Code Identification

**CRITICAL STEP:** You must identify the correct OBIS codes for the 6 Time-of-Day rate registers for each meter make your application supports.

### Meter Makesconfirmed in code:
1. **HPL** (default meter make)
2. **Secure** (uses 01005E5B* variants)
3. **Genus** (access tier restricted)

### How to Find TOD Rate OBIS Codes

#### Option 1: Check Meter Specifications
- Contact the meter manufacturer (HPL, Secure, Genus)
- Request DLMS object list (IEC 62056-62 compliant)
- Look for OBIS codes describing "Energy Rate 1/2/3/4/5/6" or "Time of Use 1/2/3/4/5/6"

#### Option 2: Examine Your Existing Data Files
1. Run a meter read and save the data file
2. Open the saved TXT file (e.g., `MTR_20260317_xxx.txt`)
3. Look at the raw DLMS hex responses
4. Map OBIS codes to register names using a DLMS analyzer

#### Option 3: DLMS Common Registry
Standard OBIS codes for TOD rates (may vary by meter):
- **0100010000FF** - Energy Rates (generic)
- **0100020000FF** - Energy Rates (generic)
- **0100030000FF** - Energy Rates (generic)
- **0100040000FF** through **0100090000FF** - Additional rates
- **01008C0700FF** - Energy Rate 1
- **01008D0700FF** - Energy Rate 2
- **01008E0700FF** - Energy Rate 3
- **01008F0700FF** - Energy Rate 4
- **0100A00700FF** - Energy Rate 5
- **0100A10700FF** - Energy Rate 6

**Note:** These are examples. Your meters may use different codes.

### Implementation Steps

Once you identify the TOD rate OBIS codes for each meter make:

1. **Update `extractMeterReadings()` method in Reading.java:**
```java
// Replace the TODO sections with actual OBIS codes
String tod1 = extractAndParse(dataStr, "TOD1_OBIS_CODE_HERE", MeterReadingParser::parseKwhValue);
String tod2 = extractAndParse(dataStr, "TOD2_OBIS_CODE_HERE", MeterReadingParser::parseKwhValue);
// ...and so on for TOD3-TOD6
```

2. **For Billing Data Extraction:**
- The Billing Profile (0100620100FF, Class 7) contains a buffer with multiple records
- Attribute 2 = Buffer (array of entries)
- Attribute 7 = Entries In Use (count)
- The "latest" billing record is typically the last entry or entry at index [entries_in_use - 1]
- Parse the buffer according to DLMS: Entry structure depends on "Capture Objects" (attr=3)

3. **Test with Each Meter Make:**
- Test with HPL meter and record actual OBIS responses
- Test with Secure meter and compare
- Test with Genus meter if available
- Compare against meter display

## DLMS Hex Decoding Details

### kWh Values (uint32)
- **Encoding:** 4-byte big-endian integer
- **Unit:** Typically 1Wh (0.001 kWh)
- **Decimal:** Divide by 1000
- **Example hex:** `00001234` = 4660 Wh = 4.660 kWh

### Power Values (uint32 or uint16)
- **Encoding:** 2 or 4 bytes, big-endian
- **Unit:** Typically Watts or VA
- **Example hex:** `0BB8` = 3000 W = 3 kW

### DLMS Tags in Hex Response
Common tags you might see:
- `06` — Integer (1-4 bytes)
- `09` — Octet string
- `02` — Boolean
- `0A` — Null
- Next byte after tag = length

Example DLMS response:
```
06 04 00001234     ← Tag=06 (int), Length=04, Value=00001234
```

## Troubleshooting

### Dialog Not Appearing
1. Check that `result` doesn't start with "Error" (successful read required)
2. Verify `lastMeterData` contains data (check logs)
3. Confirm NPCLMeterno is set correctly

### Values Showing "N/A" or "ERR"
1. OBIS code not found in meter data:
   - Verify OBIS code matches your meter specification
   - Check if meter actually reads that register
   - Review logs: "DEBUG: OBIS XXXX not found"

2. Hex parsing error:
   - Check hex data format in raw file
   - Verify byte count matches data type (8 hex chars = 4 bytes for uint32)
   - Look for "ERROR parsing OBIS XXXX" in logs

### Billing Data Not Extracted
- Current implementation has TODOs — requires code completion
- Check that ReadBillingData() is being called (depends on reading mode)
- Verify billing profile (0100620100FF) exists in meter

## Billing Profile Parsing (Advanced)

The Billing Profile (0100620100FF) structure:
```
Attribute 1: LogicalName (identifier)
Attribute 2: Buffer (array of profile records) ← PARSE THIS
Attribute 3: CaptureObjects (defines record structure)
Attribute 4: CapturePeriod (seconds between records)
Attribute 5: SortMethod
Attribute 6: SortParameters
Attribute 7: EntriesInUse (count of actual records)
Attribute 8: ProfileEntryCount (max capacity)
```

To extract "latest" billing record:
1. Read EntriesInUse (attr=7) to get count, e.g., `0x5` = 5 records
2. Read Buffer (attr=2) which contains all records
3. Parse the last record (index = EntriesInUse - 1)
4. Each record structure defined by CaptureObjects (attr=3)
5. For standard meters: record = [Time, kWh_import, kWh_export, KVA_MD, TOD1, TOD2, TOD3, TOD4, TOD5, TOD6, ...]

## Next Steps

1. **IMMEDIATE:**
   - [ ] Identify TOD rate OBIS codes for each supported meter make
   - [ ] Gather meter specifications or test with actual meters
   - [ ] Document findings in a meter-specific configuration

2. **IMPLEMENTATION:**
   - [ ] Update `extractMeterReadings()` with correct OBIS codes
   - [ ] Implement billing data extraction from 0100620100FF buffer
   - [ ] Add TOD rate parsing with correct OBIS for each meter make
   - [ ] Update `MeterReadingParser` if additional data types needed

3. **TESTING:**
   - [ ] Test with HPL meter
   - [ ] Test with Secure meter
   - [ ] Test with Genus meter
   - [ ] Verify decimal values match meter display
   - [ ] Confirm all TOD rates display correctly

4. **REFINEMENT:**
   - [ ] Add error handling for missing registers
   - [ ] Add configuration for meter-specific OBIS codes
   - [ ] Consider saving readings to database
   - [ ] Add "Share" option to export readings

## Code References

### Key Files
- **MeterReadingParser.java** — Utility methods (lines ~50-150)
- **Reading.java**:
  - `showMeterReadingDialog()` — lines ~1450-1500
  - `extractMeterReadings()` — lines ~1510-1570
  - `extractAndParse()` helper — lines ~1575-1590
  - `AsyncTaskRunner.lastMeterData` — lines ~695
  - `onPostExecute()` modifications — lines ~1170-1210

### Related Methods
- `GetParameter()` — Issues DLMS GetRequest for specific OBIS
- `ReadInstantData()` — Collects instantaneous values (line ~5850)
- `ReadBillingData()` — Collects billing profile (line ~4955)

## Support

For issues with meter reading display:
1. Check `NPCL_OPTICAL_LOG.TXT` for "DEBUG:" and "ERROR:" lines
2. Review data file raw hex to understand format
3. Compare against meter manual for expected values
4. Verify OBIS codes and attribute numbers match specification

---
**Last Updated:** 2026-03-17  
**Feature Status:** PARTIAL IMPLEMENTATION  
**Release Version:** 1.0 (Cumulative values only)  
**Next Version:** 2.0 (Complete with billing and TOD rates)
