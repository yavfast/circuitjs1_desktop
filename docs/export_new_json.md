# Новий формат файлу схем CircuitJS1 (JSON)

Цей документ описує концепцію нового, зрозумілого формату експорту схем у форматі JSON, який не потребує додаткової документації для розуміння.

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
    "version": "2.0",
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
        "connected_to": "V1.positive"
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
    "connections": ["R1.pin2", "C1.pin1", "V1.positive"]
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
        "connected_to": "V1.positive"
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
        "connected_to": "V1.positive"
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
      "positive": {
        "position": {"x": 100, "y": 100},
        "connected_to": "R1.pin1"
      },
      "negative": {
        "position": {"x": 100, "y": 200},
        "connected_to": "GND1"
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
      "positive": {
        "position": {"x": 200, "y": 100},
        "connected_to": "C1.pin1"
      },
      "negative": {
        "position": {"x": 200, "y": 200},
        "connected_to": "GND1"
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
        "connected_to": "V1.negative"
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
        "connected_to": "V1.positive"
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
        "connected_to": "V1.positive"
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
        "connected_to": "V1.positive"
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
        "connected_to": "V1.positive"
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
        "connected_to": "V1.positive"
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
    "version": "2.0",
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
      "connections": ["V1.positive", "R1.pin1", "R2.pin1"]
    },
    "N_GND": {
      "label": "GND",
      "position": {"x": 300, "y": 500},
      "connections": ["V1.negative", "Q1.emitter", "Q2.emitter"]
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
        "positive": {
          "label": "+",
          "position": {"x": 100, "y": 100},
          "connected_to": "N_VCC"
        },
        "negative": {
          "label": "-",
          "position": {"x": 100, "y": 500},
          "connected_to": "N_GND"
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
