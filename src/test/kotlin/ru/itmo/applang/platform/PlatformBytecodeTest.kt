package ru.itmo.applang.platform

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.itmo.applang.bytecode.*
import ru.itmo.applang.compat.Examples

class PlatformBytecodeTest {
    private fun rejected(module: BytecodeModule, issue: BytecodeIssue) {
        assertEquals(issue, assertThrows(BytecodeFormatException::class.java) { BytecodeVerifier.verify(module) }.issue)
        assertEquals(issue, assertThrows(BytecodeFormatException::class.java) { BytecodeWriter.writeToBytes(module) }.issue)
        if (!module.isPlatformProfile && module.events != EventTable()) return
        assertEquals(issue, assertThrows(BytecodeFormatException::class.java) {
            BytecodeReader.readFromBytes(BytecodeWriter.serialize(module))
        }.issue)
    }

    @Test
    @DisplayName("Встроенные классы нельзя подменять, создавать и изменять даже в недостижимом коде")
    fun `builtin classes cannot be forged or modified`() {
        // Arrange
        val module = Examples.compile("platform_app").module
        val prefix = module.classes.take(13)

        // Act / Assert
        rejected(module.copy(classes = module.classes.dropLast(module.classes.size)), BytecodeIssue.PLATFORM_TYPE)
        prefix.forEachIndexed { index, cls ->
            val changed = module.classes.toMutableList()
            changed[index] = cls.copy(fields = cls.fields + FieldEntry(cls.name, cls.nameConstIndex, FieldType.IntType))
            rejected(module.copy(classes = changed), BytecodeIssue.PLATFORM_TYPE)
        }
        val changed = module.classes.toMutableList()
        changed[5] = changed[5].copy(fields = emptyList())
        rejected(module.copy(classes = changed), BytecodeIssue.PLATFORM_TYPE)
        val main = module.functions[module.entryFunctionIndex]
        for (instruction in listOf(Instruction.AllocObject(0), Instruction.SetField(5, 0))) {
            rejected(module.copy(functions = module.functions.toMutableList().also {
                it[module.entryFunctionIndex] = main.copy(instructions = listOf(Instruction.ReturnVoid, instruction))
            }), BytecodeIssue.PLATFORM_TYPE)
        }
    }

    @Test
    @DisplayName("EventTable проверяет kind, порядок, уникальность, индексы и сигнатуру обработчика")
    fun `event table rejects inconsistent handler metadata`() {
        // Arrange
        val module = Examples.compile("platform_app").module
        val events = module.events
        val handlers = events.handlers

        // Act / Assert
        val badTables = listOf(
            events.copy(appStateClassIndex = 0), events.copy(appStateClassIndex = -1),
            events.copy(appStateClassIndex = 0xFFFF),
            events.copy(handlers = handlers.reversed()),
            events.copy(handlers = listOf(handlers[0], handlers[0])),
            events.copy(handlers = listOf(handlers[0].copy(eventKind = 5))),
            events.copy(handlers = listOf(handlers[0].copy(functionIndex = 0xFFFF))),
            events.copy(handlers = listOf(handlers[0].copy(functionIndex = module.entryFunctionIndex))),
            events.copy(handlers = listOf(handlers[0], handlers[1].copy(functionIndex = handlers[0].functionIndex))),
            events.copy(handlers = listOf(handlers[0].copy(functionIndex = handlers[1].functionIndex))),
        )
        for (table in badTables) {
            // Отрицательное u16 не сериализуется; проверка модели всё равно обязана отказать.
            if (table.appStateClassIndex < 0) {
                assertEquals(BytecodeIssue.EVENT_TABLE, assertThrows(BytecodeFormatException::class.java) {
                    BytecodeVerifier.verify(module.copy(events = table))
                }.issue)
            } else rejected(module.copy(events = table), BytecodeIssue.EVENT_TABLE)
        }
        val handlerIndex = handlers.first().functionIndex
        rejected(module.copy(functions = module.functions.toMutableList().also {
            it[handlerIndex] = it[handlerIndex].copy(instructions = listOf(Instruction.LoadLocal(0), Instruction.Return))
        }), BytecodeIssue.EVENT_TABLE)
    }

    @Test
    @DisplayName("Байты EventTable имеют независимую big-endian раскладку и строго проверяются")
    fun `event bytes use the specified layout and reject truncation`() {
        // Arrange
        val compiled = Examples.compile("platform_app")
        val module = compiled.module
        val codeSize = module.functions.sumOf { it.codeByteSize }.toInt()
        val eventStart = compiled.bytes.size - codeSize - 4 - 16
        val handlers = module.events.handlers
        val expected = byteArrayOf(0, 13, 0, 4) + handlers.flatMap {
            listOf(it.eventKind.toByte(), (it.functionIndex ushr 8).toByte(), it.functionIndex.toByte())
        }.toByteArray()

        // Assert
        assertArrayEquals(expected, compiled.bytes.copyOfRange(eventStart, eventStart + 16))
        for (length in 0 until 16) {
            assertEquals(BytecodeIssue.TRUNCATED, assertThrows(BytecodeFormatException::class.java) {
                BytecodeReader.readFromBytes(compiled.bytes.copyOf(eventStart + length))
            }.issue)
        }
        val badCount = compiled.bytes.copyOf().also { it[eventStart + 2] = -1; it[eventStart + 3] = -1 }
        assertEquals(BytecodeIssue.EVENT_TABLE, assertThrows(BytecodeFormatException::class.java) {
            BytecodeReader.readFromBytes(badCount)
        }.issue)
    }

    @Test
    @DisplayName("Новые инструкции не допускаются в старых профилях, а их стековый эффект проверяется")
    fun `platform opcodes require profile three and correct stack heights`() {
        val old = Examples.compile("hello_world").module
        rejected(old.copy(functions = old.functions.map {
            it.copy(instructions = listOf(Instruction.CapturePhoto, Instruction.Pop, Instruction.ReturnVoid))
        }), BytecodeIssue.OPCODE)
        rejected(old.copy(events = EventTable(0)), BytecodeIssue.LAYOUT)

        val module = Examples.compile("platform_calls").module
        val mainIndex = module.entryFunctionIndex
        for (operation in PlatformCatalog.functions) {
            if (operation.parameters.isEmpty()) continue
            rejected(module.copy(functions = module.functions.toMutableList().also {
                it[mainIndex] = it[mainIndex].copy(instructions = listOf(operation.instruction, Instruction.Pop, Instruction.ReturnVoid))
            }), BytecodeIssue.STACK_UNDERFLOW)
        }
        rejected(module.copy(functions = module.functions.toMutableList().also {
            it[mainIndex] = it[mainIndex].copy(instructions = listOf(Instruction.BindAppState, Instruction.ReturnVoid))
        }), BytecodeIssue.EVENT_TABLE)
    }
}
