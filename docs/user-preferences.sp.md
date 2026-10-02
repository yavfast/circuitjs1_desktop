# User Preferences — Specification  {#SP_USR}

> **Code:** SP_USR
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-02
>
> **Concept:** [C_USR](./user-preferences.concept.md)
> **Depends on specs:** SP_UTL, SP_PLT, SP_RND
> **Used by specs:** [SP_MEN](./menus-actions.sp.md), [SP_EDI](./canvas-editor.sp.md), [SP_UND](./commands-undo.sp.md), [SP_SCP](./scope-visualization.sp.md), [SP_EIC](./edit-info-contract.sp.md), [SP_MCP](./mcp-server.sp.md)
> **Plan:** [user-preferences.plan.md](./user-preferences.plan.md)

## 01. Data Structures  {#SP_USR_01}

### 01_01. OptionsManager  {#SP_USR_01_01}

Stateless; all methods `static`. Facade over `Storage.getLocalStorageIfSupported()`.

Key catalog (partial):

| Key | Type | Default | Owner |
|-----|------|---------|-------|
| `language` | string | null | circuitjs1.java, EditOptions |
| `decimalDigits` | int | 3 | DisplaySettings |
| `decimalDigitsShort` | int | 1 | DisplaySettings |
| `positiveColor`/`negativeColor`/`neutralColor`/`selectColor`/`currentColor` | hex string | defaults | ColorSettings via EditOptions |
| `toolbar`/`showMouseMode`/`crossHair`/`euroResistors`/`euroGates`/`whiteBackground`/`conventionalCurrent`/`mouseWheelEdit` | bool | per flag | MenuManager |
| `wheelSensitivity` | double | "1" | CircuitEditor |
| `circuitRecovery` | string | — | removed 2026-10-01 (BL-C01); `CirSim` deletes a leftover value at startup |
| `scopeDefaults` | string | — | Scope |
| `shortcuts` | string | null | MenuManager |
| `subcircuit:<name>` | string | — | CustomCompositeModel |
| `MOD_UIScale`/`MOD_TopMenuBar`/`MOD_overlayingSidebar`/`MOD_*` | mixed | per flag | ModDialog |
| `<prefix>.pos`/`<prefix>.collapsed` | `"x,y"`/`"1/0"` | — | Dialog.getPrefixedKey |
| `mcpServerEnabled` | bool | "true" | McpServerStatus (written by McpServerDialog) |
| `mcpServerPort` | int | "7311" | McpServerStatus (written by McpServerDialog) |
| `mcpServerPortRange` | int | "20" | McpServerStatus (not edited by the dialog) |
| `mcpServerHost` | string | "0.0.0.0" | McpServerStatus (written by McpServerDialog) |

