# Handoff

## What this is

An offline JavaFX re-host of the existing Threat Detector UI. The front-end is
the unchanged product UI (plus one shim); the Rust/Tauri backend is translated
1:1 to Java under `core/`, and `app/` is a thin JavaFX shell.

Read `AGENTS.md` first. `DAC_Tauri` and `DAC_Frontend` are read-only reference
sources; Rust behavior is the specification.

## Where things stand

- G1-G5 pass; see `TEST_MATRIX.md` and `PROGRESS.md`.
- Window sizing, system theme following and the performance pass are fixed and
  documented; see `PERF_REPORT.md` and the entries in `LOG.md`.
- `run-checks.bat` must end with `CHECKS PASSED`.
- Desktop shortcut `Threat Detector (Java).lnk` targets
  `javaw.exe -jar <repo>\dist\ThreatDetector.jar`; verified to log
  `READY page loaded`.
- Git history: scaffold, front-end copy, core, tests/goldens, shell, release/docs,
  with phase and gate tags. Working tree should be clean.

## Build / run / verify

```bat
run-dev.bat           :: build from source, run with console
run-checks.bat        :: clean verify + smoke + compat audit
build-release.bat     :: dist\ThreatDetector.jar + icon
powershell -ExecutionPolicy Bypass -File tools\create-shortcut.ps1
```

Requirements: JDK 22 on `PATH` (JavaFX is bundled in the jar), Maven 3.9+.
Offline at runtime. First Maven build needs the dependency download.

## Project map

| Path | Purpose |
|---|---|
| `core/src/main/java/.../model/` | Sealed verdicts, risk levels, IPC records |
| `core/.../validator.rs` equivalent | `Validator`, `UrlInspector`, `UrlRules`, `MessageRules`, `Scorer` |
| `core/.../service/CsvParser.java` | Typed/plain dialects, size and row limits |
| `core/.../engine/` | Semaphore pool, aggregator, cancellation, batch registry |
| `app/.../WebHost.java` | Asset extraction + loopback HTTP server |
| `app/.../bridge/Bridge.java` | IPC commands, JSON, events |
| `app/.../App.java` | WebView window, window policy, smoke/compat/theme/perf modes |
| `app/src/main/resources/web/` | Copied product UI (39 files) |
| `docs/ai/` | Decisions, contract, test matrix, progress, this file |
| `docs/ai/evidence/` | Gate logs, reports, screenshots |

## What to check by eye

1. Double-click the Desktop shortcut: the dashboard opens, no console, no blank
   page, scan/batch controls visible. The window should fit the screen with a
   normal title bar and working minimize/maximize/close.
2. Windows Settings -> Personalization -> Colors: switch app mode dark/light
   while Settings -> Appearance is on "System"; the app must follow without a
   restart.
3. Run the shipped 10-row CSV. Expect 2 Low, 2 Medium, 4 High, 2 NotVerified.
   The two NotVerified rows must show "Not Verified", never Low.
4. Settings -> Verdict colors -> open a shade: the preview square is a square,
   not a thin strip.
5. While a 500-row batch streams, the UI should stay responsive (scrolling may
   still feel heavy; that is the documented remaining cost).

## Troubleshooting

- **Blank page / no READY:** check `%APPDATA%\ThreatDetector\app.log`. The host
  logs the chosen port and `READY page loaded`. If port 47821 is busy, the app
  uses an ephemeral port; the URL in the log is authoritative. Port 47821 is
  normally busy only when another copy of the app is already running; close it
  before relaunching. Concurrent instances share the WebView profile and can
  lose localStorage.
- **Shortcut missing after creation:** some security tools quarantine
  `wscript`/VBS shortcuts. The supported shortcut launches `javaw.exe`
  directly; `tools\launch.vbs` is optional.
- **`Unsupported JavaFX configuration ... unnamed module`:** expected for the
  shaded jar; not an error.
- **Stale UI after editing front-end files:** the app re-extracts assets on
  launch. If needed, delete `%APPDATA%\ThreatDetector\web` and relaunch.
- **Smoke test fails:** inspect `smoke-test-report.json` (root) for `backend`,
  counts, `error`, and per-item results. `backend` must be `tauri`.

## Changing rules or parser behavior

1. Change Java code and update/add JUnit tests.
2. Regenerate goldens only in a temporary copy of `threat-core`, never inside
   `DAC_Tauri`:
   - copy `crates/threat-core` to `%TEMP%\dac_rust_oracle`,
   - add `serde_json` to that copy's `Cargo.toml`,
   - run the dump example over the dataset, `stress_500.csv` and edge inputs,
   - copy the resulting JSONL to `core/src/test/resources/golden/`.
3. Re-run `mvn -q clean verify`; `ParityTest` must stay 562/562 (or grow).
4. Update `TEST_MATRIX.md`, `DECISIONS.md` and `PROGRESS.md` if numbers or
   behavior changed.

## Deliberate deviations

See `DECISIONS.md` for all 21 entries. The two a reviewer will notice first:

- Batch commands reply `{batchId,total}` before streaming (contract) rather than
  after completion (current Rust code). The page supports both.
- URLs are served over loopback HTTP instead of `file://` so localStorage works.
  The server is loopback-only and serves only extracted app assets.

## Non-negotiables when resuming

- `NotVerified` must never become Low or safe.
- Never visit, fetch, resolve or ping an analyzed URL.
- Do not edit the copied front-end except `js/core/java-shim.js` and its four
  script tags; record changes in `FRONTEND_CHANGES.md`.
- No `unsafe`, no panics across IPC, no new heavyweight dependencies.
- Keep the tree clean and commit each milestone.
