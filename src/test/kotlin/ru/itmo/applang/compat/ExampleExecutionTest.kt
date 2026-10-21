package ru.itmo.applang.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import ru.itmo.applang.bytecode.BytecodeReader

/**
 * Исполняет байты каждого примера модельной машиной и сверяет вывод и результат `main` с
 * ожиданиями. Подтверждает смысл кода по контракту; фактический прогон на GVM — отдельный шаг,
 * когда ВМ появится.
 */
class ExampleExecutionTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    @DisplayName("Пример, прочитанный из байтов, исполняется модельной машиной с ожидаемым выводом и результатом")
    fun `example runs with the expected output and result`(expectation: ExampleExpectation) {
        // Arrange
        val compiled = Examples.compile(expectation.name)
        val module = BytecodeReader.readFromBytes(compiled.bytes)

        // Act
        val result = ReferenceInterpreter(module).run()

        // Assert
        assertEquals(expectation.profile, "${module.versionMajor}.${module.versionMinor}")
        assertEquals(expectation.stdout, result.stdout)
        assertEquals(expectation.returnValue, result.returnValue)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expressions")
    @DisplayName("Приоритет и порядок вычисления выражений соответствуют спецификации при исполнении")
    fun `expression evaluates according to the specification`(source: String, expectedOutput: String) {
        // Arrange
        val compiled = ru.itmo.applang.compiler.Compiler.compile(source) as ru.itmo.applang.compiler.CompilationResult.Success

        // Act
        val result = ReferenceInterpreter(BytecodeReader.readFromBytes(compiled.bytes)).run()

        // Assert
        assertEquals(expectedOutput, result.stdout)
    }

    companion object {
        @JvmStatic
        fun expressions() = listOf(
            org.junit.jupiter.params.provider.Arguments.of("fun main() { println(-1 + 2); }", "1\n"),
            org.junit.jupiter.params.provider.Arguments.of("fun main() { println(!true && false); }", "false\n"),
            org.junit.jupiter.params.provider.Arguments.of("fun main() { println(-2 * 3 + 10 % 4); }", "-4\n"),
            org.junit.jupiter.params.provider.Arguments.of("fun main() { println(2147483647 + 1); }", "-2147483648\n"),
            org.junit.jupiter.params.provider.Arguments.of("fun main() { println(-7 / 2); println(-7 % 2); }", "-3\n-1\n"),
            // short-circuit: правый операнд с побочным эффектом не вычисляется
            org.junit.jupiter.params.provider.Arguments.of(
                "fun side(): Bool { println(\"side\"); return true; } fun main() { println(false && side()); println(true || side()); }",
                "false\ntrue\n",
            ),
            // аргументы вычисляются справа налево, но попадают в параметры по порядку
            org.junit.jupiter.params.provider.Arguments.of(
                "fun t(n: Int): Int { println(n); return n; } fun sub(a: Int, b: Int): Int { return a - b; } fun main() { println(sub(t(10), t(3))); }",
                "3\n10\n7\n",
            ),
            // receiver метода вычисляется первым, затем аргументы справа налево
            org.junit.jupiter.params.provider.Arguments.of(
                "class C { fun pair(a: Int, b: Int): Int { return a * 10 + b; } } " +
                    "fun mk(): C { println(\"recv\"); return C(); } fun t(n: Int): Int { println(n); return n; } " +
                    "fun main() { println(mk().pair(t(1), t(2))); }",
                "recv\n2\n1\n12\n",
            ),
            // receiver записи поля вычисляется раньше правой части и один раз
            org.junit.jupiter.params.provider.Arguments.of(
                "class Box { var v: Int = 0; } fun mk(b: Box): Box { println(\"recv\"); return b; } fun t(n: Int): Int { println(n); return n; } " +
                    "fun main() { val b = Box(); mk(b).v = t(5); println(b.v); }",
                "recv\n5\n5\n",
            ),
            // объект, созданный в функции, переживает её кадр; ссылки — не копии
            org.junit.jupiter.params.provider.Arguments.of(
                "class Box { var v: Int = 1; } fun make(): Box { val local = Box(); local.v = 42; return local; } " +
                    "fun main() { val a = make(); val b = make(); b.v = 7; val c = a; c.v = c.v + 1; println(a.v); println(b.v); }",
                "43\n7\n",
            ),
        )


        @JvmStatic
        fun examples() = Examples.all
    }
}
