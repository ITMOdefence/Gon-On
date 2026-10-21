package ru.itmo.applang.bytecode

/**
 * Проверка готового модуля по правилам загрузчика и верификатора GVM до записи файла
 * (docs/backlog/base.md, BASE-06). Типизация AST не гарантирует отсутствие ошибок генератора,
 * разрешения меток и сериализации, поэтому каждый модуль, который пишет [BytecodeWriter] и
 * возвращает [BytecodeReader], проходит эту проверку целиком:
 *
 * 1. заголовок и таблицы — поддерживаемый профиль, количества, entry, имена функций;
 * 2. все инструкции, включая недостижимые, — индексы констант / слотов / функций, цели переходов
 *    только на начало инструкции своей функции, отсутствие смешения RETURN и RETURN_VOID;
 * 3. режим возврата каждой функции (VALUE / VOID / NONE), выводимый из её инструкций —
 *    в `.albc` нет поля типа результата;
 * 4. высоты операндного стека по графу управления: начальная высота 0, отсутствие underflow,
 *    одинаковая высота в точках слияния, отсутствие достижимого выхода за конец функции,
 *    ровно 1 значение на RETURN и 0 на RETURN_VOID.
 *
 * Это проверка количества значений, а не их типов: корректность Int / Bool / String
 * обеспечивает TypeChecker. Нарушение сообщается [BytecodeFormatException] с категорией GVM.
 */
object BytecodeVerifier {

    /** Арность нормального возврата функции, выведенная из её инструкций возврата. */
    enum class ReturnMode {
        /** Есть RETURN, нет RETURN_VOID: CALL оставляет одно значение. */
        VALUE,

        /** Есть RETURN_VOID, нет RETURN: CALL ничего не оставляет. */
        VOID,

        /** Нет инструкций возврата: после CALL нет нормального продолжения. */
        NONE,
    }

    private val SUPPORTED_VERSIONS = setOf(1 to 0)

    fun verify(module: BytecodeModule) {
        verifyHeader(module)
        verifyConstants(module)
        verifyFunctionTable(module)
        val returnModes = module.functions.mapIndexed { index, function -> returnModeOf(index, function) }
        module.functions.forEachIndexed { index, function ->
            val offsets = instructionOffsets(index, function)
            verifyInstructions(module, index, function, offsets)
            verifyStackHeights(module, index, function, offsets, returnModes)
        }
    }

    /** Режимы возврата всех функций модуля; используется и дизассемблером, и тестами. */
    fun returnModes(module: BytecodeModule): List<ReturnMode> =
        module.functions.mapIndexed { index, function -> returnModeOf(index, function) }

    private fun verifyHeader(module: BytecodeModule) {
        if ((module.versionMajor to module.versionMinor) !in SUPPORTED_VERSIONS) {
            fail(
                BytecodeIssue.VERSION,
                "версия формата ${module.versionMajor}.${module.versionMinor} не поддерживается " +
                    "(поддерживается ${SUPPORTED_VERSIONS.joinToString { "${it.first}.${it.second}" }})",
            )
        }
    }

    private fun verifyConstants(module: BytecodeModule) {
        if (module.constants.size > FormatLimits.MAX_TABLE_ENTRIES) {
            fail(
                BytecodeIssue.LAYOUT,
                "пул содержит ${module.constants.size} констант, максимум ${FormatLimits.MAX_TABLE_ENTRIES}",
            )
        }
        module.constants.forEachIndexed { index, constant ->
            if (constant is ConstantValue.StringConst) {
                StringConstraints.violation(constant.value)?.let { fail(BytecodeIssue.STRING, "константа #$index: $it") }
            }
        }
    }

