# Performance report - Threat Detector (JavaFX)

Measured 2026-11-19 on Jad's Windows desktop.
Method: `java -jar app\target\threat-detector-java-1.0.0-all.jar --perf`
(JSON in `perf-report.json`; harness inside `App.PERF_JS`).

## Environment

| Item | Value |
|---|---|
| Java | 22.0.2 (Oracle) |
| JavaFX | 22.0.2 |
| WebKit | AppleWebKit/617.1 (JavaFX 22) |
| OS theme | Windows app mode dark (registry checked) |
| Display | primary visual bounds 1280x689 logical, 150% scaling (1920x1033 physical effective) |
| Window | 1152x620 logical, decorated, focused during measurement |
| Graphics pipeline | D3D (`com.sun.prism.d3d.D3DPipeline`, D3D9Ex device), fallback `prism.order=d3d,sw` |
| Idle frame budget | 16.7 ms (60 Hz) |

Pipeline was verified with `-Dprism.verbose=true` ("Initialized prism pipeline:
com.sun.prism.d3d.D3DPipeline"). `Launcher` now sets `prism.order=d3d,sw` when
the user has not overridden it, so the hardware path is explicit rather than
left to defaults.

## Method and limitations

- The 500-row run uses the page's own file-selection path (synthetic `drop` on
  `#csv-drop`) so the real batch UI is active: progress line, tally, and the
  scrollable `#batch-list` that the user sees.
- It runs the page's fastest preset (`scanSpeed=fast`, 15 ms minimum per item,
  concurrency 8). That is what a user gets, not a synthetic zero-delay run.
- Idle is sampled for 2.5 s after a 1.5 s warm-up, so page boot work is not
  counted as steady-state idle.
- `requestAnimationFrame` deltas; "long frame" is > 32 ms (missing two vsyncs).
- The window is brought to front and focused before sampling; background tabs
  and occluded windows are not representative.
- Two early runs were contaminated by Windows Defender scanning a freshly built
  46 MB jar (render wait 3 s, scroll 290 ms/frame). They were discarded; the
  numbers below reproduce across repeated runs on an idle machine.

## Results (median of 3 clean runs)

| Phase | Frames | Avg frame | p95 frame | Long frames | Duration |
|---|---|---|---|---|---|
| Idle (dashboard, no work) | 150+ | 18.3 ms | 31 ms | 0-1 | 2.5 s sample |
| Theme switch (dark <-> light, including CSS transition) | 119 | 17.4 ms | 31 ms | 0 | ~2 s |
| 500-row batch: streaming (item-done events) | 58 | 75.7 ms | 430-590 ms | 10-12 | 4.8 s |
| 500-row batch: verdict rows render (after batch-done) | 14 | 127.8 ms | ~515 ms | 14 | 1.45 s |
| 500-row batch list scroll (down and up) | 78 | 38.8 ms | 65-90 ms | 57 | ~3 s (36,156 px range) |

Batch results: 500/500 item events, 500 rendered rows, 62-74 progress events
(40 ms engine throttle intact), total run plus render ~6.3 s.

### Startup (process start -> page load event)

| Stage | Time |
|---|---|
| WebHost ready (asset extract + loopback HTTP) | 0.54-0.67 s |
| WebView/WebEngine created and configured | 2.38-2.61 s |
| Page ready (HTML + 39 assets + `DOMContentLoaded` work) | 3.9-5.3 s total |

WebView/WebKit initialisation is the single largest fixed cost (~2.4 s).
The local HTTP host costs ~0.55 s, most of it deleting and re-extracting the
web tree on every launch.

## Quick wins tried (one at a time)

### 1. Explicit D3D pipeline - applied

`Launcher` sets `prism.order=d3d,sw` unless overridden. Verified D3D is active.
No behavioural change; this makes the hardware path deterministic on machines
where the default order could fall back to software.

### 2. Coalesced Java -> JavaScript event delivery - applied

Before: every item, progress and reply event called `WebEngine.executeScript`
once (500 item events + resolves = 1068 calls during one perf run). The bridge
now queues scripts and concatenates everything queued within one FX pulse into
a single `executeScript` call (order preserved, 256 KB cap, listeners already
isolate their own errors).

