package ru.itmo.applang.compat

import kotlin.random.Random

/**
 * Генератор случайных, но корректных программ Gon&On для property-тестов компилятора.
 *
 * Программы гарантированно проходят проверку типов и завершаются: циклы ограничены счётчиком,
 * рекурсии нет (функция вызывает только ранее объявленные функции и методы; метод — только
 * методы своего класса с меньшим номером), делитель — ненулевой литерал. Покрываются функции,
 * Unit-функции, if/else, while, `&&`/`||`, строки, классы с полями-объектами, конструкторы,
 * методы, чтение и запись полей, `this`.
 */
class ProgramGenerator(seed: Int) {
    private val random = Random(seed)

    private class Field(val name: String, val type: String, val mutable: Boolean, val initializer: String?)
    private class Method(val name: String, val params: List<Pair<String, String>>, val returnType: String?)
    private class ClassDef(val name: String, val fields: List<Field>, val constructorParams: List<Pair<String, String>>) {
        val methods = mutableListOf<Method>()
    }
    private class Function(val name: String, val params: List<Pair<String, String>>, val returnType: String?)
    private class Variable(val name: String, val type: String, val mutable: Boolean)

    /** Состояние генерации тела одной функции. */
    private inner class Body(
        val owner: ClassDef?,
        /** Сколько методов владельца можно вызывать через this (только с меньшим номером). */
        val callableMethods: Int,
        /** Сколько функций верхнего уровня можно вызывать (только ранее объявленные). */
        val callableFunctions: Int,
    ) {
        val scopes = ArrayDeque<MutableList<Variable>>().apply { addLast(mutableListOf()) }
        var counter = 0
        val visible get() = scopes.flatten()

        fun fresh(prefix: String) = "$prefix${counter++}"
    }

    private val classes = mutableListOf<ClassDef>()
    private val functions = mutableListOf<Function>()
    private val out = StringBuilder()

    fun generate(): String {
        repeat(random.nextInt(0, 3)) { generateClass(it) }
        repeat(random.nextInt(1, 4)) { generateFunction(it) }
        generateMain()
        return out.toString()
    }

    // ---------------------------------------------------------------- объявления

    private fun valueTypes(): List<String> = listOf("Int", "Bool", "String") + classes.map { it.name }

    private fun generateClass(index: Int) {
        val name = "C$index"
        val fields = (0 until random.nextInt(1, 4)).map { i ->
            // Поле-объект — только ранее объявленного класса, с созданием в инициализаторе.
            val type = if (classes.isNotEmpty() && random.nextInt(4) == 0) classes.random(random).name else primitive()
            val hasInitializer = type !in listOf("Int", "Bool", "String") || random.nextBoolean()
            Field("f$i", type, random.nextBoolean(), if (hasInitializer) constant(type) else null)
        }
        val constructorParams = fields.filter { it.initializer == null }.mapIndexed { i, f -> "p$i" to f.type }
        val classDef = ClassDef(name, fields, constructorParams)

        out.appendLine("class $name {")
        fields.forEach { f ->
            val init = f.initializer?.let { " = $it" } ?: ""
            out.appendLine("    ${if (f.mutable) "var" else "val"} ${f.name}: ${f.type}$init;")
        }
        if (constructorParams.isNotEmpty() || random.nextBoolean()) {
            out.appendLine("    constructor(${constructorParams.joinToString { "${it.first}: ${it.second}" }}) {")
            var paramIndex = 0
            fields.filter { it.initializer == null }.forEach { out.appendLine("        this.${it.name} = p${paramIndex++};") }
            out.appendLine("    }")
        }
        classes += classDef // методы ниже могут использовать тип своего класса
        repeat(random.nextInt(0, 3)) { methodIndex ->
            val params = (0 until random.nextInt(0, 3)).map { "a$it" to valueTypes().random(random) }
            val returnType = if (random.nextInt(3) == 0) null else valueTypes().random(random)
            val method = Method("m$methodIndex", params, returnType)
            val body = Body(classDef, callableMethods = methodIndex, callableFunctions = 0)
            params.forEach { (n, t) -> body.scopes.last() += Variable(n, t, mutable = true) }
            out.appendLine("    fun ${method.name}(${params.joinToString { "${it.first}: ${it.second}" }})${returnType?.let { ": $it" } ?: ""} {")
            generateStatements(body, indent = 2, depth = 0)
            returnType?.let { out.appendLine("        return ${expr(it, body, 0)};") }
            out.appendLine("    }")
            classDef.methods += method
        }
        out.appendLine("}")
    }

