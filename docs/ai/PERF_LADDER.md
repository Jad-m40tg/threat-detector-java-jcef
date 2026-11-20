# PERF_LADDER.md — DAC_Java UI performance ladder

STATE: stage=0 | next action=Stage 1 probes (not implemented; see OPEN) | measure lock=free | updated=2026-11-20

## BASELINE (clean run, saved window 1062x600, --perf --fps, hostReady 542ms, engineReady 2163ms)
FX fps per scenario (from fxPhaseFps):
- idle: 62.5 fps (idle-end)
- theme switch: 28.8 fps (theme-end)
- 500-row streaming: 36.0 fps (batch-done)
- 500-row scroll: 19.6 fps (scroll-end)
rAF frame metrics:
- idle: 18.2ms avg, p95 38ms, 11 long frames
- themeSwitch: 20.0ms avg, p95 39ms, 12 long
- batch500Run: 21.5ms avg, p95 40ms, 7 long, 1388ms duration
- scroll500: 39.7ms avg, p95 68ms, 55 long frames

## TARGETS
idle 55+, theme switch 50+, streaming 45+, scroll 50+ FX fps.

## PROBES
Not implemented (no --probe mode). Decision table not applied. Manual observation: scroll 19.6 fps is far below target; theme 28.8 below; streaming 36.0 below; idle 62.5 meets target.

## DECISIONS
- Stage 0 partial: FxFpsMeter + --fps + fxPhaseFps in report added. Pipeline: d3d (Direct3D), JavaFX 22, WebKit 617.1.
- Stream throttle applied in index.js (progress stored, DOM painted at 4 Hz; recent table rebuilt once at finish).
- Step 5 lighter paint (gradient removal) was applied then REVERTED per user request (gradient restored). A/B showed gradient removal improved scroll p95 by ~45% (278->153ms); user chose to keep gradient.

## CHANGES
- Stage 0: App.java FxFpsMeter + --fps + fxPhaseFps; commit pending.
- Stream throttle: index.js; commit pending.

## OPEN
- --probe mode not implemented; engine-boundness not formally measured (P7/P8).
- Scroll remains the main bottleneck (19.6 fps vs 50 target) with gradient restored.
- Theme switch (28.8) below target.
- Streaming (36.0) below target.
- Recommend: Stage 5 JDK 25/JavaFX 25 upgrade to get newer WebKit, or accept gradient removal.
