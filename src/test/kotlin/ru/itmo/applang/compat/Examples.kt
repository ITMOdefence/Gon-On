package ru.itmo.applang.compat

import ru.itmo.applang.compiler.CompilationResult
import ru.itmo.applang.compiler.Compiler
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ожидаемое поведение примера при запуске стандартным раннером: вывод PRINT/PRINTLN и значение,
 * возвращённое `main` (null — `main` без результата). Значения выписаны по смыслу исходников, а не
 * получены компилятором, — это независимое ожидание и для тестов, и для набора совместимости GVM.
 */
data class ExampleExpectation(val name: String, val profile: String, val stdout: String, val returnValue: Value?)

object Examples {
    val all = listOf(
        ExampleExpectation("hello_world", "1.0", "Hello, world!\n", Value.IntValue(0)),
        ExampleExpectation("factorial_while", "1.0", "120\n", Value.IntValue(0)),
        ExampleExpectation("gcd_demo", "1.0", "12\n1\n", Value.IntValue(0)),
        ExampleExpectation("counter", "2.0", "", Value.IntValue(7)),
        ExampleExpectation("objects", "2.0", "25\n6\n100\n35\n3\nAnn\n", null),
    )

    fun projectRoot(): Path = Path.of(System.getProperty("user.dir"))

    fun source(name: String): String = Files.readString(projectRoot().resolve("examples/$name.al"))

    fun compile(name: String): CompilationResult.Success {
        val result = Compiler.compile(source(name), "examples/$name.al")
        check(result is CompilationResult.Success) { "пример $name не компилируется: ${result.diagnostics}" }
        return result
    }
}

/** Байты в виде строк по 16 байт в шестнадцатеричной записи — формат golden-файлов `*.albc.hex`. */
fun ByteArray.toHexDump(): String =
    toList().chunked(16).joinToString("\n", postfix = "\n") { line ->
        line.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

fun parseHexDump(text: String): ByteArray =
    text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
