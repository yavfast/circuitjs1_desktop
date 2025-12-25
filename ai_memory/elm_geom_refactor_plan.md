# План рефакторингу: перенесення геометричної логіки `CircuitElm` → `ElmGeometry`

Дата: 2025-12-25

### Останні оновлення (2025-12-25)
- Додано хук `adjustDerivedGeometry(ElmGeometry)` у `MosfetElm` (перенесено з помилкового місця всередині `draw()` в область класу), виправлено синтаксичну помилку; білд пройшов.
- Додано/підтверджено хук у `TransistorElm` (no-op placeholder) — готово як миграційна точка для транзисторів.
- Запуск `npm run buildgwt` → **BUILD SUCCESS** (повна збірка пройшла після правки).
- Додав/оновив TODOs: хук додано та змінено кілька елементів на використання getter-ів; подальша міграція елементів — **в процесі**.
- NW.js інтерактивні smoke-тести тимчасово заблоковані локально через проблеми з драйверами/Vulkan/ANGLE (поставлено як відкладене завдання).


## 0) Мета та межі

### Мета
Зосередити **всю логіку роботи з геометричними полями** елемента в одному місці (`ElmGeometry`), а `CircuitElm` залишити як **сумісний фасад** (з мінімальними проксі-методами) та як точку інтеграції з симуляцією/рендером.

### Поля у фокусі (з `CircuitElm`)
- Кінцеві точки: `x, y, x2, y2`
- Похідні величини: `dx, dy, dsign, dn, dpx1, dpy1`
- Точки/посилання: `point1, point2, lead1, lead2`
- Рамка вибору: `boundingBox`

### Основне обмеження
У кодовій базі багато підкласів і логіки напряму читають/пишуть ці поля (особливо `dx/dy/dn/dsign/dpx1/dpy1`). Тому рефакторинг має бути **поетапним**, із **збереженням зворотної сумісності** на кожному кроці.

## 1) Поточний стан (baseline)

У проекті вже є `ElmGeometry` (фаза-1), який:
- Обчислює похідні (`dx/dy/dsign/dn/dpx1/dpy1`) з `x/y/x2/y2`.
- Синхронізує `owner.*` поля (щоб старий код продовжував працювати).
- Реалізує базові трансформації: drag/translate/movePoint/flipX/flipY/flipXY/flipPosts.
- Підтримує `boundingBox` через `initBoundingBox()/setBbox()/adjustBbox()`.
- Рахує `lead1/lead2` + `adjustLeadsToGrid()`.

Водночас у `CircuitElm` та підкласах все ще є:
- Прямі записи в `x/y/x2/y2` (наприклад, JSON import/legacy undump, внутрішні елементи, окремі `setPoints()` у підкласах).
- Прямі читання похідних полів в отрисовці/геометрії (дуже багато).

## 2) Цільова архітектура (target)

### 2.1 Відповідальність класів
- `ElmGeometry`:
  - Єдине джерело істини про геометрію: керує `endpoints`, `derived`, `points`, `leads`, `bbox`.
  - Надає API для безпечної зміни геометрії: `setEndpoints`, `translate`, `dragTo`, `movePoint`, `flip*`, `calcLeads`, `setBbox`.
  - Гарантує інваріанти синхронізації та не-аліасинг, де це критично (наприклад, `lead1/lead2` не повинні випадково бути тим самим об’єктом що `point1/point2`, якщо ми їх рухаємо).

- `CircuitElm`:
  - Виступає фасадом/адаптером для підкласів та існуючого коду.
  - Не містить складної логіки оновлення геометрії: лише делегує в `geom()`.
  - На пізніх етапах: поля перестають бути “первинними” (можуть стати `@Deprecated` або змінити видимість), а доступ до геометрії йде через методи.

### 2.2 Інваріанти (must hold)
1) Після будь-якої зміни `x/y/x2/y2` повинні бути коректні `dx/dy/dsign/dn/dpx1/dpy1` і `point1/point2`.
2) `boundingBox` відображає актуальні координати (для selection/drag).
3) Операції, які рухають leads, не можуть випадково рухати endpoints (виправлення аліасингу).
4) Для елементів, які “підкручують” `dn` або інші derived у `setPoints()` (коментар у `ElmGeometry.calcLeads`), поведінка має зберігатися.

