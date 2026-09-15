package ru.itmo.applang.semantic

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.applang.ast.AstBuilder
import ru.itmo.applang.ast.Program
import ru.itmo.applang.diagnostics.DiagnosticCollector
import ru.itmo.applang.parser.AppLangLexer
import ru.itmo.applang.parser.AppLangParser

private fun buildProgram(source: String): Program {
    val lexer = AppLangLexer(CharStreams.fromString(source))
    val parser = AppLangParser(CommonTokenStream(lexer))
    return AstBuilder().buildProgram(parser.program())
}

private fun typeCheck(source: String): DiagnosticCollector {
    val collector = DiagnosticCollector()
    TypeChecker(collector).check(buildProgram(source))
    return collector
}

class TypeCheckerTest {

    @ParameterizedTest(name = "валидная программа проходит проверку типов: {0}")
    @ValueSource(
        strings = [
            "fun main(): Int { return 1 + 2; }",
            "fun main(): Int { var x: Int = 1; x = x + 1; return x; }",
            "fun main() { val greeting: String = \"hi\" + \"!\"; println(greeting); }",
            "fun add(a: Int, b: Int): Int { return a + b; }",
            "fun main(): Int { if (1 < 2 && true) { return 1; } else { return 0; } }",
            "fun main(): Int { if (true) { return 1; } else { return 0; } }", // return во всех ветках if/else — MISSING_RETURN не должен сработать
            "fun main(): Int { var i: Int = 0; while (i < 10) { i = i + 1; } return i; }",
            "fun helper(): Int { return 1; } fun main(): Int { return helper() + helper(); }",
        ],
    )
    @DisplayName("Семантически корректная программа не порождает диагностик")
    fun `well-typed program has no diagnostics`(source: String) {
        // Act
        val collector = typeCheck(source)

        // Assert
        assertFalse(collector.hasErrors, "неожиданные диагностики: ${collector.diagnostics}")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("semanticErrorCases")
    @DisplayName("Семантически некорректная программа порождает диагностику ожидаемого вида")
    fun `ill-typed program reports the expected diagnostic code`(
        description: String,
        source: String,
        expectedCode: String,
    ) {
        // Act
        val collector = typeCheck(source)

        // Assert
        assertTrue(
            collector.diagnostics.any { it.code == expectedCode },
            "ожидался код '$expectedCode', получены: ${collector.diagnostics}",
        )
    }

    companion object {
        @JvmStatic
        fun semanticErrorCases() = listOf(
            Arguments.of(
                "использование необъявленной переменной",
                "fun main(): Int { return x; }",
                SemanticErrorCodes.UNDEFINED_VARIABLE,
            ),
            Arguments.of(
                "вызов необъявленной функции",
                "fun main(): Int { return unknownFunc(); }",
                SemanticErrorCodes.UNDEFINED_FUNCTION,
            ),
            Arguments.of(
                "две функции с одинаковым именем",
                "fun f(): Int { return 1; } fun f(): Int { return 2; }",
                SemanticErrorCodes.DUPLICATE_FUNCTION,
            ),
            Arguments.of(
                "повторное объявление переменной в той же функции (shadowing запрещён)",
                "fun main(): Int { var x: Int = 1; var x: Int = 2; return x; }",
                SemanticErrorCodes.DUPLICATE_VARIABLE,
            ),
            Arguments.of(
                "присваивание val-переменной",
                "fun main(): Int { val x: Int = 1; x = 2; return x; }",
                SemanticErrorCodes.VAL_REASSIGNMENT,
            ),
            Arguments.of(
                "несовпадение типа при объявлении переменной",
                "fun main(): Int { var x: Int = \"not an int\"; return x; }",
                SemanticErrorCodes.TYPE_MISMATCH,
            ),
            Arguments.of(
                "вызов функции с неверным числом аргументов",
                "fun add(a: Int, b: Int): Int { return a + b; } fun main(): Int { return add(1); }",
                SemanticErrorCodes.ARITY_MISMATCH,
            ),
            Arguments.of(
                "аргумент функции неверного типа",
                "fun takesInt(a: Int): Int { return a; } fun main(): Int { return takesInt(\"str\"); }",
                SemanticErrorCodes.ARGUMENT_TYPE_MISMATCH,
            ),
            Arguments.of(
                "операция сложения между Int и Bool",
                "fun main(): Int { return 1 + true; }",
                SemanticErrorCodes.INVALID_OPERAND_TYPE,
            ),
            Arguments.of(
                "функция с ненулевым типом возврата не заканчивается return",
                "fun main(): Int { var x: Int = 1; }",
                SemanticErrorCodes.MISSING_RETURN,
            ),
            Arguments.of(
                "функция типа Unit возвращает значение",
                "fun main() { return 1; }",
                SemanticErrorCodes.UNEXPECTED_RETURN_VALUE,
            ),
        )
    }

    @Test
    @DisplayName("После успешной проверки каждое выражение получает не-null resolvedType")
    fun `every expression gets a resolved type after successful type checking`() {
        // Arrange
        val source = "fun main(): Int { var x: Int = 1 + 2; return x; }"
        val program = buildProgram(source)

        // Act
        val collector = DiagnosticCollector()
        TypeChecker(collector).check(program)

        // Assert
        assertFalse(collector.hasErrors)
        val varDecl = program.functions.single().body.statements[0] as ru.itmo.applang.ast.VarDecl
        assertTrue(varDecl.initializer.resolvedType == ru.itmo.applang.ast.Type.INT)
    }
}
