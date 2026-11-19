# Next steps

Ordered, opinionated, none started unless marked done.

## Theme

1. Native dark title bar when the app theme is dark (Windows
   `DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)`). Skipped for now: it
   needs JNI/JNA user32 calls, which conflict with the "no risky native code"
   constraint. Revisit only if Jad asks for it specifically.
2. Listen for live Windows theme changes via `WM_SETTINGCHANGE` instead of the
   3 s registry poll, if the poll is ever visible in profiling.

## Performance (measured; see `PERF_REPORT.md`)

Already applied: explicit D3D pipeline and coalesced bridge event delivery.
Idle and theme switch run at 60 Hz. The remaining cost is DOM work on the UI
thread, so the next options are ordered by impact:

1. Virtualize `#batch-list` and the history table (visible window + spacers).
   Fixes scroll, verdict-render and streaming cost together; moderate change to
   `pages/index.js`.
2. If virtualization is deferred, trim per-row effects one at a time (gradient,
   shadow, `color-mix` border, eagerly built score-ring SVG) and re-run
   `--perf`.
3. Throttle the recent-results table during a batch (100-200 ms) while keeping
   the tally live.
4. Startup: stop re-extracting the web tree when its manifest is unchanged
   (~0.5 s), then defer non-critical page scripts. WebView init (~2.4 s) is the
   floor.
5. System-theme poll: replace the 3 s `reg query` process with
   `WM_SETTINGCHANGE` or a JNA read if profiling ever shows it.
6. Last resort: embed Chromium via JCEF (large dependency, changes the
   offline/startup story - needs an explicit decision).

## Hygiene

1. `run-checks.bat` takes ~90 s (smoke reloads the page twice for theme
   assertions). Acceptable; trim settle pauses if it grows.
2. Consider a `--profile` flag that runs smoke/perf against a throwaway WebView
   data dir so Jad's real history/settings are never touched by gates.
