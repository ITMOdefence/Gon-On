package ru.itmo.applang.compat

import ru.itmo.applang.bytecode.BytecodeIssue
import ru.itmo.applang.bytecode.BytecodeModule
import ru.itmo.applang.bytecode.BytecodeWriter
import ru.itmo.applang.bytecode.ClassEntry
import ru.itmo.applang.bytecode.ConstantValue
import ru.itmo.applang.bytecode.FieldEntry
import ru.itmo.applang.bytecode.FieldType
import ru.itmo.applang.bytecode.FormatLimits
import ru.itmo.applang.bytecode.FunctionEntry
import ru.itmo.applang.bytecode.Instruction
import ru.itmo.applang.bytecode.counterReferenceModule
import java.nio.file.Files
import java.nio.file.Path

/** Файл набора: байты и ожидание загрузчика GVM. */
data class CorpusEntry(
    val path: String,
    val profile: String,
    val description: String,
    val bytes: ByteArray,
    /** Ожидаемая категория отказа; null — файл корректен и должен загружаться. */
    val expectedIssue: BytecodeIssue? = null,
    val stdout: String? = null,
    val returnValue: Value? = null,
    /** Откуда взято ожидание вывода: из спецификации примера или из модельной машины. */
    val expectationSource: String? = null,
    val sourceFile: String? = null,
)

/**
 * Набор совместимости `.albc` для команды GVM (docs/backlog/base.md, BASE-07): корректные файлы
 * компилятора с ожидаемым выводом и намеренно испорченные файлы с ожидаемой категорией отказа
 * `BytecodeIssue`. Собирается задачей `./gradlew conformanceCorpus` в `build/conformance` и
 * публикуется артефактом CI, чтобы CI GVM мог прогнать свой загрузчик на тех же байтах.
 *
 * Испорченные файлы строятся из байтов корректных и через сериализацию без проверок, минуя
 * writer: компилятор такие файлы никогда не выдаёт.
 */
object ConformanceCorpus {

    fun entries(): List<CorpusEntry> = validEntries() + invalidProcedural() + invalidObject()

    // ------------------------------------------------------------------ корректные файлы

    private fun validEntries(): List<CorpusEntry> {
        val examples = Examples.all.map { expectation ->
            CorpusEntry(
                path = "valid/${expectation.name}.albc",
                profile = expectation.profile,
                description = "examples/${expectation.name}.al",
                bytes = Examples.compile(expectation.name).bytes,
                stdout = expectation.stdout,
                returnValue = expectation.returnValue,
                expectationSource = "specification",
                sourceFile = "examples/${expectation.name}.al",
            )
        }
        // Случайные программы: ожидаемый вывод получен модельной машиной, а не независимо.
        val generated = (0 until 10).map { seed ->
            val source = ProgramGenerator(1_000 + seed).generate()
            val compiled = ru.itmo.applang.compiler.Compiler.compile(source) as ru.itmo.applang.compiler.CompilationResult.Success
            val run = ReferenceInterpreter(compiled.module).run()
            CorpusEntry(
                path = "valid/generated_$seed.albc",
                profile = "${compiled.module.versionMajor}.${compiled.module.versionMinor}",
                description = "случайная корректная программа (seed ${1_000 + seed})",
                bytes = compiled.bytes,
                stdout = run.stdout,
                returnValue = run.returnValue,
                expectationSource = "reference-interpreter",
                sourceFile = source,
            )
        }
        return examples + generated
    }

    // ------------------------------------------------------------------ испорченные 1.0

    /** `fun main() { println(true); }` без проверок формата — основа для порчи. */
    private fun base(vararg code: Instruction, constants: List<ConstantValue> = BASE_CONSTANTS, locals: Int = 1) =
        BytecodeModule(
            1, 0, constants,
            listOf(FunctionEntry("main", 0, 0, locals, code.toList())),
            entryFunctionIndex = 0,
        )

    private val BASE_CONSTANTS = listOf(ConstantValue.StringConst("main"), ConstantValue.BoolConst(true), ConstantValue.IntConst(1))
    private val TRUE = Instruction.LoadConst(1)
    private val ONE = Instruction.LoadConst(2)

