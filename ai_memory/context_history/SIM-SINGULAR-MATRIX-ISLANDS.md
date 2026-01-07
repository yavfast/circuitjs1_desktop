## Meta
- archived_at: 2026-01-07T14:30:30+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk

## Task
- task_id: SIM-SINGULAR-MATRIX-ISLANDS
- goal: Виправити збій запуску симуляції ("Сингулярна матриця!") на схемі з двома ізольованими електричними контурами, без rollback.

## Summary
- Коренева причина: origMatrix/origRightSide не заповнювались, якщо simplifyMatrix() НЕ робив спрощення; у нелінійних схемах кожна Newton-підітерація відновлювала нульову матрицю → LU fail та "Singular matrix".
- Forward-fix: гарантувати snapshot origMatrix/origRightSide завжди.
- Додатково: robust ground mapping (усі GroundElm в node=0), діагностика LU-провалу, стабілізація діагоналі для voltage-source current змінних.

## Status
- last_result: success (DevMode: схема крокує, time зростає, stopMessage/errorMessage порожні)

## Key Files
- src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java
- src/main/java/com/lushprojects/circuitjs1/client/CircuitMath.java
- src/main/java/com/lushprojects/circuitjs1/client/element/GroundElm.java

## Restore Recipe
- В DevMode викликати CircuitJS1.resetSimulation(); кілька разів CircuitJS1.stepSimulation(); перевірити CircuitJS1.getSimInfo() що time>0 і errorMessage порожній.
