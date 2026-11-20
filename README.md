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
    I --> V["BytecodeVerifier + строгий BytecodeReader&#10;проверка по правилам GVM до записи"]
    V --> J["BytecodeWriter&#10;bytecode/BytecodeWriter.kt"]
    J --> K["Бинарный файл байткода .albc"]
    I --> L["Disassembler&#10;bytecode/Disassembler.kt"]
    L --> M["Текстовый дамп .albc.asm"]
```

Все стадии собраны в `compiler/Compiler.kt`, которым пользуются и CLI, и
тесты. Если на любой стадии находится ошибка — синтаксическая,
семантическая, превышение пределов формата или нарушение правил
верификатора GVM в сгенерированном байткоде, — пайплайн останавливается, CLI
(`cli/Main.kt`) печатает диагностики, а выходной файл не создаётся и не
перезаписывается. Подробнее про каждый шаг — в
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), про сам формат `.albc` —
в [`docs/BYTECODE_SPEC.md`](docs/BYTECODE_SPEC.md).

## Статус

- **v1** — процедурный базис: функции, переменные, `Int`/`Bool`/`String`,
  `if`/`while`, арифметика. Компилируется в профиль `.albc` **1.0**.
- **v2** — классы: поля, конструктор, методы, `this`, создание экземпляров,
  ссылочная семантика. Программа с классами компилируется в профиль
  `.albc` **2.0** (таблица классов + `ALLOC_OBJECT`/`GET_FIELD`/`SET_FIELD`).
- **Платформенный API** — компилятор загружает 21 сигнатуру из
  [`platform-api.json`](platform-api.json), проверяет платформенные типы и
  генерирует отдельный опкод для каждого вызова в `.albc` **3.0**.
  Поддерживаются `bindAppState` и четыре обработчика событий.
  Межпроектный контракт остаётся `draft`: исполнение 3.0 требует изменений
  в GVM и адаптеров PUNos, описанных в [`docs/PLATFORM_CONTRACT.md`](docs/PLATFORM_CONTRACT.md).

Профили 1.0/2.0 соответствуют контракту с командой GVM
([`docs/backlog/`](docs/backlog/)): сгенерированный байткод проверяется
правилами загрузчика и верификатора GVM до записи файла. Тесты этого
репозитория подтверждают совместимость по контракту и не заменяют
фактический прогон на отдельно разрабатываемой GVM. Проверенные ревизии
GVM/PUNos и границы интеграции указаны в
[`docs/PLATFORM_CONTRACT.md`](docs/PLATFORM_CONTRACT.md). Дальнейшие шаги — roadmap в
[`docs/LANGUAGE_SPEC.md`](docs/LANGUAGE_SPEC.md#roadmap).

```kotlin
class Counter {
    val value: Int;
    constructor(value: Int) { this.value = value; }
    fun get(): Int { return this.value; }
}

