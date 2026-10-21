package ru.itmo.applang.bytecode

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import ru.itmo.applang.bytecode.Instruction.AllocObject
import ru.itmo.applang.bytecode.Instruction.Call
import ru.itmo.applang.bytecode.Instruction.GetField
import ru.itmo.applang.bytecode.Instruction.LoadConst
import ru.itmo.applang.bytecode.Instruction.LoadLocal
import ru.itmo.applang.bytecode.Instruction.Return
import ru.itmo.applang.bytecode.Instruction.ReturnVoid
import ru.itmo.applang.bytecode.Instruction.SetField
import ru.itmo.applang.bytecode.Instruction.StoreLocal

/**
 * Эталон из docs/backlog/bytecode-contract.md §11: Counter с полем value = 7, метод get и main,
 * возвращающий 7. Модуль собран вручную по таблицам контракта, без компилятора.
 */
internal fun counterReferenceModule(): BytecodeModule = BytecodeModule(
    versionMajor = 2,
    versionMinor = 0,
    constants = listOf(
        ConstantValue.StringConst("Counter"),
        ConstantValue.StringConst("value"),
        ConstantValue.StringConst("Counter.init"),
        ConstantValue.StringConst("Counter.get"),
        ConstantValue.StringConst("main"),
        ConstantValue.IntConst(7),
    ),
    functions = listOf(
        FunctionEntry(
            "Counter.init", 2, paramCount = 2, localCount = 2,
            instructions = listOf(LoadLocal(0), LoadLocal(1), SetField(0, 0), ReturnVoid),
        ),
        FunctionEntry(
            "Counter.get", 3, paramCount = 1, localCount = 1,
            instructions = listOf(LoadLocal(0), GetField(0, 0), Return),
        ),
        FunctionEntry(
            "main", 4, paramCount = 0, localCount = 2,
            instructions = listOf(
                LoadConst(5), AllocObject(0), StoreLocal(1), LoadLocal(1), Call(0), LoadLocal(1),
                StoreLocal(0), LoadLocal(0), StoreLocal(1), LoadLocal(1), Call(1), Return,
            ),
        ),
    ),
    entryFunctionIndex = 2,
    classes = listOf(
        ClassEntry("Counter", 0, listOf(FieldEntry("value", 1, FieldType.IntType)), methodFunctionIndices = listOf(1)),
    ),
)

/** Байты эталона, выписанные из таблиц контракта (смещения в комментариях — абсолютные). */
internal val COUNTER_REFERENCE_BYTES: ByteArray = hexBytes(
    // 0: Header — ALBC, 2.0, flags 0, entry 2
    "41 4C 42 43 00 02 00 00 00 00 00 02" +
        // 12: ConstantPool, 6 записей, 71 байт
        " 00 06" +
        " 03 00 00 00 07 43 6F 75 6E 74 65 72" +
        " 03 00 00 00 05 76 61 6C 75 65" +
        " 03 00 00 00 0C 43 6F 75 6E 74 65 72 2E 69 6E 69 74" +
        " 03 00 00 00 0B 43 6F 75 6E 74 65 72 2E 67 65 74" +
        " 03 00 00 00 04 6D 61 69 6E" +
        " 01 00 00 00 07" +
        // 83: FunctionTable, 3 записи по 12 байт
        " 00 03" +
        " 00 02 02 02 00 00 00 00 00 00 00 0A" +
        " 00 03 01 01 00 00 00 0A 00 00 00 08" +
        " 00 04 00 02 00 00 00 12 00 00 00 1B" +
        // 121: ClassTable
        " 00 01 00 00 00 01 00 01 01 FF FF 00 01 00 01" +
        // 136: code_size = 45
        " 00 00 00 2D" +
        // 140: Counter.init
        " 02 00 02 01 42 00 00 00 00 16" +
        // 150: Counter.get
        " 02 00 41 00 00 00 00 15" +
        // 158: main
        " 01 00 05 40 00 00 03 01 02 01 14 00 00 02 01 03 00 02 00 03 01 02 01 14 00 01 15",
)

internal fun hexBytes(text: String): ByteArray =
    text.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

private fun withClasses(classes: List<ClassEntry>) = counterReferenceModule().copy(classes = classes)

