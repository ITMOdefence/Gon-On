# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Проект

Gon&On — учебный язык (синтаксис в духе Kotlin) и его компилятор (Kotlin + ANTLR4) в байткод стековой машины `.albc`. Здесь только язык и компилятор. ВМ (GVM) пишет другая команда на C, и она опирается **только** на спецификацию: `docs/BYTECODE_SPEC.md` и общий контракт `docs/backlog/bytecode-contract.md`, а не на код компилятора. Язык v1 — процедурный, v2 добавляет классы. Программа без классов компилируется в профиль `.albc` 1.0, с классами — в 2.0.

Внутренние имена остались от прежнего названия AppLang: пакет `ru.itmo.applang`, грамматика `AppLang.g4`, CLI `applangc`, расширение `.al`. Это не ошибка.

## Команды

Нужен JDK 17 (toolchain); Gradle wrapper лежит в репозитории.

```bash
./gradlew build                                                    # компиляция + все тесты
./gradlew test --tests 'ru.itmo.applang.semantic.TypeCheckerTest'  # один класс
./gradlew test --tests '*ClassTypeCheckerTest*'                    # по шаблону
./gradlew test --tests 'ru.itmo.applang.compat.*'                  # тесты совместимости с GVM
./gradlew conformanceCorpus                                        # набор .albc + manifest.json -> build/conformance
./gradlew run --args="examples/objects.al -o /tmp/o.albc --disasm" # скомпилировать программу
```

Парсер генерируется из `src/main/antlr/ru/itmo/applang/parser/AppLang.g4` (`generateGrammarSource`, флаги `-visitor -no-listener`). Пакет `ru.itmo.applang.parser` руками не редактируется. Приоритет операторов задаётся **порядком альтернатив** `expr`: выше — сильнее.

## Архитектура

Весь пайплайн собран в `compiler/Compiler.kt`; им пользуются и CLI, и тесты:

Lexer/Parser → `AstBuilder` → `TypeChecker` (мутирует AST: `resolvedType`, слоты, разрешённые члены) → `BytecodeEmitter` → `BytecodeWriter.writeToBytes` (`BytecodeVerifier` → serialize → строгий `BytecodeReader`, результат обязан совпасть с моделью) → атомарная запись файла.

- Любая ошибка даёт `CompilationResult.Failure`. Выходной файл при этом не создаётся и не перезаписывается. Диагностики уровня программы (`MISSING_MAIN`, `LIMIT_EXCEEDED` пула, `INTERNAL_COMPILER_ERROR`) идут с `position = null`.
- `BytecodeVerifier` повторяет верификатор GVM: цели переходов только на начало инструкции (не на `code_length`), режимы возврата VALUE/VOID/NONE, высоты стека по CFG. Отказ — `BytecodeFormatException` с категорией `BytecodeIssue`; имена категорий совпадают с GVM.
- Классы. Таблица функций идёт в порядке исходника: функция, либо `Класс.init` и затем `Класс.метод`. `this` лежит в слоте 0. Получатель метода и создаваемый объект хранятся в скрытых слотах `resolvedTempBase + resolvedTempDepth`. Заполненность полей в конструкторе проверяет `InitState` в `TypeChecker`.
- Пул констант 2.0 начинается с имён классов/полей и функций. Порядок 1.0 сохранён — от него зависят golden-файлы.

Подробности: `docs/ARCHITECTURE.md`, семантика языка — `docs/LANGUAGE_SPEC.md` (раздел 7 — классы), требования GVM — `docs/backlog/`.

## Инварианты при изменениях

- **Байткод — контракт с другой командой.** Любое видимое в байтах изменение (опкоды, раскладка, соглашение о вызове, codegen) в том же изменении обновляет `docs/BYTECODE_SPEC.md` и golden-файлы `examples/*.albc.asm` и `*.albc.hex`. `examples/counter.al` обязан компилироваться ровно в 185 байт эталона `bytecode-contract.md` §11. Сам `docs/backlog/bytecode-contract.md` — копия документации GVM; не правь его в одиночку.
- Длина инструкции задаётся только `Opcode.operandBytes`. Новый опкод добавляется в `Opcode`, `Instruction`, writer, reader, `Disassembler` и таблицу эффектов стека `BytecodeVerifier`. Коды `0x43+` не назначены.
- Пределы формата — только в `bytecode/FormatLimits.kt`, контракт строк libstr — в `bytecode/StringConstraints.kt`. Локальные копии не заводить.
- Новая семантическая проверка добавляет константу в `SemanticErrorCodes`. Тесты проверяют **код** диагностики, а не текст.
- Golden-файлы перегенерируются командами из `examples/README.md`. Новый пример добавляется в `compat/Examples.kt` и в `@ValueSource` тестов e2e/compat.

## Тесты

JUnit5, один класс на компонент (`src/test/kotlin/ru/itmo/applang/<слой>/`). `@DisplayName` пишется на русском, имена методов — в backticks на английском, тело — Arrange/Act/Assert, однотипные кейсы — параметризованные наборы.

Пакет `compat/` проверяет совместимость с GVM без самой ВМ. В нём:

- `ReferenceInterpreter` — модельная машина по контракту исполнения;
- `ProgramGenerator` — случайные корректные программы;
- мутационный тест reader'а;
- `ConformanceCorpus` — набор файлов для CI GVM.

## Документация и коммиты

- Документация на русском. При изменении поведения, архитектуры или запуска обновляй `README.md`/`docs/` в том же изменении.
- Сообщения коммитов на русском.
