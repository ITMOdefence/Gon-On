package ru.itmo.applang.compat

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.applang.bytecode.COUNTER_REFERENCE_BYTES
import java.nio.file.Files

/**
 * Сверяет реальные байты компилятора с независимыми ожиданиями: эталоном из
 * docs/backlog/bytecode-contract.md §11 и зафиксированными дампами `examples/<name>.albc.hex`.
 * Round-trip через собственные writer и reader такую проверку не заменяет: симметричная ошибка
 * обоих пережила бы его.
 */
class ContractGoldenBytesTest {

    @Test
    @DisplayName("examples/counter.al компилируется ровно в 185 байт эталона контракта 2.0")
    fun `counter example compiles to the exact contract reference bytes`() {
        // Act
        val bytes = Examples.compile("counter").bytes

        // Assert
        assertEquals(185, bytes.size)
        assertEquals(COUNTER_REFERENCE_BYTES.toHexDump(), bytes.toHexDump())
    }

    @Test
    @DisplayName("Заголовок файла 1.0 разбирается вручную, без reader'а компилятора")
    fun `procedural header fields are checked by hand`() {
        // Act
        val bytes = Examples.compile("hello_world").bytes
        fun u16(offset: Int) = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        // Assert
        assertArrayEquals("ALBC".toByteArray(Charsets.US_ASCII), bytes.copyOfRange(0, 4))
        assertEquals(1, u16(4), "version_major")
        assertEquals(0, u16(6), "version_minor")
        assertEquals(0, u16(8), "flags")
        assertEquals(0, u16(10), "entry_function_idx")
        assertEquals(3, u16(12), "constant_count: строка, Int 0, имя main")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["hello_world", "factorial_while", "gcd_demo", "counter", "objects"])
    @DisplayName("Байты примера совпадают с зафиксированным дампом examples/<name>.albc.hex")
    fun `example bytes match the committed hex dump`(name: String) {
        // Arrange
        val golden = Examples.projectRoot().resolve("examples/$name.albc.hex")
        val actual = Examples.compile(name).bytes.toHexDump()

        // Act
        val expected = parseHexDump(Files.readString(golden)).toHexDump()

        // Assert: при осознанном изменении байткода фактический дамп лежит в build/compat/
        if (expected != actual) {
            val dumpDir = Examples.projectRoot().resolve("build/compat").also { Files.createDirectories(it) }
            Files.writeString(dumpDir.resolve("$name.albc.hex"), actual)
        }
        assertEquals(expected, actual, "фактический дамп записан в build/compat/$name.albc.hex")
    }
}
