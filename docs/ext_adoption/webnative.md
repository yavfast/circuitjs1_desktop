# Adoption Notes: webnative → CircuitJS1 Desktop Mod

> **Тип документа:** аналітичний (read-only advisory, не є частиною dev-flow gates)
> **Джерело:** `/hdd/STORE/ext_repos/webnative` (інший том ФС — абсолютний шлях)
> **Концептуальний аналіз джерела:** `/hdd/STORE/ext_repos/webnative.concept.md`
> **Upstream:** https://github.com/kl1ro/webnative.git (npm: `@mindw1n/webnative`)
> **Аналіз:** 2026-06-21
> **Цільовий проєкт:** CircuitJS1 Desktop Mod (`/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop`)
> **Пов'язані документи цільового проєкту:**
> - [platform-bridge.concept.md](../platform-bridge.concept.md) — `C_PLT`
> - [browser-file-bridge.concept.md](../browser-file-bridge.concept.md) — `C_FBR`
> - [session-logging.concept.md](../session-logging.concept.md) — `C_LOG`
> - [clipboard.concept.md](../clipboard.concept.md) — `C_CLP`
> - [app-entrypoint.concept.md](../app-entrypoint.concept.md) — `C_APE`
> - [io-framework.concept.md](../io-framework.concept.md) — `C_IOF`
> - [project.md](../project.md)

## Мета документа

Зафіксувати, які архітектурні ідеї та патерни з `webnative` мають сенс адаптувати у CircuitJS1 Desktop Mod. Документ — **радник**, не план імплементації.

Ключове спостереження: обидва проєкти вирішують одну й ту саму задачу — **доставити вебзастосунок як кросплатформну десктопну програму**. CircuitJS1 робить це через **NW.js** (вбудований Chromium, ~150 МБ+, як Electron). `webnative` пропонує альтернативу — **системний WebView** (~10–15 МБ). Це робить `webnative` найрелевантнішим зовнішнім репозиторієм для шару пакування CircuitJS1.

Друге, не менш важливе спостереження: **CircuitJS1 — майже чистий клієнтський застосунок**. Це GWT-код, скомпільований у JS; жодного Node.js-бекенду бізнес-логіки немає (каталог `server/` — лише harness для remote-debug, не входить у дистрибутив). Тому більша частина архітектури `webnative` (форк-бекенд, IPC-пайп, port-key авторизація, lifecycle-вбивство зомбі-процесів) для CircuitJS1 **не потрібна** — потрібен лише *тонкий WebView-каркас* + веб-замінники кількох нативних викликів.

## Контекст: що вже є в CircuitJS1 Desktop Mod

- **Пакування через NW.js** (`package.json`: `nw@0.64.1-sdk`, `nw-builder@4.6.4`). Збірка `scripts/dev_n_build.js` уже виробляє мультиплатформні артефакти: win ia32/x64, linux ia32/x64, osx x64/arm64. Вихід — каталог `./out/`.
- **Windows-інсталятор через Inno Setup** ([project.md](../project.md)).
- **Нативні точки дотику ізольовані** (правило `JsniBoundary` у `.dev_flow/rules/architecture.md`) і їх небагато:
  - `C_PLT` ([platform-bridge](../platform-bridge.concept.md)) — відкриття зовнішніх URL через `nw.Shell.openExternal`, **уже з fallback на `window.open`**.
  - `C_LOG` ([session-logging](../session-logging.concept.md)) — запис логів на диск через NW.js `fs.appendFile`, **уже fallback-safe**: поза NW.js файлова гілка стає no-op.
  - `C_CLP` ([clipboard](../clipboard.concept.md)) — обмін текстом схеми через буфер.
  - `C_FBR` ([browser-file-bridge](../browser-file-bridge.concept.md)) — завантаження файлів **уже через чистий веб** (`<input type=file>` + `FileReader`), без Node.
- **`C_APE`** ([app-entrypoint](../app-entrypoint.concept.md)) — bootstrap уже передбачає роботу і в браузері, і в десктоп-бандлі (locale fallback "en-US under Electron").
- **`C_IOF`** ([io-framework](../io-framework.concept.md)) — імпорт/експорт схем працює на рівні рядків, без файлової системи.
- Застосунок **повністю офлайновий** і не має локального HTTP-сервера.

Висновок із контексту: нативний слід CircuitJS1 малий, ізольований і вже здебільшого має веб-фолбеки. Це робить його **кращим кандидатом на легкий WebView-каркас, ніж типовий Electron-застосунок**.

---

## 🎯 Найбільш релевантне (прямі виграші)

### 1. System WebView Decoupling — заміна NW.js на системний WebView

