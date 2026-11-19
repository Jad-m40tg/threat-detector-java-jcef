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
- Fix 2 (theme): in progress. Explicit dark/light verified working; the
  remaining gap is `theme: "system"` - the WebView reports light regardless of
  the Windows app theme. Plan: read the registry key in Java, expose it via a
  UA marker + `get_system_theme` bridge call, patch `matchMedia` in the shim,
  and move the shim `<script>` tag before `theme-init.js`.
- Fix 3 (perf): pending. Measure first (`docs/ai/PERF_REPORT.md`), apply only
  safe quick wins.

## Verification

```bat
run-checks.bat
build-release.bat
java -jar app\target\threat-detector-java-1.0.0-all.jar --window-debug --auto-close-ms=4000
java -jar app\target\threat-detector-java-1.0.0-all.jar --theme-debug
```
