package ru.itmo.applang.platform

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import ru.itmo.applang.bytecode.Instruction
import ru.itmo.applang.compat.ExecutionFault
import ru.itmo.applang.compat.ReferenceInterpreter
import ru.itmo.applang.compat.Value

class PlatformResultTest {
    @ParameterizedTest
    @MethodSource("results")
    @DisplayName("Ошибка каждого типа результата доступна через общие поля и обычный if")
    fun `failed results preserve error fields and continue execution`(type: String, index: Int, call: String) {
        // Arrange
        val compiled = compilePlatform("""
            fun main(): Int {
                val result: $type = $call;
                if (result.ok) { return 99; }
                println(result.errorMessage);
                return result.errorCode;
            }
        """.trimIndent())
        val fields = mutableListOf<Value?>(Value.BoolValue(false), Value.IntValue(9), Value.StringValue("Таймаут"))
        if (type != "Status") fields += null

        // Act: host supplies a contract-shaped failure without accessing devices.
        val execution = ReferenceInterpreter(compiled.module, platformHost = { _, _ ->
            Value.ObjectValue(index, fields.toTypedArray())
        }).run()

        // Assert
        assertEquals("Таймаут\n", execution.stdout)
        assertEquals(Value.IntValue(9), execution.returnValue)
        val reads = compiled.module.functions.single().instructions.filterIsInstance<Instruction.GetField>()
        assertEquals(listOf(0, 2, 1), reads.map { it.fieldIndex })
        assertTrue(reads.all { it.classIndex == index })
    }

    @ParameterizedTest
    @MethodSource("valueResults")
    @DisplayName("Чтение value ошибочного результата любого типа завершается UNINITIALIZED")
    fun `failed value has no default`(type: String, index: Int, call: String) {
        // Arrange: compilation is allowed without a preceding ok check.
        val compiled = compilePlatform("fun main() { val result: $type = $call; result.value; }")
        val failure = Value.ObjectValue(index, arrayOf(
            Value.BoolValue(false), Value.IntValue(9), Value.StringValue("Таймаут"), null,
        ))

        // Act
        val fault = assertThrows(ExecutionFault::class.java) {
            ReferenceInterpreter(compiled.module, platformHost = { _, _ -> failure }).run()
        }

        // Assert
        assertTrue(fault.message!!.startsWith("UNINITIALIZED:"))
        assertTrue(compiled.module.functions.single().instructions.contains(Instruction.GetField(index, 3)))
    }

    @ParameterizedTest
    @MethodSource("values")
    @DisplayName("Успешный результат сохраняет тип и значение value, включая ноль и пустую строку")
    fun `successful value preserves its type and identity`(type: String, index: Int, call: String, value: Value) {
        // Arrange
        val returnType = valueType(type)
        val compiled = compilePlatform("""
            fun main(): $returnType {
                val result: $type = $call;
                println(result.ok);
                println(result.errorCode);
                println(result.errorMessage);
                return result.value;
            }
        """.trimIndent())
        val result = Value.ObjectValue(index, arrayOf(
            Value.BoolValue(true), Value.IntValue(0), Value.StringValue(""), value,
        ))

        // Act
        val execution = ReferenceInterpreter(compiled.module, platformHost = { _, _ -> result }).run()

        // Assert
        assertEquals("true\n0\n\n", execution.stdout)
        assertSame(value, execution.returnValue)
    }

    companion object {
        private val resultCases = listOf(
            Triple("Status", 5, "writeText(\"state.txt\", \"text\")"),
            Triple("WindowResult", 6, "openWindow(\"test\")"),
            Triple("ListenerResult", 7, "listen(9000)"),
            Triple("ConnectionResult", 8, "connect(\"127.0.0.1\", 9000)"),
            Triple("PhotoResult", 9, "capturePhoto()"),
            Triple("LocationResult", 10, "getDeviceLocation()"),
            Triple("TextResult", 11, "readText(\"state.txt\")"),
            Triple("IntResult", 12, "parseInt(\"0\")"),
        )

        @JvmStatic fun results() = resultCases.map { (type, index, call) -> Arguments.of(type, index, call) }

        @JvmStatic fun valueResults() = resultCases.drop(1).map { (type, index, call) -> Arguments.of(type, index, call) }

        @JvmStatic fun values() = resultCases.drop(1).map { (type, index, call) ->
            val value = when (type) {
                "WindowResult" -> Value.ObjectValue(0, emptyArray())
                "ListenerResult" -> Value.ObjectValue(1, emptyArray())
                "ConnectionResult" -> Value.ObjectValue(2, emptyArray())
                "PhotoResult" -> Value.ObjectValue(3, emptyArray())
                "LocationResult" -> Value.ObjectValue(4, arrayOf(Value.IntValue(59_934_300), Value.IntValue(30_335_100)))
                "TextResult" -> Value.StringValue("")
                "IntResult" -> Value.IntValue(0)
                else -> error(type)
            }
            Arguments.of(type, index, call, value)
        }

        private fun valueType(type: String): String = when (type) {
            "TextResult" -> "String"
            "IntResult" -> "Int"
            "LocationResult" -> "Location"
            else -> type.removeSuffix("Result") + "Ref"
        }
    }
}
