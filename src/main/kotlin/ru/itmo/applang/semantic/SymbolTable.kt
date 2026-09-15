package ru.itmo.applang.semantic

import ru.itmo.applang.ast.Type

/** Сигнатура функции, собирается первым проходом до анализа тел — для поддержки forward-ссылок. */
data class FunctionSignature(
    val name: String,
    val paramTypes: List<Type>,
    val returnType: Type,
)

/** Таблица функций верхнего уровня. В AppLang v1 нет вложенных функций. */
class FunctionTable {
    private val functions = mutableMapOf<String, FunctionSignature>()

    fun declare(signature: FunctionSignature): Boolean {
        if (functions.containsKey(signature.name)) return false
        functions[signature.name] = signature
        return true
    }

    fun lookup(name: String): FunctionSignature? = functions[name]

    fun all(): Collection<FunctionSignature> = functions.values
}

/** Информация о локальной переменной, включая номер слота для будущего codegen. */
data class VariableInfo(
    val name: String,
    val type: Type,
    val isMutable: Boolean,
    val slot: Int,
)

/**
 * Стек областей видимости для локальных переменных одной функции.
 * Каждый [Block] AppLang v1 открывает новую область; shadowing запрещён (см. LANGUAGE_SPEC.md) —
 * повторное объявление имени, уже видимого в текущей функции, является ошибкой.
 */
class LocalScope {
    private val scopes = ArrayDeque<MutableMap<String, VariableInfo>>()
    private var nextSlot = 0

    fun enterScope() = scopes.addLast(mutableMapOf())

    fun exitScope() = scopes.removeLast()

    /** Общее число слотов, выделенных за всё время жизни функции (нужно для function table в байткоде). */
    fun totalSlotsUsed(): Int = nextSlot

    /** Возвращает переменную, если имя уже объявлено где-либо в текущей функции (для запрета shadowing). */
    fun findAnywhere(name: String): VariableInfo? {
        for (scope in scopes) {
            scope[name]?.let { return it }
        }
        return null
    }

    fun resolve(name: String): VariableInfo? = findAnywhere(name)

    fun declare(name: String, type: Type, isMutable: Boolean): VariableInfo {
        val info = VariableInfo(name, type, isMutable, nextSlot++)
        scopes.last()[name] = info
        return info
    }
}
