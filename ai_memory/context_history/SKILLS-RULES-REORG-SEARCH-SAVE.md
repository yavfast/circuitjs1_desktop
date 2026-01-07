## Meta
- last_updated: 2026-01-06T00:00:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [python-cli]
        agent_notes: Документаційна правка правил AI Skills Memory; без змін продакшен-коду.

## Current Task


- task_id: SKILLS-RULES-REORG-SEARCH-SAVE
        goal: Винести детальні правила пошуку та збереження (ingest/store) в окремі файли; залиш
ити у skills_rules.md переважно загальний протокол/псевдокод. Додати stdin як альтернативу для STORE_SKILL та INGEST_EPISODE для зручного ревʼю у чаті.                                                 global_context: Памʼять DB-first; файли епізодів — лише тимчасові артефакти. Потрібні пр
авила, що зменшують бюрократію і збільшують LTM корисність.                                             current_focus: Рефакторинг docs/skills_rules/* (overview vs detailed search/save) + stdi
n support для skill put.                                                                                active_files: [docs/skills_rules/skills_rules.md, docs/skills_rules/search.md, docs/skil
ls_rules/save.md, docs/skills_rules/cli.md, scripts/ai_skills_memory/mem.py]                            status: completed
        scope_in: Лише документація правил + приклади CLI.
        scope_out: Зміни реалізації mem.py/ai_mem.sh (якщо вже є підтримка stdin).

## Plan & References
        plan: manage_todo_list (rules update + context sync)
        related_docs:
                - docs/skills_rules/skills_rules.md
                - docs/skills_rules/cli.md
                - docs/context_rules/context_rules.md
        related_code:
                - ai_mem.sh
                - scripts/ai_skills_memory/mem.py

## Progress
        done:
                - Split detailed search guidance into docs/skills_rules/search.md; detailed save
/ingest guidance into docs/skills_rules/save.md.                                                                - Kept docs/skills_rules/skills_rules.md as mostly protocol overview + pseudocod
e + links.                                                                                                      - Added `./ai_mem.sh skill put --stdin` support in scripts/ai_skills_memory/mem.
py and documented it.                                                                                           - Clarified pseudocode by adding explicit RUN_STDIN/RUN_JSON_FILE helpers for re
view-friendly stdin vs file-based flows.                                                        
        in_progress:
                - None.

        next:
                - If needed: follow up by aligning other docs that reference old paths.
                - Optional: remove the test skill `docs-skill-stdin-test` from DB if you don’t w
ant it.                                                                                         
## Breadcrumbs
        last_focus: "Update skills rules: episode storage + stdin ingest + softer accumulation"
        last_edit_targets: ["docs/skills_rules/skills_rules.md", "docs/skills_rules/cli.md"]
        resume_recipe: "Finalize by ingesting an episode via stdin; verify docs examples stay co
nsistent."                                                                                      
## Guardrails
        invariants:
                - DB є source-of-truth; файли епізодів — опційні та тимчасові.
                - Якщо створюється episode JSON файл → тільки у `ai_memory/session_history/` або
 `/tmp/ai_memory/`.                                                                                             - Не форсити епізоди для дрібних задач без LTM цінності.

## Verification
        - Verified implementation supports stdin ingest: `scripts/ai_skills_memory/mem.py ingest
 --stdin`.                                                                                      
## Long-term Memory Candidates
        facts_to_save:
                - `./ai_mem.sh ingest` підтримує `--stdin` (без файлів).
                - Episode JSON файли не повинні зʼявлятися у корені репозиторію; рекомендовані к
аталоги: `ai_memory/session_history/` або `/tmp/ai_memory/`.                                            episodes_to_ingest:
                - Ingested: ep_df3acd09c7f844139f47e2eef0896358
                - Ingested: ep_057535d8eb6e44e9ba97f4a944a53d0f

## Quick Resume — SKILLS-RULES-EPISODES-STDIN
```yaml
# Quick Resume — SKILLS-RULES-EPISODES-STDIN
goal: "Rules: episode files in dedicated dirs; add stdin ingest; soften forced accumulation"
focus_now: "Ingest a doc-change episode via stdin"
next_action: "cat <<'JSON' | ./ai_mem.sh ingest --stdin"
key_files: [docs/skills_rules/skills_rules.md, docs/skills_rules/cli.md]
last_result: in_progress
```
