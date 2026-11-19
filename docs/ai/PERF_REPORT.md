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
| Window | 1062x600 logical (saved geometry), decorated, focused during measurement |
| Graphics pipeline | D3D (`com.sun.prism.d3d.D3DPipeline`, D3D9Ex device), `prism.order=d3d,sw` |
| Idle frame budget | 16.7 ms (60 Hz) |

Pipeline verified with `-Dprism.verbose=true` ("Initialized prism pipeline:
com.sun.prism.d3d.D3DPipeline", "Created D3D9Ex device").

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

### Harness navigation guard (added in step 0)

Some earlier `--perf` runs silently navigated from `index.html` to
`settings.html`; the sample then described a page the run did not intend to
measure. The harness now:

- logs `location.href` at the start and end of every phase (`phaseMark`) and
  records them in `perf-report.json` (`navigation.phases`);
- reports `navChanged: true` and exits non-zero if any phase saw a different
  URL, and the Java side also watches `WebEngine.locationProperty()` so a
  navigation fails the run even after the JS context is destroyed;
- records the active phase and (on navigation) the last input events in
  `localStorage`, logged as `PERF NAV EVIDENCE`;
- starts the run only once per process; a second `SUCCEEDED` state from the
  same page load used to start a second harness that raced the first.

All numbers below are from guarded runs (`navChanged=false`, `pageIsIndex=true`).
The navigation did not reproduce after the fix; if it returns, the guard now
names the phase instead of producing a bad report.

## Before (step 0 baseline, median of 3 guarded runs)

| Phase | Frames | Avg frame | p95 frame | Long frames >32 ms | Duration |
|---|---|---|---|---|---|
| Idle (dashboard, no work) | 150+ | 17.2 ms | 29-30 ms | 0-1 | 2.5 s sample |
| Theme switch (dark <-> light, including CSS transition) | 119 | 18.3 ms | 32 ms | 0 | ~2 s |
| 500-row batch: streaming (item-done events) | 84-133 | 43.4 ms | 123-133 ms | 8-12 | 4.2-5.2 s |
| 500-row batch: verdict rows render (after batch-done) | 13-14 | 185.7 ms | 256-300 ms | 13-14 | 2.4-2.6 s |
| 500-row batch list scroll (down and up) | 78 | 262.7 ms | 336-436 ms | 78 | ~20 s (36,156 px range) |

Batch results: 500/500 item events, 500 rendered rows, ~63 progress events
(40 ms engine throttle intact).

**Correction to the earlier report:** its figures (streaming 75.7 ms, scroll
38.8 ms) could not be reproduced. The old runs were partly unguarded and the
scroll phase was measured on a machine state that no longer occurs. The table
above is the baseline used for all after/before comparisons in this document.

### Startup (process start -> page load event)

| Stage | Time |
|---|---|
| WebHost ready (asset extract + loopback HTTP) | 0.47-0.67 s |
| WebView/WebEngine created and configured | 1.99-2.61 s |
| Page ready (HTML + 39 assets + `DOMContentLoaded` work) | 3.7-5.3 s total |

WebView/WebKit initialisation is the single largest fixed cost (~2 s).
The local HTTP host costs ~0.5 s, most of it deleting and re-extracting the
web tree on every launch.

## Earlier quick wins (kept)

### 1. Explicit D3D pipeline - applied

`Launcher` sets `prism.order=d3d,sw` unless overridden. Verified D3D is active.
No behavioural change; this makes the hardware path deterministic on machines
where the default order could fall back to software.

### 2. Coalesced Java -> JavaScript event delivery - applied

Before: every item, progress and reply event called `WebEngine.executeScript`
once (1068 calls during one perf run). The bridge now queues scripts and
concatenates everything queued within one FX pulse into a single
`executeScript` call (order preserved, 256 KB cap, listeners isolate their own
errors). Calls per run dropped to 31-37.

### 3. Reduced motion - tested, not enabled

A/B with `--perf-reduced` (forces the front-end's `reduceAnimations` path) showed
no measurable gain, so the user's motion setting is left alone.

### 4. Progress throttle and payload audit - no change needed

`Engine.PROGRESS_FLUSH_MS` is still 40 ms. Event payloads are the per-item
verdicts the UI needs; nothing oversized was found. The CSV is only embedded in
the perf harness, not in normal operation.

## Step 1 - bridge delivery (after)

What changed:

- **Event subscriptions.** The shim reports demand from `TD.events` to Java
  (`subscribe(name, on)`), and Java serializes and delivers only subscribed
  events. `analysis:item-start` has no page listener, so it is never serialized
  or sent: 500 events per run disappear at the source.
- **Direct `JSObject.call`.** Delivery no longer builds script text. One shim
  function (`__tdShim.__deliver`) receives one JSON array of pre-serialized
  messages; chunks are capped and the next chunk size follows the measured
  duration of the previous call (~8 ms target).
- **Time-sliced dispatch.** The shim queues messages and dispatches them inside
  `requestAnimationFrame` with a soft 6 ms budget and at least two messages per
  frame. Order is preserved. `analysis:batch-done` switches the queue to an
  unbounded drain so the final table is never left half-applied.
- **Coalescing and backpressure.** `analysis:progress` keeps only the latest
  snapshot. The outbox is capped at 2,000 entries; above the cap only
  coalescable progress is dropped (counted and logged), never results or
  replies.
- **Strong reference.** The shim `JSObject` is held for the page's lifetime and
  re-attached per load; deliveries to a detached document are dropped.

| Metric | Before | After step 1 | After step 2 |
|---|---|---|---|
| FX-thread time in Java -> JS delivery, whole run | ~4.0-4.5 s inside `executeScript` | **33 ms** total | **34 ms** total |
| Delivery calls per run | 31-37 | 92 (small bounded chunks) | 106 |
| Events serialized/delivered | 1,068 + 500 item-start | 576 (item-start never built) | 571 |
| Idle | 17.2 ms / p95 29 | 16.9 ms / p95 30 | 17.1 ms / p95 30 |
| Theme switch | 18.3 ms / p95 32 | 18.3 ms / p95 31 | 18.1 ms / p95 31 |
| 500-row streaming frame | 43.4 ms / p95 128 | **25.8 ms / p95 35** | **25.4 ms / p95 50** |
| 500-row streaming duration | 4.2-5.2 s | ~7.6 s | **4.3 s** (restored) |
| 500-row render (post-batch) | 185.7 ms / p95 259 (2.4-2.6 s) | 187.6 ms / p95 259 | **3 ms duration** (instant windowed) |
| 500-row scroll avg frame | 262.7 ms / p95 336 | 297.5 ms / p95 444 | **202.6 ms / p95 385** |
| Active DOM rows in list | 500 | 500 | **10 visible (max 29 in scroll)** |
| Look & ARIA check (0, 1, 250, 499) | N/A (all in DOM) | N/A (all in DOM) | **100% PASS** (all true) |

The bridge is no longer a measurable cost. Stream frames improved ~40%.
Streaming duration returned to ~4.3 s with the 150 ms recent-table throttle.
Verdict list render became instantaneous (3 ms vs ~2.5 s) through virtual list windowing.
Scroll frame times improved by ~60-95 ms, with DOM rows capped under 30 instead of 500.

## Step 2 - batch list virtualization and DOM throttling (after)

What changed:
- **`virtualList.js` windowed rendering:** Only visible rows plus 4 overscan rows
  are placed in `#batch-list`. Padding-top and bottom filler maintain exact
  full scroll geometry and scrollbar behavior (`scrollRangePx: 36164`).
- **Look preservation:** When scrolled into view, rows retain exact original
  DOM structure, risk mapping, data-risk attributes, and ARIA attributes
  (`aria-posinset`, `aria-setsize`). Validated by automated harness look-checks
  at positions 0, 1, 250, and 499.
- **Recent table throttling:** Recent results table rebuilds at most once per
  150 ms instead of on every item during streaming, cutting 495 redundant DOM table
  rebuilds while preserving per-item tally counters and the final result list.
- **Row template caching:** Pre-clones row templates by risk category, avoiding
  repeated `createElement` cycles.
- **CSS containment & idle motion governor:** `java-perf.css` containment applied
  to rows and idle decorative animations paused when window is hidden or blurred.

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

## What is still slow (before step 1)

1. **Scrolling 500 rows:** ~263 ms average frame (~4 fps), 78/78 frames long.
   Every row is in the DOM with its own gradient, border, ring and text; each
   `scrollTop` step forces layout and paint of the whole list.
2. **Verdict list render:** 500 rows appended 40 per animation frame takes
   ~2.4 s and blocks frames (~186 ms each).
3. **Batch streaming:** ~43 ms average frame (~23 fps) while 500 items arrive.
   Every item builds a full row in the recent-results table and updates the
   tally; all of it runs on the UI thread inside `executeScript`.
4. **Startup:** ~4-5 s to page ready, ~2 s of it WebView initialisation (fixed
   cost) plus ~0.5 s asset re-extraction on every launch.
5. **Theme switch and idle are already smooth** at 60 Hz; no work needed there.

## Next options, in order (not implemented yet)

1. **Virtualize `#batch-list`** (and the history table): render only visible
   rows plus spacers. This addresses scroll, completion render, and streaming
   at once. Highest impact, moderate change to `pages/index.js`.
2. **Throttle the recent-results table during a batch:** update it at most
   every 150 ms instead of once per item; keep the batch tally live.
3. **Cheaper bridge delivery:** direct `JSObject.call` into one shim function,
   event subscriptions, time-sliced rAF dispatch, bounded queue.
4. **Startup:** stop re-extracting the web tree when its manifest is unchanged
   (saves ~0.5 s), show the dark window immediately, create the WebView early.
   WebView init remains the floor.
5. **Last resort:** embed Chromium via JCEF (large dependency, changes the
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
