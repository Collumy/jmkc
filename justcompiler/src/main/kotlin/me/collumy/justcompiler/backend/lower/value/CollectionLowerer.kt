package me.collumy.justcompiler.backend.lower.value

import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.backend.lower.IrLowerer
import me.collumy.justcompiler.backend.lower.IrLowerer.server
import me.collumy.justcompiler.backend.lower.Lowered
import me.collumy.justcompiler.backend.server.ServerAction
import me.collumy.justcompiler.backend.server.ServerArgument
import me.collumy.justcompiler.backend.server.ServerValue
import kotlin.collections.plus

object CollectionLowerer {

    fun lower(stmt: IrStmt.CollectionSet): List<ServerAction> =
        if (stmt.isMap) lowerMap(stmt)
        else lowerList(stmt)

    fun lower(stmt: IrExpr.CollectionGet, targets: List<IrExpr.Variable> = emptyList()): Lowered =
        if (stmt.isMap) lowerMap(stmt, targets)
        else lowerList(stmt, targets)


    private fun lowerMap(mapSet: IrStmt.CollectionSet): List<ServerAction> {
        val map = IrLowerer.lower(mapSet.collection)
        val key = IrLowerer.lower(mapSet.key)
        val value = IrLowerer.lower(mapSet.value)

        val variable = if (map.value !is ServerValue.Variable) IrLowerer.temp() else map.value
        val action = ServerAction("set_variable_set_map_value", listOf(
            ServerArgument("variable", variable),
            ServerArgument("map", map.value),
            ServerArgument("key", key.value),
            ServerArgument("value", value.value),
        ))

        return map.pre + key.pre + value.pre + action
    }

    private fun lowerList(listSet: IrStmt.CollectionSet): List<ServerAction> {
        val list = IrLowerer.lower(listSet.collection)
        val index = IrLowerer.lower(listSet.key)
        val value = IrLowerer.lower(listSet.value)

        val variable = if (list.value !is ServerValue.Variable) IrLowerer.temp() else list.value
        val action = ServerAction("set_variable_set_list_value", listOf(
            ServerArgument("variable", variable),
            ServerArgument("list", list.value),
            ServerArgument("number", index.value),
            ServerArgument("value", value.value),
        ))

        return list.pre + index.pre + value.pre + action
    }

    private fun lowerMap(mapGet: IrExpr.CollectionGet, targets: List<IrExpr.Variable> = emptyList()): Lowered {
        val map = IrLowerer.lower(mapGet.collection)
        val key = IrLowerer.lower(mapGet.key)
        val resultVar = targets.singleOrNull()?.server ?: IrLowerer.temp()

        if (mapGet.isInline && map.value is ServerValue.Variable) {
            val scope = if (resultVar.scope == VariableScope.GAME) "%entry" else "%entry_${resultVar.scope.id}"
            val text = IrLowerer.lower(IrExpr.StringTemplate(listOf(
                IrValue.Text("$scope(${(map.value as ServerValue.Variable).name}, "),
                mapGet.key, IrValue.Text(")")
            )))
            return Lowered(text.value, map.pre + text.pre)
        }

        val action = ServerAction("set_variable_get_map_value", listOf(
            ServerArgument("variable", resultVar),
            ServerArgument("map", map.value),
            ServerArgument("key", key.value),
        ))

        return Lowered(resultVar, map.pre + key.pre + action)
    }

    private fun lowerList(listGet: IrExpr.CollectionGet, targets: List<IrExpr.Variable> = emptyList()): Lowered {
        val list = IrLowerer.lower(listGet.collection)
        val index = IrLowerer.lower(listGet.key)
        val resultVar = targets.singleOrNull()?.server ?: IrLowerer.temp()

        if (listGet.isInline && list.value is ServerValue.Variable) {
            val scope = if (resultVar.scope == VariableScope.GAME) "%index" else "%index_${resultVar.scope.id}"
            val text = IrLowerer.lower(IrExpr.StringTemplate(listOf(
                IrValue.Text("$scope(${(list.value as ServerValue.Variable).name}, "),
                listGet.key, IrValue.Text(")")
            )))
            return Lowered(text.value, list.pre + text.pre)
        }

        val action =  ServerAction("set_variable_get_list_value", listOf(
            ServerArgument("variable", resultVar),
            ServerArgument("list", list.value),
            ServerArgument("number", index.value),
        ))

        return Lowered(resultVar, list.pre + index.pre + action)
    }

    val IrExpr.lm: IrExpr get() = when (this) {
        is IrExpr.CollectionGet -> this.copy(isInline = true)
        else -> this
    }

}