package ru.itmo.applang.bytecode

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path

/** Модуль со всеми опкодами 1.0 и 2.0 — структурно сериализуемый, но не обязательно проходящий проверку стека. */
private fun allOpcodesModule(): BytecodeModule {
    val constants = listOf(
        ConstantValue.IntConst(42),
        ConstantValue.BoolConst(true),
        ConstantValue.StringConst("привет, мир"), // не-ASCII специально — проверяем UTF-8
        ConstantValue.StringConst(""), // пустая строка — граничный случай
        ConstantValue.StringConst("main"),
        ConstantValue.BoolConst(false),
        ConstantValue.IntConst(-1),
    )
    val mainFunction = FunctionEntry(
        name = "main",
        nameConstIndex = 4,
        paramCount = 0,
        localCount = 2,
        instructions = listOf(
            Instruction.LoadConst(0),
            Instruction.LoadLocal(0),
            Instruction.StoreLocal(1),
            Instruction.Jump(0),
            Instruction.JumpIfFalse(3),
            Instruction.Call(0),
            Instruction.Add, Instruction.Sub, Instruction.Mul, Instruction.Div, Instruction.Mod,
            Instruction.Neg, Instruction.Concat,
            Instruction.CmpEq, Instruction.CmpNe, Instruction.CmpLt, Instruction.CmpGt,
            Instruction.CmpLe, Instruction.CmpGe, Instruction.Not,
            Instruction.Print, Instruction.Println, Instruction.Pop,
            Instruction.Return, Instruction.ReturnVoid,
            Instruction.AllocObject(0), Instruction.GetField(0, 1), Instruction.SetField(0, 1),
        ),
    )
    return BytecodeModule(
        versionMajor = 2,
        versionMinor = BytecodeModule.VERSION_MINOR,
        constants = constants,
        functions = listOf(mainFunction),
        entryFunctionIndex = 0,
        classes = listOf(
            ClassEntry(
                name = "main",
                nameConstIndex = 4,
                fields = listOf(FieldEntry("main", 4, FieldType.IntType), FieldEntry("main", 4, FieldType.ObjectType(0))),
                methodFunctionIndices = listOf(0),
            ),
        ),
    )
}

/** Корректная программа: `fun main(): Int { println("hi"); return 7; }`. */
private fun validModule(): BytecodeModule = BytecodeModule(
    versionMajor = 1,
    versionMinor = 0,
    constants = listOf(ConstantValue.StringConst("hi"), ConstantValue.IntConst(7), ConstantValue.StringConst("main")),
    functions = listOf(
        FunctionEntry(
            name = "main",
            nameConstIndex = 2,
            paramCount = 0,
            localCount = 0,
            instructions = listOf(Instruction.LoadConst(0), Instruction.Println, Instruction.LoadConst(1), Instruction.Return),
        ),
    ),
    entryFunctionIndex = 0,
)

private fun hex(text: String): ByteArray =
    text.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

class BytecodeWriterReaderTest {

    @Test
    @DisplayName("Round-trip: записанный и затем прочитанный корректный модуль совпадает с исходным")
    fun `valid module survives a write-then-read round trip unchanged`() {
        // Arrange
        val original = validModule()

        // Act
        val bytes = BytecodeWriter.writeToBytes(original)
        val restored = BytecodeReader.readFromBytes(bytes)

        // Assert
        assertEquals(original, restored)
    }

    @Test
    @DisplayName("Сериализация всех опкодов и ClassTable симметрична структурному чтению")
    fun `every opcode and the class table round trip through serialization`() {
        // Arrange
        val original = allOpcodesModule()

        // Act
        val restored = BytecodeReader.readUnverified(BytecodeWriter.serialize(original))

        // Assert
        assertEquals(original, restored)
    }

