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

    private fun writeHeader(out: DataOutputStream, module: BytecodeModule) {
        out.writeBytes(MAGIC) // 4 ASCII-байта, ровно как объявлено в заголовке
        out.writeShort(module.versionMajor)
        out.writeShort(module.versionMinor)
        out.writeShort(0) // flags, зарезервировано под v1
        out.writeShort(module.entryFunctionIndex)
    }

    private fun writeConstants(out: DataOutputStream, constants: List<ConstantValue>) {
        out.writeShort(constants.size)
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
                    val bytes = constant.value.toByteArray(Charsets.UTF_8)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
        }
    }

    private fun writeFunctionTable(out: DataOutputStream, functions: List<FunctionEntry>) {
        out.writeShort(functions.size)
        var offset = 0
        for (function in functions) {
            out.writeShort(function.nameConstIndex)
            out.writeByte(function.paramCount)
            out.writeByte(function.localCount)
            out.writeInt(offset)
            out.writeInt(function.codeByteSize)
            offset += function.codeByteSize
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
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun writeInstruction(out: DataOutputStream, instruction: Instruction) {
        out.writeByte(instruction.opcode.code)
        when (instruction) {
            is Instruction.LoadConst -> out.writeShort(instruction.constIndex)
            is Instruction.LoadLocal -> out.writeByte(instruction.slot)
            is Instruction.StoreLocal -> out.writeByte(instruction.slot)
            is Instruction.Jump -> out.writeInt(instruction.target)
            is Instruction.JumpIfFalse -> out.writeInt(instruction.target)
            is Instruction.Call -> out.writeShort(instruction.functionIndex)
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
