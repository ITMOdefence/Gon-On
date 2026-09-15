package ru.itmo.applang.codegen

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
import ru.itmo.applang.bytecode.BytecodeModule
import ru.itmo.applang.bytecode.FunctionEntry
import ru.itmo.applang.bytecode.Instruction

private val BUILTIN_NAMES = setOf("print", "println")

/**
 * Транслирует типизированный AST (после успешного [ru.itmo.applang.semantic.TypeChecker]) в
 * [BytecodeModule]. Требует, чтобы у каждого [Expr] было заполнено `resolvedType`, а у
 * VarDecl/Assign/VarRef/FunctionDecl — соответствующие `resolvedSlot`/`resolvedLocalCount`;
 * без них TypeChecker не должен был пропустить программу дальше.
 *
 * Соглашение о передаче аргументов при вызове функции (см. docs/BYTECODE_SPEC.md): аргументы
 * вычисляются и кладутся на стек в ОБРАТНОМ порядке (последний аргумент — первым), поэтому
 * первый pop на стороне ВМ соответствует первому параметру. Это сделано, чтобы у ВМ был простой
 * инвариант "N раз pop -> присвоить param[0..N-1] по возрастанию", без разворота списка.
 */
class BytecodeEmitter {

    private val constantPool = ConstantPoolBuilder()

    fun emit(program: Program): BytecodeModule {
        val functionIndexByName = program.functions.withIndex().associate { (index, f) -> f.name to index }
        val entryIndex = functionIndexByName["main"]
            ?: throw IllegalArgumentException(
                "в программе нет функции 'main' — v1 требует явную точку входа (см. LANGUAGE_SPEC.md)",
            )

        val functions = program.functions.map { function ->
            emitFunction(function, functionIndexByName)
        }

        return BytecodeModule(
            versionMajor = BytecodeModule.CURRENT_VERSION_MAJOR,
            versionMinor = BytecodeModule.CURRENT_VERSION_MINOR,
            constants = constantPool.build(),
            functions = functions,
            entryFunctionIndex = entryIndex,
        )
    }

    private fun emitFunction(function: FunctionDecl, functionIndexByName: Map<String, Int>): FunctionEntry {
        val buffer = InstructionBuffer()
        emitBlock(function.body, buffer, functionIndexByName)
        // Функции с типом возврата Unit могут не содержать явного return — гарантируем корректное
        // завершение кадра, если тело "провалилось" до конца без него.
        if (function.returnType == Type.UNIT) {
            buffer.emit(Instruction.ReturnVoid)
        }

        val nameConstIndex = constantPool.stringConst(function.name)
        check(function.resolvedLocalCount >= 0) {
            "TypeChecker не заполнил resolvedLocalCount для функции '${function.name}'"
        }
        return FunctionEntry(
            name = function.name,
            nameConstIndex = nameConstIndex,
            paramCount = function.params.size,
            localCount = function.resolvedLocalCount,
            instructions = buffer.build(),
        )
    }

    private fun emitBlock(block: Block, buffer: InstructionBuffer, funcs: Map<String, Int>) {
        for (stmt in block.statements) {
            emitStmt(stmt, buffer, funcs)
        }
    }

    private fun emitStmt(stmt: Stmt, buffer: InstructionBuffer, funcs: Map<String, Int>) {
        when (stmt) {
            is Block -> emitBlock(stmt, buffer, funcs)

            is VarDecl -> {
                emitExpr(stmt.initializer, buffer, funcs)
                buffer.emit(Instruction.StoreLocal(stmt.resolvedSlot))
            }

            is Assign -> {
                emitExpr(stmt.value, buffer, funcs)
                buffer.emit(Instruction.StoreLocal(stmt.resolvedSlot))
            }

            is If -> emitIf(stmt, buffer, funcs)
            is While -> emitWhile(stmt, buffer, funcs)

            is Return -> {
                if (stmt.value != null) {
                    emitExpr(stmt.value, buffer, funcs)
                    buffer.emit(Instruction.Return)
                } else {
                    buffer.emit(Instruction.ReturnVoid)
                }
            }

            is ExprStmt -> {
                emitExpr(stmt.expr, buffer, funcs)
                // Если выражение что-то оставило на стеке (не Unit) и это значение никому не
                // нужно как statement — снимаем его, иначе стек рассинхронизируется со следующей
                // инструкцией.
                if (stmt.expr.resolvedType != Type.UNIT) {
                    buffer.emit(Instruction.Pop)
                }
            }
        }
    }

    private fun emitIf(stmt: If, buffer: InstructionBuffer, funcs: Map<String, Int>) {
        emitExpr(stmt.condition, buffer, funcs)
        if (stmt.elseBranch == null) {
            val endLabel = buffer.newLabel()
            buffer.emitJumpIfFalse(endLabel)
            emitBlock(stmt.thenBranch, buffer, funcs)
            buffer.placeLabel(endLabel)
        } else {
            val elseLabel = buffer.newLabel()
            val endLabel = buffer.newLabel()
            buffer.emitJumpIfFalse(elseLabel)
            emitBlock(stmt.thenBranch, buffer, funcs)
            buffer.emitJump(endLabel)
            buffer.placeLabel(elseLabel)
            emitBlock(stmt.elseBranch, buffer, funcs)
            buffer.placeLabel(endLabel)
        }
    }

