# Graded live agent series (E_AGT verify, 2026-10-04)

Tools of the live series in which isolated `claude -p` agents (skill `circuitjs-circuits` + `circuit_*` tools only, no `circuit_file`) built ten circuits of rising difficulty through the app's MCP server, each result re-measured and its drawing checked. Saved from the session scratchpad so a later session can repeat the series; they are diagnostics, not product code.

| File | Use |
|------|-----|
| `agent-run.mjs <id> <prompt-file> [--model sonnet] [--budget 4]` | One isolated agent run against `http://127.0.0.1:7311/mcp` (`CJS_URL`); transcript and `summary.json` under `$AGT_RUNS` (default `<tmp>/circuitjs-agent-series/<id>`). Spends model usage |
| `layout-check.mjs <doc> [--png out.png] [--json]` | Heuristic drawing check of a document: part overlap, wire through part, post inside part, label text, crossings (info), 4-way junctions, compressed/diagonal parts, ground/rail/source orientation, tight parallel spacing (< 3 cells), E-series values, non-polar ≥ 1 µF; renders a PNG at scale 2 |
| `mcp.mjs <tool> '<json>' [--png file]` | Minimal MCP client (bridge SDK); saves `circuit_render` PNGs, which the bridge CLI prints as `<image>` |
| `freqresp.mjs <doc> <srcId> <outNet> <step> f1,f2,…` | Gain at several frequencies (AC source `frequency` swept, `in`/`out` nets) |
| `single.mjs <types> <out.png>` | Renders one-post element types with 1-cell and 4-cell leads in four directions |
| `tasks/T1..T10.txt` | Task prompts (Ukrainian): divider, two LEDs, RC low-pass, bridge rectifier, zener + follower, CE amplifier, Sallen–Key, 555, 4-bit counter, buck |

Results of the first series (sonnet, 2026-10-04): electrical targets 10/10; drawing defects found and fixed in commits 709033a (symbol_overlap, short leads, IEC LED, no_ground, skill drawing rules) and model definitions PL_AGA Phases 11–14 (T2 re-run: agent-defined LED models). Still to re-run with the current build: T8, T9, T10. Open: `text_overlap` (value text crossed by a wire, T8) is not specified yet.
