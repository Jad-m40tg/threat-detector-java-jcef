# AGENTS.md - Threat Detector (Java)

This repository is the Java/JavaFX port of `DAC_Tauri`. The Rust project remains
the behavioral source of truth; this project must stay a faithful translation.

## Read-only sources

`C:\Users\ASUS Vivo\Desktop\DAC_Tauri` and `DAC_Frontend` are reference inputs.
Never edit, move or delete anything inside them.

## Non-negotiables

1. **NotVerified never becomes Low or safe.** `RiskLevel` has no unverified
   value. A `Verdict` is either `Assessed` or `NotVerified(reason)`. Only
   `ItemResult.toIpcShape` may build the wire result.
2. **Offline only.** Never fetch, resolve, ping or open an analyzed URL. The
   backend inspects strings and structure only. The loopback HTTP server serves
   only the app's own extracted assets.
3. No `unsafe`, no panics across the IPC boundary, no macro-heavy abstractions.
4. Limits: URL 2,048 UTF-8 bytes, message 4,096 UTF-8 bytes, CSV 1 MB and 5,000
   data rows. Over-limit input becomes a typed `NotVerified` or file error.
5. Keep capabilities/CSP minimal. The WebView reaches Java only through
   `window.__tdJavaBridge`; there is no shell or filesystem bridge.
6. The copied front-end changes only through `js/core/java-shim.js` and its four
   `<script>` tags. Record any such change in `docs/ai/FRONTEND_CHANGES.md`.

## Build and verify

```bat
mvn -q clean verify        :: 31 tests
run-checks.bat             :: tests + smoke + compat audit
build-release.bat          :: dist\ThreatDetector.jar
```

`run-checks.bat` must end with `CHECKS PASSED`. The differential test compares
562 inputs against Rust goldens committed in
`core/src/test/resources/golden/rust_golden.jsonl`.

## Fidelity protocol

- Rules, weights, thresholds, detection order, error-to-NotVerified mapping and
  IPC field names/shapes come from the Rust code, not from intuition.
- If you change a rule or parser behavior, regenerate the goldens in a temporary
  copy of `threat-core` with `serde_json` added. Never run generators inside
  `DAC_Tauri` and never modify its `Cargo.toml`.
- Keep `docs/ai/TEST_MATRIX.md`, `DECISIONS.md` and `PROGRESS.md` current.

## Layout

- `core/` pure Java: models, validator, URL inspector/rules, message rules,
  scorer, CSV parser, engine, cancellation, batch registry.
- `app/` JavaFX: `WebHost`, `Bridge`, `BridgeSink`, `App`, `Launcher`.
- `app/src/main/resources/web/` copied product UI (39 files).
- `docs/ai/` handoff memory. `docs/original/` upstream docs.

## Current status

Complete and verified: G1-G5 pass, shortcut launches the released jar and logs
`READY page loaded`. See `docs/ai/HANDOFF.md` before changing anything.
