package ru.itmo.applang.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.itmo.applang.bytecode.BytecodeFormatException
import ru.itmo.applang.bytecode.BytecodeIssue
import ru.itmo.applang.bytecode.BytecodeReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Набор совместимости обязан быть честным: каждый «корректный» файл принимается строгим reader'ом
 * и исполняется с заявленным выводом, каждый «испорченный» отклоняется ровно с заявленной
 * категорией. Иначе CI GVM получил бы противоречивые ожидания.
 */
class ConformanceCorpusTest {

    @Test
    @DisplayName("Каждый файл набора ведёт себя так, как заявлено в manifest.json")
    fun `every corpus file behaves as its manifest entry declares`(@TempDir directory: Path) {
        // Act
        val entries = ConformanceCorpus.write(directory)

        // Assert
        for (entry in entries) {
            val bytes = Files.readAllBytes(directory.resolve(entry.path))
            val expectedIssue = entry.expectedIssue
            if (expectedIssue == null) {
                val module = BytecodeReader.readFromBytes(bytes)
                val result = ReferenceInterpreter(module).run()
                assertEquals(entry.stdout, result.stdout, entry.path)
                assertEquals(entry.returnValue, result.returnValue, entry.path)
                assertEquals(entry.profile, "${module.versionMajor}.${module.versionMinor}", entry.path)
            } else {
                val error = assertThrows(BytecodeFormatException::class.java, { BytecodeReader.readFromBytes(bytes) }, entry.path)
                assertEquals(expectedIssue, error.issue, "${entry.path}: ${error.message}")
            }
        }
    }

    @Test
    @DisplayName("Набор покрывает оба профиля и все категории отказа, которые выдаёт компилятор")
    fun `corpus covers both profiles and the rejection categories`(@TempDir directory: Path) {
        // Act
        val entries = ConformanceCorpus.write(directory)
        val manifest = Files.readString(directory.resolve("manifest.json"))

        // Assert
        val issues = entries.mapNotNull { it.expectedIssue }.toSet()
        val required = BytecodeIssue.entries.toSet()
        assertEquals(required, issues, "без файла остались категории: ${required - issues}")
        assertTrue(entries.any { it.expectedIssue == null && it.profile == "1.0" })
        assertTrue(entries.any { it.expectedIssue == null && it.profile == "2.0" })
        entries.forEach { assertTrue(manifest.contains("\"path\": \"${it.path}\""), it.path) }
        val filesOnDisk = Files.walk(directory).filter { it.toString().endsWith(".albc") }.count()
        assertEquals(entries.size.toLong(), filesOnDisk)
    }
}
