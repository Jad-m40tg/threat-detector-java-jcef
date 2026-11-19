# Test matrix and gate evidence

Last full gate run: 2026-11-18 (local). Status: **G1-G5 PASS**.

## G1 - Dataset levels, scores and rules

`DatasetTest.tenRowDatasetAcceptance` parses
`core/src/test/resources/project_test_dataset.csv` through the Java CSV parser
and engine, and asserts the exact table below. Scores are display scores
(`min(points * 15, 99)`, clean = 12). Rule order is the emitted order.

| Row | Level | Score | State | Triggered rule IDs |
|---|---|---|---|---|
| 1 | Low | 12 | SAFE | - |
| 2 | High | 90 | THREAT | `http_protocol`, `ip_host` |
| 3 | High | 99 | THREAT | `http_protocol`, `nested_subdomains`, `suspicious_keyword` |
| 4 | High | 99 | THREAT | `http_protocol`, `authority_userinfo`, `suspicious_keyword` |
| 5 | Medium | 30 | THREAT | `http_protocol` |
| 6 | Low | 15 | SAFE | `action_request` |
| 7 | High | 99 | THREAT | `urgency_language`, `suspension_threat`, `account_reference`, `embedded_http_protocol`, `embedded_ip_host` |
| 8 | Medium | 30 | THREAT | `action_request`, `account_reference` |
| 9 | NotVerified | null | NOT_VERIFIED | - |
| 10 | NotVerified | null | NOT_VERIFIED | - |

Totals: 2 Low, 2 Medium, 4 High, 2 NotVerified. Rows 9 and 10 also assert
`riskLevel=NotVerified`, `risk=unverified`, `state=NOT_VERIFIED`, `score=null`,
and no Low/Safe claim in `InvariantsTest`.

## G2 - Every Rust test has a JUnit twin

`cargo test -- --list` in the oracle copy lists 23 Rust tests. All 23 have a
same-intent JUnit test. The captured list is
`docs/ai/evidence/rust-test-list.txt`.

| Rust suite | Rust | Java twin | Java |
|---|---|---|---|
| `url_rules::tests` | 8 | `UrlRulesTest` | 8 |
| `csv_parser::tests` | 8 | `CsvParserTest` | 8 twins + 1 fixture test |
| `dataset_test::test_ten_row_dataset_acceptance` | 1 | `DatasetTest` | 1 |
| `engine_test` | 4 | `EngineTest` | 4 |
| `invariants_test` | 2 | `InvariantsTest` | 2 |

Name map:

- URL: `http_flagged_https_not`, `ip_host_flagged_and_not_nested`,
  `nested_subdomains_on_domains_only`, `userinfo_at_flagged`, `punycode_flagged`,
  `long_url_flagged`, `shortener_flagged_exact_and_subdomain_not_lookalike`,
  `nonstandard_port_flagged_default_ports_not`.
- CSV: `plain_dialect_reads_the_app_sample_format`,
  `plain_dialect_auto_detects_messages`,
  `typed_dialect_reads_the_dataset_format`,
  `headerless_typed_file_keeps_its_first_row`,
  `headerless_plain_file_is_sniffed`,
  `row_limit_is_a_typed_error_not_truncation`,
  `header_only_and_empty_files_are_typed_errors`,
  `blank_lines_and_bom_are_tolerated`.
- Engine: `batch_delivers_every_item_and_final_progress`,
  `pre_cancelled_batch_starts_nothing`,
  `cancel_mid_batch_stops_early_and_closes_cleanly`,
  `rapid_cancel_then_fresh_batch_is_independent`.
- Invariants: `test_not_verified_invariants_sweep`,
  `test_oversized_input_invariant`.

Java adds beyond the Rust suite: `BatchRegistryTest` (1),
`EngineConcurrencyTest` (3), `ParityTest` (1), `InputTypeWireTest` (2), and the
`bad_rows.csv` fixture test (1). Current total: **31 tests in 9 suites, 0
failures**.

```bat
mvn -q clean verify
```

## G3 - 200+ input differential test against Rust

Goldens were generated from the unmodified Rust `threat-core` in a temporary
copy (`%TEMP%\dac_rust_oracle`, with `serde_json` added there only):

```text
cargo run --release --example dump -- <dataset.csv> <stress_500.csv> unused rust_golden.jsonl
```