private fun withMain(vararg code: Instruction) = counterReferenceModule().let { module ->
    module.copy(functions = module.functions.dropLast(1) + module.functions.last().copy(instructions = code.toList()))
}

private fun ByteArray.with(index: Int, value: Int): ByteArray = copyOf().also { it[index] = value.toByte() }

class ObjectProfileTest {

    @Test
    @DisplayName("Эталон контракта 2.0 сериализуется ровно в 185 байт из bytecode-contract.md")
    fun `reference module serializes to the exact contract bytes`() {
        // Act
        val bytes = BytecodeWriter.writeToBytes(counterReferenceModule())

        // Assert
        assertEquals(185, COUNTER_REFERENCE_BYTES.size, "эталон выписан с ошибкой")
        assertArrayEquals(COUNTER_REFERENCE_BYTES, bytes)
    }

    @Test
    @DisplayName("Строгий reader читает байты эталона обратно в тот же модуль")
    fun `strict reader reads the reference bytes back`() {
        assertEquals(counterReferenceModule(), BytecodeReader.readFromBytes(COUNTER_REFERENCE_BYTES))
    }

    @Test
    @DisplayName("Профиль 2.0 без классов допустим: ClassTable из двух нулевых байт")
    fun `object profile with an empty class table is valid`() {
        // Arrange
        val module = BytecodeModule(
            2, 0,
            listOf(ConstantValue.StringConst("main")),
            listOf(FunctionEntry("main", 0, 0, 0, listOf(ReturnVoid))),
            entryFunctionIndex = 0,
        )

        // Act
        val bytes = BytecodeWriter.writeToBytes(module)

        // Assert: ... function table, затем class_count = 00 00, затем code_size
        assertArrayEquals(hexBytes("00 00 00 00 00 01 16"), bytes.copyOfRange(bytes.size - 7, bytes.size))
    }

