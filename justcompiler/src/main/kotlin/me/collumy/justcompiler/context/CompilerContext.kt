package me.collumy.justcompiler.context

import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.context.collect.CompilerCollector
import me.collumy.justcompiler.context.registry.FunctionRegistry
import me.collumy.justcompiler.context.registry.VariableRegistry
import me.collumy.justcompiler.utils.ConverterUtils
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.isObject
import java.util.Collections.newSetFromMap
import java.util.IdentityHashMap

object CompilerContext {

    private val args = IdentityHashMap<IrValueSymbol, IrExpr>()          // параметры раскрываемых inline-функций
    private val lambdas = IdentityHashMap<IrValueSymbol, IrFunction>()   // lambda-параметры inline-функций
    private val names = IdentityHashMap<IrValueSymbol, String>()         // имена переменных, объявленных внутри inline

    private var captured: Set<IrValueSymbol> = emptySet()                // захвачено локальными функциями текущей функции
    private var inlineId: Int? = null                                    // номер раскрываемого сейчас inline-вызова
    private var declared: MutableList<Pair<IrValueSymbol, String?>>? = null  // для отката имён при выходе из inline
    private var counter = 0
    private var depth = 0

    val actionLambdas: MutableSet<IrFunction> = newSetFromMap(IdentityHashMap())
    val inlinedLambdas: MutableSet<IrFunction> = newSetFromMap(IdentityHashMap())
    val activeLabels = IdentityHashMap<IrFunctionSymbol, String>()

    private const val MAX_INLINE_DEPTH = 64

    fun collect(files: List<IrFile>) { for (file in files) CompilerCollector.collect(file) }

    /** Вызывать в начале process(): объект живёт дольше одной компиляции. */
    fun reset() {
        args.clear(); lambdas.clear(); names.clear()
        captured = emptySet(); inlineId = null; declared = null
        counter = 0; depth = 0
        actionLambdas.clear(); inlinedLambdas.clear()
        activeLabels.clear()
        FunctionRegistry.reset()
        VariableRegistry.reset()
        ConverterUtils.reset()
    }

    fun nextId(): Int = ++counter

    // ── поиск ──
    fun findArg(symbol: IrValueSymbol): IrExpr? = args[symbol]
    fun registerArg(symbol: IrValueSymbol, expr: IrExpr) {
        args[symbol] = expr
    }
    fun findLambda(symbol: IrValueSymbol): IrFunction? = lambdas[symbol]
    fun registerLabel(symbol: IrFunctionSymbol, label: String) {
        activeLabels[symbol] = label
    }
    fun findLabel(symbol: IrFunctionSymbol): String? = activeLabels[symbol]
    fun getScope(symbol: IrValueSymbol): VariableScope =
        if (symbol in captured) VariableScope.LOCAL else VariableScope.LINE

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun resolveName(symbol: IrValueSymbol): String {
        names[symbol]?.let { return it }
        val raw = symbol.owner.name.asString()
        return if (raw == "<this>") "this" else raw
    }

    /** Объявление локальной переменной: имя считается один раз и запоминается за символом. */
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun declare(symbol: IrValueSymbol): String {
        names[symbol]?.let { return it }
        val raw = symbol.owner.name.asString()
        val name = if (raw == "<this>") "this" else raw
        val id = inlineId
        if (id == null || symbol in captured) return name          // вне inline и LOCAL-переменные без префикса
        val resolved = "_inline_${id}_$name"
        declared?.add(symbol to names.put(symbol, resolved))       // put возвращает прежнее имя (на случай вложенного inline той же функции)
        return resolved
    }

    // ── области ──
    fun <R> withCaptured(set: Set<IrValueSymbol>, block: () -> R): R {
        val prev = captured
        captured = set
        try { return block() } finally { captured = prev }
    }

    fun <R> withInline(
        function: IrFunction,
        id: Int,
        newArgs: Map<IrValueSymbol, IrExpr>,
        newLambdas: Map<IrValueSymbol, IrFunction>,
        block: () -> R,
    ): R {
        if (++depth > MAX_INLINE_DEPTH) error("Inline слишком глубоко вложен (рекурсия?): ${function.name}")

        val prevId = inlineId
        val prevDeclared = declared
        val paramSymbols = function.parameters.map { it.symbol }.toSet() + newArgs.keys
        val savedArgs = args.snapshot(paramSymbols)
        val savedLambdas = lambdas.snapshot(newLambdas.keys)
        val declaredHere = mutableListOf<Pair<IrValueSymbol, String?>>()

        inlineId = id
        declared = declaredHere
        args.putAll(newArgs)
        lambdas.putAll(newLambdas)
        try {
            return block()
        } finally {
            declaredHere.asReversed().forEach { (s, old) -> if (old == null) names.remove(s) else names[s] = old }
            args.restore(savedArgs)
            lambdas.restore(savedLambdas)
            inlineId = prevId
            declared = prevDeclared
            depth--
        }
    }

    // ── откат значений: нужен, потому что символы параметров одной и той же функции совпадают при вложенном вызове ──
    private fun <V> IdentityHashMap<IrValueSymbol, V>.snapshot(keys: Set<IrValueSymbol>): Map<IrValueSymbol, V?> =
        keys.associateWith { this[it] }

    private fun <V> IdentityHashMap<IrValueSymbol, V>.restore(saved: Map<IrValueSymbol, V?>) {
        saved.forEach { (k, v) -> if (v == null) remove(k) else put(k, v) }
    }
}
