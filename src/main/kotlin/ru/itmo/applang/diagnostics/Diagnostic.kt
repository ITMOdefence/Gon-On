package ru.itmo.applang.diagnostics

/** Позиция в исходном файле, 1-based строка и колонка (как в большинстве IDE и компиляторов). */
data class SourcePosition(val line: Int, val column: Int) {
    override fun toString(): String = "$line:$column"
}

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
}

/**
 * Единый формат диагностики для всех стадий компилятора (lexer/parser/semantic).
 * `code` — стабильный машиночитаемый идентификатор ошибки (используется в тестах),
 * `message` — человекочитаемое описание для CLI.
 */
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val position: SourcePosition,
) {
    override fun toString(): String = "${severity.name.lowercase()} at $position: $message [$code]"
}

/** Собирает диагностики по ходу компиляции одной единицы (файла). */
class DiagnosticCollector {
    private val _diagnostics = mutableListOf<Diagnostic>()

    val diagnostics: List<Diagnostic> get() = _diagnostics

    val hasErrors: Boolean
        get() = _diagnostics.any { it.severity == DiagnosticSeverity.ERROR }

    fun error(code: String, message: String, position: SourcePosition) {
        _diagnostics += Diagnostic(DiagnosticSeverity.ERROR, code, message, position)
    }

    fun warning(code: String, message: String, position: SourcePosition) {
        _diagnostics += Diagnostic(DiagnosticSeverity.WARNING, code, message, position)
    }
}