## 3) План міграції (поетапно)

Нижче — рекомендований порядок робіт, який дозволяє зупинятися після кожної фази з робочим білдом.

### Фаза A — Інвентаризація і класифікація використань (status: done)
**Ціль:** зрозуміти, де саме “жива” геометрична логіка ще знаходиться поза `ElmGeometry`.

1) Зібрати мапу використань полів:
   - Пошук по проекту: `x/y/x2/y2`, `dx/dy/dsign/dn/dpx1/dpy1`, `point1/point2/lead1/lead2`, `boundingBox`.
   - Розділити на категорії:
     - **Read-only** (отрисовка/розрахунки без мутації).
     - **Write endpoints** (пряме присвоєння `x/y/x2/y2`).
     - **Write derived** (прямі присвоєння `dn`, `dsign`, `dpx1` тощо).
     - **Write points/leads/bbox**.

2) Зробити список “проблемних” підкласів:
   - Ті, що override `setPoints()` і змінюють `dn`/`dsign`.
   - Ті, що мають >2 постів або нестандартну геометрію pins (трансформери, транзистори, опампи).

**Результат фази:** таблиця/список класів і типів доступу (для контролю прогресу).

Виконано (поточна сесія):
- [x] Знайдені ключові “write endpoints” місця (JSON import, drag/flip у кількох елементів).
- [x] Зібрані приклади “write derived” у `setPoints()` (SCR/Triac/Transistor/Transformer).
- [x] Зафіксовано у [ai_memory/elm_geom_refactor_inventory.md](ai_memory/elm_geom_refactor_inventory.md).

---

### Фаза B — Єдині точки входу для мутації endpoints (status: in-progress)
**Ціль:** мінімізувати прямі записи в `x/y/x2/y2` поза `ElmGeometry`.

1) Додати/закріпити в `CircuitElm` канонічні методи:
   - `setPosition(...)` вже делегує в `geom().setEndpoints(...)` — залишити як canonical.
   - Додати (або стандартизувати використання) методи:
     - `setEndpoints(int x1, int y1, int x2, int y2)` → делегат в `ElmGeometry`.
     - `setStart(int x, int y)` / `setEnd(int x2, int y2)` (опційно) — якщо часто треба змінювати лише одну точку.

2) Замінити прямі присвоєння в “ядрі” (не у всіх підкласах одразу):
   - JSON import: `applyJsonPinPositions(...)` і подібні місця — переписати на використання `setEndpoints(...)` або `geom().setEndpoints(...)`.
   - Після цього `finalizeJsonImport()` має робити мінімум (або лише `geom().updatePointsFromEndpoints()` якщо були специфічні винятки).

3) Протокол змін:
   - Якщо зараз існують місця, де `x/y/x2/y2` змінюються напряму і тільки потім викликається `setPoints()`, то тимчасово можна лишити як є, але додати правило: **будь-яка мутація endpoints MUST завершуватися викликом `setPoints()`**.

**Результат фази:** менше “ручних” записів; більше змін йде через `ElmGeometry`.

Виконано (поточна сесія):
- [x] Додано `CircuitElm.setEndpoints(...)`.
- [x] JSON import (`applyJsonPinPositions`) переведено на `setEndpoints(...)`.
- [x] `finalizeJsonImport()` робить `initBoundingBox()` перед `setPoints()`.
- [x] Міграція кількох write-sites: `DPDTSwitchElm.flip()`, `ChipElm.drag()`, `TransLineElm.drag()`, `WattmeterElm.drag()`, конструктори `TransformerElm`/`TappedTransformerElm`.
- [x] `WattmeterElm` (setPoints + draw) — перейшов на getter-снапшоти `getDn()/getDx()/getDy()` для розрахунків; перевірка білдом пройшла.
- [x] `TransistorElm` (setPoints + draw) — замінено використання `dsign`/`dn`/`dx`/`dy` на локальні сніпшоти через `getDsign()/getDn()/getDx()/getDy()` (flip semantics збережено локально); білд пройшов успішно.
- [x] `TriacElm` (setPoints) — replaced direct `dn`/`point2` assignments with `geom().setEndpoints(...)` and switched subsequent usage to `getDn()`; build verified.

