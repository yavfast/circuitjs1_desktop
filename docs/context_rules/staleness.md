# Staleness Detection and Refresh

## Thresholds

- `STALENESS_WARN_DAYS = 7` — warn, verify key files
- `STALENESS_FULL_REFRESH = 30` — require full refresh
- `COMMIT_DRIFT = 50` — commits behind HEAD → warn

## When to check

- Start of new chat when loading `ai_memory/active_context.md`
- When restoring an archived context

## Staleness indicators

Context is stale if any true:

1. `last_updated` > 7 days → warn, verify files
2. `last_updated` > 30 days → full refresh required
3. Any `active_files` missing → update list
4. Branch mismatch → ask user or switch
5. >50 commits behind HEAD → warn about conflicts

## Refresh protocol

1. Record staleness in Decisions/Blockers
2. Verify `active_files` and `key_files` exist
3. Check git: branch, uncommitted changes, revision drift
4. Update stale sections (files, git state, failure context)
5. Bump `last_updated`
6. Add Decisions entry

## Refresh levels

- **Lightweight** (<30 days, no missing files): update timestamp, verify, proceed
- **Full** (≥30 days or missing files): re-scan code, rebuild Progress, confirm before work