- 10 dataset rows + 500 stress rows + 52 hand-written edge cases = **562 inputs**.
- `ParityTest` runs each input through the Java engine and compares 15 IPC fields:
  `id, inputType, input, riskLevel, risk, state, score, status, category,
  details, reasons, triggeredRules, recommendation, error, ts`.
- Only `durationMs` is excluded (wall-clock measurement).
- Result: **562/562 exact matches**; the test fails with a field-level diff list
  and requires at least 500 inputs.
- Coverage includes unicode/IDN, punycode, IPv4/IPv6, userinfo, default and
  non-standard ports, shorteners, long URLs, `file/ws/wss/ftp`, empty and
  oversized inputs, malformed schemes, and message/embedded-URL rules.

Goldens: `core/src/test/resources/golden/rust_golden.jsonl`.

## G4 - Concurrency, cancellation and failure isolation

`EngineConcurrencyTest`:

- `concurrencyNeverExceedsTheLimitButDoesRunInParallel`: 40 items, limit 4;
  asserts peak active work `<= 4` and `> 1`.
- `oneThrowingItemBecomesNotVerifiedAndTheBatchStillCompletes`: 12 items, one
  analyzer throws; asserts all 12 delivered, 11 assessed, the bad one is
  `NotVerified(ExecutionPanicked)`, and `batch-done` count is 12.
- `everyStartPrecedesItsDoneAndBatchDoneIsLast`: 60 items; asserts one start and
  one done per item, start before done, final progress reports 60, and
  `batch-done` is the last event.

`EngineTest` also covers: every item delivered with monotonic final progress,
pre-cancelled batch starts nothing, mid-batch cancel stops early and closes with
`count == delivered`, and a fresh batch after cancel is independent.

```bat
mvn -q -pl core test -Dtest='EngineTest,EngineConcurrencyTest'
```

## G5 - Smoke test

```bat
java -jar app\target\threat-detector-java-1.0.0-all.jar --smoke-test
```

The smoke script calls the real front-end adapter path
`TD.services.analysis.analyzeCsvFile`, not a direct `invoke`, and listens on
`TD.events`:

- `backend=tauri` (proves the Tauri shim/adapter path was selected).
- `count=10`.
- `levels={Low=2, Medium=2, High=4, NotVerified=2}`.
- `ok=true`, exit code 0.
- Screenshot captured to `screenshot-index.png` and recorded in
  `smoke-test-report.json`.
- Report also records dataset path, duration, batch ID, user agent and every
  result.

Latest run: `SMOKE PASS count=10 levels={Low=2, Medium=2, High=4,
NotVerified=2} backend=tauri windowOk=true themeOk=true`, ~7 s (includes the
two theme reloads). The report also asserts that the shown window sits inside
the primary visual bounds, and that dark mode yields `data-theme=dark` with a
low-luminance body while light yields `data-theme=light` with a bright body.

## Performance harness

```bat
java -jar app\target\threat-detector-java-1.0.0-all.jar --perf
```

Drives the page's real file-drop path with the 500-row `stress_500.csv`
fixture, samples `requestAnimationFrame` deltas, and writes
`perf-report.json`. Phases: idle, theme switch, 500-row streaming, verdict
render, 500-row list scroll, plus startup timings and bridge script counts.
`--perf --perf-reduced` runs the same A/B with reduced motion forced.
Median results and interpretation: `docs/ai/PERF_REPORT.md`.

## WebView compatibility audit

```bat
java -jar app\target\threat-detector-java-1.0.0-all.jar --compat-audit
```

WebKit 617.1 (JavaFX 22), localStorage available. Unsupported checks and impact:

| Feature | Supported | Impact / mitigation |
|---|---|---|
| `:has()` | no | Not used by the copied UI. |
| `content-visibility` | no | Performance hint only; batch rows still render. |
| `aspect-ratio` | no | Shade square fixed in the shim (D12); shield has explicit size. |
| `backdrop-filter` | no | Cosmetic blur only. |

`color-mix`, container queries, subgrid, `dvh/svh`, modern JS (optional
chaining, nullish, class fields, structuredClone, Resize/IntersectionObserver,
crypto.randomUUID) all pass.

The audit exits non-zero if `localStorage` is unavailable, because history and
settings depend on it. Unsupported CSS features are reported but do not fail the
gate; their impact is either absent or mitigated in the shim.

## One-command gate

```bat
run-checks.bat
```

Runs `mvn clean verify`, the smoke test, and the compatibility audit. Expected
last line: `CHECKS PASSED`.
