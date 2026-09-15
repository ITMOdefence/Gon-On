package ru.itmo.applang.semantic

/**
 * Стабильные коды семантических ошибок. Используются и в сообщениях компилятора, и в тестах
 * (тесты проверяют код, а не текст сообщения — текст может меняться, код нет).
 */
object SemanticErrorCodes {
    const val UNDEFINED_VARIABLE = "UNDEFINED_VARIABLE"
    const val UNDEFINED_FUNCTION = "UNDEFINED_FUNCTION"
    const val DUPLICATE_FUNCTION = "DUPLICATE_FUNCTION"
    const val DUPLICATE_VARIABLE = "DUPLICATE_VARIABLE"
    const val VAL_REASSIGNMENT = "VAL_REASSIGNMENT"
    const val TYPE_MISMATCH = "TYPE_MISMATCH"
    const val ARITY_MISMATCH = "ARITY_MISMATCH"
    const val ARGUMENT_TYPE_MISMATCH = "ARGUMENT_TYPE_MISMATCH"
    const val RETURN_TYPE_MISMATCH = "RETURN_TYPE_MISMATCH"
    const val MISSING_RETURN = "MISSING_RETURN"
    const val UNEXPECTED_RETURN_VALUE = "UNEXPECTED_RETURN_VALUE"
    const val INVALID_OPERAND_TYPE = "INVALID_OPERAND_TYPE"
}
