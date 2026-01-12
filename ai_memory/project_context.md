# Project Context — CircuitJS1 Desktop (onboarding)

## Meta
- last_updated: 2026-01-12T14:58:04+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- primary_language: uk

## What This Repo Is

Це desktop-адаптація CircuitJS1 (симулятор електронних схем): ядро симулятора написане на Java і компілюється в JavaScript через GWT; desktop запуск/пакування робиться через NW.js.

Проєкт орієнтований на навчальне використання (багато моделей ідеалізовані).

## Key Modules / Folders

- `src/main/java/` — основний код симулятора та UI-логіка (GWT client code, елементи, solver, rendering).
- `src/main/webapp/` / `war/` — веб-ресурси (HTML/JS/CSS) і базові assets для UI.
- `target/site/` — результат `npm run buildgwt` (готова зібрана web-версія, яку запускає NW.js).
- `scripts/` — dev/build/run скрипти (Node.js + shell), зручні entrypoints для NW.js/Devmode.
- `server/` — допоміжний Node-сервер для remote debug/інструментів (якщо використовується).
- `docs/` — документація по проєкту, API, форматам експорту, внутрішніх правилах.
- `tests/` — тестові/прикладні схеми та допоміжні артефакти.

## Build / Run / Debug (common commands)

- `npm install` — встановлення Node-залежностей.
- `npm run buildgwt` — збирає GWT web app → `target/site/`.
- `npm start` — запускає зібрану версію в NW.js SDK (працює з `target/site/`).
- `npm run devmode` — GWT DevMode для швидкої розробки UI/Java; працює напряму з `war/` (НЕ з `target/site/`).
- `npm run build` — збирає desktop release (за замовчуванням Linux x64) → `out/`.
- `npm run full` — повна очистка + пакування → `out/`.

## Project-Specific Concepts

- **GWT**: Java-код UI/симулятора компілюється в JS; DevMode і `target/site` мають різні runtime режими.
- **NW.js**: desktop runtime, який відкриває web app локально.
- **JS API**: автоматизація/тести/інтеграції — див. `docs/JS_API.md`.
- **Export formats**:
  - новий/планований JSON: `docs/EXPORT_CJS.md`
  - legacy text dump: `docs/EXPORT_OLD.md`
- **Standard circuits corpus** (корисно для регресій): `src/main/java/com/lushprojects/circuitjs1/public/circuits`

## Common Gotchas

- DevMode (`npm run devmode`) працює з `war/` і може відрізнятись від поведінки зібраного `target/site/`.
- Якщо потрібен стабільний E2E тест зібраного UI — орієнтуйтесь на `target/site/` + NW.js.

## References (start here)

- `docs/project.md` — огляд проєкту, структура, основні команди.
- `README.md` — збірка/запуск/короткі нотатки.
- `docs/JS_API.md` — автоматизація і тестування.
- `docs/EXPORT_CJS.md`, `docs/EXPORT_OLD.md` — формати експорту.
- `docs/elements.md` — каталог елементів.
