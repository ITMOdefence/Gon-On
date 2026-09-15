package ru.itmo.applang.e2e

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.itmo.applang.ast.AstBuilder
import ru.itmo.applang.bytecode.Disassembler
import ru.itmo.applang.codegen.BytecodeEmitter
import ru.itmo.applang.diagnostics.DiagnosticCollector
import ru.itmo.applang.parser.AppLangLexer
import ru.itmo.applang.parser.AppLangParser
import ru.itmo.applang.semantic.TypeChecker
import java.nio.file.Files
import java.nio.file.Path

/**
 * Компилирует каждый пример из examples/ полным пайплайном и сравнивает дизассемблированный
 * вывод с зафиксированным golden-файлом examples/<name>.albc.asm. Любое изменение opcode set,
 * формата constant pool или стратегии codegen, которое меняет получившийся байткод, должно
 * либо остаться совместимым с golden-файлом, либо явно его обновить в этом же коммите —
 * так документация (docs/BYTECODE_SPEC.md, примеры) не расходится с реальным поведением компилятора.
 */
class EndToEndExamplesTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["hello_world", "factorial_while"])
    @DisplayName("Дизассемблированный вывод компилятора совпадает с golden-файлом примера")
    fun `compiled example matches its golden disassembly`(exampleName: String) {
        // Arrange
        val projectRoot = Path.of(System.getProperty("user.dir"))
        val sourceFile = projectRoot.resolve("examples/$exampleName.al")
        val goldenFile = projectRoot.resolve("examples/$exampleName.albc.asm")
        val source = Files.readString(sourceFile)
        val expectedDisassembly = Files.readString(goldenFile)

        // Act
        val lexer = AppLangLexer(CharStreams.fromString(source))
        val parser = AppLangParser(CommonTokenStream(lexer))
        val program = AstBuilder().buildProgram(parser.program())

        val collector = DiagnosticCollector()
        TypeChecker(collector).check(program)
        assertFalse(collector.hasErrors, "пример должен компилироваться без ошибок: ${collector.diagnostics}")

        val module = BytecodeEmitter().emit(program)
        val actualDisassembly = Disassembler.disassemble(module)

        // Assert
        assertEquals(expectedDisassembly, actualDisassembly)
    }
}
