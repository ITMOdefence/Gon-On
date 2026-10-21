package ru.itmo.applang.bytecode

/**
 * Единственный source of truth для нумерации опкодов стековой машины Gon&On.
 * Таблица в docs/BYTECODE_SPEC.md должна дословно соответствовать этому файлу — при добавлении
 * или изменении опкода обновляй оба места в одном коммите.
 *
 * Диапазон 0x01-0x3F зарезервирован за v1. Профиль 2.0 добавляет ровно 0x40-0x42, не меняя
 * семантику кодов v1; 0x43 и далее не назначены. [sinceVersionMajor] — минимальная мажорная
 * версия формата, в которой опкод допустим.
 */
enum class Opcode(val code: Int, val operandBytes: Int = 0, val sinceVersionMajor: Int = 1) {
    LOAD_CONST(0x01, operandBytes = 2),
    LOAD_LOCAL(0x02, operandBytes = 1),
    STORE_LOCAL(0x03, operandBytes = 1),

    ADD(0x04),
    SUB(0x05),
    MUL(0x06),
    DIV(0x07),
    MOD(0x08),
    NEG(0x09),
    CONCAT(0x0A),

    CMP_EQ(0x0B),
    CMP_NE(0x0C),
    CMP_LT(0x0D),
    CMP_GT(0x0E),
    CMP_LE(0x0F),
    CMP_GE(0x10),

    NOT(0x11),

    JUMP(0x12, operandBytes = 4),
    JUMP_IF_FALSE(0x13, operandBytes = 4),

    CALL(0x14, operandBytes = 2),
    RETURN(0x15),
    RETURN_VOID(0x16),

    PRINT(0x17),
    PRINTLN(0x18),

    POP(0x19),

    // Профиль 2.0 (docs/backlog/bytecode-contract.md §7.2). В файле 1.0 эти коды отклоняются.
    ALLOC_OBJECT(0x40, operandBytes = 2, sinceVersionMajor = 2),
    GET_FIELD(0x41, operandBytes = 4, sinceVersionMajor = 2),
    SET_FIELD(0x42, operandBytes = 4, sinceVersionMajor = 2),
    ;

    /** Полная длина закодированной инструкции: байт опкода и операнды фиксированной ширины. */
    val byteSize: Int get() = 1 + operandBytes

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** Опкод по его байту или null для неназначенного кода — неизвестный опкод не трактуется как noop. */
        fun fromCodeOrNull(code: Int): Opcode? = byCode[code]
    }
}
