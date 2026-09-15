package ru.itmo.applang.parser

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.applang.diagnostics.DiagnosticCollector
import ru.itmo.applang.diagnostics.SyntaxErrorListener

private fun parse(source: String): Pair<AppLangParser.ProgramContext, DiagnosticCollector> {
    val collector = DiagnosticCollector()
    val listener = SyntaxErrorListener(collector)

    val lexer = AppLangLexer(CharStreams.fromString(source))
    lexer.removeErrorListeners()
    lexer.addErrorListener(listener)

    val parser = AppLangParser(CommonTokenStream(lexer))
    parser.removeErrorListeners()
    parser.addErrorListener(listener)

    return parser.program() to collector
}

class ParserTest {

    @ParameterizedTest(name = "программа синтаксически валидна: {0}")
    @ValueSource(
        strings = [
            "fun main(): Int { return 0; }",
            "fun main() { var x = 1; }",
            "fun add(a: Int, b: Int): Int { return a + b; }",
            "fun main(): Int { if (true) { return 1; } else { return 2; } }",
            "fun main(): Int { while (1 < 2) { return 1; } return 0; }",
        ],
    )
    @DisplayName("Синтаксически корректные программы парсятся без ошибок")
    fun `well-formed program produces no syntax diagnostics`(source: String) {
        // Act
        val (_, collector) = parse(source)

        // Assert
        assertFalse(collector.hasErrors, "неожиданные диагностики: ${collector.diagnostics}")
    }

    @ParameterizedTest(name = "программа синтаксически некорректна: {0}")
    @ValueSource(
        strings = [
            "fun main(): Int { return 0 }", // нет ';'
            "fun main( { return 0; }", // незакрытая скобка параметров
            "fun main(): Int return 0; }", // нет '{'
            "var x = 1;", // объявление переменной вне функции не предусмотрено грамматикой
        ],
    )
    @DisplayName("Синтаксически некорректные программы порождают хотя бы одну диагностику")
    fun `malformed program produces at least one syntax diagnostic`(source: String) {
        // Act
        val (_, collector) = parse(source)

        // Assert
        assertTrue(collector.hasErrors, "ожидалась синтаксическая ошибка, но диагностик нет")
    }

    @Test
    @DisplayName("Дерево разбора содержит ровно столько functionDecl, сколько функций в исходнике")
    fun `parse tree contains one functionDecl per declared function`() {
        // Arrange
        val source = """
            fun a(): Int { return 1; }
            fun b(): Int { return 2; }
            fun c(): Int { return 3; }
        """.trimIndent()

        // Act
        val (programCtx, collector) = parse(source)

        // Assert
        assertFalse(collector.hasErrors)
        assertEquals(3, programCtx.functionDecl().size)
    }

    @Test
    @DisplayName("Приоритет операторов: '*' связывает сильнее '+' даже без скобок")
    fun `multiplication binds tighter than addition without parentheses`() {
        // Arrange: 1 + 2 * 3 должно разобраться как AddSub(1, MulDivMod(2, 3)), а не наоборот
        val source = "fun main(): Int { return 1 + 2 * 3; }"

        // Act
        val (programCtx, collector) = parse(source)
        val returnExpr = programCtx.functionDecl(0).block().statement(0).returnStmt().expr()

        // Assert
        assertFalse(collector.hasErrors)
        assertTrue(returnExpr is AppLangParser.AddSubContext, "верхний узел ожидания — AddSub ('+')")
        val addSub = returnExpr as AppLangParser.AddSubContext
        assertTrue(
            addSub.right is AppLangParser.MulDivModContext,
            "правый операнд '+' должен быть MulDivMod ('2 * 3'), т.к. '*' приоритетнее",
        )
    }
}
