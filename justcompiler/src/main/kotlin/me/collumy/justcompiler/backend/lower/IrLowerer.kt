package me.collumy.justcompiler.backend.lower

import me.collumy.justcompiler.backend.TriggerType
import me.collumy.justcompiler.backend.server.*
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrModule
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.backend.lower.value.CollectionLowerer
import me.collumy.justcompiler.backend.lower.value.PropertyLowerer
import me.collumy.justcompiler.backend.lower.value.ValueLowerer

object IrLowerer {

    private var tempVarCounter = 0
    fun temp() = ServerValue.Variable("temp_${tempVarCounter++}")

    val IrExpr.Variable.server get() = ServerValue.Variable(this.name, this.scope)
    val ServerValue.Variable.ir get() = IrExpr.Variable(this.name, this.scope)
    val ServerValue.low get() = Lowered(this, emptyList())

    fun lower(module: IrModule): ServerCode {
        tempVarCounter = 0
        val handlers = module.triggers.flatMap { TriggerLowerer.lower(it) }.toMutableList()

        val stmts = module.stmts.flatMap { lower(it) }
        if (stmts.isNotEmpty())
            handlers += ServerTrigger(TriggerType.EVENT, "world_start", stmts)

        return ServerCode(handlers)
    }

    fun lower(stmts: List<IrStmt>, resetCounter: Boolean = true): List<ServerAction> {
        if (resetCounter) tempVarCounter = 0
        return stmts.flatMap { lower(it) }
    }
    fun List<ServerAction>.delRet(): List<ServerAction> =
        if (lastOrNull()?.name == "control_return_function") dropLast(1) else this

    fun lower(stmt: IrStmt): List<ServerAction> = when (stmt) {
        is IrStmt.Assign -> StmtLowerer.lower(stmt)
        is IrStmt.MultiAssign -> StmtLowerer.lower(stmt)
        is IrStmt.PropertySet -> PropertyLowerer.lower(stmt)
        is IrStmt.CollectionSet -> CollectionLowerer.lower(stmt)

        is IrStmt.Break -> listOf(ServerAction("control_stop_repeat"))
        is IrStmt.Continue -> listOf(ServerAction("control_skip_iteration"))
        is IrStmt.Return -> StmtLowerer.lower(stmt)

        is IrStmt.While -> StmtLowerer.lower(stmt)
        is IrStmt.Expr -> lower(stmt.expr).pre
    }

    fun lower(expr: IrExpr): Lowered = when (expr) {
        is IrValue -> ValueLowerer.lower(expr)
        is IrExpr.Tuple -> ValueLowerer.lower(IrValue.ListValue(expr.elements))
        is IrExpr.Variable -> Lowered(ServerValue.Variable(expr.name, expr.scope), emptyList())
        is IrExpr.StringTemplate -> ExprLowerer.lower(expr)

        is IrExpr.Binary -> ExprLowerer.lower(expr)
        is IrExpr.Unary -> ExprLowerer.lower(expr)
        is IrExpr.Update -> ExprLowerer.lower(expr)

        is IrExpr.Action -> ExprLowerer.lower(expr)
        is IrExpr.FunctionCall -> ExprLowerer.lower(expr)
        is IrExpr.StartProcess -> ExprLowerer.lower(expr)
        is IrExpr.Block -> ExprLowerer.lower(expr)

        is IrExpr.If -> ExprLowerer.lower(expr)
        is IrExpr.Is -> ExprLowerer.lower(expr)
        is IrExpr.CollectionGet -> CollectionLowerer.lower(expr)
        is IrExpr.PropertyGet -> PropertyLowerer.lower(expr)
    }
}

data class Lowered(val result: ServerValue?, val pre: List<ServerAction>) {
    val value: ServerValue get() = result ?: error("Результат равен null")
}
