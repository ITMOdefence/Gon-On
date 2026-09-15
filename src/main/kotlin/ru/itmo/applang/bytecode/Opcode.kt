package ru.itmo.applang.bytecode

/**
 * Единственный source of truth для нумерации опкодов AppLang v1 стековой машины.
 * Таблица в docs/BYTECODE_SPEC.md должна дословно соответствовать этому файлу — при добавлении
 * или изменении опкода обновляй оба места в одном коммите.
 *
 * Диапазон 0x01-0x3F зарезервирован за v1. Будущие версии языка добавляют новые опкоды начиная
 * с 0x40, не переиспользуя и не меняя семантику существующих кодов v1 (чтобы старый байткод
 * оставался читаемым будущей ВМ без версионных веток внутри интерпретатора базовых операций).
 */
enum class Opcode(val code: Int) {
    LOAD_CONST(0x01),
    LOAD_LOCAL(0x02),
    STORE_LOCAL(0x03),

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

    JUMP(0x12),
    JUMP_IF_FALSE(0x13),

    CALL(0x14),
    RETURN(0x15),
    RETURN_VOID(0x16),

    PRINT(0x17),
    PRINTLN(0x18),

    POP(0x19),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): Opcode =
            byCode[code] ?: throw IllegalArgumentException("Неизвестный опкод: 0x${code.toString(16)}")
    }
}
