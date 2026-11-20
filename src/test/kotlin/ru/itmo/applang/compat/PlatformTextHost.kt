package ru.itmo.applang.compat

import ru.itmo.applang.bytecode.Instruction

/** Детерминированная модель трёх текстовых операций для проверки байткода corpus. */
internal object PlatformTextHost {
    fun invoke(instruction: Instruction, arguments: List<Value>): Value = when (instruction) {
        Instruction.FormatInt -> Value.StringValue((arguments[0] as Value.IntValue).value.toString())
        Instruction.ParseInt -> {
            val text = (arguments[0] as Value.StringValue).value
            if (!text.matches(Regex("[+-]?[0-9]+"))) {
                result(12, null, 1)
            } else {
                val number = text.toIntOrNull()
                if (number == null) result(12, null, 13) else result(12, Value.IntValue(number), 0)
            }
        }
        Instruction.GetTextField -> {
            val text = (arguments[0] as Value.StringValue).value
            val separator = (arguments[1] as Value.StringValue).value
            val index = (arguments[2] as Value.IntValue).value
            if (separator.isEmpty() || index < 0) {
                result(11, null, 1)
            } else {
                val field = text.split(separator).getOrNull(index)
                if (field == null) result(11, null, 5) else result(11, Value.StringValue(field), 0)
            }
        }
        else -> throw ExecutionFault("для ${instruction.opcode} требуется отдельный тестовый host")
    }

    private fun result(classIndex: Int, value: Value?, errorCode: Int) = Value.ObjectValue(classIndex, arrayOf(
        Value.BoolValue(errorCode == 0),
        Value.IntValue(errorCode),
        Value.StringValue(if (errorCode == 0) "" else "Ошибка операции"),
        value,
    ))
}
