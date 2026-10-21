package ru.itmo.applang.compiler

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import ru.itmo.applang.ast.AstBuilder
import ru.itmo.applang.ast.Program
import ru.itmo.applang.bytecode.BytecodeFormatException
import ru.itmo.applang.bytecode.BytecodeModule
import ru.itmo.applang.bytecode.BytecodeWriter
import ru.itmo.applang.codegen.BytecodeEmitter
import ru.itmo.applang.codegen.CodegenLimitException
import ru.itmo.applang.diagnostics.Diagnostic
import ru.itmo.applang.diagnostics.DiagnosticCollector
import ru.itmo.applang.diagnostics.SyntaxErrorListener
import ru.itmo.applang.parser.AppLangLexer
import ru.itmo.applang.parser.AppLangParser
import ru.itmo.applang.semantic.SemanticErrorCodes
import ru.itmo.applang.semantic.TypeChecker

/** Коды диагностик стадий после семантического анализа. */
object CompilerErrorCodes {
    /**
     * Нарушение инварианта после успешной проверки исходника: генератор построил байткод, который
     * не проходит проверку формата GVM. Это дефект компилятора, а не ошибка программы.
     */
    const val INTERNAL_COMPILER_ERROR = "INTERNAL_COMPILER_ERROR"
}

/** Результат компиляции одной единицы. Успех означает, что [bytes] прошли полную проверку GVM-профиля. */
sealed class CompilationResult {
    abstract val diagnostics: List<Diagnostic>

    /** AST, если парсинг прошёл без синтаксических ошибок (нужен для `--dump-ast`). */
    abstract val program: Program?

    class Success(
        override val program: Program,
        val module: BytecodeModule,
        val bytes: ByteArray,
        override val diagnostics: List<Diagnostic>,
    ) : CompilationResult()

    class Failure(
        override val program: Program?,
        override val diagnostics: List<Diagnostic>,
    ) : CompilationResult()
}

/**
 * Полный пайплайн компилятора: Lexer/Parser -> AstBuilder -> TypeChecker -> BytecodeEmitter ->
 * проверка и сериализация [BytecodeWriter]. Общий для CLI и тестов, чтобы порядок стадий и
 * обработка ошибок были одинаковыми. Любая ошибка исходника, превышение предела или отказ
 * проверки байткода даёт [CompilationResult.Failure] — результат никогда не объявляется успешным
 * частично.
 */
object Compiler {

    fun compile(source: String, sourceName: String = "<source>"): CompilationResult {
        val collector = DiagnosticCollector()
        val errorListener = SyntaxErrorListener(collector)

        val lexer = AppLangLexer(CharStreams.fromString(source, sourceName))
        lexer.removeErrorListeners()
        lexer.addErrorListener(errorListener)

        val parser = AppLangParser(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)

        val programCtx = parser.program()
        if (collector.hasErrors) {
            return CompilationResult.Failure(null, collector.diagnostics)
        }

        val program = AstBuilder().buildProgram(programCtx)
        TypeChecker(collector).check(program)
        if (collector.hasErrors) {
            return CompilationResult.Failure(program, collector.diagnostics)
        }

        val module = try {
            BytecodeEmitter().emit(program)
        } catch (e: CodegenLimitException) {
            collector.error(SemanticErrorCodes.LIMIT_EXCEEDED, e.message ?: "превышен предел формата", null)
            return CompilationResult.Failure(program, collector.diagnostics)
        } catch (e: IllegalStateException) {
            collector.error(CompilerErrorCodes.INTERNAL_COMPILER_ERROR, "ошибка генерации: ${e.message}", null)
            return CompilationResult.Failure(program, collector.diagnostics)
        }

        val bytes = try {
            BytecodeWriter.writeToBytes(module)
        } catch (e: BytecodeFormatException) {
            collector.error(
                CompilerErrorCodes.INTERNAL_COMPILER_ERROR,
                "сгенерированный байткод не прошёл проверку: ${e.message}",
                null,
            )
            return CompilationResult.Failure(program, collector.diagnostics)
        }
        return CompilationResult.Success(program, module, bytes, collector.diagnostics)
    }
}
