package ru.itmo.applang.semantic

import ru.itmo.applang.ast.Assign
import ru.itmo.applang.ast.BinaryOp
import ru.itmo.applang.ast.BinaryOperator
import ru.itmo.applang.ast.Block
import ru.itmo.applang.ast.BoolLiteral
import ru.itmo.applang.ast.Call
import ru.itmo.applang.ast.Expr
import ru.itmo.applang.ast.ExprStmt
import ru.itmo.applang.ast.FunctionDecl
import ru.itmo.applang.ast.If
import ru.itmo.applang.ast.IntLiteral
import ru.itmo.applang.ast.Program
import ru.itmo.applang.ast.Return
import ru.itmo.applang.ast.Stmt
import ru.itmo.applang.ast.StringLiteral
import ru.itmo.applang.ast.Type
import ru.itmo.applang.ast.UnaryOp
import ru.itmo.applang.ast.UnaryOperator
import ru.itmo.applang.ast.VarDecl
import ru.itmo.applang.ast.VarRef
import ru.itmo.applang.ast.While
import ru.itmo.applang.diagnostics.DiagnosticCollector
import ru.itmo.applang.diagnostics.SourcePosition

/** Встроенные функции AppLang v1. Не являются пользовательскими функциями и не попадают в [FunctionTable]. */
private val BUILTIN_NAMES = setOf("print", "println")

/**
 * Проверяет имена и типы во всей программе, заполняя [Expr.resolvedType] у каждого выражения.
 * Диагностики собираются в переданный [DiagnosticCollector]; codegen обязан запускаться только
 * если после проверки [DiagnosticCollector.hasErrors] == false.
 *
 * Упрощение v1: вместо полного контроль-флоу анализа "все пути возвращают значение" требуется,
 * чтобы явный `return` был последним оператором тела функции с ненулевым returnType
 * (см. docs/LANGUAGE_SPEC.md, раздел "Ограничения v1").
 */
class TypeChecker(private val collector: DiagnosticCollector) {

    private val functionTable = FunctionTable()

    fun check(program: Program) {
        collectFunctionSignatures(program)
        for (function in program.functions) {
            checkFunctionBody(function)
        }
    }

    private fun collectFunctionSignatures(program: Program) {
        for (function in program.functions) {
            val signature = FunctionSignature(function.name, function.params.map { it.type }, function.returnType)
            val declared = functionTable.declare(signature)
            if (!declared) {
                collector.error(
                    SemanticErrorCodes.DUPLICATE_FUNCTION,
                    "функция '${function.name}' уже объявлена",
                    function.position,
                )
            }
        }
    }

    private fun checkFunctionBody(function: FunctionDecl) {
        val scope = LocalScope()
        scope.enterScope()
        for (param in function.params) {
            scope.declare(param.name, param.type, isMutable = true)
        }
        checkBlock(function.body, scope, function.returnType)
        scope.exitScope()
        function.resolvedLocalCount = scope.totalSlotsUsed()

        if (function.returnType != Type.UNIT && !blockAlwaysReturns(function.body)) {
            collector.error(
                SemanticErrorCodes.MISSING_RETURN,
                "функция '${function.name}' должна гарантированно завершаться оператором return " +
                    "на всех путях выполнения (тип возврата ${function.returnType})",
                function.position,
            )
        }
    }

    /**
     * Упрощённая (не полноценный CFG) проверка "на всех путях есть return", достаточная для v1:
     * блок гарантированно возвращает значение, если его последний statement — либо явный `return`,
     * либо вложенный блок, который сам гарантированно возвращает, либо `if` с обеими ветками
     * (`else` обязателен), каждая из которых гарантированно возвращает. `while` не считается
     * гарантированным возвратом даже при видимо бесконечном условии — компилятор не анализирует
     * значения условий statically для этой цели.
     */
    private fun blockAlwaysReturns(block: Block): Boolean {
        val last = block.statements.lastOrNull() ?: return false
        return stmtAlwaysReturns(last)
    }

    private fun stmtAlwaysReturns(stmt: Stmt): Boolean = when (stmt) {
        is Return -> true
        is Block -> blockAlwaysReturns(stmt)
        is If -> stmt.elseBranch != null && blockAlwaysReturns(stmt.thenBranch) && blockAlwaysReturns(stmt.elseBranch)
        else -> false
    }

    private fun checkBlock(block: Block, scope: LocalScope, returnType: Type) {
        scope.enterScope()
        for (stmt in block.statements) {
            checkStmt(stmt, scope, returnType)
        }
        scope.exitScope()
    }

