package ru.itmo.applang.bytecode

import java.nio.file.Files
import java.nio.file.Path

/**
 * Строгий reference-reader `.albc`: разбирает файл обратно в [BytecodeModule] по тому же профилю,
 * что и загрузчик GVM (docs/backlog/base.md, BASE-07). Рантаймом компилятора не используется, но
 * служит независимой проверкой реально записанных байтов: [BytecodeWriter] прогоняет через него
 * каждый результат, а тесты — выходные файлы.
 *
 * Ничего не угадывает и не прощает: точная сигнатура, поддерживаемые major и minor, нулевые flags,
 * Bool только 0 / 1, строки по контракту libstr, непрерывные смещения функций, точный конец файла
 * после секции кода. Размеры из файла проверяются по оставшимся байтам и пределам до выделения
 * памяти; u32 трактуются как беззнаковые. Любое нарушение — [BytecodeFormatException].
 */
object BytecodeReader {
    private const val TAG_INT = 1
    private const val TAG_BOOL = 2
    private const val TAG_STRING = 3

    fun readFromFile(path: Path): BytecodeModule = readFromBytes(Files.readAllBytes(path))

    /** Читает файл и проверяет его полностью, включая [BytecodeVerifier]. */
    fun readFromBytes(bytes: ByteArray): BytecodeModule {
        val module = readUnverified(bytes)
        BytecodeVerifier.verify(module)
        return module
    }

    /**
     * Только безопасное декодирование и проверка раскладки, без анализа инструкций и стека.
     * Нужно тестам round-trip сериализации отдельных опкодов вне корректной программы.
     */
    internal fun readUnverified(bytes: ByteArray): BytecodeModule {
        val input = Cursor(bytes)

        val magic = input.bytes(BytecodeWriter.MAGIC.length, "сигнатура")
        if (!magic.contentEquals(BytecodeWriter.MAGIC.toByteArray(Charsets.US_ASCII))) {
            fail(BytecodeIssue.MAGIC, "неверная сигнатура файла, ожидалось '${BytecodeWriter.MAGIC}'")
        }
        val versionMajor = input.u16("version_major")
        val versionMinor = input.u16("version_minor")
        if ((versionMajor to versionMinor) !in BytecodeModule.SUPPORTED_VERSIONS) {
            fail(
                BytecodeIssue.VERSION,
                "версия формата $versionMajor.$versionMinor не поддерживается " +
                    "(поддерживаются ${BytecodeModule.SUPPORTED_VERSIONS.joinToString { "${it.first}.${it.second}" }})",
            )
        }
        val flags = input.u16("flags")
        if (flags != 0) {
            fail(BytecodeIssue.FLAGS, "flags = 0x${flags.toString(16)}, в профиле $versionMajor.$versionMinor допустим только 0")
        }
        val entryFunctionIndex = input.u16("entry_function_idx")

        val constants = readConstants(input)
        val functionHeaders = readFunctionTable(input)
        if (entryFunctionIndex >= functionHeaders.size) {
            fail(BytecodeIssue.ENTRY, "entry_function_idx = $entryFunctionIndex вне таблицы из ${functionHeaders.size} функций")
        }
        // ClassTable есть только в профиле 2.0 и лежит до code_size.
        val classes = if (versionMajor == BytecodeModule.OBJECT_VERSION_MAJOR) readClassTable(input, constants) else emptyList()

        val codeSize = input.u32("code_size")
        if (codeSize > FormatLimits.MAX_CODE_BYTES) {
            fail(BytecodeIssue.RESOURCE_LIMIT, "code_size = $codeSize больше предела реализации ${FormatLimits.MAX_CODE_BYTES}")
        }
        val codeBytes = input.bytes(codeSize.toInt(), "секция кода")
        if (input.remaining != 0) {
            fail(BytecodeIssue.LAYOUT, "после секции кода ${input.remaining} лишних байт (смещение ${input.position})")
        }
        checkContiguousCode(functionHeaders, codeSize)

        val functions = functionHeaders.mapIndexed { index, header ->
            val name = constants.getOrNull(header.nameConstIndex) as? ConstantValue.StringConst
                ?: fail(
                    BytecodeIssue.FUNCTION_NAME,
                    "функция #$index: name_const_idx = ${header.nameConstIndex} не ссылается на String-константу",
                )
            FunctionEntry(
                name = name.value,
                nameConstIndex = header.nameConstIndex,
                paramCount = header.paramCount,
                localCount = header.localCount,
                instructions = decodeInstructions(codeBytes, header, index, name.value, versionMajor),
            )
        }
        return BytecodeModule(versionMajor, versionMinor, constants, functions, entryFunctionIndex, classes)
    }

