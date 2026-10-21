package ru.itmo.applang.bytecode

/** Тегированная запись constant pool — соответствует tag-байту в бинарном формате (BYTECODE_SPEC.md). */
sealed class ConstantValue {
    data class IntConst(val value: Int) : ConstantValue()
    data class BoolConst(val value: Boolean) : ConstantValue()
    data class StringConst(val value: String) : ConstantValue()
}

/**
 * Одна функция в module-level function table. [instructions] хранит уже полностью разрешённый
 * код (метки переходов заменены на абсолютные byte-offset'ы, см. codegen/Labels.kt) —
 * [BytecodeWriter] лишь сериализует его в байты, не занимаясь резолвом.
 *
 * Методы и функции инициализации классов профиля 2.0 — такие же записи общей таблицы:
 * неявный `this` занимает слот 0 и входит в [paramCount] и [localCount].
 */
data class FunctionEntry(
    val name: String,
    val nameConstIndex: Int,
    val paramCount: Int,
    val localCount: Int,
    val instructions: List<Instruction>,
) {
    /** Длина кода функции в байтах; Long, чтобы сумма не переполнялась до проверки пределов. */
    val codeByteSize: Long get() = instructions.sumOf { it.byteSize.toLong() }
}

/**
 * Объявленный тип поля в ClassTable 2.0: тег `type_tag:u8` и `type_class_idx:u16`. Встроенные
 * типы используют индекс-заглушку [FormatLimits.NO_CLASS_INDEX] (0xFFFF), поле-объект — индекс
 * конкретного класса. Это файловые теги контракта, а не порядок C-перечисления ValueType GVM.
 */
sealed class FieldType(val tag: Int) {
    abstract val classIndex: Int

    data object IntType : FieldType(TAG_INT) {
        override val classIndex = FormatLimits.NO_CLASS_INDEX
    }

    data object BoolType : FieldType(TAG_BOOL) {
        override val classIndex = FormatLimits.NO_CLASS_INDEX
    }

    data object StringType : FieldType(TAG_STRING) {
        override val classIndex = FormatLimits.NO_CLASS_INDEX
    }

    data class ObjectType(override val classIndex: Int) : FieldType(TAG_OBJECT)

    companion object {
        const val TAG_INT = 1
        const val TAG_BOOL = 2
        const val TAG_STRING = 3
        const val TAG_OBJECT = 4
    }
}

/** Поле класса: имя (String-константа) и объявленный тип. Номер поля — позиция в [ClassEntry.fields]. */
data class FieldEntry(
    val name: String,
    val nameConstIndex: Int,
    val type: FieldType,
)

/**
 * Описание класса профиля 2.0 — файловое представление `Object` GVM. Индекс класса — позиция в
 * [BytecodeModule.classes]. [methodFunctionIndices] — индексы методов в общей таблице функций;
 * функция инициализации сюда не входит: её индекс компилятор пишет прямо в CALL.
 */
data class ClassEntry(
    val name: String,
    val nameConstIndex: Int,
    val fields: List<FieldEntry>,
    val methodFunctionIndices: List<Int>,
)

/**
 * In-memory модель всей единицы компиляции — вход для [BytecodeWriter] и [Disassembler].
 *
 * Профиль 1.0 — процедурный: [classes] пуст, объектных инструкций нет. Профиль 2.0 добавляет
 * ClassTable между таблицей функций и кодом и инструкции ALLOC_OBJECT / GET_FIELD / SET_FIELD
 * (docs/backlog/bytecode-contract.md).
 */
data class BytecodeModule(
    val versionMajor: Int,
    val versionMinor: Int,
    val constants: List<ConstantValue>,
    val functions: List<FunctionEntry>,
    val entryFunctionIndex: Int,
    val classes: List<ClassEntry> = emptyList(),
) {
    val isObjectProfile: Boolean get() = versionMajor == OBJECT_VERSION_MAJOR

    companion object {
        /** Процедурный профиль `.albc` 1.0. */
        const val PROCEDURAL_VERSION_MAJOR = 1

        /** Объектный профиль `.albc` 2.0. */
        const val OBJECT_VERSION_MAJOR = 2

        /** Единственная поддерживаемая minor-версия обоих профилей. */
        const val VERSION_MINOR = 0

        /** Поддерживаемые пары (major, minor); всё остальное отклоняется. */
        val SUPPORTED_VERSIONS = setOf(PROCEDURAL_VERSION_MAJOR to VERSION_MINOR, OBJECT_VERSION_MAJOR to VERSION_MINOR)
    }
}
