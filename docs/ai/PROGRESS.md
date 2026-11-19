# Progress

Living project memory for the Java/JavaFX port. Newest entries first.

## Status: complete + window/theme/perf fixes, G1-G5 pass

- Source of truth: `C:\Users\ASUS Vivo\Desktop\DAC_Tauri` (read-only).
- Product UI copied from `DAC_Tauri\src` into `app\src\main\resources\web`.
- Desktop shortcut `Threat Detector (Java).lnk` launches
  `dist\ThreatDetector.jar` and logs `READY page loaded`.
- Core suite: **31 tests in 9 suites, 0 failures**.
- Differential parity: **562/562 inputs** match Rust IPC output on 15 fields.
- Smoke: `ok=true`, `backend=tauri`, 10 rows, 2 Low / 2 Medium / 4 High /
  2 NotVerified.
- Compatibility: WebKit 617.1; localStorage works; shade-picker `aspect-ratio`
  gap fixed inside the shim. The compat audit now fails the gate if
  localStorage is unavailable.

## Phase log

### Phase 0 - scaffold and front-end copy

- Parent POM with `core` and `app`; JDK 22, OpenJFX 22.0.2, Jackson databind,
  Commons CSV, JUnit 5.
- Copied 38 UI files; added `js/core/java-shim.js` and one `<script>` line to
  the four HTML pages. Recursive SHA-256 audit: no other differences.
- Original docs archived under `docs/original`.

### Phase 1 - core translation

- Models: `RiskLevel` (LOW/MEDIUM/HIGH only), sealed `Verdict` with `Assessed`
  and `NotVerified(NotVerifiedReason)`, `IpcAnalysisResult` with Rust field
  order.
- `Validator`, offline `UrlInspector`, `UrlRules` (8 rules), `MessageRules`,
  `Scorer`, `ThreatException` -> typed reason mapping.
- `CsvParser`: typed and plain dialects, BOM/blank/CRLF tolerance, 1 MB file and
  5,000-row limits, typed row errors.
- `Engine`: fair semaphore + virtual threads, completion service, single
  aggregator, 40 ms progress throttle, cancellation token.

### Phase 2 - JavaFX shell

- `WebHost`: extracts assets to `%APPDATA%\ThreatDetector\web`, serves them on
  `127.0.0.1:47821` (ephemeral fallback) for a stable localStorage origin.
- `Bridge`: all five commands, Jackson JSON, virtual-thread work, FX-thread
  replies/events. `BridgeSink` maps engine callbacks to the four event names.
- `Launcher`: plain main class for the shaded jar; file logging to
  `%APPDATA%\ThreatDetector\app.log`.
- `App`: smoke, compatibility and screenshot modes.

### Phase 3 - verification

- Rust oracle generated from an unmodified copy of `threat-core` in
  `%TEMP%\dac_rust_oracle` with `serde_json` added there only. 562 inputs:
  10 dataset + 500 stress + 52 edge cases.
- `ParityTest` compares `id, inputType, input, riskLevel, risk, state, score,
  status, category, details, reasons, triggeredRules, recommendation, error, ts`;
  `durationMs` is excluded.
- `DatasetTest` pins the G1 table (levels, scores, ordered rule IDs).
- `EngineConcurrencyTest` proves the concurrency bound, failure isolation and
  start/done/batch-done ordering.
- Visual QA screenshots for index, history, settings, hardware, shade picker.
  The shade square initially collapsed to 2 px; fixed via the shim
  (`ResizeObserver` + `MutationObserver`), product CSS untouched.

### Phase 4 - release and shortcut

- `build-release.bat` -> `dist\ThreatDetector.jar` (~46 MB shaded) and
  `dist\icon.ico`.
- `tools\create-shortcut.ps1` targets `javaw.exe -jar`. An earlier
  `wscript.exe` + VBS shortcut was removed by security software; the direct
  javaw shortcut persists and was verified to log `READY page loaded`.
- Scripts: `run-dev.bat`, `run-checks.bat`, `build-release.bat`,
  `tools\launch.vbs` (optional).

### Phase 5 - window, theme and performance fixes

- Window: stage size now comes from the primary screen visual bounds (90%,
  capped 1280x800, min 900x600 never larger than the screen). Saved state is
  validated against all screens; `--reset-window`, `--window-debug`, F11/Escape
  full screen. Root cause: fixed 1280x800 scene on a 1280x689-logical desktop
  (150% scaling).
- Theme: `theme: "system"` now follows the Windows app theme. Java reads
  `AppsUseLightTheme`, exposes a UA marker plus `get_system_theme`, and the
  shim owns `matchMedia('(prefers-color-scheme: dark)')`. The last theme write
  survives exit (600 ms flush grace). Smoke asserts dark and light apply.
- Perf: added a `--perf` harness driving the real file-drop and batch UI.
  Coalesced bridge events, added demand-driven event subscriptions and direct
  `JSObject.call` delivery (Step 1). Implemented `#batch-list` DOM virtualization,
  recent table 150 ms throttling, row template caching, and CSS containment/motion
  governor (Step 2). Post-batch render dropped from ~2.5 s to 3 ms; streaming duration
  restored to 4.3 s; look preservation (rows 0, 1, 250, 499) 100% verified. Full
  details in `PERF_REPORT.md`.

## Verification commands

```bat
run-checks.bat        :: mvn clean verify + smoke + compat; ends CHECKS PASSED
build-release.bat     :: dist\ThreatDetector.jar
run-dev.bat           :: build and launch with console output
java -jar app\target\threat-detector-java-1.0.0-all.jar --perf
java -jar app\target\threat-detector-java-1.0.0-all.jar --theme-debug
```

## Evidence

- `docs/ai/TEST_MATRIX.md` - G1-G5 detail and Rust-to-JUnit mapping.
- `docs/ai/evidence/checks.log` - last full gate output.
- `docs/ai/evidence/smoke-test-report.json` - last smoke report.
- `docs/ai/evidence/compat-report.json` - last WebView audit.
- `docs/ai/evidence/screenshot-*.png` - rendered pages.
- Goldens: `core/src/test/resources/golden/rust_golden.jsonl`.

## Notes / known limitations

- JavaFX 22 WebKit 617.1 lacks `:has`, `content-visibility`, `aspect-ratio`
  and `backdrop-filter`; only the shade picker needed a shim fix.
- Startup prints `Unsupported JavaFX configuration: classes were loaded from
  'unnamed module'`. Expected for a shaded jar.
- `checks.log` in the project root is ignored; curated evidence lives under
  `docs/ai/evidence/`.
- The temporary Rust oracle (`%TEMP%\dac_rust_oracle`) can be deleted; the
  goldens are committed.