    private fun checkStmt(stmt: Stmt, scope: LocalScope, returnType: Type) {
        when (stmt) {
            is Block -> checkBlock(stmt, scope, returnType)
            is VarDecl -> checkVarDecl(stmt, scope)
            is Assign -> checkAssign(stmt, scope)
            is If -> {
                checkExpectedType(stmt.condition, scope, Type.BOOL, "условие if")
                checkBlock(stmt.thenBranch, scope, returnType)
                stmt.elseBranch?.let { checkBlock(it, scope, returnType) }
            }
            is While -> {
                checkExpectedType(stmt.condition, scope, Type.BOOL, "условие while")
                checkBlock(stmt.body, scope, returnType)
            }
            is Return -> checkReturn(stmt, scope, returnType)
            is ExprStmt -> checkExpr(stmt.expr, scope)
        }
    }

    private fun checkVarDecl(stmt: VarDecl, scope: LocalScope) {
        if (scope.findAnywhere(stmt.name) != null) {
            collector.error(
                SemanticErrorCodes.DUPLICATE_VARIABLE,
                "переменная '${stmt.name}' уже объявлена в этой функции (shadowing запрещён в v1)",
                stmt.position,
            )
        }
        val initType = checkExpr(stmt.initializer, scope)
        val declaredType = stmt.declaredType
        val effectiveType = when {
            declaredType == null -> initType
            initType == null -> declaredType
            declaredType != initType -> {
                collector.error(
                    SemanticErrorCodes.TYPE_MISMATCH,
                    "переменная '${stmt.name}' объявлена как $declaredType, " +
                        "но инициализатор имеет тип $initType",
                    stmt.position,
                )
                declaredType
            }
            else -> declaredType
        }
        val info = scope.declare(stmt.name, effectiveType ?: Type.INT, stmt.isMutable)
        stmt.resolvedSlot = info.slot
    }

    private fun checkAssign(stmt: Assign, scope: LocalScope) {
        val variable = scope.resolve(stmt.name)
        if (variable == null) {
            collector.error(
                SemanticErrorCodes.UNDEFINED_VARIABLE,
                "переменная '${stmt.name}' не объявлена",
                stmt.position,
            )
            checkExpr(stmt.value, scope)
            return
        }
        if (!variable.isMutable) {
            collector.error(
                SemanticErrorCodes.VAL_REASSIGNMENT,
                "нельзя переприсвоить 'val'-переменную '${stmt.name}'",
                stmt.position,
            )
        }
        stmt.resolvedSlot = variable.slot
        checkExpectedType(stmt.value, scope, variable.type, "присваивание '${stmt.name}'")
    }

    private fun checkReturn(stmt: Return, scope: LocalScope, returnType: Type) {
        if (stmt.value == null) {
            if (returnType != Type.UNIT) {
                collector.error(
                    SemanticErrorCodes.RETURN_TYPE_MISMATCH,
                    "функция должна возвращать значение типа $returnType",
                    stmt.position,
                )
            }
            return
        }
        if (returnType == Type.UNIT) {
            collector.error(
                SemanticErrorCodes.UNEXPECTED_RETURN_VALUE,
                "функция с типом возврата Unit не должна возвращать значение",
                stmt.position,
            )
            checkExpr(stmt.value, scope)
            return
        }
        checkExpectedType(stmt.value, scope, returnType, "return")
    }

    private fun checkExpectedType(expr: Expr, scope: LocalScope, expected: Type, context: String): Type? {
        val actual = checkExpr(expr, scope)
        if (actual != null && actual != expected) {
            collector.error(
                SemanticErrorCodes.TYPE_MISMATCH,
                "$context: ожидался тип $expected, получен $actual",
                expr.position,
            )
        }
        return actual
    }

    /**
     * Проверяет выражение и возвращает его тип. Возвращает null, если тип не удалось определить
     * из-за уже зарегистрированной ошибки (чтобы не порождать каскад повторных диагностик).
     */
    private fun checkExpr(expr: Expr, scope: LocalScope): Type? {
        val type = inferExpr(expr, scope)
        expr.resolvedType = type
        return type
    }

    private fun inferExpr(expr: Expr, scope: LocalScope): Type? = when (expr) {
        is IntLiteral -> Type.INT
        is BoolLiteral -> Type.BOOL
        is StringLiteral -> Type.STRING
        is VarRef -> {
            val variable = scope.resolve(expr.name)
            if (variable == null) {
                collector.error(
                    SemanticErrorCodes.UNDEFINED_VARIABLE,
                    "переменная '${expr.name}' не объявлена",
                    expr.position,
                )
                null
            } else {
                expr.resolvedSlot = variable.slot
                variable.type
            }
        }
        is UnaryOp -> inferUnaryOp(expr, scope)
        is BinaryOp -> inferBinaryOp(expr, scope)
        is Call -> inferCall(expr, scope)
    }

