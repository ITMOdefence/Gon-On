package ru.itmo.applang.ast

import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.TerminalNode
import ru.itmo.applang.diagnostics.SourcePosition
import ru.itmo.applang.parser.AppLangBaseVisitor
import ru.itmo.applang.parser.AppLangParser

private fun ParserRuleContext.pos(): SourcePosition =
    SourcePosition(start.line, start.charPositionInLine + 1)

private fun TerminalNode.pos(): SourcePosition =
    SourcePosition(symbol.line, symbol.charPositionInLine + 1)

/** Строит AST из ANTLR parse tree. Не выполняет никакой семантической проверки. */
class AstBuilder : AppLangBaseVisitor<Any?>() {

    fun buildProgram(ctx: AppLangParser.ProgramContext): Program {
        val functions = ctx.functionDecl().map { visitFunctionDeclNode(it) }
        return Program(functions)
    }

    private fun visitFunctionDeclNode(ctx: AppLangParser.FunctionDeclContext): FunctionDecl {
        val name = ctx.IDENT().text
        val params = ctx.paramList()?.param().orEmpty().map { p ->
            Param(p.IDENT().text, parseType(p.type()), p.IDENT().pos())
        }
        val returnType = ctx.type()?.let { parseType(it) } ?: Type.UNIT
        val body = visitBlockNode(ctx.block())
        return FunctionDecl(name, params, returnType, body, ctx.pos())
    }

    private fun parseType(ctx: AppLangParser.TypeContext): Type = when (ctx.text) {
        "Int" -> Type.INT
        "Bool" -> Type.BOOL
        "String" -> Type.STRING
        else -> error("Неизвестный тип в грамматике: ${ctx.text}")
    }

    private fun visitBlockNode(ctx: AppLangParser.BlockContext): Block {
        val statements = ctx.statement().map { visitStatementNode(it) }
        return Block(statements, ctx.pos())
    }

    private fun visitStatementNode(ctx: AppLangParser.StatementContext): Stmt = when {
        ctx.varDecl() != null -> visitVarDeclNode(ctx.varDecl())
        ctx.assignment() != null -> visitAssignmentNode(ctx.assignment())
        ctx.ifStmt() != null -> visitIfStmtNode(ctx.ifStmt())
        ctx.whileStmt() != null -> visitWhileStmtNode(ctx.whileStmt())
        ctx.returnStmt() != null -> visitReturnStmtNode(ctx.returnStmt())
        ctx.exprStmt() != null -> visitExprStmtNode(ctx.exprStmt())
        ctx.block() != null -> visitBlockNode(ctx.block())
        else -> error("Неизвестный вид statement в грамматике: ${ctx.text}")
    }

    private fun visitVarDeclNode(ctx: AppLangParser.VarDeclContext): VarDecl {
        val isMutable = ctx.VAR() != null
        val declaredType = ctx.type()?.let { parseType(it) }
        val initializer = visitExprNode(ctx.expr())
        return VarDecl(ctx.IDENT().text, isMutable, declaredType, initializer, ctx.pos())
    }

    private fun visitAssignmentNode(ctx: AppLangParser.AssignmentContext): Assign =
        Assign(ctx.IDENT().text, visitExprNode(ctx.expr()), ctx.pos())

    private fun visitIfStmtNode(ctx: AppLangParser.IfStmtContext): If {
        val condition = visitExprNode(ctx.expr())
        val thenBranch = visitBlockNode(ctx.thenBranch)
        val elseBranch = ctx.elseBranch?.let { visitBlockNode(it) }
        return If(condition, thenBranch, elseBranch, ctx.pos())
    }

    private fun visitWhileStmtNode(ctx: AppLangParser.WhileStmtContext): While =
        While(visitExprNode(ctx.expr()), visitBlockNode(ctx.block()), ctx.pos())

    private fun visitReturnStmtNode(ctx: AppLangParser.ReturnStmtContext): Return =
        Return(ctx.expr()?.let { visitExprNode(it) }, ctx.pos())

    private fun visitExprStmtNode(ctx: AppLangParser.ExprStmtContext): ExprStmt =
        ExprStmt(visitExprNode(ctx.expr()), ctx.pos())

    private fun visitExprNode(ctx: AppLangParser.ExprContext): Expr = when (ctx) {
        is AppLangParser.MulDivModContext -> BinaryOp(
            binaryOperatorOf(ctx.op.text), visitExprNode(ctx.left), visitExprNode(ctx.right), ctx.pos(),
        )
        is AppLangParser.AddSubContext -> BinaryOp(
            binaryOperatorOf(ctx.op.text), visitExprNode(ctx.left), visitExprNode(ctx.right), ctx.pos(),
        )
        is AppLangParser.RelationalContext -> BinaryOp(
            binaryOperatorOf(ctx.op.text), visitExprNode(ctx.left), visitExprNode(ctx.right), ctx.pos(),
        )
        is AppLangParser.EqualityContext -> BinaryOp(
            binaryOperatorOf(ctx.op.text), visitExprNode(ctx.left), visitExprNode(ctx.right), ctx.pos(),
        )
        is AppLangParser.LogicalAndContext -> BinaryOp(
            BinaryOperator.AND, visitExprNode(ctx.left), visitExprNode(ctx.right), ctx.pos(),
        )
        is AppLangParser.LogicalOrContext -> BinaryOp(
            BinaryOperator.OR, visitExprNode(ctx.left), visitExprNode(ctx.right), ctx.pos(),
        )
        is AppLangParser.LogicalNotContext -> UnaryOp(UnaryOperator.NOT, visitExprNode(ctx.operand), ctx.pos())
        is AppLangParser.UnaryMinusContext -> UnaryOp(UnaryOperator.NEG, visitExprNode(ctx.operand), ctx.pos())
        is AppLangParser.ParensContext -> visitExprNode(ctx.inner)
        is AppLangParser.CallContext -> Call(
            ctx.IDENT().text,
            ctx.argList()?.expr().orEmpty().map { visitExprNode(it) },
            ctx.pos(),
        )
        is AppLangParser.VarRefContext -> VarRef(ctx.IDENT().text, ctx.pos())
        is AppLangParser.IntLiteralContext -> IntLiteral(ctx.text.toInt(), ctx.pos())
        is AppLangParser.StringLiteralContext -> StringLiteral(unescapeString(ctx.text), ctx.pos())
        is AppLangParser.BoolLiteralContext -> BoolLiteral(ctx.text == "true", ctx.pos())
        else -> error("Неизвестный вид expr в грамматике: ${ctx.text}")
    }

    private fun binaryOperatorOf(op: String): BinaryOperator = when (op) {
        "+" -> BinaryOperator.ADD
        "-" -> BinaryOperator.SUB
        "*" -> BinaryOperator.MUL
        "/" -> BinaryOperator.DIV
        "%" -> BinaryOperator.MOD
        "==" -> BinaryOperator.EQ
        "!=" -> BinaryOperator.NE
        "<" -> BinaryOperator.LT
        ">" -> BinaryOperator.GT
        "<=" -> BinaryOperator.LE
        ">=" -> BinaryOperator.GE
        else -> error("Неизвестный бинарный оператор: $op")
    }

    /** Строковый литерал приходит из грамматики вместе с кавычками — убираем их и раскрываем escape-последовательности. */
    private fun unescapeString(raw: String): String {
        val body = raw.substring(1, raw.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                when (body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(body[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }
}
