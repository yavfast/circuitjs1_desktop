```markdown
# Canonical Definitions

Single source of truth for enums, schemas, and thresholds used across context management modules.

## Status Values

### Task Status (Current Task / Secondary Tasks)

```yaml
task_status:
  - not-started   # Task defined but work not begun
  - in-progress   # Actively being worked on
  - paused        # Work interrupted, can be resumed
  - blocked       # Cannot proceed due to dependency/blocker
  - completed     # Finished successfully
```

### Archive Status (contexts_index.yaml)

```yaml
archive_status:
  - paused        # Task suspended, context preserved for later
  - completed     # Task finished, context kept for reference
```

### Session Status (session_history files)

```yaml
session_status:
  - in-progress   # Session ongoing
  - completed     # Session finished
```

## Priority Values

```yaml
priority:
  - high          # Likely to become Current Task soon
  - medium        # Important but not urgent
  - low           # Backlog; can be archived first
```

## Category Values

Recommended categories for task classification (extensible):

```yaml
category:
  - refactor      # Code restructuring without behavior change
  - feature       # New functionality
  - bugfix        # Bug fixes
  - docs          # Documentation changes
  - infra         # Build, CI/CD, tooling
  - research      # Investigation, analysis
  - test          # Test coverage
  - migration     # Data/code migration
```

## Staleness Thresholds

```yaml
staleness:
  warn_days: 7           # Age > 7 days → warn, verify key files
  full_refresh_days: 30  # Age > 30 days → require full refresh
  commit_drift: 50       # Commits behind HEAD → warn about conflicts
```

## Good Match Rule

Deterministic rule for selecting an archived context to restore:

```yaml
good_match:
  # Priority order (first match wins):
  - condition: "task_id exact match"
    result: good_match
  - condition: "category matches AND ≥2 overlapping tags"
    result: good_match
  - condition: "else"
    result: no_match  # → bootstrap new context
```

## Registry Schema (contexts_index.yaml)

```yaml
schema_version: 1

# Required fields per entry:
required:
  - task_id       # Unique identifier (string)
  - title         # Human-readable title (string)
  - category      # From category enum above (string)
  - tags          # List of keywords (array of strings)
  - status        # From archive_status enum (string)
  - archived_at   # ISO-8601 datetime (string)
  - file          # Repo-relative path to archive file (string)
  - summary       # 1-3 sentence resume-oriented summary (string)

# Recommended fields:
recommended:
  - related_paths   # Key files/folders for this task (array)
  - restore_recipe  # Steps to resume work (array of strings)

# Optional fields:
optional:
  - confidence        # Match confidence 0.0-1.0 (number)
  - last_restored_at  # When last restored (ISO-8601 string)
  - repo_revision     # Git commit SHA at archive time (string)
  - git_branch        # Associated branch name (string)
```

## Active Context Template Schema

See `active_context_template.md` for full structure. Key constraints:

- **Exactly one** Current Task at a time
- Quick Resume block **must** be at the end
- `last_updated` **must** be ISO-8601 with timezone

## Cross-References

When modifying definitions in this file, update:

- `context_rules.md` — entrypoint pseudocode
- `switching.md` — archive/restore flow
- `staleness.md` — refresh thresholds
- `multi_task.md` — secondary task rules
- `sync.md` — sync algorithm
- `hygiene_security.md` — checklists

```
