package ru.itmo.applang.compat

import ru.itmo.applang.bytecode.BytecodeModule
import ru.itmo.applang.bytecode.ConstantValue
import ru.itmo.applang.bytecode.FieldType
import ru.itmo.applang.bytecode.Instruction

/** Значение модельной стековой машины. */
sealed class Value {
    data class IntValue(val value: Int) : Value()
    data class BoolValue(val value: Boolean) : Value()
    data class StringValue(val value: String) : Value()

    /** Ссылка на экземпляр: равенство — идентичность объекта, как у ссылки GVM. */
    class ObjectValue(val classIndex: Int, val fields: Array<Value?>) : Value()
}

/** Ошибка исполнения модельной машины — аналог fault'а GVM (деление на 0, незаполненное поле, ...). */
class ExecutionFault(message: String) : Exception(message)

/** Результат запуска: вывод PRINT/PRINTLN и значение, возвращённое точкой входа (null для RETURN_VOID). */
data class ExecutionResult(val stdout: String, val returnValue: Value?)

/**
 * Модельный интерпретатор байткода Gon&On по контракту docs/BYTECODE_SPEC.md и
 * docs/backlog/bytecode-contract.md. Это не GVM и не замена ей: он нужен тестам, чтобы проверять
 * смысл сгенерированного кода (порядок аргументов, `this` в слоте 0, ссылочная семантика,
 * short-circuit), пока настоящая ВМ недоступна. Модуль считается уже проверенным верификатором,
 * поэтому структурные проверки здесь минимальны; динамические проверки GET/SET повторяют GVM.
 */
class ReferenceInterpreter(private val module: BytecodeModule, private val maxSteps: Long = 5_000_000) {
    private val output = StringBuilder()
    private var steps = 0L

    fun run(): ExecutionResult {
        val result = call(module.entryFunctionIndex, emptyList(), depth = 0)
        return ExecutionResult(output.toString(), result)
    }

    private class Frame(localCount: Int) {
        val locals = arrayOfNulls<Value>(localCount)
        val stack = ArrayDeque<Value>()

        fun pop(): Value = stack.removeLastOrNull() ?: throw ExecutionFault("операндный стек пуст")
        fun push(value: Value) = stack.addLast(value)
    }

    /** [arguments] — значения в порядке pop: первый элемент попадает в слот 0. */
    private fun call(functionIndex: Int, arguments: List<Value>, depth: Int): Value? {
        if (depth > MAX_CALL_DEPTH) throw ExecutionFault("превышена глубина вызовов")
        val function = module.functions[functionIndex]
        val frame = Frame(function.localCount)
        arguments.forEachIndexed { slot, value -> frame.locals[slot] = value }

        val offsets = IntArray(function.instructions.size)
        var offset = 0
        function.instructions.forEachIndexed { i, instruction ->
            offsets[i] = offset
            offset += instruction.byteSize
        }
        fun indexOf(target: Int) = offsets.binarySearch(target).also { require(it >= 0) { "цель $target" } }

        var ip = 0
        while (true) {
            if (++steps > maxSteps) throw ExecutionFault("превышен лимит шагов модельной машины")
            val instruction = function.instructions.getOrNull(ip) ?: throw ExecutionFault("выход за конец функции")
            ip++
            when (instruction) {
                is Instruction.LoadConst -> frame.push(constant(instruction.constIndex))
                is Instruction.LoadLocal -> frame.push(
                    frame.locals[instruction.slot] ?: throw ExecutionFault("чтение незаполненного слота ${instruction.slot}"),
                )
                is Instruction.StoreLocal -> frame.locals[instruction.slot] = frame.pop()
                Instruction.Add -> binaryInt(frame) { a, b -> a + b }
                Instruction.Sub -> binaryInt(frame) { a, b -> a - b }
                Instruction.Mul -> binaryInt(frame) { a, b -> a * b }
                Instruction.Div -> binaryInt(frame) { a, b -> if (b == 0) throw ExecutionFault("деление на 0") else a / b }
                Instruction.Mod -> binaryInt(frame) { a, b -> if (b == 0) throw ExecutionFault("деление на 0") else a % b }
                Instruction.Neg -> frame.push(Value.IntValue(-int(frame.pop())))
                Instruction.Concat -> {
                    val right = string(frame.pop())
                    frame.push(Value.StringValue(string(frame.pop()) + right))
                }
                Instruction.CmpEq -> frame.push(Value.BoolValue(equal(frame.pop(), frame.pop())))
                Instruction.CmpNe -> frame.push(Value.BoolValue(!equal(frame.pop(), frame.pop())))
                Instruction.CmpLt -> compare(frame) { a, b -> a < b }
                Instruction.CmpGt -> compare(frame) { a, b -> a > b }
                Instruction.CmpLe -> compare(frame) { a, b -> a <= b }
                Instruction.CmpGe -> compare(frame) { a, b -> a >= b }
                Instruction.Not -> frame.push(Value.BoolValue(!bool(frame.pop())))
                is Instruction.Jump -> ip = indexOf(instruction.target)
                is Instruction.JumpIfFalse -> if (!bool(frame.pop())) ip = indexOf(instruction.target)
                is Instruction.Call -> {
                    val callee = module.functions[instruction.functionIndex]
                    val args = List(callee.paramCount) { frame.pop() }
                    call(instruction.functionIndex, args, depth + 1)?.let { frame.push(it) }
                }
                Instruction.Return -> return frame.pop()
                Instruction.ReturnVoid -> return null
                Instruction.Print -> output.append(format(frame.pop()))
                Instruction.Println -> output.append(format(frame.pop())).append('\n')
                Instruction.Pop -> frame.pop()
                is Instruction.AllocObject -> {
                    val classEntry = module.classes[instruction.classIndex]
                    frame.push(Value.ObjectValue(instruction.classIndex, arrayOfNulls(classEntry.fields.size)))
                }
                is Instruction.GetField -> {
                    val receiver = receiver(frame.pop(), instruction.classIndex)
                    frame.push(
                        receiver.fields[instruction.fieldIndex]
                            ?: throw ExecutionFault("UNINITIALIZED: чтение незаполненного поля #${instruction.fieldIndex}"),
                    )
                }
                is Instruction.SetField -> {
                    val value = frame.pop()
                    val receiver = receiver(frame.pop(), instruction.classIndex)
                    val declared = module.classes[instruction.classIndex].fields[instruction.fieldIndex].type
                    if (!matches(value, declared)) throw ExecutionFault("TYPE: значение не соответствует типу поля")
                    receiver.fields[instruction.fieldIndex] = value
                }
            }
        }
    }

