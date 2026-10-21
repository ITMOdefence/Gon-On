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
 * Единый формат диагностики для всех стадий компилятора (lexer/parser/semantic/codegen).
 * `code` — стабильный машиночитаемый идентификатор ошибки (используется в тестах),
 * `message` — человекочитаемое описание для CLI.
 *
 * [position] равен null у диагностик уровня всей программы, которые не относятся к конкретному
 * месту исходника: отсутствие точки входа `main`, переполнение пула констант, внутренняя ошибка
 * генерации. Такая диагностика выводится без строки и колонки, а не с выдуманной позицией 1:1.
 */
data class Diagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val position: SourcePosition?,
) {
    override fun toString(): String {
        val location = if (position != null) " at $position" else ""
        return "${severity.name.lowercase()}$location: $message [$code]"
    }
}

/** Собирает диагностики по ходу компиляции одной единицы (файла). */
class DiagnosticCollector {
    private val _diagnostics = mutableListOf<Diagnostic>()

    val diagnostics: List<Diagnostic> get() = _diagnostics

    val hasErrors: Boolean
        get() = _diagnostics.any { it.severity == DiagnosticSeverity.ERROR }

    fun error(code: String, message: String, position: SourcePosition?) {
        _diagnostics += Diagnostic(DiagnosticSeverity.ERROR, code, message, position)
    }

    fun warning(code: String, message: String, position: SourcePosition?) {
        _diagnostics += Diagnostic(DiagnosticSeverity.WARNING, code, message, position)
    }
}
