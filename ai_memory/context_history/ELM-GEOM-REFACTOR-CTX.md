## Meta
 last_updated: 2025-12-25T19:13:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
 active_skills: [circuitjs1-dev-workflow, circuitjs1-ui-maintenance]
 agent_notes: Linux workspace; DevMode запускаємо через scripts/run_dev_web.sh; візуальна перевірка через Chrome DevTools MCP + docs/JS_API.md.
- recent_activity: FORCED/HARD MODE увімкнено: legacy geometry поля в `CircuitElm` видалені (compile-break як драйвер). Далі — ітеративний цикл `npm run buildgwt` → виправлення compile errors → повтор. Останні правки: `ElmGeometry` (додано `setLead1/setLead2` для уникнення alias з endpoints), міграція `GateElm`/`AndGateElm`/`AudioOutputElm`/`GraphicElm`/`BoxElm` на `geom()`/getter-и. Поточний білд падає на наступному батчі: `CapacitorElm`, `ComparatorElm`, `CrossSwitchElm`, `CrystalElm`.

## Current Task

- task_id: ELM-GEOM-REFACTOR-CTX
	goal: Форсовано завершити рефакторинг геометрії: зробити використання legacy геометричних полів неможливим і перевести весь код на `geom()`/`ElmGeometry` як єдине джерело істини.
	current_focus: Серійна міграція `*Elm` після відрізання полів у `CircuitElm` (HARD MODE). Поточний батч: `CapacitorElm`, `ComparatorElm`, `CrossSwitchElm`, `CrystalElm`.
	active_files: [src/main/java/com/lushprojects/circuitjs1/client/element/ElmGeometry.java, src/main/java/com/lushprojects/circuitjs1/client/element/GateElm.java, src/main/java/com/lushprojects/circuitjs1/client/element/AndGateElm.java, src/main/java/com/lushprojects/circuitjs1/client/element/AudioOutputElm.java, src/main/java/com/lushprojects/circuitjs1/client/element/GraphicElm.java, src/main/java/com/lushprojects/circuitjs1/client/element/BoxElm.java]
	status: in-progress
	scope_in: Масова міграція всіх use-sites на `geom()`; додавання стандартної геометричної логіки в `ElmGeometry`; допускається тимчасовий compile-break як механізм форсування.
	scope_out: Нові UI фічі/редизайн; зміни формату експорту; нефокусні рефактори поза геометрією.

## Plan & References
 plan: ai_memory/elm_geom_refactor_plan.md (FORCED / HARD MODE: фази H0–H3; compile-break до завершення міграції)
- related_docs:
	- ai_memory/elm_geom_refactor_inventory.md
	- ai_memory/elm_geom_refactor_classification.md
	- ai_memory/geom_visual_checks/failing_elements.md
	- docs/context_rules/context_rules.md
	- docs/project.md
	- docs/JS_API.md
	- docs/elements.md
- related_code:
	- src/main/java/ (геометрія елементів: `CircuitElm`, `ElmGeometry`, підкласи `*Elm`)
	- src/main/java/com/lushprojects/circuitjs1/client/io/json/CircuitElementFactory.java (джерело списку JSON type)

## Progress
- done:
	- FORCED/HARD MODE активовано: геометричні поля в `CircuitElm` прибрані/недоступні; компіляція навмисно “ламається”, доки всі use-sites не мігровані.
	- Мігровано на `geom()`/getter-и (частково, останній батч): `GateElm`, `AndGateElm`, `AudioOutputElm`, `GraphicElm`, `BoxElm`.
	- Додано `ElmGeometry.setLead1/setLead2` для елементів, які повинні гарантувати, що lead-и не alias-яться з endpoints.

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
	- [x] Додано guards у `Graphics.drawPolyline` та `Graphics.fillPolygon` для захисту від degenerate/NaN координат при рефакторингу геометрії.
	- [x] Застосовано ініціалізацію `owner.point1/owner.point2` в `ElmGeometry.updatePointsFromEndpoints()` (тимчасово з `System.out.println` для перевірки).
	- [x] Запущено DevMode (`./scripts/run_dev_web.sh`) та виконано runtime перевірки (JS API, скріншот); canvas now renders geometry.
	- [x] Знайдено повний перелік JSON type для елементів у `CircuitElementFactory` (потрібно для покриття “всіх елементів” через `importFromJson()`).

	in_progress:
	- Ітеративний compile-fix loop: `npm run buildgwt` → міграція наступних offenders → повтор.

