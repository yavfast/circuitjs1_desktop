# Live verification harness

`harness.mjs` drives a compiled GWT build in headless Chromium through the Chrome DevTools Protocol and the `window.CircuitJS1` automation API ([docs/JS_API.md](../../docs/JS_API.md)). It serves the build over a local static HTTP server on `127.0.0.1`, runs the scenarios, and never writes into the repository.

It exists because the defects it covers are invisible to a GWT compile and to an element-count comparison: undo that loads an empty circuit, paste that drops an element, JSON import that resets element parameters or geometry, and a text writer that rounds values.

## Requirements

- node 22 or later (built-in `WebSocket` and `fetch`)
- python3 (`http.server`)
- Chromium on `PATH` (override with `CHROMIUM=/path/to/chromium`)
- a compiled build: `npm run buildgwt` (writes `target/site/`)

## Run

```bash
npm run buildgwt
npm run test:live                                  # undo, paste, sliders, textfid, roundtrip, synth
node tests/live/harness.mjs undo paste             # a subset
CIRCUITS=all node tests/live/harness.mjs roundtrip # every bundled example (~340, a few minutes)
JSON_STATE=1 node tests/live/harness.mjs roundtrip # JSON export including simulation state
EVAL='CircuitJS1.getSimInfo()' node tests/live/harness.mjs eval   # ad-hoc probe
```

Each scenario prints one line, `PASS <name> {json}` or `FAIL <name> {json}`. Exit code: 0 when every scenario passes, 1 when any fails, 2 on a harness error.

| Variable | Default | Meaning |
|---|---|---|
| `SITE_DIR` | `target/site` | Site root containing `circuitjs.html` |
| `OUT_DIR` | `<os tmpdir>/circuitjs-live-harness` | Artifacts (overwritten on each run) |
| `CIRCUITS` | built-in list of 44 | Comma-separated example file names, or `all` |
| `JSON_STATE` | unset | Use `exportAsJsonWithState` for the JSON legs |
| `UNDO_CIRCUIT`, `PASTE_CIRCUIT` | `lrc.txt` | Circuits for the undo and paste scenarios |
| `CHROMIUM`, `HTTP_PORT`, `CDP_PORT` | `chromium`, free ports | Browser binary and fixed ports |
| `VERBOSE`, `LOAD_TIMEOUT_MS` | unset, 60000 | Logging and app-load timeout |

## Scenarios

| Name | Checks |
|---|---|
| `undo` | Delete an element, Ctrl+Z restores it (text export equal), Ctrl+Y re-deletes. Keyboard events via CDP. |
| `paste` | Ctrl+A + Ctrl+D and Ctrl+A + Ctrl+C + Ctrl+V double the element count; undo of the duplicate restores it. |
| `sliders` | A pot, LDR, NTC, variable rail and audio output: the Sliders dialog shows 4 sliders + 1 "Play" button after load, 8 + 2 after Ctrl+A/Ctrl+D (and 8 adjustable `38` lines), 4 + 1 after undo, 3 + 0 after deleting the pot and the audio output. |
| `textfid` | Raw example file → text import → text export, compared number by number per element line (flags field excluded). Catches a lossy text writer. |
| `roundtrip` | Text import → export text T1 and JSON J1 → import J1 → export T2 and J2. Reports element count, element class, text line and JSON property differences, and import log warnings. |
| `synth` | Creates one default element of every JSON type name found in the build and runs the same JSON/text legs. |
| `eval` | Evaluates `EVAL` in the page and prints the result, console and exceptions. |

## Reading the results

`OUT_DIR` holds `results.json`, `console.log`, `exceptions.log` and per-scenario details (`roundtrip/`, `roundtrip_summary.json`, `roundtrip_textdiff_by_type.txt`, `textfidelity.json`, `synth_summary.json`).

Known, accepted differences (a scenario can still report FAIL because of them):

- `roundtrip` without `JSON_STATE`: capacitor/inductor/chip/relay lines differ in simulation-state fields, which a JSON export without state omits by design.
- `roundtrip` class deltas such as `RailElm → ACRailElm`, `TransistorElm → NTransistorElm`: equivalent subclasses with an identical text dump.
- JSON `bounds` right/bottom differ between J1 and J2: the bounding box is informational and is recomputed on the next draw; element geometry comes from the pins.
- `textfid`: transistor and gate state fields (last junction/output voltages) are reset on load, legacy `WF_VAR` rails are written as DC, legacy pulse duty cycles are migrated, and a legacy capacitor token without the series-resistance flag is ignored — the same behavior as the original CircuitJS1.