    @Test
    @DisplayName("Дизассемблер показывает классы, поля, методы и объектные инструкции")
    fun `disassembler shows the class table and object instructions`() {
        // Act
        val text = Disassembler.disassemble(counterReferenceModule())

        // Assert
        assertTrue(text.contains("; version 2.0"))
        assertTrue(text.contains(".class Counter  ; class 0\n  field 0: value Int\n  method 1: Counter.get"), text)
        assertTrue(text.contains("ALLOC_OBJECT 0  ; Counter"), text)
        assertTrue(text.contains("SET_FIELD 0 0  ; Counter.value"), text)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidModules")
    @DisplayName("Нарушение правил профиля 2.0 отклоняется верификатором с ожидаемой причиной")
    fun `invalid object module is rejected`(description: String, module: BytecodeModule, expected: BytecodeIssue) {
        // Act
        val error = assertThrows(BytecodeFormatException::class.java) { BytecodeVerifier.verify(module) }

        // Assert
        assertEquals(expected, error.issue, error.message)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corruptedBytes")
    @DisplayName("Испорченная ClassTable или объектный опкод в 1.0 отклоняются строгим reader'ом")
    fun `corrupted object bytes are rejected by the reader`(description: String, bytes: ByteArray, expected: BytecodeIssue) {
        // Act
        val error = assertThrows(BytecodeFormatException::class.java) { BytecodeReader.readFromBytes(bytes) }

        // Assert
        assertEquals(expected, error.issue, error.message)
    }

    @Test
    @DisplayName("Обрезанный в любом месте файл 2.0 даёт ошибку формата, а не исключение JVM")
    fun `object file truncated anywhere is rejected with a format error`() {
        for (length in 0 until COUNTER_REFERENCE_BYTES.size) {
            assertThrows(BytecodeFormatException::class.java) {
                BytecodeReader.readFromBytes(COUNTER_REFERENCE_BYTES.copyOf(length))
            }
        }
        assertDoesNotThrow { BytecodeReader.readFromBytes(COUNTER_REFERENCE_BYTES) }
    }

    companion object {
        private val counter = counterReferenceModule().classes.single()

        @JvmStatic
        fun invalidModules() = listOf(
            Arguments.of(
                "объектная инструкция в профиле 1.0",
                counterReferenceModule().copy(versionMajor = 1, classes = emptyList()),
                BytecodeIssue.OPCODE,
            ),
            Arguments.of(
                "классы в профиле 1.0",
                counterReferenceModule().copy(versionMajor = 1),
                BytecodeIssue.LAYOUT,
            ),
            Arguments.of("ALLOC_OBJECT несуществующего класса", withMain(AllocObject(1), Return), BytecodeIssue.CLASS_INDEX),
            Arguments.of(
                "GET_FIELD несуществующего поля",
                withMain(AllocObject(0), GetField(0, 1), Return),
                BytecodeIssue.FIELD_INDEX,
            ),
            Arguments.of("GET_FIELD без receiver", withMain(GetField(0, 0), Return), BytecodeIssue.STACK_UNDERFLOW),
            Arguments.of(
                "SET_FIELD с одним значением на стеке",
                withMain(AllocObject(0), SetField(0, 0), LoadConst(5), Return),
                BytecodeIssue.STACK_UNDERFLOW,
            ),
            Arguments.of("повторное имя класса", withClasses(listOf(counter, counter.copy(methodFunctionIndices = emptyList()))), BytecodeIssue.CLASS_NAME),
            Arguments.of(
                "имя класса не идентификатор",
                counterReferenceModule().let { m ->
                    m.copy(classes = listOf(counter.copy(name = "Counter.get", nameConstIndex = 3)))
                },
                BytecodeIssue.CLASS_NAME,
            ),
            Arguments.of(
                "повторное имя поля",
                withClasses(listOf(counter.copy(fields = counter.fields + counter.fields))),
                BytecodeIssue.FIELD_NAME,
            ),
            Arguments.of(
                "тип поля — несуществующий класс",
                withClasses(listOf(counter.copy(fields = listOf(FieldEntry("value", 1, FieldType.ObjectType(4)))))),
                BytecodeIssue.FIELD_TYPE,
            ),
            Arguments.of(
                "метод без this (param_count = 0)",
                withClasses(listOf(counter.copy(methodFunctionIndices = listOf(2)))),
                BytecodeIssue.METHOD_INDEX,
            ),
            Arguments.of(
                "одна функция — метод двух классов",
                withClasses(
                    listOf(counter, ClassEntry("main", 4, emptyList(), listOf(1))),
                ),
                BytecodeIssue.METHOD_INDEX,
            ),
            Arguments.of("метод вне таблицы функций", withClasses(listOf(counter.copy(methodFunctionIndices = listOf(9)))), BytecodeIssue.METHOD_INDEX),
            Arguments.of(
                "точка входа 2.0 с параметрами",
                counterReferenceModule().copy(entryFunctionIndex = 1),
                BytecodeIssue.ENTRY,
            ),
        )

        @JvmStatic
        fun corruptedBytes() = listOf(
            Arguments.of("неизвестный type_tag поля", COUNTER_REFERENCE_BYTES.with(129, 5), BytecodeIssue.FIELD_TYPE),
            Arguments.of("type_tag 0", COUNTER_REFERENCE_BYTES.with(129, 0), BytecodeIssue.FIELD_TYPE),
            Arguments.of("Int-поле с индексом класса вместо 0xFFFF", COUNTER_REFERENCE_BYTES.with(130, 0).with(131, 0), BytecodeIssue.FIELD_TYPE),
            Arguments.of("поле-объект с заглушкой 0xFFFF", COUNTER_REFERENCE_BYTES.with(129, 4), BytecodeIssue.FIELD_TYPE),
            Arguments.of("имя класса — Int-константа", COUNTER_REFERENCE_BYTES.with(124, 5), BytecodeIssue.CLASS_NAME),
            Arguments.of("имя поля — Int-константа", COUNTER_REFERENCE_BYTES.with(128, 5), BytecodeIssue.FIELD_NAME),
            Arguments.of("лишний байт после кода", COUNTER_REFERENCE_BYTES + byteArrayOf(0), BytecodeIssue.LAYOUT),
            Arguments.of("неназначенный опкод 0x43", COUNTER_REFERENCE_BYTES.with(140 + 4, 0x43), BytecodeIssue.OPCODE),
            Arguments.of(
                "объектный опкод в файле 1.0",
                hexBytes(
                    "41 4C 42 43 00 01 00 00 00 00 00 00  00 01 03 00 00 00 04 6D 61 69 6E" +
                        "  00 01 00 00 00 01 00 00 00 00 00 00 00 04  00 00 00 04 40 00 00 15",
                ),
                BytecodeIssue.OPCODE,
            ),
        )
    }
}
