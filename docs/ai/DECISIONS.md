# Decisions

Each entry records a choice, the reason, and the consequence. Rust remains the
behavioral specification; deviations are called out explicitly.

## D1 - JDK 22 + JavaFX 22.0.2 (non-LTS)

Only JDK 22.0.2 was already installed; installing another JDK was not approved.
The parent POM targets `maven.compiler.release=22`, and OpenJFX 22.0.2 matches
the runtime. Virtual threads require 21+, so 22 supports the chosen concurrency
model. This is a non-LTS runtime; moving to JDK 21 LTS later is a POM/runtime
change, not a source rewrite.

## D2 - Two Maven modules

`core` is pure Java (models, validation, rules, scoring, CSV, engine) with zero
JavaFX dependencies. `app` is a thin JavaFX shell (WebView, bridge, host). This
keeps rule tests fast and prevents UI types from leaking into analysis logic.

## D3 - Rust enums as Java sealed types

Rust enums with data (`Verdict`, `NotVerifiedReason`) become sealed interfaces
with one record per variant. `RiskLevel` deliberately has only LOW/MEDIUM/HIGH.
There is no constructible state in which an unverified input carries a risk
level, so the invariant is enforced by types, not by convention.

## D4 - One error-to-NotVerified mapping

`ThreatException.Kind` mirrors `ThreatError`. `toNotVerifiedReason()` maps every
kind to a typed reason: empty/whitespace/oversized/malformed URL/CSV row/I/O,
cancelled and execution-panicked. Bridges convert unexpected `Throwable`s to
`ExecutionPanicked`; no failure path defaults to Low.

## D5 - UTF-8 byte limits

Rust `str.len()` counts UTF-8 bytes. Java uses
`string.getBytes(StandardCharsets.UTF_8).length`, so unicode inputs hit exactly
the same 2,048/4,096-byte limits. CSV limits stay 1 MB and 5,000 data rows.

## D6 - Custom offline URL inspector

`java.net.URI` is stricter and normalizes differently from Rust's `url` crate.
`UrlInspector` implements the subset the rules consume: scheme, authority,
userinfo, host, port, default-port removal, IDN via `java.net.IDN`, and a
normalized string for the `long_url` rule. It performs no DNS or network access.
The 562-input differential test proves equivalence for the shipped corpus and
edge cases.

## D7 - CSV parser: line-oriented, two dialects

`CsvParser` uses Commons CSV per line to mirror the Rust parser's flexible-width,
per-record behavior. It recognizes the typed `id,input_type,content` dialect and
the plain first-column dialect, consumes a header only when recognized, tolerates
BOM/blank lines/CRLF, and turns bad rows into typed `NotVerified` items. File
size and row-count violations abort the file with a typed error.

## D8 - Virtual threads + fair semaphore

A virtual-thread executor submits item tasks; a fair `Semaphore` bounds active
analysis to `concurrency` (default 8, capped 32). An
`ExecutorCompletionService` feeds a single aggregator thread that owns all
counters, so there is no mutex on batch state. Progress is throttled to one
update per 40 ms, with a final update when the last item of a complete batch
lands; cancelled batches end with `batch-done`, whose count is authoritative.
This preserves the Rust semaphore/aggregator semantics; the scheduler differs
but observable bounds and event ordering do not.

## D9 - Batch reply timing

The frozen IPC contract says `analyze_csv` / `analyze_csv_content` return
immediately with `{batchId,total}`. The Rust code currently awaits the batch and
returns afterwards. The Java bridge follows the contract and resolves the
promise before streaming events, which lets the page set `activeBatchId` early
and reject stale progress/done events. The page handles both timings.

## D10 - Loopback HTTP host instead of file://

The front-end is extracted to `%APPDATA%\ThreatDetector\web` and served from
`http://127.0.0.1:47821/` (ephemeral port if busy). A stable HTTP origin gives
WebView durable localStorage for history and settings; `file://` origins are
opaque in WebKit. The server binds loopback only and serves only extracted app
assets.

## D11 - Tauri shim

`js/core/java-shim.js` defines only the API the product uses:
`__TAURI__.core.invoke`, `__TAURI__.event.listen`, and internal
resolve/reject/emit entry points. It activates only when the user agent contains
`ThreatDetectorJavaFX`; in a normal browser the original mock backend remains
selected. Java calls `window.__tdShim.*` on the FX thread.

## D12 - aspect-ratio fallback lives in the shim

JavaFX 22 WebKit does not support `aspect-ratio`. `.shield-wrap` already has
explicit width and height, but `.shade-picker__square` collapsed to a 2 px strip.
The shim detects missing support and sizes any `.shade-picker__square` from its
`offsetWidth`, observing resize and DOM mutations. The copied front-end CSS is
untouched.

## D13 - Jackson for JSON

The bridge uses Jackson databind only: records serialize with their component
names, which are already camelCase (`batchId`, `triggeredRules`, `durationMs`).
Nulls are emitted explicitly where Rust emits `null` (for example `score` and
`error`). No custom serializers or field-order tricks are used.

## D14 - Shaded jar with a plain Launcher

`maven-shade-plugin` produces `threat-detector-java-1.0.0-all.jar` with
`Launcher` as `Main-Class`. Launching through a non-`Application` class avoids
the "JavaFX runtime components are missing" error. `javafx-swing` is included
only for `SwingFXUtils` screenshot capture; it is not part of the UI.

## D15 - Logging without a framework

`java.util.logging` writes `%APPDATA%\ThreatDetector\app.log` (1 MB cap, append).
This gives the shortcut a durable `READY page loaded` breadcrumb without adding a
logging dependency. Console logging remains for development.

## D16 - Shortcut targets javaw directly

The first shortcut targeted `wscript.exe` + `launch.vbs`; security software
removed it. The supported shortcut now targets `javaw.exe -jar
dist\ThreatDetector.jar`, which is a normal Java application launch.
`tools\launch.vbs` remains available for users who prefer it.

## D17 - Rust oracle in a temporary copy

Goldens are generated by copying `crates/threat-core` to
`%TEMP%\dac_rust_oracle`, adding `serde_json` there, and running an example. The
original `DAC_Tauri` tree is never modified. The resulting JSONL is committed
under `core/src/test/resources/golden/`.

## D18 - No installer

The requested delivery is a Desktop shortcut. `build-release.bat` plus
`tools/create-shortcut.ps1` produce that; `jpackage`/MSI was not required and
would add a second packaging toolchain.
