## Meta
- last_updated: 2025-12-25T15:40:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- agent_notes: Linux workspace; build via `npm run buildgwt` when validating larger geometry changes.
- recent_activity: Added `adjustDerivedGeometry` hook in `MosfetElm` (syntax fix + relocation), full build and geometry-check passed; NW.js manual smoke-tests blocked locally by graphics driver/Vulkan/ANGLE issues.

## Current Task
- task_id: CTX-RULES-BREADCRUMBS-001
- goal: Оновити правила `docs/context_update_rules.md`, додавши секції Breadcrumbs/Guardrails та замінивши ліміти “компактності” на принцип “необхідної достатності”.
- scope_in: документаційні правки шаблону active context і правил синхронізації.
- scope_out: зміни коду симулятора/збірки; будь-які UX- або функціональні зміни.

### Secondary Tasks
- task_id: ELM-GEOM-REFACTOR-CTX
	goal: Продовжити поетапний рефакторинг геометрії `CircuitElm` → `ElmGeometry` (зменшити прямі write-sites і deprecated derived reads), тримаючи збірку “зеленою”.
	status: in-progress
	next: Продовжити міграцію derived reads (`dx/dy/dn/dsign`) на getter-сніпшоти в підкласах малими батчами + `npm run buildgwt` після серій змін.

## Plan & References
- plan: ai_memory/elm_geom_refactor_plan.md (фази A–E; B/C/D in-progress; останнє оновлення плану: 2025-12-24)
- related_docs:
	- ai_memory/elm_geom_refactor_inventory.md
	- ai_memory/elm_geom_refactor_classification.md
	- docs/context_update_rules.md
	- docs/project.md
- related_code:
	- src/main/java/ (геометрія елементів: `CircuitElm`, `ElmGeometry`, підкласи `*Elm`)

## Progress
- done:
	- Фаза A: інвентаризація/класифікація usage; артефакти збережено в `ai_memory/elm_geom_refactor_inventory.md` та `ai_memory/elm_geom_refactor_classification.md`.
	- Фаза B/C/D (частково): є канонічний `setEndpoints(...)`, є derived getter-и (`getDx/getDy/getDn/getDsign/...`), частина базової логіки делегована в `ElmGeometry`.
	- Масово мігруються read-only derived reads у підкласах на getter-сніпшоти; прямі endpoint writes поступово усуваються.
	- Останнє: `DPDTSwitchElm` і `UnijunctionElm` переведені з прямого `dx/dy/dn/dsign` на `get*()` сніпшоти; додатково мігрували `SwitchElm`, `CapacitorElm`, `TriodeElm`, `TransLineElm` (canFlipX/canFlipY), `OhmMeterElm` (dx/dy в draw).
	- Верифікація: `npm run buildgwt` проходить (останній раз: 2025-12-24)

	- Малий рефактор: замінено прямі `ce.dx/ce.dy` у `CircuitSimulator` на `getDx()/getDy()`.

	- Малий рефактор: додано `ElmGeometry.recomputeDerivedWithMinDn()` та оновлено `TransformerElm.adjustDerivedGeometry()` щоб користуватись хелпером.

	- [x] `TransformerElm`: використано хелпер `recomputeDerivedWithMinDn(1)` у `adjustDerivedGeometry()`

	- [x] `CustomTransformerElm`: замінено власні обчислення на `geom.recomputeDerivedAxisAlignedWithMinDn(1)`
	- [x] `TransistorElm`: реалізовано `adjustDerivedGeometry(ElmGeometry)` і застосовано `geom.recomputeDerivedWithMinDn(16)` для запобігання колапсу візуальної геометрії.
	- [x] `OrGateElm` / `ProbeElm`: виправлено прямі звернення до поля `dn` на `getDn()` для уникнення використання soft-deprecated поля.

	- [x] Мікрооптимізація: кешування `ce.getDx()/ce.getDy()` в `CircuitSimulator` (зменшує зайві виклики `ensureGeometryUpdated()`) — білд пройшов.
	- [x] Додано хук `adjustDerivedGeometry(ElmGeometry)` у `MosfetElm` (переміщено з області `draw()` у клас, синтаксична помилка виправлена).
	- [x] Тест: `npm run buildgwt` (2025-12-25T15:38:15+02:00) → **BUILD SUCCESS**.
	- [x] Soft deprecation: додано `@Deprecated` для `dx/dy/dsign/dn/dpx1/dpy1` у `CircuitElm` і короткий Javadoc; розробникам рекомендовано використовувати getter-и або `geom()`.

	in_progress:
	- Manual NW.js smoke test: drag/flip/selection для `Transformer` та `CustomTransformer` — **blocked locally** due to graphics driver/Vulkan/ANGLE issues (dev-run recorded and postponed).

