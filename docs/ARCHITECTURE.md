# Архитектура компилятора Gon&On

## Пайплайн

```
исходный .al файл
      │
      ▼
┌─────────────────┐   ANTLR4-сгенерированные AppLangLexer/AppLangParser
│  Lexer + Parser  │   (src/main/antlr/.../AppLang.g4)
└─────────────────┘
      │  ParseTree
      ▼
┌─────────────────┐
│   AstBuilder     │   ru.itmo.applang.ast.AstBuilder — Visitor над ParseTree
└─────────────────┘
      │  AST (Program, FunctionDecl, Stmt, Expr — ru.itmo.applang.ast.Ast)
      ▼
┌─────────────────┐
│   TypeChecker    │   ru.itmo.applang.semantic.TypeChecker
│                  │   резолв имён (SymbolTable), проверка типов,
│                  │   заполняет Expr.resolvedType и слоты локальных переменных
└─────────────────┘
      │  типизированный AST (или диагностики об ошибках — тогда пайплайн останавливается)
      ▼
┌─────────────────┐
│ BytecodeEmitter  │   ru.itmo.applang.codegen.BytecodeEmitter
│                  │   обход AST -> List<Instruction> на функцию,
│                  │   InstructionBuffer (codegen/Labels.kt) резолвит метки if/while
│                  │   в byte-offset'ы, ConstantPoolBuilder дедуплицирует константы
└─────────────────┘
      │  BytecodeModule (ru.itmo.applang.bytecode)
      ▼
┌─────────────────┐        ┌─────────────────┐
│ BytecodeWriter   │        │  Disassembler    │
│ -> файл .albc    │        │ -> текстовый .asm│
└─────────────────┘        └─────────────────┘
```

`BytecodeReader` — обратное чтение `.albc` в `BytecodeModule`; не
используется рантаймом компилятора, но служит reference-реализацией
формата для round-trip тестов и для дизассемблирования уже
сериализованных файлов (полезно как независимый инструмент проверки
формата для команды ВМ).

## Пакеты

| Пакет | Ответственность |
|---|---|
| `ru.itmo.applang.parser` | ANTLR-сгенерированный код (не редактируется вручную) |
| `ru.itmo.applang.ast` | AST-узлы и построение AST из ParseTree |
| `ru.itmo.applang.semantic` | таблицы символов, проверка типов, коды семантических ошибок |
| `ru.itmo.applang.codegen` | генерация байткода из типизированного AST |
| `ru.itmo.applang.bytecode` | модель байткода, опкоды, бинарная сериализация/десериализация, дизассемблер |
| `ru.itmo.applang.diagnostics` | единый формат диагностик для всех стадий (lexer/parser/semantic) |
| `ru.itmo.applang.cli` | точка входа `applangc` |

## Точки расширения для будущих версий языка

- Новые узлы AST добавляются как новые подтипы `sealed class Stmt`/`Expr`
  (`ast/Ast.kt`) — существующие узлы не меняются.
- Новые опкоды добавляются в `Opcode.kt` начиная с `0x40` (см.
  `docs/BYTECODE_SPEC.md` §3) — существующие коды не переиспользуются.
- Новые семантические проверки — новые ветки в `TypeChecker` + новые коды
  в `SemanticErrorCodes`.
