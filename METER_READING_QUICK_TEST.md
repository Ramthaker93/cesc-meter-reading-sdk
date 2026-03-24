# Quick Start: Testing Meter Reading Display Feature

## What Happens Now

After a successful meter read, your app will:
1. Display the usual success Toast message
2. **NEW:** Show an AlertDialog with a "Meter Reading Summary"
3. Display extracted register values in two sections:
   - **Cumulative** — Instantaneous data (kWh Import, Export, KVA MD)
   - **Billing** — Latest billing record values + TOD rates

## How to Test

### Test 1: Run Instantaneous Read
```
1. Open OpticalReading app
2. Meter Make: Select HPL (or your default)
3. Reading Type: Select "INSTANTANEOUS"
4. Meter No: Optional (will read from meter)
5. Tap "Read with Theft"
6. Wait for completion message
```

**Expected Result:**
- ✅ Toast shows "Success" or similar
- ✅ AlertDialog appears with title "Meter Reading Summary — [MeterNo]"
- ✅ Shows Cumulative section with:
  - kWh Import: [value in kWh]
  - kWh Export: [value in kWh]
  - KVA MD: [value in kW]
- ⚠️ Billing section shows N/A (not implemented yet)
- ⚠️ TOD rates show N/A (need OBIS identification)

### Test 2: Run Billing Read
```
1. Reading Type: Select "BILLING"
2. Meter No: Optional
3. Tap "Read with Theft"
4. Wait for completion
```

**Expected Result:**
- ✅ Dialog appears (same as above)
- ✅ Cumulative values populate
- ⚠️ Billing values still N/A

### Test 3: Check Logs
```
1. Wait for read to complete
2. Connect device via Android Debug Bridge (adb)
3. Pull log file: adb pull /sdcard/NPCL_OPTICAL_LOG.TXT
4. Search for "DEBUG:" lines
5. Look for:
   - "DEBUG: OBIS 0100010800FF → hex='...' → value='XXXX.XXX kWh'"
   - "DEBUG: OBIS 0100020800FF → hex='...' → value='XXXX.XXX kWh'"
```

### Test 4: Verify Values Match Meter Display
```
1. Note values shown in dialog
2. Check physical meter display
3. Compare cumulative values:
   - kWh Import should match meter's import register
   - kWh Export should match meter's export register
   - KVA MD should match meter's demand maximum
4. If values differ:
   - Check OBIS code is correct
   - Verify data type (uint32 vs uint16)
   - Review unit conversion (1000) if needed
```

## Current Feature State

### Working ✅
- **kWh Import Cumulative** — OBIS 0100010800FF (attr 2)
- **kWh Export Cumulative** — OBIS 0100020800FF (attr 2)
- **Power/KVA MD** — OBIS 0100030700FF
- **Dialog Display** — Shows after successful read
- **Error Handling** — Gracefully handles missing data
- **Logging** — All operations logged

### Not Implemented ⚠️
- **Billing Values** — Requires buffer parsing
- **TOD Rates 1-6** — Requires OBIS code identification
- **Meter-Make Configuration** — HPL/Secure/Genus variants

## Troubleshooting Test Issues

### Dialog Doesn't Appear
**Cause:** Read failed or data is empty

**Solution:**
1. Check Toast message — does it say "Success"?
2. If read failed, check:
   - USB cable connection
   - Meter is powered on
   - Correct meter make selected
   - Correct DLMS password
3. Check log file for "Error" messages

### Values Show "N/A" or "ERR"
**Cause:** OBIS code not found or parsing failed

**Solution:**
1. Check log file:
   - Search for "ERROR parsing OBIS"
   - Check if "OBIS XXXX not found"
2. Verify meter data file contains values:
   - For cumulative read: look for kWh patterns
   - Check values match meter display ranges
3. Contact support with log file

### Values Don't Match Meter Display
**Cause:** Wrong OBIS code or unit conversion error

**Solution:**
1. Compare meter display → dialog values
2. Check meter specification for OBIS codes
3. Verify unit: kWh (not Wh), kW (not W)
4. Check hex-to-decimal conversion (÷1000)

### Dialog Text Overlaps or Appears Cut Off
**Cause:** Screen resolution/density issue

**Solution:**
1. The dialog content is scrollable — swipe to see more
2. Text is in monospace font for alignment
3. Reduce font size if needed (edit showMeterReadingDialog in Reading.java)

## Next Action Items

### For You To Complete
1. **Identify TOD Rate OBIS Codes:**
   - Gather meter specification documents
   - Test with real meters to find codes
   - Document in METER_MAKE_TOD_CONFIG.md

2. **Update Code with Correct OBIS:**
   - Edit Reading.java - extractMeterReadings() method
   - Replace placeholder TOD sections
   - Rebuild app

3. **Test with Real Meters:**
   - HPL meter: Verify TOD rates 1-6
   - Secure meter: Check if different codes needed
   - Genus meter: Test if supported

### For Feature Expansion
- [ ] Implement billing data extraction
- [ ] Parse billing profile buffer
- [ ] Create meter-make specific configuration
- [ ] Add "Save" and "Share" buttons
- [ ] Add "Details" view with raw hex

## File References

### New Files
| File | Purpose |
|------|---------|
| MeterReadingParser.java | DLMS hex parsing utilities |
| METER_READING_DISPLAY_GUIDE.md | Detailed implementation guide |
| METER_MAKE_TOD_CONFIG.md | Configuration template |
| FEATURE_IMPLEMENTATION_SUMMARY.md | What was implemented |

### Modified Files
| File | Changes |
|------|---------|
| Reading.java | Added display methods, data extraction, logging |

## Code Examples

### Check if Feature is Working
Look in NPCL_OPTICAL_LOG.TXT for these patterns:

**Good extraction:**
```
[14:23:45.123] DEBUG: OBIS 0100010800FF → hex='0006040000AF7F' → value='45439.487 kWh'
[14:23:45.234] DEBUG: OBIS 0100020800FF → hex='0006040000001A' → value='26.000 kWh'
```

**Missing OBIS:**
```
[14:23:45.345] DEBUG: OBIS 0100030700FF not found in meter data
```

**Parsing error:**
```
[14:23:46.123] ERROR parsing OBIS 0100010800FF: String index out of range
```

## Demo Values

Expected ranges (for reference):
- **kWh Import:** 0 to 999,999.999 (cumulative counter)
- **kWh Export:** 0 to 999,999.999 (cumulative counter)
- **KVA MD:** 0 to 100+ (peak demand in kW or kVA)
- **TOD Rates 1-6:** Should match rate registers (typically 0-100)

If values are outside these ranges or show as 0 on all fields, something is wrong.

## Support Information

### If You Get Stuck
1. **Check the logs:** NPCL_OPTICAL_LOG.TXT contains all debug info
2. **Review documentation:** METER_READING_DISPLAY_GUIDE.md
3. **Compare with meter:** Verify values against physical meter

### Share This Info When Reporting Issues
- Log file excerpt (copy relevant lines from NPCL_OPTICAL_LOG.TXT)
- Meter make (HPL/Secure/Genus)
- Meter model number
- Expected vs. actual values
- Android device and API level

---

**Last Updated:** 2026-03-17  
**Feature Version:** 1.0  
**Status:** PARTIAL (Cumulative values working, TOD pending)  
**Testing Checklist:** See above

Ready to test? Connect your meter and tap "Read with Theft"! 🚀