**У source:** концепція №1 «Системна WebView-інтеграція». Замість вбудовування Chromium застосунок використовує `WebKitGTK` (Linux), `Edge WebView2` (Windows), системний WebView (Android). Нативні шаблони — `src/core/linux/`, `src/core/windows/`. Результат: дистрибутив 10–15 МБ замість 150 МБ+.

**Чому для CircuitJS1:** прямо б'є в найбільшу ваду поточної дистрибуції — розмір і споживання RAM бандла NW.js. Оскільки UI CircuitJS1 — це Canvas2D + DOM-меню (без екзотичних браузерних API), він добре лягає на можливості системних WebView. Нативні виклики, які треба зберегти, зведені до `C_PLT`/`C_LOG`/`C_CLP` і **вже мають веб-фолбеки**.

**Що взяти:**
- Сам каркас `webnative` як цільову платформу пакування для «легкого» build-таргета, паралельного існуючому NW.js (не заміна, а другий вихід `out/`).
- Нативні WebView-шаблони (`src/core/linux/src/webkit.cpp`, `src/core/windows/webview2.cpp`) як референс, якщо робити власний каркас без повної залежності від `webnative`.
- Підхід «завантажити локальний `index.html` за `file://` + дозволити доступ до локальних ресурсів» (див. «Запобігання CORS» у source).

**Ефект / вартість:** ефект високий (×10 зменшення розміру). Вартість — **дні-тижні**: треба перевірити, що GWT-вивід (`target/site/`) рендериться коректно у WebKitGTK і WebView2, і замінити три нативні виклики (див. п.2 нижче) на веб-еквіваленти. Рекомендується спочатку **спайк**: завантажити поточний `target/site/circuitjs.html` у голий WebKitGTK-каркас і пройти smoke-test (намалювати схему, запустити симуляцію, scope).

**Ризик:** *Render Inconsistency* (trade-off із source) — на машинах зі старим WebKitGTK/WebView2 частина CSS/JS може відрізнятися. Для CircuitJS1 ризик помірний (Canvas2D стабільний), але треба тестувати верстку діалогів. Зачіпає контракт `C_APE` (bootstrap припускав NW.js/Electron-середовище) і всі три нативні концепти нижче.

### 2. Platform-oriented SDK — тонкий шим над WebView IPC (мапиться на C_PLT)

**У source:** концепція №8 `@mindw1n/webnative-core`. Ховає відмінності per-OS WebView-мостів (`window.webkit.messageHandlers.*.postMessage` на Linux vs `window.chrome.webview.postMessage` на Windows) за єдиним `makeApiRequest(action, body): Promise`.

**Чому для CircuitJS1:** якщо застосунок переходить на системний WebView, його `C_PLT` ([platform-bridge](../platform-bridge.concept.md)) перестає мати доступ до `nw.Shell`/`fs` у сторінці й має звертатися до нативного шару саме через такий міст. `C_PLT` уже є природним місцем для цієї абстракції — він уже інкапсулює «детект рантайму + fallback». Достатньо розширити його з гілки «NW.js / browser» до «WebView-IPC / NW.js / browser».

**Що взяти:**
- Патерн єдиного асинхронного фасаду над per-OS `postMessage`.
- Правило «детект рантайму один раз, далі — прозорий виклик» (узгоджується з наявним `PlatformUtils.getPlatformInfo()`).

**Ефект / вартість:** середній ефект, **години-дні**. Це невелике розширення вже наявного концепту, а не нова підсистема.

**Ризик:** низький; зачіпає лише `C_PLT`. Головне — не розповзтися JSNI поза межі `C_PLT` (дотримати правило `JsniBoundary`).

### 3. Dynamic Tool Bootstrapping + AppImage-вихід для Linux

**У source:** концепція №10 «Самоналаштовувана екосистема» та №2 (prebuilds). CLI сам перевіряє наявність `appimagetool`/`makensis` у кеші, **завантажує з GitHub-релізів**, ставить `chmod 0o755` і пакує. Нові коміти (`b3c2fc6 4.0.0`, `94d8a2f`, `d22949d`) додали Windows-exe-білдер і AppImage за конвенцією `<name>.AppImage`.

**Чому для CircuitJS1:** збірка `scripts/dev_n_build.js` зараз для Linux віддає **каталог** `out/linux-x64`, а не самодостатній портативний файл. Патерн «CLI на вимогу тягне пакувальний бінарник і виробляє AppImage» — дешеве, ізольоване покращення build-скрипта, незалежне від рішення про WebView.

**Що взяти:**
- Крок «спакувати Linux-вихід як `CircuitJS1.AppImage`» (через `appimagetool`, що завантажується на вимогу й кешується — як у source).
- Патерн lazy-download + cache + `chmod` для зовнішніх пакувальних утиліт у `dev_n_build.js`.

