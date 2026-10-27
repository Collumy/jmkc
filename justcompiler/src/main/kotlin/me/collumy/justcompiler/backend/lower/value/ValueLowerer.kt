package me.collumy.justcompiler.backend.lower.value

import me.collumy.justcompiler.backend.lower.*
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.backend.lower.IrLowerer.low
import me.collumy.justcompiler.backend.lower.IrLowerer.server
import me.collumy.justcompiler.backend.server.ServerAction
import me.collumy.justcompiler.backend.server.ServerArgument
import me.collumy.justcompiler.backend.server.ServerValue

object ValueLowerer {

    fun lower(value: IrValue, targets: List<IrExpr.Variable> = emptyList()): Lowered {
        val target = targets.singleOrNull()

        return when (value) {
            is IrValue.Number -> ServerValue.Number(value.value).low
            is IrValue.Text -> ServerValue.Text(value.value, value.parsing).low
            is IrValue.Enum -> {
                val raw = value.raw?.let { ServerValue.Variable(it.name, it.scope) }
                ServerValue.Enum(value.value, raw).low
            }
            is IrValue.GameValue -> ServerValue.GameValue(value.value, value.selection).low
            is IrValue.Location -> BuiltInLowerer.lower(value, target)
            is IrValue.Vector -> BuiltInLowerer.lower(value, target)
            is IrValue.Potion -> BuiltInLowerer.lower(value, target)
            is IrValue.Sound -> BuiltInLowerer.lower(value, target)
            is IrValue.Particle -> BuiltInLowerer.lower(value, target)
            is IrValue.Item -> BuiltInLowerer.lower(value, target)
            is IrValue.Null -> ServerValue.Null.low
            is IrValue.Selection -> ServerValue.Null.low
            is IrValue.ListValue -> lower(value, target)
            is IrValue.MapValue -> lower(value, target)
        }
    }

    fun lower(list: IrValue.ListValue, target: IrExpr.Variable?): Lowered {
        val pre = mutableListOf<ServerAction>()
        val elements = ServerValue.ListValue(list.values.map {
            val lowered = IrLowerer.lower(it)
            pre += lowered.pre
            lowered.value
        })

        if (target != null) {
            val action = if (elements.values.size < 22) {
                ServerAction("set_variable_create_list", listOf(
                    ServerArgument("variable", target.server),
                    ServerArgument("values", elements)
                ))
            } else {
                ServerAction("set_variable_value", listOf(
                    ServerArgument("variable", target.server),
                    ServerArgument("value", elements)
                ))
            }
            return Lowered(null, pre + action)
        }

        return Lowered(elements, pre)
    }

    fun lower(map: IrValue.MapValue, target: IrExpr.Variable?): Lowered {
        val pre = mutableListOf<ServerAction>()
        val pairs = ServerValue.MapValue(map.pairs.map { (first, second) ->
            val l1 = IrLowerer.lower(first); val l2 = IrLowerer.lower(second)
            pre += l1.pre; pre += l2.pre
            l1.value to l2.value
        })

        if (target != null) {
            val action = if (pairs.pairs.size < 16) {
                ServerAction("set_variable_create_map_from_values", listOf(
                    ServerArgument("variable", target.server),
                    ServerArgument("keys", ServerValue.ListValue(pairs.pairs.map { it.first })),
                    ServerArgument("values", ServerValue.ListValue(pairs.pairs.map { it.second }))
                ))
            } else {
                ServerAction("set_variable_value", listOf(
                    ServerArgument("variable", target.server),
                    ServerArgument("value", pairs)
                ))
            }
            return Lowered(null, pre + action)
        }

        return Lowered(pairs, pre)
    }

}