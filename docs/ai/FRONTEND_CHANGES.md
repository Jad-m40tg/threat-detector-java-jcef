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

## What was not changed

No CSS, no page logic, no service adapter, no HTML structure or data attributes,
no asset, and no settings/history behavior. The product UI and its mock backend
fallback remain intact.
