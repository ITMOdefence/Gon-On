package ru.itmo.applang.codegen

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

/** Проверяет требование GVM: цель каждого перехода — начало инструкции, строго меньше длины кода функции. */
private fun assertAllJumpTargetsAreInstructionStarts(instructions: List<Instruction>) {
    val starts = instructions.indices.map { byteOffsetOf(instructions, it) }.toSet()
    val targets = instructions.mapNotNull {
        when (it) {
            is Instruction.Jump -> it.target
            is Instruction.JumpIfFalse -> it.target
            else -> null
        }
    }
    targets.forEach { assertTrue(it in starts, "цель перехода $it не является началом инструкции: $instructions") }
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
    @DisplayName("if с else, обе ветки которого возвращают, не содержит перехода на конец функции")
    fun `if with else where both branches return has no jump past the end of the function`() {
        // Arrange
        val module = compile("fun main(): Int { if (false) { return 1; } else { return 2; } }")

        // Act
        val instructions = module.function("main").instructions
        // 0=LoadConst(false),1=JumpIfFalse(elseLabel),2=LoadConst(1),3=Return,4=LoadConst(2),5=Return

        // Assert
        assertEquals(6, instructions.size)
        assertEquals(byteOffsetOf(instructions, 4), (instructions[1] as Instruction.JumpIfFalse).target)
        assertFalse(instructions.any { it is Instruction.Jump }, "после then-ветки с return переход не нужен")
        assertAllJumpTargetsAreInstructionStarts(instructions)
    }

    @Test
    @DisplayName("if с else, then-ветка которого продолжается, перепрыгивает else к следующей инструкции")
    fun `if with else jumps over the else branch when the then branch falls through`() {
        // Arrange
        val module = compile(
            "fun main(): Int { var x: Int = 0; if (true) { x = 1; } else { x = 2; } return x; }",
        )

        // Act
        val instructions = module.function("main").instructions
        val jump = instructions.filterIsInstance<Instruction.Jump>().single()
        val continuationIndex = instructions.indexOfLast { it is Instruction.LoadLocal }

        // Assert
        assertEquals(byteOffsetOf(instructions, continuationIndex), jump.target)
        assertAllJumpTargetsAreInstructionStarts(instructions)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "fun main(): Int { if (true) { return 1; } else { return 2; } }",
            "fun main(): Int { if (true) { return 1; } return 2; }",
            "fun main(): Int { if (true) { if (false) { return 1; } else { return 2; } } else { return 3; } }",
            "fun main(): Int { var i: Int = 0; while (i < 3) { if (i == 1) { return i; } i = i + 1; } return 0; }",
            "fun main(): Int { while (true) { return 1; } return 2; }",
            "fun main(): Bool { if (true && false || true) { return true || false; } else { return false && true; } }",
            "fun absolute(x: Int): Int { if (x < 0) { return -x; } else { return x; } } fun main(): Int { return absolute(-5); }",
            "fun main() { if (true) { return; } else { return; } }",
        ],
    )
    @DisplayName("Все цели переходов указывают на начало существующей инструкции функции")
    fun `every jump targets the start of an existing instruction`(source: String) {
        // Act
        val module = compile(source)

        // Assert
        module.functions.forEach { assertAllJumpTargetsAreInstructionStarts(it.instructions) }
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
    @DisplayName("Отдельный вызов функции с результатом снимает ненужное значение через POP, а Unit-вызов — нет")
    fun `standalone call pops its value only when the callee returns one`() {
        // Arrange
        val module = compile(
            "fun value(): Int { return 1; } fun nothing() {} fun main() { value(); nothing(); }",
        )

        // Act
        val instructions = module.function("main").instructions

        // Assert
        assertEquals(
            listOf(Instruction.Call(0), Instruction.Pop, Instruction.Call(1), Instruction.ReturnVoid),
            instructions,
        )
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

    @Test
    @DisplayName("Пул констант больше 65535 записей после дедупликации — ошибка предела, а не усечение")
    fun `constant pool larger than u16 is rejected`() {
        // Arrange: 65535 разных Int-литералов + имя main = 65536 записей
        val body = (0 until 65_535).joinToString(" ") { "println($it);" }
        val source = "fun main() { $body println(0); println(1); }"
        val lexer = AppLangLexer(CharStreams.fromString(source))
        val program = AstBuilder().buildProgram(AppLangParser(CommonTokenStream(lexer)).program())
        val collector = DiagnosticCollector()
        TypeChecker(collector).check(program)
        assertFalse(collector.hasErrors)

        // Act & Assert
        val error = assertThrows(CodegenLimitException::class.java) { BytecodeEmitter().emit(program) }
        assertTrue(error.message!!.contains("65536"))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "fun main() {} fun a() {} fun b() {}",
            "fun a() {} fun main() {} fun b() {}",
            "fun a() {} fun b() {} fun main() {}",
        ],
    )
    @DisplayName("Индекс entry указывает на main независимо от её положения в исходнике")
    fun `entry index points to main wherever it is declared`(source: String) {
        // Act
        val module = compile(source)

        // Assert
        assertEquals("main", module.functions[module.entryFunctionIndex].name)
    }

    @Test
    @DisplayName("Программа без классов компилируется в профиль 1.0, с классом — в 2.0")
    fun `profile is chosen by the presence of classes`() {
        // Act
        val procedural = compile("fun main() {}")
        val objects = compile("class A {} fun main() {}")

        // Assert
        assertEquals(1 to 0, procedural.versionMajor to procedural.versionMinor)
        assertEquals(2 to 0, objects.versionMajor to objects.versionMinor)
        assertEquals(listOf("A.init", "main"), objects.functions.map { it.name })
    }

    @Test
    @DisplayName("Вызов метода: receiver первым и один раз во временный слот, аргументы справа налево, this последним")
    fun `method call evaluates receiver first then arguments right to left`() {
        // Arrange
        val module = compile(
            "class C { fun m(a: Int, b: Int): Int { return a - b; } } " +
                "fun main(): Int { val c = C(); return c.m(10, 20); }",
        )
        val main = module.function("main")
        val tempSlot = 1 // слот 0 — переменная c, временные слоты идут после именованных
        val c = 0
        fun constIndex(value: Int) = module.constants.indexOf(ru.itmo.applang.bytecode.ConstantValue.IntConst(value))

        // Act
        val callSequence = main.instructions.dropWhile { it != Instruction.LoadLocal(c) }

        // Assert
        assertEquals(
            listOf(
                Instruction.LoadLocal(c),
                Instruction.StoreLocal(tempSlot),
                Instruction.LoadConst(constIndex(20)),
                Instruction.LoadConst(constIndex(10)),
                Instruction.LoadLocal(tempSlot),
                Instruction.Call(module.functions.indexOfFirst { it.name == "C.m" }),
                Instruction.Return,
            ),
            callSequence,
        )
        assertEquals(3, module.function("C.m").paramCount, "this + 2 явных параметра")
    }

    @Test
    @DisplayName("Создание: аргументы, ALLOC_OBJECT, сохранение ссылки, CALL инициализации, ссылка как результат")
    fun `construction allocates then calls the initializer and yields the reference`() {
        // Arrange
        val module = compile("class P { var v: Int; constructor(v: Int) { this.v = v; } } fun main() { val p = P(5); }")
        val init = module.functions.indexOfFirst { it.name == "P.init" }

        // Act
        val main = module.function("main").instructions

        // Assert: слот 0 — переменная p, слот 1 — временный
        assertEquals(
            listOf(
                Instruction.LoadConst(module.constants.indexOf(ru.itmo.applang.bytecode.ConstantValue.IntConst(5))),
                Instruction.AllocObject(0),
                Instruction.StoreLocal(1),
                Instruction.LoadLocal(1),
                Instruction.Call(init),
                Instruction.LoadLocal(1),
                Instruction.StoreLocal(0),
                Instruction.ReturnVoid,
            ),
            main,
        )
        assertEquals(2, module.function("P.init").paramCount)
        assertEquals(Instruction.ReturnVoid, module.function("P.init").instructions.last())
    }

    @Test
    @DisplayName("Запись поля: receiver вычисляется раньше правой части, затем SET_FIELD")
    fun `field write evaluates receiver before the value`() {
        // Arrange
        val module = compile("class P { var v: Int = 0; } fun main() { val p = P(); p.v = 7; }")

        // Act
        val tail = module.function("main").instructions.takeLast(4)

        // Assert
        assertEquals(
            listOf(
                Instruction.LoadLocal(0),
                Instruction.LoadConst(module.constants.indexOf(ru.itmo.applang.bytecode.ConstantValue.IntConst(7))),
                Instruction.SetField(0, 0),
                Instruction.ReturnVoid,
            ),
            tail,
        )
    }

    @Test
    @DisplayName("Инициализаторы полей выполняются в порядке объявления до тела конструктора")
    fun `field initializers run in declaration order before the constructor body`() {
        // Arrange
        val module = compile(
            "class A { var first: Int = 1; var second: Int; var third: Int = 3; constructor() { this.second = 2; } } fun main() {}",
        )

        // Act
        val writes = module.function("A.init").instructions.filterIsInstance<Instruction.SetField>().map { it.fieldIndex }

        // Assert
        assertEquals(listOf(0, 2, 1), writes)
    }

    @Test
    @DisplayName("Вложенные вызовы методов используют разные временные слоты и не затирают receiver")
    fun `nested method calls use distinct temporary slots`() {
        // Arrange
        val module = compile(
            "class C { fun id(x: Int): Int { return x; } } " +
                "fun main(): Int { val a = C(); val b = C(); return a.id(b.id(1)); }",
        )

        // Act
        val main = module.function("main")
        val stores = main.instructions.filterIsInstance<Instruction.StoreLocal>().map { it.slot }

        // Assert: a=0, b=1; создания используют слот 2; a.id — слот 2, вложенный b.id — слот 3
        assertEquals(listOf(2, 0, 2, 1, 2, 3), stores)
        assertEquals(4, main.localCount)
    }

    @Test
    @DisplayName("Неиспользованный объектный результат снимается POP, после Unit-метода POP нет")
    fun `unused object result is popped but unit method result is not`() {
        // Arrange
        val module = compile("class A { fun touch() {} } fun main() { A(); A().touch(); }")

        // Act
        val main = module.function("main").instructions

        // Assert
        assertEquals(1, main.count { it == Instruction.Pop })
        assertEquals(Instruction.ReturnVoid, main.last())
        assertTrue(main[main.size - 2] is Instruction.Call)
    }

    @Test
    @DisplayName("ClassTable: поля с типами, методы класса без функции инициализации")
    fun `class table lists typed fields and methods without the initializer`() {
        // Arrange
        val module = compile(
            "class Owner { var name: String = \"x\"; var pet: Pet = Pet(); fun hello() {} } class Pet { var age: Int = 1; } fun main() {}",
        )

        // Act
        val owner = module.classes[0]

        // Assert
        assertEquals(listOf("name", "pet"), owner.fields.map { it.name })
        assertEquals(ru.itmo.applang.bytecode.FieldType.StringType, owner.fields[0].type)
        assertEquals(ru.itmo.applang.bytecode.FieldType.ObjectType(1), owner.fields[1].type)
        assertEquals(listOf("Owner.hello"), owner.methodFunctionIndices.map { module.functions[it].name })
    }
}
