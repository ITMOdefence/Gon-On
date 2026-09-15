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
 */
data class FunctionEntry(
    val name: String,
    val nameConstIndex: Int,
    val paramCount: Int,
    val localCount: Int,
    val instructions: List<Instruction>,
) {
    val codeByteSize: Int get() = instructions.sumOf { it.byteSize }
}

/** In-memory модель всей единицы компиляции — вход для [BytecodeWriter] и [Disassembler]. */
data class BytecodeModule(
    val versionMajor: Int,
    val versionMinor: Int,
    val constants: List<ConstantValue>,
    val functions: List<FunctionEntry>,
    val entryFunctionIndex: Int,
) {
    companion object {
        const val CURRENT_VERSION_MAJOR = 1
        const val CURRENT_VERSION_MINOR = 0
    }
}
