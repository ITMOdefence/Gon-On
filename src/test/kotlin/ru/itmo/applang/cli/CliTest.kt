package ru.itmo.applang.cli

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.applang.bytecode.BytecodeReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Результат запуска CLI: код завершения и захваченные stdout / stderr. */
private class CliRun(val exitCode: Int, val out: String, val err: String)

private fun runCli(vararg args: String): CliRun {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exitCode = run(arrayOf(*args), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
    return CliRun(exitCode, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
}

class CliTest {

    @Test
    @DisplayName("Успешная компиляция пишет проверенный файл и только после этого сообщает об успехе")
    fun `successful compilation writes a verified file`(@TempDir directory: Path) {
        // Arrange
        val source = directory.resolve("ok.al").also { Files.writeString(it, "fun main(): Int { return 0; }") }
        val output = directory.resolve("ok.albc")

        // Act
        val result = runCli(source.toString(), "-o", output.toString())

        // Assert
        assertEquals(ExitCodes.OK, result.exitCode, result.err)
        assertTrue(result.out.contains("Скомпилировано"))
        assertEquals("main", BytecodeReader.readFromFile(output).functions.single().name)
    }

    @Test
    @DisplayName("Ошибка исходника не создаёт выходной файл и не печатает сообщение об успехе")
    fun `source error does not create the output file`(@TempDir directory: Path) {
        // Arrange
        val source = directory.resolve("bad.al").also {
            Files.writeString(it, "fun nothing() {} fun main() { val x = nothing(); }")
        }
        val output = directory.resolve("bad.albc")

        // Act
        val result = runCli(source.toString(), "-o", output.toString())

        // Assert
        assertEquals(ExitCodes.COMPILATION_FAILED, result.exitCode)
        assertFalse(Files.exists(output))
        assertTrue(result.err.contains("NO_VALUE"), result.err)
        assertFalse(result.out.contains("Скомпилировано"))
        assertFalse(result.err.contains("Exception"), "вместо диагностики напечатан stack trace: ${result.err}")
    }

    @Test
    @DisplayName("Ошибка не перезаписывает уже существующий выходной файл")
    fun `error leaves an existing output file unchanged`(@TempDir directory: Path) {
        // Arrange
        val source = directory.resolve("bad.al").also { Files.writeString(it, "fun main(x: Int) {}") }
        val output = directory.resolve("previous.albc").also { Files.write(it, byteArrayOf(7, 7, 7)) }

        // Act
        val result = runCli(source.toString(), "-o", output.toString())

        // Assert
        assertEquals(ExitCodes.COMPILATION_FAILED, result.exitCode)
        assertTrue(result.err.contains("INVALID_MAIN_SIGNATURE"), result.err)
        assertArrayEquals(byteArrayOf(7, 7, 7), Files.readAllBytes(output))
    }

    @Test
    @DisplayName("Отсутствие main сообщается диагностикой уровня программы без позиции")
    fun `missing main is reported without a source position`(@TempDir directory: Path) {
        // Arrange
        val source = directory.resolve("lib.al").also { Files.writeString(it, "fun helper() {}") }

        // Act
        val result = runCli(source.toString(), "-o", directory.resolve("lib.albc").toString())

        // Assert
        assertEquals(ExitCodes.COMPILATION_FAILED, result.exitCode)
        assertTrue(result.err.contains("error: в программе нет функции 'main'"), result.err)
        assertTrue(result.err.contains("[MISSING_MAIN]"), result.err)
    }

    @Test
    @DisplayName("Исходник не в UTF-8 отклоняется понятным сообщением")
    fun `source that is not utf8 is rejected with a clear message`(@TempDir directory: Path) {
        // Arrange
        val source = directory.resolve("latin1.al").also { Files.write(it, byteArrayOf(0x66, 0xFF.toByte(), 0x0A)) }

        // Act
        val result = runCli(source.toString())

        // Assert
        assertEquals(ExitCodes.COMPILATION_FAILED, result.exitCode)
        assertTrue(result.err.contains("UTF-8"), result.err)
    }

    @Test
    @DisplayName("Без аргументов CLI печатает справку и завершается с кодом использования")
    fun `missing arguments print usage`() {
        // Act
        val result = runCli()

        // Assert
        assertEquals(ExitCodes.USAGE, result.exitCode)
        assertTrue(result.err.contains("Использование"))
    }
}
