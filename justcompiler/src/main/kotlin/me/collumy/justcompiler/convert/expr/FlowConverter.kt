package me.collumy.justcompiler.convert.expr

import org.jetbrains.kotlin.ir.util.kotlinFqName
import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.ir.BinaryOp
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.utils.ConverterUtils
import me.collumy.justcompiler.utils.ConverterUtils.main
import me.collumy.justcompiler.context.collect.CompilerCollector.isInline
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isObject
import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.IrArgument
import me.collumy.justcompiler.backend.ir.UnaryOp
import me.collumy.justcompiler.context.CompilerContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.isUnit

object FlowConverter {


    fun convert(w: IrWhen): IrExpr = when (w.origin) {
        IrStatementOrigin.ANDAND -> logical(w, BinaryOp.AND)
        IrStatementOrigin.OROR   -> logical(w, BinaryOp.OR)
        else -> convertIf(w)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convertIf(whenExpr: IrWhen): IrExpr {
        val typeClassifier = (whenExpr.type.classifierOrNull as? IrClassSymbol)?.owner?.name?.asString()
        val isUnit = whenExpr.type.isUnit() || typeClassifier == "Unit"

        val isSafeCall = whenExpr.branches.size == 2 &&
            whenExpr.branches[0].result.let { it is IrConst && it.value == null } &&
            whenExpr.branches[1] is IrElseBranch

        if (isSafeCall) {
            val nullCheckCond = JustCompiler.convert(whenExpr.branches[0].condition)
            val notNullCond = when {
                nullCheckCond is IrExpr.Binary && nullCheckCond.op == BinaryOp.EQ -> IrExpr.Binary(nullCheckCond.left, BinaryOp.NEQ, nullCheckCond.right)
                nullCheckCond is IrExpr.Binary && nullCheckCond.op == BinaryOp.NEQ -> IrExpr.Binary(nullCheckCond.left, BinaryOp.EQ, nullCheckCond.right)
                nullCheckCond is IrExpr.Unary && nullCheckCond.op == UnaryOp.NOT -> nullCheckCond.expr
                nullCheckCond != null -> IrExpr.Unary(UnaryOp.NOT, nullCheckCond)
                else -> error("Не удалось инвертировать условие safe call")
            }
            val body = JustCompiler.convert(whenExpr.branches[1].result) ?: return IrExpr.Block()

            val isVoidCall = when (body) {
                is IrExpr.FunctionCall -> body.returnsCount == 0
                is IrExpr.Action -> body.returns.isEmpty()
                else -> false
            }

            if (isUnit || isVoidCall) {
                return IrExpr.If(listOf(IrExpr.If.Branch(notNullCond, body)), elseExpr = null)
            }
        }

        val branches = mutableListOf<IrExpr.If.Branch>()
        var elseExpr: IrExpr? = null

        for (branch in whenExpr.branches) {
            if (branch is IrElseBranch) {
                val convertedElse = JustCompiler.convert(branch.result)
                if (isUnit) {
                    val firstCond = branches.firstOrNull()?.condition
                    if (firstCond != null && branches.size == 1 && convertedElse != null) {
                        val inverted = when {
                            firstCond is IrExpr.Binary && firstCond.op == BinaryOp.EQ -> IrExpr.Binary(firstCond.left, BinaryOp.NEQ, firstCond.right)
                            firstCond is IrExpr.Binary && firstCond.op == BinaryOp.NEQ -> IrExpr.Binary(firstCond.left, BinaryOp.EQ, firstCond.right)
                            firstCond is IrExpr.Unary && firstCond.op == UnaryOp.NOT -> firstCond.expr
                            else -> IrExpr.Unary(UnaryOp.NOT, firstCond)
                        }
                        branches.add(IrExpr.If.Branch(inverted, convertedElse))
                    } else if (branches.size > 1 && convertedElse != null) {
                        branches.add(IrExpr.If.Branch(IrValue.Number(1.0), convertedElse))
                    }
                } else {
                    elseExpr = convertedElse
                }
                break
            }

            val condition = JustCompiler.convert(branch.condition) ?: continue
            val body = JustCompiler.convert(branch.result) ?: continue

            branches.add(IrExpr.If.Branch(condition, body))
        }

        if (branches.isEmpty()) {
            return elseExpr ?: IrExpr.Block(emptyList())
        }

        return IrExpr.If(branches, elseExpr)
    }

    fun convert(loop: IrLoop): IrStmt.While {
        val condition = JustCompiler.convert(loop.condition) ?: error("Ошибка, не удалось конвертировать условие")
        val body = loop.body.let { JustCompiler.convertBody(it) }
        return IrStmt.While(condition, body, loop is IrDoWhileLoop)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convertForLoop(container: IrContainerExpression): IrExpr {
        val iteratorVar = container.statements.filterIsInstance<IrVariable>().firstOrNull()
        val whileLoop = container.statements.filterIsInstance<IrWhileLoop>().firstOrNull()
            ?: error("Не найден while loop внутри for loop")

        val iteratorCall = iteratorVar?.initializer as? IrCall
        val convertedIter = iteratorCall?.let { JustCompiler.convert(it) }

        val iterFn = iteratorCall?.symbol?.owner
        val extParam = iterFn?.parameters?.firstOrNull { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.ExtensionReceiver }
        val collectionExpr = iteratorCall?.dispatchReceiver
            ?: extParam?.let { iteratorCall.arguments.getOrNull(it.indexInParameters) }
            ?: iteratorCall?.arguments?.firstOrNull()

        val convertedCollection = collectionExpr?.let { JustCompiler.convert(it) }

        var prependedStmts = emptyList<IrStmt>()
        val repeatAction: IrExpr.Action = when (convertedIter) {
            is IrExpr.Action -> convertedIter
            is IrExpr.Block -> {
                val lastStmt = convertedIter.body.lastOrNull()
                val lastAction = (lastStmt as? IrStmt.Expr)?.expr as? IrExpr.Action
                if (lastAction != null) {
                    prependedStmts = convertedIter.body.dropLast(1)
                    lastAction
                } else {
                    IrExpr.Action("repeat_for_each_in_list", listOfNotNull(convertedCollection?.let { IrArgument("list", it) }))
                }
            }
            else -> IrExpr.Action("repeat_for_each_in_list", listOfNotNull(convertedCollection?.let { IrArgument("list", it) }))
        }

        val loopStatements = when (val body = whileLoop.body) {
            is IrContainerExpression -> body.statements
            null -> emptyList()
            else -> listOf(body)
        }

        val rangeInfo = extractRangeInfo(collectionExpr)
        if (rangeInfo != null) {
            val itemVar = loopStatements.firstOrNull() as? IrVariable
            val itemName = itemVar?.let { CompilerContext.declare(it.symbol) } ?: "i"
            val restStatements = if (itemVar != null) loopStatements.drop(1) else loopStatements
            val convertedBody = JustCompiler.convertStatements(restStatements)

            val rangeArgs = listOf(
                IrArgument("variable", IrExpr.Variable(itemName, VariableScope.LINE)),
                IrArgument("start", rangeInfo.start),
                IrArgument("end", rangeInfo.end),
                IrArgument("interval", rangeInfo.interval)
            )
            return IrExpr.Action("repeat_on_range", rangeArgs, body = convertedBody)
        }

        val isMap = repeatAction.name == "repeat_for_each_map_entry"

        val updatedRepeat = if (isMap) {
            val firstStmt = loopStatements.firstOrNull() as? IrVariable
            val secondStmt = loopStatements.getOrNull(1) as? IrVariable
            val thirdStmt = loopStatements.getOrNull(2) as? IrVariable

            val (keyVarName, valueVarName, restStatements) = if (secondStmt != null && thirdStmt != null) {
                val kName = CompilerContext.declare(secondStmt.symbol)
                val vName = CompilerContext.declare(thirdStmt.symbol)
                Triple(kName, vName, loopStatements.drop(3))
            } else {
                val vName = firstStmt?.let { CompilerContext.declare(it.symbol) } ?: "entry"
                Triple("key", vName, loopStatements.drop(1))
            }

            val convertedBody = JustCompiler.convertStatements(restStatements)
            val baseArgs = repeatAction.args.filter { it.name != "key_variable" && it.name != "value_variable" }
            val hasMapArg = baseArgs.any { it.name == "map" }
            val finalArgs = (if (!hasMapArg && convertedCollection != null) baseArgs + IrArgument("map", convertedCollection) else baseArgs) + listOf(
                IrArgument("key_variable", IrExpr.Variable(keyVarName, VariableScope.LINE)),
                IrArgument("value_variable", IrExpr.Variable(valueVarName, VariableScope.LINE))
            )
            repeatAction.copy(args = finalArgs, body = convertedBody)
        } else {
            val itemVar = loopStatements.firstOrNull() as? IrVariable
            val itemName = itemVar?.let { CompilerContext.declare(it.symbol) } ?: "item"
            val restStatements = if (itemVar != null) loopStatements.drop(1) else loopStatements
            val convertedBody = JustCompiler.convertStatements(restStatements)

            val baseArgs = repeatAction.args.filter { it.name != "value_variable" }
            val hasListArg = baseArgs.any { it.name == "list" }
            val finalArgs = (if (!hasListArg && convertedCollection != null) baseArgs + IrArgument("list", convertedCollection) else baseArgs) + listOf(
                IrArgument("value_variable", IrExpr.Variable(itemName, VariableScope.LINE))
            )
            repeatAction.copy(args = finalArgs, body = convertedBody)
        }

        return if (prependedStmts.isEmpty()) updatedRepeat else IrExpr.Block(prependedStmts + IrStmt.Expr(updatedRepeat))
    }

    data class RangeInfo(val start: IrExpr, val end: IrExpr, val interval: IrExpr)

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun extractRangeInfo(expr: org.jetbrains.kotlin.ir.expressions.IrExpression?): RangeInfo? {
        val call = expr as? org.jetbrains.kotlin.ir.expressions.IrCall ?: return null
        val fnName = call.symbol.owner.name.asString()
        val fqName = call.symbol.owner.kotlinFqName.asString()

        if (fnName == "step" && fqName.startsWith("kotlin.ranges.")) {
            val innerRange = call.arguments.getOrNull(0) ?: call.dispatchReceiver ?: return null
            val inner = extractRangeInfo(innerRange) ?: return null
            val stepArg = call.arguments.getOrNull(1) ?: return null
            val stepExpr = JustCompiler.convert(stepArg) ?: return null
            val finalInterval = if (inner.interval is IrValue.Number && inner.interval.value < 0) {
                if (stepExpr is IrValue.Number) IrValue.Number(-stepExpr.value)
                else IrExpr.Binary(IrValue.Number(0.0), BinaryOp.MINUS, stepExpr)
            } else {
                stepExpr
            }
            return RangeInfo(inner.start, inner.end, finalInterval)
        }

        if (fqName.startsWith("kotlin.") && (fnName in listOf("rangeTo", "rangeUntil", "until", "downTo"))) {
            val startArg = call.arguments.getOrNull(0) ?: call.dispatchReceiver ?: return null
            val endArg = call.arguments.getOrNull(1) ?: return null
            val startExpr = JustCompiler.convert(startArg) ?: return null
            val rawEndExpr = JustCompiler.convert(endArg) ?: return null

            return when (fnName) {
                "rangeTo" -> RangeInfo(startExpr, rawEndExpr, IrValue.Number(1.0))
                "rangeUntil", "until" -> {
                    val endMinusOne = if (rawEndExpr is IrValue.Number) {
                        IrValue.Number(rawEndExpr.value - 1.0)
                    } else {
                        IrExpr.Binary(rawEndExpr, BinaryOp.MINUS, IrValue.Number(1.0))
                    }
                    RangeInfo(startExpr, endMinusOne, IrValue.Number(1.0))
                }
                "downTo" -> RangeInfo(startExpr, rawEndExpr, IrValue.Number(-1.0))
                else -> null
            }
        }
        return null
    }

    private fun logical(w: IrWhen, op: BinaryOp): IrExpr {
        val left = JustCompiler.convert(w.branches[0].condition) ?: error("...")
        val rightExpr = if (op == BinaryOp.AND) w.branches[0].result else w.branches[1].result
        return IrExpr.Binary(left, op, JustCompiler.convert(rightExpr) ?: error("..."))
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(expr: IrTypeOperatorCall): IrExpr? = when (expr.operator) {
        IrTypeOperator.INSTANCEOF, IrTypeOperator.NOT_INSTANCEOF -> {
            val targetExpr = JustCompiler.convert(expr.argument) ?: return null
            val typeOperand = expr.typeOperand
            val irClass = (typeOperand.classifierOrNull as? IrClassSymbol)?.owner
            val anns = irClass?.let { ConverterUtils.getAnnotations(it.annotations) }.orEmpty()
            val isBuiltIn = "BuiltInValue" in anns
            val userClass = if (irClass != null && !irClass.isObject && !irClass.isInline && !isBuiltIn && irClass.fileOrNull != null) {
                irClass.name.asString()
            } else null
            val expectedType = ConverterUtils.getType(typeOperand).main()
            val isInverted = expr.operator == IrTypeOperator.NOT_INSTANCEOF

            IrExpr.Is(targetExpr, expectedType, isInverted, userClass)
        }
        IrTypeOperator.CAST, IrTypeOperator.SAFE_CAST, IrTypeOperator.IMPLICIT_CAST,
        IrTypeOperator.IMPLICIT_COERCION_TO_UNIT, IrTypeOperator.IMPLICIT_NOTNULL ->
            JustCompiler.convert(expr.argument)
        else -> null
    }
}
