# Meter Make TOD Rate Configuration

## Purpose
Document the specific OBIS codes for Time-of-Day (TOD) rates for each meter make used in your application.

## How to Use This File
1. For each meter make (HPL, Secure, Genus), identify the OBIS codes for TOD rates 1-6
2. Update the corresponding section below
3. Once identified, update the `extractMeterReadings()` method in Reading.java

---

## HPL Meter Configuration

**Status:** ⚠️ PENDING IDENTIFICATION  
**Source:** [Your Testing/Specification Document]  
**Tested:** No  
**Date Identified:** (None yet)

```
TOD1 OBIS Code: __________________ (Attribute: __)
TOD2 OBIS Code: __________________ (Attribute: __)
TOD3 OBIS Code: __________________ (Attribute: __)
TOD4 OBIS Code: __________________ (Attribute: __)
TOD5 OBIS Code: __________________ (Attribute: __)
TOD6 OBIS Code: __________________ (Attribute: __)

Helper Properties:
- Data type: uint32 (4 bytes)
- Unit: kWh (divide by 1000)
- Encoding: Big-endian
```

**Notes:**
- HPL is the default/fallback meter make
- Used when manufacturer string matches "HPL"
- Verify against physical meter display

**Testing Results:**
```
Test Date: ___________
Meter Serial: ___________
Expected Values (from meter display):
  TOD1: _____ kWh
  TOD2: _____ kWh
  TOD3: _____ kWh
  TOD4: _____ kWh
  TOD5: _____ kWh
  TOD6: _____ kWh

Actual Values (from application):
  TOD1: _____ kWh
  TOD2: _____ kWh
  TOD3: _____ kWh
  TOD4: _____ kWh
  TOD5: _____ kWh
  TOD6: _____ kWh

Match: Yes / No / Partial
Issues: ___________
```

---

## Secure Meter Configuration

**Status:** ⚠️ PENDING IDENTIFICATION  
**Source:** [Your Testing/Specification Document]  
**Tested:** No  
**Date Identified:** (None yet)

```
TOD1 OBIS Code: __________________ (Attribute: __)
TOD2 OBIS Code: __________________ (Attribute: __)
TOD3 OBIS Code: __________________ (Attribute: __)
TOD4 OBIS Code: __________________ (Attribute: __)
TOD5 OBIS Code: __________________ (Attribute: __)
TOD6 OBIS Code: __________________ (Attribute: __)

Helper Properties:
- Data type: uint32 (4 bytes)
- Unit: kWh (divide by 1000)
- Encoding: Big-endian
- Note: Uses 01005E5B* prefix variants in code
```

**Notes:**
- Detected by checking `isSecureMeter()` method
- May have different OBIS codes than HPL
- Verify against physical meter display

**Testing Results:**
```
Test Date: ___________
Meter Serial: ___________
Expected Values (from meter display):
  TOD1: _____ kWh
  TOD2: _____ kWh
  TOD3: _____ kWh
  TOD4: _____ kWh
  TOD5: _____ kWh
  TOD6: _____ kWh

Actual Values (from application):
  TOD1: _____ kWh
  TOD2: _____ kWh
  TOD3: _____ kWh
  TOD4: _____ kWh
  TOD5: _____ kWh
  TOD6: _____ kWh

Match: Yes / No / Partial
Issues: ___________
```

---

## Genus Meter Configuration

**Status:** ⚠️ PENDING IDENTIFICATION  
**Source:** [Your Testing/Specification Document]  
**Tested:** No  
**Date Identified:** (None yet)

```
TOD1 OBIS Code: __________________ (Attribute: __)
TOD2 OBIS Code: __________________ (Attribute: __)
TOD3 OBIS Code: __________________ (Attribute: __)
TOD4 OBIS Code: __________________ (Attribute: __)
TOD5 OBIS Code: __________________ (Attribute: __)
TOD6 OBIS Code: __________________ (Attribute: __)

Helper Properties:
- Data type: uint32 (4 bytes)
- Unit: kWh (divide by 1000)
- Encoding: Big-endian
- Note: May have access tier restrictions
```

**Notes:**
- May have DLMS access level restrictions
- Verify authentication credentials are correct
- May require different password than other makes

**Testing Results:**
```
Test Date: ___________
Meter Serial: ___________
Expected Values (from meter display):
  TOD1: _____ kWh
  TOD2: _____ kWh
  TOD3: _____ kWh
  TOD4: _____ kWh
  TOD5: _____ kWh
  TOD6: _____ kWh

Actual Values (from application):
  TOD1: _____ kWh
  TOD2: _____ kWh
  TOD3: _____ kWh
  TOD4: _____ kWh
  TOD5: _____ kWh
  TOD6: _____ kWh

Match: Yes / No / Partial
Issues: ___________
```

