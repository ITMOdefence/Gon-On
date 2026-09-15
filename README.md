# AppLang

![CI](https://github.com/ITMOdefence/Gon-On/actions/workflows/ci.yml/badge.svg)

**AppLang** — учебный язык программирования с синтаксисом в духе Kotlin/Java
и его компилятор в байткод стековой машины. Проект курса «Разработка
мобильных приложений» (Университет ИТМО): команда разрабатывает упрощённый
аналог Android поверх Linux, и AppLang — это язык, на котором в итоге должны
писаться мобильные приложения для этой платформы.

Эта часть проекта отвечает **только за язык и компилятор**. Виртуальную
машину, которая исполняет получившийся байткод, реализует другая команда на
C/C++ — она не видит код компилятора и работает исключительно по
спецификации в [`docs/BYTECODE_SPEC.md`](docs/BYTECODE_SPEC.md).

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

## Компиляция программы на AppLang

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
examples/          примеры программ на AppLang + golden-файлы дизассемблированного байткода
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
