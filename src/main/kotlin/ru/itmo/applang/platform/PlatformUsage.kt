package ru.itmo.applang.platform

import ru.itmo.applang.ast.*

/** Выбор профиля до назначения class_idx, включая вызовы в недостижимом коде. */
object PlatformUsage {
    fun detect(program: Program): Boolean {
        val declaredClasses = program.classes.map { it.name }.toSet()
        fun type(type: Type?) = type is Type.ClassType &&
            type.name in PlatformAbi.classNames && type.name !in declaredClasses
        fun expr(expr: Expr): Boolean = when (expr) {
            is Call -> expr.functionName in PlatformAbi.reservedFunctions ||
                (expr.functionName in PlatformAbi.classNames && expr.functionName !in declaredClasses) || expr.args.any(::expr)
            is BinaryOp -> expr(expr.left) || expr(expr.right)
            is UnaryOp -> expr(expr.operand)
            is FieldAccess -> expr(expr.receiver)
            is MethodCall -> expr(expr.receiver) || expr.args.any(::expr)
            else -> false
        }
        fun stmt(stmt: Stmt): Boolean = when (stmt) {
            is Block -> stmt.statements.any(::stmt)
            is VarDecl -> type(stmt.declaredType) || expr(stmt.initializer)
            is Assign -> expr(stmt.value)
            is FieldAssign -> expr(stmt.receiver) || expr(stmt.value)
            is If -> expr(stmt.condition) || stmt(stmt.thenBranch) || stmt.elseBranch?.let(::stmt) == true
            is While -> expr(stmt.condition) || stmt(stmt.body)
            is Return -> stmt.value?.let(::expr) == true
            is ExprStmt -> expr(stmt.expr)
        }
        fun function(fn: FunctionDecl): Boolean = fn.name in PlatformAbi.reservedFunctions ||
            PlatformAbi.events.any { it.name == fn.name } || type(fn.returnType) ||
            fn.params.any { type(it.type) } || stmt(fn.body)
        return program.functions.any(::function) || program.classes.any { cls ->
            cls.fields.any { type(it.type) || it.initializer?.let(::expr) == true } ||
                cls.methods.any(::function) || cls.constructors.any { constructor ->
                    constructor.params.any { type(it.type) } || stmt(constructor.body)
                }
        }
    }
}
