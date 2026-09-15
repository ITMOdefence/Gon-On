package ru.itmo.applang.bytecode

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Разбирает бинарный `.albc` обратно в [BytecodeModule]. Не используется рантаймом (рантайм —
 * это будущая ВМ на C/C++), но служит reference-реализацией формата: round-trip тесты
 * (write -> read -> сравнить) и [Disassembler] на уже сериализованном файле используют именно её.
 */
object BytecodeReader {
    private const val TAG_INT = 1
    private const val TAG_BOOL = 2
    private const val TAG_STRING = 3

    class MalformedBytecodeException(message: String) : Exception(message)

    fun readFromFile(path: Path): BytecodeModule = readFromBytes(Files.readAllBytes(path))

    fun readFromBytes(bytes: ByteArray): BytecodeModule {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        try {
            val (versionMajor, versionMinor, entryFunctionIndex) = readHeader(input)
            val constants = readConstants(input)
            val functionHeaders = readFunctionTable(input)
            val codeSize = input.readInt()
            val codeBytes = ByteArray(codeSize)
            input.readFully(codeBytes)

            val functions = functionHeaders.map { header ->
                val instructions = decodeInstructions(codeBytes, header.codeOffset, header.codeLength)
                FunctionEntry(
                    name = (constants[header.nameConstIndex] as ConstantValue.StringConst).value,
                    nameConstIndex = header.nameConstIndex,
                    paramCount = header.paramCount,
                    localCount = header.localCount,
                    instructions = instructions,
                )
            }

            return BytecodeModule(versionMajor, versionMinor, constants, functions, entryFunctionIndex)
        } catch (e: EOFException) {
            throw MalformedBytecodeException("неожиданный конец файла: ${e.message}")
        }
    }

    private data class Header(val versionMajor: Int, val versionMinor: Int, val entryFunctionIndex: Int)

    private fun readHeader(input: DataInputStream): Header {
        val magicBytes = ByteArray(4)
        input.readFully(magicBytes)
        val magic = String(magicBytes, Charsets.US_ASCII)
        if (magic != BytecodeWriter.MAGIC) {
            throw MalformedBytecodeException("неверная сигнатура файла: '$magic', ожидалось '${BytecodeWriter.MAGIC}'")
        }
        val versionMajor = input.readUnsignedShort()
        val versionMinor = input.readUnsignedShort()
        input.readUnsignedShort() // flags, зарезервировано
        val entryFunctionIndex = input.readUnsignedShort()
        if (versionMajor != BytecodeModule.CURRENT_VERSION_MAJOR) {
            throw MalformedBytecodeException(
                "неподдерживаемая мажорная версия формата: $versionMajor " +
                    "(поддерживается ${BytecodeModule.CURRENT_VERSION_MAJOR})",
            )
        }
        return Header(versionMajor, versionMinor, entryFunctionIndex)
    }

    private fun readConstants(input: DataInputStream): List<ConstantValue> {
        val count = input.readUnsignedShort()
        return (0 until count).map {
            when (val tag = input.readUnsignedByte()) {
                TAG_INT -> ConstantValue.IntConst(input.readInt())
                TAG_BOOL -> ConstantValue.BoolConst(input.readByte().toInt() != 0)
                TAG_STRING -> {
                    val length = input.readInt()
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    ConstantValue.StringConst(String(bytes, Charsets.UTF_8))
                }
                else -> throw MalformedBytecodeException("неизвестный тег константы: $tag")
            }
        }
    }

    private data class FunctionHeader(
        val nameConstIndex: Int,
        val paramCount: Int,
        val localCount: Int,
        val codeOffset: Int,
        val codeLength: Int,
    )

    private fun readFunctionTable(input: DataInputStream): List<FunctionHeader> {
        val count = input.readUnsignedShort()
        return (0 until count).map {
            FunctionHeader(
                nameConstIndex = input.readUnsignedShort(),
                paramCount = input.readUnsignedByte(),
                localCount = input.readUnsignedByte(),
                codeOffset = input.readInt(),
                codeLength = input.readInt(),
            )
        }
    }

    private fun decodeInstructions(codeBytes: ByteArray, offset: Int, length: Int): List<Instruction> {
        val input = DataInputStream(ByteArrayInputStream(codeBytes, offset, length))
        val result = mutableListOf<Instruction>()
        var consumed = 0
        while (consumed < length) {
            val opcodeByte = input.readUnsignedByte()
            consumed += 1
            val opcode = Opcode.fromCode(opcodeByte)
            val instruction: Instruction = when (opcode) {
                Opcode.LOAD_CONST -> Instruction.LoadConst(input.readUnsignedShort().also { consumed += 2 })
                Opcode.LOAD_LOCAL -> Instruction.LoadLocal(input.readUnsignedByte().also { consumed += 1 })
                Opcode.STORE_LOCAL -> Instruction.StoreLocal(input.readUnsignedByte().also { consumed += 1 })
                Opcode.ADD -> Instruction.Add
                Opcode.SUB -> Instruction.Sub
                Opcode.MUL -> Instruction.Mul
                Opcode.DIV -> Instruction.Div
                Opcode.MOD -> Instruction.Mod
                Opcode.NEG -> Instruction.Neg
                Opcode.CONCAT -> Instruction.Concat
                Opcode.CMP_EQ -> Instruction.CmpEq
                Opcode.CMP_NE -> Instruction.CmpNe
                Opcode.CMP_LT -> Instruction.CmpLt
                Opcode.CMP_GT -> Instruction.CmpGt
                Opcode.CMP_LE -> Instruction.CmpLe
                Opcode.CMP_GE -> Instruction.CmpGe
                Opcode.NOT -> Instruction.Not
                Opcode.JUMP -> Instruction.Jump(input.readInt().also { consumed += 4 })
                Opcode.JUMP_IF_FALSE -> Instruction.JumpIfFalse(input.readInt().also { consumed += 4 })
                Opcode.CALL -> Instruction.Call(input.readUnsignedShort().also { consumed += 2 })
                Opcode.RETURN -> Instruction.Return
                Opcode.RETURN_VOID -> Instruction.ReturnVoid
                Opcode.PRINT -> Instruction.Print
                Opcode.PRINTLN -> Instruction.Println
                Opcode.POP -> Instruction.Pop
            }
            result += instruction
        }
        return result
    }
}
