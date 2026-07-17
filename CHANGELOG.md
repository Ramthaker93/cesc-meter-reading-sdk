# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

Detailed field-log-driven rationale for each `metersdk` fix lives in the version-header
comment block at the top of `metersdk/src/main/java/com/npcl/com/vcpopdl/ReadingSDK.java`
(mirrored in `app/src/main/java/com/npcl/com/vcpopdl/Reading.java`) — this file summarizes
the same history at release granularity.

## [app 1.1.31 / metersdk 1.1.31] - 2026-07-17

### Fixed
- **V56 — Mangled LP head repair**: the evening load-profile gap page
  (22:00 IST → midnight) consistently arrived from Secure meters with its first
  3 bytes stripped — the array tag, count, and the first record's struct tag `02` —
  so every optical read was missing the 22:00 IST interval (95/96 records/day)
  while MRI-based reads captured the full 96. Added `repairMangledLpHead()`,
  applied at every LP/midnight page-reception site (gap ladder, day loop, bulk,
  bulk-fallback probe, entry probes/pages, midnight selective + pagination): when
  a page's payload starts with a plausible field-count byte directly followed by
  a `090c07e…` clock, the constant DLMS struct tag `02` (a structural byte only,
  never a meter value) is prepended so the strict record collector accepts it.

### Changed
- `metersdk` published version bumped `1.1.30` → `1.1.31`.
- App `versionCode` `1` → `2`, `versionName` `"1.0"` → `"1.1.31"` (now tracks the
  `metersdk` version it bundles, since the app is a thin host around the SDK).

## Earlier history

Versions V38–V55 (newest-first LP pagination, HDLC continuation-frame fix, gap-driven
LP completion, link-state self-healing, silence-vs-absence completion, gap-engine
retry path, and related field-log-driven fixes) predate this changelog. See the
version-header comments in `ReadingSDK.java` / `Reading.java` for full details on
each of those releases.
