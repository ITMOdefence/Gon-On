package ru.itmo.applang.bytecode

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import ru.itmo.applang.bytecode.Instruction.Add
import ru.itmo.applang.bytecode.Instruction.Call
import ru.itmo.applang.bytecode.Instruction.Jump
import ru.itmo.applang.bytecode.Instruction.JumpIfFalse
import ru.itmo.applang.bytecode.Instruction.LoadConst
import ru.itmo.applang.bytecode.Instruction.LoadLocal
import ru.itmo.applang.bytecode.Instruction.Pop
import ru.itmo.applang.bytecode.Instruction.Println
import ru.itmo.applang.bytecode.Instruction.Return
import ru.itmo.applang.bytecode.Instruction.ReturnVoid
import ru.itmo.applang.bytecode.Instruction.StoreLocal

/** Описание функции тестового модуля: (параметры, слоты, инструкции). */
private data class Fn(val params: Int, val locals: Int, val code: List<Instruction>)

/**
 * Модуль из функций f0, f1, ...; entry — f0. Константы: 0 = Int 1, 1 = Bool true, далее имена функций.
 */
private fun moduleOf(vararg functions: Fn): BytecodeModule {
    val constants = listOf(ConstantValue.IntConst(1), ConstantValue.BoolConst(true)) +
        functions.indices.map { ConstantValue.StringConst("f$it") }
    return BytecodeModule(
        versionMajor = 1,
        versionMinor = 0,
        constants = constants,
        functions = functions.mapIndexed { index, fn ->
            FunctionEntry("f$index", index + 2, fn.params, fn.locals, fn.code)
        },
        entryFunctionIndex = 0,
    )
}

private fun main(vararg code: Instruction) = moduleOf(Fn(0, 1, code.toList()))

private val ONE = LoadConst(0)
private val TRUE = LoadConst(1)

class BytecodeVerifierTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("validModules")
    @DisplayName("Корректный байткод проходит проверку")
    fun `valid bytecode passes verification`(description: String, module: BytecodeModule) {
        assertDoesNotThrow { BytecodeVerifier.verify(module) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidModules")
    @DisplayName("Нарушение правил верификатора GVM обнаруживается с ожидаемой причиной")
    fun `invalid bytecode is rejected with the expected issue`(
        description: String,
        module: BytecodeModule,
        expectedIssue: BytecodeIssue,
    ) {
        // Act
        val error = assertThrows(BytecodeFormatException::class.java) { BytecodeVerifier.verify(module) }

        // Assert
        assertEquals(expectedIssue, error.issue, error.message)
    }

    @Test
    @DisplayName("Сообщение об ошибке содержит функцию и смещение инструкции")
    fun `error message names the function and instruction offset`() {
        // Arrange
        val module = moduleOf(Fn(0, 0, listOf(ReturnVoid)), Fn(0, 0, listOf(ONE, Add, Return)))

        // Act
        val error = assertThrows(BytecodeFormatException::class.java) { BytecodeVerifier.verify(module) }

        // Assert
        assertTrue(error.message!!.contains("#1 'f1'") && error.message!!.contains("+3"), error.message)
    }

    @Test
    @DisplayName("Режим возврата выводится из инструкций: VALUE, VOID или NONE")
    fun `return mode is derived from return instructions`() {
        // Arrange
        val module = moduleOf(Fn(0, 0, listOf(ONE, Return)), Fn(0, 0, listOf(ReturnVoid)), Fn(0, 0, listOf(Jump(0))))

        // Act
        val modes = BytecodeVerifier.returnModes(module)

        // Assert
        assertEquals(
            listOf(BytecodeVerifier.ReturnMode.VALUE, BytecodeVerifier.ReturnMode.VOID, BytecodeVerifier.ReturnMode.NONE),
            modes,
        )
    }

    companion object {
        @JvmStatic
        fun validModules() = listOf(
            Arguments.of("минимальная функция RETURN_VOID", main(ReturnVoid)),
            Arguments.of("значение на RETURN", main(ONE, Return)),
            Arguments.of(
                "if/else со слиянием одинаковых высот",
                // 0 TRUE, 3 JIF 16, 8 ONE, 11 JUMP 19, 16 ONE, 19 RETURN
                main(TRUE, JumpIfFalse(16), ONE, Jump(19), ONE, Return),
            ),
            Arguments.of(
                "сбалансированный цикл",
                // 0 TRUE, 3 JIF 13, 8 JUMP 0, 13 RETURN_VOID
                main(TRUE, JumpIfFalse(13), Jump(0), ReturnVoid),
            ),
            Arguments.of("бесконечный цикл без возврата (режим NONE)", main(Jump(0))),
            Arguments.of(
                "прямой вызов функции с аргументом и результатом",
                moduleOf(Fn(0, 0, listOf(ONE, Call(1), Return)), Fn(1, 1, listOf(LoadLocal(0), Return))),
            ),
            Arguments.of(
                "рекурсия",
                moduleOf(Fn(0, 0, listOf(ONE, Call(1), Return)), Fn(1, 1, listOf(LoadLocal(0), Call(1), Return))),
            ),
            Arguments.of(
                "взаимная рекурсия",
                moduleOf(
                    Fn(0, 0, listOf(Call(1), ReturnVoid)),
                    Fn(0, 0, listOf(Call(2), ReturnVoid)),
                    Fn(0, 0, listOf(Call(1), ReturnVoid)),
                ),
            ),
            Arguments.of(
                "после вызова функции NONE нет продолжения",
                moduleOf(Fn(0, 0, listOf(Call(1))), Fn(0, 0, listOf(Jump(0)))),
            ),
            Arguments.of(
                "снятие ненужного результата через POP",
                moduleOf(Fn(0, 0, listOf(Call(1), Pop, ReturnVoid)), Fn(0, 0, listOf(ONE, Return))),
            ),
            Arguments.of(
                "недостижимый код со структурно корректными операндами",
                main(ReturnVoid, Add, Add, ReturnVoid),
            ),
        )

        @JvmStatic
        fun invalidModules() = listOf(
            Arguments.of("неподдерживаемая версия", main(ReturnVoid).copy(versionMajor = 3), BytecodeIssue.VERSION),
            Arguments.of("пустая таблица функций", main(ReturnVoid).copy(functions = emptyList()), BytecodeIssue.LAYOUT),
            Arguments.of("entry вне таблицы", main(ReturnVoid).copy(entryFunctionIndex = 1), BytecodeIssue.ENTRY),
            Arguments.of("функция без инструкций", main(), BytecodeIssue.LAYOUT),
            Arguments.of("param_count больше local_count", moduleOf(Fn(2, 1, listOf(ReturnVoid))), BytecodeIssue.PARAMETERS),
            Arguments.of(
                "имя функции не String-константа",
                main(ReturnVoid).let { it.copy(functions = listOf(it.functions.single().copy(nameConstIndex = 0))) },
                BytecodeIssue.FUNCTION_NAME,
            ),
            Arguments.of(
                "строка с NUL в пуле",
                main(ReturnVoid).let { it.copy(constants = it.constants + ConstantValue.StringConst("a\u0000")) },
                BytecodeIssue.STRING,
            ),
            Arguments.of("индекс константы вне пула", main(LoadConst(99), Return), BytecodeIssue.CONSTANT_INDEX),
            Arguments.of("слот вне local_count", main(LoadLocal(1), Return), BytecodeIssue.LOCAL_INDEX),
            Arguments.of("запись в слот вне local_count", main(ONE, StoreLocal(5), ReturnVoid), BytecodeIssue.LOCAL_INDEX),
            Arguments.of("индекс функции вне таблицы", main(Call(7), ReturnVoid), BytecodeIssue.FUNCTION_INDEX),
            Arguments.of("недостижимый переход на конец функции", main(ReturnVoid, Jump(6)), BytecodeIssue.JUMP_TARGET),
            Arguments.of("переход внутрь операнда", main(Jump(1), ReturnVoid), BytecodeIssue.JUMP_TARGET),
            Arguments.of("отрицательная цель перехода", main(Jump(-1), ReturnVoid), BytecodeIssue.JUMP_TARGET),
            Arguments.of("смешаны RETURN и RETURN_VOID", main(ReturnVoid, ONE, Return), BytecodeIssue.RETURN),
            Arguments.of("underflow бинарной операции", main(ONE, Add, Return), BytecodeIssue.STACK_UNDERFLOW),
            Arguments.of("underflow унарной операции", main(Instruction.Neg, Return), BytecodeIssue.STACK_UNDERFLOW),
            Arguments.of("underflow STORE_LOCAL", main(StoreLocal(0), ReturnVoid), BytecodeIssue.STACK_UNDERFLOW),
            Arguments.of("underflow PRINTLN", main(Println, ReturnVoid), BytecodeIssue.STACK_UNDERFLOW),
            Arguments.of("underflow POP", main(Pop, ReturnVoid), BytecodeIssue.STACK_UNDERFLOW),
            Arguments.of("underflow JUMP_IF_FALSE", main(JumpIfFalse(0), ReturnVoid), BytecodeIssue.STACK_UNDERFLOW),
            Arguments.of(
                "underflow CALL: аргументов меньше param_count",
                moduleOf(Fn(0, 0, listOf(ONE, Call(1), ReturnVoid)), Fn(2, 2, listOf(ReturnVoid))),
                BytecodeIssue.STACK_UNDERFLOW,
            ),
            Arguments.of(
                "Unit-результат использован как значение",
                moduleOf(Fn(0, 0, listOf(Call(1), Println, ReturnVoid)), Fn(0, 0, listOf(ReturnVoid))),
                BytecodeIssue.STACK_UNDERFLOW,
            ),
            Arguments.of(
                "разные высоты в точке слияния",
                // 0 TRUE, 3 JIF 11, 8 ONE, 11 RETURN_VOID — в 11 приходят высоты 1 и 0
                main(TRUE, JumpIfFalse(11), ONE, ReturnVoid),
                BytecodeIssue.STACK_JOIN,
            ),
            Arguments.of(
                "несбалансированный цикл",
                // 0 ONE, 3 JUMP 0 — каждый проход добавляет значение
                main(ONE, Jump(0)),
                BytecodeIssue.STACK_JOIN,
            ),
            Arguments.of("лишнее значение перед RETURN", main(ONE, ONE, Return), BytecodeIssue.RETURN),
            Arguments.of("значение перед RETURN_VOID", main(ONE, ReturnVoid), BytecodeIssue.RETURN),
            Arguments.of("достижимый выход за конец функции", main(ONE, Pop), BytecodeIssue.FALLOFF),
            Arguments.of(
                "falloff на одной из веток условного перехода",
                // 0 TRUE, 3 JIF 9, 8 RETURN_VOID, 9 TRUE, 12 POP — ветка false доходит до конца функции
                main(TRUE, JumpIfFalse(9), ReturnVoid, TRUE, Pop),
                BytecodeIssue.FALLOFF,
            ),
        )
    }
}