    private fun verifyFunctionTable(module: BytecodeModule) {
        val count = module.functions.size
        if (count == 0) {
            fail(BytecodeIssue.LAYOUT, "таблица функций пуста: загружаемой программе нужна хотя бы одна функция")
        }
        if (count > FormatLimits.MAX_TABLE_ENTRIES) {
            fail(BytecodeIssue.LAYOUT, "таблица содержит $count функций, максимум ${FormatLimits.MAX_TABLE_ENTRIES}")
        }
        if (module.entryFunctionIndex !in 0 until count) {
            fail(BytecodeIssue.ENTRY, "entry_function_idx = ${module.entryFunctionIndex} вне таблицы из $count функций")
        }

        var totalCodeSize = 0L
        module.functions.forEachIndexed { index, function ->
            val where = functionContext(index, function)
            val name = module.constants.getOrNull(function.nameConstIndex)
            if (name !is ConstantValue.StringConst) {
                fail(
                    BytecodeIssue.FUNCTION_NAME,
                    "$where: name_const_idx = ${function.nameConstIndex} не ссылается на String-константу",
                )
            }
            if (name.value != function.name) {
                fail(BytecodeIssue.FUNCTION_NAME, "$where: имя в модели не совпадает с константой '${name.value}'")
            }
            if (function.paramCount !in 0..FormatLimits.MAX_PARAMETERS) {
                fail(BytecodeIssue.LAYOUT, "$where: param_count = ${function.paramCount} не помещается в u8")
            }
            if (function.localCount !in 0..FormatLimits.MAX_LOCAL_SLOTS) {
                fail(BytecodeIssue.LAYOUT, "$where: local_count = ${function.localCount} не помещается в u8")
            }
            if (function.paramCount > function.localCount) {
                fail(
                    BytecodeIssue.PARAMETERS,
                    "$where: param_count = ${function.paramCount} больше local_count = ${function.localCount}",
                )
            }
            if (function.instructions.isEmpty()) {
                fail(BytecodeIssue.LAYOUT, "$where: code_length = 0, у функции нет ни одной инструкции")
            }
            totalCodeSize += function.codeByteSize
            if (totalCodeSize > FormatLimits.MAX_CODE_BYTES) {
                fail(
                    BytecodeIssue.RESOURCE_LIMIT,
                    "суммарный размер кода превышает предел реализации ${FormatLimits.MAX_CODE_BYTES} байт",
                )
            }
        }
    }

    /** Смещение начала каждой инструкции от начала функции; последний элемент — длина кода. */
    private fun instructionOffsets(index: Int, function: FunctionEntry): LongArray {
        val offsets = LongArray(function.instructions.size + 1)
        function.instructions.forEachIndexed { i, instruction ->
            offsets[i + 1] = offsets[i] + instruction.byteSize
        }
        if (offsets.last() > FormatLimits.MAX_U32) {
            fail(BytecodeIssue.LAYOUT, "${functionContext(index, function)}: длина кода не помещается в u32")
        }
        return offsets
    }

    private fun verifyInstructions(module: BytecodeModule, index: Int, function: FunctionEntry, offsets: LongArray) {
        val codeLength = offsets.last()
        function.instructions.forEachIndexed { i, instruction ->
            val where = "${functionContext(index, function)}, +${offsets[i]} ${instruction.opcode}"
            when (instruction) {
                is Instruction.LoadConst -> if (instruction.constIndex !in module.constants.indices) {
                    fail(
                        BytecodeIssue.CONSTANT_INDEX,
                        "$where: индекс константы ${instruction.constIndex} вне пула из ${module.constants.size}",
                    )
                }
                is Instruction.LoadLocal -> checkSlot(instruction.slot, function, where)
                is Instruction.StoreLocal -> checkSlot(instruction.slot, function, where)
                is Instruction.Jump -> checkJumpTarget(instruction.target.toLong(), offsets, codeLength, where)
                is Instruction.JumpIfFalse -> checkJumpTarget(instruction.target.toLong(), offsets, codeLength, where)
                is Instruction.Call -> if (instruction.functionIndex !in module.functions.indices) {
                    fail(
                        BytecodeIssue.FUNCTION_INDEX,
                        "$where: индекс функции ${instruction.functionIndex} вне таблицы из ${module.functions.size}",
                    )
                }
                else -> Unit
            }
        }
    }

    private fun checkSlot(slot: Int, function: FunctionEntry, where: String) {
        if (slot !in 0 until function.localCount) {
            fail(BytecodeIssue.LOCAL_INDEX, "$where: слот $slot вне local_count = ${function.localCount}")
        }
    }

    /** Цель — начало существующей инструкции этой же функции; конец кода инструкцией не является. */
    private fun checkJumpTarget(target: Long, offsets: LongArray, codeLength: Long, where: String) {
        if (target !in 0 until codeLength) {
            fail(BytecodeIssue.JUMP_TARGET, "$where: цель $target вне кода функции длиной $codeLength")
        }
        if (offsets.binarySearch(target, 0, offsets.size - 1) < 0) {
            fail(BytecodeIssue.JUMP_TARGET, "$where: цель $target указывает внутрь операнда, а не на начало инструкции")
        }
    }

    private fun returnModeOf(index: Int, function: FunctionEntry): ReturnMode {
        val hasValueReturn = function.instructions.any { it is Instruction.Return }
        val hasVoidReturn = function.instructions.any { it is Instruction.ReturnVoid }
        return when {
            hasValueReturn && hasVoidReturn -> fail(
                BytecodeIssue.RETURN,
                "${functionContext(index, function)}: смешаны RETURN и RETURN_VOID (в том числе в недостижимом коде)",
            )
            hasValueReturn -> ReturnMode.VALUE
            hasVoidReturn -> ReturnMode.VOID
            else -> ReturnMode.NONE
        }
    }

