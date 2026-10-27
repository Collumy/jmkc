package me.collumy.justcompiler.backend.lower

import me.collumy.justcompiler.backend.server.*
import me.collumy.justcompiler.backend.ir.*
import me.collumy.justcompiler.backend.lower.value.PropertyLowerer
import me.collumy.justcompiler.backend.lower.value.ValueLowerer

object StmtLowerer {

    fun lower(assign: IrStmt.Assign): List<ServerAction> {
        val inserted = insert(assign.value, listOf(assign.target))
        if (inserted != null) return inserted

        val vari = IrLowerer.lower(assign.target)
        val lowered = IrLowerer.lower(assign.value)

        return lowered.pre + ServerAction("set_variable_value", listOf(
            ServerArgument("variable", vari.value),
            ServerArgument("value", lowered.value),
        ))
    }

    fun lower(assign: IrStmt.MultiAssign, tuple: List<IrExpr> = emptyList()): List<ServerAction> {
        if (assign.targets.isEmpty()) error("Ошибка, в MultiAssign нет targets")

        // Деструктуризация
        if (tuple.isEmpty()) {
            val inserted = insert(assign.value, assign.targets)
            if (inserted != null) return inserted
            else if (assign.value is IrExpr.Tuple) return lower(assign, assign.value.elements)
        }

        // Мульти-ассигн
        if (assign.targets.size != tuple.size)
            error("Ошибка, в MultiAssign неравное количество targets и values")

        val stmts = mutableListOf<ServerAction>()
        val variables = mutableListOf<ServerValue.Variable>()
        val values = mutableListOf<ServerValue>()

        assign.targets.zip(tuple).forEach { (target, value) ->
            val lowered = IrLowerer.lower(value)
            stmts += lowered.pre

            variables += ServerValue.Variable(target.name, target.scope)
            values += lowered.value
        }

        val action = ServerAction("set_variable_multiple", listOf(
            ServerArgument("variables", ServerValue.ListValue(variables)),
            ServerArgument("values", ServerValue.ListValue(values)),
        ))

        return stmts + action
    }

    fun lower(ret: IrStmt.Return): List<ServerAction> {
        val stmts = mutableListOf<ServerAction>()
        val values = mutableListOf<ServerValue>()

        for (value in ret.values) {
            val lowered = IrLowerer.lower(value)
            stmts += lowered.pre
            values += lowered.value
        }

        val sets = ServerAction("set_variable_multiple", listOf(
            ServerArgument("variables", ServerValue.ListValue(List(values.size) { i -> ServerValue.Variable("ret$i") })),
            ServerArgument("values", ServerValue.ListValue(values))
        ))

        return stmts + sets + ServerAction("control_return_function")
    }

    fun lower(stmt: IrStmt.While): List<ServerAction> {
        val cond = ConditionLowerer.lower(stmt.condition)
        val body = stmt.body.flatMap { IrLowerer.lower(it) }

        if (stmt.condition is IrValue.Number && stmt.condition.value == 1.0)
            return listOf(ServerAction("repeat_forever", body = body))

        if (!stmt.isDoWhile && cond.pre.isEmpty()) {
            return listOf(ServerAction("repeat_while", cond.args, body,
                conditional = cond.action, isInverted = cond.isInverted, selection = cond.selection))
        }

        val loopBody = mutableListOf<ServerAction>()
        val breakCondition = cond.pre + ServerAction(cond.action, cond.args,
            body = listOf(ServerAction("control_break")),
            isInverted = !cond.isInverted, selection = cond.selection,
        )

        if (stmt.isDoWhile) {
            loopBody += body
            loopBody += breakCondition
        } else {
            loopBody += breakCondition
            loopBody += body
        }

        return listOf(ServerAction("repeat_forever", body = loopBody))
    }










    fun insert(value: IrExpr, targets: List<IrExpr.Variable>): List<ServerAction>? = when (value) {
        is IrExpr.Action -> ExprLowerer.lower(value, targets).pre
        is IrExpr.FunctionCall -> ExprLowerer.lower(value, targets).pre
        is IrExpr.Block -> insert(value, targets)
        is IrExpr.If -> ExprLowerer.lower(value, targets).pre
        is IrExpr.Is -> ExprLowerer.lower(value, targets).pre
        is IrValue -> ValueLowerer.lower(value, targets).pre.ifEmpty { null }
        is IrExpr.PropertyGet -> PropertyLowerer.lower(value, targets).pre
        else -> null
    }

    private fun insert(block: IrExpr.Block, targets: List<IrExpr.Variable>): List<ServerAction> {
        if (block.body.isEmpty()) error("Ошибка: пустой блок не может возвращать значение")

        val statements = block.body.toMutableList()
        val last = statements.removeLast()
        val finalAssign = when {
            last is IrStmt.Expr && targets.size == 1 -> listOf(IrStmt.Assign(targets[0], last.expr))
            last is IrStmt.Expr -> listOf(IrStmt.MultiAssign(targets, last.expr))
            last is IrStmt.Assign && targets.size == 1 -> listOf(last, IrStmt.Assign(targets[0], last.target))
            else -> error("Ошибка: последняя инструкция блока должна быть выражением для возврата значения, но получено ${last::class.simpleName}: $last")
        }

        return (statements + finalAssign).flatMap { IrLowerer.lower(it) }
    }

}