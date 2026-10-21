package ru.itmo.applang.bytecode

/**
 * Инструкция байткода в типобезопасном (не сыром байтовом) виде.
 * Ровно один подкласс на опкод — упрощает и codegen, и disassembler, и юнит-тесты
 * (ожидаемая последовательность инструкций читается как обычный список объектов Kotlin).
 *
 * `byteSize` — точный размер инструкции в закодированном виде (1 байт опкода + операнды),
 * используется при разрешении меток переходов в byte-offset'ы (см. codegen/Labels.kt). Берётся из
 * [Opcode.byteSize] — единого источника длин для writer, reader, меток и верификатора.
 */
sealed class Instruction(val opcode: Opcode) {
    val byteSize: Int get() = opcode.byteSize

    data class LoadConst(val constIndex: Int) : Instruction(Opcode.LOAD_CONST)

    data class LoadLocal(val slot: Int) : Instruction(Opcode.LOAD_LOCAL)

    data class StoreLocal(val slot: Int) : Instruction(Opcode.STORE_LOCAL)

    data object Add : Instruction(Opcode.ADD)
    data object Sub : Instruction(Opcode.SUB)
    data object Mul : Instruction(Opcode.MUL)
    data object Div : Instruction(Opcode.DIV)
    data object Mod : Instruction(Opcode.MOD)
    data object Neg : Instruction(Opcode.NEG)
    data object Concat : Instruction(Opcode.CONCAT)

    data object CmpEq : Instruction(Opcode.CMP_EQ)
    data object CmpNe : Instruction(Opcode.CMP_NE)
    data object CmpLt : Instruction(Opcode.CMP_LT)
    data object CmpGt : Instruction(Opcode.CMP_GT)
    data object CmpLe : Instruction(Opcode.CMP_LE)
    data object CmpGe : Instruction(Opcode.CMP_GE)

    data object Not : Instruction(Opcode.NOT)

    /** [target] — абсолютный byte-offset внутри code-секции функции (не индекс инструкции). */
    data class Jump(val target: Int) : Instruction(Opcode.JUMP)

    data class JumpIfFalse(val target: Int) : Instruction(Opcode.JUMP_IF_FALSE)

    data class Call(val functionIndex: Int) : Instruction(Opcode.CALL)

    data object Return : Instruction(Opcode.RETURN)
    data object ReturnVoid : Instruction(Opcode.RETURN_VOID)

    data object Print : Instruction(Opcode.PRINT)
    data object Println : Instruction(Opcode.PRINTLN)

    data object Pop : Instruction(Opcode.POP)
}
