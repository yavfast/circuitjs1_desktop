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
- `JsonCircuitImporter.java` - Заглушка (не реалізовано)

### 4. JSON Експорт - секції

| Секція | Статус | Опис |
|--------|--------|------|
| `schema` | ✅ | format, version |
| `simulation` | ✅ | time_step, display options, voltage_range |
| `elements` | ✅ | Всі 145 елементів з properties, pins, bounds |
| `nodes` | ✅ | Вузли де 3+ пінів з'єднуються |
| `scopes` | ✅ | Осцилографи з повною конфігурацією |
| `adjustables` | ✅ | Слайдери з діапазонами та значеннями |

### 5. Елементи (✅ Завершено - 145 класів)

Додано методи до базового класу `CircuitElm`:
- `getJsonTypeName()` - Тип елемента
- `getJsonProperties()` - Map властивостей з одиницями
- `getJsonPinNames()` - Масив імен пінів
- `getJsonPinPosition(int)` - Координати піна
- `getJsonBounds()` - Обмежуючий прямокутник
- `getJsonFlags()` - Прапорці елемента

Кожен з 145 класів елементів перевизначає ці методи відповідно до своєї специфіки.

### 6. Додаткові зміни

Додано публічні геттери:
- `ScopePlot.getElm()` 
- `Adjustable.getElm()`
- `Adjustable.getEditItem()`
- `Adjustable.getSliderValue()` (змінено на public)

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

## Файли документації

- `docs/export_new_json.md` - Повна специфікація формату

## Що залишилось

1. **JsonCircuitImporter** - Реалізація імпорту з JSON
2. **Тестування** - Round-trip тести експорт→імпорт
3. **UI інтеграція** - Меню для вибору формату експорту

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
- WAR: ✅
- Linux x64 release: ✅
