# Gon&On

![CI](https://github.com/ITMOdefence/Gon-On/actions/workflows/ci.yml/badge.svg)

**Gon&On** — учебный язык программирования с синтаксисом в духе Kotlin/Java
и его компилятор в байткод стековой машины. Проект курса «Разработка
мобильных приложений» (Университет ИТМО): команда разрабатывает упрощённый
аналог Android поверх Linux, и Gon&On — это язык, на котором в итоге должны
писаться мобильные приложения для этой платформы.

Эта часть проекта отвечает **только за язык и компилятор**. Виртуальную
машину, которая исполняет получившийся байткод, реализует другая команда на
C/C++ — она не видит код компилятора и работает исключительно по
спецификации в [`docs/BYTECODE_SPEC.md`](docs/BYTECODE_SPEC.md).

## Как исходный код превращается в байткод

```mermaid
flowchart TD
    A["Исходный код .al&#10;например examples/hello_world.al"] --> B

    subgraph ANTLR4["ANTLR4 (генерируется из грамматики)"]
        B["Lexer + Parser&#10;src/main/antlr/.../AppLang.g4"]
    end

    B --> C["Parse Tree"]
    C --> D["AstBuilder&#10;ast/AstBuilder.kt"]
    D --> E["AST&#10;ast/Ast.kt"]
    E --> F["TypeChecker&#10;semantic/TypeChecker.kt&#10;резолв имён + проверка типов"]
    F --> G["Типизированный AST&#10;resolvedType / resolvedSlot заполнены"]
    G --> H["BytecodeEmitter&#10;codegen/BytecodeEmitter.kt&#10;+ codegen/Labels.kt, ConstantPoolBuilder.kt"]
    H --> I["BytecodeModule&#10;bytecode/BytecodeModule.kt"]
    I --> J["BytecodeWriter&#10;bytecode/BytecodeWriter.kt"]
    J --> K["Бинарный файл байткода .albc"]
    I --> L["Disassembler&#10;bytecode/Disassembler.kt"]
    L --> M["Текстовый дамп .albc.asm"]
```

Если на любом из шагов "AstBuilder → TypeChecker" находится ошибка
(синтаксическая или семантическая), пайплайн останавливается и CLI
(`cli/Main.kt`) печатает диагностики вместо генерации байткода — до
`BytecodeEmitter` дело не доходит. Подробнее про каждый шаг — в
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), про сам формат `.albc` —
в [`docs/BYTECODE_SPEC.md`](docs/BYTECODE_SPEC.md).

## Статус

Текущая версия — **v1**: процедурный базис языка (без классов/ООП). Это не
одноразовый прототип, а первая ступень запланированной поэтапной эволюции к
полноценному языку для разработки приложений под ТЗ курса — см. roadmap в
[`docs/LANGUAGE_SPEC.md`](docs/LANGUAGE_SPEC.md#roadmap).

## Требования

- JDK 17+
- Gradle wrapper (входит в репозиторий, отдельно ничего ставить не нужно)

## Сборка и тесты

```bash
./gradlew build        # компиляция + весь тестовый набор
./gradlew test         # только тесты
```

## Компиляция программы на Gon&On

```bash
./gradlew run --args="examples/hello_world.al -o hello.albc --disasm"
```

Флаги CLI (`applangc`):

| Флаг | Назначение |
|---|---|
| `-o <path>` | путь к выходному файлу байткода (по умолчанию `<file>.albc`) |
| `--disasm` | вывести дизассемблированный байткод на stdout |
| `--dump-ast` | вывести AST на stdout (для отладки) |

## Структура репозитория

```
src/main/antlr/    грамматика AppLang (ANTLR4)
src/main/kotlin/   компилятор: AST, семантический анализ, codegen, байткод, CLI
src/test/kotlin/   тесты (по одному классу на компонент, см. ниже)
examples/          примеры программ на Gon&On + golden-файлы дизассемблированного байткода
docs/              спецификации языка и байткода, архитектура
```

## Документация

- [`docs/LANGUAGE_SPEC.md`](docs/LANGUAGE_SPEC.md) — грамматика, семантика,
  ограничения v1, roadmap следующих версий языка.
- [`docs/BYTECODE_SPEC.md`](docs/BYTECODE_SPEC.md) — **самодостаточная**
  спецификация набора опкодов и бинарного формата `.albc` для команды,
  разрабатывающей ВМ. Не требует чтения кода компилятора.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — пайплайн компилятора
  (для отчёта по курсу).

## Тестирование

Тесты организованы по слоям компилятора (JUnit5, параметризованные наборы
для однотипных кейсов, `@DisplayName` на русском):

- `lexer/LexerTest` — токенизация;
- `parser/ParserTest` — синтаксический анализ, приоритеты операторов;
- `ast/AstBuilderTest` — построение AST;
- `semantic/TypeCheckerTest` — резолв имён и проверка типов (позитив и
  параметризованный негатив: undefined var/function, type mismatch,
  val reassignment, arity mismatch и т.д.);
- `codegen/BytecodeEmitterTest` — генерация байткода для представительных
  конструкций (арифметика, if/else, while, вызов функции, short-circuit
  `&&`/`||`, конкатенация строк);
- `bytecode/BytecodeWriterReaderTest` — round-trip сериализации формата
  `.albc`;
- `e2e/EndToEndExamplesTest` — сквозная компиляция `examples/*.al` со
  сверкой дизассемблированного вывода с golden-файлами.

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`) собирает проект и прогоняет
весь тестовый набор на каждый push и pull request в `main`.
