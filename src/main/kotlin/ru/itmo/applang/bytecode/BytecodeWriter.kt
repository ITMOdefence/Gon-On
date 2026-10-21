package ru.itmo.applang.bytecode

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Сериализует [BytecodeModule] в бинарный формат `.albc`, описанный в docs/BYTECODE_SPEC.md.
 * Формат — big-endian (сетевой порядок байт), что совпадает с поведением [DataOutputStream]
 * "из коробки", поэтому явного byte-swap здесь нет.
 */
object BytecodeWriter {
    const val MAGIC = "ALBC"

    private const val TAG_INT = 1
    private const val TAG_BOOL = 2
    private const val TAG_STRING = 3

    fun writeToBytes(module: BytecodeModule): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            writeHeader(out, module)
            writeConstants(out, module.constants)
            writeFunctionTable(out, module.functions)
            writeCodeSection(out, module.functions)
        }
        return buffer.toByteArray()
    }

    fun writeToFile(module: BytecodeModule, path: Path) {
        Files.write(path, writeToBytes(module))
    }

    private fun checkedCodeSize(size: Long): Long {
        if (size > FormatLimits.MAX_CODE_BYTES) {
            throw BytecodeFormatException(
                BytecodeIssue.RESOURCE_LIMIT,
                "размер кода $size байт превышает предел реализации компилятора ${FormatLimits.MAX_CODE_BYTES}",
            )
        }
        return size
    }

    // Запись полей с проверкой разрядности: значение, не помещающееся в поле, — ошибка, а не
    // молчаливое усечение младших битов (например, local_count = 256 не может стать 0).

    private fun DataOutputStream.writeU8(value: Int, field: String) {
        checkRange(value.toLong(), FormatLimits.MAX_U8.toLong(), field)
        writeByte(value)
    }

    private fun DataOutputStream.writeU16(value: Int, field: String) {
        checkRange(value.toLong(), FormatLimits.MAX_U16.toLong(), field)
        writeShort(value)
    }

    private fun DataOutputStream.writeU32(value: Long, field: String) {
        checkRange(value, FormatLimits.MAX_U32, field)
        writeInt(value.toInt())
    }

    private fun checkRange(value: Long, max: Long, field: String) {
        if (value < 0 || value > max) {
            throw BytecodeFormatException(BytecodeIssue.LAYOUT, "$field = $value не помещается в поле (0..$max)")
        }
    }

    private fun writeHeader(out: DataOutputStream, module: BytecodeModule) {
        out.writeBytes(MAGIC) // 4 ASCII-байта, ровно как объявлено в заголовке
        out.writeU16(module.versionMajor, "version_major")
        out.writeU16(module.versionMinor, "version_minor")
        out.writeU16(0, "flags") // flags, в профилях 1.0 и 2.0 всегда 0
        out.writeU16(module.entryFunctionIndex, "entry_function_idx")
    }

    private fun writeConstants(out: DataOutputStream, constants: List<ConstantValue>) {
        out.writeU16(constants.size, "constant_count")
        for (constant in constants) {
            when (constant) {
                is ConstantValue.IntConst -> {
                    out.writeByte(TAG_INT)
                    out.writeInt(constant.value)
                }
                is ConstantValue.BoolConst -> {
                    out.writeByte(TAG_BOOL)
                    out.writeByte(if (constant.value) 1 else 0)
                }
                is ConstantValue.StringConst -> {
                    out.writeByte(TAG_STRING)
                    val bytes = StringConstraints.encode(constant.value)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
        }
    }

    private fun writeFunctionTable(out: DataOutputStream, functions: List<FunctionEntry>) {
        out.writeU16(functions.size, "function_count")
        var offset = 0L
        for (function in functions) {
            out.writeU16(function.nameConstIndex, "name_const_idx функции '${function.name}'")
            out.writeU8(function.paramCount, "param_count функции '${function.name}'")
            out.writeU8(function.localCount, "local_count функции '${function.name}'")
            out.writeU32(offset, "code_offset функции '${function.name}'")
            out.writeU32(function.codeByteSize, "code_length функции '${function.name}'")
            offset = checkedCodeSize(offset + function.codeByteSize)
        }
    }

    private fun writeCodeSection(out: DataOutputStream, functions: List<FunctionEntry>) {
        val codeBytes = ByteArrayOutputStream()
        DataOutputStream(codeBytes).use { code ->
            for (function in functions) {
                for (instruction in function.instructions) {
                    writeInstruction(code, instruction)
                }
            }
        }
        val bytes = codeBytes.toByteArray()
        val declaredSize = functions.sumOf { it.codeByteSize }
        if (bytes.size.toLong() != declaredSize) {
            throw BytecodeFormatException(
                BytecodeIssue.LAYOUT,
                "записано ${bytes.size} байт кода, а сумма длин функций равна $declaredSize",
            )
        }
        out.writeU32(bytes.size.toLong(), "code_size")
        out.write(bytes)
    }

    private fun writeInstruction(out: DataOutputStream, instruction: Instruction) {
        out.writeByte(instruction.opcode.code)
        when (instruction) {
            is Instruction.LoadConst -> out.writeU16(instruction.constIndex, "индекс константы LOAD_CONST")
            is Instruction.LoadLocal -> out.writeU8(instruction.slot, "слот LOAD_LOCAL")
            is Instruction.StoreLocal -> out.writeU8(instruction.slot, "слот STORE_LOCAL")
            is Instruction.Jump -> out.writeU32(instruction.target.toLong(), "цель JUMP")
            is Instruction.JumpIfFalse -> out.writeU32(instruction.target.toLong(), "цель JUMP_IF_FALSE")
            is Instruction.Call -> out.writeU16(instruction.functionIndex, "индекс функции CALL")
            // Инструкции без операндов: только что записанного байта опкода достаточно.
            Instruction.Add, Instruction.Sub, Instruction.Mul, Instruction.Div, Instruction.Mod,
            Instruction.Neg, Instruction.Concat, Instruction.CmpEq, Instruction.CmpNe,
            Instruction.CmpLt, Instruction.CmpGt, Instruction.CmpLe, Instruction.CmpGe,
            Instruction.Not, Instruction.Return, Instruction.ReturnVoid, Instruction.Print,
            Instruction.Println, Instruction.Pop,
                -> Unit
        }
    }
}
