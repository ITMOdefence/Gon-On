package ru.itmo.applang.semantic

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
import ru.itmo.applang.ast.Param
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
import ru.itmo.applang.bytecode.FormatLimits
import ru.itmo.applang.bytecode.StringConstraints
import ru.itmo.applang.diagnostics.DiagnosticCollector
import ru.itmo.applang.diagnostics.SourcePosition
import java.util.BitSet

/** Встроенные функции. Не являются пользовательскими функциями и не попадают в [FunctionTable]. */
private val BUILTIN_NAMES = setOf("print", "println")

/** Имя функции — точки входа исполняемой программы. */
const val ENTRY_POINT_NAME = "main"

/** Типы, значения которых умеют печатать PRINT/PRINTLN GVM. */
private val PRINTABLE_TYPES = setOf(Type.INT, Type.BOOL, Type.STRING)

/** Типы, для которых определены CMP_EQ/CMP_NE GVM; сравнение объектов не определено. */
private val COMPARABLE_TYPES = setOf(Type.INT, Type.BOOL, Type.STRING)

/** Имя скрытой переменной `this` в слоте 0: ключевое слово, поэтому из исходника недоступно как имя. */
private const val THIS_SLOT_NAME = "this"

/**
 * Состояние заполненности полей при проверке функции инициализации (LANG-06, LANG-07).
 * [assigned] — поля, гарантированно заполненные на всех путях до текущей точки;
 * [maybeAssigned] — поля, которые могли быть записаны хотя бы на одном пути (для однократности val).
 */
private class InitState(val fieldCount: Int) {
    var assigned = BitSet(fieldCount)
    var maybeAssigned = BitSet(fieldCount)

    /** Глубина вложенности циклов: запись val-поля в цикле может выполниться несколько раз. */
    var loopDepth = 0

    /** Экземпляр готов, когда все поля гарантированно заполнены: дальше this можно использовать свободно. */
    val isReady: Boolean get() = assigned.cardinality() == fieldCount

    fun snapshot(): Pair<BitSet, BitSet> = assigned.clone() as BitSet to maybeAssigned.clone() as BitSet

    fun restore(state: Pair<BitSet, BitSet>) {
        assigned = state.first.clone() as BitSet
        maybeAssigned = state.second.clone() as BitSet
    }

    /** Слияние двух путей: заполнено то, что заполнено на обоих; «возможно записано» — на любом. */
    fun merge(left: Pair<BitSet, BitSet>, right: Pair<BitSet, BitSet>) {
        assigned = (left.first.clone() as BitSet).apply { and(right.first) }
        maybeAssigned = (left.second.clone() as BitSet).apply { or(right.second) }
    }
}

/**
 * Контекст проверки тела одной функции: функции верхнего уровня, метода или функции
 * инициализации класса.
 */
private class FunctionContext(
    val scope: LocalScope,
    val returnType: Type,
    /** Класс, которому принадлежит `this`; null у функций верхнего уровня. */
    val owner: ClassInfo?,
    /** Не null только при проверке функции инициализации (инициализаторы полей + конструктор). */
    val initState: InitState?,
) {
    /** Текущая глубина живых скрытых временных слотов (receiver метода, создаваемый объект). */
    var tempDepth = 0
    var maxTempDepth = -1

    fun useTempSlot(): Int {
        maxTempDepth = maxOf(maxTempDepth, tempDepth)
        return tempDepth
    }
}

/**
 * Проверяет имена и типы во всей программе, заполняя [Expr.resolvedType] у каждого выражения и
 * разрешённые слоты / члены у узлов. Диагностики собираются в переданный [DiagnosticCollector];
 * codegen обязан запускаться только если после проверки [DiagnosticCollector.hasErrors] == false.
 *
 * Упрощение: вместо полного контроль-флоу анализа "все пути возвращают значение" используется
 * структурное правило (см. [stmtAlwaysReturns] и docs/LANGUAGE_SPEC.md, §5.4).
 */
class TypeChecker(private val collector: DiagnosticCollector) {

    private val functionTable = FunctionTable()
    private val classes = linkedMapOf<String, ClassInfo>()