    private fun constant(index: Int): Value = when (val constant = module.constants[index]) {
        is ConstantValue.IntConst -> Value.IntValue(constant.value)
        is ConstantValue.BoolConst -> Value.BoolValue(constant.value)
        is ConstantValue.StringConst -> Value.StringValue(constant.value)
    }

    private fun receiver(value: Value, classIndex: Int): Value.ObjectValue {
        if (value !is Value.ObjectValue || value.classIndex != classIndex) {
            throw ExecutionFault("TYPE: receiver не является экземпляром класса #$classIndex")
        }
        return value
    }

    private fun matches(value: Value, type: FieldType): Boolean = when (type) {
        FieldType.IntType -> value is Value.IntValue
        FieldType.BoolType -> value is Value.BoolValue
        FieldType.StringType -> value is Value.StringValue
        is FieldType.ObjectType -> value is Value.ObjectValue && value.classIndex == type.classIndex
    }

    private inline fun binaryInt(frame: Frame, operation: (Int, Int) -> Int) {
        val right = int(frame.pop())
        frame.push(Value.IntValue(operation(int(frame.pop()), right)))
    }

    private inline fun compare(frame: Frame, operation: (Int, Int) -> Boolean) {
        val right = int(frame.pop())
        frame.push(Value.BoolValue(operation(int(frame.pop()), right)))
    }

    private fun equal(right: Value, left: Value): Boolean = when {
        left is Value.ObjectValue || right is Value.ObjectValue -> throw ExecutionFault("CMP_EQ для объектов не определён")
        else -> left == right
    }

    private fun int(value: Value) = (value as? Value.IntValue)?.value ?: throw ExecutionFault("ожидался Int, получен $value")
    private fun bool(value: Value) = (value as? Value.BoolValue)?.value ?: throw ExecutionFault("ожидался Bool, получен $value")
    private fun string(value: Value) = (value as? Value.StringValue)?.value ?: throw ExecutionFault("ожидалась String, получен $value")

    private fun format(value: Value): String = when (value) {
        is Value.IntValue -> value.value.toString()
        is Value.BoolValue -> value.value.toString()
        is Value.StringValue -> value.value
        is Value.ObjectValue -> throw ExecutionFault("PRINT для объектов не определён")
    }

    private companion object {
        const val MAX_CALL_DEPTH = 1_000
    }
}
