package me.collumy.justcompiler.context.registry

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.utils.ConverterUtils
import me.collumy.justcompiler.utils.SelectionUtils
import me.collumy.justcompiler.context.collect.CompilerCollector.isInline
import me.collumy.justcompiler.context.model.VariableSignature
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.getClass

object VariableRegistry {

    private val globalSymbols = mutableMapOf<IrSymbol, VariableSignature>()
    private val localSymbols = mutableSetOf<IrSymbol>()

    fun reset() {
        globalSymbols.clear()
        localSymbols.clear()
    }

    fun registerVariable(variable: IrVariable, signature: VariableSignature) {
        globalSymbols[variable.symbol] = signature
        localSymbols += variable.symbol
    }

    fun registerProperty(property: IrProperty, prefix: String = "") {
        val name = prefix + property.name.asString()
        val anns = ConverterUtils.getAnnotations(property.annotations)

        val typeClass = property.backingField?.type?.getClass()
            ?: property.getter?.returnType?.getClass()
        if (typeClass != null && SelectionUtils.isSelectionClass(typeClass)) {
            val initializer = property.backingField?.initializer?.expression
            val selection = SelectionUtils.resolve(initializer)
                ?: if (property.name.asString().equals("DEFAULT", ignoreCase = true)) null
                else SelectionUtils.fromName(property.name.asString(), typeClass.name.asString().contains("Entity", ignoreCase = true))
            val desc = VariableSignature.Selection(selection)
            globalSymbols[property.symbol] = desc
            property.getter?.symbol?.let { globalSymbols[it] = desc }
            return
        }

        if (property.isConst || "Inline" in anns) {
            val constInit = property.backingField?.initializer?.expression
            if (constInit != null) {
                val constExpr = JustCompiler.convert(constInit)
                if (constExpr != null) {
                    val desc = VariableSignature.Inline(constExpr)
                    globalSymbols[property.symbol] = desc
                    property.getter?.symbol?.let { globalSymbols[it] = desc }
                    return
                }
            }
        }

        val scope = if ("Save" in anns) VariableScope.SAVE else VariableScope.GAME
        val descriptor = VariableSignature.Property(name, scope, property.getter.customOrNull(), property.setter.customOrNull())
        bindSymbols(property, descriptor)
    }

    fun registerVirtualProperty(property: IrProperty) {
        val name = property.name.asString()
        val getter = property.getter

        val returnType = getter?.returnType ?: property.backingField?.type
        val typeClass = returnType?.getClass()

        val initializer = property.backingField?.initializer?.expression
        val getterExpr = when (val body = getter?.body) {
            is IrExpressionBody -> body.expression
            is IrBlockBody -> (body.statements.lastOrNull() as? IrReturn)?.value
            else -> null
        }
        val targetExpr = initializer ?: getterExpr

        if (typeClass?.let { "Selection" in ConverterUtils.getAnnotations(it.annotations) } == true) {
            val selection = SelectionUtils.resolve(targetExpr)
            val desc = VariableSignature.Selection(selection)
            bindSymbols(property, desc)
            return
        }

        if (targetExpr != null) {
            val expr = JustCompiler.convert(targetExpr)
            if (expr != null) {
                val desc = VariableSignature.Inline(expr)
                bindSymbols(property, desc)
                return
            }
        }

        if (getter != null && getter.customOrNull() != null) {
            val bodyStmts = getter.body?.let { JustCompiler.convertBody(it) }.orEmpty()
            val desc = VariableSignature.Inline(IrExpr.Block(bodyStmts))
            bindSymbols(property, desc)
        }
    }

    private fun bindSymbols(property: IrProperty, desc: VariableSignature) {
        globalSymbols[property.symbol] = desc
        property.getter?.symbol?.let { globalSymbols[it] = desc }
        property.setter?.symbol?.let { globalSymbols[it] = desc }
        property.backingField?.symbol?.let { globalSymbols[it] = desc }
    }

    fun resolve(symbol: IrSymbol): VariableSignature? = globalSymbols[symbol]

    private fun IrSimpleFunction?.customOrNull(): IrSimpleFunction? =
        this?.takeIf { it.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR }
}
