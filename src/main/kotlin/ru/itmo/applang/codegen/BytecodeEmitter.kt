package ru.itmo.applang.codegen

import ru.itmo.applang.ast.Assign
import ru.itmo.applang.ast.BinaryOp
import ru.itmo.applang.ast.BinaryOperator
import ru.itmo.applang.ast.Block
import ru.itmo.applang.ast.BoolLiteral
import ru.itmo.applang.ast.Call
import ru.itmo.applang.ast.ClassDecl
import ru.itmo.applang.ast.Expr
import ru.itmo.applang.ast.ExprStmt
import ru.itmo.applang.ast.FieldAccess
import ru.itmo.applang.ast.FieldAssign
import ru.itmo.applang.ast.FunctionDecl
import ru.itmo.applang.ast.If
import ru.itmo.applang.ast.IntLiteral
import ru.itmo.applang.ast.MethodCall
import ru.itmo.applang.ast.Program
import ru.itmo.applang.ast.Return
import ru.itmo.applang.ast.Stmt
import ru.itmo.applang.ast.StringLiteral
import ru.itmo.applang.ast.This
import ru.itmo.applang.ast.Type
import ru.itmo.applang.ast.UnaryOp
import ru.itmo.applang.ast.UnaryOperator
import ru.itmo.applang.ast.VarDecl
import ru.itmo.applang.ast.VarRef
import ru.itmo.applang.ast.While
import ru.itmo.applang.bytecode.BytecodeModule
import ru.itmo.applang.bytecode.ClassEntry
import ru.itmo.applang.bytecode.FieldEntry
import ru.itmo.applang.bytecode.FieldType
import ru.itmo.applang.bytecode.FormatLimits
import ru.itmo.applang.bytecode.FunctionEntry
import ru.itmo.applang.bytecode.Instruction
import ru.itmo.applang.semantic.ENTRY_POINT_NAME
import java.util.IdentityHashMap

private val BUILTIN_NAMES = setOf("print", "println")

/** Слот неявного `this` у методов и функций инициализации. */
private const val THIS_SLOT = 0

/**
 * Программа корректна по типам, но не помещается в формат `.albc`: например, пул констант после
 * дедупликации больше 65535 записей. Это ошибка компиляции с диагностикой LIMIT_EXCEEDED, а не
 * внутренняя ошибка генератора.
 */
class CodegenLimitException(message: String) : Exception(message)

/**
 * Транслирует типизированный AST (после успешного [ru.itmo.applang.semantic.TypeChecker]) в
 * [BytecodeModule]. Требует, чтобы у каждого [Expr] было заполнено `resolvedType`, а у узлов —
 * соответствующие `resolvedSlot`/`resolvedLocalCount`/разрешённые члены; без них TypeChecker не
 * должен был пропустить программу дальше.
 *
 * Соглашение о передаче аргументов при вызове функции (см. docs/BYTECODE_SPEC.md): аргументы
 * вычисляются и кладутся на стек в ОБРАТНОМ порядке (последний аргумент — первым), поэтому
 * первый pop на стороне ВМ соответствует первому параметру. Это сделано, чтобы у ВМ был простой
 * инвариант "N раз pop -> присвоить param[0..N-1] по возрастанию", без разворота списка.
 *
 * Программа без классов компилируется в профиль 1.0, с классами — в 2.0. Таблица функций
 * упорядочена по исходнику: функция верхнего уровня занимает одну запись, класс — функцию
 * инициализации `Класс.init` и затем свои методы `Класс.метод` в порядке объявления.
 */
class BytecodeEmitter {

    private val constantPool = ConstantPoolBuilder()

    /** Индекс в общей таблице функций: ключ — [FunctionDecl] функции/метода или [ClassDecl] для его init. */
    private val functionIndex = IdentityHashMap<Any, Int>()
    private val topLevelFunctionIndex = mutableMapOf<String, Int>()
    private val classIndex = IdentityHashMap<ClassDecl, Int>()

