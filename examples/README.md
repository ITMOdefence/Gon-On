# Примеры программ на AppLang

- `hello_world.al` — минимальная программа, встроенный `println`.
- `factorial_while.al` — локальные переменные, `while`, вызов функции с
  аргументом и возвращаемым значением.

Каждому `.al`-файлу соответствует golden-файл `<name>.albc.asm` —
зафиксированный дизассемблированный вывод компилятора для этой программы.
Golden-файлы используются в `e2e/EndToEndExamplesTest` (регрессия) и как
примеры в `docs/BYTECODE_SPEC.md` §8 для команды, разрабатывающей ВМ.

## Как прогнать вручную

Из корня репозитория:

```bash
./gradlew run --args="examples/hello_world.al -o /tmp/hello.albc --disasm"
./gradlew run --args="examples/factorial_while.al -o /tmp/factorial.albc --disasm"
```

## Обновление golden-файлов

Если сознательно меняется codegen или формат дизассемблера, golden-файлы
нужно перегенерировать и проверить diff глазами (чтобы убедиться, что
изменение байткода — то, что и ожидалось):

```bash
./gradlew run --args="examples/hello_world.al -o /tmp/hello.albc --disasm" -q | tail -n +2 > examples/hello_world.albc.asm
./gradlew run --args="examples/factorial_while.al -o /tmp/factorial.albc --disasm" -q | tail -n +2 > examples/factorial_while.albc.asm
```

После перегенерации убери случайную пустую первую строку (она приходит из
CLI-обёртки, не из самого дизассемблера) и прогони `./gradlew test`, чтобы
`EndToEndExamplesTest` подтвердил совпадение.
