package ru.itmo.applang.platform

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PlatformCatalogTest {
    @Test
    @DisplayName("Каталог из дистрибутива совпадает с корневым JSON и не зависит от рабочего каталога")
    fun `packaged catalog matches the source catalog`() {
        val source = Files.readString(Path.of("platform-api.json"))
        assertEquals(PlatformCatalog.parse(source), PlatformCatalog.functions)
        assertEquals(21, PlatformCatalog.functions.size)
        assertEquals(PlatformCatalog.functions, PlatformCatalog.parse(source.replace("Фото", "\\u0424ото")))
    }

    @Test
    @DisplayName("Неподдерживаемая схема, повторы ключей, неизвестные типы и повреждённый JSON отвергаются")
    fun `catalog rejects schema drift and invalid json`() {
        val source = Files.readString(Path.of("platform-api.json"))
        val invalid = listOf(
            source.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3"),
            source.replaceFirst("\"schemaVersion\": 2", "\"schemaVersion\": 2, \"schemaVersion\": 2"),
            source.replaceFirst("\"WindowResult\"", "\"UnknownResult\""),
            source.replaceFirst("\"openWindow\"", "\"unknownOperation\""),
            source.replaceFirst("\"openWindow\"", "\"capturePhoto\""),
            source + " []", source.dropLast(3), "{\"schemaVersion\":02}",
        )
        invalid.forEach { text -> assertThrows(RuntimeException::class.java) { PlatformCatalog.parse(text) } }
    }
}
