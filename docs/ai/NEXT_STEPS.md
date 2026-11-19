# Next steps

Ordered, opinionated, none started.

## Theme

1. Native dark title bar when the app theme is dark (Windows
   `DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE)`). Skipped for now: it
   needs JNI/JNA user32 calls, which conflict with the "no risky native code"
   constraint. Revisit only if Jad asks for it specifically.
2. Listen for live Windows theme changes via `WM_SETTINGCHANGE` instead of the
   3 s poll, if the poll is ever visible in profiling.

## Performance (only if `PERF_REPORT.md` shows a real problem)

1. Remove the heaviest CSS effects on the batch list (gradient + `color-mix`
   border per row) - biggest suspected cost with 500 rows.
2. Virtualize the 500-row batch list / history table (render window + spacer).
3. If WebKit remains the bottleneck, embed Chromium via JCEF (heavy dependency,
   changes the offline story - needs an explicit decision).

## Hygiene

1. `run-checks.bat` currently takes ~90 s (smoke reloads the page twice for the
   theme assertions). Acceptable; trim the settle pauses if it grows.
2. Consider a `--profile` flag that runs smoke/perf against a throwaway WebView
   data dir so Jad's real history/settings are never touched by gates.
