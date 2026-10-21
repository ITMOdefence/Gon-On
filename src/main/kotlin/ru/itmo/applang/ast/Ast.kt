package ru.itmo.applang.ast

import ru.itmo.applang.diagnostics.SourcePosition

/**
 * AST языка Gon&On: процедурный базис v1 и классы v2.
 *
 * Иерархия открыта для расширения: [Stmt] и [Expr] — sealed-классы, новые конструкции
 * добавляются новыми подтипами без изменения существующих узлов. Поля `resolved*` заполняет
 * [ru.itmo.applang.semantic.TypeChecker]; codegen работает только с полностью разрешённым AST.
 */

/** Типы значений Gon&On. */
sealed class Type {
    data object INT : Type() {
        override fun toString() = "Int"
    }

    data object BOOL : Type() {
        override fun toString() = "Bool"
    }

    data object STRING : Type() {
        override fun toString() = "String"
    }

    /** Отсутствие результата у функции без возвращаемого значения. Не тип значения, переменной или поля. */
    data object UNIT : Type() {
        override fun toString() = "Unit"
    }

    /** Пользовательский класс; два класса с одинаковыми полями — разные типы (номинальная типизация). */
    data class ClassType(val name: String) : Type() {
        override fun toString() = name
    }
}

data class Param(
    val name: String,
    val type: Type,
    val position: SourcePosition,
)

/** Объявление верхнего уровня: функция или класс. */
sealed interface TopLevelDecl {
    val name: String
    val position: SourcePosition
}

/** Функция верхнего уровня или метод класса (метод получает неявный `this` в слоте 0). */
data class FunctionDecl(
    override val name: String,
    val params: List<Param>,
    val returnType: Type,
    val body: Block,
    override val position: SourcePosition,
) : TopLevelDecl {
    /** Общее число слотов локальных переменных (включая `this`, параметры и скрытые временные слоты). */
    var resolvedLocalCount: Int = -1

    /** Первый скрытый временный слот: временный слот глубины `d` — `resolvedTempBase + d`. */
    var resolvedTempBase: Int = -1
}

/** Поле класса; тип всегда объявлен явно. */
data class FieldDecl(
    val name: String,
    val isMutable: Boolean, // true для var, false для val
    val type: Type,
    val initializer: Expr?,
    val position: SourcePosition,
)

/** Пользовательский конструктор: тело функции инициализации после инициализаторов полей. */
data class ConstructorDecl(
    val params: List<Param>,
    val body: Block,
    val position: SourcePosition,
)

data class ClassDecl(
    override val name: String,
    val fields: List<FieldDecl>,
    /** Объявленные конструкторы; языком допускается не более одного (проверяет TypeChecker). */
    val constructors: List<ConstructorDecl>,
    val methods: List<FunctionDecl>,
    override val position: SourcePosition,
) : TopLevelDecl {
    val constructor: ConstructorDecl? get() = constructors.firstOrNull()

    /** Слоты функции инициализации: `this`, параметры конструктора, локальные и временные. */
    var resolvedInitLocalCount: Int = -1
    var resolvedInitTempBase: Int = -1
}

/** Программа — объявления верхнего уровня в порядке исходника. */
data class Program(
    val declarations: List<TopLevelDecl>,
) {
    val functions: List<FunctionDecl> get() = declarations.filterIsInstance<FunctionDecl>()
    val classes: List<ClassDecl> get() = declarations.filterIsInstance<ClassDecl>()
}

sealed class Stmt {
    abstract val position: SourcePosition
}

data class Block(
    val statements: List<Stmt>,
    override val position: SourcePosition,
) : Stmt()

data class VarDecl(
    val name: String,
    val isMutable: Boolean, // true для var, false для val
    val declaredType: Type?, // null, если тип не указан явно и выводится из инициализатора
    val initializer: Expr,
    override val position: SourcePosition,
) : Stmt() {
    /** Номер слота локальной переменной, заполняется TypeChecker'ом; используется codegen'ом. */
    var resolvedSlot: Int = -1
}

data class Assign(
    val name: String,
    val value: Expr,
    override val position: SourcePosition,
) : Stmt() {
    var resolvedSlot: Int = -1
}

/** Запись поля `receiver.field = value`; receiver вычисляется раньше правой части. */
data class FieldAssign(
    val receiver: Expr,
    val fieldName: String,
    val value: Expr,
    override val position: SourcePosition,
) : Stmt() {
    var resolvedClassIndex: Int = -1
    var resolvedFieldIndex: Int = -1
}

data class If(
    val condition: Expr,
    val thenBranch: Block,
    val elseBranch: Block?,
    override val position: SourcePosition,
) : Stmt()

data class While(
    val condition: Expr,
    val body: Block,
    override val position: SourcePosition,
) : Stmt()

data class Return(
    val value: Expr?,
    override val position: SourcePosition,
) : Stmt()

data class ExprStmt(
    val expr: Expr,
    override val position: SourcePosition,
) : Stmt()

sealed class Expr {
    abstract val position: SourcePosition

    /**
     * Заполняется семантическим анализатором ([ru.itmo.applang.semantic.TypeChecker]) после
     * успешной проверки типов. До этого момента (сразу после парсинга) равен null.
     * Codegen обязан работать только с типизированным AST и вправе полагаться на не-null.
     */
    var resolvedType: Type? = null
}

data class IntLiteral(val value: Int, override val position: SourcePosition) : Expr()
data class BoolLiteral(val value: Boolean, override val position: SourcePosition) : Expr()
data class StringLiteral(val value: String, override val position: SourcePosition) : Expr()

data class VarRef(val name: String, override val position: SourcePosition) : Expr() {
    var resolvedSlot: Int = -1
}

enum class BinaryOperator {
    ADD, SUB, MUL, DIV, MOD,
    EQ, NE, LT, GT, LE, GE,
    AND, OR,
}

data class BinaryOp(
    val operator: BinaryOperator,
    val left: Expr,
    val right: Expr,
    override val position: SourcePosition,
) : Expr()

enum class UnaryOperator { NEG, NOT }

data class UnaryOp(
    val operator: UnaryOperator,
    val operand: Expr,
    override val position: SourcePosition,
) : Expr()

/**
 * Вызов по имени: функция верхнего уровня, встроенная `print`/`println` или создание экземпляра
 * класса `Name(args)` (LANG-02) — что именно, решает TypeChecker.
 */
data class Call(
    val functionName: String,
    val args: List<Expr>,
    override val position: SourcePosition,
) : Expr() {
    /** Класс, если вызов — создание экземпляра. */
    var resolvedConstructorClass: ClassDecl? = null

    /** Глубина скрытого слота для ссылки на создаваемый экземпляр. */
    var resolvedTempDepth: Int = -1
}

/** Текущий экземпляр внутри метода или конструктора; слот 0. */
data class This(override val position: SourcePosition) : Expr()

/** Чтение поля `receiver.field`. */
data class FieldAccess(
    val receiver: Expr,
    val fieldName: String,
    override val position: SourcePosition,
) : Expr() {
    var resolvedClassIndex: Int = -1
    var resolvedFieldIndex: Int = -1
}

/** Вызов метода `receiver.method(args)`; receiver вычисляется первым и ровно один раз. */
data class MethodCall(
    val receiver: Expr,
    val methodName: String,
    val args: List<Expr>,
    override val position: SourcePosition,
) : Expr() {
    var resolvedMethod: FunctionDecl? = null

    /** Глубина скрытого слота, в котором receiver ждёт вычисления аргументов. */
    var resolvedTempDepth: Int = -1
}
