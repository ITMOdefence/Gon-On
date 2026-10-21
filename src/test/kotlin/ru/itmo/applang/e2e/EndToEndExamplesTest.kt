package ru.itmo.applang.e2e

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.applang.bytecode.BytecodeReader
import ru.itmo.applang.bytecode.Disassembler
import ru.itmo.applang.cli.ExitCodes
import ru.itmo.applang.cli.run
import ru.itmo.applang.compiler.CompilationResult
import ru.itmo.applang.compiler.Compiler
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Компилирует каждый пример из examples/ через CLI в реальный файл, читает этот файл строгим
 * reader'ом и сравнивает дизассемблированный вывод с зафиксированным golden-файлом
 * examples/<name>.albc.asm. Любое изменение opcode set, формата constant pool или стратегии
 * codegen, которое меняет получившийся байткод, должно либо остаться совместимым с golden-файлом,
 * либо явно его обновить в этом же коммите — так документация (docs/BYTECODE_SPEC.md, примеры)
 * не расходится с реальным поведением компилятора.
 */
class EndToEndExamplesTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["hello_world", "factorial_while", "gcd_demo"])
    @DisplayName("Записанный файл примера читается строгим reader'ом и совпадает с golden-дампом")
    fun `compiled example file matches its golden disassembly`(exampleName: String, @TempDir directory: Path) {
        // Arrange
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val sourceFile = projectRoot.resolve("examples/$exampleName.al")
        val goldenFile = projectRoot.resolve("examples/$exampleName.albc.asm")
        val outputFile = directory.resolve("$exampleName.albc")
        val expectedDisassembly = Files.readString(goldenFile)

        // Act
        val exitCode = run(
            arrayOf(sourceFile.toString(), "-o", outputFile.toString()),
            PrintStream(ByteArrayOutputStream()),
            PrintStream(ByteArrayOutputStream()),
        )
        val moduleFromFile = BytecodeReader.readFromFile(outputFile)

        // Assert
        assertEquals(ExitCodes.OK, exitCode, "пример должен компилироваться без ошибок")
        assertEquals(expectedDisassembly, Disassembler.disassemble(moduleFromFile))
        val inMemory = Compiler.compile(Files.readString(sourceFile)) as CompilationResult.Success
        assertEquals(inMemory.module, moduleFromFile, "файл должен читаться ровно в модель, построенную компилятором")
        assertTrue(inMemory.bytes.contentEquals(Files.readAllBytes(outputFile)))
    }
}
