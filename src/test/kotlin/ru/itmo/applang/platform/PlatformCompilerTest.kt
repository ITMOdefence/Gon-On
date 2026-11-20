package ru.itmo.applang.platform

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.Arguments
import ru.itmo.applang.bytecode.*
import ru.itmo.applang.compiler.CompilationResult
import ru.itmo.applang.compiler.Compiler
import ru.itmo.applang.compat.Examples
import ru.itmo.applang.compat.ReferenceInterpreter
import ru.itmo.applang.compat.Value

internal fun compilePlatform(source: String): CompilationResult.Success {
    val result = Compiler.compile(source)
    check(result is CompilationResult.Success) { result.diagnostics.joinToString("\n") }
    return result
}

class PlatformCompilerTest {
    @ParameterizedTest
    @MethodSource("operationNames")
    @DisplayName("Каждая операция проверяет арность и тип каждого аргумента")
    fun `all operation arguments reject incorrect types and counts`(name: String) {
        // Arrange
        val operation = PlatformCatalog.find(name)!!
        val parameters = operation.parameters.joinToString { "${it.name}: ${it.type}" }
        val arguments = operation.parameters.map { it.name }
        fun rejectedCall(args: List<String>, diagnostic: String) {
            val source = "fun check($parameters) { $name(${args.joinToString()}); } fun main() {}"
            val result = Compiler.compile(source)
            assertTrue(result is CompilationResult.Failure)
            assertTrue(result.diagnostics.any { it.code == diagnostic }, result.diagnostics.toString())
        }

        // Act / Assert
        val wrongCount = if (arguments.isEmpty()) listOf("1") else arguments.dropLast(1)
        rejectedCall(wrongCount, "ARITY_MISMATCH")
        operation.parameters.forEachIndexed { index, parameter ->
            val wrongType = arguments.toMutableList()
            wrongType[index] = if (parameter.type == ru.itmo.applang.ast.Type.INT) "\"wrong\"" else "1"
            rejectedCall(wrongType, "ARGUMENT_TYPE_MISMATCH")
        }
    }

    @Test
    @DisplayName("Все встроенные классы защищены от создания и записи каждого публичного поля")
    fun `every builtin type is protected at source level`() {
        // Arrange / Act / Assert
        for (type in PlatformAbi.classes) {
            val construction = Compiler.compile("fun main() { ${type.name}(); }")
            assertTrue(construction.diagnostics.any { it.code == "PLATFORM_CONSTRUCTION" }, type.name)
            for ((field, _) in type.fields) {
                val source = "fun check(value: ${type.name}) { value.$field = value.$field; } fun main() {}"
                val assignment = Compiler.compile(source)
                assertTrue(assignment.diagnostics.any { it.code == "PLATFORM_READ_ONLY" }, "${type.name}.$field")
            }
            val oldProfile = compilePlatform("class ${type.name} {} fun main() { ${type.name}(); }")
            assertEquals(2, oldProfile.module.versionMajor, type.name)
        }
    }

    @ParameterizedTest
    @MethodSource("operations")
    @DisplayName("Каждый вызов каталога получает свой контрактный опкод и сохраняет тип результата")
    fun `each catalog operation has its own opcode and typed result`(name: String, code: Int, resultType: String) {
        // Arrange: параметры передаются снаружи, без подделки платформенных ссылок.
        val function = PlatformCatalog.find(name)!!
        val params = function.parameters.joinToString { "${it.name}: ${it.type}" }
        val args = function.parameters.joinToString { it.name }
        val source = "fun check($params): $resultType { return $name($args); } fun main() {}"

        // Act
        val compiled = compilePlatform(source)
        val instructions = compiled.module.functions.first().instructions

        // Assert: номера выписаны независимо от таблицы реализации.
        assertEquals(3, compiled.module.versionMajor)
        assertEquals(code, instructions[instructions.lastIndex - 1].opcode.code)
        assertEquals(listOf(Instruction.Return), instructions.takeLast(1))
        assertEquals(compiled.module, BytecodeReader.readFromBytes(compiled.bytes))
        assertEquals(1, instructions[instructions.lastIndex - 1].byteSize)
        assertEquals(function.parameters.indices.reversed().map { Instruction.LoadLocal(it) }, instructions.dropLast(2))
    }

    @Test
    @DisplayName("Все инструкции каталога и BIND_APP_STATE проходят сериализацию и чтение")
    fun `fixtures cover all platform instructions and event metadata`() {
        // Arrange / Act
        val calls = Examples.compile("platform_calls")
        val app = Examples.compile("platform_app")

        // Assert
        val opcodes = (calls.module.functions + app.module.functions).flatMap { it.instructions }.map { it.opcode }.toSet()
        assertTrue(opcodes.containsAll(Opcode.entries.filter { it.sinceVersionMajor == 3 }))
        assertEquals(13, app.module.events.appStateClassIndex)
        assertEquals(listOf(1, 2, 3, 4), app.module.events.handlers.map { it.eventKind })
        assertEquals(listOf(2, 3, 3, 3), app.module.events.handlers.map { app.module.functions[it.functionIndex].paramCount })
        assertEquals(app.module, BytecodeReader.readFromBytes(app.bytes))
        assertTrue(Disassembler.disassemble(app.module).contains(".events state=13"))
        assertEquals(listOf("WindowRef", "ListenerRef", "ConnectionRef", "PhotoRef", "Location", "Status",
            "WindowResult", "ListenerResult", "ConnectionResult", "PhotoResult", "LocationResult", "TextResult", "IntResult"),
            calls.module.classes.map { it.name })
    }