    private fun inferUnaryOp(expr: UnaryOp, scope: LocalScope): Type? {
        val operandType = checkExpr(expr.operand, scope) ?: return null
        return when (expr.operator) {
            UnaryOperator.NEG -> requireType(operandType, Type.INT, expr.position, "унарный минус")
            UnaryOperator.NOT -> requireType(operandType, Type.BOOL, expr.position, "логическое отрицание '!'")
        }
    }

    private fun inferBinaryOp(expr: BinaryOp, scope: LocalScope): Type? {
        val leftType = checkExpr(expr.left, scope)
        val rightType = checkExpr(expr.right, scope)
        if (leftType == null || rightType == null) return null

        return when (expr.operator) {
            BinaryOperator.ADD -> when {
                leftType == Type.INT && rightType == Type.INT -> Type.INT
                leftType == Type.STRING && rightType == Type.STRING -> Type.STRING
                else -> reportOperandMismatch(expr, leftType, rightType, "'+' (сложение или конкатенация строк)")
            }
            BinaryOperator.SUB, BinaryOperator.MUL, BinaryOperator.DIV, BinaryOperator.MOD ->
                requireBothType(leftType, rightType, Type.INT, expr, "арифметическая операция") ?.let { Type.INT }

            BinaryOperator.LT, BinaryOperator.GT, BinaryOperator.LE, BinaryOperator.GE ->
                requireBothType(leftType, rightType, Type.INT, expr, "операция сравнения")?.let { Type.BOOL }

            BinaryOperator.EQ, BinaryOperator.NE -> {
                if (leftType != rightType) {
                    reportOperandMismatch(expr, leftType, rightType, "'==' / '!=' (операнды разных типов)")
                } else {
                    Type.BOOL
                }
            }

            BinaryOperator.AND, BinaryOperator.OR ->
                requireBothType(leftType, rightType, Type.BOOL, expr, "логическая операция")?.let { Type.BOOL }
        }
    }

    private fun requireBothType(left: Type, right: Type, required: Type, expr: BinaryOp, context: String): Type? {
        if (left != required || right != required) {
            return reportOperandMismatch(expr, left, right, context)
        }
        return required
    }

    private fun reportOperandMismatch(expr: BinaryOp, left: Type, right: Type, context: String): Type? {
        collector.error(
            SemanticErrorCodes.INVALID_OPERAND_TYPE,
            "$context недопустима для типов $left и $right",
            expr.position,
        )
        return null
    }

    private fun requireType(actual: Type, required: Type, position: SourcePosition, context: String): Type? {
        if (actual != required) {
            collector.error(
                SemanticErrorCodes.INVALID_OPERAND_TYPE,
                "$context недопустима для типа $actual (ожидался $required)",
                position,
            )
            return null
        }
        return required
    }

    private fun inferCall(expr: Call, scope: LocalScope): Type? {
        if (expr.functionName in BUILTIN_NAMES) {
            if (expr.args.size != 1) {
                collector.error(
                    SemanticErrorCodes.ARITY_MISMATCH,
                    "встроенная функция '${expr.functionName}' принимает ровно 1 аргумент, передано ${expr.args.size}",
                    expr.position,
                )
            }
            // print/println полиморфны по типу аргумента (Int/Bool/String) — это особый случай
            // компилятора, а не обычное разрешение перегрузок пользовательских функций.
            expr.args.forEach { checkExpr(it, scope) }
            return Type.UNIT
        }

        val signature = functionTable.lookup(expr.functionName)
        if (signature == null) {
            collector.error(
                SemanticErrorCodes.UNDEFINED_FUNCTION,
                "функция '${expr.functionName}' не объявлена",
                expr.position,
            )
            expr.args.forEach { checkExpr(it, scope) }
            return null
        }

        if (signature.paramTypes.size != expr.args.size) {
            collector.error(
                SemanticErrorCodes.ARITY_MISMATCH,
                "функция '${expr.functionName}' ожидает ${signature.paramTypes.size} " +
                    "аргумент(ов), передано ${expr.args.size}",
                expr.position,
            )
            expr.args.forEach { checkExpr(it, scope) }
            return signature.returnType
        }

        for ((arg, expectedType) in expr.args.zip(signature.paramTypes)) {
            val actualType = checkExpr(arg, scope)
            if (actualType != null && actualType != expectedType) {
                collector.error(
                    SemanticErrorCodes.ARGUMENT_TYPE_MISMATCH,
                    "аргумент функции '${expr.functionName}': ожидался $expectedType, получен $actualType",
                    arg.position,
                )
            }
        }
        return signature.returnType
    }
}