**Ефект / вартість:** добрий UX-ефект (один портативний файл) за низьку ціну — **години**. Це **quick win**: не торкається коду застосунку, лише build-скрипта.

**Ризик:** мінімальний; новий крок паралельний наявним таргетам. Перевірити ліцензійну сумісність завантажуваних бінарників.

---

## 🔧 Середня релевантність (потребує рішення)

### 4. Docker-assisted Multi-runtime Packaging

**У source:** концепція №7. Складні тулчейни (Android SDK/Gradle, крос-пакування) інкапсульовані в Docker-образах (`mindw1n/webnative-android`); якщо таргет не збирається на хості — CLI прозоро загортає білд у контейнер.

**Чому для CircuitJS1:** проєкт уже декларує Windows/macOS/Linux і має Inno Setup для Windows. Складання не-Linux артефактів і Windows-інсталятора з Linux-хоста — біль; Docker-обгортка дала б відтворювані крос-білди в CI.

**Вердикт: відкласти.** CircuitJS1 уже отримує мультиплатформні NW.js-бінарники через `nw-builder` (який сам тягне готові NW.js-зборки per-OS), тож гострої потреби в Docker немає *поки тримаємось NW.js*. Рішення стає актуальним, якщо проєкт піде шляхом п.1 (власний WebView-каркас з нативною компіляцією C++ під кожну ОС) — тоді Docker-крос-білд із source стане прямою цінністю. Прив'язати це рішення до результату спайку п.1.

### 5. Build-Time Platform Aliasing

**У source:** концепція №6. Webpack-аліаси підмінюють `app/api/${platform}.ts` за змінною `PLATFORM`, тримаючи бізнес-код вільним від `if (platform === …)`.

**Чому для CircuitJS1:** якщо з'явиться кілька рантайм-таргетів (NW.js / системний WebView / чистий браузер-PWA), знадобиться чисте перемикання реалізацій нативних викликів без розгалужень у доменному коді.

**Вердикт: взяти ідею, але не інструмент.** CircuitJS1 — GWT, не webpack. Той самий результат дає **GWT deferred binding** (`GWT.create` + `<replace-with>`/`<when-property-is>` у `circuitjs1.gwt.xml`): можна мати `PlatformBridge` з різними реалізаціями, обраними на етапі компіляції за property. Запозичується *патерн* (вибір платформи на build-time, домен без умовного коду), реалізація — нативна для GWT. Стосується `C_PLT`, `C_LOG`, `C_CLP`.

---

## ❌ Менш релевантне / не брати

| Ідея з webnative | Причина не брати |
|---|---|
| 3. Forked Node.js Lifecycle | У CircuitJS1 немає бекенду, який треба форкати — застосунок чисто клієнтський. |
| 4. Secure Native IPC Pipe (pipe/Named Pipe) | Немає локального бекенд-процесу → нема каналу для приватного IPC між шаром і сервером. |
| 5. Ephemeral Port-Key Exchange (X-API-Key) | Немає локального HTTP-сервера → немає приватного API, який треба захищати токеном. |
| 9. Application Lifecycle Integrity (SIGTERM/TerminateProcess) | Немає дочірнього Node-процесу → немає зомбі, якими треба керувати. |
| Alt-backends (Python/Flask через пайп) | Сценарій передбачає серверний шар, якого в CircuitJS1 концептуально немає. |
| Android/iOS/Capacitor таргети | Поза scope: CircuitJS1 Desktop орієнтований на десктоп; мобільний Canvas-UX — окрема велика тема, не запозичення. |

---

## 💡 Похідні ідеї (синтез)

- **Ідея: «Web-API-only» режим — прибрати залежність від in-page Node взагалі.**
  **Inspired by:** концепції №1 (System WebView) і «Запобігання CORS» з webnative + наявні `C_PLT`/`C_LOG`/`C_CLP`/`C_FBR`.
  **Чому цікаво:** на відміну від типового Electron-застосунку, CircuitJS1 використовує Node лише для трьох речей: запис логів на диск (`C_LOG`), відкриття URL (`C_PLT`), буфер обміну (`C_CLP`); завантаження файлів (`C_FBR`) уже чисто веб. Якщо замінити ці три на веб-API (`navigator.clipboard`, File System Access API / завантаження blob, `window.open`), CircuitJS1 зможе працювати у **будь-якому** WebView **без жодного бекенду й IPC-моста** — тобто отримати виграш webnative за розміром, *оминувши* всю його серверну машинерію. Бонус: той самий артефакт стає повноцінним браузерним PWA.
  **Що вимагає:** аудит трьох концептів на предмет «чи є адекватний веб-API»; спайк із File System Access API для збереження `.txt`/`.cjs` схем; оцінка деградації файл-логування (можливо, лише in-memory + ручний експорт лог-файлу).

