package ru.itmo.applang.semantic

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.assertEquals
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
            "fun add(a: Int, b: Int): Int { return a + b; } fun main() {}",
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "fun nothing() {} fun main() { nothing(); }",
            "fun main() { print(1); println(\"text\"); println(true); }",
            "fun value(): Int { return 1; } fun main() { value(); }",
        ],
    )
    @DisplayName("Вызов функции без результата допустим как отдельный оператор")
    fun `unit call is allowed as a standalone statement`(source: String) {
        // Act
        val collector = typeCheck(source)

        // Assert
        assertFalse(collector.hasErrors, "неожиданные диагностики: ${collector.diagnostics}")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "fun nothing() {} fun main() { val x = nothing(); }",
            "fun main() { var x = println(1); }",
            "fun nothing() {} fun main() { var x: Int = 1; x = nothing(); }",
            "fun nothing() {} fun main() { println(nothing()); }",
            "fun main() { println(println(1)); }",
            "fun main() { print(print(1)); }",
            "fun nothing() {} fun main() { nothing() == nothing(); }",
            "fun nothing() {} fun main() { nothing() != nothing(); }",
            "fun nothing() {} fun takes(a: Int) {} fun main() { takes(nothing()); }",
            "fun nothing() {} fun main() { if (nothing()) {} }",
            "fun nothing() {} fun main() { while (nothing()) {} }",
            "fun nothing() {} fun main() { val x: Int = 1 + nothing(); }",
            "fun nothing() {} fun main() { val x: Bool = !nothing(); }",
            "fun nothing() {} fun main() { val x: Int = -nothing(); }",
            "fun nothing() {} fun main() { val x: Bool = true && nothing(); }",
            "fun nothing() {} fun main(): Int { return nothing(); }",
            "fun nothing() {} fun main() { val x = (nothing()); }",
            "fun nothing() {} fun main() { println((nothing())); }",
        ],
    )
    @DisplayName("Результат функции без значения нельзя использовать как значение")
    fun `unit call used as a value is rejected`(source: String) {
        // Act
        val collector = typeCheck(source)

        // Assert
        assertTrue(
            collector.diagnostics.any { it.code == SemanticErrorCodes.NO_VALUE },
            "ожидался код '${SemanticErrorCodes.NO_VALUE}', получены: ${collector.diagnostics}",
        )
    }

    @Test
    @DisplayName("Строковый литерал с настоящим символом NUL отклоняется с позицией литерала")
    fun `string literal containing a real nul character is rejected`() {
        // Arrange: в исходнике сам символ U+0000, а не его запись "\\0"
        val source = "fun main() { println(\"a\u0000b\"); }"

        // Act
        val collector = typeCheck(source)

        // Assert
        val diagnostic = collector.diagnostics.single { it.code == SemanticErrorCodes.INVALID_STRING_CONSTANT }
        assertEquals(1, diagnostic.position?.line)
    }

    @Test
    @DisplayName("Длина литерала проверяется в байтах после раскрытия escape-последовательностей")
    fun `string literal length is checked after unescaping`() {
        // Arrange: "\n" в исходнике — 2 символа, в константе — 1 байт
        val limit = ru.itmo.applang.bytecode.FormatLimits.MAX_STRING_BYTES
        val atLimit = "\\n".repeat(limit)
        val overLimit = "a".repeat(limit + 1)

        // Act
        val ok = typeCheck("fun main() { println(\"$atLimit\"); }")
        val tooLong = typeCheck("fun main() { println(\"$overLimit\"); }")

        // Assert
        assertFalse(ok.hasErrors, "неожиданные диагностики: ${ok.diagnostics}")
        assertTrue(tooLong.diagnostics.any { it.code == SemanticErrorCodes.INVALID_STRING_CONSTANT })
    }

    @Test
    @DisplayName("255 локальных слотов допустимы, 256 — превышение предела local_count")
    fun `255 local slots are allowed and 256 exceed the limit`() {
        // Arrange
        fun program(slots: Int) = "fun main() { " + (0 until slots).joinToString(" ") { "val v$it: Int = $it;" } + " }"

        // Act
        val ok = typeCheck(program(255))
        val tooMany = typeCheck(program(256))

        // Assert
        assertFalse(ok.hasErrors, "неожиданные диагностики: ${ok.diagnostics}")
        assertTrue(tooMany.diagnostics.any { it.code == SemanticErrorCodes.LIMIT_EXCEEDED })
    }

    @Test
    @DisplayName("Слоты считаются за всю функцию: переменные соседних блоков не переиспользуют слоты")
    fun `slots of sibling blocks are counted in total`() {
        // Arrange: одновременно видно не больше 128 переменных, но всего выделяется 256 слотов
        val half = (0 until 128).joinToString(" ") { "val a$it: Int = $it;" }
        val otherHalf = (0 until 128).joinToString(" ") { "val b$it: Int = $it;" }
        val source = "fun main() { { $half } { $otherHalf } }"

        // Act
        val collector = typeCheck(source)

        // Assert
        assertTrue(collector.diagnostics.any { it.code == SemanticErrorCodes.LIMIT_EXCEEDED })
    }

    @Test
    @DisplayName("255 параметров допустимы, но ещё одна локальная переменная превышает local_count")
    fun `255 parameters are allowed but one more local exceeds the limit`() {
        // Arrange
        val params = (0 until 255).joinToString(", ") { "p$it: Int" }
        val okSource = "fun wide($params) {} fun main() {}"
        val tooManySource = "fun wide($params) { val extra: Int = 1; } fun main() {}"
        val tooManyParams = "fun wide($params, last: Int) {} fun main() {}"

        // Act & Assert
        assertFalse(typeCheck(okSource).hasErrors)
        assertTrue(typeCheck(tooManySource).diagnostics.any { it.code == SemanticErrorCodes.LIMIT_EXCEEDED })
        assertTrue(typeCheck(tooManyParams).diagnostics.any { it.code == SemanticErrorCodes.LIMIT_EXCEEDED })
    }

    @ParameterizedTest(name = "программа без main: ''{0}''")
    @ValueSource(strings = ["", "fun helper(): Int { return 1; }", "fun mainly() {}"])
    @DisplayName("Программа без main получает диагностику уровня программы без позиции")
    fun `program without main reports a program-level diagnostic`(source: String) {
        // Act
        val collector = typeCheck(source)

        // Assert
        val diagnostic = collector.diagnostics.single { it.code == SemanticErrorCodes.MISSING_MAIN }
        assertEquals(null, diagnostic.position)
    }

    @Test
    @DisplayName("main с параметрами отклоняется в стандартном режиме с позицией объявления")
    fun `main with parameters is rejected`() {
        // Act
        val collector = typeCheck("fun helper() {}\nfun main(arg: Int) {}")

        // Assert
        val diagnostic = collector.diagnostics.single { it.code == SemanticErrorCodes.INVALID_MAIN_SIGNATURE }
        assertEquals(2, diagnostic.position?.line)
    }

    @Test
    @DisplayName("Повторное объявление main отклоняется как дубликат")
    fun `duplicate main is rejected as a duplicate function`() {
        // Act
        val collector = typeCheck("fun main() {} fun main() {}")

        // Assert
        assertTrue(collector.diagnostics.any { it.code == SemanticErrorCodes.DUPLICATE_FUNCTION })
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "fun main() {}",
            "fun main(): Int { return 0; }",
            "fun main(): Bool { return true; }",
            "fun main(): String { return \"done\"; }",
        ],
    )
    @DisplayName("main без параметров допустим с любым типом результата")
    fun `main without parameters is allowed with any result type`(source: String) {
        assertFalse(typeCheck(source).hasErrors)
    }

    @Test
    @DisplayName("65535 функций помещаются в таблицу, 65536 — превышение предела function_count")
    fun `function table limit is checked by count`() {
        // Arrange: AST строится напрямую — парсить 65536 функций незачем
        fun programWith(count: Int): ru.itmo.applang.ast.Program {
            val position = ru.itmo.applang.diagnostics.SourcePosition(1, 1)
            val functions = (0 until count - 1).map {
                ru.itmo.applang.ast.FunctionDecl("f$it", emptyList(), ru.itmo.applang.ast.Type.UNIT, ru.itmo.applang.ast.Block(emptyList(), position), position)
            }
            val main = ru.itmo.applang.ast.FunctionDecl("main", emptyList(), ru.itmo.applang.ast.Type.UNIT, ru.itmo.applang.ast.Block(emptyList(), position), position)
            return ru.itmo.applang.ast.Program(functions + main)
        }

        // Act
        val atLimit = DiagnosticCollector().also { TypeChecker(it).check(programWith(65_535)) }
        val overLimit = DiagnosticCollector().also { TypeChecker(it).check(programWith(65_536)) }

        // Assert
        assertFalse(atLimit.hasErrors, "${atLimit.diagnostics.take(3)}")
        assertTrue(overLimit.diagnostics.any { it.code == SemanticErrorCodes.LIMIT_EXCEEDED })
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
