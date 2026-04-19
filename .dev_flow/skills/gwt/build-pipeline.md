---
skill: build-pipeline
domain: gwt
topics: [maven, gwt-compile, npm, nw-builder, packaging, java17]
source: onboard
updated: 2026-04-18
---

# Build Pipeline

## Context

circuitjs1 desktop is a dual-toolchain project: **Maven + GWT** compiles
Java to browser JavaScript; **npm + nw-builder** wraps the result into a
NW.js native desktop app. You must understand both halves before touching
`pom.xml` or `scripts/dev_n_build.js`.

## Key concepts

**Language level.** Java 17 source/target per `pom.xml` (RULE_STYLE_001).
GWT 2.12.2 is the compiler; it supports Java 17 syntax but emulates a
**subset** of the JDK. Forbidden: `java.awt.*`, `java.io.*`, threads,
reflection, `java.nio.*` (RULE_STYLE_002).

**Two build artifacts.**
1. `war/circuitjs1/*.cache.js` — the GWT-compiled module, one file per
   permutation (usually one locale + user-agent combo after GWT
   optimization). Loaded by `war/circuitjs.html`.
2. `out/` — NW.js desktop bundles (Linux/Win/Mac packaged by `nw-builder`).

**npm scripts** (`package.json`):

| Script | Purpose |
|---|---|
| `npm run dev` | Default dev flow (node `scripts/dev_n_build.js`) |
| `npm run devmode` | GWT devmode / SuperDevMode — recompile-on-refresh loop for Java iteration |
| `npm run check` | `--checksteps`: compile-only sanity check; use before committing Java changes (RULE_TEST_001) |
| `npm run buildgwt` | GWT compile only, populates `war/` |
| `npm run build` | `--buildall`: full build including NW.js packaging to `out/` |
| `npm run full` | `--fullrebuild`: clean + full |
| `npm run start` | `--rungwt`: launch devmode server without rebuild |

**GWT compile cost is the bottleneck.** A full compile is ~minutes; a
devmode recompile is seconds. Prefer `npm run devmode` for inner-loop
work; run `npm run check` before commit.

**JSNI is checked at GWT-compile time**, not Maven-compile time — many
bugs only surface during `buildgwt`, not during IDE/javac.

## Usage in this project

- Release version lives in `circuitjs1.java:39` (`versionString =
  "3.1.3js"`); bump on release (the value ends up inside exported
  `.circuitjs.txt` files).
- `shortRelaySupported = false` (`circuitjs1.java:43`) — compile-time
  flag for URL-shortened circuit sharing, disabled in this fork.
- The locale catalog is **fetched at runtime** (`circuitjs1.java:110-134`)
  from `GWT.getModuleBaseURL() + "locale_<lang>.txt"`, not baked into the
  compile. Translations live under `war/circuitjs1/`.
- `circuitjs.html` hosts the compiled module and the Dropbox SDK
  `<script>` required for ImportFromDropbox JSNI (see `jsni-patterns.md`).

## Pitfalls

1. **Do not add dependencies that pull `java.io.*` or reflection.** They
   compile via Maven but fail at GWT-compile time, often with cryptic
   `Line X: The method Y is not emulated` errors.
2. **`npm run check` is the minimum gate** (RULE_TEST_001). A
   Java-level compile error will not be caught by `mvn compile` alone
   because GWT's translator is stricter.
3. **Locale fetch can silently fail at runtime** (`circuitjs1.java:50-53`
   `onError` only logs). If a translation file is missing after a
   release, non-English users see raw keys.
4. **NW.js version is pinned** (`nw: "0.64.1-sdk"`, `nw-builder: "4.6.4"`
   in `package.json`). Upgrading requires verifying Web Audio and
   clipboard JSNI adapters (they touch browser APIs that differ between
   Chromium versions).
5. **Devmode verification requirement** (RULE_TEST_002, RULE_TEST_005):
   after simulator-core or editor changes, exercise an analog, a
   digital, and a subcircuit example in devmode — a GWT compile alone is
   insufficient.

## References

- `package.json` (scripts, devDeps)
- `pom.xml` (Java 17, GWT 2.12.2)
- `scripts/dev_n_build.js` (all npm entry points route here)
- README build section
- `.dev_flow/onboard/analysis/layer3__app-entry.md` §"onModuleLoad flow"
- Rules: RULE_STYLE_001, RULE_STYLE_002, RULE_TEST_001, RULE_TEST_002, RULE_TEST_005