    fun emit(program: Program): BytecodeModule {
        val isObjectProfile = program.classes.isNotEmpty()
        val layout = functionLayout(program)
        layout.forEachIndexed { index, slot -> functionIndex[slot.key] = index }
        program.functions.forEach { topLevelFunctionIndex[it.name] = functionIndex.getValue(it) }
        program.classes.forEachIndexed { index, classDecl -> classIndex[classDecl] = index }

        // Наличие и сигнатуру main проверяет TypeChecker (MISSING_MAIN / INVALID_MAIN_SIGNATURE);
        // здесь её отсутствие — нарушение инварианта пайплайна, а не ошибка пользователя.
        val entryIndex = checkNotNull(topLevelFunctionIndex[ENTRY_POINT_NAME]) {
            "TypeChecker пропустил программу без функции '$ENTRY_POINT_NAME'"
        }

        // В профиле 2.0 метаданные (имена классов, полей, функций) занимают начало пула в порядке
        // таблиц — так раскладка совпадает с эталоном docs/backlog/bytecode-contract.md §11.
        // Профиль 1.0 сохраняет прежний порядок: имя функции после констант её кода.
        val classes = if (isObjectProfile) {
            val entries = program.classes.map { classEntry(it) }
            layout.forEach { constantPool.stringConst(it.debugName) }
            entries
        } else {
            emptyList()
        }

        val functions = layout.map { it.emit() }

        // Предел проверяется по фактическому пулу после дедупликации, включая имена.
        val constants = constantPool.build()
        if (constants.size > FormatLimits.MAX_TABLE_ENTRIES) {
            throw CodegenLimitException(
                "пул констант после дедупликации содержит ${constants.size} записей " +
                    "(включая имена функций, классов и полей), максимум ${FormatLimits.MAX_TABLE_ENTRIES} (constant_count: u16)",
            )
        }

        return BytecodeModule(
            versionMajor = if (isObjectProfile) BytecodeModule.OBJECT_VERSION_MAJOR else BytecodeModule.PROCEDURAL_VERSION_MAJOR,
            versionMinor = BytecodeModule.VERSION_MINOR,
            constants = constants,
            functions = functions,
            entryFunctionIndex = entryIndex,
            classes = classes,
        )
    }

    /** Запись будущей таблицы функций и способ сгенерировать её код. */
    private inner class FunctionSlot(val key: Any, val debugName: String, val emit: () -> FunctionEntry)

    private fun functionLayout(program: Program): List<FunctionSlot> = program.declarations.flatMap { declaration ->
        when (declaration) {
            is FunctionDecl -> listOf(
                FunctionSlot(declaration, declaration.name) { emitFunction(declaration, declaration.name, hasThis = false) },
            )
            is ClassDecl -> listOf(
                FunctionSlot(declaration, "${declaration.name}.init") { emitInitializer(declaration) },
            ) + declaration.methods.map { method ->
                val name = "${declaration.name}.${method.name}"
                FunctionSlot(method, name) { emitFunction(method, name, hasThis = true) }
            }
        }
    }

    private fun classEntry(classDecl: ClassDecl): ClassEntry = ClassEntry(
        name = classDecl.name,
        nameConstIndex = constantPool.stringConst(classDecl.name),
        fields = classDecl.fields.map { field ->
            FieldEntry(field.name, constantPool.stringConst(field.name), fieldType(field.type))
        },
        methodFunctionIndices = classDecl.methods.map { functionIndex.getValue(it) },
    )

    private fun fieldType(type: Type): FieldType = when (type) {
        Type.INT -> FieldType.IntType
        Type.BOOL -> FieldType.BoolType
        Type.STRING -> FieldType.StringType
        is Type.ClassType -> FieldType.ObjectType(
            classIndex.entries.single { it.key.name == type.name }.value,
        )
        Type.UNIT -> error("Unit не может быть типом поля — TypeChecker должен был это отклонить")
    }

    private fun emitFunction(function: FunctionDecl, name: String, hasThis: Boolean): FunctionEntry {
        check(function.resolvedLocalCount >= 0) {
            "TypeChecker не заполнил resolvedLocalCount для функции '$name'"
        }
        val body = FunctionBodyEmitter(function.resolvedTempBase)
        val bodyCompletesNormally = body.emitBlock(function.body)
        // Функции с типом возврата Unit могут не содержать явного return — гарантируем корректное
        // завершение кадра, если тело может "провалиться" до конца без него.
        if (function.returnType == Type.UNIT && bodyCompletesNormally) {
            body.buffer.emit(Instruction.ReturnVoid)
        }
        return FunctionEntry(
            name = name,
            nameConstIndex = constantPool.stringConst(name),
            paramCount = function.params.size + if (hasThis) 1 else 0,
            localCount = function.resolvedLocalCount,
            instructions = body.buffer.build(),
        )
    }

    /**
     * Функция инициализации класса: `this` в слоте 0 и параметры конструктора; инициализаторы полей
     * в порядке объявления, затем тело конструктора и RETURN_VOID. Результата нет — ссылку на
     * созданный объект выражение создания берёт из своего временного слота.
     */
    private fun emitInitializer(classDecl: ClassDecl): FunctionEntry {
        val name = "${classDecl.name}.init"
        check(classDecl.resolvedInitLocalCount >= 0) { "TypeChecker не заполнил слоты функции '$name'" }
        val body = FunctionBodyEmitter(classDecl.resolvedInitTempBase)
        val ownIndex = classIndex.getValue(classDecl)
        classDecl.fields.forEachIndexed { fieldIndex, field ->
            val initializer = field.initializer ?: return@forEachIndexed
            body.buffer.emit(Instruction.LoadLocal(THIS_SLOT))
            body.emitExpr(initializer)
            body.buffer.emit(Instruction.SetField(ownIndex, fieldIndex))
        }
        classDecl.constructor?.let { body.emitBlock(it.body) }
        body.buffer.emit(Instruction.ReturnVoid)
        return FunctionEntry(
            name = name,
            nameConstIndex = constantPool.stringConst(name),
            paramCount = 1 + (classDecl.constructor?.params?.size ?: 0),
            localCount = classDecl.resolvedInitLocalCount,
            instructions = body.buffer.build(),
        )
    }

