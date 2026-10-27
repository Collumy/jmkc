package me.collumy.justcompiler.backend.lower

import me.collumy.justcompiler.backend.ActionSelection
import me.collumy.justcompiler.backend.ir.BinaryOp
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.backend.ir.UnaryOp
import me.collumy.justcompiler.backend.server.ServerAction
import me.collumy.justcompiler.backend.server.ServerArgument
import me.collumy.justcompiler.backend.server.ServerValue

data class Condition(
    val action: String,
    val args: List<ServerArgument>,
    val pre: List<ServerAction> = emptyList(),
    val selection: ActionSelection? = null,
    val isInverted: Boolean = false
)

object ConditionLowerer {

    fun lower(expr: IrExpr): Condition = when (expr) {
        is IrExpr.Action -> lowerAction(expr)
        is IrExpr.Binary -> lowerBinary(expr)
        is IrExpr.Unary -> lowerUnary(expr)
        is IrExpr.Is -> lowerIs(expr)
        else -> lowerFallback(expr)
    }

    private fun lowerAction(action: IrExpr.Action): Condition {
        val pre = mutableListOf<ServerAction>()

        val args = action.args.map { (name, value) ->
            val lowered = IrLowerer.lower(value)
            pre += lowered.pre
            ServerArgument(name, lowered.value)
        }

        return Condition(action.name, args, pre, action.selection)
    }

    private fun lowerUnary(unary: IrExpr.Unary): Condition {
        return when (unary.op) {
            UnaryOp.NOT -> {
                val inner = lower(unary.expr)
                inner.copy(isInverted = !inner.isInverted)
            }
            UnaryOp.NEG -> lowerFallback(unary)
        }
    }

    private fun lowerIs(isExpr: IrExpr.Is): Condition {
        val pre = mutableListOf<ServerAction>()
        val target = IrLowerer.lower(isExpr.target)
        pre += target.pre

        return if (isExpr.userClass != null) {
            Condition("if_variable_map_value_equals", listOf(
                ServerArgument("map", target.value),
                ServerArgument("key", ServerValue.Text("type")),
                ServerArgument("values", ServerValue.ListValue(listOf(ServerValue.Text(isExpr.userClass))))
            ), pre, isInverted = isExpr.isInverted)
        } else {
            Condition("if_variable_is_type", listOf(
                ServerArgument("value", ServerValue.ListValue(listOf(target.value))),
                ServerArgument("variable_type", ServerValue.Enum(isExpr.type.id))
            ), pre, isInverted = isExpr.isInverted)
        }
    }

    private fun lowerBinary(binary: IrExpr.Binary): Condition {
        val pre = mutableListOf<ServerAction>()

        return when (binary.op) {
            BinaryOp.EQ, BinaryOp.NEQ -> {
                val isEq = binary.op == BinaryOp.EQ

                // Проверка на null (x == null / x != null)
                if (binary.left is IrValue.Null || binary.right is IrValue.Null) {
                    val targetExpr = if (binary.left is IrValue.Null) binary.right else binary.left
                    val target = IrLowerer.lower(targetExpr)
                    pre += target.pre

                    Condition("if_variable_list_is_empty", listOf(
                        ServerArgument("list", target.value)
                    ), pre, isInverted = !isEq)
                } else {
                    val left = IrLowerer.lower(binary.left)
                    val right = IrLowerer.lower(binary.right)
                    pre += left.pre
                    pre += right.pre

                    Condition("if_variable_equals", listOf(
                        ServerArgument("value", left.value),
                        ServerArgument("compare", right.value)
                    ), pre, isInverted = !isEq)
                }
            }

            BinaryOp.GT, BinaryOp.GTE, BinaryOp.LT, BinaryOp.LTE -> {
                val left = IrLowerer.lower(binary.left)
                val right = IrLowerer.lower(binary.right)
                pre += left.pre
                pre += right.pre

                val actionName = when (binary.op) {
                    BinaryOp.GT -> "if_variable_greater"
                    BinaryOp.GTE -> "if_variable_greater_or_equals"
                    BinaryOp.LT -> "if_variable_less"
                    BinaryOp.LTE -> "if_variable_less_or_equals"
                }

                Condition(actionName, listOf(
                    ServerArgument("value", left.value),
                    ServerArgument("compare", right.value)
                ), pre)
            }

            // Вычисляемые логические связки (&&, ||) или вычисления
            else -> lowerFallback(binary)
        }
    }

    private fun lowerFallback(expr: IrExpr): Condition {
        val pre = mutableListOf<ServerAction>()
        val lowered = IrLowerer.lower(expr)
        pre += lowered.pre

        return Condition("if_variable_equals", pre = pre, args = listOf(
            ServerArgument("value", lowered.value),
            ServerArgument("compare", ServerValue.Number(1.0))
        ))
    }
}