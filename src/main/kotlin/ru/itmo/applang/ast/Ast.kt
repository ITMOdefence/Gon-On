package ru.itmo.applang.ast

import ru.itmo.applang.diagnostics.SourcePosition

/**
 * AST языка AppLang v1 (процедурный базис).
 *
 * Иерархия намеренно открыта для расширения будущими версиями языка: [Stmt] и [Expr] —
 * sealed-классы, новые подтипы (объявление класса, `new`-выражение, доступ к полю и т.п.)
 * добавляются в v2 без изменения уже существующих узлов.
 */

/** Типы значений AppLang v1. В v2 сюда добавятся пользовательские типы (классы). */
enum class Type {
    INT,
    BOOL,
    STRING,
    UNIT, // тип функций без возвращаемого значения
}

data class Param(
    val name: String,
    val type: Type,
    val position: SourcePosition,
)

data class FunctionDecl(
    val name: String,
    val params: List<Param>,
    val returnType: Type,
    val body: Block,
    val position: SourcePosition,
) {
    /** Общее число слотов локальных переменных (включая параметры), заполняется TypeChecker'ом. */
    var resolvedLocalCount: Int = -1
}

data class Program(
    val functions: List<FunctionDecl>,
)

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

data class Call(
    val functionName: String,
    val args: List<Expr>,
    override val position: SourcePosition,
) : Expr()
