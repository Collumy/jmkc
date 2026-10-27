package me.collumy.justcompiler.convert.expr

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.*
import me.collumy.justcompiler.convert.ValueConverter
import me.collumy.justcompiler.context.CompilerContext
import me.collumy.justcompiler.utils.ConverterUtils
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName

import me.collumy.justcompiler.utils.InlineClassUtils

object ConstructorConverter {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(call: IrConstructorCall, target: IrExpr.Variable? = null): IrExpr {
        ValueConverter.convert(call)?.also { return it }
        val constructor = call.symbol.owner
        val clazz = constructor.constructedClass
        if (InlineClassUtils.isInlineClass(clazz)) {
            val arg = call.arguments.firstOrNull { it != null }
                ?: constructor.parameters.firstOrNull { it.name.asString() != "<this>" }?.defaultValue?.expression
                ?: error("Ошибка, конструктору inline-класса ${clazz.name} не передан аргумент")
            return JustCompiler.convert(arg)
                ?: error("Ошибка, не удалось конвертировать аргумент конструктора ${clazz.name}")
        }
        return convertUserClass(call, target)
    }

    private const val TAG_CLASS = true
    private const val CLASS_KEY = "type"

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun convertUserClass(call: IrConstructorCall, target: IrExpr.Variable? = null): IrExpr {
        val constructor = call.symbol.owner
        val clazz = constructor.constructedClass
        val className = clazz.name.asString()

        if (clazz.fileOrNull == null)
            error("Ошибка, класс ${clazz.kotlinFqName} из библиотеки нельзя создавать в коде")
        if (!constructor.isPrimary)
            error("Ошибка, у $className вторичные конструкторы не поддерживаются")
        if (clazz.isInner)
            error("Ошибка, inner-классы не поддерживаются ($className)")
        if (clazz.superTypes.any { !it.isAny() })
            error("Ошибка, наследование не поддерживается ($className)")

        val initBlocks = clazz.declarations.filterIsInstance<IrAnonymousInitializer>()
        val id = CompilerContext.nextId()
        val pre = mutableListOf<IrStmt>()
        val explicit = mutableMapOf<IrValueSymbol, IrExpr>()

        // 1. Переданные аргументы конвертируются в области вызывающего кода
        constructor.parameters.forEachIndexed { i, param ->
            val arg = call.arguments.getOrNull(i) ?: param.defaultValue?.expression ?: error("Ошибка, конструктору $className не передан аргумент '${param.name}'")
            val value = JustCompiler.convert(arg) ?: error("Ошибка, не удалось конвертировать аргумент '${param.name}' конструктора $className")
            explicit[param.symbol] = materialize(param, id, value, pre)
        }

        val fields = CompilerContext.withInline(constructor, id, explicit, emptyMap()) {
            collectFields(clazz)
        }

        val map = IrValue.MapValue(fields)

        if (initBlocks.isEmpty()) {
            return if (pre.isEmpty()) map else IrExpr.Block(pre + IrStmt.Expr(map))
        }

        // 2. Обработка init-блоков
        val instanceVar = target ?: IrExpr.Variable("__inst_${id}", VariableScope.LINE)
        pre += IrStmt.Assign(instanceVar, map)

        val thisReceiver = clazz.thisReceiver
        val inlineExplicit = if (thisReceiver != null) explicit + (thisReceiver.symbol to instanceVar) else explicit

        val initStmts = CompilerContext.withInline(constructor, id, inlineExplicit, emptyMap()) {
            initBlocks.flatMap { JustCompiler.convertBody(it.body) }
        }
        pre += initStmts

        return IrExpr.Block(pre + IrStmt.Expr(instanceVar))
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun collectFields(clazz: IrClass): List<Pair<IrExpr, IrExpr>> {
        val fields = mutableListOf<Pair<IrExpr, IrExpr>>()
        if (TAG_CLASS) fields += IrValue.Text(CLASS_KEY) to IrValue.Text(clazz.name.asString())

        for (property in clazz.declarations.filterIsInstance<IrProperty>()) {
            val initializer = property.backingField?.initializer?.expression ?: continue
            val value = JustCompiler.convert(initializer)
                ?: error("Ошибка, не удалось конвертировать свойство '${property.name}' класса ${clazz.name}")
            fields += IrValue.Text(property.name.asString()) to value
        }
        return fields
    }

    /** Нетривиальный аргумент вычисляется один раз: параметр может использоваться в нескольких свойствах. */
    private fun materialize(param: IrValueParameter, id: Int, value: IrExpr, pre: MutableList<IrStmt>): IrExpr {
        if (value is IrExpr.Variable || value is IrValue.Number || value is IrValue.Text) return value
        val temp = IrExpr.Variable("__arg_${id}_${param.name.asString()}", VariableScope.LINE)
        pre += IrStmt.Assign(temp, value)
        return temp
    }
}
