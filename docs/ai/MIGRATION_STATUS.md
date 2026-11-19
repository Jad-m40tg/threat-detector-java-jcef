# Migration status (Tauri/Rust -> Java/JavaFX)

| Area | Status | Notes |
|---|---|---|
| Core rules/CSV/engine | done | 31 JUnit tests, 562/562 Rust parity inputs. |
| IPC bridge + events | done | Frozen contract; see `IPC_CONTRACT.md`. |
| Front-end copy | done | Byte-identical except shim + 4 script tags (`FRONTEND_CHANGES.md`). |
| Desktop shortcut | done | `javaw -jar dist\ThreatDetector.jar`; verified launch. |
| Window geometry | fixed | Screen-aware sizing + saved-state validation; was fixed 1280x800 on a 1280x689 desktop. |
| Theme: explicit light/dark | verified | Applies instantly, survives reload (smoke-asserted). |
| Theme: "system" | in progress | WebView always reports light; Java must expose the Windows app theme. |
| Perf measurement | pending | `PERF_REPORT.md`; measure idle/theme/scroll/batch frame times. |
| Native dark title bar | not planned | Needs JNI/user32; risk outweighs value (see `NEXT_STEPS.md`). |
