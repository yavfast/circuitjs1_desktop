# Контекст задачі: Новий JSON експорт для CircuitJS1

## Мета
Реалізувати новий JSON формат експорту схем (версія 2.0), який є самодокументованим та людино-читабельним.

## Виконана робота

### 1. IO Архітектура (✅ Завершено)

Створено пакет `com.lushprojects.circuitjs1.client.io` з інтерфейсами:

| Файл | Опис |
|------|------|
| `CircuitFormat.java` | Інтерфейс формату: getId(), getName(), getExtensions() |
| `CircuitExporter.java` | export(document), exportSelection(document, list) |
| `CircuitImporter.java` | canImport(data), importCircuit(document, data, flags) |
| `CircuitFormatRegistry.java` | Статичний реєстр форматів з автовизначенням |

### 2. Text Format (✅ Завершено)

Пакет `io.text`:
- `TextCircuitFormat.java` - Розширення: ".txt", ".circuitjs"
- `TextCircuitExporter.java` - Експорт у старий текстовий формат
- `TextCircuitImporter.java` - Імпорт зі старого формату

### 3. JSON Format (✅ Завершено)

Пакет `io.json`:
- `JsonCircuitFormat.java` - Розширення: ".json", версія 2.0
- `JsonCircuitExporter.java` - Повний експорт у JSON
- `JsonCircuitImporter.java` - **Повна реалізація імпорту**
- `CircuitElementFactory.java` - Фабрика елементів для імпорту
- `UnitParser.java` - Парсинг значень з одиницями (10 kOhm → 10000)

### 4. JSON Експорт - секції

| Секція | Статус | Опис |
|--------|--------|------|
| `schema` | ✅ | format, version |
| `simulation` | ✅ | time_step, display options, voltage_range |
| `elements` | ✅ | Всі 145 елементів з properties, pins, bounds |
| `nodes` | ✅ | Вузли де 3+ пінів з'єднуються |
| `scopes` | ✅ | Осцилографи з повною конфігурацією |
| `adjustables` | ✅ | Слайдери з діапазонами та значеннями |

### 5. JSON Імпорт - секції

| Секція | Статус | Опис |
|--------|--------|------|
| `schema` | ✅ | Валідація формату та версії |
| `simulation` | ✅ | time_step, display options, speed |
| `elements` | ✅ | Створення через CircuitElementFactory |
| `scopes` | ✅ | Відновлення осцилографів |
| `adjustables` | ✅ | Відновлення слайдерів |

### 6. Елементи з applyJsonProperties() (✅ Реалізовано)

Додано метод імпорту до елементів:
- `CircuitElm` (базовий) - applyJsonProperties(), applyJsonPinPositions(), getJsonDouble/Int/Boolean/String helpers
- `ResistorElm` - resistance
- `CapacitorElm` - capacitance, initial_voltage, series_resistance, back_euler
- `InductorElm` - inductance, initial_current, back_euler
- `TransistorElm` - beta, model
- `DiodeElm` - model
- `LEDElm` - color_r/g/b, max_brightness_current
- `MosfetElm` - threshold_voltage, beta, digital, body_diode, body_terminal
- `VoltageElm` - max_voltage, dc_offset, frequency, phase_shift, duty_cycle

### 7. CircuitElementFactory (✅ Завершено)

Мапінг 120+ JSON типів до Java класів:
- Базові: Wire, Ground, Resistor, Capacitor, Inductor, Potentiometer
- Джерела: DCVoltage, ACVoltage, Rail, CurrentSource, Noise, AM, FM
- Напівпровідники: Diode, LED, TransistorNPN/PNP, NMOSFET/PMOSFET, SCR, Triac
- Логіка: AndGate, OrGate, DFlipFlop, Counter, Timer555
- І багато інших...

## Структура JSON експорту

```json
{
  "schema": { "format": "circuitjs", "version": "2.0" },
  "simulation": { "time_step": "5 us", "display": {...} },
  "elements": {
    "R1": {
      "type": "Resistor",
      "properties": { "resistance": "10 kΩ" },
      "pins": {
        "pin1": { "position": {"x": 100, "y": 200}, "connected_to": ["V1.positive"] },
        "pin2": { "position": {"x": 200, "y": 200}, "connected_to": ["N1"] }
      }
    }
  },
  "nodes": {
    "N1": { "position": {"x": 200, "y": 200}, "connections": ["R1.pin2", "C1.pin1", "L1.pin1"] }
  },
  "scopes": [{
    "element": "Q1",
    "speed": 64,
    "display": { "show_voltage": true, "show_current": false },
    "plots": [{ "element": "Q1", "units": "V", "color": "#00FF00" }]
  }],
  "adjustables": [{
    "element": "R1",
    "edit_item": 0,
    "label": "Resistance",
    "min_value": 100,
    "max_value": 100000,
    "current_value": 10000
  }]
}
```

## Що залишилось

1. **UI інтеграція** - Меню для вибору формату експорту
2. **Тестування** - Round-trip тести експорт→імпорт
3. **Додаткові елементи** - applyJsonProperties() для решти елементів

## Команди

```bash
# Компіляція
mvn compile -q

# Повна збірка
node ./scripts/dev_n_build.js --buildall
```

## Останній успішний білд

- Дата: 2025-11-27
- GWT компіляція: ✅
- Імпортер: ✅ Повністю реалізований
- Фабрика елементів: ✅