- next:
	- Завершити manual NW.js smoke (drag/flip Transformer & CustomTransformer), перевірити `leads`/`boundingBox`/selection та undo/redo при переміщеннях.
	- Після успішного smoke: перейти до наступного батчу міграцій (`TransLineElm` / gate elements).
	- Після пачок змін, що торкаються кількох файлів: прогнати `npm run buildgwt`.

	- [x] Reviewed candidate set: `CurrentElm`, `TransLineElm`, `CCCSElm/CCVSElm/VCCSElm` — orientation/derived usage reviewed, marked low-risk.
	- [x] `OrGateElm` / `ProbeElm` tidy: replaced `dn` field reads with `getDn()`.
	- Оновити `ai_memory/elm_geom_refactor_plan.md` та додати session history entry.

## Breadcrumbs
- last_focus: "Міграція обчислення derived для Transformer / CustomTransformer; підготовка ручного smoke-тесту."
- last_edit_targets: `ElmGeometry.recomputeDerivedWithMinDn`, `ElmGeometry.recomputeDerivedAxisAlignedWithMinDn`, `TransformerElm.adjustDerivedGeometry`, `CustomTransformerElm.adjustDerivedGeometry`, `CircuitSimulator` (cached getDx/getDy).
- next_hops: "1) запустити `npm start` та відкрити dev-додаток; 2) додати Transformer/CustomTransformer на схему; 3) виконати drag/flip/selection та перевірити поведінку."
- navigation_queries: [`recomputeDerivedWithMinDn`, `recomputeDerivedAxisAlignedWithMinDn`, `TransformerElm`, `CustomTransformerElm`]
- resume_recipe: "1) `npm start` → 2) Load a test circuit with Transformer and CustomTransformer → 3) perform drag/flip and check selection/leads → 4) update session file."

## Guardrails
- invariants:
	- Після будь-якої зміни endpoints/derived — пройти `npm run buildgwt`  
	- Не робити змін, що змінюють видиму візуальну геометрію елемента без окремого візуального ревью.
- scope_guardrails:
	- У цій пачці не міняти JSNI-інтерфейси або публічні API; розширення helper-ів і пересування обчислень в `ElmGeometry` — дозволено.
- temporary_allowances:
	- Дозволено тимчасові overrides в `adjustDerivedGeometry()` для особливих елементів до завершення Фази C.

## Decisions
- 2025-12-24T16:31:31+02:00 → `ai_memory/active_context.md` заповнюється “зі стану плану” (а не з комітів), бо активний контекст був порожнім і користувач попросив синхронізацію згідно плану.
- 2025-12-24T16:31:31+02:00 → Тримати “dirty” толерантність (`ensureUpToDate`) як тимчасову мітігацію до завершення міграції write-sites.
- 2025-12-24T16:34:18+02:00 → Вирівняно структуру плану: додано явний заголовок Фази D, щоб секції не “злипалися”.
- 2025-12-24T16:49:38+02:00 → Для SCRElm/TriacElm читаємо derived через getter-и (локальні сніпшоти), щоб поступово прибрати залежність від полів.
- 2025-12-24T16:49:38+02:00 → Виправлення build blockers (Transformer/CustomTransformer) робимо першочергово, щоб не накопичувати “не-білдиться” стан під час міграції.
- 2025-12-24T17:23:10+02:00 → Для пошуку залишкових derived reads використовуємо grep по типових патернах (`dx==0`, `dy==0`, `.../dn`) і мігруємо малими батчами.
- 2025-12-25T00:00:00+02:00 → Додано в `docs/context_update_rules.md` опціональні секції Breadcrumbs/Guardrails і замінено “компактність” на принцип “необхідної достатності” (без числових лімітів).

## Open Questions / Blockers
- Для елементів, що спеціально мутують `dn/dsign` в `setPoints()` (SCR/Triac/Transformer/Transistor/Pot): який цільовий підхід обираємо (hook у `ElmGeometry` vs залишити override довше)?

## Verification
- Build (GWT): `npm run buildgwt`