    private fun raw(module: BytecodeModule): ByteArray = BytecodeWriter.serialize(module)

    private fun ByteArray.with(index: Int, value: Int): ByteArray = copyOf().also { it[index] = value.toByte() }

    private fun invalid(name: String, profile: String, issue: BytecodeIssue, description: String, bytes: ByteArray) =
        CorpusEntry("invalid/$name.albc", profile, description, bytes, expectedIssue = issue)

    private fun invalidProcedural(): List<CorpusEntry> {
        val hello = Examples.compile("hello_world").bytes
        // Раскладка hello_world: header 0..11, пул 12..45 (строка #0: тег 14, длина 15..18, текст
        // 19..31; Int 32..36; "main" 37..45), function_count 46..47, запись функции 48..59 (имя
        // 48..49, params 50, locals 51, offset 52..55, length 56..59), code_size 60..63, код 64..71.
        check(hello.size == 72) { "раскладка hello_world изменилась: ${hello.size} байт" }
        val boolOffset = raw(base(TRUE, Instruction.Println, Instruction.ReturnVoid)).let { bytes ->
            // пул: count(2) + "main"(1+4+4) => тег Bool по смещению 12+2+9 = 23, значение — 24
            check(bytes[23].toInt() == FieldType.TAG_BOOL) { "раскладка базового модуля изменилась" }
            bytes
        }
        return listOf(
            invalid("bad_magic", "1.0", BytecodeIssue.MAGIC, "сигнатура XLBC вместо ALBC", hello.with(0, 'X'.code)),
            invalid("version_3_0", "?", BytecodeIssue.VERSION, "неизвестная мажорная версия 3.0", hello.with(5, 3)),
            invalid("version_1_1", "?", BytecodeIssue.VERSION, "неизвестная минорная версия 1.1", hello.with(7, 1)),
            invalid("flags_nonzero", "1.0", BytecodeIssue.FLAGS, "flags = 1", hello.with(9, 1)),
            invalid("entry_out_of_range", "1.0", BytecodeIssue.ENTRY, "entry_function_idx = 1 при одной функции", hello.with(11, 1)),
            invalid("constant_tag_unknown", "1.0", BytecodeIssue.CONSTANT_TAG, "тег константы 9", hello.with(14, 9)),
            invalid("bool_payload_2", "1.0", BytecodeIssue.BOOLEAN, "Bool-константа закодирована байтом 2", boolOffset.with(24, 2)),
            invalid("string_invalid_utf8", "1.0", BytecodeIssue.STRING, "некорректный UTF-8 в строке", hello.with(19, 0xC3)),
            invalid("string_with_nul", "1.0", BytecodeIssue.STRING, "байт NUL внутри строки", hello.with(19, 0)),
            invalid(
                "string_too_long", "1.0", BytecodeIssue.STRING,
                "строка на 1 байт длиннее предела libstr ${FormatLimits.MAX_STRING_BYTES}",
                stringTooLong(),
            ),
            invalid("trailing_byte", "1.0", BytecodeIssue.LAYOUT, "лишний байт после секции кода", hello + byteArrayOf(0)),
            invalid("truncated_header", "1.0", BytecodeIssue.TRUNCATED, "файл обрезан внутри заголовка", hello.copyOf(10)),
            invalid(
                "empty_function_table", "1.0", BytecodeIssue.LAYOUT, "таблица функций пуста",
                raw(base(Instruction.ReturnVoid).copy(functions = emptyList())),
            ),
            invalid("function_name_not_string", "1.0", BytecodeIssue.FUNCTION_NAME, "name_const_idx указывает на Int-константу", hello.with(49, 1)),
            invalid("params_exceed_locals", "1.0", BytecodeIssue.PARAMETERS, "param_count = 1 при local_count = 0", hello.with(50, 1)),
            invalid("code_offset_gap", "1.0", BytecodeIssue.LAYOUT, "code_offset первой функции = 1", hello.with(55, 1)),
            invalid("code_length_mismatch", "1.0", BytecodeIssue.LAYOUT, "code_length не совпадает с code_size", hello.with(59, 7)),
            invalid("code_size_huge", "1.0", BytecodeIssue.RESOURCE_LIMIT, "code_size = 0xFFFFFFFF", hello.with(60, 0xFF).with(61, 0xFF).with(62, 0xFF).with(63, 0xFF)),
            invalid("opcode_unknown", "1.0", BytecodeIssue.OPCODE, "неназначенный опкод 0x43", hello.with(hello.size - 1, 0x43)),
            invalid("operand_truncated", "1.0", BytecodeIssue.OPERAND, "LOAD_CONST без операнда в конце функции", hello.with(hello.size - 1, 0x01)),
            invalid(
                "object_opcode_in_1_0", "1.0", BytecodeIssue.OPCODE, "ALLOC_OBJECT в файле профиля 1.0",
                raw(base(Instruction.AllocObject(0), Instruction.Pop, Instruction.ReturnVoid)),
            ),
            invalid("constant_index", "1.0", BytecodeIssue.CONSTANT_INDEX, "LOAD_CONST вне пула", raw(base(Instruction.LoadConst(99), Instruction.Return))),
            invalid("local_index", "1.0", BytecodeIssue.LOCAL_INDEX, "LOAD_LOCAL вне local_count", raw(base(Instruction.LoadLocal(1), Instruction.Return))),
            invalid("function_index", "1.0", BytecodeIssue.FUNCTION_INDEX, "CALL несуществующей функции", raw(base(Instruction.Call(5), Instruction.ReturnVoid))),
            invalid(
                "jump_to_code_end", "1.0", BytecodeIssue.JUMP_TARGET,
                "недостижимый JUMP на смещение, равное длине функции (прежняя ошибка компилятора, BASE-01)",
                raw(base(TRUE, Instruction.Return, Instruction.Jump(9))),
            ),
            invalid("jump_into_operand", "1.0", BytecodeIssue.JUMP_TARGET, "JUMP внутрь операнда", raw(base(Instruction.Jump(1), Instruction.ReturnVoid))),
            invalid("mixed_returns", "1.0", BytecodeIssue.RETURN, "RETURN и RETURN_VOID в одной функции", raw(base(Instruction.ReturnVoid, ONE, Instruction.Return))),
            invalid("value_before_return_void", "1.0", BytecodeIssue.RETURN, "значение на стеке перед RETURN_VOID", raw(base(ONE, Instruction.ReturnVoid))),
            invalid("extra_value_before_return", "1.0", BytecodeIssue.RETURN, "два значения на стеке перед RETURN", raw(base(ONE, ONE, Instruction.Return))),
            invalid("stack_underflow", "1.0", BytecodeIssue.STACK_UNDERFLOW, "ADD с одним значением на стеке", raw(base(ONE, Instruction.Add, Instruction.Return))),
            invalid(
                "stack_join", "1.0", BytecodeIssue.STACK_JOIN, "в точку слияния приходят высоты 1 и 0",
                // 0 TRUE, 3 JUMP_IF_FALSE 11, 8 ONE, 11 RETURN_VOID
                raw(base(TRUE, Instruction.JumpIfFalse(11), ONE, Instruction.ReturnVoid)),
            ),
            invalid("loop_imbalance", "1.0", BytecodeIssue.STACK_JOIN, "цикл добавляет значение на каждом проходе", raw(base(ONE, Instruction.Jump(0)))),
            invalid("falloff", "1.0", BytecodeIssue.FALLOFF, "достижимый выход за конец функции", raw(base(ONE, Instruction.Pop))),
        )
    }

