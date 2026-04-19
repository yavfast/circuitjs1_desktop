# Epic: Domain Core — Circuit Elements, Edit UI & Serialization  {#E_DOMAIN_CORE}

> **Code:** E_DOMAIN_CORE
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard

## Purpose

The widest family of concepts in the codebase: everything that defines *what a
circuit is* — the element base contract, all 14 element categories, shared
parameter models, waveform generators, the per-element edit-dialog pipeline,
and the pluggable serializer that turns a circuit into text/JSON and back.
Together these concepts own the authoritative in-memory representation that
the simulator consumes and the editor manipulates.

## Member Concepts

### Foundations
- [C_ELB](element-base.concept.md) — abstract `CircuitElm` contract unifying simulation, topology, rendering, edit, persistence
- [C_WFM](waveforms.concept.md) — strategy-pattern V(t) signal generators for voltage-source elements
- [C_SHM](shared-models.concept.md) — named parameter catalogs (DiodeModel, TransistorModel, CustomLogicModel, CustomCompositeModel)

### Element categories
- [C_EPS](elements-passives.concept.md) — resistors, capacitors, inductors, wires
- [C_ESRC](elements-sources.concept.md) — voltage and current sources
- [C_EDS](elements-diodes-semis.concept.md) — diodes, Zeners, SCRs, varactors
- [C_ETR](elements-transistors.concept.md) — BJTs, MOSFETs, JFETs, tubes
- [C_EOA](elements-opamps-analog.concept.md) — op-amps, VCOs, analog switches
- [C_ELC](elements-logic-combinational.concept.md) — combinational gates, muxes, decoders, clock
- [C_ELS](elements-logic-sequential.concept.md) — flip-flops, latches, counters, shift registers
- [C_ECH](elements-chips.concept.md) — full ICs (555, 7-seg, RAM, UART)
- [C_ESW](elements-switches.concept.md) — switches, pushbuttons, relays
- [C_EIO](elements-io-probes-meters.concept.md) — voltmeters, ammeters, labeled nodes, displays
- [C_EAR](elements-audio-rf.concept.md) — speaker, antenna, AM/FM
- [C_EEM](elements-electromechanical.concept.md) — motors, servos, thermistors, photoresistors
- [C_EGR](elements-graphic-overlay.concept.md) — text labels, boxes, annotations
- [C_EMG](elements-magnetics-transmission.concept.md) — transformers, coupled inductors, transmission lines

### Edit dialog system
- [C_EIC](edit-info-contract.concept.md) — universal `EditInfo`-row parameter editing pipeline
- [C_IEU](import-export-ui.concept.md) — import/export circuit-text dialog surfaces
- [C_DIN](dialog-info.concept.md) — informational app-level dialogs
- [C_DSP](dialog-specialized.concept.md) — bespoke dialogs (Controls, CompositeModel, Scope, Search, Slider, Subcircuit)

### IO framework
- [C_IOF](io-framework.concept.md) — pluggable text/JSON exporter/importer orchestrator

## Cross-cutting Invariants

- Every concrete element in the 14 categories extends `BaseCircuitElm` and
  honors the full `CircuitElm` override contract defined in **C_ELB**
  (`stamp`, `doStep`, `stepFinished`, `getPostCount`, `getInfo`, `getEditInfo`
  /`setEditValue`, `getDumpType`, `getDumpClass`, `draw`).
- Serialization is **symmetric**: every element's `dump()` / `getDumpType()`
  must round-trip through the matching exporter + importer in **C_IOF**.
- Elements that expose tunable parameters through **C_EIC** must also dump
  those same fields — the edit dialog and the serializer share the same
  source-of-truth field list.
- Shared-model references (**C_SHM**) are serialized by *name*; importers
  lazy-create missing models.
- Waveform generators (**C_WFM**) are value objects — elements swap the
  strategy instance rather than mutating a shared one.

## Known Friction


- 2026-04-19 — Initialized from onboard procedure.
