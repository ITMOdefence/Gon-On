package ru.itmo.applang.semantic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.applang.compiler.CompilationResult
import ru.itmo.applang.compiler.Compiler
import ru.itmo.applang.compat.ReferenceInterpreter
import ru.itmo.applang.compat.Value

class IntegerLiteralTest {
    @ParameterizedTest
    @MethodSource("boundaries")
    @DisplayName("Полный диапазон Int доступен через целочисленные литералы")
    fun `signed integer boundaries compile and execute`(expression: String, expected: Int) {
        // Arrange / Act
        val result = Compiler.compile("fun main(): Int { return $expression; }")

        // Assert
        assertTrue(result is CompilationResult.Success, result.diagnostics.toString())
        val execution = ReferenceInterpreter((result as CompilationResult.Success).module).run()
        assertEquals(Value.IntValue(expected), execution.returnValue)
    }

    @ParameterizedTest
    @ValueSource(strings = ["2147483648", "-2147483649", "999999999999999999999999999999", "0 - 2147483648"])
    @DisplayName("Литерал вне диапазона Int возвращает диагностику с позицией")
    fun `out of range literals return a positioned diagnostic`(expression: String) {
        // Arrange / Act
        val result = Compiler.compile("fun main(): Int { return $expression; }")

        // Assert
        assertTrue(result is CompilationResult.Failure)
        val diagnostic = result.diagnostics.single()
        assertEquals("INTEGER_LITERAL_OUT_OF_RANGE", diagnostic.code)
        assertEquals(1, diagnostic.position!!.line)
        assertTrue(diagnostic.position.column >= 25)
    }

    companion object {
        @JvmStatic fun boundaries() = listOf(
            Arguments.of("-2147483648", Int.MIN_VALUE),
            Arguments.of("-0002147483648", Int.MIN_VALUE),
            Arguments.of("2147483647", Int.MAX_VALUE),
            Arguments.of("0", 0),
            Arguments.of("-7", -7),
            Arguments.of("-(7)", -7),
            Arguments.of("1 - 2", -1),
        )
    }
}