    private fun readClassTable(input: Cursor, constants: List<ConstantValue>): List<ClassEntry> {
        val classCount = input.u16("class_count")
        val classes = (0 until classCount).map { classIndex ->
            val classNameIndex = input.u16("name_const_idx класса #$classIndex")
            val className = stringConstant(constants, classNameIndex)
                ?: fail(BytecodeIssue.CLASS_NAME, "класс #$classIndex: name_const_idx = $classNameIndex не ссылается на String-константу")
            val fieldCount = input.u16("field_count класса #$classIndex")
            val fields = (0 until fieldCount).map { fieldIndex ->
                val where = "поле #$fieldIndex класса #$classIndex '$className'"
                val fieldNameIndex = input.u16("name_const_idx, $where")
                val fieldName = stringConstant(constants, fieldNameIndex)
                    ?: fail(BytecodeIssue.FIELD_NAME, "$where: name_const_idx = $fieldNameIndex не ссылается на String-константу")
                val tag = input.u8("type_tag, $where")
                val typeClassIndex = input.u16("type_class_idx, $where")
                FieldEntry(fieldName, fieldNameIndex, fieldType(tag, typeClassIndex, classCount, where))
            }
            val methodCount = input.u16("method_count класса #$classIndex")
            val methods = (0 until methodCount).map { input.u16("function_idx метода #$it класса #$classIndex") }
            ClassEntry(className, classNameIndex, fields, methods)
        }
        return classes
    }

    /** Каноническая пара тега и индекса: 1/2/3 только с 0xFFFF, 4 только с индексом существующего класса. */
    private fun fieldType(tag: Int, classIndex: Int, classCount: Int, where: String): FieldType {
        val builtin = when (tag) {
            FieldType.TAG_INT -> FieldType.IntType
            FieldType.TAG_BOOL -> FieldType.BoolType
            FieldType.TAG_STRING -> FieldType.StringType
            FieldType.TAG_OBJECT -> {
                if (classIndex >= classCount) {
                    fail(BytecodeIssue.FIELD_TYPE, "$where: тип-класс #$classIndex вне таблицы из $classCount классов")
                }
                return FieldType.ObjectType(classIndex)
            }
            else -> fail(BytecodeIssue.FIELD_TYPE, "$where: неизвестный type_tag $tag")
        }
        if (classIndex != FormatLimits.NO_CLASS_INDEX) {
            fail(BytecodeIssue.FIELD_TYPE, "$where: у встроенного типа type_class_idx = $classIndex вместо 0xFFFF")
        }
        return builtin
    }

    private fun stringConstant(constants: List<ConstantValue>, index: Int): String? =
        (constants.getOrNull(index) as? ConstantValue.StringConst)?.value

    private fun readConstants(input: Cursor): List<ConstantValue> {
        val count = input.u16("constant_count")
        return (0 until count).map { index ->
            when (val tag = input.u8("тег константы #$index")) {
                TAG_INT -> ConstantValue.IntConst(input.i32("Int-константа #$index"))
                TAG_BOOL -> when (val value = input.u8("Bool-константа #$index")) {
                    0 -> ConstantValue.BoolConst(false)
                    1 -> ConstantValue.BoolConst(true)
                    else -> fail(BytecodeIssue.BOOLEAN, "Bool-константа #$index закодирована байтом $value, допустимы только 0 и 1")
                }
                TAG_STRING -> {
                    val length = input.u32("длина строки #$index")
                    try {
                        StringConstraints.checkLength(length)
                        ConstantValue.StringConst(StringConstraints.decode(input.bytes(length.toInt(), "строка #$index")))
                    } catch (e: BytecodeFormatException) {
                        if (e.issue != BytecodeIssue.STRING) throw e
                        fail(BytecodeIssue.STRING, "константа #$index: ${e.message}")
                    }
                }
                else -> fail(BytecodeIssue.CONSTANT_TAG, "неизвестный тег константы #$index: $tag")
            }
        }
    }

    private class FunctionHeader(
        val nameConstIndex: Int,
        val paramCount: Int,
        val localCount: Int,
        val codeOffset: Long,
        val codeLength: Long,
    )

    private fun readFunctionTable(input: Cursor): List<FunctionHeader> {
        val count = input.u16("function_count")
        if (count == 0) {
            fail(BytecodeIssue.LAYOUT, "таблица функций пуста")
        }
        return (0 until count).map { index ->
            FunctionHeader(
                nameConstIndex = input.u16("name_const_idx функции #$index"),
                paramCount = input.u8("param_count функции #$index"),
                localCount = input.u8("local_count функции #$index"),
                codeOffset = input.u32("code_offset функции #$index"),
                codeLength = input.u32("code_length функции #$index"),
            )
        }
    }

    /** Код функций лежит подряд: первый offset 0, каждый следующий — конец предыдущего, последний конец — code_size. */
    private fun checkContiguousCode(headers: List<FunctionHeader>, codeSize: Long) {
        var expectedOffset = 0L
        headers.forEachIndexed { index, header ->
            if (header.codeOffset != expectedOffset) {
                fail(
                    BytecodeIssue.LAYOUT,
                    "функция #$index: code_offset = ${header.codeOffset}, ожидался $expectedOffset (код без разрывов и наложений)",
                )
            }
            if (header.codeLength == 0L) {
                fail(BytecodeIssue.LAYOUT, "функция #$index: code_length = 0")
            }
            expectedOffset += header.codeLength
            if (expectedOffset > codeSize) {
                fail(BytecodeIssue.LAYOUT, "функция #$index выходит за пределы секции кода размером $codeSize")
            }
        }
        if (expectedOffset != codeSize) {
            fail(BytecodeIssue.LAYOUT, "сумма длин функций $expectedOffset не равна code_size = $codeSize")
        }
    }

