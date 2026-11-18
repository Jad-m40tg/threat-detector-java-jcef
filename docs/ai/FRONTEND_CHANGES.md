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

It is inserted immediately after `js/core/core.js` and before
`js/core/td-config.js`, so `window.__TAURI__` exists before backend selection.
`git diff --no-index --unified=1` confirms there are no other line changes in
those files. All other 34 copied files are byte-identical to the source.

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

## What was not changed

No CSS, no page logic, no service adapter, no HTML structure or data attributes,
no asset, and no settings/history behavior. The product UI and its mock backend
fallback remain intact.