    /**
     * Строку длиннее предела writer закодировать откажется, поэтому файл собирается вручную:
     * в корректный файл с пустой строкой в конце пула подставляется длина и текст.
     */
    private fun stringTooLong(): ByteArray {
        val bytes = raw(base(ONE, Instruction.Return, constants = BASE_CONSTANTS + ConstantValue.StringConst("")))
        // пул: count(2) + "main"(9) + Bool(2) + Int(5) => пустая строка: тег 30, длина 31..34
        check(bytes[30].toInt() == 3) { "раскладка базового модуля изменилась" }
        val length = FormatLimits.MAX_STRING_BYTES + 1
        val lengthBytes = byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())
        return bytes.copyOfRange(0, 31) + lengthBytes + ByteArray(length) { 'a'.code.toByte() } + bytes.copyOfRange(35, bytes.size)
    }

    // ------------------------------------------------------------------ испорченные 2.0

    private fun invalidObject(): List<CorpusEntry> {
        val counter = Examples.compile("counter").bytes
        // ClassTable эталона: class_count 121..122, имя класса 123..124, field_count 125..126,
        // имя поля 127..128, type_tag 129, type_class_idx 130..131, method_count 132..133.
        val reference = counterReferenceModule()
        val counterClass = reference.classes.single()
        fun withClasses(vararg classes: ClassEntry) = raw(reference.copy(classes = classes.toList()))
        fun withMain(vararg code: Instruction) =
            raw(reference.copy(functions = reference.functions.dropLast(1) + reference.functions.last().copy(instructions = code.toList())))

        return listOf(
            invalid("field_type_tag_unknown", "2.0", BytecodeIssue.FIELD_TYPE, "type_tag поля = 5", counter.with(129, 5)),
            invalid("field_builtin_with_class", "2.0", BytecodeIssue.FIELD_TYPE, "Int-поле с type_class_idx = 0 вместо 0xFFFF", counter.with(130, 0).with(131, 0)),
            invalid("field_object_with_sentinel", "2.0", BytecodeIssue.FIELD_TYPE, "поле-объект с type_class_idx = 0xFFFF", counter.with(129, 4)),
            invalid("class_name_not_string", "2.0", BytecodeIssue.CLASS_NAME, "имя класса указывает на Int-константу", counter.with(124, 5)),
            invalid("field_name_not_string", "2.0", BytecodeIssue.FIELD_NAME, "имя поля указывает на Int-константу", counter.with(128, 5)),
            invalid("class_trailing_byte", "2.0", BytecodeIssue.LAYOUT, "лишний байт после секции кода 2.0", counter + byteArrayOf(0)),
            invalid("class_table_truncated", "2.0", BytecodeIssue.TRUNCATED, "файл обрезан внутри ClassTable", counter.copyOf(127)),
            invalid(
                "class_name_duplicate", "2.0", BytecodeIssue.CLASS_NAME, "два класса с именем Counter",
                withClasses(counterClass, counterClass.copy(methodFunctionIndices = emptyList())),
            ),
            invalid(
                "class_name_not_identifier", "2.0", BytecodeIssue.CLASS_NAME, "имя класса 'Counter.get' не идентификатор",
                withClasses(counterClass.copy(name = "Counter.get", nameConstIndex = 3)),
            ),
            invalid(
                "field_name_duplicate", "2.0", BytecodeIssue.FIELD_NAME, "два поля value в одном классе",
                withClasses(counterClass.copy(fields = counterClass.fields + counterClass.fields)),
            ),
            invalid(
                "method_without_this", "2.0", BytecodeIssue.METHOD_INDEX, "метод с param_count = 0",
                withClasses(counterClass.copy(methodFunctionIndices = listOf(2))),
            ),
            invalid(
                "method_two_owners", "2.0", BytecodeIssue.METHOD_INDEX, "одна функция — метод двух классов",
                withClasses(counterClass, ClassEntry("main", 4, listOf(FieldEntry("value", 1, FieldType.IntType)), listOf(1))),
            ),
            invalid("entry_with_params", "2.0", BytecodeIssue.ENTRY, "точка входа 2.0 с param_count = 1", raw(reference.copy(entryFunctionIndex = 1))),
            invalid("alloc_unknown_class", "2.0", BytecodeIssue.CLASS_INDEX, "ALLOC_OBJECT несуществующего класса", withMain(Instruction.AllocObject(1), Instruction.Return)),
            invalid(
                "get_unknown_field", "2.0", BytecodeIssue.FIELD_INDEX, "GET_FIELD несуществующего поля",
                withMain(Instruction.AllocObject(0), Instruction.GetField(0, 1), Instruction.Return),
            ),
            invalid(
                "set_field_underflow", "2.0", BytecodeIssue.STACK_UNDERFLOW, "SET_FIELD с одним значением на стеке",
                withMain(Instruction.AllocObject(0), Instruction.SetField(0, 0), Instruction.LoadConst(5), Instruction.Return),
            ),
        )
    }

    // ------------------------------------------------------------------ запись на диск

    fun write(directory: Path): List<CorpusEntry> {
        val entries = entries()
        for (entry in entries) {
            val file = directory.resolve(entry.path)
            Files.createDirectories(file.parent)
            Files.write(file, entry.bytes)
            entry.sourceFile?.let { source ->
                val sourceText = if (source.startsWith("examples/")) Files.readString(Examples.projectRoot().resolve(source)) else source
                Files.writeString(directory.resolve(entry.path.removeSuffix(".albc") + ".al"), sourceText)
            }
        }
        Files.writeString(directory.resolve("manifest.json"), manifest(entries))
        Files.writeString(directory.resolve("README.md"), README)
        return entries
    }

    private fun manifest(entries: List<CorpusEntry>): String = buildString {
        appendLine("{")
        appendLine("  \"format\": \"gon-on-albc-conformance/1\",")
        appendLine("  \"files\": [")
        entries.forEachIndexed { index, entry ->
            val fields = mutableListOf(
                "\"path\": ${json(entry.path)}",
                "\"profile\": ${json(entry.profile)}",
                "\"expect\": ${json(if (entry.expectedIssue == null) "accept" else "reject")}",
                "\"description\": ${json(entry.description)}",
            )
            entry.expectedIssue?.let { fields += "\"issue\": ${json(it.name)}" }
            entry.stdout?.let { fields += "\"stdout\": ${json(it)}" }
            if (entry.expectedIssue == null) fields += "\"returnValue\": ${returnValueJson(entry.returnValue)}"
            entry.expectationSource?.let { fields += "\"expectationSource\": ${json(it)}" }
            append("    { ").append(fields.joinToString(", ")).append(" }")
            appendLine(if (index < entries.lastIndex) "," else "")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun returnValueJson(value: Value?): String = when (value) {
        null -> "null"
        is Value.IntValue -> "{ \"type\": \"Int\", \"value\": ${value.value} }"
        is Value.BoolValue -> "{ \"type\": \"Bool\", \"value\": ${value.value} }"
        is Value.StringValue -> "{ \"type\": \"String\", \"value\": ${json(value.value)} }"
        is Value.ObjectValue -> "{ \"type\": \"Object\", \"classIndex\": ${value.classIndex} }"
    }

    private fun json(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private val README = """
        # Набор совместимости .albc (Gon&On -> GVM)

        Сгенерирован компилятором Gon&On (`./gradlew conformanceCorpus`). Описание каждого файла — в
        `manifest.json`:

        - `expect: "accept"` — корректный файл. Загрузчик и верификатор GVM обязаны его принять;
          раннер без аргументов должен напечатать `stdout`, а `main` — вернуть `returnValue`
          (`null` — `main` без результата). `expectationSource: "specification"` — ожидание
          выписано по смыслу исходника, `"reference-interpreter"` — получено модельной машиной
          компилятора. Исходник лежит рядом в `.al`.
        - `expect: "reject"` — файл нарушает контракт. GVM обязана отказать при загрузке;
          `issue` — ожидаемая категория `BytecodeIssue` из документации верификатора GVM.

        Контракт: `docs/BYTECODE_SPEC.md` и `docs/backlog/bytecode-contract.md` репозитория Gon-On.
    """.trimIndent() + "\n"
}

/** Точка входа задачи `conformanceCorpus`: `args[0]` — каталог для набора. */
fun main(args: Array<String>) {
    val directory = Path.of(args.firstOrNull() ?: "build/conformance")
    val entries = ConformanceCorpus.write(directory)
    val (valid, invalid) = entries.partition { it.expectedIssue == null }
    println("Набор совместимости: ${valid.size} корректных и ${invalid.size} испорченных файлов -> $directory")
}