    private fun generateFunction(index: Int) {
        val params = (0 until random.nextInt(0, 3)).map { "a$it" to valueTypes().random(random) }
        val returnType = if (random.nextInt(3) == 0) null else valueTypes().random(random)
        val function = Function("fn$index", params, returnType)
        val body = Body(owner = null, callableMethods = 0, callableFunctions = index)
        params.forEach { (n, t) -> body.scopes.last() += Variable(n, t, mutable = true) }
        out.appendLine("fun ${function.name}(${params.joinToString { "${it.first}: ${it.second}" }})${returnType?.let { ": $it" } ?: ""} {")
        generateStatements(body, indent = 1, depth = 0)
        returnType?.let { out.appendLine("    return ${expr(it, body, 0)};") }
        out.appendLine("}")
        functions += function
    }

    private fun generateMain() {
        val body = Body(owner = null, callableMethods = 0, callableFunctions = functions.size)
        out.appendLine("fun main() {")
        generateStatements(body, indent = 1, depth = 0)
        // Хотя бы один вывод, чтобы результат программы был наблюдаем.
        out.appendLine("    println(${expr(primitive(), body, 0)});")
        out.appendLine("}")
    }

    // ---------------------------------------------------------------- операторы

    private fun generateStatements(body: Body, indent: Int, depth: Int) {
        repeat(random.nextInt(1, 5)) { statement(body, indent, depth) }
    }

    private fun line(indent: Int, text: String) = out.appendLine("    ".repeat(indent) + text)

    private fun block(body: Body, indent: Int, depth: Int) {
        body.scopes.addLast(mutableListOf())
        generateStatements(body, indent, depth + 1)
        body.scopes.removeLast()
    }

    private fun statement(body: Body, indent: Int, depth: Int) {
        val nested = depth < 2
        when (random.nextInt(if (nested) 9 else 7)) {
            0, 1 -> {
                val type = valueTypes().random(random)
                val mutable = random.nextBoolean()
                val name = body.fresh("v")
                line(indent, "${if (mutable) "var" else "val"} $name: $type = ${expr(type, body, 0)};")
                body.scopes.last() += Variable(name, type, mutable)
            }
            2 -> body.visible.filter { it.mutable }.randomOrNull(random)?.let {
                line(indent, "${it.name} = ${expr(it.type, body, 0)};")
            } ?: line(indent, "println(${expr("Int", body, 0)});")
            3 -> line(indent, "println(${expr(primitive(), body, 0)});")
            4 -> fieldWrite(body)?.let { line(indent, it) } ?: line(indent, "print(${expr("String", body, 0)});")
            5 -> callStatement(body)?.let { line(indent, it) } ?: line(indent, "println(${expr("Bool", body, 0)});")
            6 -> line(indent, "print(${expr("Int", body, 0)});")
            7 -> {
                line(indent, "if (${expr("Bool", body, 0)}) {")
                block(body, indent + 1, depth)
                if (random.nextBoolean()) {
                    line(indent, "} else {")
                    block(body, indent + 1, depth)
                }
                line(indent, "}")
            }
            else -> {
                // Ограниченный цикл: счётчик неизменяем для остального тела.
                val counter = body.fresh("i")
                line(indent, "var $counter: Int = 0;")
                line(indent, "while ($counter < ${random.nextInt(1, 5)}) {")
                body.scopes.last() += Variable(counter, "Int", mutable = false)
                block(body, indent + 1, depth)
                line(indent + 1, "$counter = $counter + 1;")
                line(indent, "}")
            }
        }
    }

    /** Запись var-поля через переменную-объект или через this внутри метода. */
    private fun fieldWrite(body: Body): String? {
        val receivers = receivers(body)
        val (receiver, classDef) = receivers.randomOrNull(random) ?: return null
        val field = classDef.fields.filter { it.mutable }.randomOrNull(random) ?: return null
        return "$receiver.${field.name} = ${expr(field.type, body, 0)};"
    }

    /** Вызов функции или метода как отдельный оператор: результат, если есть, отбрасывается. */
    private fun callStatement(body: Body): String? {
        val candidates = mutableListOf<String>()
        functions.take(body.callableFunctions).forEach { f -> candidates += "${f.name}(${args(f.params, body)});" }
        for ((receiver, classDef) in receivers(body)) {
            val methods = if (receiver == "this") classDef.methods.take(body.callableMethods) else classDef.methods
            methods.forEach { m -> candidates += "$receiver.${m.name}(${args(m.params, body)});" }
        }
        classes.randomOrNull(random)?.let { c -> candidates += "${creation(c, body, 0)};" }
        return candidates.randomOrNull(random)
    }

