package ru.itmo.applang.cli

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import ru.itmo.applang.ast.AstBuilder
import ru.itmo.applang.bytecode.BytecodeWriter
import ru.itmo.applang.bytecode.Disassembler
import ru.itmo.applang.codegen.BytecodeEmitter
import ru.itmo.applang.diagnostics.DiagnosticCollector
import ru.itmo.applang.diagnostics.SyntaxErrorListener
import ru.itmo.applang.parser.AppLangLexer
import ru.itmo.applang.parser.AppLangParser
import ru.itmo.applang.semantic.TypeChecker
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private class CliArgs(
    val inputFile: Path,
    val outputFile: Path,
    val disasm: Boolean,
    val dumpAst: Boolean,
)

private fun printUsageAndExit(): Nothing {
    System.err.println(
        """
        Использование: applangc <file.al> [-o out.albc] [--disasm] [--dump-ast]

          -o <path>     путь к выходному файлу байткода (по умолчанию: <file>.albc)
          --disasm      дополнительно вывести дизассемблированный байткод на stdout
          --dump-ast    дополнительно вывести AST на stdout (для отладки)
        """.trimIndent(),
    )
    exitProcess(2)
}

private fun parseArgs(args: Array<String>): CliArgs {
    if (args.isEmpty()) printUsageAndExit()

    var input: Path? = null
    var output: Path? = null
    var disasm = false
    var dumpAst = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-o" -> {
                if (i + 1 >= args.size) printUsageAndExit()
                output = Path.of(args[i + 1])
                i += 2
            }
            "--disasm" -> {
                disasm = true
                i += 1
            }
            "--dump-ast" -> {
                dumpAst = true
                i += 1
            }
            else -> {
                if (input != null) printUsageAndExit()
                input = Path.of(args[i])
                i += 1
            }
        }
    }

    val inputFile = input ?: printUsageAndExit()
    val outputFile = output ?: inputFile.resolveSibling(inputFile.fileName.toString().substringBeforeLast('.') + ".albc")
    return CliArgs(inputFile, outputFile, disasm, dumpAst)
}

fun main(args: Array<String>) {
    val cli = parseArgs(args)

    if (!Files.exists(cli.inputFile)) {
        System.err.println("Файл не найден: ${cli.inputFile}")
        exitProcess(1)
    }
    val source = Files.readString(cli.inputFile)

    val collector = DiagnosticCollector()
    val errorListener = SyntaxErrorListener(collector)

    val lexer = AppLangLexer(CharStreams.fromString(source, cli.inputFile.toString()))
    lexer.removeErrorListeners()
    lexer.addErrorListener(errorListener)

    val tokens = CommonTokenStream(lexer)
    val parser = AppLangParser(tokens)
    parser.removeErrorListeners()
    parser.addErrorListener(errorListener)

    val programCtx = parser.program()
    if (collector.hasErrors) {
        reportAndExit(collector, cli.inputFile)
    }

    val program = AstBuilder().buildProgram(programCtx)
    if (cli.dumpAst) {
        println(program)
    }

    TypeChecker(collector).check(program)
    if (collector.hasErrors) {
        reportAndExit(collector, cli.inputFile)
    }
    // Диагностики-предупреждения (если появятся в будущих версиях) всё равно стоит показать.
    collector.diagnostics.forEach { println(it) }

    val module = BytecodeEmitter().emit(program)
    BytecodeWriter.writeToFile(module, cli.outputFile)
    println("Скомпилировано: ${cli.inputFile} -> ${cli.outputFile}")

    if (cli.disasm) {
        println()
        print(Disassembler.disassemble(module))
    }
}

private fun reportAndExit(collector: DiagnosticCollector, file: Path): Nothing {
    System.err.println("Ошибки компиляции ($file):")
    collector.diagnostics.forEach { System.err.println("  $it") }
    exitProcess(1)
}
