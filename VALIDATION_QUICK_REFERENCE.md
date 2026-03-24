# QUICK REFERENCE: XML CONVERSION VALIDATION GUIDE

## Understanding Validation Messages

All validation messages appear in `NPCL_OPTICAL_LOG.TXT` with these patterns:

### 1. Instantaneous Data Validation
```
VALIDATION_OK: Instantaneous data FOUND (V=true, I=true, P=true)
├─ V = Voltage (OBIS 0100010200FF)
├─ I = Current (OBIS 0100020200FF)  
└─ P = Power (OBIS 0100030700FF)

VALIDATION_WARN: Instantaneous data MAY BE MISSING — check meter response
└─ Indicates meter might not be providing measurement data
```

### 2. Load Profile Validation (for LOAD_PROFILE or COMPLETE modes)
```
VALIDATION_LP: CaptureObj=true, Period=true, EntriesInUse=true, Buffer=true
├─ CaptureObj=true         → Column definitions loaded ✓
├─ Period=true             → Read interval known ✓
├─ EntriesInUse=true       → Record count known ✓
└─ Buffer=true             → Historical data present ✓

VALIDATION_CRITICAL: LP Capture Objects (attr=3) MISSING — XML parser will fail
└─ Without capture objects, XML converter cannot understand data structure

VALIDATION_CRITICAL: LP Buffer (attr=2) MISSING — NO HISTORICAL DATA
├─ Meters has no history
├─ Or read was interrupted
└─ Or meter rejected the read
```

### 3. Data Summary
```
METER_DATA_SUMMARY: NamePlate=X Instant=Y Billing=Z LoadProfile=W Events=V TotalSize=Nbytes
├─ NamePlate: Number of nameplate OBIS entries (Class 1)
├─ Instant: Instantaneous measurement entries (Class 3 + 4)
├─ Billing: Billing profile entries (OBIS 0100620100FF)
├─ LoadProfile: Load profile entries (OBIS 0100630100FF)
├─ Events: Event log entries (OBIS 0000630300FF)
└─ TotalSize: Total data downloaded
```

## Decision Tree: Understanding "What's Missing"

```
Does output say "Instantaneous data FOUND"?
├─ NO  → Problem: Meter not responding to instantaneous reads
│        └─ Action: Check meter connectivity, try different timeout settings
│
└─ YES → Check next...

Is it LOAD_PROFILE or COMPLETE mode?
├─ NO (Just INSTANT or BILLING) → Check data summary numbers
│        └─ Should have Instant > 5
│
└─ YES → Check LP validation:

Does "VALIDATION_LP: CaptureObj=true" appear?
├─ NO  → Problem: Meter rejected capture objects read
│        └─ Action: Verify meter make hardcoded capture objects
│
└─ YES → Check next...

Does "VALIDATION_LP: Buffer=true" appear?
├─ NO  → Problem: Load profile buffer not read
│        ├─ Check "RLS_BULK_CHECK ok=true" in log
│        │  └─ If false: Bulk read failed, may have fallen back to daily reads
│        │
│        └─ Check line "VALIDATION_LP_RECORDS: "
│           ├─ If 0 records: Meter has no profile history or read was empty
│           └─ If > 0 records: Data was transferred but not in buffer position
│
└─ YES ✓ Profile data collected

Convert to XML and check result:
├─ <Warning> tags present → Look at message, indicates what data was missing
└─ No <Warning> tags → Conversion successful
```

## Common Scenarios & Solutions

| Scenario | Log Shows | TXT File Has | Problem | Solution |
|----------|-----------|--------------|---------|----------|
| Good instant | `Instant=30, FOUND` | Many 0003/0004 entries | None | Proceed to XML |
| Missing instant | `Instant=0, MAY BE MISSING` | No 0003/0004 entries | Meter no response | Retry/Reset meter |
| Good LP | `CaptureObj=true, Buffer=true, 45 records` | 0100630100FF with 4 attrs | None | Proceed to XML |
| Missing LP capture | `CaptureObj=false` | No `0100630100FF 03` | Meter rejected | Use hardcoded |
| Empty LP buffer | `Buffer=true, 0 records` | LP attrs 3,4,7 but no 0212090c | No history | Normal if new meter |
| LP read timeout | `LP_END, RLS_SEL_DEADLINE_HIT` | Partial LP entries | Time limit | Increase timeout |

## XML Sections After Conversion

After running DLMSToXMLConverter, check that the XML contains all required sections:

### For INSTANT mode:
```xml
✓ <?xml version="1.0" ...?>
✓ <MeterData>
✓   <Header> <Manufacturer>, <MeterNumber>
✓   <Instantaneous> ... <Instantaneous>
✓ </MeterData>
```

### For LOAD_PROFILE mode:
```xml
✓ <?xml version="1.0" ...?>
✓ <MeterData>
✓   <Header>
✓   <Instantaneous>
✓   <LoadProfile>
✓     <CaptureObjects> ... </CaptureObjects>
✓     <CapturePeriod> ... </CapturePeriod>
✓     <EntriesInUse> ... </EntriesInUse>
✓     <ProfileBuffer> ... </ProfileBuffer>
✓   </LoadProfile>
✓ </MeterData>
```

If any section is missing:
1. Search TXT file for expected OBIS codes
2. If not in TXT: Problem is in data collection (Reading.java)
3. If in TXT but not in XML: Problem is in conversion (DLMSToXMLConverter.java)

## Log File Location
- **Android App Log:** `/sdcard/NPCL_OPTICAL_LOG.TXT`
- **Meter Data:** `/sdcard/{MeterNo}_{Timestamp}.TXT`
- **Validation Messages:** Look for `VALIDATION_` prefix in logs

## Running Validation Manually

```java
// In Reading.java onProgressUpdate() or after file save:
StringBuilder meterData = new StringBuilder(/* your data */);
validateMeterDataForXML(meterData, "LOAD_PROFILE");  // or "INSTANT", "BILLING"
logMeterDataSummary(meterData);

// Results appear in NPCL_OPTICAL_LOG.TXT
```

## Performance Indicators

Good read times:
- **Instantaneous:** 200-500ms
- **Billing:** 1-3 seconds  
- **LoadProfile (30 days):** 15-60 seconds
- **Complete:** 20-80 seconds

If times exceed these significantly, check:
- USB cable quality
- Meter timeout settings
- Network interference (for wireless meters)
- Meter capacity/age
