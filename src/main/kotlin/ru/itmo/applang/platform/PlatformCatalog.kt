package ru.itmo.applang.platform

import ru.itmo.applang.ast.Type
import ru.itmo.applang.bytecode.Instruction
import ru.itmo.applang.bytecode.StringConstraints

data class PlatformParameter(val name: String, val type: Type)
data class PlatformFunction(
    val name: String,
    val parameters: List<PlatformParameter>,
    val returnType: Type,
    val instruction: Instruction,
)

/** Каталог — ресурс JAR/дистрибутива, а не файл относительно рабочего каталога. */
object PlatformCatalog {
    val functions: List<PlatformFunction> by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream("/platform-api.json")) {
            "в дистрибутиве отсутствует platform-api.json"
        }
        resource.bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
    }

    fun find(name: String): PlatformFunction? = functions.firstOrNull { it.name == name }

    /** Строго проверяет схему и соответствие каталога фиксированному набору опкодов ABI. */
    internal fun parse(text: String): List<PlatformFunction> {
        val root = CatalogJson(text).read() as? Map<*, *> ?: error("каталог должен быть объектом")
        require(root.keys == setOf("schemaVersion", "status", "functions")) { "неизвестные поля каталога" }
        require(root["schemaVersion"] == 2L && root["status"] == "draft") { "неподдерживаемая версия/статус каталога" }
        val entries = root["functions"] as? List<*> ?: error("functions должен быть массивом")
        val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
        fun Map<*, *>.string(key: String): String = (get(key) as? String)?.also {
            require(StringConstraints.violation(it) == null) { "некорректная строка $key" }
        } ?: error("$key должен быть строкой")
        val functions = entries.map { value ->
            val entry = value as? Map<*, *> ?: error("запись функции должна быть объектом")
            require(entry.keys == setOf("name", "description", "group", "parameters", "returnType"))
            val name = entry.string("name")
            require(identifier.matches(name))
            require(entry.string("description").isNotBlank() && entry.string("group").isNotBlank())
            val instruction = PlatformAbi.instructions[name] ?: error("для $name не назначен опкод")
            val parameters = (entry["parameters"] as? List<*>)?.map { parameter ->
                val item = parameter as? Map<*, *> ?: error("параметр должен быть объектом")
                require(item.keys == setOf("name", "type"))
                val parameterName = item.string("name")
                require(identifier.matches(parameterName))
                PlatformParameter(parameterName, PlatformAbi.type(item.string("type")))
            } ?: error("parameters должен быть массивом")
            require(parameters.map { it.name }.distinct().size == parameters.size)
            PlatformFunction(name, parameters, PlatformAbi.type(entry.string("returnType")), instruction)
        }
        require(functions.map { it.name }.toSet() == PlatformAbi.instructions.keys &&
            functions.size == PlatformAbi.instructions.size) { "каталог не соответствует набору операций ABI 3.0" }
        return functions
    }
}

/** Минимальный строгий JSON-reader для схемы каталога, без дополнительных runtime-зависимостей. */
private class CatalogJson(private val text: String) {
    private var offset = 0

    fun read(): Any? {
        val result = value(0)
        whitespace()
        require(offset == text.length) { "лишние данные JSON на позиции $offset" }
        return result
    }

    private fun whitespace() {
        while (offset < text.length && text[offset] in " \t\r\n") offset++
    }

    private fun take(char: Char): Boolean {
        whitespace()
        if (text.getOrNull(offset) != char) return false
        offset++
        return true
    }

    private fun expect(char: Char) = require(take(char)) { "ожидался '$char' на позиции $offset" }

    private fun value(depth: Int): Any? {
        require(depth <= 32) { "слишком глубокий JSON" }
        whitespace()
        return when (text.getOrNull(offset)) {
            '{' -> {
                offset++
                val result = linkedMapOf<String, Any?>()
                if (!take('}')) {
                    do {
                        whitespace()
                        val key = string()
                        require(!result.containsKey(key)) { "повторный JSON-ключ $key" }
                        expect(':')
                        result[key] = value(depth + 1)
                    } while (take(','))
                    expect('}')
                }
                result
            }
            '[' -> {
                offset++
                val result = mutableListOf<Any?>()
                if (!take(']')) {
                    do { result += value(depth + 1) } while (take(','))
                    expect(']')
                }
                result
            }
            '"' -> string()
            in '0'..'9' -> {
                val start = offset
                if (text[offset++] != '0') while (text.getOrNull(offset) in '0'..'9') offset++
                text.substring(start, offset).toLong()
            }
            else -> error("недопустимое значение JSON на позиции $offset")
        }
    }

    private fun string(): String {
        require(text.getOrNull(offset++) == '"') { "ожидалась строка JSON" }
        return buildString {
            while (true) {
                val char = text.getOrNull(offset++) ?: error("незавершённая строка JSON")
                if (char == '"') break
                require(char >= ' ') { "управляющий символ в строке JSON" }
                if (char != '\\') append(char) else {
                    append(when (val escape = text.getOrNull(offset++)) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            require(offset + 4 <= text.length)
                            val digits = text.substring(offset, offset + 4)
                            require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            offset += 4
                            digits.toInt(16).toChar()
                        }
                        else -> error("недопустимое экранирование JSON")
                    })
                }
            }
        }
    }
}
