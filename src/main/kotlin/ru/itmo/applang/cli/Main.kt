package ru.itmo.applang.cli

import ru.itmo.applang.bytecode.BytecodeFormatException
import ru.itmo.applang.bytecode.BytecodeWriter
import ru.itmo.applang.bytecode.Disassembler
import ru.itmo.applang.compiler.CompilationResult
import ru.itmo.applang.compiler.Compiler
import ru.itmo.applang.diagnostics.Diagnostic
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Коды завершения `applangc`. */
object ExitCodes {
    const val OK = 0
    const val COMPILATION_FAILED = 1
    const val USAGE = 2
}

private class CliArgs(
    val inputFile: Path,
    val outputFile: Path,
    val disasm: Boolean,
    val dumpAst: Boolean,
)

private const val USAGE = """Использование: applangc <file.al> [-o out.albc] [--disasm] [--dump-ast]

  -o <path>     путь к выходному файлу байткода (по умолчанию: <file>.albc)
  --disasm      дополнительно вывести дизассемблированный байткод (модель в памяти) на stdout
  --dump-ast    дополнительно вывести AST на stdout (для отладки)"""

private fun parseArgs(args: Array<String>): CliArgs? {
    var input: Path? = null
    var output: Path? = null
    var disasm = false
    var dumpAst = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-o" -> {
                if (i + 1 >= args.size) return null
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
                if (input != null) return null
                input = Path.of(args[i])
                i += 1
            }
        }
    }

    val inputFile = input ?: return null
    val outputFile = output ?: inputFile.resolveSibling(inputFile.fileName.toString().substringBeforeLast('.') + ".albc")
    return CliArgs(inputFile, outputFile, disasm, dumpAst)
}

/**
 * Точка входа CLI без `exitProcess`, чтобы её можно было вызывать из тестов. При любой ошибке
 * (исходника, предела формата, проверки байткода, ввода-вывода) выходной файл не создаётся и не
 * перезаписывается, а сообщение «Скомпилировано» печатается только после успешной записи.
 */
fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
    val cli = parseArgs(args) ?: run {
        err.println(USAGE)
        return ExitCodes.USAGE
    }

    if (!Files.exists(cli.inputFile)) {
        err.println("Файл не найден: ${cli.inputFile}")
        return ExitCodes.COMPILATION_FAILED
    }
    val source = try {
        Files.readString(cli.inputFile)
    } catch (e: CharacterCodingException) {
        err.println("Не удалось прочитать ${cli.inputFile}: исходник должен быть в кодировке UTF-8")
        return ExitCodes.COMPILATION_FAILED
    } catch (e: IOException) {
        err.println("Не удалось прочитать ${cli.inputFile}: ${e.message}")
        return ExitCodes.COMPILATION_FAILED
    }

    val result = Compiler.compile(source, cli.inputFile.toString())
    if (cli.dumpAst) {
        result.program?.let { out.println(it) }
    }

    when (result) {
        is CompilationResult.Failure -> {
            reportErrors(err, cli.inputFile, result.diagnostics)
            return ExitCodes.COMPILATION_FAILED
        }
        is CompilationResult.Success -> {
            // Диагностики-предупреждения (если появятся в будущих версиях) всё равно стоит показать.
            result.diagnostics.forEach { out.println(it) }
            try {
                BytecodeWriter.writeToFile(result.module, cli.outputFile)
            } catch (e: BytecodeFormatException) {
                err.println("Внутренняя ошибка компилятора: ${e.message}")
                return ExitCodes.COMPILATION_FAILED
            } catch (e: IOException) {
                err.println("Не удалось записать ${cli.outputFile}: ${e.message}")
                return ExitCodes.COMPILATION_FAILED
            }
            out.println("Скомпилировано: ${cli.inputFile} -> ${cli.outputFile}")
            if (cli.disasm) {
                out.println()
                out.print(Disassembler.disassemble(result.module))
            }
            return ExitCodes.OK
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(run(args))
}

private fun reportErrors(err: PrintStream, file: Path, diagnostics: List<Diagnostic>) {
    err.println("Ошибки компиляции ($file):")
    diagnostics.forEach { err.println("  $it") }
}
