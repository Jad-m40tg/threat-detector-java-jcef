# Gate evidence

Curated outputs from the final verification run. Regenerate with
`run-checks.bat`; the root-level reports and screenshots are ignored by git and
copied here for the handoff.

| File | Source | What it proves |
|---|---|---|
| `checks.log` | `run-checks.bat` | Maven tests, smoke result and compat audit; ends `CHECKS PASSED`. |
| `test-summary.txt` | Surefire XML after the final build | Per-suite and total JUnit counts: 31 tests, 0 failures, 562 golden inputs. |
| `frontend-audit.txt` | SHA-256 tree comparison | 38 source / 39 copy files; only the shim is added, only four HTML files differ (one script line each). |
| `rust-test-list.txt` | `cargo test -- --list` in the oracle copy | The 23 Rust tests mapped to same-intent JUnit twins (G2 provenance). |
| `smoke-test-report.json` | `--smoke-test` | Real adapter path (`backend=tauri`), 10 rows, 2 Low / 2 Medium / 4 High / 2 NotVerified, per-item results. |
| `compat-report.json` | `--compat-audit` | WebKit 617.1 feature matrix, localStorage availability, frame timing. The audit fails if localStorage is unavailable. |
| `screenshot-index.png` | smoke / screenshot mode | Dashboard render in JavaFX WebView. |
| `screenshot-history.png` | `--screenshot --page=history.html` | History page render. |
| `screenshot-settings.png` | `--screenshot --page=settings.html` | Settings page render. |
| `screenshot-hardware.png` | `--screenshot --page=hardware.html` | Hardware monitor render. |
| `screenshot-shade-picker.png` | settings + `--click=#vs-row-light button` | Verdict shade modal; square is square despite missing `aspect-ratio`. |
| `shortcut-launch.log` | Desktop shortcut launch | `READY page loaded` from the packaged `dist\ThreatDetector.jar`. |

The Rust oracle (562 JSONL rows) is committed at
`core/src/test/resources/golden/rust_golden.jsonl`; it is an input to
`ParityTest`, not repeated here.