fun main(): Int {
    val counter = Counter(7);
    return counter.get();
}
```

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
| `--disasm` | вывести дизассемблированный байткод (модель в памяти, совпадающая с записанным файлом) на stdout |
| `--dump-ast` | вывести AST на stdout (для отладки) |

Коды завершения: `0` — файл записан; `1` — ошибка компиляции, чтения или
записи (диагностики в stderr, выходной файл не тронут); `2` — неверные
аргументы. Исходник читается как UTF-8.

## Структура репозитория

```
src/main/antlr/    грамматика AppLang (ANTLR4)
src/main/kotlin/   компилятор: AST, семантический анализ, codegen, байткод, CLI
src/test/kotlin/   компонентные, платформенные и совместимые с контрактом тесты
examples/          примеры программ на Gon&On + golden-файлы дизассемблированного байткода
platform-api.json  общий черновик сигнатур платформенных функций для Gon&On и GVM
docs/              спецификации языка и байткода, архитектура
docs/backlog/      требования команды GVM и общий бинарный контракт 1.0 / 2.0
```

## Компиляция платформенного приложения

```bash
./gradlew run --args="examples/platform_app.al -o /tmp/platform_app.albc --disasm"
./gradlew installDist
```

`build/install/applang/bin/applang` можно запускать из любого каталога:
`platform-api.json` включён в JAR и не ищется относительно текущего пути.
Дополнительные переменные окружения не требуются. Пример `platform_app.al`
создаёт окно, состояние и обработчики; `platform_calls.al` содержит все
21 вызов для проверки байткода. Компилятор не запускает GVM или PUNos.
Старая GVM для 1.0/2.0 отклонит файл 3.0 как неподдерживаемую версию.

## Документация

- [`docs/LANGUAGE_SPEC.md`](docs/LANGUAGE_SPEC.md) — грамматика, семантика,
  ограничения v1, roadmap следующих версий языка.
- [`docs/BYTECODE_SPEC.md`](docs/BYTECODE_SPEC.md) — **самодостаточная**
  спецификация набора опкодов и бинарного формата `.albc` для команды,
  разрабатывающей ВМ. Не требует чтения кода компилятора.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — пайплайн компилятора
  (для отчёта по курсу).
- [`docs/PLATFORM_API.md`](docs/PLATFORM_API.md) — формат общего каталога
  платформенных функций, типы и порядок согласования изменений.
- [`docs/PLATFORM_CONTRACT.md`](docs/PLATFORM_CONTRACT.md) — черновик контракта
  Gon&On — GVM — PUNos: отдельная инструкция и C-функция на каждый вызов,
  предлагаемый профиль `.albc` 3.0, результаты, события, ресурсы и критерии
  приёмки. Часть компилятора реализована; runtime требует согласования и реализации команд GVM/PUNos.
- [`docs/backlog/`](docs/backlog/) — бэклог совместимости с GVM:
  базовый профиль (`base.md`), объектная модель (`oop.md`), решения по
  языку (`language-decisions.md`) и общий бинарный контракт двух команд
  (`bytecode-contract.md`).

## Тестирование

```bash
./gradlew test --tests 'ru.itmo.applang.semantic.TypeCheckerTest'   # один класс
./gradlew test --tests '*TypeCheckerTest.*main*'                     # по шаблону имени
```

Тесты организованы по слоям компилятора (JUnit5, параметризованные наборы
для однотипных кейсов, `@DisplayName` на русском):

- `lexer/LexerTest` — токенизация;
- `parser/ParserTest` — синтаксический анализ, приоритеты операторов;
- `ast/AstBuilderTest` — построение AST;
- `semantic/TypeCheckerTest` — резолв имён и проверка типов (позитив и
  параметризованный негатив: undefined var/function, type mismatch,
  val reassignment, arity mismatch, Unit как значение, строки libstr,
  точка входа `main`, пределы слотов и параметров);
- `semantic/ClassTypeCheckerTest` — правила классов LANG-01…10: имена,
  члены, конструктор, заполненность полей, раскрытие `this`, `val`-поля;
- `codegen/BytecodeEmitterTest` — генерация байткода для представительных
  конструкций (арифметика, if/else, while, вызов функции, short-circuit
  `&&`/`||`, конкатенация строк) и корректность всех целей переходов;
- `bytecode/BytecodeVerifierTest` — правила верификатора GVM: underflow,
  слияния, циклы, возвраты, falloff, индексы, рекурсия;
- `bytecode/BytecodeWriterReaderTest` — round-trip, независимая сверка
  байтов раскладки, строгий reader на испорченных и обрезанных файлах,
  отказ writer'а от невалидного модуля;
- `bytecode/StringConstraintsTest` — контракт строк libstr;
- `bytecode/ObjectProfileTest` — профиль 2.0: эталон контракта на 185 байт,
  ClassTable и объектные инструкции, отказы verifier и reader;
- `cli/CliTest` — коды завершения и сохранность выходного файла при ошибках;
- `e2e/EndToEndExamplesTest` — сквозная компиляция `examples/*.al` через CLI в
  реальный файл, чтение строгим reader'ом и сверка с golden-файлами.

### Совместимость с GVM без самой ВМ (`compat/`)

Совместимость в этом репозитории проверяется по контракту — отдельно
от фактического прогона на внешней GVM:

- `ContractGoldenBytesTest` — `examples/counter.al` компилируется ровно в
  185 байт эталона `docs/backlog/bytecode-contract.md` §11; байты всех
  примеров совпадают с зафиксированными `examples/*.albc.hex`; поля заголовка
  разбираются вручную, без reader'а компилятора;
- `ReaderMutationTest` — 15 000 детерминированных порч файлов примеров:
  строгий reader либо отказывает с `BytecodeFormatException`, либо принимает
  каноничный файл (повторная запись даёт те же байты), но никогда не падает
  исключением JVM;
- `RandomProgramTest` — 300 случайных корректных программ (`ProgramGenerator`,
  оба профиля): компиляция, проверка верификатором, round-trip через байты и
  исполнение без ошибок;
- `ExampleExecutionTest` — модельная машина (`ReferenceInterpreter`,
  реализует контракт исполнения) исполняет байты примеров: вывод, результат
  `main`, порядок вычисления аргументов и receiver, short-circuit, ссылочная
  семантика;
- `ConformanceCorpusTest` — набор файлов для CI GVM ведёт себя ровно так, как
  заявлено в его `manifest.json`.

```bash
./gradlew test --tests 'ru.itmo.applang.compat.*'   # только тесты совместимости
./gradlew conformanceCorpus                          # набор .albc + manifest.json в build/conformance
```

Набор совместимости (`build/conformance`) содержит корректные файлы с
ожидаемым выводом и результатом и испорченные файлы с ожидаемой категорией
отказа `BytecodeIssue` (по одному и больше на каждую категорию верификатора
GVM). Он предназначен для подключения к CI внешнего репозитория GVM.

Формат manifest — `gon-on-albc-conformance/2`. Добавлены исполняемые сценарии
текстовых операций 3.0 и режим `execution: "run-fault"`. Такой файл должен
успешно загружаться, затем завершаться категорией `runtimeFault`, например
`UNINITIALIZED` при чтении `value` ошибочного результата. Для этого режима
поле `returnValue` отсутствует. Потребитель corpus должен поддерживать версию 2.
Модельные проверки текстовых операций используют `PlatformTextHost`.
Для внешней приёмки эти же файлы нужно выполнить на GVM.

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`) на каждый push и pull request в
`main` запускает два джоба:

- `build-and-test` — сборка и весь тестовый набор;
- `compat` — тесты совместимости с контрактом GVM и сборка набора
  совместимости, который публикуется артефактом `conformance-corpus`.

Тесты, которым нужен доступ к репозиториям GVM и PUNos (сверка опкодов с
документацией GVM, пределов с libstr, фактический прогон на ВМ), пока не
подключены к CI этого репозитория. Наличие реализации внешней GVM само
по себе не подтверждает прохождение этих проверок.
