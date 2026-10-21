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

    /** Вызов функции без результата использован там, где нужно значение (BASE-02). */
    const val NO_VALUE = "NO_VALUE"

    /** Строковый литерал нарушает контракт libstr: NUL, некорректный UTF-8 или длина > 1 МиБ (BASE-03). */
    const val INVALID_STRING_CONSTANT = "INVALID_STRING_CONSTANT"

    /** Превышен предел формата `.albc` или реализации компилятора: слоты, параметры, таблицы (BASE-04). */
    const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"

    /** В программе нет функции `main` — точки входа стандартного раннера GVM (BASE-05). */
    const val MISSING_MAIN = "MISSING_MAIN"

    /** `main` объявлена с параметрами: стандартный раннер GVM запускает entry без аргументов (BASE-05). */
    const val INVALID_MAIN_SIGNATURE = "INVALID_MAIN_SIGNATURE"
}
