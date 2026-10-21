package ru.itmo.applang.bytecode

/**
 * Пределы формата `.albc` и реализации компилятора. Единственное место, где они заданы:
 * TypeChecker, emitter, writer, reader и verifier используют эти константы, а не свои копии.
 *
 * Пределы формата следуют из разрядности полей (u8/u16/u32). Пределы реализации JVM строже
 * u32: массивы и позиции в Kotlin индексируются Int, поэтому код и строки больше [MAX_CODE_BYTES]
 * отклоняются явно, а не обрезаются. Ресурсные настройки конкретной GVM могут быть ещё строже;
 * они здесь не придумываются (docs/backlog/base.md, BASE-04).
 */
object FormatLimits {
    /** Максимум для поля u8: `param_count`, `local_count`, номер локального слота. */
    const val MAX_U8 = 0xFF

    /** Максимум для поля u16: количества констант, функций, классов, полей, методов и индексы. */
    const val MAX_U16 = 0xFFFF

    /** Максимум для поля u32. */
    const val MAX_U32 = 0xFFFF_FFFFL

    /** Количество записей таблицы с полем количества u16: от 0 до 65535 (последний индекс — 65534). */
    const val MAX_TABLE_ENTRIES = MAX_U16

    /** Локальные слоты функции, включая параметры (`local_count: u8`). */
    const val MAX_LOCAL_SLOTS = MAX_U8

    /** Параметры функции (`param_count: u8`); у методов сюда входит неявный `this`. */
    const val MAX_PARAMETERS = MAX_U8

    /**
     * Предел длины строковой константы в байтах UTF-8: `STRING_MAX_LENGTH` библиотеки libstr
     * PUNos. Строку длиннее загрузчик GVM не примет, хотя поле длины в файле — u32.
     */
    const val MAX_STRING_BYTES = 1_048_576

    /** Предел реализации компилятора для размера кода и смещений (Int-индексация JVM), меньше u32. */
    const val MAX_CODE_BYTES = Int.MAX_VALUE - 8

    /** Значение `type_class_idx` для полей встроенных типов в ClassTable 2.0. */
    const val NO_CLASS_INDEX = 0xFFFF
}
