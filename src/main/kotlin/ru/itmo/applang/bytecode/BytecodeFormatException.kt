package ru.itmo.applang.bytecode

/**
 * Категория нарушения формата `.albc`. Названия повторяют `BytecodeIssue` из документации
 * верификатора GVM (`DevDoc/documentation/LoadRead/bytecode_verifier.md`), чтобы отказ
 * компилятора, reference-reader и ВМ можно было сопоставить по одной причине.
 */
enum class BytecodeIssue {
    TRUNCATED,
    MAGIC,
    VERSION,
    FLAGS,
    CONSTANT_TAG,
    BOOLEAN,
    STRING,
    LAYOUT,
    ENTRY,
    FUNCTION_NAME,
    PARAMETERS,
    OPCODE,
    OPERAND,
    CONSTANT_INDEX,
    LOCAL_INDEX,
    FUNCTION_INDEX,
    CLASS_INDEX,
    CLASS_NAME,
    FIELD_INDEX,
    FIELD_TYPE,
    FIELD_NAME,
    METHOD_INDEX,
    JUMP_TARGET,
    STACK_UNDERFLOW,
    STACK_JOIN,
    RETURN,
    FALLOFF,
    RESOURCE_LIMIT,
}

/**
 * Единая ошибка формата: и строгий [BytecodeReader], и [BytecodeVerifier], и [BytecodeWriter]
 * сообщают о нарушении контракта только этим исключением — с категорией [issue] и контекстом
 * (функция, смещение инструкции, поле) в сообщении. Повреждённый вход не должен приводить к
 * `IndexOutOfBoundsException`, `NegativeArraySizeException` и подобным непрофильным исключениям.
 */
class BytecodeFormatException(
    val issue: BytecodeIssue,
    message: String,
) : Exception("[$issue] $message")
