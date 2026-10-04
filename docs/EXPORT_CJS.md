# Новий формат файлу схем CircuitJS1 (JSON)

Цей документ описує концепцію нового, зрозумілого формату експорту схем у форматі JSON, який не потребує додаткової документації для розуміння.

> **Відповідність реалізації (перевірено 2026-09-30, аудит PL_AUDIT_20260930_173830; оновлено 2026-10-02, PL_AGA Phase 10).** Приклади нижче — концепція; частину полів і назв типів реалізація не приймає. Авторитетними є `JsonCircuitExporter` / `JsonCircuitImporter` / `CircuitElementFactory`:
>
> - `schema` **обов'язково** містить `"format": "circuitjs"` і `"version"`, що починається з `"2."` — інакше імпорт відхиляється.
> - `simulation`: `time_step`, `min_time_step`, `voltage_range` (рядки з одиницями SI), `simulation_speed`, `current_speed`, `power_brightness` (позиції повзунків), `auto_time_step`, `display` {`show_dots`, `show_voltage`, `show_power`, `show_values`, `small_grid`}, необов'язковий `hint` {`type`, `item1`, `item2`}. Поля `max_time_step`, `options`, `iteration_count` не читаються.
> - `type` елемента — ключ реєстру `CircuitElementFactory` (повний список — `CircuitElementFactory.getAllJsonTypeNames()`); назви на кшталт `DCVoltageSource`, `PolarizedCapacitor`, `NMOSFET`, `PNP`, `TextLabel` не зареєстровані.
> - `_flags` записується завжди (відсутність означає «прапорці конструктора за замовчуванням») і з 2026-10-03 дорівнює прапорцям, які пише текстовий дамп (разом із бітами формату — наприклад, 4 у конденсатора з послідовним опором), навіть для елемента, який ще не дампився; кожна властивість із `properties` читається назад тим самим елементом.
> - Числа з одиницями записуються без втрат (`UnitParser.parse` повертає рівно збережене значення), не залежать від налаштування точності відображення; `u` — мікро, `f` — фемто.
> - Списки в `state`/`properties` — масиви JSON; `bounds` — лише довідкова рамка, геометрію елемента задають піни.
> - Ключі елементів у `elements` — це ID елементів із реєстру ID документа (ті самі ID, що повертають `CircuitJS1.getElementIds()` і Agent API): форма `^[A-Za-z][A-Za-z0-9_]{0,31}$`, нумерація окремо для кожного префікса (`R1`, `R2`, `C1`, `W1`, `GND1`; транзистор — `TRA1`). Раніше експортер нумерував ключі одним спільним лічильником (`R1`, `C2`, `W3`). Імпорт (заміна вмісту) зберігає ключі як ID, якщо вони відповідають формі й унікальні; інакше генерується новий ID із попередженням `ids_regenerated` у журналі. Вставка JSON (paste) створює елементи з новими ID. Правила — [agent-api.sp.md §03_02](./agent-api.sp.md#SP_AGA_03_02).
> - `simulation.auto_time_step` записується завжди, також зі значенням `false`; якщо ключа немає, імпорт залишає поточне налаштування документа.
> - Піни провідника `Wire` називаються `a`/`b` (не `pin1`/`pin2`, як у прикладі нижче).
> - **Версія 2.1 (2026-10-03, [SP_AGA_DEC_06](./agent-api.sp.md#SP_AGA_DEC_06)): назви пінів полярних елементів відповідають їхній фактичній полярності.** Двовивідні джерела напруги (`VoltageSourceDC`, `VoltageSourceAC`, `VoltageSourceSquare` та інші форми сигналу) — `minus` (пін 0), `plus` (пін 1; на ньому напруга вища на `voltage`); джерело струму `CurrentSource` — `in` (пін 0), `out` (пін 1, вістря стрілки: струм виходить із джерела тут, тож на навантаженні `out` — вищий кінець); омметр `OhmMeter` — `com`, `probe` (пін 1, «червоний» щуп); операційний підсилювач `OpAmp` — завжди `in-`, `in+`, `out` незалежно від `swap_inputs` (прапорець лише дзеркалить малюнок). Полярний конденсатор (`positive` = пін 0, `negative`) не змінився. Експортер писав `"version": "2.1"` до версії 2.2 (нижче); імпорт приймає будь-яку `2.x`.
> - **Трансформатор `Transformer` (2.1, 2026-10-03):** піни `p1` (пін 0), `s1` (пін 1), `p2` (пін 2), `s2` (пін 3). Обмотки — `p1`–`p2` (первинна, індуктивність `inductance`) і `s1`–`s2` (вторинна, `inductance`·`ratio`²); `p1` і `s1` — синфазні («точкові») кінці. Попередні назви `pri1`, `pri2`, `sec1`, `sec2` іменували піни 0–3 по порядку й тому ставили `pri2` на вторинну обмотку, а `sec1` на первинну. Властивість `ratio` — це N2/N1 (витки вторинної на виток первинної; 10 — підвищення 1:10); діалог елемента показує обернене значення як «Ratio (N1/N2)». Значення у файлах не змінилося. `TappedTransformer` (`pri1`, `pri2`, `sec1`, `tap`, `sec2`) уже мав правильні назви.
> - **Файли 2.0 зі старими назвами завантажуються як раніше.** Імпорт читає застарілі назви як псевдоніми з їхнім старим значенням: для джерел напруги й струму `positive` → пін 0, `negative` → пін 1; для омметра `probe+` → пін 0, `probe-` → пін 1; для трансформатора `pri1`, `pri2`, `sec1`, `sec2` → піни 0, 1, 2, 3. (Тобто в старих файлах `positive` джерела напруги насправді був мінусовим виводом.) Старі збірки читають файли 2.1 через запасний шлях «за порядком ключів» — експортер пише піни в порядку виводів. Псевдоніми діють лише для імпорту JSON; в Agent API (`PostRef`) старі назви дають `unknown_post`.
> - **P-канальні польові транзистори (2026-10-04, [SP_AGA_DEC_08](./agent-api.sp.md#SP_AGA_DEC_08)):** `PMOS` і `PJFET` називають пін 1 `drain`, пін 2 `source` (N-канальні `NMOS`, `NJFET`: `source`, `drain`), як на малюнку (мітки D/S) і як підключено діод підкладки. Раніше P-канальні мали порядок N-канальних. Псевдонімів і зміни версії немає (JSON 2.x не гарантує сумісності між версіями): у старому файлі PMOS/PJFET зберігає геометрію і з'єднання `connected_to`, лише збережені `state.pins` витоку/стоку читаються навхрест.
> - `scopes[].label` — підпис осцилографа; `adjustables[].shared_slider` — індекс спільного повзунка.
> - **Версія 2.2 (2026-10-04, [SP_AGA_03_12](./agent-api.sp.md#SP_AGA_03_12)): секція `models` і налаштування осцилографа в схемі.** Експортер завжди пише `"version": "2.2"` і верхньорівневий масив `models` — лише коли схема використовує небудовану модель (діод, транзистор, custom logic, підсхему) — див. розділ [Моделі (models)](#моделі-models). Елемент `Scope` (осцилограф у схемі, текстовий рядок `403`) має властивість `scope` у формі запису секції `scopes` — див. [Осцилограф у схемі](#осцилограф-у-схемі-елемент-scope). Формат JSON 2.x ще в розробці й не обіцяє сумісності між своїми версіями (рішення розробника, 2026-10-04): поведінка інших збірок із файлами 2.2 не визначена.

## Основні принципи

1. **Самодокументований формат** — кожен параметр має зрозумілу назву
2. **Унікальна ідентифікація** — кожен елемент має унікальний ID (ключ об'єкта)
3. **Явні з'єднання** — кожен pin вказує, з яким pin іншого елемента він з'єднаний
4. **Вузли як елементи** — точки з'єднання є окремими елементами типу `Node`
5. **Дефолтні значення** — параметри зі значеннями за замовчуванням можна пропускати

## Структура файлу

Файл використовує формат JSON для універсальної сумісності та легкої обробки програмами.
**ID елементів є ключами об'єктів**, що гарантує їх унікальність та забезпечує швидкий доступ.

```json
{
  "schema": {
    "version": "2.2",
    "name": "Назва схеми",
    "description": "Опис схеми",
    "created": "2025-11-26",
    "author": "Автор"
  },

  "simulation": {
    "max_time_step": "5 us",
    "min_time_step": "5 ns",
    "iteration_count": 15,
    "voltage_range": "5 V",
    "current_scale": "50 mA",
    "power_scale": "26 mW",
    "options": {
      "show_current_dots": true,
      "show_voltage": true,
      "show_power": false,
      "auto_time_step": true
    }
  },

  "nodes": {
    "N1": {
      "position": {"x": 736, "y": 336},
      "connections": ["R1.pin2", "L1.pin1", "C3.pin1"]
    },
    "N2": {
      "position": {"x": 816, "y": 336},
      "connections": ["L1.pin2", "C2.pin1", "R2.pin1"]
    }
  },

  "elements": {
    "R1": {
      "type": "Resistor",
      "properties": {
        "resistance": "10 kOhm"
      },
      "pins": {...}
    },
    "R2": {
      "type": "Resistor",
      "properties": {
        "resistance": "1 kOhm"
      },
      "pins": {...}
    }
  }
}
```

---

## Позиціонування елементів

Положення та орієнтація кожного елемента визначається через:
1. **Абсолютні координати пінів** — однозначно задають положення та орієнтацію
2. **bounds** — обмежуючий прямокутник для редактора (виділення, переміщення)

### Принцип: координати пінів визначають все

Замість використання окремих параметрів `rotation` та `flip`, орієнтація елемента 
повністю визначається абсолютними координатами його пінів:

```json
{
  "Q1": {
    "type": "TransistorNPN",
    "pins": {
      "base": {"position": {"x": 268, "y": 300}},
      "collector": {"position": {"x": 300, "y": 252}},
      "emitter": {"position": {"x": 300, "y": 348}}
    }
  }
}
```

```json
{
  "Q2": {
    "type": "TransistorNPN",
    "pins": {
      "base": {"position": {"x": 332, "y": 300}},
      "collector": {"position": {"x": 300, "y": 252}},
      "emitter": {"position": {"x": 300, "y": 348}}
    }
  }
}
```

Візуалізація:
```
База зліва:           База справа:
    C                     C
    |                     |
B --|                     |-- B
    |                     |
    E                     E
```

### Структура bounds

Параметр `bounds` визначає область елемента на схемі для редактора.
Тип bounds визначається автоматично за наявними ключами:

#### Прямокутник (axis-aligned rectangle)

```json
{
  "bounds": {"left": 268, "top": 252, "right": 332, "bottom": 348}
}
```

| Ключ | Опис |
|------|------|
| `left` | X-координата лівого краю |
| `top` | Y-координата верхнього краю |
| `right` | X-координата правого краю |
| `bottom` | Y-координата нижнього краю |

#### Коло (circle)

```json
{
  "bounds": {"x": 300, "y": 300, "radius": 24}
}
```

| Ключ | Опис |
|------|------|
| `x` | X-координата центру |
| `y` | Y-координата центру |
| `radius` | Радіус кола |

#### Повернутий прямокутник (rotated rectangle)

Для елементів під довільним кутом:

```json
{
  "bounds": {"cx": 300, "cy": 200, "width": 64, "height": 16, "rotation": 45}
}
```

| Ключ | Опис |
|------|------|
| `cx` | X-координата центру |
| `cy` | Y-координата центру |
| `width` | Ширина прямокутника |
| `height` | Висота прямокутника |
| `rotation` | Кут повороту в градусах |

### Автовизначення типу bounds

| Наявні ключі | Тип |
|--------------|-----|
| `left, top, right, bottom` | axis-aligned rectangle |
| `x, y, radius` | circle |
| `cx, cy, width, height` | centered rectangle |
| `cx, cy, width, height, rotation` | rotated rectangle |

### Приклад повного опису елемента

```json
{
  "M1": {
    "type": "NMOSFET",
    "label": "M1",
    "bounds": {"left": 268, "top": 252, "right": 332, "bottom": 348},
    "properties": {
      "model": "IRF540",
      "threshold_voltage": "2 V"
    },
    "pins": {
      "gate": {
        "position": {"x": 268, "y": 300},
        "connected_to": "R1.pin2"
      },
      "drain": {
        "position": {"x": 300, "y": 252},
        "connected_to": "L1.pin2"
      },
      "source": {
        "position": {"x": 300, "y": 348},
        "connected_to": "GND1"
      }
    }
  }
}
```

### Елемент під кутом 45°

```json
{
  "R1": {
    "type": "Resistor",
    "bounds": {"cx": 300, "cy": 200, "width": 64, "height": 16, "rotation": 45},
    "properties": {
      "resistance": "10 kOhm"
    },
    "pins": {
      "pin1": {
        "position": {"x": 277, "y": 177},
        "connected_to": "V1.plus"
      },
      "pin2": {
        "position": {"x": 323, "y": 223},
        "connected_to": "N1"
      }
    }
  }
}
```

---

## Опис базових елементів

### Структура елемента

Кожен елемент схеми має уніфіковану структуру:

| Параметр | Обов'язковий | Опис |
|----------|--------------|------|
| `type` | ✅ Так | Тип елемента (`"Resistor"`, `"Capacitor"`, `"TransistorNPN"`, тощо) |
| `label` | ❌ Ні | Мітка для відображення на схемі |
| `description` | ❌ Ні | Текстовий опис або примітка до елемента |
| `bounds` | ❌ Ні | Обмежуючий прямокутник для редактора |
| `properties` | ❌ Ні | Об'єкт зі специфічними властивостями елемента |
| `pins` | ✅ Так | Об'єкт з описом виводів елемента |

### Структура піна (pin)

Кожен пін елемента є ключем в об'єкті `pins` і має такі параметри:

| Параметр | Обов'язковий | Опис |
|----------|--------------|------|
| `position` | ✅ Так | Абсолютні координати піна `{"x": ..., "y": ...}` |
| `connected_to` | ❌ Ні | Посилання на пін іншого елемента (`"ElementID.PinID"`) або вузол (`"NodeID"`) |
| `label` | ❌ Ні | Мітка для відображення на схемі |
| `polarity` | ❌ Ні | Полярність: `"positive"`, `"negative"` (для полярних елементів) |
| `type` | ❌ Ні | Тип піна: `"input"`, `"output"`, `"common"`, `"power"` |

### 1. Вузол з'єднання (Node)

Точка, де з'єднуються декілька провідників або виводів елементів.

```json
{
  "N1": {
    "label": "Вузол живлення",
    "position": {"x": 400, "y": 200},
    "connections": ["R1.pin2", "C1.pin1", "V1.plus"]
  }
}
```

### 2. Провідник (Wire)

Електричне з'єднання між двома точками.

```json
{
  "W1": {
    "type": "Wire",
    "pins": {
      "pin1": {
        "position": {"x": 100, "y": 200},
        "connected_to": "R1.pin1"
      },
      "pin2": {
        "position": {"x": 200, "y": 200},
        "connected_to": "N1"
      }
    }
  }
}
```

### 3. Резистор (Resistor)

```json
{
  "R1": {
    "type": "Resistor",
    "label": "R1",
    "properties": {
      "resistance": "10 kOhm"
    },
    "pins": {
      "pin1": {
        "position": {"x": 100, "y": 200},
        "connected_to": "V1.plus"
      },
      "pin2": {
        "position": {"x": 200, "y": 200},
        "connected_to": "N1"
      }
    }
  }
}
```

### 4. Конденсатор (Capacitor)

```json
{
  "C1": {
    "type": "Capacitor",
    "properties": {
      "capacitance": "1 uF"
    },
    "pins": {
      "pin1": {
        "position": {"x": 300, "y": 200},
        "connected_to": "N1"
      },
      "pin2": {
        "position": {"x": 300, "y": 300},
        "connected_to": "GND1"
      }
    }
  }
}
```

### 5. Полярний конденсатор (PolarizedCapacitor)

```json
{
  "C2": {
    "type": "PolarizedCapacitor",
    "properties": {
      "capacitance": "100 uF",
      "voltage_rating": "25 V"
    },
    "pins": {
      "anode": {
        "position": {"x": 400, "y": 200},
        "connected_to": "V1.plus"
      },
      "cathode": {
        "position": {"x": 400, "y": 300},
        "connected_to": "GND1"
      }
    }
  }
}
```

### 6. Котушка індуктивності (Inductor)

```json
{
  "L1": {
    "type": "Inductor",
    "properties": {
      "inductance": "1 mH"
    },
    "pins": {
      "pin1": {
        "position": {"x": 500, "y": 200},
        "connected_to": "N1"
      },
      "pin2": {
        "position": {"x": 500, "y": 300},
        "connected_to": "Q1.drain"
      }
    }
  }
}
```

### 7. Джерело постійної напруги (DCVoltageSource)

```json
{
  "V1": {
    "type": "DCVoltageSource",
    "properties": {
      "voltage": "12 V"
    },
    "pins": {
      "minus": {
        "position": {"x": 100, "y": 200},
        "connected_to": "GND1"
      },
      "plus": {
        "position": {"x": 100, "y": 100},
        "connected_to": "R1.pin1"
      }
    }
  }
}
```

### 8. Джерело змінної напруги (ACVoltageSource)

```json
{
  "V2": {
    "type": "ACVoltageSource",
    "properties": {
      "waveform": "sine",
      "amplitude": "5 V",
      "frequency": "1 kHz"
    },
    "pins": {
      "minus": {
        "position": {"x": 200, "y": 200},
        "connected_to": "GND1"
      },
      "plus": {
        "position": {"x": 200, "y": 100},
        "connected_to": "C1.pin1"
      }
    }
  }
}
```

### 9. Земля (Ground)

```json
{
  "GND1": {
    "type": "Ground",
    "pins": {
      "pin": {
        "position": {"x": 100, "y": 400},
        "connected_to": "V1.minus"
      }
    }
  }
}
```

### 10. Діод (Diode)

```json
{
  "D1": {
    "type": "Diode",
    "properties": {
      "model": "1N4148"
    },
    "pins": {
      "anode": {
        "position": {"x": 600, "y": 200},
        "connected_to": "R1.pin2"
      },
      "cathode": {
        "position": {"x": 700, "y": 200},
        "connected_to": "GND1"
      }
    }
  }
}
```

### 11. Світлодіод (LED)

```json
{
  "LED1": {
    "type": "LED",
    "properties": {
      "color": {"r": 1.0, "g": 0.0, "b": 0.0}
    },
    "pins": {
      "anode": {
        "position": {"x": 800, "y": 200},
        "connected_to": "R2.pin2"
      },
      "cathode": {
        "position": {"x": 900, "y": 200},
        "connected_to": "GND1"
      }
    }
  }
}
```

### 12. Біполярний транзистор NPN (TransistorNPN)

```json
{
  "Q1": {
    "type": "TransistorNPN",
    "properties": {
      "model": "2N2222",
      "beta": 100
    },
    "pins": {
      "base": {
        "position": {"x": 400, "y": 300},
        "connected_to": "R3.pin2"
      },
      "collector": {
        "position": {"x": 450, "y": 250},
        "connected_to": "R4.pin1"
      },
      "emitter": {
        "position": {"x": 450, "y": 350},
        "connected_to": "GND1"
      }
    }
  }
}
```

### 13. Біполярний транзистор PNP (TransistorPNP)

```json
{
  "Q2": {
    "type": "TransistorPNP",
    "properties": {
      "model": "2N2907"
    },
    "pins": {
      "base": {
        "position": {"x": 500, "y": 300},
        "connected_to": "R5.pin2"
      },
      "collector": {
        "position": {"x": 550, "y": 350},
        "connected_to": "LED1.anode"
      },
      "emitter": {
        "position": {"x": 550, "y": 250},
        "connected_to": "V1.plus"
      }
    }
  }
}
```

### 14. N-канальний MOSFET (NMOSFET)

```json
{
  "M1": {
    "type": "NMOSFET",
    "properties": {
      "model": "IRF540",
      "threshold_voltage": "2 V"
    },
    "pins": {
      "gate": {
        "position": {"x": 600, "y": 300},
        "connected_to": "R6.pin2"
      },
      "drain": {
        "position": {"x": 650, "y": 250},
        "connected_to": "L1.pin2"
      },
      "source": {
        "position": {"x": 650, "y": 350},
        "connected_to": "GND1"
      }
    }
  }
}
```

### 15. P-канальний MOSFET (PMOSFET)

```json
{
  "M2": {
    "type": "PMOSFET",
    "properties": {
      "model": "IRF9540"
    },
    "pins": {
      "gate": {
        "position": {"x": 700, "y": 300},
        "connected_to": "R7.pin2"
      },
      "drain": {
        "position": {"x": 750, "y": 350},
        "connected_to": "LED2.anode"
      },
      "source": {
        "position": {"x": 750, "y": 250},
        "connected_to": "V1.plus"
      }
    }
  }
}
```

### 16. Операційний підсилювач (OpAmp)

```json
{
  "U1": {
    "type": "OpAmp",
    "properties": {
      "model": "LM741"
    },
    "pins": {
      "inverting": {
        "position": {"x": 800, "y": 280},
        "connected_to": "R8.pin2"
      },
      "non_inverting": {
        "position": {"x": 800, "y": 320},
        "connected_to": "R9.pin2"
      },
      "output": {
        "position": {"x": 900, "y": 300},
        "connected_to": "R10.pin1"
      }
    }
  }
}
```

### 17. Перемикач (Switch)

```json
{
  "SW1": {
    "type": "Switch",
    "properties": {
      "state": "open",
      "momentary": false
    },
    "pins": {
      "pin1": {
        "position": {"x": 200, "y": 400},
        "connected_to": "V1.plus"
      },
      "pin2": {
        "position": {"x": 300, "y": 400},
        "connected_to": "R1.pin1"
      }
    }
  }
}
```

### 18. Потенціометр (Potentiometer)

```json
{
  "VR1": {
    "type": "Potentiometer",
    "properties": {
      "resistance": "10 kOhm",
      "wiper_position": 0.5
    },
    "pins": {
      "terminal1": {
        "position": {"x": 400, "y": 400},
        "connected_to": "V1.plus"
      },
      "wiper": {
        "position": {"x": 450, "y": 450},
        "connected_to": "U1.non_inverting"
      },
      "terminal2": {
        "position": {"x": 500, "y": 400},
        "connected_to": "GND1"
      }
    }
  }
}
```

### 19. Трансформатор (Transformer)

> Реалізація: властивості `inductance`, `ratio` (N2/N1), `coupling`, `primary_resistance`, `secondary_resistance`, `reverse_polarity`; піни `p1`, `s1`, `p2`, `s2` (див. примітку про версію 2.1 на початку документа). Приклад нижче — концепція.

```json
{
  "T1": {
    "type": "Transformer",
    "properties": {
      "primary_inductance": "10 mH",
      "turns_ratio": 10
    },
    "pins": {
      "primary_1": {
        "position": {"x": 100, "y": 500},
        "connected_to": "V1.plus"
      },
      "primary_2": {
        "position": {"x": 100, "y": 600},
        "connected_to": "GND1"
      },
      "secondary_1": {
        "position": {"x": 200, "y": 500},
        "connected_to": "D1.anode"
      },
      "secondary_2": {
        "position": {"x": 200, "y": 600},
        "connected_to": "N2"
      }
    }
  }
}
```

### 20. Текстова мітка (TextLabel)

```json
{
  "TXT1": {
    "type": "TextLabel",
    "position": {"x": 400, "y": 100},
    "properties": {
      "text": "Генератор на MOSFET",
      "font_size": 14
    }
  }
}
```

---

## Приклад повної схеми

Простий мультивібратор на двох транзисторах:

```json
{
  "schema": {
    "version": "2.2",
    "name": "Мультивібратор",
    "description": "Класичний мультивібратор на двох NPN транзисторах",
    "created": "2025-11-26",
    "author": "User"
  },

  "simulation": {
    "max_time_step": "1 us",
    "min_time_step": "1 ns",
    "iteration_count": 15,
    "voltage_range": "10 V",
    "options": {
      "show_current_dots": true,
      "show_voltage": true
    }
  },

  "nodes": {
    "N_VCC": {
      "label": "VCC",
      "position": {"x": 300, "y": 100},
      "connections": ["V1.plus", "R1.pin1", "R2.pin1"]
    },
    "N_GND": {
      "label": "GND",
      "position": {"x": 300, "y": 500},
      "connections": ["V1.minus", "Q1.emitter", "Q2.emitter"]
    }
  },

  "elements": {
    "V1": {
      "type": "DCVoltageSource",
      "label": "V1",
      "properties": {
        "voltage": "9 V"
      },
      "pins": {
        "minus": {
          "label": "-",
          "position": {"x": 100, "y": 500},
          "connected_to": "N_GND"
        },
        "plus": {
          "label": "+",
          "position": {"x": 100, "y": 100},
          "connected_to": "N_VCC"
        }
      }
    },

    "R1": {
      "type": "Resistor",
      "label": "R1",
      "properties": {
        "resistance": "1 kOhm"
      },
      "pins": {
        "pin1": {
          "label": "1",
          "position": {"x": 200, "y": 100},
          "connected_to": "N_VCC"
        },
        "pin2": {
          "label": "2",
          "position": {"x": 200, "y": 200},
          "connected_to": "Q1.collector"
        }
      }
    },

    "R2": {
      "type": "Resistor",
      "label": "R2",
      "properties": {
        "resistance": "1 kOhm"
      },
      "pins": {
        "pin1": {
          "label": "1",
          "position": {"x": 400, "y": 100},
          "connected_to": "N_VCC"
        },
        "pin2": {
          "label": "2",
          "position": {"x": 400, "y": 200},
          "connected_to": "Q2.collector"
        }
      }
    },

    "R3": {
      "type": "Resistor",
      "label": "R3",
      "properties": {
        "resistance": "47 kOhm"
      },
      "pins": {
        "pin1": {
          "label": "1",
          "position": {"x": 200, "y": 300},
          "connected_to": "C1.pin2"
        },
        "pin2": {
          "label": "2",
          "position": {"x": 280, "y": 350},
          "connected_to": "Q2.base"
        }
      }
    },

    "R4": {
      "type": "Resistor",
      "label": "R4",
      "properties": {
        "resistance": "47 kOhm"
      },
      "pins": {
        "pin1": {
          "label": "1",
          "position": {"x": 400, "y": 300},
          "connected_to": "C2.pin2"
        },
        "pin2": {
          "label": "2",
          "position": {"x": 320, "y": 350},
          "connected_to": "Q1.base"
        }
      }
    },

    "C1": {
      "type": "Capacitor",
      "label": "C1",
      "properties": {
        "capacitance": "10 uF"
      },
      "pins": {
        "pin1": {
          "label": "1",
          "position": {"x": 200, "y": 200},
          "connected_to": "Q1.collector"
        },
        "pin2": {
          "label": "2",
          "position": {"x": 200, "y": 300},
          "connected_to": "R3.pin1"
        }
      }
    },

    "C2": {
      "type": "Capacitor",
      "label": "C2",
      "properties": {
        "capacitance": "10 uF"
      },
      "pins": {
        "pin1": {
          "label": "1",
          "position": {"x": 400, "y": 200},
          "connected_to": "Q2.collector"
        },
        "pin2": {
          "label": "2",
          "position": {"x": 400, "y": 300},
          "connected_to": "R4.pin1"
        }
      }
    },

    "Q1": {
      "type": "TransistorNPN",
      "label": "Q1",
      "properties": {
        "model": "BC547",
        "beta": 200
      },
      "pins": {
        "base": {
          "label": "B",
          "position": {"x": 280, "y": 350},
          "connected_to": "R4.pin2"
        },
        "collector": {
          "label": "C",
          "position": {"x": 300, "y": 200},
          "connected_to": "R1.pin2"
        },
        "emitter": {
          "label": "E",
          "position": {"x": 300, "y": 500},
          "connected_to": "N_GND"
        }
      }
    },

    "Q2": {
      "type": "TransistorNPN",
      "label": "Q2",
      "properties": {
        "model": "BC547",
        "beta": 200
      },
      "pins": {
        "base": {
          "label": "B",
          "position": {"x": 320, "y": 350},
          "connected_to": "R3.pin2"
        },
        "collector": {
          "label": "C",
          "position": {"x": 300, "y": 200},
          "connected_to": "R2.pin2"
        },
        "emitter": {
          "label": "E",
          "position": {"x": 300, "y": 500},
          "connected_to": "N_GND"
        }
      }
    },

    "LED1": {
      "type": "LED",
      "label": "LED1",
      "properties": {
        "color": {"r": 1.0, "g": 0.0, "b": 0.0}
      },
      "pins": {
        "anode": {
          "position": {"x": 200, "y": 150},
          "connected_to": "Q1.collector"
        },
        "cathode": {
          "position": {"x": 150, "y": 150},
          "connected_to": "N_VCC"
        }
      }
    },

    "LED2": {
      "type": "LED",
      "label": "LED2",
      "properties": {
        "color": {"r": 0.0, "g": 1.0, "b": 0.0}
      },
      "pins": {
        "anode": {
          "position": {"x": 400, "y": 150},
          "connected_to": "Q2.collector"
        },
        "cathode": {
          "position": {"x": 450, "y": 150},
          "connected_to": "N_VCC"
        }
      }
    }
  },

  "scopes": [
    {
      "element": "Q1",
      "position": 0,
      "speed": 64,
      "display": {
        "show_voltage": true,
        "show_current": false,
        "show_scale": true,
        "show_max": false,
        "show_min": false,
        "show_frequency": false,
        "show_fft": false,
        "show_rms": false,
        "show_average": false,
        "show_duty_cycle": false,
        "show_negative": false,
        "show_element_info": true
      },
      "plot_mode": {
        "plot_2d": false,
        "plot_xy": false,
        "max_scale": false,
        "log_spectrum": false
      },
      "plots": [
        {
          "element": "Q1",
          "units": "V",
          "color": "#00FF00",
          "scale": 1.0,
          "v_position": 0
        }
      ]
    }
  ],

  "adjustables": [
    {
      "element": "R1",
      "edit_item": 0,
      "label": "Опір R1",
      "min_value": 100,
      "max_value": 100000,
      "current_value": 10000
    }
  ]
}
```

---

## Подальший розвиток

1. **JSON Schema** — створення схеми валідації для автоматичної перевірки
2. **Конвертер** — розробка конвертера зі старого формату в новий
3. **Імпорт/Експорт** — підтримка обох форматів у додатку
4. **Розширені елементи** — додавання описів для всіх типів елементів
5. **Бібліотеки** — підтримка посилань на зовнішні бібліотеки моделей

---

## Примітки

### Формат фізичних величин

Значення фізичних параметрів в об'єкті `properties` вказуються разом з одиницею виміру у форматі `"значення одиниця"`:

```json
{
  "properties": {
    "resistance": "10 kOhm",
    "capacitance": "100 nF",
    "voltage": "3.3 V",
    "frequency": "1.5 MHz"
  }
}
```

#### Підтримувані одиниці виміру

| Величина | Одиниці | Приклади |
|----------|---------|----------|
| Опір | Ohm, kOhm, MOhm | `"470 Ohm"`, `"10 kOhm"`, `"1 MOhm"` |
| Ємність | pF, nF, uF, mF, F | `"100 pF"`, `"10 nF"`, `"47 uF"` |
| Індуктивність | nH, uH, mH, H | `"100 nH"`, `"10 uH"`, `"1 mH"` |
| Напруга | mV, V, kV | `"500 mV"`, `"12 V"`, `"1 kV"` |
| Струм | nA, uA, mA, A | `"10 nA"`, `"100 uA"`, `"2 A"` |
| Частота | Hz, kHz, MHz, GHz | `"50 Hz"`, `"1 kHz"`, `"433 MHz"` |
| Час | ns, us, ms, s | `"100 ns"`, `"10 us"`, `"1 s"` |
| Потужність | mW, W, kW | `"250 mW"`, `"5 W"`, `"1 kW"` |

#### Альтернативний запис

Можна також використовувати числове значення в базових одиницях СІ:

```json
{
  "properties": {
    "resistance": 10000,
    "capacitance": 1.0e-6
  }
}
```

### Приклади властивостей за типами елементів

| Тип елемента | Властивості в `properties` |
|--------------|---------------------------|
| Resistor | `resistance` |
| Capacitor | `capacitance` |
| PolarizedCapacitor | `capacitance`, `voltage_rating` |
| Inductor | `inductance` |
| DCVoltageSource | `voltage` |
| ACVoltageSource | `waveform`, `amplitude`, `frequency` |
| Diode | `model` |
| LED | `color` |
| TransistorNPN/PNP | `model`, `beta` |
| NMOSFET/PMOSFET | `model`, `threshold_voltage` |
| OpAmp | `model` |
| Switch | `state`, `momentary` |
| Potentiometer | `resistance`, `wiper_position` |
| Transformer | `primary_inductance`, `turns_ratio` |
| TappedTransformer | `primary_inductance`, `ratio`, `coupling_coefficient`, `spacing`, `tap_position` (пікселі редактора: первинна й уся вторинна мають довжину 2 × `spacing`, відвід — `tap_position` від `sec1`; без ключів форма береться з позицій пінів) |
| TextLabel | `text`, `font_size` |

### Інші примітки

- Координати вказуються в піксельних одиницях сітки
- ID елементів є ключами об'єктів, що гарантує унікальність
- Формат `connected_to` використовує нотацію `"ElementID.PinID"`
- Усі рядкові значення обов'язково беруться в подвійні лапки
- Специфічні властивості елемента виносяться в об'єкт `properties`

---

## Осцилографи (Scopes)

Секція `scopes` містить масив конфігурацій осцилографів.

### Структура scope

| Параметр | Тип | Опис |
|----------|-----|------|
| `element` | string | ID головного елемента |
| `position` | number | Позиція в стеку осцилографів |
| `speed` | number | Швидкість розгортки (кроки симуляції на піксель) |
| `display` | object | Налаштування відображення |
| `plot_mode` | object | Режим побудови графіку |
| `manual_scale` | object | Ручний масштаб (опціонально) |
| `plots` | array | Масив окремих графіків |

### Параметри display

| Параметр | Тип | Опис |
|----------|-----|------|
| `show_voltage` | boolean | Показувати напругу |
| `show_current` | boolean | Показувати струм |
| `show_scale` | boolean | Показувати шкалу |
| `show_max` | boolean | Показувати максимум |
| `show_min` | boolean | Показувати мінімум |
| `show_frequency` | boolean | Показувати частоту |
| `show_fft` | boolean | Показувати FFT |
| `show_rms` | boolean | Показувати RMS |
| `show_average` | boolean | Показувати середнє |
| `show_duty_cycle` | boolean | Показувати duty cycle |
| `show_negative` | boolean | Показувати від'ємні значення |
| `show_element_info` | boolean | Показувати інформацію про елемент |

### Параметри plot_mode

| Параметр | Тип | Опис |
|----------|-----|------|
| `plot_2d` | boolean | 2D графік |
| `plot_xy` | boolean | X-Y графік |
| `max_scale` | boolean | Максимальний масштаб |
| `log_spectrum` | boolean | Логарифмічний спектр |

### Структура plot

| Параметр | Тип | Опис |
|----------|-----|------|
| `element` | string | ID елемента для цього графіка |
| `units` | string | Одиниці: `"V"`, `"A"`, `"W"`, `"Ω"` |
| `color` | string | Колір графіка (hex) |
| `scale` | number | Масштаб (одиниць на поділку) |
| `v_position` | number | Вертикальна позиція (-100 до +100) |
| `ac_coupled` | boolean | AC-зв'язок (опціонально) |

### Приклад scope

```json
{
  "scopes": [
    {
      "element": "Q1",
      "position": 0,
      "speed": 64,
      "display": {
        "show_voltage": true,
        "show_current": true,
        "show_scale": true,
        "show_frequency": true
      },
      "plot_mode": {
        "plot_2d": false,
        "plot_xy": false
      },
      "manual_scale": {
        "enabled": true,
        "divisions": 8
      },
      "plots": [
        {
          "element": "Q1",
          "units": "V",
          "color": "#00FF00",
          "scale": 2.0,
          "v_position": 0
        },
        {
          "element": "Q1",
          "units": "A",
          "color": "#FFFF00",
          "scale": 0.01,
          "v_position": 0,
          "ac_coupled": true
        }
      ]
    }
  ]
}
```

### Осцилограф у схемі (елемент `Scope`)

Осцилограф, вбудований у схему (елемент `Scope`, текстовий рядок `403`), з версії 2.2 зберігає свої налаштування у властивості `properties.scope`. Її форма — запис секції `scopes` (ті самі ключі `element`, `position`, `label`, `speed`, `display`, `plot_mode`, `trigger`, `history`, `scales`, `manual_scale`, `plots`); `element` і `plots[].element` — ID елементів схеми, `position` — поле позиції в стеку з рядка `403` (зберігається без змін). Властивість пишеться, лише коли осцилограф має елемент (як і рядок `403`). Відсутні `trigger`, `history` і `manual_scale` означають значення за замовчуванням (ручний масштаб вимкнено), а не збережені користувачем налаштування осцилографа за замовчуванням; графік (`plots[]`), чий `element` не знайдено у файлі, пропускається. Імпорт застосовує її після створення всіх елементів, тож посилання на будь-який елемент файлу розв'язуються; осцилограф, чий `element` не знайдено, залишається без елемента й не записується в текстовий формат. Тому коловий шлях текст → JSON → текст зберігає кожен рядок `403` байт у байт. До версії 2.2 властивість не писалася, і рядки `403` губилися.

```json
"S1": {
  "type": "Scope",
  "properties": {
    "scope": {
      "element": "W6",
      "position": 0,
      "speed": 256,
      "display": {"show_voltage": true, "show_current": false, "show_scale": false, "show_max": false, "show_min": false,
                  "show_frequency": false, "show_fft": false, "show_rms": false, "show_average": false,
                  "show_duty_cycle": false, "show_negative": false, "show_element_info": false},
      "plot_mode": {"plot_2d": false, "plot_xy": false, "max_scale": false, "log_spectrum": false},
      "scales": {"voltage": 5, "current": 0.1, "ohms": 5, "watts": 5},
      "plots": [
        {"element": "W6", "units": "V", "value": 0, "color": "#00ff00", "scale": 1.25, "v_position": 0}
      ]
    }
  },
  "pins": {"_startpoint": {"position": {"x": 192, "y": 208}}, "_endpoint": {"position": {"x": 224, "y": 240}}},
  "_flags": 0
}
```

Agent API ([agent-api.sp.md §02_05](./agent-api.sp.md#SP_AGA_02_05)) показує лише прості властивості (число, рядок, логічне значення), тому запис елемента `Scope` у `getCircuit` і AgentCircuit цієї властивості не містить.

---

## Регулятори (Adjustables)

Секція `adjustables` містить масив слайдерів для динамічного керування параметрами елементів.

### Структура adjustable

| Параметр | Тип | Опис |
|----------|-----|------|
| `element` | string | ID елемента, який контролюється |
| `edit_item` | number | Індекс властивості в списку редагування |
| `label` | string | Текстова мітка слайдера |
| `min_value` | number | Мінімальне значення |
| `max_value` | number | Максимальне значення |
| `current_value` | number | Поточне значення |
| `shared_slider` | number | Індекс спільного слайдера (опціонально) |

### Приклад adjustables

```json
{
  "adjustables": [
    {
      "element": "R1",
      "edit_item": 0,
      "label": "Опір R1",
      "min_value": 100,
      "max_value": 100000,
      "current_value": 10000
    },
    {
      "element": "C1",
      "edit_item": 0,
      "label": "Ємність C1",
      "min_value": 1e-9,
      "max_value": 1e-3,
      "current_value": 1e-6
    },
    {
      "element": "V1",
      "edit_item": 0,
      "label": "Напруга живлення",
      "min_value": 1,
      "max_value": 24,
      "current_value": 12,
      "shared_slider": 0
    }
  ]
}
```

### Спільні слайдери

Параметр `shared_slider` дозволяє декільком регуляторам використовувати один слайдер.
Значення — це індекс слайдера в масиві `adjustables`, з яким буде синхронізоване значення.

---

## Моделі (models)

З версії 2.2 ([SP_AGA_03_12](./agent-api.sp.md#SP_AGA_03_12)) файл несе визначення моделей, які використовує схема: масив `models` на верхньому рівні. Модель — іменований набір параметрів, спільний для всіх документів сесії: діодна (`kind: "diode"`; її називають `Diode`, `LED`, `Varactor`, `ZenerDiode` у властивості `model`), транзисторна (`transistor`; `TransistorNPN`/`TransistorPNP`, `model`), custom logic (`logic`; `CustomLogic`, `model_name`) і підсхема (`subcircuit`; `Subcircuit`, `model_name`).

- **Що пишеться.** Кожна небудована модель, яку називає елемент схеми, і — через моделі підсхем — моделі, які називають елементи всередині підсхем (транзитивне замикання), кожна один раз. Залежності йдуть першими; в іншому — за першим використанням у порядку ID елементів (як `getCircuit` Agent API). Будовані моделі (`default`, `1N4148`, внутрішні частини вбудованих мікросхем) не пишуться. Масив пишеться лише непорожнім; обмеження кількості немає. Експорт виділення (`exportSelection`) пише моделі виділених елементів.
- **Форма запису.** ModelSpec ([agent-api.sp.md §01_13](./agent-api.sp.md#SP_AGA_01_13)), коли її розбір відтворює рядок моделі точно: діод і транзистор — `{kind, name, parameters}` (значення без втрат: величини — рядки з одиницями, числа — числа JSON, нескінченність — `"inf"`); логіка — `{kind, name, inputs, outputs, rules, info}`. Інакше — ModelText `{kind, name, modelText}`: рівно один рядок моделі текстового формату (`34`, `32`, `!`, `.`); підсхеми пишуться завжди так. `from` і `source` (поля введення Agent API) у файлі недопустимі: файл несе повні визначення.
- **Завантаження користувачем** (відкриття файлу, вставка, діалог імпорту) і `openFile` Agent API: моделі визначаються до елементів так само, як рядки моделей текстового імпорту — запис замінює модель сесії з тим самим ім'ям. Недійсний запис (невідомий `kind`, `from`, `source`, параметр поза діапазоном, рядок, що не розбирається) пропускається з повідомленням у консолі/журналі, без alert; елементи, що його називають, отримують запасну модель невідомої моделі (`openFile` повідомляє `value_adjusted`). Логічна модель, правила якої не розбираються, завантажується з правилами до хибного рядка, як текстовий рядок `!`: завантаження користувачем показує повідомлення парсера (alert), як текстове; `openFile` повідомляє `value_adjusted`. `openFile` повідомляє `value_adjusted` для кожного пропущеного запису (`models[<i>]`) і кожного елемента, що його називає. Відхилене `openFile` відновлює кожну модель, яку файл створив або замінив.
- **Вставка** (`Ctrl+V` тексту JSON) визначає моделі так само; одне `undo` прибирає вставлені елементи, модель лишається. **«Імпортувати лише підсхеми»** бере записи `subcircuit` і записи, від яких вони залежать, і жодного елемента. Кількість записів у файлі не обмежена (обмеження 200 діє лише для `models` AgentCircuit).
- **`importCircuit` Agent API** з текстом JSON перевіряє секцію до будь-якої зміни: ім'я, що вже існує, приймається лише з ідентичним визначенням (той самий рядок моделі), інакше `name_taken`; недійсний запис — `invalid_value`, і імпорт відхиляється; нова підсхема проходить перевірку внутрішніх посилань і пінів.

```json
{
  "schema": {"format": "circuitjs", "version": "2.2"},
  "elements": {
    "LED1": {"type": "LED", "properties": {"model": "led-green-2v1"}, "pins": {...}, "_flags": 2},
    "X1": {"type": "Subcircuit", "properties": {"model_name": "diode-block"}, "pins": {...}, "_flags": 0}
  },
  "models": [
    {"kind": "diode", "name": "led-green-2v1",
     "parameters": {"saturation_current": "1.7143528192808883e-7 A", "series_resistance": "0 Ohm",
                    "emission_coefficient": 6.958985277823168, "breakdown_voltage": "0 V",
                    "forward_voltage": "2.1 V", "forward_current": "20 mA"}},
    {"kind": "logic", "name": "and2", "inputs": ["A", "B"], "outputs": ["Y"], "rules": ["11=1", "??=0"], "info": "and2"},
    {"kind": "diode", "name": "sub-diode", "parameters": {...}},
    {"kind": "subcircuit", "name": "diode-block", "modelText": ". diode-block 1 2 2 2 a 1 0 2 k 2 0 3 DiodeElm\\s1\\s2 ..."}
  ]
}
```

---

## Збереження стану симуляції

При експорті схеми можна зберегти **поточний стан симуляції** — внутрішні змінні елементів,
які визначають їхню поведінку в даний момент часу. Це дозволяє:

- Продовжити симуляцію з точки зупинки
- Зберегти стан схеми під час тестування
- Відтворити конкретний момент роботи схеми

### Опція збереження стану

При експорті у JSON потрібно увімкнути опцію `include_state`:

```json
{
  "schema": {
    "version": "2.2",
    "include_state": true,
    ...
  }
}
```

### Структура стану елемента

Стан зберігається в об'єкті `state` кожного елемента:

```json
{
  "C1": {
    "type": "Capacitor",
    "properties": {
      "capacitance": "100 uF"
    },
    "pins": {...},
    "state": {
      "pin_voltages": [5.0, 0.0],
      "pin_currents": [0.001, -0.001],
      "voltdiff": 5.0
    }
  }
}
```

### Базовий стан (для всіх елементів)

Кожен елемент успадковує базовий стан від `CircuitElm`:

| Параметр | Тип | Опис |
|----------|-----|------|
| `pin_voltages` | number[] | Напруги на кожному піні елемента |
| `pin_currents` | number[] | Струми через кожен пін елемента |

### Специфічний стан елементів

Різні типи елементів мають додаткові змінні стану:

#### Пасивні компоненти

| Елемент | Змінні стану | Опис |
|---------|--------------|------|
| `Capacitor` | `voltdiff` | Різниця напруг на конденсаторі |
| `Inductor` | `current` | Струм через індуктивність |
| `Memristor` | `dopeWidth`, `resistance` | Ширина допованої зони, поточний опір |

#### Напівпровідники

| Елемент | Змінні стану | Опис |
|---------|--------------|------|
| `Diode` | `current` | Струм через діод |
| `TransistorNPN/PNP` | `ib`, `ic`, `ie` | Струми бази, колектора, емітера |
| `NMOSFET/PMOSFET` | `ids` | Струм стоку |
| `NJFET/PJFET` | `gateCurrentGS`, `gateCurrentGD` | Струми затвору |
| `SCR` | `lastvac`, `lastvag`, `aresistance`, `ia`, `ig` | Стан тиристора |
| `Triac` | `state`, `i1`, `i2`, `aresistance` | Стан симістора |
| `Triode` | `currentp`, `currentg`, `currentc` | Струми аноду, сітки, катоду |
| `VaractorDiode` | `capvoltdiff`, `capacitance`, `capCurrent` | Стан варикапа |
| `TunnelDiode` | `lastvoltdiff` | Остання різниця напруг |

#### Перемикачі та реле

| Елемент | Змінні стану | Опис |
|---------|--------------|------|
| `Switch` | `position` | Позиція перемикача |
| `Relay` | `d_position`, `i_position`, `onState`, `coilCurrent` | Стан реле |
| `RelayCoil` | `coilCurrent`, `d_position`, `i_position`, `avgCurrent` | Стан котушки реле |
| `RelayContact` | `switchCurrent`, `i_position` | Стан контакту реле |
| `Fuse` | `heat`, `blown` | Нагрів, стан перегорання |
| `SparkGap` | `state`, `resistance` | Стан розрядника |
| `MotorProtectionSwitch` | `heats`, `blown` | Нагріви фаз, стан спрацювання |

#### Трансформатори

| Елемент | Змінні стану | Опис |
|---------|--------------|------|
| `Transformer` | `current[0]`, `current[1]` | Струми первинної та вторинної обмоток |
| `TappedTransformer` | `current[0..3]` | Струми всіх обмоток |
| `CustomTransformer` | `coilCurrents[]` | Масив струмів всіх обмоток |

#### Двигуни

| Елемент | Змінні стану | Опис |
|---------|--------------|------|
| `DCMotor` | `angle`, `speed`, `coilCurrent`, `inertiaCurrent` | Кут, швидкість, струми |
| `ThreePhaseMotor` | `angle`, `speed`, `filteredSpeed`, `coilCurrents[]` | Кут, швидкості, струми фаз |

#### Логічні та таймерні елементи

| Елемент | Змінні стану | Опис |
|---------|--------------|------|
| `Chip` (базовий) | `values[]`, `lastClock` | Вихідні значення, стан clock |
| `Timer555` | `out`, `triggerSuppressed` | Стан виходу, пригнічення тригера |
| `Monostable` | `prevInputValue`, `triggered`, `lastRisingEdge` | Стан моностабільного мультивібратора |
| `SeqGen` | `bitPosition`, `clockstate` | Позиція біта, стан clock |
| `Sweep` | `frequency`, `freqTime`, `dir`, `v` | Параметри розгортки |
| `TimeDelayRelay` | `lastTransition`, `poweredState`, `onState`, `resistance` | Стан реле затримки |

#### Інші елементи

| Елемент | Змінні стану | Опис |
|---------|--------------|------|
| `OpAmp` | `lastvd` | Остання різниця напруг на входах |
| `Lamp` | `temp`, `resistance` | Температура нитки, опір |
| `TransmissionLine` | `ptr`, `current1`, `current2` | Вказівник буфера, струми |
| `CompositeElement` | `subElements[]` | Стан всіх підлеглих елементів |

### Приклад експорту зі станом

```json
{
  "schema": {
    "version": "2.2",
    "name": "RC фільтр",
    "include_state": true
  },
  
  "elements": {
    "R1": {
      "type": "Resistor",
      "properties": {"resistance": "1 kOhm"},
      "pins": {...},
      "state": {
        "pin_voltages": [5.0, 2.5],
        "pin_currents": [0.0025, -0.0025]
      }
    },
    "C1": {
      "type": "Capacitor",
      "properties": {"capacitance": "100 uF"},
      "pins": {...},
      "state": {
        "pin_voltages": [2.5, 0.0],
        "pin_currents": [0.0025, -0.0025],
        "voltdiff": 2.5
      }
    },
    "Q1": {
      "type": "TransistorNPN",
      "properties": {"hfe": 100},
      "pins": {...},
      "state": {
        "pin_voltages": [0.7, 5.0, 0.0],
        "pin_currents": [0.0001, 0.01, -0.0101],
        "ib": 0.0001,
        "ic": 0.01,
        "ie": 0.0101
      }
    },
    "M1": {
      "type": "DCMotor",
      "properties": {"coilInductance": "0.01 H"},
      "pins": {...},
      "state": {
        "pin_voltages": [12.0, 0.0],
        "pin_currents": [0.5, -0.5],
        "angle": 45.7,
        "speed": 1500.0,
        "coilCurrent": 0.5,
        "inertiaCurrent": 0.1
      }
    }
  }
}
```

### Застосування стану при імпорті

При імпорті схеми зі станом, симулятор:

1. Створює всі елементи схеми
2. Відновлює напруги та струми пінів
3. Відновлює специфічні змінні стану кожного елемента
4. Продовжує симуляцію з відновленого стану

### Програмний API

#### Експорт стану

```java
// Експорт схеми зі станом
JsonCircuitExporter exporter = new JsonCircuitExporter(sim.getCircuit());
String json = exporter.exportToString(true); // true = includeState
```

#### Імпорт стану

```java
// Імпорт та відновлення стану
JsonCircuitImporter importer = new JsonCircuitImporter(json);
importer.importCircuit(sim.getCircuit());
// Стан автоматично відновлюється, якщо присутній у JSON
```

#### Методи елементів

Кожен елемент реалізує методи для роботи зі станом:

```java
// В класі елемента
@Override
public JsObject getJsonState() {
    JsObject obj = super.getJsonState(); // Базові напруги/струми
    obj.set("voltdiff", voltdiff);       // Специфічний стан
    return obj;
}

@Override
public void applyJsonState(JsObject state) {
    super.applyJsonState(state);         // Відновити напруги/струми
    voltdiff = state.getDouble("voltdiff", 0.0);
}
```
