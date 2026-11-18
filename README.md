# Threat Detector (Java)

An offline JavaFX desktop port of the existing Threat Detector front-end. The
HTML/CSS/JS UI is the original product UI; the Rust/Tauri backend was translated
to Java and is reached through a small `window.__TAURI__` shim.

## Run it

Double-click **Threat Detector (Java)** on the Desktop, or:

```bat
run-dev.bat
```

The shortcut starts the packaged jar with `javaw` and no console window.

## Verify it

```bat
run-checks.bat
```

This runs the 31 JUnit tests (including a 562-input differential test against
Rust goldens), the end-to-end smoke test, and the WebView compatibility audit.
The audit fails if localStorage is unavailable, because history and settings
depend on it. Expected final line: `CHECKS PASSED`.

## Build a release

```bat
build-release.bat
```

Produces `dist\ThreatDetector.jar` and `dist\icon.ico`. Recreate the desktop
shortcut after moving the folder or rebuilding on another machine:

```powershell
powershell -ExecutionPolicy Bypass -File tools\create-shortcut.ps1
```

## Requirements

- JDK 22 on `PATH` (the installed runtime; JavaFX 22.0.2 is bundled in the jar).
- Maven 3.9+ to build. Runtime is fully offline; Maven needs the network only
  for the first dependency download.

## What works

- Dashboard single scan and CSV batch scan, with streaming progress and cancel.
- CSV file picker, drag-and-drop content path, 1 MB / 5,000-row limits.
- Risk rules, scores, thresholds, rule IDs and IPC JSON shapes match Rust.
- `NotVerified` can never become Low or a safe state.
- History, settings, verdict-color shade picker and hardware monitor render in
  JavaFX WebView. History/settings use the front-end's own localStorage.
- No analyzed URL is ever visited, fetched, resolved or pinged.

## Layout

```
core/   Pure Java rules, CSV parser, engine, models (no JavaFX)
app/    JavaFX shell, WebView host, IPC bridge, smoke/screenshot modes
app/src/main/resources/web/   Copied front-end (one shim + four script tags)
docs/ai/    Decisions, IPC contract, test matrix, progress, handoff
docs/original/  Original project documentation (reference only)
tools/  launch.vbs and create-shortcut.ps1
```

## Known limitations

- JavaFX 22 uses WebKit 617.1, which lacks `:has`, `content-visibility`,
  `aspect-ratio` and `backdrop-filter`. Only the shade picker depended on
  `aspect-ratio`; the shim sizes it explicitly. The rest are unused or cosmetic.
- The jar is a shaded ~46 MB bundle; there is no MSI/installer. The requested
  Desktop shortcut is the delivery mechanism.
- Run one instance at a time. Two instances share the extracted assets and the
  WebView profile; the second falls back to another port and may lose
  localStorage-backed history/settings.
- JavaFX prints `Unsupported JavaFX configuration: classes were loaded from
  'unnamed module'` on startup. It is expected for a shaded jar and harmless.
- Logs: `%APPDATA%\ThreatDetector\app.log`. Extracted UI and WebView data live
  under `%APPDATA%\ThreatDetector\`.

## Documentation

- `docs/ai/TEST_MATRIX.md` - G1-G5 gate evidence and Rust-to-JUnit mapping.
- `docs/ai/DECISIONS.md` - translation and packaging decisions.
- `docs/ai/FRONTEND_CHANGES.md` - exact front-end delta.
- `docs/ai/IPC_CONTRACT.md` - Java bridge contract.
- `docs/ai/HANDOFF.md` - how to resume or troubleshoot.
