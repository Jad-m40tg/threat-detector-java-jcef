# Migration status (Tauri/Rust -> Java/JavaFX)

| Area | Status | Notes |
|---|---|---|
| Core rules/CSV/engine | done | 31 JUnit tests, 562/562 Rust parity inputs. |
| IPC bridge + events | done | Frozen contract; see `IPC_CONTRACT.md`. |
| Front-end copy | done | Byte-identical except shim + 4 script tags (`FRONTEND_CHANGES.md`). |
| Desktop shortcut | done | `javaw -jar dist\ThreatDetector.jar`; verified launch. |
| Window geometry | fixed | Screen-aware sizing + saved-state validation; was fixed 1280x800 on a 1280x689 desktop. |
| Theme: explicit light/dark | fixed | Applies instantly, survives restart (exit flush grace); smoke-asserted. |
| Theme: "system" | fixed | Registry `AppsUseLightTheme` -> UA marker + `get_system_theme`; shim owns matchMedia(dark). |
| Perf measurement | done | `PERF_REPORT.md`: idle 18.3 ms, theme 17.4 ms, streaming 75.7 ms/frame (500 rows), scroll 38.8 ms/frame; startup 3.9-5.3 s. |
| Perf quick wins | done | Explicit D3D pipeline; coalesced Java -> JS events (1068 -> ~36 script calls/run, streaming 190 -> 76 ms). Reduced motion tested, rejected. |
| Native dark title bar | not planned | Needs JNI/user32; risk outweighs value (see `NEXT_STEPS.md`). |
