# Resume

How to resume work on this repo. Read `AGENTS.md` first.

## Ground rules (from AGENTS.md, restated)

- `Desktop\DAC_Tauri` and `Desktop\DAC_Frontend` are read-only reference inputs.
- Front-end edits only in `app/src/main/resources/web/js/core/java-shim.js` plus
  its `<script>` tags; every change goes to `FRONTEND_CHANGES.md`.
- Commit after each finished fix (plain ASCII messages, clean tree at the end).
- Keep `docs/ai/*` current: this file, `LOG.md`, `MIGRATION_STATUS.md`,
  `NEXT_STEPS.md`, `PROGRESS.md`, `TEST_MATRIX.md`, `DECISIONS.md`.
- Time-box each problem: 3 attempts, then log the blocker and move on.
- `run-checks.bat` must end with `CHECKS PASSED` before any fix is called done.

## Current status (2026-11-19)

- Fix 1 (window geometry): done. `App` sizes the stage from the primary visual
  bounds (90%, cap 1280x800, min 900x600 capped by the screen), validates saved
  state in `%APPDATA%\ThreatDetector\window.state`, supports `--reset-window`
  and `--window-debug`, toggles full screen with F11/Escape, and the smoke test
  asserts the shown window sits inside the visual bounds. Root cause of the
  bug: fixed 1280x800 on a 1280x689-logical-pixel desktop (150% scaling).
- Fix 2 (theme): done. Explicit dark/light apply immediately and survive a
  restart. `theme: "system"` follows Windows `AppsUseLightTheme` via a UA
  marker plus the `get_system_theme` bridge command; the shim owns
  `matchMedia('(prefers-color-scheme: dark)')` and fires change events. Root
  causes: WebView always reported light; the last localStorage write was lost
  on `System.exit`.
- Fix 3 (perf): done. `--perf` harness measures idle, theme switch, 500-row
  streaming, verdict render and scroll. Applied: explicit D3D pipeline and
  coalesced Java -> JS event delivery (1068 -> ~36 `executeScript` calls per
  run; streaming 190 -> 76 ms per frame). Reduced motion was tested and
  rejected (no gain); progress throttle stays at 40 ms. Details and next
  options: `docs/ai/PERF_REPORT.md`.

## Verification

```bat
run-checks.bat
build-release.bat
java -jar app\target\threat-detector-java-1.0.0-all.jar --window-debug --auto-close-ms=4000
java -jar app\target\threat-detector-java-1.0.0-all.jar --theme-debug
java -jar app\target\threat-detector-java-1.0.0-all.jar --perf
java -jar app\target\threat-detector-java-1.0.0-all.jar --perf --perf-reduced
```

Measured reports land in `perf-report.json`, `compat-report.json`,
`theme-debug.json` and `window-debug.log`; curated copies live in
`docs/ai/evidence/`. Run one app instance at a time (shared WebView profile).
