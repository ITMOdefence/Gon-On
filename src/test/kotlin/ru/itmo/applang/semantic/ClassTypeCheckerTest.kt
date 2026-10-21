package ru.itmo.applang.semantic

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import ru.itmo.applang.ast.AstBuilder
import ru.itmo.applang.ast.Program
import ru.itmo.applang.diagnostics.DiagnosticCollector
import ru.itmo.applang.parser.AppLangLexer
import ru.itmo.applang.parser.AppLangParser

private fun buildProgram(source: String): Program {
    val lexer = AppLangLexer(CharStreams.fromString(source))
    val parser = AppLangParser(CommonTokenStream(lexer))
    return AstBuilder().buildProgram(parser.program())
}

/** Проверяет исходник; `fun main() {}` добавляется, если в фрагменте нет своей точки входа. */
private fun typeCheck(source: String): DiagnosticCollector {
    val program = if ("fun main(" in source) source else "$source\nfun main() {}"
    val collector = DiagnosticCollector()
    TypeChecker(collector).check(buildProgram(program))
    return collector
}

private const val POINT = "class Point { var x: Int; var y: Int; constructor(x: Int, y: Int) { this.x = x; this.y = y; } " +
    "fun sum(): Int { return this.x + this.y; } fun move(dx: Int, dy: Int) { this.x = this.x + dx; this.y = this.y + dy; } }"

