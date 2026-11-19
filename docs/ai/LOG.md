# Log

Newest first. One line per finished unit of work.

- 2026-11-19 fix(perf): measured with `--perf`, then applied two safe quick
  wins - explicit `prism.order=d3d,sw` and coalesced Java -> JS event delivery
  (1068 -> ~36 `executeScript` calls per run, streaming 190 -> 76 ms/frame).
  Reduced motion A/B showed no gain and was not enabled; 40 ms progress
  throttle unchanged. Regression harness and results in `PERF_REPORT.md`.
- 2026-11-19 fix(theme): system theme follows Windows (registry -> UA marker +
  `get_system_theme` + shim matchMedia patch); shim tag moved to head; exit()
  flush grace so the last theme choice survives restart; `--theme-debug` dump;
  smoke asserts dark/light apply + persist across reload. Two root causes:
  WebView always reported light; localStorage journal lost the last write on exit.
- 2026-11-19 fix(window): screen-aware startup size (90% cap 1280x800), saved-state
  validation with `--reset-window`, `--window-debug`, F11/Escape full screen,
  window geometry + inside-visual-bounds assertion in the smoke report. Root
  cause: fixed 1280x800 on a 1280x689 logical desktop (150% scaling).
- 2026-11-19 docs: created RESUME/MIGRATION_STATUS/NEXT_STEPS/LOG; AGENTS.md unchanged.