    @Test
    @DisplayName("Аргументы платформы вычисляются справа налево и передаются host в исходном порядке")
    fun `platform arguments preserve observable evaluation and parameter order`() {
        // Arrange
        val compiled = compilePlatform("""
            fun number(): Int { println(7); return 7; }
            fun label(): String { println("label"); return "Фото"; }
            fun main() {
                val opened = openWindow("test");
                addButton(opened.value, number(), label());
            }
        """.trimIndent())
        val window = Value.ObjectValue(0, emptyArray())
        val calls = mutableListOf<Pair<Instruction, List<Value>>>()

        // Act: тестовый host проверяет мост; он не изображает работающую ОС.
        val execution = ReferenceInterpreter(compiled.module, platformHost = { instruction, args ->
            calls += instruction to args
            when (instruction) {
                Instruction.OpenWindow -> Value.ObjectValue(6, arrayOf(Value.BoolValue(true), Value.IntValue(0), Value.StringValue(""), window))
                Instruction.AddButton -> Value.ObjectValue(5, arrayOf(Value.BoolValue(true), Value.IntValue(0), Value.StringValue("")))
                else -> error("неожиданный вызов $instruction")
            }
        }).run()

        // Assert
        assertEquals("label\n7\n", execution.stdout)
        assertEquals(listOf(window, Value.IntValue(7), Value.StringValue("Фото")), calls.last().second)
        assertEquals(2, calls.size)
    }

    @ParameterizedTest
    @MethodSource("rejections")
    @DisplayName("Платформенные типы, аргументы и сигнатуры событий защищены диагностикой языка")
    fun `invalid platform source has a stable diagnostic`(source: String, code: String) {
        // Act
        val result = Compiler.compile(source)
        // Assert
        assertTrue(result is CompilationResult.Failure)
        assertTrue(result.diagnostics.any { it.code == code }, result.diagnostics.toString())
    }

    @Test
    @DisplayName("Обычные программы сохраняют прежние профили, включая пользовательские имена классов")
    fun `old profiles and nonplatform user types remain supported`() {
        assertEquals(1, compilePlatform("fun main() {}").module.versionMajor)
        assertEquals(2, compilePlatform("class WindowRef {} fun main() { WindowRef(); }").module.versionMajor)
        assertEquals(185, Examples.compile("counter").bytes.size)
        assertEquals(3, compilePlatform("fun helper(result: IntResult): Int { return result.value; } fun main() {}").module.versionMajor)
    }

    companion object {
        @JvmStatic fun operationNames() = PlatformAbi.instructions.keys.toList()

        @JvmStatic fun operations() = listOf(
            Arguments.of("openWindow", 0x50, "WindowResult"), Arguments.of("setText", 0x51, "Status"),
            Arguments.of("addButton", 0x52, "Status"), Arguments.of("addTextInput", 0x53, "Status"),
            Arguments.of("getText", 0x54, "TextResult"), Arguments.of("drawImage", 0x55, "Status"),
            Arguments.of("drawMarker", 0x56, "Status"), Arguments.of("listen", 0x57, "ListenerResult"),
            Arguments.of("connect", 0x58, "ConnectionResult"), Arguments.of("sendText", 0x59, "Status"),
            Arguments.of("closeConnection", 0x5A, "Status"), Arguments.of("closeListener", 0x5B, "Status"),
            Arguments.of("capturePhoto", 0x5C, "PhotoResult"), Arguments.of("uploadPhoto", 0x5D, "TextResult"),
            Arguments.of("getDeviceLocation", 0x5E, "LocationResult"), Arguments.of("releasePhoto", 0x5F, "Status"),
            Arguments.of("readText", 0x60, "TextResult"), Arguments.of("writeText", 0x61, "Status"),
            Arguments.of("formatInt", 0x62, "String"), Arguments.of("parseInt", 0x63, "IntResult"),
            Arguments.of("getField", 0x64, "TextResult"),
        )

        @JvmStatic fun rejections() = listOf(
            Arguments.of("fun main() { openWindow(1); }", "ARGUMENT_TYPE_MISMATCH"),
            Arguments.of("fun main() { addButton(); }", "ARITY_MISMATCH"),
            Arguments.of("fun main() { WindowRef(); }", "PLATFORM_CONSTRUCTION"),
            Arguments.of("fun main() { val r = openWindow(\"x\"); r.ok = false; }", "PLATFORM_READ_ONLY"),
            Arguments.of("class WindowRef {} fun main() { capturePhoto(); }", "DUPLICATE_CLASS"),
            Arguments.of("fun capturePhoto() {} fun main() {}", "NAME_CONFLICT"),
            Arguments.of("fun main() { bindAppState(1); }", "INVALID_APP_STATE"),
            Arguments.of("class AppState {} fun main() { bindAppState(1); }", "ARGUMENT_TYPE_MISMATCH"),
            Arguments.of("class AppState {} fun main() { val x = bindAppState(AppState()); }", "NO_VALUE"),
            Arguments.of("class AppState {} fun onButtonClick(state: AppState) {} fun main() {}", "INVALID_EVENT_HANDLER"),
            Arguments.of("class AppState {} fun onButtonClick(state: AppState, id: Int): Int { return 0; } fun main() {}", "INVALID_EVENT_HANDLER"),
            Arguments.of("fun onButtonClick(state: Int, id: Int) {} fun main() {}", "INVALID_APP_STATE"),
            Arguments.of("class AppState { fun onButtonClick(id: Int) {} } fun main() {}", "INVALID_EVENT_HANDLER"),
            Arguments.of("fun main() { println(capturePhoto()); }", "ARGUMENT_TYPE_MISMATCH"),
            Arguments.of("fun main() { val r = capturePhoto(); val x = r == r; }", "INVALID_OPERAND_TYPE"),
        )
    }
}