    /** Доступные получатели: переменные-объекты и this (в методе экземпляр уже готов). */
    private fun receivers(body: Body): List<Pair<String, ClassDef>> {
        val result = body.visible.mapNotNull { v -> classes.firstOrNull { it.name == v.type }?.let { v.name to it } }
            .toMutableList()
        body.owner?.let { result += "this" to it }
        return result
    }

    // ---------------------------------------------------------------- выражения

    private fun primitive() = listOf("Int", "Bool", "String").random(random)

    private fun args(params: List<Pair<String, String>>, body: Body?): String =
        params.joinToString { (_, type) -> if (body == null) constant(type) else expr(type, body, 2) }

    /** Выражение без переменных — для инициализаторов полей. */
    private fun constant(type: String): String = when (type) {
        "Int" -> random.nextInt(-50, 100).toString()
        "Bool" -> random.nextBoolean().toString()
        "String" -> stringLiteral()
        else -> creation(classes.first { it.name == type }, body = null, depth = 2)
    }

    private fun stringLiteral(): String {
        val alphabet = "abcxyz XYZ0129"
        val text = (0 until random.nextInt(0, 6)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return if (random.nextInt(5) == 0) "\"п$text\\n\"" else "\"$text\""
    }

    private fun creation(classDef: ClassDef, body: Body?, depth: Int): String =
        "${classDef.name}(${classDef.constructorParams.joinToString { (_, type) ->
            if (body == null) constant(type) else expr(type, body, depth + 1)
        }})"

    private fun expr(type: String, body: Body, depth: Int): String {
        val options = mutableListOf<() -> String?>()
        options += { constant(type).takeIf { type in listOf("Int", "Bool", "String") } }
        options += { body.visible.filter { it.type == type }.randomOrNull(random)?.name }
        options += { fieldRead(type, body) }
        options += { callExpr(type, body, depth) }
        if (depth < 3) {
            when (type) {
                "Int" -> {
                    options += { "(${expr("Int", body, depth + 1)} ${listOf("+", "-", "*").random(random)} ${expr("Int", body, depth + 1)})" }
                    options += { "(${expr("Int", body, depth + 1)} ${listOf("/", "%").random(random)} ${random.nextInt(1, 10)})" }
                    options += { "-${expr("Int", body, depth + 1)}" }
                }
                "Bool" -> {
                    options += { "(${expr("Int", body, depth + 1)} ${listOf("<", ">", "<=", ">=", "==", "!=").random(random)} ${expr("Int", body, depth + 1)})" }
                    options += { "(${expr("Bool", body, depth + 1)} ${listOf("&&", "||").random(random)} ${expr("Bool", body, depth + 1)})" }
                    options += { "!${expr("Bool", body, depth + 1)}" }
                    options += { "(${expr("String", body, depth + 1)} == ${expr("String", body, depth + 1)})" }
                }
                "String" -> options += { "(${expr("String", body, depth + 1)} + ${expr("String", body, depth + 1)})" }
                else -> options += { creation(classes.first { it.name == type }, body, depth) }
            }
        } else if (type !in listOf("Int", "Bool", "String")) {
            options += { creation(classes.first { it.name == type }, body = null, depth = depth) }
        }
        repeat(20) { options.random(random)()?.let { return it } }
        return constant(type)
    }

    private fun fieldRead(type: String, body: Body): String? {
        val (receiver, classDef) = receivers(body).randomOrNull(random) ?: return null
        val field = classDef.fields.filter { it.type == type }.randomOrNull(random) ?: return null
        return "$receiver.${field.name}"
    }

    private fun callExpr(type: String, body: Body, depth: Int): String? {
        if (depth >= 3) return null
        val candidates = mutableListOf<String>()
        functions.take(body.callableFunctions).filter { it.returnType == type }.forEach { f ->
            candidates += "${f.name}(${args(f.params, body)})"
        }
        for ((receiver, classDef) in receivers(body)) {
            val methods = if (receiver == "this") classDef.methods.take(body.callableMethods) else classDef.methods
            methods.filter { it.returnType == type }.forEach { m -> candidates += "$receiver.${m.name}(${args(m.params, body)})" }
        }
        return candidates.randomOrNull(random)
    }
}
