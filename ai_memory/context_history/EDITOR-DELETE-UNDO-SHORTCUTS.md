# Context Archive — EDITOR-DELETE-UNDO-SHORTCUTS

## Meta
- archived_at: 2026-01-08T00:00:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk

## Task
- task_id: EDITOR-DELETE-UNDO-SHORTCUTS
- goal: Виправити 3 баги редактора: (1) delete інколи прибирає “зайвий” елемент, (2) undo після delete може очищати всю схему, (3) після відкриття схеми одразу не працюють швидкі команди додавання елементів.
- scope_in: Мінімальні зміни в delete/undo/focus; без редизайну UI.
- scope_out: Переробка всієї системи undo або повний рефактор input handling.

## Progress
- done:
  - `CircuitEditor.doDelete()`: при наявності selection видаляє лише selected; якщо selection нема — видаляє максимум один елемент (menuElm або mouseElm), більше не видаляє hovered паралельно.
  - `UndoManager`: додано `resetAndSeedFromCurrentCircuit()` для скидання undo/redo та seed поточним станом.
  - `LoadFile.doLoad()`: після open/load робить reset+seed undo і ставить фокус на canvas.
  - `DocumentManager`: після restore closed tab робить reset+seed undo; після tab switch ставить фокус на canvas (Timer).
  - `mvn -q -DskipTests=true test` — OK.
- next:
  - Ручний smoke: відкрити схему → без кліку натиснути shortcut (напр. `r`) → має перейти в Add mode.
  - Delete selected не має чіпати hovered.
  - Ctrl+Z після delete відновлює елемент (без очищення всієї схеми).

## Key files
- src/main/java/com/lushprojects/circuitjs1/client/CircuitEditor.java
- src/main/java/com/lushprojects/circuitjs1/client/UndoManager.java
- src/main/java/com/lushprojects/circuitjs1/client/LoadFile.java
- src/main/java/com/lushprojects/circuitjs1/client/DocumentManager.java