    fun check(program: Program) {
        collectClasses(program)
        collectFunctionSignatures(program)
        collectClassMembers()
        checkEntryPoint(program)
        checkTableLimits(program)
        for (declaration in program.declarations) {
            when (declaration) {
                is FunctionDecl -> checkFunctionBody(declaration, owner = null)
                is ClassDecl -> {
                    val info = classes[declaration.name]?.takeIf { it.decl === declaration } ?: continue
                    checkInitializer(info)
                    declaration.methods.forEach { checkFunctionBody(it, owner = info) }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Объявления
    // ------------------------------------------------------------------------------------

    /** Первый проход по классам: имена известны до проверки любых типов, поэтому ссылки вперёд допустимы. */
    private fun collectClasses(program: Program) {
        for (classDecl in program.classes) {
            if (classDecl.name in BUILTIN_NAMES) {
                collector.error(
                    SemanticErrorCodes.NAME_CONFLICT,
                    "класс не может называться '${classDecl.name}': это встроенная функция",
                    classDecl.position,
                )
            }
            if (classDecl.name in classes) {
                collector.error(SemanticErrorCodes.DUPLICATE_CLASS, "класс '${classDecl.name}' уже объявлен", classDecl.position)
                continue
            }
            classes[classDecl.name] = ClassInfo(classDecl, classes.size)
        }
    }

    private fun collectFunctionSignatures(program: Program) {
        for (function in program.functions) {
            checkParamCount(function.name, function.params, implicitThis = false, function.position)
            if (function.name in BUILTIN_NAMES) {
                collector.error(
                    SemanticErrorCodes.NAME_CONFLICT,
                    "функция не может называться '${function.name}': это встроенная функция",
                    function.position,
                )
            }
            if (function.name in classes) {
                collector.error(
                    SemanticErrorCodes.NAME_CONFLICT,
                    "имя '${function.name}' уже занято классом: классы и функции верхнего уровня — одно пространство имён",
                    function.position,
                )
            }
            val signature = signatureOf(function)
            if (!functionTable.declare(signature)) {
                collector.error(
                    SemanticErrorCodes.DUPLICATE_FUNCTION,
                    "функция '${function.name}' уже объявлена",
                    function.position,
                )
            }
        }
    }

    /** Поля (с устойчивыми номерами), сигнатуры методов и конструктора каждого класса. */
    private fun collectClassMembers() {
        for (info in classes.values) {
            val decl = info.decl
            val memberNames = mutableSetOf<String>()
            for (field in decl.fields) {
                if (!memberNames.add(field.name)) {
                    collector.error(
                        SemanticErrorCodes.DUPLICATE_MEMBER,
                        "в классе '${decl.name}' уже есть член с именем '${field.name}'",
                        field.position,
                    )
                    continue
                }
                resolveDeclaredType(field.type, field.position)
                info.fields += FieldInfo(field.name, info.fields.size, field.type, field.isMutable, field)
            }
            for (method in decl.methods) {
                checkParamCount("${decl.name}.${method.name}", method.params, implicitThis = true, method.position)
                if (!memberNames.add(method.name)) {
                    collector.error(
                        SemanticErrorCodes.DUPLICATE_MEMBER,
                        "в классе '${decl.name}' уже есть член с именем '${method.name}'",
                        method.position,
                    )
                    continue
                }
                info.methods[method.name] = MethodInfo(info, method, signatureOf(method))
            }
            decl.constructors.drop(1).forEach {
                collector.error(
                    SemanticErrorCodes.DUPLICATE_MEMBER,
                    "в классе '${decl.name}' может быть не более одного конструктора",
                    it.position,
                )
            }
            decl.constructor?.let { constructor ->
                checkParamCount("конструктор '${decl.name}'", constructor.params, implicitThis = true, constructor.position)
                constructor.params.forEach { resolveDeclaredType(it.type, it.position) }
                info.constructorParamTypes = constructor.params.map { it.type }
            }
        }
    }

    private fun signatureOf(function: FunctionDecl): FunctionSignature {
        function.params.forEach { resolveDeclaredType(it.type, it.position) }
        resolveDeclaredType(function.returnType, function.position)
        return FunctionSignature(function.name, function.params.map { it.type }, function.returnType)
    }

    /** Проверяет, что объявленный тип существует. Возвращает false для неизвестного класса. */
    private fun resolveDeclaredType(type: Type, position: SourcePosition): Boolean {
        if (type is Type.ClassType && type.name !in classes) {
            collector.error(SemanticErrorCodes.UNDEFINED_CLASS, "класс '${type.name}' не объявлен", position)
            return false
        }
        return true
    }

    private fun checkParamCount(name: String, params: List<Param>, implicitThis: Boolean, position: SourcePosition) {
        val max = if (implicitThis) FormatLimits.MAX_PARAMETERS - 1 else FormatLimits.MAX_PARAMETERS
        if (params.size > max) {
            val reason = if (implicitThis) " (неявный this занимает ещё один из ${FormatLimits.MAX_PARAMETERS})" else ""
            collector.error(
                SemanticErrorCodes.LIMIT_EXCEEDED,
                "'$name' объявляет ${params.size} параметров, максимум $max$reason (param_count: u8)",
                position,
            )
        }
    }

    /** Общая таблица функций содержит функции, методы и по одной функции инициализации на класс. */
    private fun checkTableLimits(program: Program) {
        val functionCount = program.functions.size + program.classes.sumOf { 1 + it.methods.size }
        if (functionCount > FormatLimits.MAX_TABLE_ENTRIES) {
            collector.error(
                SemanticErrorCodes.LIMIT_EXCEEDED,
                "в программе $functionCount функций (включая методы и функции инициализации классов), " +
                    "таблица функций вмещает не более ${FormatLimits.MAX_TABLE_ENTRIES}",
                null,
            )
        }
        if (program.classes.size > FormatLimits.MAX_TABLE_ENTRIES) {
            collector.error(
                SemanticErrorCodes.LIMIT_EXCEEDED,
                "в программе ${program.classes.size} классов, максимум ${FormatLimits.MAX_TABLE_ENTRIES}",
                null,
            )
        }
    }

    /**
     * Профиль исполняемой программы для стандартного раннера GVM: ровно одна функция `main` без
     * параметров (повторное объявление ловит общее правило дубликатов). Формат `.albc` хранит
     * только индекс entry; выбор функции по имени `main` и требование запуска без аргументов —
     * правило языка, а не загрузчика. Тип результата `main` не ограничивается: значение,
     * возвращённое `main`, не является кодом завершения процесса раннера.
     */
    private fun checkEntryPoint(program: Program) {
        val main = program.functions.firstOrNull { it.name == ENTRY_POINT_NAME }
        if (main == null) {
            collector.error(
                SemanticErrorCodes.MISSING_MAIN,
                "в программе нет функции '$ENTRY_POINT_NAME' — точки входа исполняемой программы",
                null,
            )
            return
        }
        if (main.params.isNotEmpty()) {
            collector.error(
                SemanticErrorCodes.INVALID_MAIN_SIGNATURE,
                "функция '$ENTRY_POINT_NAME' не должна иметь параметров: стандартный раннер GVM " +
                    "запускает точку входа без аргументов",
                main.position,
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // Тела функций
    // ------------------------------------------------------------------------------------

    private fun checkFunctionBody(function: FunctionDecl, owner: ClassInfo?) {
        val scope = LocalScope()
        scope.enterScope()
        owner?.let { scope.declare(THIS_SLOT_NAME, it.type, isMutable = false) }
        for (param in function.params) {
            scope.declare(param.name, param.type, isMutable = true)
        }
        val ctx = FunctionContext(scope, function.returnType, owner, initState = null)
        checkBlock(function.body, ctx)
        scope.exitScope()

        function.resolvedTempBase = scope.totalSlotsUsed()
        function.resolvedLocalCount = function.resolvedTempBase + ctx.maxTempDepth + 1
        val displayName = owner?.let { "${it.name}.${function.name}" } ?: function.name
        checkLocalCount(displayName, function.resolvedLocalCount, function.position)

        if (function.returnType != Type.UNIT && !blockAlwaysReturns(function.body)) {
            collector.error(
                SemanticErrorCodes.MISSING_RETURN,
                "функция '$displayName' должна гарантированно завершаться оператором return " +
                    "на всех путях выполнения (тип возврата ${function.returnType})",
                function.position,
            )
        }
    }

    /**
     * Функция инициализации класса: инициализаторы полей в порядке объявления, затем тело
     * конструктора (LANG-05). К её концу каждое поле должно быть гарантированно заполнено.
     */
    private fun checkInitializer(info: ClassInfo) {
        val decl = info.decl
        val scope = LocalScope()
        scope.enterScope()
        scope.declare(THIS_SLOT_NAME, info.type, isMutable = false)
        decl.constructor?.params?.forEach { scope.declare(it.name, it.type, isMutable = true) }
        val state = InitState(info.fields.size)
        val ctx = FunctionContext(scope, Type.UNIT, info, state)

        for (field in info.fields) {
            val initializer = field.decl.initializer ?: continue
            checkExpectedType(initializer, ctx, field.type, "инициализатор поля '${info.name}.${field.name}'")
            state.assigned.set(field.index)
            state.maybeAssigned.set(field.index)
        }
        decl.constructor?.let { checkBlock(it.body, ctx) }
        scope.exitScope()

        val missing = info.fields.filter { !state.assigned[it.index] }
        if (missing.isNotEmpty()) {
            val where = if (decl.constructor == null) "без конструктора все поля должны иметь инициализатор" else
                "поля должны быть заполнены на всех путях конструктора"
            collector.error(
                SemanticErrorCodes.FIELD_NOT_INITIALIZED,
                "класс '${info.name}': не заполнены поля ${missing.joinToString { "'${it.name}'" }} — $where",
                decl.constructor?.position ?: decl.position,
            )
        }

        decl.resolvedInitTempBase = scope.totalSlotsUsed()
        decl.resolvedInitLocalCount = decl.resolvedInitTempBase + ctx.maxTempDepth + 1
        checkLocalCount("${info.name}.init", decl.resolvedInitLocalCount, decl.position)
    }

    /**
     * Слоты не переиспользуются после выхода из блока, поэтому с пределом сравнивается общее
     * число выделенных слотов (включая this и скрытые временные), а не максимум одновременно
     * видимых переменных (BASE-04).
     */
    private fun checkLocalCount(name: String, localCount: Int, position: SourcePosition) {
        if (localCount > FormatLimits.MAX_LOCAL_SLOTS) {
            collector.error(
                SemanticErrorCodes.LIMIT_EXCEEDED,
                "функция '$name' использует $localCount локальных слотов (включая параметры и скрытые " +
                    "временные), максимум ${FormatLimits.MAX_LOCAL_SLOTS} (local_count: u8)",
                position,
            )
        }
    }

    /**
     * Упрощённая (не полноценный CFG) проверка "на всех путях есть return":
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

    private fun checkBlock(block: Block, ctx: FunctionContext) {
        ctx.scope.enterScope()
        for (stmt in block.statements) {
            checkStmt(stmt, ctx)
        }
        ctx.scope.exitScope()
    }

    private fun checkStmt(stmt: Stmt, ctx: FunctionContext) {
        when (stmt) {
            is Block -> checkBlock(stmt, ctx)
            is VarDecl -> checkVarDecl(stmt, ctx)
            is Assign -> checkAssign(stmt, ctx)
            is FieldAssign -> checkFieldAssign(stmt, ctx)
            is If -> checkIf(stmt, ctx)
            is While -> checkWhile(stmt, ctx)
            is Return -> checkReturn(stmt, ctx)
            is ExprStmt -> checkExpr(stmt.expr, ctx)
        }
    }

    /** В функции инициализации состояния веток сливаются: заполнено только то, что заполнено в обеих. */
    private fun checkIf(stmt: If, ctx: FunctionContext) {
        checkExpectedType(stmt.condition, ctx, Type.BOOL, "условие if")
        val state = ctx.initState
        val before = state?.snapshot()
        checkBlock(stmt.thenBranch, ctx)
        if (state == null || before == null) {
            stmt.elseBranch?.let { checkBlock(it, ctx) }
            return
        }
        val afterThen = state.snapshot()
        state.restore(before)
        stmt.elseBranch?.let { checkBlock(it, ctx) }
        state.merge(afterThen, state.snapshot())
    }

    /** Цикл может не выполниться ни разу, поэтому записи в теле не доказывают заполнение после него. */
    private fun checkWhile(stmt: While, ctx: FunctionContext) {
        checkExpectedType(stmt.condition, ctx, Type.BOOL, "условие while")
        val state = ctx.initState
        val before = state?.snapshot()
        state?.let { it.loopDepth++ }
        checkBlock(stmt.body, ctx)
        if (state != null && before != null) {
            state.loopDepth--
            state.merge(before, state.snapshot())
        }
    }

    private fun checkVarDecl(stmt: VarDecl, ctx: FunctionContext) {
        if (ctx.scope.findAnywhere(stmt.name) != null) {
            collector.error(
                SemanticErrorCodes.DUPLICATE_VARIABLE,
                "переменная '${stmt.name}' уже объявлена в этой функции (shadowing запрещён)",
                stmt.position,
            )
        }
        val declaredType = stmt.declaredType?.takeIf { resolveDeclaredType(it, stmt.position) }
        val initType = checkValueExpr(stmt.initializer, ctx, "инициализатор переменной '${stmt.name}'")
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
        val info = ctx.scope.declare(stmt.name, effectiveType ?: Type.INT, stmt.isMutable)
        stmt.resolvedSlot = info.slot
    }

    private fun checkAssign(stmt: Assign, ctx: FunctionContext) {
        val variable = ctx.scope.resolve(stmt.name)
        if (variable == null) {
            collector.error(
                SemanticErrorCodes.UNDEFINED_VARIABLE,
                "переменная '${stmt.name}' не объявлена",
                stmt.position,
            )
            checkValueExpr(stmt.value, ctx, "присваивание '${stmt.name}'")
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
        checkExpectedType(stmt.value, ctx, variable.type, "присваивание '${stmt.name}'")
    }

    /**
     * Запись поля (LANG-07). `var`-поле записывается где угодно. `val`-поле — только в функции
     * инициализации своего класса через `this`, не более одного раза на любом пути и не в цикле.
     * Неизменяемость ссылки (`val p = ...`) не делает поля объекта неизменяемыми.
     */
    private fun checkFieldAssign(stmt: FieldAssign, ctx: FunctionContext) {
        val owner = checkReceiver(stmt.receiver, ctx, "запись поля '${stmt.fieldName}'", allowUnreadyThis = true)
        val field = owner?.let { lookupField(it, stmt.fieldName, stmt.position) }
        if (owner == null || field == null) {
            checkValueExpr(stmt.value, ctx, "запись поля '${stmt.fieldName}'")
            return
        }
        checkExpectedType(stmt.value, ctx, field.type, "запись поля '${owner.name}.${field.name}'")
        stmt.resolvedClassIndex = owner.index
        stmt.resolvedFieldIndex = field.index

        // Состояние заполненности меняет только запись в своё поле через this внутри инициализации.
        val state = ctx.initState?.takeIf { stmt.receiver is This && ctx.owner === owner }
        if (!field.isMutable) {
            val reason = when {
                state == null -> "'val'-поле записывается только при инициализации своего экземпляра через this"
                state.maybeAssigned[field.index] -> "'val'-поле уже могло быть записано на этом пути"
                state.loopDepth > 0 -> "'val'-поле нельзя записывать в цикле: запись может выполниться несколько раз"
                else -> null
            }
            reason?.let {
                collector.error(
                    SemanticErrorCodes.VAL_FIELD_REASSIGNMENT,
                    "нельзя записать поле '${owner.name}.${field.name}': $it",
                    stmt.position,
                )
            }
        }
        state?.let {
            it.assigned.set(field.index)
            it.maybeAssigned.set(field.index)
        }
    }

    private fun checkReturn(stmt: Return, ctx: FunctionContext) {
        if (ctx.initState != null) {
            collector.error(SemanticErrorCodes.RETURN_IN_CONSTRUCTOR, "в конструкторе return запрещён", stmt.position)
            stmt.value?.let { checkValueExpr(it, ctx, "return") }
            return
        }
        val returnType = ctx.returnType
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
            checkValueExpr(stmt.value, ctx, "return")
            return
        }
        checkExpectedType(stmt.value, ctx, returnType, "return")
    }

    // ------------------------------------------------------------------------------------
    // Выражения
    // ------------------------------------------------------------------------------------

    private fun checkExpectedType(expr: Expr, ctx: FunctionContext, expected: Type, context: String): Type? {
        val actual = checkValueExpr(expr, ctx, context)
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
     * Проверяет выражение в контексте, которому нужно значение (инициализатор, аргумент, операнд,
     * условие, возврат). Вызов функции без результата здесь недопустим: он ничего не кладёт на
     * стек, и инструкции-потребителю не хватило бы операнда (BASE-02). Разрешён такой вызов только
     * как отдельный оператор — см. [ExprStmt] в [checkStmt].
     */
    private fun checkValueExpr(expr: Expr, ctx: FunctionContext, context: String): Type? {
        val type = checkExpr(expr, ctx)
        if (type == Type.UNIT) {
            collector.error(
                SemanticErrorCodes.NO_VALUE,
                "$context: выражение не возвращает значения (вызов функции без результата)",
                expr.position,
            )
            return null
        }
        return type
    }

    /**
     * Проверяет выражение и возвращает его тип. Возвращает null, если тип не удалось определить
     * из-за уже зарегистрированной ошибки (чтобы не порождать каскад повторных диагностик).
     */
    private fun checkExpr(expr: Expr, ctx: FunctionContext): Type? {
        val type = inferExpr(expr, ctx)
        expr.resolvedType = type
        return type
    }

    private fun inferExpr(expr: Expr, ctx: FunctionContext): Type? = when (expr) {
        is IntLiteral -> Type.INT
        is BoolLiteral -> Type.BOOL
        is StringLiteral -> {
            StringConstraints.violation(expr.value)?.let { reason ->
                collector.error(
                    SemanticErrorCodes.INVALID_STRING_CONSTANT,
                    "строковый литерал не может быть константой GVM: $reason",
                    expr.position,
                )
            }
            Type.STRING
        }
        is VarRef -> {
            val variable = ctx.scope.resolve(expr.name)
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
        is UnaryOp -> inferUnaryOp(expr, ctx)
        is BinaryOp -> inferBinaryOp(expr, ctx)
        is Call -> inferCall(expr, ctx)
        is This -> inferThisAsValue(expr, ctx)
        is FieldAccess -> inferFieldAccess(expr, ctx)
        is MethodCall -> inferMethodCall(expr, ctx)
    }

    /**
     * `this` как самостоятельное значение (аргумент, инициализатор, возврат, receiver метода).
     * Внутри инициализации это раскрытие недостроенного экземпляра, пока не заполнены все поля
     * (LANG-06). Чтение и запись полей через `this.` обрабатываются отдельно в [checkReceiver].
     */
    private fun inferThisAsValue(expr: This, ctx: FunctionContext): Type? {
        val owner = thisOwner(expr, ctx) ?: return null
        val state = ctx.initState
        if (state != null && !state.isReady) {
            collector.error(
                SemanticErrorCodes.THIS_ESCAPE,
                "this класса '${owner.name}' используется до заполнения всех полей: недостроенный " +
                    "экземпляр нельзя передавать, сохранять или вызывать его методы",
                expr.position,
            )
        }
        return owner.type
    }

    private fun thisOwner(expr: This, ctx: FunctionContext): ClassInfo? {
        if (ctx.owner == null) {
            collector.error(SemanticErrorCodes.INVALID_THIS, "this допустим только в методе или конструкторе", expr.position)
        }
        return ctx.owner
    }

    /**
     * Проверяет receiver обращения к члену и возвращает его класс. Для чтения и записи поля
     * receiver `this` допустим и в недостроенном экземпляре ([allowUnreadyThis]); готовность
     * конкретного поля проверяется отдельно.
     */
    private fun checkReceiver(receiver: Expr, ctx: FunctionContext, context: String, allowUnreadyThis: Boolean): ClassInfo? {
        val type = if (receiver is This && allowUnreadyThis) {
            thisOwner(receiver, ctx)?.type.also { receiver.resolvedType = it }
        } else {
            checkValueExpr(receiver, ctx, "получатель ($context)")
        } ?: return null
        if (type !is Type.ClassType) {
            collector.error(
                SemanticErrorCodes.INVALID_RECEIVER,
                "$context: у значения типа $type нет членов, нужен объект класса",
                receiver.position,
            )
            return null
        }
        return classes[type.name] // неизвестный класс уже получил UNDEFINED_CLASS в объявлении
    }

    private fun lookupField(owner: ClassInfo, name: String, position: SourcePosition): FieldInfo? {
        val field = owner.field(name)
        if (field == null) {
            val hint = if (name in owner.methods) " ('$name' — метод, его нужно вызвать со скобками)" else ""
            collector.error(SemanticErrorCodes.UNDEFINED_FIELD, "у класса '${owner.name}' нет поля '$name'$hint", position)
        }
        return field
    }

    private fun inferFieldAccess(expr: FieldAccess, ctx: FunctionContext): Type? {
        val owner = checkReceiver(expr.receiver, ctx, "чтение поля '${expr.fieldName}'", allowUnreadyThis = true) ?: return null
        val field = lookupField(owner, expr.fieldName, expr.position) ?: return null
        val state = ctx.initState
        if (state != null && expr.receiver is This && ctx.owner === owner && !state.assigned[field.index]) {
            collector.error(
                SemanticErrorCodes.FIELD_NOT_INITIALIZED,
                "поле '${owner.name}.${field.name}' читается до того, как оно гарантированно заполнено",
                expr.position,
            )
        }
        expr.resolvedClassIndex = owner.index
        expr.resolvedFieldIndex = field.index
        return field.type
    }

    /** Receiver вычисляется первым и сохраняется в скрытом слоте; аргументы — пока этот слот жив. */
    private fun inferMethodCall(expr: MethodCall, ctx: FunctionContext): Type? {
        val owner = checkReceiver(expr.receiver, ctx, "вызов метода '${expr.methodName}'", allowUnreadyThis = false)
        expr.resolvedTempDepth = ctx.useTempSlot()
        ctx.tempDepth++
        try {
            if (owner == null) {
                expr.args.forEach { checkValueExpr(it, ctx, "аргумент '${expr.methodName}'") }
                return null
            }
            val method = owner.methods[expr.methodName]
            if (method == null) {
                val hint = if (owner.field(expr.methodName) != null) " ('${expr.methodName}' — поле, а не метод)" else ""
                collector.error(
                    SemanticErrorCodes.UNDEFINED_METHOD,
                    "у класса '${owner.name}' нет метода '${expr.methodName}'$hint",
                    expr.position,
                )
                expr.args.forEach { checkValueExpr(it, ctx, "аргумент '${expr.methodName}'") }
                return null
            }
            expr.resolvedMethod = method.decl
            checkArguments("${owner.name}.${method.decl.name}", method.signature.paramTypes, expr.args, expr.position, ctx)
            return method.signature.returnType
        } finally {
            ctx.tempDepth--
        }
    }

    private fun inferUnaryOp(expr: UnaryOp, ctx: FunctionContext): Type? {
        val operandType = checkValueExpr(expr.operand, ctx, "операнд унарной операции") ?: return null
        return when (expr.operator) {
            UnaryOperator.NEG -> requireType(operandType, Type.INT, expr.position, "унарный минус")
            UnaryOperator.NOT -> requireType(operandType, Type.BOOL, expr.position, "логическое отрицание '!'")
        }
    }

    private fun inferBinaryOp(expr: BinaryOp, ctx: FunctionContext): Type? {
        val leftType = checkValueExpr(expr.left, ctx, "левый операнд")
        val rightType = checkValueExpr(expr.right, ctx, "правый операнд")
        if (leftType == null || rightType == null) return null

        return when (expr.operator) {
            BinaryOperator.ADD -> when {
                leftType == Type.INT && rightType == Type.INT -> Type.INT
                leftType == Type.STRING && rightType == Type.STRING -> Type.STRING
                else -> reportOperandMismatch(expr, leftType, rightType, "'+' (сложение или конкатенация строк)")
            }
            BinaryOperator.SUB, BinaryOperator.MUL, BinaryOperator.DIV, BinaryOperator.MOD ->
                requireBothType(leftType, rightType, Type.INT, expr, "арифметическая операция")?.let { Type.INT }

            BinaryOperator.LT, BinaryOperator.GT, BinaryOperator.LE, BinaryOperator.GE ->
                requireBothType(leftType, rightType, Type.INT, expr, "операция сравнения")?.let { Type.BOOL }

            BinaryOperator.EQ, BinaryOperator.NE -> when {
                leftType != rightType ->
                    reportOperandMismatch(expr, leftType, rightType, "'==' / '!=' (операнды разных типов)")
                leftType !in COMPARABLE_TYPES ->
                    reportOperandMismatch(expr, leftType, rightType, "'==' / '!=' (сравнение объектов не определено)")
                else -> Type.BOOL
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

    /** Вызов по имени: встроенная функция, функция верхнего уровня или создание экземпляра класса. */
    private fun inferCall(expr: Call, ctx: FunctionContext): Type? {
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
            for (arg in expr.args) {
                val argType = checkValueExpr(arg, ctx, "аргумент '${expr.functionName}'")
                if (argType != null && argType !in PRINTABLE_TYPES) {
                    collector.error(
                        SemanticErrorCodes.ARGUMENT_TYPE_MISMATCH,
                        "аргумент '${expr.functionName}': ожидался Int, Bool или String, получен $argType",
                        arg.position,
                    )
                }
            }
            return Type.UNIT
        }

        classes[expr.functionName]?.let { return inferConstruction(expr, it, ctx) }

        val signature = functionTable.lookup(expr.functionName)
        if (signature == null) {
            collector.error(
                SemanticErrorCodes.UNDEFINED_FUNCTION,
                "функция или класс '${expr.functionName}' не объявлены",
                expr.position,
            )
            expr.args.forEach { checkValueExpr(it, ctx, "аргумент '${expr.functionName}'") }
            return null
        }
        checkArguments(expr.functionName, signature.paramTypes, expr.args, expr.position, ctx)
        return signature.returnType
    }

    /**
     * Создание экземпляра `Class(args)`: аргументы вычисляются до ALLOC_OBJECT, поэтому их
     * временные слоты не пересекаются со слотом создаваемого объекта той же глубины.
     */
    private fun inferConstruction(expr: Call, info: ClassInfo, ctx: FunctionContext): Type {
        checkArguments("конструктор '${info.name}'", info.constructorParamTypes, expr.args, expr.position, ctx)
        expr.resolvedConstructorClass = info.decl
        expr.resolvedTempDepth = ctx.useTempSlot()
        return info.type
    }

    private fun checkArguments(
        name: String,
        paramTypes: List<Type>,
        args: List<Expr>,
        position: SourcePosition,
        ctx: FunctionContext,
    ) {
        if (paramTypes.size != args.size) {
            collector.error(
                SemanticErrorCodes.ARITY_MISMATCH,
                "'$name' ожидает ${paramTypes.size} аргумент(ов), передано ${args.size}",
                position,
            )
            args.forEach { checkValueExpr(it, ctx, "аргумент '$name'") }
            return
        }
        for ((arg, expectedType) in args.zip(paramTypes)) {
            val actualType = checkValueExpr(arg, ctx, "аргумент '$name'")
            if (actualType != null && actualType != expectedType) {
                collector.error(
                    SemanticErrorCodes.ARGUMENT_TYPE_MISMATCH,
                    "аргумент '$name': ожидался $expectedType, получен $actualType",
                    arg.position,
                )
            }
        }
    }
}