    /**
     * Обход графа управления от первой инструкции с высотой 0. Каждая инструкция получает
     * входную высоту при первом достижении; повторный приход с другой высотой — конфликт слияния.
     * Недостижимым инструкциям высота не назначается: для них достаточно структурных проверок.
     */
    private fun verifyStackHeights(
        module: BytecodeModule,
        index: Int,
        function: FunctionEntry,
        offsets: LongArray,
        returnModes: List<ReturnMode>,
    ) {
        val instructions = function.instructions
        val heights = IntArray(instructions.size) { UNREACHED }
        val queue = ArrayDeque<Int>()
        heights[0] = 0
        queue.addLast(0)

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val instruction = instructions[i]
            val height = heights[i]
            val where = "${functionContext(index, function)}, +${offsets[i]} ${instruction.opcode}"

            val effect = stackEffect(instruction, module, returnModes)
            if (height < effect.required) {
                fail(
                    BytecodeIssue.STACK_UNDERFLOW,
                    "$where: нужно ${effect.required} значений на стеке, есть $height",
                )
            }
            when (instruction) {
                Instruction.Return -> if (height != 1) {
                    fail(BytecodeIssue.RETURN, "$where: RETURN требует ровно 1 значение на стеке, есть $height")
                }
                Instruction.ReturnVoid -> if (height != 0) {
                    fail(BytecodeIssue.RETURN, "$where: RETURN_VOID требует пустой стек, есть $height")
                }
                else -> Unit
            }

            val outHeight = height - effect.required + effect.produced
            for (successor in successors(instruction, i, offsets, effect)) {
                if (successor == instructions.size) {
                    fail(BytecodeIssue.FALLOFF, "$where: выполнение достигает конца функции без возврата")
                }
                when (heights[successor]) {
                    UNREACHED -> {
                        heights[successor] = outHeight
                        queue.addLast(successor)
                    }
                    outHeight -> Unit
                    else -> fail(
                        BytecodeIssue.STACK_JOIN,
                        "${functionContext(index, function)}, +${offsets[successor]}: в точку слияния приходят " +
                            "высоты стека ${heights[successor]} и $outHeight",
                    )
                }
            }
        }
    }

    private class StackEffect(val required: Int, val produced: Int, val terminal: Boolean = false)

    private fun stackEffect(
        instruction: Instruction,
        module: BytecodeModule,
        returnModes: List<ReturnMode>,
    ): StackEffect = when (instruction) {
        is Instruction.LoadConst, is Instruction.LoadLocal -> StackEffect(0, 1)
        is Instruction.StoreLocal, Instruction.Print, Instruction.Println, Instruction.Pop -> StackEffect(1, 0)
        Instruction.Add, Instruction.Sub, Instruction.Mul, Instruction.Div, Instruction.Mod, Instruction.Concat,
        Instruction.CmpEq, Instruction.CmpNe, Instruction.CmpLt, Instruction.CmpGt, Instruction.CmpLe, Instruction.CmpGe,
        -> StackEffect(2, 1)
        Instruction.Neg, Instruction.Not -> StackEffect(1, 1)
        is Instruction.Jump -> StackEffect(0, 0)
        is Instruction.JumpIfFalse -> StackEffect(1, 0)
        is Instruction.Call -> {
            val callee = module.functions[instruction.functionIndex]
            when (returnModes[instruction.functionIndex]) {
                ReturnMode.VALUE -> StackEffect(callee.paramCount, 1)
                ReturnMode.VOID -> StackEffect(callee.paramCount, 0)
                ReturnMode.NONE -> StackEffect(callee.paramCount, 0, terminal = true)
            }
        }
        Instruction.Return -> StackEffect(1, 0, terminal = true)
        Instruction.ReturnVoid -> StackEffect(0, 0, terminal = true)
    }

    private fun successors(instruction: Instruction, index: Int, offsets: LongArray, effect: StackEffect): List<Int> {
        if (effect.terminal) return emptyList()
        return when (instruction) {
            is Instruction.Jump -> listOf(indexOfOffset(offsets, instruction.target))
            is Instruction.JumpIfFalse -> listOf(indexOfOffset(offsets, instruction.target), index + 1)
            else -> listOf(index + 1)
        }
    }

    private fun indexOfOffset(offsets: LongArray, target: Int): Int = offsets.binarySearch(target.toLong(), 0, offsets.size - 1)

    private fun functionContext(index: Int, function: FunctionEntry) = "функция #$index '${function.name}'"

    private fun fail(issue: BytecodeIssue, message: String): Nothing = throw BytecodeFormatException(issue, message)

    private const val UNREACHED = -1
}
