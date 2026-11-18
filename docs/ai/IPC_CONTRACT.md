# IPC contract - Threat Detector (Java)

This is the Java bridge contract. It preserves the frozen Rust/Tauri contract
shipped in `DAC_Tauri/docs/IPC_CONTRACT.md`: same commands, argument fields,
event names, payload shapes, and `NotVerified` invariants. Differences are listed
at the end.

## Transport

The copied front-end still calls `window.__TAURI__.core.invoke(command, args)`
and subscribes with `window.__TAURI__.event.listen(name, handler)`. In JavaFX,
`js/core/java-shim.js` implements those two functions on top of:

```text
window.__tdJavaBridge.call(callId, command, jsonArgs)   Java entry point
window.__tdShim.resolve(callId, jsonString)             reply value
window.__tdShim.reject(callId, message)                 command failure
window.__tdShim.emit(eventName, payloadJson)            streaming event
```

`call()` runs on the JavaFX thread. Work is handed to virtual threads; replies
and events return through `Platform.runLater`, so delivery order is preserved.
The bridge is only attached after `READY page loaded`; events emitted before
that are dropped.

Argument keys are camelCase (`inputType`, `minItemDurationMs`, `batchId`).
Rejections are plain descriptive strings, never JSON risk objects. Any analyzed
item that cannot be verified is returned as a normal result with
`riskLevel: "NotVerified"` - command failure is reserved for unusable requests
(missing fields, unreadable file, oversized file, malformed JSON).

## Commands

### `analyze_item`

```json
{ "request": { "id": "1", "inputType": "url", "content": "https://example.com" } }
```

- `id` optional; absent becomes `null` in the result. The page normally supplies
  a string.
- `inputType` optional. Only exact `"url"` or `"message"` select a parser;
  every other value (including `"URL"`) is treated as absent and auto-detected,
  matching Rust `parse_input_type`.
- `content` required. Missing content rejects with `missing field \`content\``.
- Returns one `IpcAnalysisResult`.

### `analyze_csv`

```json
{ "path": "C:\\data\\rows.csv", "options": { "concurrency": 8, "minItemDurationMs": 0 } }
```

- Parses the file, then replies with `{ "batchId": "batch-...", "total": N }`
  and streams the batch events. The reply always precedes the first item event,
  so the page can set `activeBatchId` before events arrive.
- File errors (I/O, >1 MB, >5,000 data rows) reject the command; they do not
  produce a partial batch.
- `options` may be omitted or null. `concurrency` defaults to 8 and is clamped
  to 1..32; `minItemDurationMs` defaults to 0 and is clamped to >= 0.

### `analyze_csv_content`

```json
{ "name": "pasted.csv", "content": "id,input_type,content\n1,url,https://..." }
```

Same as `analyze_csv`, but parses an in-memory string. `name` is only a label
for parser error messages. Both fields are required.

### `cancel_batch`

```json
{ "batchId": "batch-1795041268619" }
```

Returns `true` if the batch exists and the cancellation token was signalled,
otherwise `false`. Cancellation is cooperative. Already-cancelled current items
complete as `NotVerified` with `error.kind = "BatchCancelled"`; queued items are
not started. `batch-done` still fires with `count` equal to the number of results
actually delivered.

### `pick_csv`

No arguments. Shows the native file chooser on the JavaFX thread.
Returns the absolute path string, or `null` if the user cancels. Cancelling is
not an error.

## Events

| Event | Payload |
|---|---|
| `analysis:item-start` | `{ "id": "1", "input": "..." }` |
| `analysis:item-done` | `{ "input": "...", "result": IpcAnalysisResult }` |
| `analysis:progress` | `{ "batchId": "...", "done": 3, "total": 10, "pct": 30 }` |
| `analysis:batch-done` | `{ "batchId": "...", "count": 10, "results": [IpcAnalysisResult...] }` |

Ordering guarantees:

- One `item-start` and one `item-done` per started item; start precedes done.
- Progress is throttled to at most one event per 40 ms, plus a guaranteed final
  event where `done == total` (for cancelled batches, `total` is the parsed row
  count and `done` is what was delivered).
- `batch-done` is the last event of a batch and carries every delivered result.
- Items that were never started (cancellation) have no start/done pair.

## `IpcAnalysisResult`

Exact field order and nullability match Rust:

```json
{
  "id": "1",
  "inputType": "url",
  "input": "https://example.com",
  "riskLevel": "Low",
  "risk": "low",
  "state": "SAFE",
  "score": 12,
  "status": "verified",
  "category": "Legitimate URL",
  "details": "Valid protocol and standard structure; clean inspection.",
  "reasons": ["No suspicious heuristic patterns detected."],
  "triggeredRules": [{ "id": "http_protocol", "points": 2, "description": "..." }],
  "recommendation": "Standard precautions apply. No immediate risk indicators found.",
  "error": null,
  "durationMs": 0,
  "ts": 1000
}
```

- `riskLevel`: `Low`, `Medium`, `High`, or `NotVerified`.
- `risk`: `low`, `medium`, `high`, or `unverified` (the historical Rust wire
  spelling; kept for UI compatibility, never a claim of safety).
- `state`: `SAFE`, `THREAT`, or `NOT_VERIFIED`.
- `score`: display score `min(points * 15, 99)`; clean URLs use 12. `null` for
  `NotVerified`.
- `status`: `verified` or `not_verified`.
- `triggeredRules`: ordered rule hits with the English `description`.
- `durationMs`: wall-clock analysis time; excluded from parity comparison.
- `ts`: epoch milliseconds supplied at conversion time.

## Invariants

1. `riskLevel = "NotVerified"` implies `risk = "unverified"`,
   `state = "NOT_VERIFIED"`, `score = null`, `status = "not_verified"`, and a
   non-null `error` with `{ kind, message }`. It never carries Low/Medium/High.
2. A `NotVerified` result is never dropped from a batch; failures are isolated
   per item and reported as `NotVerified(ExecutionPanicked)`.
3. `error.kind` values mirror Rust `NotVerifiedReason`:
   `EmptyInput`, `WhitespaceOnly`, `TooLong`, `MalformedUrl`, `UnsupportedScheme`,
   `CsvRowInvalid`, `CsvFileTooLarge`, `CsvTooManyRows`, `IoError`,
   `BatchCancelled`, `ExecutionPanicked`.
4. No command or event contains a visited URL, HTTP response, DNS result or
   network timing. Analysis is pure string/structure inspection.

## Intentional differences from the Rust build

| Topic | Rust build | Java build | Why |
|---|---|---|---|
| Batch reply timing | `commands.rs` awaits the batch, then returns after completion. | Parses (bounded) and replies `{batchId,total}` before streaming. | Follows the frozen written contract and lets the page reject stale events. The page handles both. |
| `batchId` value | Tauri-generated batch identity. | `"batch-" + epochMillis`. | Opaque to the UI; uniqueness is sufficient. |
| Cancelled progress | Depends on scheduling. | Final progress uses parsed total; `done` counts delivered results. | Keeps the contract's "final state guaranteed" rule. |
| `pick_csv` | Tauri dialog plugin. | JavaFX `FileChooser`, `*.csv` filter. | No plugin system in JavaFX; same path/null result. |

Everything else - rule IDs, weights, thresholds, categories, reason ordering,
error mapping and JSON names - is translated 1:1 and locked by
`core/src/test/java/.../ParityTest.java` (562 Rust golden inputs).