Додатково (мітігація з розділу E/"Dirty" модель):
- [x] Додано `ElmGeometry.ensureUpToDate()` і виклик з `CircuitElm.geom()` для толерантності до legacy direct writes.

---

### Фаза C — Консолідація похідних величин (derived) (status: in-progress)
**Ціль:** перестати вважати `dx/dy/dn/...` “первинними” полями `CircuitElm`.

1) Зафіксувати джерело істини:
   - `ElmGeometry.updatePointsFromEndpoints()` — єдиний метод, який має обчислювати `dx/dy/dsign/dn/dpx1/dpy1`.

2) Додати у `CircuitElm` легкі getter-и (без зміни API підкласів):
   - `getDx() / getDy() / getDn() / getDpx1() ...`
   - На першому кроці вони можуть повертати `this.dx` тощо (щоб не ламати нічого), але ідея — створити “шлях міграції”, щоб потім переключити їх на `geom` або зробити `ensureGeometryUpdated()`.

3) Поступово переводити підкласи з прямого доступу до полів на getter-и:
   - Пріоритет: елементи з простими `setPoints()` (двохвивідні), потім складні.
   - Це **довга робота**, але вона суттєво зменшить surface area для майбутніх змін видимості полів.

4) Винятки/особливості:
   - Деякі елементи (з історичного коду) змінюють `dn` у `setPoints()` для UI-геометрії. Потрібно:
     - або перенести таку “підкрутку” в явний hook `ElmGeometry` (наприклад, `owner.adjustDerivedGeometry(geom)`),
     - або задокументувати як allowed-override на певний час.

**Результат фази:** з’являється уніфікована абстракція доступу до derived, яка дозволяє пізніше сховати поля.

Виконано (поточна сесія):
- [x] Додано `ensureGeometryUpdated()` та getter-и `getDx/getDy/getDsign/getDn/getDpx1/getDpy1`.
- [x] Додано `ElmGeometry.recomputeDerivedWithMinDn()` і оновлено `TransformerElm.adjustDerivedGeometry()` для використання хелперу; білд та скрипт перевірки геометрії пройшли.
- [x] Перші міграції read-only використань на getter-и (напр. `CircuitElm.drawValues()`, `DPDTSwitchElm.flip()`).
- [x] Розширено міграцію read-only derived на getter-и (перші безпечні пачки):
   - [x] `GroundElm` (draw)
   - [x] `ComparatorElm` (setPoints + flipX/flipY)
   - [x] `OpAmpElm` (setPoints)
   - [x] `OpAmpRealElm` (setPoints)
   - [x] `OTAElm` (setPoints)
   - [x] `JfetElm` (setPoints)
   - [x] `ProbeElm` (draw)
   - [x] `VoltageElm` (draw + drawWaveform)
   - [x] `RelayCoilElm` (setPoints)
   - [x] `RelayContactElm` (draw + setPoints)
   - [x] `DarlingtonElm` (draw + setPoints)
   - [x] `AmmeterElm` (draw)
   - [x] `PolarCapacitorElm` (setPoints)
   - [x] `GateElm` (setPoints; high leverage for gate subclasses)
   - [x] `AndGateElm` (setPoints + draw)
   - [x] `OrGateElm` (setPoints + draw)
   - [x] `InverterElm` (setPoints)
   - [x] `InvertingSchmittElm` (setPoints)
   - [x] `PotElm` (setPoints + draw)
   - [x] `JfetElm` (setPoints)
   - [x] `MosfetElm` (setPoints + draw)
   - [x] `SchmittElm` (setPoints)
   - [x] `DelayBufferElm` (setPoints)
   - [x] `TriStateElm` (setPoints)
   - [x] `MosfetElm` (setPoints)
   - [x] `LEDElm` (setPoints)
   - [x] `LogicInputElm` (setPoints)
   - [x] `LogicOutputElm` (setPoints)
   - [x] `OutputElm` (draw)
   - [x] `LabeledNodeElm` (setPoints + draw)
   - [x] `StopTriggerElm` (setPoints)
   - [x] `TestPointElm` (draw)
   - [x] `CrystalElm` (setPoints)
   - [x] `CrossSwitchElm` (setPoints)
   - [x] `FMElm` (setPoints)
   - [x] `AMElm` (setPoints)
   - [x] `CurrentElm` (draw)
   - [x] `LampElm` (draw)
   - [x] `RailElm` (setPoints + draw)
   - [x] `SweepElm` (setPoints + draw)
   - [x] `AudioOutputElm` (draw)
   - [x] `DataRecorderElm` (setPoints)
   - [x] `SparkGapElm` (setPoints)

