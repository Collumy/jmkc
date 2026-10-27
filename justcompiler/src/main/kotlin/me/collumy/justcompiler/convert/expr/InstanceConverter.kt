package me.collumy.justcompiler.convert.expr

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.utils.ConverterUtils
import me.collumy.justcompiler.utils.FunctionUtils
import me.collumy.justcompiler.context.collect.CompilerCollector.isInline
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isObject

import me.collumy.justcompiler.utils.InlineClassUtils

object InstanceConverter {

    /** Свойство обычного пользовательского класса. Object-ы обрабатывает VariableRegistry, библиотека не наша. */
    fun classProperty(property: IrProperty?): IrProperty? {
        val owner = property?.parent as? IrClass ?: return null
        if (owner.isObject || owner.isInline || owner.fileOrNull == null) return null
        return property
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun get(call: IrCall): IrExpr? {
        val getter = call.symbol.owner
        val owner = getter.correspondingPropertySymbol?.owner?.parent as? IrClass
        if (owner != null && InlineClassUtils.isInlineClass(owner)) {
            if (getter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
                return FunctionUtils.inline(getter, call.arguments)
            }
            val receiver = call.arguments.firstOrNull()?.let { JustCompiler.convert(it) }
                ?: error("Ошибка, не удалось конвертировать объект при чтении '${getter.name}'")
            return receiver
        }

        val property = classProperty(getter.correspondingPropertySymbol?.owner) ?: return null
        if (getter.parameters.size != 1) return null

        if (getter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR)
            return FunctionUtils.inline(getter, call.arguments)

        val receiver = call.arguments[0]!!.let { JustCompiler.convert(it) }
            ?: error("Ошибка, не удалось конвертировать объект при чтении '${property.name}'")

        getType(call.arguments[0]!!.type)?.also {
            return IrExpr.PropertyGet(receiver, it, property.name.asString())
        }

        return IrExpr.CollectionGet(true, receiver, IrValue.Text(property.name.asString()))
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun set(call: IrCall): IrStmt? {
        val setter = call.symbol.owner
        val owner = setter.correspondingPropertySymbol?.owner?.parent as? IrClass
        if (owner != null && InlineClassUtils.isInlineClass(owner)) {
            if (setter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR) {
                return FunctionUtils.inline(setter, call.arguments)?.let { IrStmt.Expr(it) }
            }
            val receiver = call.arguments.firstOrNull()?.let { JustCompiler.convert(it) }
                ?: error("Ошибка, не удалось конвертировать объект при записи '${setter.name}'")
            val value = call.arguments.lastOrNull()?.let { JustCompiler.convert(it) }
                ?: error("Ошибка, не удалось конвертировать значение для '${setter.name}'")
            if (receiver is IrExpr.Variable) {
                return IrStmt.Assign(receiver, value)
            }
            return null
        }

        val property = classProperty(setter.correspondingPropertySymbol?.owner) ?: return null
        if (setter.parameters.size != 2) return null                       // <set-x>(this, value)

        if (setter.origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR)
            return FunctionUtils.inline(setter, call.arguments)?.let { IrStmt.Expr(it) }

        val receiver = call.arguments[0]?.let { JustCompiler.convert(it) }
            ?: error("Ошибка, не удалось конвертировать объект при записи '${property.name}'")
        val value = call.arguments[1]?.let { JustCompiler.convert(it) }
            ?: error("Ошибка, не удалось конвертировать значение для '${property.name}'")

        getType(call.arguments[0]!!.type)?.also {
            return IrStmt.PropertySet(receiver, it, property.name.asString(), value)
        }

        return IrStmt.CollectionSet(true, receiver, IrValue.Text(property.name.asString()), value)
    }

    private fun getType(type: IrType): ServerType? {
        val type = ConverterUtils.getSingleType(type)
        val isIt = type in listOf(
            ServerType.LOCATION, ServerType.VECTOR, ServerType.ITEM, ServerType.MAP,
            ServerType.POTION, ServerType.SOUND, ServerType.PARTICLE
        )
        return if (isIt) type else null
    }
}