---

## Billing Data Configuration

For all meter makes, document the billing profile structure:

**Billing Profile OBIS:** 0100620100FF (Class 7)

### Attribute 2 (Buffer) Structure
```
Each billing record contains:

Field 1: _____________________ (OBIS: _____________)
Field 2: _____________________ (OBIS: _____________)
Field 3: _____________________ (OBIS: _____________)
Field 4: _____________________ (OBIS: _____________)
Field 5: _____________________ (OBIS: _____________)
Field 6: _____________________ (OBIS: _____________)
Field 7: _____________________ (OBIS: _____________)
Field 8: _____________________ (OBIS: _____________)
...
```

### Example Buffer Structure (Common)
```
Field 1: DateTime (OBIS: 0000010000FF)
Field 2: kWh Import (OBIS: 0100010800FF)
Field 3: kWh Export (OBIS: 0100020800FF)
Field 4: kVA MD (OBIS: 0100030700FF)
Field 5: TOD Rate 1 (OBIS: ________________)
Field 6: TOD Rate 2 (OBIS: ________________)
... (continue for TOD3-6)
```

---

## How to Identify Missing OBIS Codes

### Step 1: Examine Meter Data File
```
1. Run meter read with your application
2. Locate saved data file: /sdcard/NPCL_OPTICAL_DATA/[MeterNo]_timestamp.txt
3. Open in text editor
4. Look for hex patterns and OBIS codes
```

### Step 2: Contact Manufacturer
- HPL: Support contact/website
- Secure: Support contact/website
- Genus: Support contact/website

### Step 3: DLMS Specification Analysis
- Use IEC 62056-62 standard DLMS registry
- Search for meter model-specific documents
- Look for "OBIS Directory" or "Object List"

### Step 4: Reverse Engineering
```
In meter read data file:
- Find records for known values (e.g., kWh from main display)
- Match the raw hex to expected values
- Deduce OBIS code from pattern/position
- Verify by checking if value changes appropriately
```

---

## Code Integration

Once you complete this configuration:

1. Update `Reading.java` - `extractMeterReadings()` method:
```java
private java.util.Map<String, String> extractMeterReadings(StringBuilder meterData) {
    // ... existing code ...
    
    // Add meter-make specific TOD extraction:
    if (currentMeterMake == MeterMake.HPL) {
        readings.put("tod1", extractAndParse(dataStr, "0100XXXXXX00FF", ...));
        // ... etc
    } else if (currentMeterMake == MeterMake.SECURE) {
        // Secure-specific codes
    } else if (currentMeterMake == MeterMake.GENUS) {
        // Genus-specific codes
    }
}
```

2. For billing data, create a new method:
```java
private java.util.Map<String, String> extractBillingReadings(StringBuilder meterData) {
    // Parse 0100620100FF attr=2 buffer
    // Extract latest record based on EntriesInUse (attr=7)
    // Parse each field according to CaptureObjects structure
    // Return extracted values
}
```

---

## References

### Documentation Files
- `METER_READING_DISPLAY_GUIDE.md` — Feature implementation overview
- `CHEAT_SHEET.txt` — Quick reference guide
- `DLMS_XML_CONVERSION_TROUBLESHOOTING.md` — Data format help

### Code Files  
- `Reading.java` — Main activity, contains extractMeterReadings()
- `MeterReadingParser.java` — Hex parsing utilities

### External Resources
- IEC 62056-62 Standard (DLMS/COSEM)
- Meter manufacturer DLMS specification sheets
- DLMS object browser/analyzer tools

---

## Checklist for Completion

### HPL Meter
- [ ] OBIS codes identified or confirmed
- [ ] Tested with actual meter
- [ ] Values match meter display
- [ ] All 6 TOD rates completed
- [ ] Code updated and tested

### Secure Meter
- [ ] OBIS codes identified or confirmed
- [ ] Tested with actual meter
- [ ] Values match meter display
- [ ] All 6 TOD rates completed
- [ ] Code updated and tested

### Genus Meter
- [ ] OBIS codes identified or confirmed
- [ ] Tested with actual meter
- [ ] Values match meter display
- [ ] All 6 TOD rates completed
- [ ] Code updated and tested

### Billing Data (All Makes)
- [ ] Buffer structure documented
- [ ] Field mappings identified
- [ ] Extraction code working
- [ ] Latest record correctly identified
- [ ] All fields parsing correctly

---

**Document Version:** 1.0  
**Last Updated:** 2026-03-17  
**Status:** EXPECTING USER INPUT  
**Next Review:** After meter testing complete