---

### Фаза D — Консолідація points/leads/bbox та helper-ів (status: in-progress)
**Ціль:** щоб `point1/point2/lead1/lead2/boundingBox` ніколи не були в неконсистентному стані.

1) `point1/point2`:
   - Зафіксувати: `point1 == (x,y)`, `point2 == (x2,y2)` для базових 2-terminal.
   - Для елементів зі складною геометрією: дозволити override (але бажано через спеціалізований API).

2) `lead1/lead2`:
   - У `ElmGeometry` уже є логіка не-аліасингу у `adjustLeadsToGrid()`.
   - Перевірити “тонкі місця”: де `lead1 = point1` або `lead2 = point2` очікується.
   - Зробити рекомендацію: операції, що рухають leads, **мають гарантувати**, що endpoints не рухаються.

3) `boundingBox`:
   - Закріпити правило: будь-який метод, який змінює геометрію, має або:
     - оновлювати bbox інкрементально, або
     - перевиставляти bbox (через `setBbox/initBoundingBox`) і потім розширювати `adjustBbox` у `setPoints()`.

4) Перенесення дрібних геометричних helper-ів з `CircuitElm` (опційно, але бажано):
   - `creationFailed()` → `ElmGeometry.isZeroSize()` (бо це чиста геометрія).
   - `getHandlePoint()` для базового випадку (2 endpoint handles) → `ElmGeometry.getHandlePoint(n)`.
   - `getMouseDistance(...)` для базових елементів → `ElmGeometry.mouseDistance(...)`.

**Результат фази:** `CircuitElm` має мінімум “геометричних” рішень.

Виконано (поточна сесія):
- [x] `creationFailed()` делегує в `ElmGeometry.isZeroSize()`.
- [x] Базовий `getHandlePoint()` делегує в `ElmGeometry.getHandlePoint(n)`.
- [x] Базовий `getMouseDistance()` делегує в `ElmGeometry.getMouseDistanceSq(...)`.

---

### Фаза E — Депрекейт/інкапсуляція полів (довгостроково)
**Ціль:** поступово прибирати прямий доступ до полів без різкого “breaking change”.

1) М’яка депрекейшн стратегія:
   - Позначити `dx/dy/dsign/dn/dpx1/dpy1` як `@Deprecated` (спочатку тільки Javadoc попередження, потім анотації).
   - **Soft-encapsulation:** змінено видимість полів з `public` на `protected`, щоб зменшити публічну surface area і спонукати використовувати getter-и або `geom()` (зроблено 2025-12-25).
   - Додати коментар/правило: “не писати напряму — використовуйте API `geom()`/getter-и”.

2) Видимість:
   - На останньому етапі (коли більшість підкласів мігрувала) перевести деякі поля з `public` в `protected` або package-private.
   - Для JSNI (`addJSMethods()`), де потрібен прямий доступ до `x/y/x2/y2`, варіанти:
     - лишити endpoints public ще довго, або
     - зробити JSNI читання через методи (але це вимагатиме більших змін).

3) “Dirty” модель (тільки якщо стане потрібно):
   - Якщо ще залишаться прямі записи в endpoints, можна додати явний контракт: “після прямого запису виклич `setPoints()`”.
   - Автоматично відловити прямі записи без інкапсуляції в Java майже неможливо, тому або інкапсулюємо поля, або живемо з контрактом.

**Результат фази:** реальна інкапсуляція геометрії, менше випадкових регресій.

## 4) Перевірки і критерії готовності (Definition of Done)

### Мінімальні критерії (для проміжних фаз)
- Білд проходить (`npm run buildgwt`).
- Заощаджуючи час при роботі з невеликими правками: **повний білд** (`npm run buildgwt`) виконувати **лише після складних змін**, що зачіпають кілька файлів або можуть вплинути на результат компіляції; для простих локальних змін виконувати цільову/локальну перевірку (швидка компіляція змінених файлів, модульні тести або інші швидкі перевірки).
- Ручна перевірка в UI:
  - drag/move/flip працюють
  - selection по bounding box працює
  - leads не “з’їжджають” і не рухають endpoints
  - undo/redo (якщо зачіпали) не ламається