MCP server keys ([SP_MCP_01_01](./mcp-server.sp.md#SP_MCP_01_01), [C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02)): `McpServerStatus.readPrefs()` owns their validation. `mcpServerEnabled` is exactly `"true"` or `"false"`; `mcpServerPort` is an integer 1024..65535; `mcpServerPortRange` is 1..100 with `port + range − 1 ≤ 65535`; `mcpServerHost` is trimmed and must be `localhost`, an IPv4 dotted quad or an IPv6 literal without brackets or zone. An invalid stored value is replaced by its default at start-up (the range by 20, or by `65535 − port + 1` when 20 does not fit), one warning log line names the replaced keys, and the stored value is not rewritten. These keys have no URL-query layer. They are read once at start-up, so a change applies at the next start. The MCP Server dialog validates enabled, port and host with the same rules and writes the three together.

### 01_02. DisplaySettings  {#SP_USR_01_02}

| Field | Type | Default |
|-------|------|---------|
| decimalDigits (static) | int | 3 |
| shortDecimalDigits (static) | int | 1 |
| numberFormatsLoaded | boolean | false |
| menuManager | MenuManager | ctor-bound |

Ten instance getters each `menuManager != null && item != null && item.getState()`: `showDots`, `showVoltage`, `showPower`, `showValues`, `euroResistors`, `euroGates`, `printableMode`, `showConductance`, `smallGrid`, `showCrossHair`.

### 01_03. ColorSettings  {#SP_USR_01_03}

| Field | Type | Default |
|-------|------|---------|
| voltageRange | double | 5 |
| COLOR_SCALE_COUNT | int (const) | 201 |
| colorScale | Color[201] | built by `updateColorScale` |
| backgroundColor | Color | black |
| foregroundColor | Color | white |
| elementColor | Color | gray |
| selectColor | Color | cyan |
| positiveColor / negativeColor / neutralColor | Color | green / red / gray |
| currentColor | Color | yellow |
| postColor | Color | gray |
| printable | boolean | false |

### 01_04. QueryParameters  {#SP_USR_01_04}

| Field | Type | Description |
|-------|------|-------------|
| map | HashMap<String,String> | parsed pairs; keys undecoded, values URL-decoded |

## 02. Contracts  {#SP_USR_02}

### 02_01. OptionsManager  {#SP_USR_02_01}

    String  getOptionFromStorage(key, def)
    boolean getBoolOptionFromStorage(key, def)      -- strict "true".equals
    int     getIntOptionFromStorage(key, def)       -- catches NumberFormatException -> def
    double  getDoubleOptionFromStorage(key, def)
    void    setOptionInStorage(key, String|boolean|int|double)
        logs "Save option: k = v" via CirSim.console
    void    removeOptionFromStorage(key)
    void    clearAllOptions()
    int     getStorageLength();  String getStorageKey(int i)
    boolean hasOption(key)
    String  getPrefixedKey(prefix, key)  -- "prefix.key", or "key" if prefix empty

### 02_02. ColorSettings  {#SP_USR_02_02}

    FUNCTION updateColorScale():
        FOR i in 0..200:
            v = i*2/201 - 1
            IF v < 0: colorScale[i] = blend(neutralColor, negativeColor, |v|)
            ELSE:     colorScale[i] = blend(neutralColor, positiveColor, v)

    FUNCTION getVoltageColor(volts):
        IF printable: RETURN Color.black
        idx = clamp((volts + voltageRange) * 200 / (2*voltageRange), 0, 200)
        RETURN colorScale[(int) idx]

    FUNCTION getPowerColor(power):
        IF printable: RETURN Color.black
        idx = clamp(N/2 + N/2 * (-power), 0, 200)
        RETURN colorScale[(int) idx]

### 02_03. QueryParameters  {#SP_USR_02_03}

    QueryParameters(): map = {}
        search = JSNI window.location.search
        FOR each pair in search.split("&"):
            parts = pair.split("=")
            map[parts[0]] = URL.decode(parts[1])    -- crashes on bare flag

    String  getValue(key)
    boolean getBooleanValue(key, def)
        val = map[key]
        IF val == null: RETURN def
        RETURN val == "1" OR "true".equalsIgnoreCase(val)

## 03. Validation Rules  {#SP_USR_03}

- `OptionsManager.getLocalStorage()` may return `null` (private browsing).
- `DisplaySettings` getters must null-guard `menuManager` and the item (boot-safe).
- Callers mutating `ColorSettings.positive/negative/neutralColor` must then call `updateColorScale()` (documented in-code).
- URL override layering: `qp.getValue(k) ?? OptionsManager.getOptionFromStorage(k, default)`. The MCP server keys are the exception: storage → default only, validated by `McpServerStatus` ([§01_01](#SP_USR_01_01)).

## 04. State Transitions  {#SP_USR_04}

URL → localStorage → edit → persist pipeline:

| From | To | Trigger | Side effect |
|------|----|---------|-------------|
| (URL only) | (URL + storage) | first boot with `?param=` | `CircuitInfo` fields set; storage untouched unless written via setter |
| (storage) | (storage') | menu toggle | `setOptionInStorage(k, newVal)` + console log |
| (storage') | (in-memory cache') | runtime read | `DisplaySettings.reloadNumberFormatsFromStorage` / `ColorSettings.setXxx` |

## 05. Verification Criteria  {#SP_USR_05}

### 05_01. Functional  {#SP_USR_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| getIntOptionFromStorage | corrupt value | `"abc"` | returns default |
| getVoltageColor | printable | any volts | Color.black |
| getVoltageColor | normal, mid-range | 0 V | colorScale[100] ≈ neutralColor |
| QueryParameters | `?lang=fr` | — | map["lang"]="fr" |

### 05_02. Invariants  {#SP_USR_05_02}

| Invariant | Verification |
|-----------|--------------|
| colorScale always length 201 | inspect after updateColorScale |
| 0V maps to neutralColor | colorScale[100] == neutralColor (in normal mode) |
| booleans strict "true" | other strings return false |

### 05_03. Edge Cases  {#SP_USR_05_03}

| Case | Input | Expected |
|------|-------|----------|
| bare flag `?foo` | — | ArrayIndexOutOfBoundsException (known bug) |
| "1"/"0" via getBoolOptionFromStorage | "1" | returns false (mismatch) |
| private browsing | Storage unavailable | all getters return defaults; sets no-op |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-10-02 | PL_MCP Phase 5 propagate: MCP server keys (`mcpServerEnabled`, `mcpServerPort`, `mcpServerPortRange`, `mcpServerHost`) in the key catalog with their validation and fallback; no URL layer for them. |