- next:
	- Реалізувати Фазу H0: додати/уточнити API в `ElmGeometry` (endpoints/derived/points/leads/bbox access).
	- Реалізувати Фазу H1: прибрати/заблокувати геометричні поля в `CircuitElm` (compile-break як контроль).
	- Серійно мігрувати `*Elm` на `geom()` (Фаза H2) до повного зеленого `npm run buildgwt`.

	- [x] Reviewed candidate set: `CurrentElm`, `TransLineElm`, `CCCSElm/CCVSElm/VCCSElm` — orientation/derived usage reviewed, marked low-risk.
	- [x] `OrGateElm` / `ProbeElm` tidy: replaced `dn` field reads with `getDn()`.
	- Оновити `ai_memory/elm_geom_refactor_plan.md` та додати session history entry.

	in_progress:
	- Візуальна серійна перевірка (DevMode + JS API) — **deferred** до моменту, коли базова компіляція знову стане стабільною.

	 next:
		- Далі мігрувати: `CapacitorElm`, `ComparatorElm`, `CrossSwitchElm`, `CrystalElm` (points/leads/bbox/endpoints).
		- Після зеленої компіляції: повернути DevMode-візуальні батчі.

## Breadcrumbs
 last_focus: "HARD MODE активний: виправляємо компіляцію батчами після видалення legacy полів."
- last_edit_targets: ["src/main/java/com/lushprojects/circuitjs1/client/element/ElmGeometry.java", "src/main/java/com/lushprojects/circuitjs1/client/element/GateElm.java", "src/main/java/com/lushprojects/circuitjs1/client/element/AndGateElm.java", "src/main/java/com/lushprojects/circuitjs1/client/element/AudioOutputElm.java", "src/main/java/com/lushprojects/circuitjs1/client/element/GraphicElm.java", "src/main/java/com/lushprojects/circuitjs1/client/element/BoxElm.java"]
 next_hops: "1) Fix `CapacitorElm` → 2) Fix `ComparatorElm` → 3) Fix `CrossSwitchElm` → 4) Fix `CrystalElm` → 5) rebuild."
 navigation_queries: ["cannot find symbol: variable point1", "cannot find symbol: variable lead1", "geom().getPoint1", "geom().getLead1", "getX2()", "getBoundingBox()", "calcLeads"]
 resume_recipe: "1) `npm run buildgwt` → 2) відкривати файли зі списку помилок → 3) замінювати поля на `geom()`/getter-и → 4) rebuild до зеленої компіляції."

## Guardrails
- invariants:
	- У HARD MODE compile-break дозволений; перевірка логіки робиться через `git diff` (формули/гілки), а build повертається в зелений стан тільки після завершення H2.
	- Не робити змін, що змінюють видиму візуальну геометрію елемента без окремого візуального ревью.
- scope_guardrails:
	- У цій пачці не міняти JSNI-інтерфейси або публічні API; розширення helper-ів і пересування обчислень в `ElmGeometry` — дозволено.
- temporary_allowances:
	- Дозволено тимчасові overrides в `adjustDerivedGeometry()` для особливих елементів до завершення Фази C.
	- Дозволено тимчасово неуспішну компіляцію до завершення Фази H2.

