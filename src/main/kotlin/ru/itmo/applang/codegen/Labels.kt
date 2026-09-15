package ru.itmo.applang.codegen

import ru.itmo.applang.bytecode.Instruction

/**
 * Буфер инструкций одной функции с поддержкой меток для if/while: `emitJump`/`emitJumpIfFalse`
 * ссылаются на метку по её id до того, как известен её финальный byte-offset, `placeLabel`
 * фиксирует позицию метки в потоке инструкций. [build] разрешает все метки в абсолютные
 * byte-offset'ы — именно так, как их ожидает [Instruction.Jump]/[Instruction.JumpIfFalse]
 * согласно docs/BYTECODE_SPEC.md.
 */
class InstructionBuffer {
    private val instructions = mutableListOf<Instruction>()
    private val labelPositions = mutableMapOf<Int, Int>() // labelId -> индекс инструкции
    private val jumpLabelRefs = mutableMapOf<Int, Int>() // индекс инструкции-перехода -> labelId
    private var nextLabelId = 0

    fun newLabel(): Int = nextLabelId++

    fun placeLabel(label: Int) {
        check(label !in labelPositions) { "метка $label уже размещена" }
        labelPositions[label] = instructions.size
    }

    fun emit(instruction: Instruction) {
        instructions += instruction
    }

    fun emitJump(label: Int) {
        jumpLabelRefs[instructions.size] = label
        instructions += Instruction.Jump(UNRESOLVED)
    }

    fun emitJumpIfFalse(label: Int) {
        jumpLabelRefs[instructions.size] = label
        instructions += Instruction.JumpIfFalse(UNRESOLVED)
    }

    /** Разрешает все метки и возвращает финальный список инструкций функции. */
    fun build(): List<Instruction> {
        val offsets = IntArray(instructions.size + 1)
        for (i in instructions.indices) {
            offsets[i + 1] = offsets[i] + instructions[i].byteSize
        }
        return instructions.mapIndexed { index, instruction ->
            val labelId = jumpLabelRefs[index] ?: return@mapIndexed instruction
            val targetOffset = offsets[labelPositions.getValue(labelId)]
            when (instruction) {
                is Instruction.Jump -> Instruction.Jump(targetOffset)
                is Instruction.JumpIfFalse -> Instruction.JumpIfFalse(targetOffset)
                else -> instruction
            }
        }
    }

    private companion object {
        const val UNRESOLVED = -1
    }
}
