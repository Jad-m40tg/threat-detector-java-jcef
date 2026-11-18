# GEMINI.md

Read and follow `AGENTS.md` first. In short:

- `DAC_Tauri` and `DAC_Frontend` are read-only reference sources.
- `NotVerified` must never become Low or safe.
- Never fetch or visit an analyzed URL; analysis is offline string inspection.
- Do not edit the copied front-end except `js/core/java-shim.js` and its four
  script tags; record edits in `docs/ai/FRONTEND_CHANGES.md`.
- Run `run-checks.bat` before claiming success; it must end `CHECKS PASSED`.
- Rust behavior is the specification. Regenerate goldens in a temp copy only.
- Current status and next steps: `docs/ai/HANDOFF.md`.