## Decisions
- 2025-12-24T16:31:31+02:00 → `ai_memory/active_context.md` заповнюється “зі стану плану” (а не з комітів), бо активний контекст був порожнім і користувач попросив синхронізацію згідно плану.
- 2025-12-24T16:31:31+02:00 → Тримати “dirty” толерантність (`ensureUpToDate`) як тимчасову мітігацію до завершення міграції write-sites.
- 2025-12-24T16:34:18+02:00 → Вирівняно структуру плану: додано явний заголовок Фази D, щоб секції не “злипалися”.
- 2025-12-24T16:49:38+02:00 → Для SCRElm/TriacElm читаємо derived через getter-и (локальні сніпшоти), щоб поступово прибрати залежність від полів.
- 2025-12-24T16:49:38+02:00 → Виправлення build blockers (Transformer/CustomTransformer) робимо першочергово, щоб не накопичувати “не-білдиться” стан під час міграції.
- 2025-12-24T17:23:10+02:00 → Для пошуку залишкових derived reads використовуємо grep по типових патернах (`dx==0`, `dy==0`, `.../dn`) і мігруємо малими батчами.
- 2025-12-25T00:00:00+02:00 → Оновлено правила активного контексту: використовується `docs/context_rules/context_rules.md` (entrypoint + модулі), Breadcrumbs/Guardrails оформлено як опціональні секції; принцип — “необхідна достатність” (без числових лімітів).
- 2025-12-25T16:22:56+02:00 → Ініціалізація `point1/point2` у `ElmGeometry.updatePointsFromEndpoints()` додана як негайне виправлення для запобігання null-point runtime failures; тимчасовий debug print додано для валідації.
- 2025-12-25T16:23:18+02:00 → Додано guards у `Graphics.drawPolyline` та `Graphics.fillPolygon` щоб захиститись від degenerate/NaN координат при рефакторингу геометрії; DevMode було запущено і базова runtime валідація пройдена.
- 2025-12-25T17:15:24+02:00 → За запитом користувача: `Optocoupler` тимчасово **SKIP** у серійній візуальній перевірці; проблему зафіксовано окремим списком.
- 2025-12-25T19:02:00+02:00 → Стратегія змінена на FORCED / HARD MODE: прибираємо геометричні поля `CircuitElm` і мігруємо все на `geom()` навіть ціною тимчасового compile-break.

## Open Questions / Blockers
- Для елементів, що спеціально мутують `dn/dsign` в `setPoints()` (SCR/Triac/Transformer/Transistor/Pot): який цільовий підхід обираємо (hook у `ElmGeometry` vs залишити override довше)?
- Чи вважаємо тимчасові debug prints в Java за допустимі у короткостроковій перевірці або одразу замінювати на логгер з рівнем DEBUG?
- `Optocoupler`: JSON import/finalize/setPoints не завершується успішно (елемент не додається). Не блокує решту — **пропускаємо**, статус зафіксовано в `ai_memory/geom_visual_checks/failing_elements.md`.

## Verification

- Build (GWT): `npm run buildgwt` (зараз падає на помилках компіляції в `CapacitorElm`, `ComparatorElm`, `CrossSwitchElm`, `CrystalElm`).

## Failure Context
- last_failure_time: 2025-12-25T19:12:13+02:00
- failure_type: compile
- error_signature: "cannot find symbol (legacy geometry fields: point1/point2/lead1/lead2/x/y/x2/y2)"
- failing_files: ["src/main/java/com/lushprojects/circuitjs1/client/element/CapacitorElm.java", "src/main/java/com/lushprojects/circuitjs1/client/element/ComparatorElm.java", "src/main/java/com/lushprojects/circuitjs1/client/element/CrossSwitchElm.java", "src/main/java/com/lushprojects/circuitjs1/client/element/CrystalElm.java"]
- attempted_fixes: "Мігровано `GateElm`/`AndGateElm`/`AudioOutputElm`/`GraphicElm`/`BoxElm`; додано `ElmGeometry.setLead1/setLead2`."
- resolution_status: pending

---

## Archive Meta
- archived_at: 2025-12-26T10:12:27+02:00
- archived_reason: "Switched to designing context switching system"
- status: paused
- superseded_by_task_id: CTX-SWITCHING-RULES-001

## Archive Summary
- Стан: HARD MODE міграції геометрії активний; допустимий тимчасовий compile-break.
- Де зупинились: `npm run buildgwt` падає на `CapacitorElm`, `ComparatorElm`, `CrossSwitchElm`, `CrystalElm` через звернення до legacy геометричних полів.
- Як відновити: запустити `npm run buildgwt`, по черзі виправити offenders заміною полів на `geom()`/getter-и, повторювати до зеленого білду; DevMode-візуальні перевірки робити після стабілізації компіляції.