/** Семантика классов: docs/LANGUAGE_SPEC.md, раздел 7 (решения LANG-01…LANG-10). */
class ClassTypeCheckerTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("validPrograms")
    @DisplayName("Корректная программа с классами проходит проверку без диагностик")
    fun `well-typed class program has no diagnostics`(description: String, source: String) {
        // Act
        val collector = typeCheck(source)

        // Assert
        assertFalse(collector.hasErrors, "неожиданные диагностики: ${collector.diagnostics}")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPrograms")
    @DisplayName("Нарушение правила классов порождает диагностику ожидаемого вида")
    fun `ill-typed class program reports the expected code`(description: String, source: String, expectedCode: String) {
        // Act
        val collector = typeCheck(source)

        // Assert
        assertTrue(
            collector.diagnostics.any { it.code == expectedCode },
            "ожидался код '$expectedCode', получены: ${collector.diagnostics}",
        )
    }

    @Test
    @DisplayName("Номера полей — позиции объявления, методы и поля разрешаются с учётом владельца")
    fun `fields get declaration order indices and members resolve by owner`() {
        // Arrange
        val source = "class A { var p: Int = 1; var q: Int = 2; fun get(): Int { return this.q; } } " +
            "class B { var q: Int = 3; fun get(): Int { return this.q; } } " +
            "fun main(): Int { return A().get() + B().get(); }"
        val program = buildProgram(source)

        // Act
        val collector = DiagnosticCollector()
        TypeChecker(collector).check(program)

        // Assert
        assertFalse(collector.hasErrors, "${collector.diagnostics}")
        val returnA = program.classes[0].methods[0].body.statements.single() as ru.itmo.applang.ast.Return
        val returnB = program.classes[1].methods[0].body.statements.single() as ru.itmo.applang.ast.Return
        val readA = returnA.value as ru.itmo.applang.ast.FieldAccess
        val readB = returnB.value as ru.itmo.applang.ast.FieldAccess
        assertEquals(0 to 1, readA.resolvedClassIndex to readA.resolvedFieldIndex)
        assertEquals(1 to 0, readB.resolvedClassIndex to readB.resolvedFieldIndex)
    }

    @Test
    @DisplayName("Незаполненное поле сообщается с перечнем имён у конструктора")
    fun `missing field initialization lists the field names`() {
        // Act
        val collector = typeCheck("class A { var a: Int; var b: Int; constructor() { this.a = 1; } }")

        // Assert
        val diagnostic = collector.diagnostics.single { it.code == SemanticErrorCodes.FIELD_NOT_INITIALIZED }
        assertTrue(diagnostic.message.contains("'b'") && !diagnostic.message.contains("'a'"), diagnostic.message)
    }

    companion object {
        @JvmStatic
        fun validPrograms() = listOf(
            Arguments.of("пустой класс и создание", "class Empty {} fun main() { val e = Empty(); }"),
            Arguments.of("класс без конструктора с инициализаторами", "class C { var n: Int = 1; val s: String = \"x\"; } fun main() { val c = C(); c.n = c.n + 1; println(c.s); }"),
            Arguments.of("конструктор, методы и вызовы", "$POINT fun main() { val p = Point(1, 2); p.move(3, 4); println(p.sum()); }"),
            Arguments.of(
                "поле-объект и цепочка обращений",
                "$POINT class Box { val corner: Point = Point(0, 0); fun cornerX(): Int { return this.corner.x; } } " +
                    "fun main() { val b = Box(); b.corner.x = 5; println(b.corner.sum() + b.cornerX()); }",
            ),
            Arguments.of(
                "две ссылки на один экземпляр: val-ссылка на изменяемый объект",
                "$POINT fun main() { val p = Point(1, 1); val alias: Point = p; alias.x = 7; println(p.x); }",
            ),
            Arguments.of(
                "конструктор с ветвлением заполняет поле на всех путях",
                "class A { val v: Int; constructor(flag: Bool) { if (flag) { this.v = 1; } else { this.v = 2; } } }",
            ),
            Arguments.of(
                "вложенные ветвления, все конечные ветки заполняют поле",
                "class A { val v: Int; constructor(a: Bool, b: Bool) { if (a) { if (b) { this.v = 1; } else { this.v = 2; } } else { this.v = 3; } } }",
            ),
            Arguments.of(
                "чтение уже заполненного поля в инициализаторе и конструкторе",
                "class A { val a: Int = 1; val b: Int = this.a + 1; val c: Int; constructor() { this.c = this.a + this.b; } }",
            ),
            Arguments.of(
                "инициализатор поля видит параметр конструктора",
                "class A { val doubled: Int = n * 2; constructor(n: Int) {} }",
            ),
            Arguments.of(
                "после заполнения всех полей this можно передавать и вызывать методы",
                "fun use(a: A) {} class A { var v: Int; constructor() { this.v = 1; use(this); this.touch(); } fun touch() {} }",
            ),
            Arguments.of(
                "var-поле можно перезаписывать в конструкторе и в цикле",
                "class A { var v: Int = 0; constructor(n: Int) { var i: Int = 0; while (i < n) { this.v = this.v + i; i = i + 1; } } }",
            ),
            Arguments.of(
                "функция и метод возвращают объект конкретного класса",
                "$POINT fun make(): Point { return Point(1, 2); } class F { fun make(): Point { return make(); } } " +
                    "fun main() { val p: Point = F().make(); println(p.x); }",
            ),
            Arguments.of("класс, объявленный после использования", "fun main() { val l = Later(); } class Later {}"),
            Arguments.of(
                "циклическая ссылка типов классов",
                "class Node { var value: Int; var next: Other; constructor(v: Int, n: Other) { this.value = v; this.next = n; } } " +
                    "class Other { var back: Int = 0; fun link(n: Node): Node { return n; } }",
            ),
            Arguments.of("одноимённые методы разных классов", "class A { fun f(): Int { return 1; } } class B { fun f(): Bool { return true; } } fun main() { val x: Int = A().f(); val y: Bool = B().f(); }"),
            Arguments.of("метод с именем встроенной функции", "class Logger { fun println(s: String) {} } fun main() { Logger().println(\"x\"); }"),
            Arguments.of("параметр конструктора с именем поля", "class A { val v: Int; constructor(v: Int) { this.v = v; } }"),
            Arguments.of("неиспользованный результат создания как оператор", "class A {} fun main() { A(); }"),
            Arguments.of("метод без результата как оператор", "$POINT fun main() { Point(1, 2).move(1, 1); }"),
            Arguments.of(
                "254 явных параметра метода",
                "class Wide { fun m(" + (0 until 254).joinToString(", ") { "p$it: Int" } + ") {} }",
            ),
        )

        @JvmStatic
        fun invalidPrograms() = listOf(
            Arguments.of("неизвестный тип переменной", "fun main() { val a: Missing = 1; }", SemanticErrorCodes.UNDEFINED_CLASS),
            Arguments.of("неизвестный тип параметра", "fun f(a: Missing) {}", SemanticErrorCodes.UNDEFINED_CLASS),
            Arguments.of("неизвестный тип поля", "class A { var b: Missing; }", SemanticErrorCodes.UNDEFINED_CLASS),
            Arguments.of("неизвестный тип результата", "fun f(): Missing { return f(); }", SemanticErrorCodes.UNDEFINED_CLASS),
            Arguments.of("два класса с одним именем", "class A {} class A {}", SemanticErrorCodes.DUPLICATE_CLASS),
            Arguments.of("класс и функция с одним именем", "class A {} fun A() {}", SemanticErrorCodes.NAME_CONFLICT),
            Arguments.of("класс с именем встроенной функции", "class println {}", SemanticErrorCodes.NAME_CONFLICT),
            Arguments.of("функция с именем встроенной функции", "fun print(x: Int) {}", SemanticErrorCodes.NAME_CONFLICT),
            Arguments.of("повторное поле", "class A { var x: Int = 1; var x: Int = 2; }", SemanticErrorCodes.DUPLICATE_MEMBER),
            Arguments.of("поле и метод с одним именем", "class A { var x: Int = 1; fun x() {} }", SemanticErrorCodes.DUPLICATE_MEMBER),
            Arguments.of("повторный метод", "class A { fun f() {} fun f() {} }", SemanticErrorCodes.DUPLICATE_MEMBER),
            Arguments.of("два конструктора", "class A { constructor() {} constructor(x: Int) {} }", SemanticErrorCodes.DUPLICATE_MEMBER),
            Arguments.of("чтение несуществующего поля", "$POINT fun main() { println(Point(1, 2).z); }", SemanticErrorCodes.UNDEFINED_FIELD),
            Arguments.of("запись несуществующего поля", "$POINT fun main() { Point(1, 2).z = 1; }", SemanticErrorCodes.UNDEFINED_FIELD),
            Arguments.of("вызов несуществующего метода", "$POINT fun main() { Point(1, 2).fly(); }", SemanticErrorCodes.UNDEFINED_METHOD),
            Arguments.of("метод вызван как поле", "$POINT fun main() { println(Point(1, 2).sum); }", SemanticErrorCodes.UNDEFINED_FIELD),
            Arguments.of("обращение к члену у Int", "fun main() { val n: Int = 1; println(n.x); }", SemanticErrorCodes.INVALID_RECEIVER),
            Arguments.of("вызов метода у String", "fun main() { \"s\".length(); }", SemanticErrorCodes.INVALID_RECEIVER),
            Arguments.of("this в функции верхнего уровня", "fun main() { println(this.x); }", SemanticErrorCodes.INVALID_THIS),
            Arguments.of("запись поля неверного типа", "$POINT fun main() { Point(1, 2).x = true; }", SemanticErrorCodes.TYPE_MISMATCH),
            Arguments.of(
                "классы с одинаковыми полями — разные типы",
                "class A { var v: Int = 0; } class B { var v: Int = 0; } fun main() { val a: A = B(); }",
                SemanticErrorCodes.TYPE_MISMATCH,
            ),
            Arguments.of("аргумент метода неверного типа", "$POINT fun main() { Point(1, 2).move(1, \"2\"); }", SemanticErrorCodes.ARGUMENT_TYPE_MISMATCH),
            Arguments.of("аргумент конструктора неверного типа", "$POINT fun main() { val p = Point(1, false); }", SemanticErrorCodes.ARGUMENT_TYPE_MISMATCH),
            Arguments.of("неверное число аргументов конструктора", "$POINT fun main() { val p = Point(1); }", SemanticErrorCodes.ARITY_MISMATCH),
            Arguments.of("аргумент неявного конструктора", "class A {} fun main() { val a = A(1); }", SemanticErrorCodes.ARITY_MISMATCH),
            Arguments.of("поле без инициализатора и без конструктора", "class A { var v: Int; }", SemanticErrorCodes.FIELD_NOT_INITIALIZED),
            Arguments.of(
                "поле заполняется только в одной ветке",
                "class A { var v: Int; constructor(f: Bool) { if (f) { this.v = 1; } } }",
                SemanticErrorCodes.FIELD_NOT_INITIALIZED,
            ),
            Arguments.of(
                "поле заполняется только в цикле",
                "class A { var v: Int; constructor(n: Int) { while (n > 0) { this.v = n; } } }",
                SemanticErrorCodes.FIELD_NOT_INITIALIZED,
            ),
            Arguments.of(
                "чтение поля до заполнения в конструкторе",
                "class A { var a: Int; var b: Int; constructor() { this.b = this.a; this.a = 1; } }",
                SemanticErrorCodes.FIELD_NOT_INITIALIZED,
            ),
            Arguments.of(
                "чтение поля до заполнения в инициализаторе",
                "class A { val a: Int = this.b; val b: Int = 1; }",
                SemanticErrorCodes.FIELD_NOT_INITIALIZED,
            ),
            Arguments.of(
                "передача недостроенного this",
                "fun use(a: A) {} class A { var v: Int; constructor() { use(this); this.v = 1; } }",
                SemanticErrorCodes.THIS_ESCAPE,
            ),
            Arguments.of(
                "вызов метода на недостроенном this",
                "class A { var v: Int; constructor() { this.touch(); this.v = 1; } fun touch() {} }",
                SemanticErrorCodes.THIS_ESCAPE,
            ),
            Arguments.of(
                "сохранение недостроенного this в переменную",
                "class A { var v: Int; constructor() { val me = this; this.v = 1; } }",
                SemanticErrorCodes.THIS_ESCAPE,
            ),
            Arguments.of(
                "недостроенный this в инициализаторе поля",
                "fun id(a: A): Int { return 1; } class A { val v: Int = id(this); }",
                SemanticErrorCodes.THIS_ESCAPE,
            ),
            Arguments.of("запись val-поля в методе", "class A { val v: Int = 1; fun set() { this.v = 2; } }", SemanticErrorCodes.VAL_FIELD_REASSIGNMENT),
            Arguments.of("запись val-поля снаружи", "class A { val v: Int = 1; } fun main() { A().v = 2; }", SemanticErrorCodes.VAL_FIELD_REASSIGNMENT),
            Arguments.of(
                "повторная запись val-поля в конструкторе",
                "class A { val v: Int; constructor() { this.v = 1; this.v = 2; } }",
                SemanticErrorCodes.VAL_FIELD_REASSIGNMENT,
            ),
            Arguments.of(
                "val-поле с инициализатором записывается в конструкторе",
                "class A { val v: Int = 1; constructor() { this.v = 2; } }",
                SemanticErrorCodes.VAL_FIELD_REASSIGNMENT,
            ),
            Arguments.of(
                "val-поле записывается после ветки, где оно могло быть записано",
                "class A { val v: Int; constructor(f: Bool) { if (f) { this.v = 1; } this.v = 2; } }",
                SemanticErrorCodes.VAL_FIELD_REASSIGNMENT,
            ),
            Arguments.of(
                "val-поле записывается в цикле",
                "class A { val v: Int = 0; constructor() { while (true) { this.v = 1; } } }",
                SemanticErrorCodes.VAL_FIELD_REASSIGNMENT,
            ),
            Arguments.of(
                "запись val-поля другого экземпляра в конструкторе",
                "class A { val v: Int; constructor(other: A) { other.v = 1; this.v = 1; } }",
                SemanticErrorCodes.VAL_FIELD_REASSIGNMENT,
            ),
            Arguments.of("return в конструкторе", "class A { constructor() { return; } }", SemanticErrorCodes.RETURN_IN_CONSTRUCTOR),
            Arguments.of("сравнение объектов", "class A {} fun main() { val b: Bool = A() == A(); }", SemanticErrorCodes.INVALID_OPERAND_TYPE),
            Arguments.of("печать объекта", "class A {} fun main() { println(A()); }", SemanticErrorCodes.ARGUMENT_TYPE_MISMATCH),
            Arguments.of("конкатенация объекта со строкой", "class A {} fun main() { val s = \"a\" + A(); }", SemanticErrorCodes.INVALID_OPERAND_TYPE),
            Arguments.of("Unit-метод как значение", "$POINT fun main() { val r = Point(1, 2).move(1, 1); }", SemanticErrorCodes.NO_VALUE),
            Arguments.of("метод без return на всех путях", "class A { fun f(): Int { if (true) { return 1; } } }", SemanticErrorCodes.MISSING_RETURN),
            Arguments.of(
                "255 явных параметров метода (с this — 256)",
                "class Wide { fun m(" + (0 until 255).joinToString(", ") { "p$it: Int" } + ") {} }",
                SemanticErrorCodes.LIMIT_EXCEEDED,
            ),
            Arguments.of("main как метод не считается точкой входа", "class A { fun main() {} }", SemanticErrorCodes.MISSING_MAIN),
        )
    }
}
