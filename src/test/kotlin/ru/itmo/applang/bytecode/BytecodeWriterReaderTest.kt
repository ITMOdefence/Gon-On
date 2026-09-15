package ru.itmo.applang.bytecode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private fun sampleModule(): BytecodeModule {
    val constants = listOf(
        ConstantValue.IntConst(42),
        ConstantValue.BoolConst(true),
        ConstantValue.StringConst("привет, мир"), // не-ASCII специально — проверяем UTF-8
        ConstantValue.StringConst(""), // пустая строка — граничный случай
        ConstantValue.StringConst("main"),
    )
    val mainFunction = FunctionEntry(
        name = "main",
        nameConstIndex = 4,
        paramCount = 0,
        localCount = 0,
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
            Instruction.ReturnVoid,
        ),
    )
    return BytecodeModule(
        versionMajor = BytecodeModule.CURRENT_VERSION_MAJOR,
        versionMinor = BytecodeModule.CURRENT_VERSION_MINOR,
        constants = constants,
        functions = listOf(mainFunction),
        entryFunctionIndex = 0,
    )
}

class BytecodeWriterReaderTest {

    @Test
    @DisplayName("Round-trip: записанный и затем прочитанный модуль совпадает с исходным")
    fun `module survives a write-then-read round trip unchanged`() {
        // Arrange
        val original = sampleModule()

        // Act
        val bytes = BytecodeWriter.writeToBytes(original)
        val restored = BytecodeReader.readFromBytes(bytes)

        // Assert
        assertEquals(original, restored)
    }

    @Test
    @DisplayName("Пустой модуль без функций и констант тоже корректно проходит round-trip")
    fun `empty module round-trips correctly`() {
        // Arrange: entryFunctionIndex формально 0, но functions пуст — граничный случай сериализации
        val original = BytecodeModule(
            versionMajor = BytecodeModule.CURRENT_VERSION_MAJOR,
            versionMinor = BytecodeModule.CURRENT_VERSION_MINOR,
            constants = emptyList(),
            functions = emptyList(),
            entryFunctionIndex = 0,
        )

        // Act
        val restored = BytecodeReader.readFromBytes(BytecodeWriter.writeToBytes(original))

        // Assert
        assertEquals(original, restored)
    }

    @Test
    @DisplayName("Файл с неверной сигнатурой отклоняется читателем с понятной ошибкой")
    fun `reader rejects a file with wrong magic bytes`() {
        // Arrange
        val bytes = BytecodeWriter.writeToBytes(sampleModule())
        val corrupted = bytes.copyOf()
        corrupted[0] = 'X'.code.toByte()

        // Act & Assert
        assertThrows(BytecodeReader.MalformedBytecodeException::class.java) {
            BytecodeReader.readFromBytes(corrupted)
        }
    }

    @Test
    @DisplayName("Файл с неподдерживаемой мажорной версией отклоняется читателем")
    fun `reader rejects a file with an unsupported major version`() {
        // Arrange
        val futureModule = sampleModule().copy(versionMajor = BytecodeModule.CURRENT_VERSION_MAJOR + 1)
        val bytes = BytecodeWriter.writeToBytes(futureModule)

        // Act & Assert
        assertThrows(BytecodeReader.MalformedBytecodeException::class.java) {
            BytecodeReader.readFromBytes(bytes)
        }
    }
}