    private fun emitWhile(stmt: While, buffer: InstructionBuffer, funcs: Map<String, Int>) {
        val startLabel = buffer.newLabel()
        val endLabel = buffer.newLabel()
        buffer.placeLabel(startLabel)
        emitExpr(stmt.condition, buffer, funcs)
        buffer.emitJumpIfFalse(endLabel)
        emitBlock(stmt.body, buffer, funcs)
        buffer.emitJump(startLabel)
        buffer.placeLabel(endLabel)
    }

    private fun emitExpr(expr: Expr, buffer: InstructionBuffer, funcs: Map<String, Int>) {
        when (expr) {
            is IntLiteral -> buffer.emit(Instruction.LoadConst(constantPool.intConst(expr.value)))
            is BoolLiteral -> buffer.emit(Instruction.LoadConst(constantPool.boolConst(expr.value)))
            is StringLiteral -> buffer.emit(Instruction.LoadConst(constantPool.stringConst(expr.value)))

            is VarRef -> buffer.emit(Instruction.LoadLocal(expr.resolvedSlot))

            is UnaryOp -> {
                emitExpr(expr.operand, buffer, funcs)
                buffer.emit(if (expr.operator == UnaryOperator.NEG) Instruction.Neg else Instruction.Not)
            }

            is BinaryOp -> emitBinaryOp(expr, buffer, funcs)

            is Call -> emitCall(expr, buffer, funcs)
        }
    }

    private fun emitBinaryOp(expr: BinaryOp, buffer: InstructionBuffer, funcs: Map<String, Int>) {
        when (expr.operator) {
            BinaryOperator.AND -> emitShortCircuitAnd(expr, buffer, funcs)
            BinaryOperator.OR -> emitShortCircuitOr(expr, buffer, funcs)
            else -> {
                emitExpr(expr.left, buffer, funcs)
                emitExpr(expr.right, buffer, funcs)
                buffer.emit(simpleBinaryInstruction(expr))
            }
        }
    }

    private fun simpleBinaryInstruction(expr: BinaryOp): Instruction {
        val isStringConcat = expr.operator == BinaryOperator.ADD && expr.left.resolvedType == Type.STRING
        return when (expr.operator) {
            BinaryOperator.ADD -> if (isStringConcat) Instruction.Concat else Instruction.Add
            BinaryOperator.SUB -> Instruction.Sub
            BinaryOperator.MUL -> Instruction.Mul
            BinaryOperator.DIV -> Instruction.Div
            BinaryOperator.MOD -> Instruction.Mod
            BinaryOperator.EQ -> Instruction.CmpEq
            BinaryOperator.NE -> Instruction.CmpNe
            BinaryOperator.LT -> Instruction.CmpLt
            BinaryOperator.GT -> Instruction.CmpGt
            BinaryOperator.LE -> Instruction.CmpLe
            BinaryOperator.GE -> Instruction.CmpGe
            BinaryOperator.AND, BinaryOperator.OR ->
                error("AND/OR обрабатываются отдельно через short-circuit, не через simpleBinaryInstruction")
        }
    }

    /** a && b: если a ложно — результат false без вычисления b (short-circuit). */
    private fun emitShortCircuitAnd(expr: BinaryOp, buffer: InstructionBuffer, funcs: Map<String, Int>) {
        val falseLabel = buffer.newLabel()
        val endLabel = buffer.newLabel()
        emitExpr(expr.left, buffer, funcs)
        buffer.emitJumpIfFalse(falseLabel)
        emitExpr(expr.right, buffer, funcs)
        buffer.emitJump(endLabel)
        buffer.placeLabel(falseLabel)
        buffer.emit(Instruction.LoadConst(constantPool.boolConst(false)))
        buffer.placeLabel(endLabel)
    }

    /** a || b: если a истинно — результат true без вычисления b (short-circuit). */
    private fun emitShortCircuitOr(expr: BinaryOp, buffer: InstructionBuffer, funcs: Map<String, Int>) {
        val evalRightLabel = buffer.newLabel()
        val endLabel = buffer.newLabel()
        emitExpr(expr.left, buffer, funcs)
        buffer.emitJumpIfFalse(evalRightLabel)
        buffer.emit(Instruction.LoadConst(constantPool.boolConst(true)))
        buffer.emitJump(endLabel)
        buffer.placeLabel(evalRightLabel)
        emitExpr(expr.right, buffer, funcs)
        buffer.placeLabel(endLabel)
    }

    private fun emitCall(expr: Call, buffer: InstructionBuffer, funcs: Map<String, Int>) {
        if (expr.functionName in BUILTIN_NAMES) {
            emitExpr(expr.args.single(), buffer, funcs)
            buffer.emit(if (expr.functionName == "println") Instruction.Println else Instruction.Print)
            return
        }

        // Аргументы кладутся на стек в обратном порядке — см. комментарий к классу.
        for (arg in expr.args.asReversed()) {
            emitExpr(arg, buffer, funcs)
        }
        val functionIndex = funcs.getValue(expr.functionName)
        buffer.emit(Instruction.Call(functionIndex))
    }
}
