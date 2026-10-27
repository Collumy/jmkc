package me.collumy.justcompiler.utils

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrStmt
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.kotlinFqName

object CollectionCalls {

    private val GET = setOf("kotlin.collections.List.get", "kotlin.collections.Map.get")
    private val SET = setOf("kotlin.collections.MutableList.set", "kotlin.collections.MutableMap.put")
    private const val EXT_SET = "kotlin.collections.set"

    private fun matches(fn: IrSimpleFunction, names: Set<String>) =
        fn.allOverridden(includeSelf = true).any { it.kotlinFqName.asString() in names }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun get(call: IrCall): IrExpr? {
        val fn = call.symbol.owner
        if (fn.name.asString() != "get" || !matches(fn, GET)) return null

        val target = call.arguments[0]?.let { JustCompiler.convert(it) } ?: error("Ошибка, не удалось конвертировать коллекцию в get")
        val key = call.arguments[1]?.let { JustCompiler.convert(it) } ?: error("Ошибка, не удалось конвертировать ключ в get")

        val isMap = ConverterUtils.getSingleType(call.arguments[0]!!.type) != ServerType.LIST
        return IrExpr.CollectionGet(isMap, target, key)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun set(call: IrCall): IrStmt? {
        val fn = call.symbol.owner
        val isSet = fn.kotlinFqName.asString() == EXT_SET || matches(fn, SET)
        if (!isSet) return null

        val target = call.arguments[0]?.let { JustCompiler.convert(it) }
        if (target !is IrExpr.Variable)
            error("Ошибка, запись в коллекцию поддерживается только у переменной (list[i] = v), а не у выражения")

        val key = call.arguments[1]?.let { JustCompiler.convert(it) } ?: error("Ошибка, не удалось конвертировать ключ в set")
        val value = call.arguments[2]?.let { JustCompiler.convert(it) } ?: error("Ошибка, не удалось конвертировать значение в set")

        val isMap = ConverterUtils.getSingleType(call.arguments[0]!!.type) != ServerType.LIST
        return IrStmt.CollectionSet(isMap, target, key, value)
    }
}