    @Test
    @DisplayName("Байты корректного модуля совпадают с раскладкой спецификации независимо от reader")
    fun `serialized bytes match the specified layout`() {
        // Arrange
        val expected = hex(
            "41 4C 42 43  00 01  00 00  00 00  00 00" + // header: ALBC, 1.0, flags 0, entry 0
                " 00 03  03 00 00 00 02 68 69  01 00 00 00 07  03 00 00 00 04 6D 61 69 6E" + // 3 константы
                " 00 01  00 02  00  00  00 00 00 00  00 00 00 08" + // 1 функция: name 2, params 0, locals 0, offset 0, length 8
                " 00 00 00 08  01 00 00  18  01 00 01  15", // code_size 8: LOAD_CONST 0, PRINTLN, LOAD_CONST 1, RETURN
        )

        // Act
        val bytes = BytecodeWriter.writeToBytes(validModule())

        // Assert
        assertArrayEquals(expected, bytes)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corruptions")
    @DisplayName("Строгий reader отклоняет послабления относительно профиля GVM с ожидаемой причиной")
    fun `strict reader rejects files that violate the GVM profile`(
        description: String,
        corrupt: (ByteArray) -> ByteArray,
        expectedIssue: BytecodeIssue,
    ) {
        // Arrange
        val bytes = corrupt(BytecodeWriter.writeToBytes(validModule()))

        // Act
        val error = assertThrows(BytecodeFormatException::class.java) { BytecodeReader.readFromBytes(bytes) }

        // Assert
        assertEquals(expectedIssue, error.issue, error.message)
    }

    @Test
    @DisplayName("Обрезанный в любом месте файл даёт ошибку формата, а не исключение JVM")
    fun `file truncated anywhere is rejected with a format error`() {
        // Arrange
        val bytes = BytecodeWriter.writeToBytes(validModule())

        // Act & Assert
        for (length in 0 until bytes.size) {
            assertThrows(BytecodeFormatException::class.java) { BytecodeReader.readFromBytes(bytes.copyOf(length)) }
        }
    }

    @Test
    @DisplayName("Огромная длина строки из файла отклоняется до выделения памяти")
    fun `huge string length is rejected before allocation`() {
        // Arrange: длина первой строки = 0xFFFFFFFF (беззнаковый u32, а не -1)
        val bytes = BytecodeWriter.writeToBytes(validModule())
        for (i in 15..18) bytes[i] = 0xFF.toByte()

        // Act
        val error = assertThrows(BytecodeFormatException::class.java) { BytecodeReader.readFromBytes(bytes) }

        // Assert
        assertEquals(BytecodeIssue.STRING, error.issue)
    }

    @Test
    @DisplayName("Writer не сериализует модуль, нарушающий контракт, даже при вызове в обход CLI")
    fun `writer refuses an invalid module passed directly`() {
        // Arrange: переход на конец функции (смещение 6 = длина кода) — то, что раньше генерировал компилятор
        val module = validModule().let {
            it.copy(functions = listOf(it.functions.single().copy(instructions = listOf(Instruction.Jump(6), Instruction.ReturnVoid))))
        }

        // Act & Assert
        assertThrows(BytecodeFormatException::class.java) { BytecodeWriter.writeToBytes(module) }
    }

    @Test
    @DisplayName("При ошибке проверки файл не создаётся, а существующий не меняется")
    fun `invalid module leaves the output file untouched`(@TempDir directory: Path) {
        // Arrange
        val invalid = validModule().copy(entryFunctionIndex = 3)
        val missing = directory.resolve("missing.albc")
        val existing = directory.resolve("existing.albc")
        Files.write(existing, byteArrayOf(1, 2, 3))

        // Act
        assertThrows(BytecodeFormatException::class.java) { BytecodeWriter.writeToFile(invalid, missing) }
        assertThrows(BytecodeFormatException::class.java) { BytecodeWriter.writeToFile(invalid, existing) }

        // Assert
        assertFalse(Files.exists(missing))
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(existing))
        assertEquals(listOf("existing.albc"), Files.list(directory).map { it.fileName.toString() }.toList())
    }

    @Test
    @DisplayName("Значение, не помещающееся в поле u8/u16, отклоняется writer'ом, а не усекается")
    fun `writer rejects values that do not fit their field instead of truncating`() {
        // Arrange
        val module = validModule()
        val main = module.functions.single()
        val tooManyLocals = module.copy(functions = listOf(main.copy(localCount = 256)))
        val tooBigConstIndex = module.copy(
            functions = listOf(main.copy(instructions = listOf(Instruction.LoadConst(70_000), Instruction.Return))),
        )

        // Act & Assert
        assertThrows(BytecodeFormatException::class.java) { BytecodeWriter.writeToBytes(tooManyLocals) }
        assertThrows(BytecodeFormatException::class.java) { BytecodeWriter.serialize(tooManyLocals) }
        assertThrows(BytecodeFormatException::class.java) { BytecodeWriter.writeToBytes(tooBigConstIndex) }
    }

