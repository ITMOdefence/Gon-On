package ru.itmo.applang.bytecode

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

private const val LIMIT = FormatLimits.MAX_STRING_BYTES

class StringConstraintsTest {

    @Test
    @DisplayName("Пустая строка допустима и кодируется в 0 байт")
    fun `empty string is allowed`() {
        // Act
        val bytes = StringConstraints.encode("")

        // Assert
        assertEquals(0, bytes.size)
    }

    @Test
    @DisplayName("ASCII-строка ровно на пределе libstr допустима, на байт длиннее — нет")
    fun `ascii string at the libstr limit is allowed and one byte more is rejected`() {
        // Arrange
        val atLimit = "a".repeat(LIMIT)
        val overLimit = "a".repeat(LIMIT + 1)

        // Act & Assert
        assertEquals(LIMIT, StringConstraints.encode(atLimit).size)
        val error = assertThrows(BytecodeFormatException::class.java) { StringConstraints.encode(overLimit) }
        assertEquals(BytecodeIssue.STRING, error.issue)
        assertTrue(error.message!!.contains("${LIMIT + 1}") && error.message!!.contains("$LIMIT"))
    }

    @Test
    @DisplayName("Предел считается в байтах UTF-8, а не в символах Kotlin-строки")
    fun `limit is measured in utf8 bytes rather than string length`() {
        // Arrange: 'я' занимает 2 байта UTF-8, эмодзи вне BMP — 4 байта и 2 UTF-16 code unit
        val cyrillicAtLimit = "я".repeat(LIMIT / 2)
        val cyrillicOverLimit = cyrillicAtLimit + "a"
        val emoji = "😀"

        // Act & Assert
        assertEquals(LIMIT, StringConstraints.encode(cyrillicAtLimit).size)
        assertNotNull(StringConstraints.violation(cyrillicOverLimit))
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte()), StringConstraints.encode(emoji))
        assertNotNull(StringConstraints.violation(emoji.repeat(LIMIT / 4) + "a"))
    }

    @ParameterizedTest(name = "NUL в позиции {0}")
    @ValueSource(strings = ["start", "middle", "end"])
    @DisplayName("Символ NUL внутри строки отклоняется в любой позиции")
    fun `nul character is rejected anywhere`(where: String) {
        // Arrange
        val value = when (where) {
            "start" -> "\u0000abc"
            "middle" -> "ab\u0000c"
            else -> "abc\u0000"
        }

        // Act
        val error = assertThrows(BytecodeFormatException::class.java) { StringConstraints.encode(value) }

        // Assert
        assertEquals(BytecodeIssue.STRING, error.issue)
    }

    @Test
    @DisplayName("Текст из обратного слеша и нуля — не NUL и допустим")
    fun `backslash followed by zero is not nul`() {
        assertNull(StringConstraints.violation("\\0"))
    }

    @ParameterizedTest(name = "непарный суррогат: {0}")
    @ValueSource(strings = ["\uD83D", "a\uDE00b", "\uDE00\uD83D"])
    @DisplayName("Непарный суррогат отклоняется, а не заменяется символом замены")
    fun `unpaired surrogate is rejected instead of being replaced`(value: String) {
        assertThrows(BytecodeFormatException::class.java) { StringConstraints.encode(value) }
    }

    @Test
    @DisplayName("Строгое декодирование отклоняет некорректный UTF-8 и NUL")
    fun `strict decoding rejects malformed utf8 and nul`() {
        // Arrange
        val malformed = listOf(
            byteArrayOf(0xC3.toByte(), 0x28), // обрезанная двухбайтовая последовательность
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()), // overlong-кодирование '/'
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // закодированный суррогат (CESU-8)
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0x61, 0x00, 0x62),
        )

        // Act & Assert
        malformed.forEach { bytes ->
            assertThrows(BytecodeFormatException::class.java) { StringConstraints.decode(bytes) }
        }
        assertEquals("привет", StringConstraints.decode("привет".toByteArray(Charsets.UTF_8)))
    }

    private fun assertTrue(condition: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(condition)
}
