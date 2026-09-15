package ru.itmo.applang.codegen

import ru.itmo.applang.bytecode.ConstantValue

/** Строит constant pool модуля с дедупликацией — одинаковая константа получает один и тот же индекс. */
class ConstantPoolBuilder {
    private val constants = mutableListOf<ConstantValue>()
    private val indexByValue = mutableMapOf<ConstantValue, Int>()

    fun intConst(value: Int): Int = intern(ConstantValue.IntConst(value))
    fun boolConst(value: Boolean): Int = intern(ConstantValue.BoolConst(value))
    fun stringConst(value: String): Int = intern(ConstantValue.StringConst(value))

    private fun intern(value: ConstantValue): Int =
        indexByValue.getOrPut(value) {
            constants += value
            constants.size - 1
        }

    fun build(): List<ConstantValue> = constants.toList()
}
