# Front-end changes

Source: `C:\Users\ASUS Vivo\Desktop\DAC_Tauri\src` (read-only reference).
Destination: `app\src\main\resources\web`.

## Copy method

`robocopy src app\src\main\resources\web /E /XD docs` copied 38 product files.
The source `src\docs` folder was intentionally excluded; it is not UI. One new
file was added, for 39 files in the Java copy.

## Exact delta

A recursive SHA-256 comparison of the two trees reports:

- **Only in Java copy:** `js\core\java-shim.js`
- **Only in source:** none (excluding `src\docs`)
- **Modified:** `index.html`, `history.html`, `settings.html`, `hardware.html`

Each modified HTML file differs by exactly one added line:

```html
<script src="js/core/java-shim.js"></script>
```

As of the 2026-11-19 theme fix it sits in `<head>`, immediately before
`js/core/theme-init.js` (see below); `window.__TAURI__` still exists before
backend selection runs at the end of `<body>`. `git diff --no-index
--unified=1` confirms there are no other line changes in those files. All
other 34 copied files are byte-identical to the source.

## New file: `js/core/java-shim.js`

- Inert unless `navigator.userAgent` contains `ThreatDetectorJavaFX`, which
  `App.java` appends. A normal browser keeps the original mock backend.
- Defines `window.__TAURI__.core.invoke(command, args)` and
  `window.__TAURI__.event.listen(name, callback)`.
- Java resolves/rejects invokes and emits events through `window.__tdShim`.
- Adds an `aspect-ratio` fallback (D12): if `CSS.supports("aspect-ratio",
  "1 / 1")` is false, `.shade-picker__square` gets an explicit pixel height
  from `offsetWidth`, refreshed by `ResizeObserver` and a `MutationObserver`.
  This fixes the collapsed 2 px shade square without touching product CSS.

## 2026-11-19: system theme following (fix 2)

Two real defects were found and fixed:

1. `theme: "system"` was stuck on light: JavaFX WebKit reports
   `(prefers-color-scheme: dark)` as light even when Windows is in dark app
   mode. The shim now owns that one query: initial value from the UA marker
   (`TDSystemDark`/`TDSystemLight`/`TDSystemUnknown`, read synchronously
   because `theme-init.js` resolves before any bridge call exists), then live
   updates via the new `get_system_theme` bridge command (Windows registry
   `AppsUseLightTheme`, polled every 3 s, change events fired to listeners).
   All other media queries pass through untouched; still a no-op outside
   JavaFX (same UA gate).
2. The last theme change could be lost when the window closed: `System.exit`
   fired before WebKit flushed its localStorage journal. `App.exit()` now
   waits 600 ms after `Platform.exit()`. Verified by running `--theme-debug`
   twice: the stored theme now survives the restart.

Shim edits in this fix: matchMedia patch + `window.__tdDiag` console/error
capture (feeds the `--theme-debug` dump). HTML edit: the shim `<script>` tag
moved from the end of `<body>` to `<head>`, immediately before
`js/core/theme-init.js`, so the patch is active during pre-paint theme
resolution. No CSS or page logic changed.

## 2026-11-19: performance fix (fix 3)

No front-end product files changed. The applied wins are Java-side: the D3D
pipeline is requested explicitly in `Launcher`, and `Bridge` coalesces queued
Java -> JS callbacks into one `executeScript` per FX pulse (order preserved).
The 40 ms progress throttle in the engine is unchanged, and reduced motion was
tested but not enabled because it showed no gain.

## 2026-11-19: bridge event subscriptions (perf step 1)

Two minimal JS edits, both inert outside the JavaFX shim:

- `js/core/events.js`: `TD.events.on/off` now report the listener count for the
  affected event to `window.__tdShim.demand(name, count)`. No change to how
  events are stored or dispatched; in a browser `__tdShim` is absent and the
  call is skipped. This is what lets Java skip `analysis:item-start` (no page
  listener) instead of serializing and delivering 500 unused events per batch.
- `js/core/java-shim.js`: tracks demand (`analysis:*` events are subscribed only
  when a page listener exists; other events when a direct `event.listen` exists),
  exposes `__deliver` (one JSON array per call) with an ordered,
  `requestAnimationFrame`-sliced queue (soft 6 ms budget, at least two messages
  per frame, unbounded drain at `analysis:batch-done`) and `__subs` for the
  initial subscription set. `event.listen`/unlisten update direct subscriptions.
  The old `resolve`/`reject`/`emit` entry points remain as thin wrappers.

No CSS, HTML, markup or page logic changed.

## 2026-11-19: batch list virtualization and DOM throttling (perf step 2)

Four front-end additions, active only when running inside the JavaFX host:

- `js/components/virtualList.js`: new component implementing a windowed view for
  long lists (`threshold: 60`, `overscan: 4`, row-height and gap measurement).
  Derived padding-top stands in for off-screen rows above the window, and a single
  filler element stands in for off-screen rows below the window, preserving the full
  scroll range and scrollbar geometry.
- `css/java-perf.css`: scoped under `html.is-javafx` (which `java-shim.js` sets at
  boot). Adds CSS layout/paint/style containment for repeated batch rows and
  recent table rows, plus an idle motion governor that pauses CSS animations only
  when the window is unfocused, minimized or hidden. Linked in the HTML pages.
- `js/pages/index.js`:
  1. Throttled the recent results table during batch streaming (debounced to at most
     once per 150 ms via `queueRecent`/`flushRecent` with the latest 5 items, plus a
     final flush on batch completion). Tally counts stay per-item live.
  2. Batch row template cloning (`rowTemplate` per risk shape, `fillBatchRow`)
     instead of repeated element creation.
  3. Integrated `virtualList` on `#batch-list` for batches exceeding 60 rows,
     retaining identical row markup, risk labels, input title, subtext, lazy rings,
     and ARIA attributes (`aria-posinset`, `aria-setsize`).
- `index.html`: added `<script src="js/components/virtualList.js"></script>` and
  `<link rel="stylesheet" href="css/java-perf.css">`. Other HTML pages link `java-perf.css`.

## What was not changed

No service adapter, no backend IPC protocol, no URL analysis or scoring rules,
and no settings/history behavior. Outside JavaFX (or with `?novirt=1`), all
standard behavior and DOM fallback paths remain intact.
