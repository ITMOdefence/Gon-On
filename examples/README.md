# Примеры программ на Gon&On

- `hello_world.al` — минимальная программа, встроенный `println`.
- `factorial_while.al` — локальные переменные, `while`, вызов функции с
  аргументом и возвращаемым значением. Печатает `120`.
- `gcd_demo.al` — алгоритм Евклида; `absolute()` — `if/else`, обе ветки
  которого возвращают значение (после then-ветки переход не генерируется).
  Печатает `12` и `1`.
- `counter.al` — эталон контракта `.albc` 2.0
  (`docs/backlog/bytecode-contract.md` §11): класс с `val`-полем,
  конструктор и метод. Компилируется ровно в 185 байт эталона; `main`
  возвращает `7`, вывода нет.
- `objects.al` — несколько классов, поле-объект, две ссылки на один объект,
  метод с несколькими параметрами, конструктор с ветвлением и инициализаторы
  полей. Печатает `25`, `6`, `100`, `35`, `3`, `Ann`.

Каждому `.al`-файлу соответствует golden-файл `<name>.albc.asm` —
зафиксированный дизассемблированный вывод компилятора для этой программы.
Golden-файлы используются в `e2e/EndToEndExamplesTest` (регрессия) и как
примеры в `docs/BYTECODE_SPEC.md` §8 для команды, разрабатывающей ВМ.

## Как прогнать вручную

Из корня репозитория:

```bash
./gradlew run --args="examples/hello_world.al -o /tmp/hello.albc --disasm"
./gradlew run --args="examples/factorial_while.al -o /tmp/factorial.albc --disasm"
./gradlew run --args="examples/gcd_demo.al -o /tmp/gcd.albc --disasm"
./gradlew run --args="examples/counter.al -o /tmp/counter.albc --disasm"
./gradlew run --args="examples/objects.al -o /tmp/objects.albc --disasm"
```

## Обновление golden-файлов

Если сознательно меняется codegen или формат дизассемблера, golden-файлы
нужно перегенерировать и проверить diff глазами (чтобы убедиться, что
изменение байткода — то, что и ожидалось):

```bash
for name in hello_world factorial_while gcd_demo counter objects; do
  ./gradlew run -q --args="examples/$name.al -o /tmp/$name.albc --disasm" | tail -n +3 > examples/$name.albc.asm
done
```

`tail -n +3` отрезает строку «Скомпилировано: ...» и пустую строку после
неё — они приходят из CLI, а не из дизассемблера. После перегенерации
прогони `./gradlew test`, чтобы `EndToEndExamplesTest` подтвердил
совпадение. Новый пример нужно добавить в `@ValueSource` этого теста.
