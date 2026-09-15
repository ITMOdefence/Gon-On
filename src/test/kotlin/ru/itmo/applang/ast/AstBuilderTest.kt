package ru.itmo.applang.ast

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.itmo.applang.parser.AppLangLexer
import ru.itmo.applang.parser.AppLangParser

private fun buildProgram(source: String): Program {
    val lexer = AppLangLexer(CharStreams.fromString(source))
    val parser = AppLangParser(CommonTokenStream(lexer))
    return AstBuilder().buildProgram(parser.program())
}

class AstBuilderTest {

    @Test
    @DisplayName("Функция без параметров и без указанного типа возврата получает тип Unit")
    fun `function without explicit return type resolves to Unit`() {
        // Arrange
        val source = "fun sideEffect() { }"

        // Act
        val program = buildProgram(source)

        // Assert
        val function = program.functions.single()
        assertEquals("sideEffect", function.name)
        assertEquals(emptyList<Param>(), function.params)
        assertEquals(Type.UNIT, function.returnType)
    }

    @Test
    @DisplayName("Параметры функции сохраняют имя, тип и порядок объявления")
    fun `function parameters preserve name, type and declaration order`() {
        // Arrange
        val source = "fun greet(name: String, times: Int): Int { return times; }"

        // Act
        val program = buildProgram(source)

        // Assert
        val params = program.functions.single().params
        assertEquals(listOf(Param("name", Type.STRING, params[0].position), Param("times", Type.INT, params[1].position)), params)
    }

    @Test
    @DisplayName("Бинарное выражение строится как BinaryOp с корректным оператором и операндами")
    fun `binary expression becomes BinaryOp node with correct operator`() {
        // Arrange
        val source = "fun main(): Int { return 1 + 2; }"

        // Act
        val program = buildProgram(source)
        val returnStmt = program.functions.single().body.statements.single() as Return
        val expr = returnStmt.value as BinaryOp

        // Assert
        assertEquals(BinaryOperator.ADD, expr.operator)
        assertEquals(IntLiteral(1, expr.left.position), expr.left)
        assertEquals(IntLiteral(2, expr.right.position), expr.right)
    }

    @Test
    @DisplayName("Вызов функции сохраняет имя и список аргументов в порядке их следования")
    fun `function call preserves callee name and argument order`() {
        // Arrange
        val source = "fun main(): Int { return add(1, 2); }"

        // Act
        val program = buildProgram(source)
        val returnStmt = program.functions.single().body.statements.single() as Return
        val call = returnStmt.value as Call

        // Assert
        assertEquals("add", call.functionName)
        assertEquals(listOf(1, 2), call.args.map { (it as IntLiteral).value })
    }

    @Test
    @DisplayName("Строковый литерал раскрывает escape-последовательности и убирает кавычки")
    fun `string literal is unescaped and unquoted`() {
        // Arrange
        val source = """fun main() { println("a\nb"); }"""

        // Act
        val program = buildProgram(source)
        val exprStmt = program.functions.single().body.statements.single() as ExprStmt
        val call = exprStmt.expr as Call
        val stringLiteral = call.args.single() as StringLiteral

        // Assert
        assertEquals("a\nb", stringLiteral.value)
    }

    @Test
    @DisplayName("До семантического анализа resolvedType всех выражений равен null")
    fun `resolvedType is null right after parsing, before semantic analysis`() {
        // Arrange
        val source = "fun main(): Int { return 1 + 2; }"

        // Act
        val program = buildProgram(source)
        val returnStmt = program.functions.single().body.statements.single() as Return

        // Assert
        assertNull(returnStmt.value?.resolvedType)
    }
}