    @Test
    @DisplayName("Модуль всех опкодов покрывает каждый опкод, а code_size равен сумме длин инструкций")
    fun `all opcodes module covers every opcode and its code size matches instruction sizes`() {
        // Arrange
        val instructions = allOpcodesModule().functions.single().instructions
        val expectedCodeSize = instructions.sumOf { 1 + it.opcode.operandBytes }

        // Act
        val bytes = BytecodeWriter.serialize(allOpcodesModule())
        val codeSizeOffset = bytes.size - expectedCodeSize - 4
        val codeSizeField = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[codeSizeOffset + i].toInt() and 0xFF) }

        // Assert
        assertEquals(Opcode.entries.toSet(), instructions.map { it.opcode }.toSet())
        assertEquals(expectedCodeSize, codeSizeField)
    }

    companion object {
        private fun ByteArray.with(index: Int, value: Int): ByteArray = copyOf().also { it[index] = value.toByte() }

        @JvmStatic
        fun corruptions() = listOf(
            Arguments.of("неверная сигнатура", { b: ByteArray -> b.with(0, 'X'.code) }, BytecodeIssue.MAGIC),
            Arguments.of("неизвестная мажорная версия 3", { b: ByteArray -> b.with(5, 3) }, BytecodeIssue.VERSION),
            Arguments.of("неизвестная минорная версия 1.1", { b: ByteArray -> b.with(7, 1) }, BytecodeIssue.VERSION),
            Arguments.of("ненулевые flags", { b: ByteArray -> b.with(9, 1) }, BytecodeIssue.FLAGS),
            Arguments.of("entry вне таблицы функций", { b: ByteArray -> b.with(11, 1) }, BytecodeIssue.ENTRY),
            Arguments.of("неизвестный тег константы", { b: ByteArray -> b.with(14, 9) }, BytecodeIssue.CONSTANT_TAG),
            Arguments.of("некорректный UTF-8 в строке", { b: ByteArray -> b.with(19, 0xC3) }, BytecodeIssue.STRING),
            Arguments.of("NUL внутри строки", { b: ByteArray -> b.with(19, 0) }, BytecodeIssue.STRING),
            Arguments.of("лишний байт после секции кода", { b: ByteArray -> b + byteArrayOf(0) }, BytecodeIssue.LAYOUT),
            Arguments.of("неизвестный опкод", { b: ByteArray -> b.with(b.size - 1, 0x43) }, BytecodeIssue.OPCODE),
            Arguments.of("обрезанный операнд в конце функции", { b: ByteArray -> b.with(b.size - 1, 0x01) }, BytecodeIssue.OPERAND),
            Arguments.of("имя функции ссылается на Int-константу", { b: ByteArray -> b.with(38, 1) }, BytecodeIssue.FUNCTION_NAME),
            Arguments.of("param_count больше local_count", { b: ByteArray -> b.with(39, 1) }, BytecodeIssue.PARAMETERS),
            Arguments.of("смещение функции не равно 0", { b: ByteArray -> b.with(44, 1) }, BytecodeIssue.LAYOUT),
            Arguments.of("длина функции не совпадает с code_size", { b: ByteArray -> b.with(48, 7) }, BytecodeIssue.LAYOUT),
            Arguments.of(
                "code_size = 0xFFFFFFFF отклоняется без выделения памяти",
                { b: ByteArray -> b.with(49, 0xFF).with(50, 0xFF).with(51, 0xFF).with(52, 0xFF) },
                BytecodeIssue.RESOURCE_LIMIT,
            ),
            Arguments.of(
                "code_size больше оставшихся байт",
                { b: ByteArray -> b.with(51, 0x01) },
                BytecodeIssue.TRUNCATED,
            ),
            Arguments.of("RETURN заменён на RETURN_VOID при значении на стеке", { b: ByteArray -> b.with(b.size - 1, 0x16) }, BytecodeIssue.RETURN),
        )
    }
}
