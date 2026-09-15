package ru.itmo.applang.bytecode

/**
 * Инструкция байткода в типобезопасном (не сыром байтовом) виде.
 * Ровно один подкласс на опкод — упрощает и codegen, и disassembler, и юнит-тесты
 * (ожидаемая последовательность инструкций читается как обычный список объектов Kotlin).
 *
 * `byteSize` — точный размер инструкции в закодированном виде (1 байт опкода + операнды),
 * используется при разрешении меток переходов в byte-offset'ы (см. codegen/Labels.kt).
 */
sealed class Instruction(val opcode: Opcode) {
    abstract val byteSize: Int

    data class LoadConst(val constIndex: Int) : Instruction(Opcode.LOAD_CONST) {
        override val byteSize = 3 // opcode(1) + idx:u16(2)
    }

    data class LoadLocal(val slot: Int) : Instruction(Opcode.LOAD_LOCAL) {
        override val byteSize = 2 // opcode(1) + slot:u8(1)
    }

    data class StoreLocal(val slot: Int) : Instruction(Opcode.STORE_LOCAL) {
        override val byteSize = 2
    }

    data object Add : Instruction(Opcode.ADD) { override val byteSize = 1 }
    data object Sub : Instruction(Opcode.SUB) { override val byteSize = 1 }
    data object Mul : Instruction(Opcode.MUL) { override val byteSize = 1 }
    data object Div : Instruction(Opcode.DIV) { override val byteSize = 1 }
    data object Mod : Instruction(Opcode.MOD) { override val byteSize = 1 }
    data object Neg : Instruction(Opcode.NEG) { override val byteSize = 1 }
    data object Concat : Instruction(Opcode.CONCAT) { override val byteSize = 1 }

    data object CmpEq : Instruction(Opcode.CMP_EQ) { override val byteSize = 1 }
    data object CmpNe : Instruction(Opcode.CMP_NE) { override val byteSize = 1 }
    data object CmpLt : Instruction(Opcode.CMP_LT) { override val byteSize = 1 }
    data object CmpGt : Instruction(Opcode.CMP_GT) { override val byteSize = 1 }
    data object CmpLe : Instruction(Opcode.CMP_LE) { override val byteSize = 1 }
    data object CmpGe : Instruction(Opcode.CMP_GE) { override val byteSize = 1 }

    data object Not : Instruction(Opcode.NOT) { override val byteSize = 1 }

    /** [target] — абсолютный byte-offset внутри code-секции функции (не индекс инструкции). */
    data class Jump(val target: Int) : Instruction(Opcode.JUMP) {
        override val byteSize = 5 // opcode(1) + addr:u32(4)
    }

    data class JumpIfFalse(val target: Int) : Instruction(Opcode.JUMP_IF_FALSE) {
        override val byteSize = 5
    }

    data class Call(val functionIndex: Int) : Instruction(Opcode.CALL) {
        override val byteSize = 3 // opcode(1) + func_idx:u16(2)
    }

    data object Return : Instruction(Opcode.RETURN) { override val byteSize = 1 }
    data object ReturnVoid : Instruction(Opcode.RETURN_VOID) { override val byteSize = 1 }

    data object Print : Instruction(Opcode.PRINT) { override val byteSize = 1 }
    data object Println : Instruction(Opcode.PRINTLN) { override val byteSize = 1 }

    data object Pop : Instruction(Opcode.POP) { override val byteSize = 1 }
}