| Metric | Per-event call | Coalesced |
|---|---|---|
| executeScript calls per run | 1068 | 31-37 |
| Batch streaming frames | 21-24 | 52-63 |
| Batch streaming avg frame | 181-208 ms | 67-85 ms |
| Batch row render | 1.3-1.7 s | 1.3-1.5 s (unchanged) |
| Scroll 500 rows | 39-45 ms/frame | 39-45 ms/frame (unchanged) |
| Idle / theme switch | 16.3-16.9 ms | 17.4-19.0 ms (within noise) |

The win is real but partial: the boundary overhead is gone, yet the same DOM
work still runs on the UI thread, so streaming remains ~13 fps.

### 3. Reduced motion - tested, not enabled

A/B with `--perf-reduced` (forces the front-end's `reduceAnimations` path):
idle 16.6 vs 16.6 ms, theme switch 16.5 vs 16.5 ms, streaming 76 vs 77 ms,
scroll 39 vs 43 ms. No measurable gain, so the user's motion setting is left
alone. (Earlier 290 ms scroll numbers were the Defender artifact described
above, not animation cost.)

### 4. Progress throttle and payload audit - no change needed

`Engine.PROGRESS_FLUSH_MS` is still 40 ms (confirmed at runtime: 62-74 progress
events over a 4.8 s batch). Event payloads are the per-item verdicts the UI
needs; nothing oversized was found. The CSV is only embedded in the perf
harness, not in normal operation.

## WebKit feature support (compat report)

| Feature | Supported |
|---|---|
| `color-mix()` | yes |
| `@container` | yes |
| `subgrid` | yes |
| `dvh` / `svh` | yes |
| `:has()` | **no** |
| `content-visibility` | **no** |
| `aspect-ratio` | **no** |
| `backdrop-filter` | **no** |

CSS uses `@supports` fallbacks for the unsupported cases. Because
`content-visibility` is unavailable, off-screen rows cannot be skipped by CSS;
only DOM virtualization can avoid their layout/paint cost.

## What is still slow (measured)

1. **Batch streaming:** ~76 ms average frame (about 13 fps) while 500 items
   arrive. Every item inserts a DOM row in the recent-results table and updates
   the tally; the work is on the UI thread.
2. **Verdict list render:** 500 rows appended 40 per animation frame takes
   ~1.45 s and blocks frames (~128 ms each).
3. **Scrolling 500 rows:** ~39 ms average frame (~25 fps), p95 ~65-90 ms. The
   whole list is in the DOM and WebKit repaints visible rows.
4. **Startup:** ~4-5 s to page ready, ~2.4 s of it WebView initialisation
   (fixed cost) plus ~0.55 s asset re-extraction on every launch.
5. **Theme switch and idle are already smooth** at 60 Hz; no work needed there.

## Next options, in order (not implemented)

1. **Virtualize `#batch-list`** (and the history table): render only visible
   rows plus spacers. This addresses scroll, completion render, and streaming
   at once. Highest impact, moderate change to `pages/index.js`.
2. **Trim per-row effects** if virtualization is deferred: row gradients,
   shadows, `color-mix` borders, and eagerly built score-ring SVG are the
   suspected paint costs. Remove one at a time and re-run `--perf`.
3. **Throttle the recent-results table during a batch:** update it at most
   every 100-200 ms instead of once per item; keep the batch tally live.
4. **Startup:** stop re-extracting the web tree when its manifest is unchanged
   (saves ~0.5 s), and defer non-critical page scripts. WebView init remains
   the floor.
5. **System-theme polling:** `reg query` spawns a process every 3 s. Idle p95
   sits at ~31 ms; if profiling shows the poll is responsible, switch to
   `WM_SETTINGCHANGE` or a JNA registry read. Not changed here.
6. **Last resort:** embed Chromium via JCEF (large dependency, changes the
   offline/startup story; needs an explicit decision).

## Reproduce

```bat
mvn -q -pl app -am package -DskipTests
java -jar app\target\threat-detector-java-1.0.0-all.jar --perf
java -jar app\target\threat-detector-java-1.0.0-all.jar --perf --perf-reduced
java -jar app\target\threat-detector-java-1.0.0-all.jar --compat-audit
```

Artifacts: `perf-report.json`, `compat-report.json`; copies in
`docs/ai/evidence/`.
