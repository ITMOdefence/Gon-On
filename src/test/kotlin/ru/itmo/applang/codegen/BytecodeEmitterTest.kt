package ru.itmo.applang.codegen

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.itmo.applang.ast.AstBuilder
import ru.itmo.applang.bytecode.BytecodeModule
import ru.itmo.applang.bytecode.FunctionEntry
import ru.itmo.applang.bytecode.Instruction
import ru.itmo.applang.diagnostics.DiagnosticCollector
import ru.itmo.applang.parser.AppLangLexer
import ru.itmo.applang.parser.AppLangParser
import ru.itmo.applang.semantic.TypeChecker

/** Компилирует программу целиком (парсинг -> проверка типов -> codegen), падая, если в ней есть ошибки. */
private fun compile(source: String): BytecodeModule {
    val lexer = AppLangLexer(CharStreams.fromString(source))
    val parser = AppLangParser(CommonTokenStream(lexer))
    val program = AstBuilder().buildProgram(parser.program())

    val collector = DiagnosticCollector()
    TypeChecker(collector).check(program)
    check(!collector.hasErrors) { "тестовая программа должна быть валидна, но есть ошибки: ${collector.diagnostics}" }

    return BytecodeEmitter().emit(program)
}

private fun BytecodeModule.function(name: String): FunctionEntry =
    functions.single { it.name == name }

/** Byte-offset, на котором начиналась бы инструкция с данным индексом в списке — для проверки целей переходов. */
private fun byteOffsetOf(instructions: List<Instruction>, index: Int): Int =
    instructions.take(index).sumOf { it.byteSize }

class BytecodeEmitterTest {

    @Test
    @DisplayName("Арифметическое выражение компилируется в push операндов и одну инструкцию операции")
    fun `arithmetic expression compiles to push operands then op`() {
        // Arrange
        val module = compile("fun main(): Int { return 1 + 2; }")

        // Act
        val instructions = module.function("main").instructions

        // Assert: LOAD_CONST(1), LOAD_CONST(2), ADD, RETURN — операнды кладутся в порядке написания
        assertEquals(
            listOf(Instruction.LoadConst(0), Instruction.LoadConst(1), Instruction.Add, Instruction.Return),
            instructions,
        )
    }

    @Test
    @DisplayName("var объявление кладёт значение инициализатора в слот, назначенный переменной")
    fun `var declaration stores initializer value into its assigned slot`() {
        // Arrange
        val module = compile("fun main(): Int { var x: Int = 5; return x; }")

        // Act
        val instructions = module.function("main").instructions

        // Assert: STORE_LOCAL и последующий LOAD_LOCAL ссылаются на один и тот же слот
        val storeSlot = (instructions[1] as Instruction.StoreLocal).slot
        val loadSlot = (instructions[2] as Instruction.LoadLocal).slot
        assertEquals(storeSlot, loadSlot)
    }

    @Test
    @DisplayName("if без else компилируется в один условный переход на конец блока")
    fun `if without else compiles to a single conditional jump past the block`() {
        // Arrange
        val module = compile("fun main(): Int { if (true) { return 1; } return 0; }")

        // Act
        val instructions = module.function("main").instructions

        // Assert: 0=LoadConst(true), 1=JumpIfFalse(?), 2=LoadConst(1), 3=Return, 4=LoadConst(0), 5=Return
        assertEquals(6, instructions.size)
        assertTrue(instructions[1] is Instruction.JumpIfFalse)
        val jumpTarget = (instructions[1] as Instruction.JumpIfFalse).target
        // Цель перехода — начало кода, идущего ПОСЛЕ then-ветки (инструкция с индексом 4: LoadConst(0)).
        assertEquals(byteOffsetOf(instructions, 4), jumpTarget)
    }

