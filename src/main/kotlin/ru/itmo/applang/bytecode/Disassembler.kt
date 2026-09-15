package ru.itmo.applang.bytecode

/**
 * Текстовый дамп [BytecodeModule] — не часть обязательного бинарного контракта, но
 * reference-инструмент для команды ВМ: по этому выводу можно вручную сверять сериализацию
 * своего загрузчика байткода, а golden-файлы в examples/ используют именно этот формат.
 */
object Disassembler {

    fun disassemble(module: BytecodeModule): String {
        val sb = StringBuilder()
        sb.appendLine("; AppLang bytecode disassembly")
        sb.appendLine("; version ${module.versionMajor}.${module.versionMinor}")
        sb.appendLine()

        sb.appendLine(".constants")
        module.constants.forEachIndexed { index, constant ->
            sb.appendLine("  $index: ${formatConstant(constant)}")
        }
        sb.appendLine()

        module.functions.forEachIndexed { index, function ->
            val marker = if (index == module.entryFunctionIndex) "  ; entry point" else ""
            sb.appendLine(".function ${function.name}(params=${function.paramCount}, locals=${function.localCount})$marker")
            var offset = 0
            for (instruction in function.instructions) {
                sb.appendLine("  %04d: %s".format(offset, formatInstruction(instruction)))
                offset += instruction.byteSize
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun formatConstant(constant: ConstantValue): String = when (constant) {
        is ConstantValue.IntConst -> "Int32 ${constant.value}"
        is ConstantValue.BoolConst -> "Bool ${constant.value}"
        is ConstantValue.StringConst -> "String ${escapeForDisplay(constant.value)}"
    }

    private fun escapeForDisplay(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }

    private fun formatInstruction(instruction: Instruction): String = when (instruction) {
        is Instruction.LoadConst -> "LOAD_CONST ${instruction.constIndex}"
        is Instruction.LoadLocal -> "LOAD_LOCAL ${instruction.slot}"
        is Instruction.StoreLocal -> "STORE_LOCAL ${instruction.slot}"
        Instruction.Add -> "ADD"
        Instruction.Sub -> "SUB"
        Instruction.Mul -> "MUL"
        Instruction.Div -> "DIV"
        Instruction.Mod -> "MOD"
        Instruction.Neg -> "NEG"
        Instruction.Concat -> "CONCAT"
        Instruction.CmpEq -> "CMP_EQ"
        Instruction.CmpNe -> "CMP_NE"
        Instruction.CmpLt -> "CMP_LT"
        Instruction.CmpGt -> "CMP_GT"
        Instruction.CmpLe -> "CMP_LE"
        Instruction.CmpGe -> "CMP_GE"
        Instruction.Not -> "NOT"
        is Instruction.Jump -> "JUMP %04d".format(instruction.target)
        is Instruction.JumpIfFalse -> "JUMP_IF_FALSE %04d".format(instruction.target)
        is Instruction.Call -> "CALL ${instruction.functionIndex}"
        Instruction.Return -> "RETURN"
        Instruction.ReturnVoid -> "RETURN_VOID"
        Instruction.Print -> "PRINT"
        Instruction.Println -> "PRINTLN"
        Instruction.Pop -> "POP"
    }
}