    private fun decodeInstructions(
        codeBytes: ByteArray,
        header: FunctionHeader,
        index: Int,
        name: String,
        versionMajor: Int,
    ): List<Instruction> {
        val start = header.codeOffset.toInt()
        val end = start + header.codeLength.toInt()
        val input = Cursor(codeBytes, start, end)
        val result = mutableListOf<Instruction>()
        while (input.remaining > 0) {
            val offset = input.position - start
            val where = "функция #$index '$name', +$offset"
            val opcodeByte = input.u8(where)
            val opcode = Opcode.fromCodeOrNull(opcodeByte)
                ?: fail(BytecodeIssue.OPCODE, "$where: неизвестный опкод 0x${opcodeByte.toString(16).padStart(2, '0')}")
            if (opcode.sinceVersionMajor > versionMajor) {
                fail(BytecodeIssue.OPCODE, "$where: опкод $opcode недопустим в профиле $versionMajor.0")
            }
            if (input.remaining < opcode.operandBytes) {
                fail(BytecodeIssue.OPERAND, "$where: операнд $opcode обрезан концом функции")
            }
            result += when (opcode) {
                Opcode.LOAD_CONST -> Instruction.LoadConst(input.u16(where))
                Opcode.LOAD_LOCAL -> Instruction.LoadLocal(input.u8(where))
                Opcode.STORE_LOCAL -> Instruction.StoreLocal(input.u8(where))
                Opcode.ADD -> Instruction.Add
                Opcode.SUB -> Instruction.Sub
                Opcode.MUL -> Instruction.Mul
                Opcode.DIV -> Instruction.Div
                Opcode.MOD -> Instruction.Mod
                Opcode.NEG -> Instruction.Neg
                Opcode.CONCAT -> Instruction.Concat
                Opcode.CMP_EQ -> Instruction.CmpEq
                Opcode.CMP_NE -> Instruction.CmpNe
                Opcode.CMP_LT -> Instruction.CmpLt
                Opcode.CMP_GT -> Instruction.CmpGt
                Opcode.CMP_LE -> Instruction.CmpLe
                Opcode.CMP_GE -> Instruction.CmpGe
                Opcode.NOT -> Instruction.Not
                Opcode.JUMP -> Instruction.Jump(jumpTarget(input.u32(where), header, where))
                Opcode.JUMP_IF_FALSE -> Instruction.JumpIfFalse(jumpTarget(input.u32(where), header, where))
                Opcode.CALL -> Instruction.Call(input.u16(where))
                Opcode.RETURN -> Instruction.Return
                Opcode.RETURN_VOID -> Instruction.ReturnVoid
                Opcode.PRINT -> Instruction.Print
                Opcode.PRINTLN -> Instruction.Println
                Opcode.POP -> Instruction.Pop
                Opcode.ALLOC_OBJECT -> Instruction.AllocObject(input.u16(where))
                Opcode.GET_FIELD -> Instruction.GetField(input.u16(where), input.u16(where))
                Opcode.SET_FIELD -> Instruction.SetField(input.u16(where), input.u16(where))
            }
        }
        return result
    }

    private fun jumpTarget(target: Long, header: FunctionHeader, where: String): Int {
        if (target >= header.codeLength) {
            fail(BytecodeIssue.JUMP_TARGET, "$where: цель перехода $target вне кода функции длиной ${header.codeLength}")
        }
        return target.toInt()
    }

    private fun fail(issue: BytecodeIssue, message: String): Nothing = throw BytecodeFormatException(issue, message)

    /** Курсор чтения big-endian полей с проверкой границ: нехватка байтов — TRUNCATED, а не исключение JVM. */
    private class Cursor(private val bytes: ByteArray, start: Int = 0, private val end: Int = bytes.size) {
        var position = start
            private set

        val remaining: Int get() = end - position

        private fun require(count: Int, field: String) {
            if (count < 0 || count > remaining) {
                throw BytecodeFormatException(
                    BytecodeIssue.TRUNCATED,
                    "$field: нужно $count байт по смещению $position, осталось $remaining",
                )
            }
        }

        fun u8(field: String): Int {
            require(1, field)
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(field: String): Int {
            require(2, field)
            return (u8(field) shl 8) or u8(field)
        }

        fun u32(field: String): Long {
            require(4, field)
            return (u16(field).toLong() shl 16) or u16(field).toLong()
        }

        fun i32(field: String): Int = u32(field).toInt()

        fun bytes(count: Int, field: String): ByteArray {
            require(count, field)
            return bytes.copyOfRange(position, position + count).also { position += count }
        }
    }
}