    @Test
    @DisplayName("if с else компилируется в переход в else-ветку и безусловный переход в конец из then-ветки")
    fun `if with else jumps to else branch and then jumps past it from the then branch`() {
        // Arrange
        val module = compile("fun main(): Int { if (false) { return 1; } else { return 2; } }")

        // Act
        val instructions = module.function("main").instructions
        // 0=LoadConst(false),1=JumpIfFalse(elseLabel),2=LoadConst(1),3=Return,4=Jump(endLabel),
        // 5=LoadConst(2),6=Return

        // Assert
        val jumpIfFalseTarget = (instructions[1] as Instruction.JumpIfFalse).target
        val jumpTarget = (instructions[4] as Instruction.Jump).target
        assertEquals(byteOffsetOf(instructions, 5), jumpIfFalseTarget, "else-ветка начинается с индекса 5")
        assertEquals(byteOffsetOf(instructions, 7), jumpTarget, "переход из then ведёт за пределы всего if")
    }

    @Test
    @DisplayName("while компилируется в переход назад к проверке условия после тела цикла")
    fun `while loop jumps back to the condition check after its body`() {
        // Arrange
        val module = compile("fun main(): Int { var i: Int = 0; while (i < 3) { i = i + 1; } return i; }")

        // Act
        val instructions = module.function("main").instructions
        val backwardJump = instructions.filterIsInstance<Instruction.Jump>().single()
        val conditionStartIndex = instructions.indexOfFirst { it is Instruction.LoadLocal } // первая LOAD_LOCAL — начало условия i < 3

        // Assert
        assertEquals(byteOffsetOf(instructions, conditionStartIndex), backwardJump.target)
    }

    @Test
    @DisplayName("Аргументы вызова функции кладутся на стек в обратном порядке (см. BYTECODE_SPEC.md)")
    fun `call arguments are pushed in reverse order`() {
        // Arrange
        val module = compile(
            "fun sub(a: Int, b: Int): Int { return a - b; } " +
                "fun main(): Int { return sub(10, 20); }",
        )

        // Act
        val instructions = module.function("main").instructions
        val loadConsts = instructions.filterIsInstance<Instruction.LoadConst>()

        // Assert: первым push'ится аргумент b(20), затем a(10) — обратный порядок
        val constants = module.constants
        fun valueOf(idx: Int) = (constants[idx] as ru.itmo.applang.bytecode.ConstantValue.IntConst).value
        assertEquals(20, valueOf(loadConsts[0].constIndex))
        assertEquals(10, valueOf(loadConsts[1].constIndex))
    }

    @Test
    @DisplayName("'&&' компилируется через переходы (short-circuit), без отдельного логического опкода")
    fun `logical and compiles via jumps without a dedicated AND opcode`() {
        // Arrange
        val module = compile("fun main(): Bool { return true && false; }")

        // Act
        val instructions = module.function("main").instructions

        // Assert
        assertTrue(instructions.any { it is Instruction.JumpIfFalse }, "должен быть условный переход для short-circuit")
        assertFalse(
            instructions.any { it.opcode.name.contains("AND") },
            "в наборе опкодов нет AND — логика реализуется через переходы",
        )
    }

    @Test
    @DisplayName("Конкатенация строк использует CONCAT, а не ADD")
    fun `string concatenation uses CONCAT, not ADD`() {
        // Arrange
        val module = compile("fun main() { println(\"a\" + \"b\"); }")

        // Act
        val instructions = module.function("main").instructions

        // Assert
        assertTrue(instructions.contains(Instruction.Concat))
        assertFalse(instructions.contains(Instruction.Add))
    }

    @Test
    @DisplayName("Функция с типом возврата Unit заканчивается RETURN_VOID, даже без явного return")
    fun `unit function ends with RETURN_VOID even without explicit return`() {
        // Arrange
        val module = compile("fun main() { println(\"hi\"); }")

        // Act
        val instructions = module.function("main").instructions

        // Assert
        assertEquals(Instruction.ReturnVoid, instructions.last())
    }
}
