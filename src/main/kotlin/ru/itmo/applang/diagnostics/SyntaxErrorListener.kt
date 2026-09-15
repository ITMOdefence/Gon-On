package ru.itmo.applang.diagnostics

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

/**
 * Перехватывает синтаксические ошибки ANTLR (лексер и парсер) и превращает их в [Diagnostic]
 * вместо вывода в stderr по умолчанию. Подключается и к лексеру, и к парсеру.
 */
class SyntaxErrorListener(private val collector: DiagnosticCollector) : BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?,
    ) {
        collector.error(
            code = "SYNTAX_ERROR",
            message = msg ?: "синтаксическая ошибка",
            position = SourcePosition(line, charPositionInLine + 1),
        )
    }
}