### Кінцева ціль (після фаз C–E)
- Більшість елементів не читає `dx/dy/dn/...` напряму (використовує getter-и або API geometry).
- Всі мутації endpoints проходять через `ElmGeometry` (або формально завершуються `setPoints()`).
- `ElmGeometry` є єдиним місцем, де обчислюються derived величини.

## 5) Ризики та як їх мінімізувати

- **Депрекейція похідних полів:** Додано Javadoc `@deprecated` для полів `dx/dy/dn/dsign/dpx1/dpy1` в `CircuitElm`. Рекомендуємо використовувати `getDx()/getDy()/getDn()/getDsign()/getDpx1()/getDpy1()` або `geom()` API.

- **Ризик:** підкласи, що мутують `dn/dsign` у `setPoints()`, можуть змінити поведінку `calcLeads()`/bbox.
  - **Мітігація:** виділити їх у список “особливих”; для них узгодити окремий хук/контракт.

**Soft deprecation (progress):**
- [x] Додано `@Deprecated` та Javadoc для полів `dx`, `dy`, `dsign`, `dn`, `dpx1`, `dpy1` у `CircuitElm` (м'яка депрекейшн-стратегія).  
  Причина: попередити нові write-sites; надалі використовувати `getDx()/getDy()/getDn()/geom()`.

- **Ризик:** елементи з нестандартними pins (transistor/transformer/opamp) можуть мати `point1/point2` не рівні “першому/другому пінам”.
  - **Мітігація:** не форсувати жорстко `point1/point2` як pins; залишити можливість override через `getJsonStartPoint()/getJsonEndPoint()` і `getJsonPinPosition()`.

- **Ризик:** приховування полів може ламати JSNI/рефлексію.
  - **Мітігація:** тримати public endpoints довше; переносити JS API на методи лише при готовності.

## 6) Рекомендований порядок комітів (дрібні, безпечні кроки)

1) Додати документацію/контракти в `CircuitElm` та `ElmGeometry` (що є первинним, що derived).
2) Перевести JSON import на `geom().setEndpoints(...)`.
3) Додати getter-и для derived.
4) Почати міграцію 5–10 простих елементів на getter-и.
5) Винести `creationFailed()/getHandlePoint()/getMouseDistance()` в `ElmGeometry` (за бажанням).
6) Поступово депрекейтити прямий доступ.

## 7) Керування планом та змінами (план — “живий” документ)

### 7.1 Уточнення/розширення плану при зміні обставин
- Якщо під час роботи змінюються вимоги, з’являються нові обмеження або уточнюється контекст (наприклад: JSNI-вимоги, нестандартні pins у певних елементів, регресії у UI), цей план можна і потрібно уточнювати.
- Дозволено:
  - уточнювати формулювання існуючих кроків;
  - додавати нові підпункти/фази;
  - змінювати порядок/пріоритети виконання.
- Умови: зміни плану не мають порушувати інваріанти з розділу 2.2 і повинні зберігати зворотну сумісність між фазами.

### 7.2 Фіксація прогресу: позначення виконаних пунктів
- Після виконання пункту або підпункту слід позначати його як виконаний, щоб було видно фактичний прогрес.
- Рекомендований формат позначок:
  - для фаз: `### Фаза B — ... (status: planned | in-progress | done)`
  - для підпунктів: чекбокси `- [ ] ...` → `- [x] ...`

### 7.3 Дозволені корекції коду та виправлення помилок
- Під час рефакторингу дозволено (і за потреби необхідно) змінювати логіку коду та виправляти знайдені помилки, якщо це потрібно для:
  - збереження поведінки та зворотної сумісності;
  - підтримки геометричних інваріантів (endpoints/derived/points/leads/bbox);
  - усунення регресій, які проявилися через міграцію.
- Правило безпечної зони: фікси мають бути мінімальними, бажано локалізованими до геометрії/рендеру, і супроводжуватися перевірками з розділу 4.
