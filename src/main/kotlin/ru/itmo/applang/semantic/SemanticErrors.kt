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

    // ---- Классы и объекты (docs/LANGUAGE_SPEC.md, раздел 7) ----

    /** Тип или создание ссылается на необъявленный класс. */
    const val UNDEFINED_CLASS = "UNDEFINED_CLASS"

    /** Два класса с одинаковым именем. */
    const val DUPLICATE_CLASS = "DUPLICATE_CLASS"

    /** Повторное имя поля / метода в классе, поле и метод с одним именем или второй конструктор. */
    const val DUPLICATE_MEMBER = "DUPLICATE_MEMBER"

    /** Имя класса совпадает с функцией верхнего уровня, либо класс / функция названы print / println. */
    const val NAME_CONFLICT = "NAME_CONFLICT"

    /** У класса нет поля с таким именем. */
    const val UNDEFINED_FIELD = "UNDEFINED_FIELD"

    /** У класса нет метода с таким именем. */
    const val UNDEFINED_METHOD = "UNDEFINED_METHOD"

    /** Обращение к члену у значения, не являющегося объектом (Int, Bool, String). */
    const val INVALID_RECEIVER = "INVALID_RECEIVER"

    /** `this` вне метода или конструктора. */
    const val INVALID_THIS = "INVALID_THIS"

    /** Недостроенный `this` раскрыт до заполнения всех полей: передан, сохранён или вызван метод. */
    const val THIS_ESCAPE = "THIS_ESCAPE"

    /** Поле прочитано до гарантированного заполнения либо не заполнено к концу инициализации. */
    const val FIELD_NOT_INITIALIZED = "FIELD_NOT_INITIALIZED"

    /** Повторная запись `val`-поля или запись `val`-поля вне инициализации своего экземпляра. */
    const val VAL_FIELD_REASSIGNMENT = "VAL_FIELD_REASSIGNMENT"

    /** `return` в конструкторе. */
    const val RETURN_IN_CONSTRUCTOR = "RETURN_IN_CONSTRUCTOR"
}