    /** Генерация тела одной функции; временный слот глубины `d` — `tempBase + d`. */
    private inner class FunctionBodyEmitter(private val tempBase: Int) {
        val buffer = InstructionBuffer()

        private fun tempSlot(depth: Int): Int {
            check(tempBase >= 0 && depth >= 0) { "TypeChecker не назначил временный слот" }
            return tempBase + depth
        }

        /**
         * Генерирует код блока и возвращает, может ли выполнение продолжиться после него
         * ("завершается нормально"), или все пути блока заканчиваются возвратом. Операторы после
         * гарантированного возврата недостижимы и не генерируются: GVM проверяет даже недостижимые
         * инструкции, поэтому лишний код — лишний риск невалидной структуры (BASE-01).
         */
        fun emitBlock(block: Block): Boolean {
            for (stmt in block.statements) {
                if (!emitStmt(stmt)) return false
            }
            return true
        }

        /** Генерирует код оператора; возвращает true, если выполнение может продолжиться после него. */
        private fun emitStmt(stmt: Stmt): Boolean {
            when (stmt) {
                is Block -> return emitBlock(stmt)

                is VarDecl -> {
                    emitExpr(stmt.initializer)
                    buffer.emit(Instruction.StoreLocal(stmt.resolvedSlot))
                }

                is Assign -> {
                    emitExpr(stmt.value)
                    buffer.emit(Instruction.StoreLocal(stmt.resolvedSlot))
                }

                // receiver вычисляется раньше правой части и остаётся ниже значения на стеке.
                is FieldAssign -> {
                    emitExpr(stmt.receiver)
                    emitExpr(stmt.value)
                    buffer.emit(Instruction.SetField(stmt.resolvedClassIndex, stmt.resolvedFieldIndex))
                }

                is If -> return emitIf(stmt)
                is While -> return emitWhile(stmt)

                is Return -> {
                    if (stmt.value != null) {
                        emitExpr(stmt.value)
                        buffer.emit(Instruction.Return)
                    } else {
                        buffer.emit(Instruction.ReturnVoid)
                    }
                    return false
                }

                is ExprStmt -> {
                    emitExpr(stmt.expr)
                    // Если выражение что-то оставило на стеке (не Unit) и это значение никому не
                    // нужно как statement — снимаем его, иначе стек рассинхронизируется со следующей
                    // инструкцией.
                    if (stmt.expr.resolvedType != Type.UNIT) {
                        buffer.emit(Instruction.Pop)
                    }
                }
            }
            return true
        }

        /**
         * Переход к общей конечной метке ставится после then-ветки только если она может завершиться
         * нормально: после ветки, которая всегда возвращает, такой JUMP недостижим, а когда обе ветки
         * возвращают, его цель совпала бы с концом функции (`target == code_length`), что GVM
         * отвергает (BASE-01).
         */
        private fun emitIf(stmt: If): Boolean {
            emitExpr(stmt.condition)
            if (stmt.elseBranch == null) {
                val endLabel = buffer.newLabel()
                buffer.emitJumpIfFalse(endLabel)
                emitBlock(stmt.thenBranch)
                buffer.placeLabel(endLabel)
                return true
            }
            val elseLabel = buffer.newLabel()
            val endLabel = buffer.newLabel()
            buffer.emitJumpIfFalse(elseLabel)
            val thenCompletes = emitBlock(stmt.thenBranch)
            if (thenCompletes) {
                buffer.emitJump(endLabel)
            }
            buffer.placeLabel(elseLabel)
            val elseCompletes = emitBlock(stmt.elseBranch)
            buffer.placeLabel(endLabel)
            return thenCompletes || elseCompletes
        }

        /**
         * Цикл считается завершающимся нормально всегда: условие не вычисляется статически, а выход
         * по `JUMP_IF_FALSE` — допустимый путь. Обратный переход не нужен, если тело всегда возвращает.
         */
        private fun emitWhile(stmt: While): Boolean {
            val startLabel = buffer.newLabel()
            val endLabel = buffer.newLabel()
            buffer.placeLabel(startLabel)
            emitExpr(stmt.condition)
            buffer.emitJumpIfFalse(endLabel)
            if (emitBlock(stmt.body)) {
                buffer.emitJump(startLabel)
            }
            buffer.placeLabel(endLabel)
            return true
        }

        fun emitExpr(expr: Expr) {
            when (expr) {
                is IntLiteral -> buffer.emit(Instruction.LoadConst(constantPool.intConst(expr.value)))
                is BoolLiteral -> buffer.emit(Instruction.LoadConst(constantPool.boolConst(expr.value)))
                is StringLiteral -> buffer.emit(Instruction.LoadConst(constantPool.stringConst(expr.value)))

                is VarRef -> buffer.emit(Instruction.LoadLocal(expr.resolvedSlot))
                is This -> buffer.emit(Instruction.LoadLocal(THIS_SLOT))

                is UnaryOp -> {
                    emitExpr(expr.operand)
                    buffer.emit(if (expr.operator == UnaryOperator.NEG) Instruction.Neg else Instruction.Not)
                }

                is BinaryOp -> emitBinaryOp(expr)

                is Call -> emitCall(expr)

                is FieldAccess -> {
                    emitExpr(expr.receiver)
                    buffer.emit(Instruction.GetField(expr.resolvedClassIndex, expr.resolvedFieldIndex))
                }

                is MethodCall -> emitMethodCall(expr)
            }
        }

        private fun emitBinaryOp(expr: BinaryOp) {
            when (expr.operator) {
                BinaryOperator.AND -> emitShortCircuitAnd(expr)
                BinaryOperator.OR -> emitShortCircuitOr(expr)
                else -> {
                    emitExpr(expr.left)
                    emitExpr(expr.right)
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
        private fun emitShortCircuitAnd(expr: BinaryOp) {
            val falseLabel = buffer.newLabel()
            val endLabel = buffer.newLabel()
            emitExpr(expr.left)
            buffer.emitJumpIfFalse(falseLabel)
            emitExpr(expr.right)
            buffer.emitJump(endLabel)
            buffer.placeLabel(falseLabel)
            buffer.emit(Instruction.LoadConst(constantPool.boolConst(false)))
            buffer.placeLabel(endLabel)
        }

        /** a || b: если a истинно — результат true без вычисления b (short-circuit). */
        private fun emitShortCircuitOr(expr: BinaryOp) {
            val evalRightLabel = buffer.newLabel()
            val endLabel = buffer.newLabel()
            emitExpr(expr.left)
            buffer.emitJumpIfFalse(evalRightLabel)
            buffer.emit(Instruction.LoadConst(constantPool.boolConst(true)))
            buffer.emitJump(endLabel)
            buffer.placeLabel(evalRightLabel)
            emitExpr(expr.right)
            buffer.placeLabel(endLabel)
        }

        private fun emitCall(expr: Call) {
            if (expr.functionName in BUILTIN_NAMES) {
                emitExpr(expr.args.single())
                buffer.emit(if (expr.functionName == "println") Instruction.Println else Instruction.Print)
                return
            }

            // Аргументы кладутся на стек в обратном порядке — см. комментарий к классу.
            for (arg in expr.args.asReversed()) {
                emitExpr(arg)
            }

            val constructed = expr.resolvedConstructorClass
            if (constructed == null) {
                buffer.emit(Instruction.Call(topLevelFunctionIndex.getValue(expr.functionName)))
                return
            }

            // Создание: выделить экземпляр, сохранить ссылку во временном слоте, вызвать функцию
            // инициализации с this на вершине (аргументы уже лежат ниже), затем выдать ссылку как
            // результат выражения. Временный слот удерживает объект для GC на всё время инициализации.
            val temp = tempSlot(expr.resolvedTempDepth)
            buffer.emit(Instruction.AllocObject(classIndex.getValue(constructed)))
            buffer.emit(Instruction.StoreLocal(temp))
            buffer.emit(Instruction.LoadLocal(temp))
            buffer.emit(Instruction.Call(functionIndex.getValue(constructed)))
            buffer.emit(Instruction.LoadLocal(temp))
        }

        /**
         * Вызов метода: receiver вычисляется первым и ровно один раз, сохраняется во временном
         * слоте, затем аргументы справа налево, и receiver загружается последним — он оказывается
         * на вершине и попадает в слот 0 (`this`) вызываемого кадра.
         */
        private fun emitMethodCall(expr: MethodCall) {
            val temp = tempSlot(expr.resolvedTempDepth)
            emitExpr(expr.receiver)
            buffer.emit(Instruction.StoreLocal(temp))
            for (arg in expr.args.asReversed()) {
                emitExpr(arg)
            }
            buffer.emit(Instruction.LoadLocal(temp))
            val method = checkNotNull(expr.resolvedMethod) { "TypeChecker не разрешил метод '${expr.methodName}'" }
            buffer.emit(Instruction.Call(functionIndex.getValue(method)))
        }
    }
}