- **Ідея: подвійний build-таргет `out/nwjs-*` + `out/webview-*` за спільного GWT-виводу.**
  **Inspired by:** концепції №2 (prebuilt shells) і №6 (platform aliasing) з webnative.
  **Чому цікаво:** дозволяє ввести легкий WebView-каркас поступово, без ризику для робочого NW.js-релізу; GWT-вивід (`target/site/`) спільний, різниться лише обгортка-каркас і реалізація `PlatformBridge`. Користувач/CI обирає таргет.
  **Що вимагає:** параметризувати `dev_n_build.js` за типом каркаса; узгодити з GWT deferred binding (п.5); результат спайку п.1.

- **Ідея: уніфікувати DevTools-діагностику під прапором `env: development`.**
  **Inspired by:** розділ «Можливості діагностики» webnative (`webkit_web_inspector_show`, `OpenDevToolsWindow`).
  **Чому цікаво:** CircuitJS1 уже має власний remote-debug harness (`server/remote-debug-server.js`, `docs/remote_dbg.md`). У системному WebView відкриття вбудованого інспектора за конфіг-прапором — простіший шлях, ніж зовнішній remote-debug-сервер.
  **Що вимагає:** прив'язано до рішення про WebView-каркас (п.1); до того моменту — не актуально.

---

## 📋 Рекомендований порядок дій

| # | Зміна | Файли | Рекомендована phase | Estimate |
|---|---|---|---|---|
| 1 | **Quick win:** AppImage-вихід для Linux + lazy-download `appimagetool` (п.3) | `scripts/dev_n_build.js` | concept-spec-plan (build-only, малий) | години |
| 2 | **Спайк:** завантажити `target/site/circuitjs.html` у голий WebKitGTK-каркас, smoke-test (малювання/симуляція/scope) (п.1) | — (експеримент) | spike | дні |
| 3 | Аудит `C_PLT`/`C_LOG`/`C_CLP` на веб-API-еквіваленти; «Web-API-only» режим (синтез #1) | `C_PLT`, `C_LOG`, `C_CLP` | concept + spec | дні |
| 4 | Розширити `C_PLT` до тонкого WebView-IPC-фасаду (п.2) | `C_PLT` (`PlatformUtils`) | spec-plan | години-дні |
| 5 | GWT deferred binding для `PlatformBridge` + подвійний build-таргет (п.5, синтез #2) | `circuitjs1.gwt.xml`, `dev_n_build.js` | plan | дні |
| 6 | Docker-крос-білд (п.4) — лише якщо обрано власний C++ WebView-каркас | `scripts/`, нові Dockerfile | — (умовно) | тижні |

**Блокери та залежності:** пункти 4–6 заблоковані результатом спайку №2 — якщо системний WebView не дає прийнятного рендеру GWT-виводу, шлях п.1 закривається й усе нижче по таблиці втрачає сенс (лишається тільки quick win №1, який самодостатній). Пункт 3 («Web-API-only») варто робити *перед* №4–5: якщо веб-API покриють усі три нативні виклики, потреба у WebView-IPC-мості (№4) і навіть у будь-якому бекенді зникає взагалі. Пункт 6 — найдорожчий і умовний; не починати без №1+№2.

---

## Джерела

- Проєкт: `/hdd/STORE/ext_repos/webnative` (ключові файли: `src/core/linux/src/webkit.cpp`, `src/core/windows/webview2.cpp`, `src/index.ts`, `prebuilds/`, `readme.md`)
- Концептуальний аналіз: `/hdd/STORE/ext_repos/webnative.concept.md` (коміт `cd44117`, реліз 4.0.0)
- Документи цільового проєкту: [platform-bridge](../platform-bridge.concept.md) `C_PLT`, [browser-file-bridge](../browser-file-bridge.concept.md) `C_FBR`, [session-logging](../session-logging.concept.md) `C_LOG`, [clipboard](../clipboard.concept.md) `C_CLP`, [app-entrypoint](../app-entrypoint.concept.md) `C_APE`, [io-framework](../io-framework.concept.md) `C_IOF`, [project.md](../project.md)
- Upstream: https://github.com/kl1ro/webnative.git

## Changelog

- 2026-06-21: початковий adoption-документ. Аналіз `webnative` 4.0.0 (`cd44117`) проти CircuitJS1 Desktop Mod. Ключовий висновок: релевантний лише шар пакування (System WebView vs NW.js); серверна архітектура webnative не застосовна (CircuitJS1 — чистий клієнт). Quick win — AppImage-вихід.
