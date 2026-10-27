package me.collumy.justcompiler.convert.expr

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.ActionSelection
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.IrArgument
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.backend.ir.UnaryOp
import me.collumy.justcompiler.utils.ConverterUtils.getAnnotations
import me.collumy.justcompiler.utils.FunctionUtils
import me.collumy.justcompiler.utils.SelectionUtils
import me.collumy.justcompiler.context.CompilerContext
import me.collumy.justcompiler.context.collect.CompilerCollector.isInline
import me.collumy.justcompiler.context.model.VariableSignature
import me.collumy.justcompiler.context.registry.VariableRegistry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.getClass
import me.collumy.justcompiler.utils.InlineClassUtils

object VariableConverter {

    fun convert(stmt: IrVariable): IrStmt? {
        val typeClass = stmt.type.getClass()

        if (typeClass != null && "Selection" in getAnnotations(typeClass.annotations)) {
            val selection = SelectionUtils.resolve(stmt.initializer)
            val desc = VariableSignature.Selection(selection)
            VariableRegistry.registerVariable(stmt, desc)
            return null
        }

        val name = CompilerContext.declare(stmt.symbol)
        val scope = CompilerContext.getScope(stmt.symbol)
        val vari = IrExpr.Variable(name, scope)

        var initializer = stmt.initializer ?: return null
        val isIfNotExists = initializer is IrCall && initializer.symbol.owner.name.asString() in listOf("ifNotExists", "once")
        if (isIfNotExists) {
            val call = initializer as IrCall
            initializer = call.arguments.firstOrNull() ?: initializer
        }

        val converted = if (initializer is IrConstructorCall) {
            ConstructorConverter.convert(initializer, vari)
        } else {
            JustCompiler.convert(initializer)
        } ?: return null

        val assignStmt = if (converted is IrExpr.Block && converted.body.lastOrNull() == IrStmt.Expr(vari)) {
            val stmts = converted.body.dropLast(1)
            if (stmts.size == 1) stmts[0] else IrStmt.Expr(IrExpr.Block(stmts))
        } else {
            IrStmt.Assign(vari, converted)
        }

        if (typeClass != null && InlineClassUtils.isInlineClass(typeClass)) {
            val slot = InlineClassUtils.getInlineSlot(typeClass) ?: ""
            val desc = VariableSignature.InlineClass(name, scope, slot)
            VariableRegistry.registerVariable(stmt, desc)
        }

        return if (isIfNotExists) {
            IrStmt.Expr(
                IrExpr.If(
                    branches = listOf(
                        IrExpr.If.Branch(
                            condition = IrExpr.Unary(
                                UnaryOp.NOT,
                                IrExpr.Action(
                                    name = "if_variable_exists",
                                    args = listOf(
                                        IrArgument("variable", vari),
                                        IrArgument("compare_type", IrValue.Enum("ANY"))
                                    )
                                )
                            ),
                            body = IrExpr.Block(listOf(assignStmt))
                        )
                    )
                )
            )
        } else {
            assignStmt
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(variable: IrGetValue): IrExpr {
        CompilerContext.findArg(variable.symbol)?.let { return it }
        val desc = VariableRegistry.resolve(variable.symbol)
        if (desc is VariableSignature.Selection) {
            return IrValue.Selection(desc.selection)
        }
        val owner = variable.symbol.owner
        val anns = getAnnotations(owner.annotations)

        // Определяем, где объявлена переменная
        val isGlobal = when (owner) {
            is IrVariable -> owner.parent !is IrFunction && owner.parent is IrFile
            is IrValueParameter -> false
            is IrField -> owner.parent is IrFile || owner.correspondingPropertySymbol?.owner?.parent is IrFile
            else -> false
        }

        val scope = when {
            !isGlobal -> CompilerContext.getScope(variable.symbol)
            "Save" in anns -> VariableScope.SAVE
            else -> VariableScope.GAME
        }

        val parentFn = (owner as? IrValueParameter)?.parent as? IrFunction
        val rawName = owner.name.asString()
        val cleanRawName = if (rawName == "<this>") "this" else rawName
        val name = when {
            parentFn?.name?.asString()?.startsWith("<anonymous>") == true &&
                    parentFn !in CompilerContext.actionLambdas -> {
                val index = parentFn.parameters.indexOf(owner)
                if (index != -1) "arg$index" else cleanRawName
            }
            scope == VariableScope.LOCAL -> cleanRawName
            else -> CompilerContext.resolveName(variable.symbol)
        }

        return IrExpr.Variable(name, scope)
    }

    fun convert(stmt: IrSetValue): IrStmt? {
        val name = CompilerContext.resolveName(stmt.symbol)
        val scope = CompilerContext.getScope(stmt.symbol)
        val vari = IrExpr.Variable(name, scope)

        var valueExpr = stmt.value
        val isIfNotExists = valueExpr is IrCall && valueExpr.symbol.owner.name.asString() in listOf("ifNotExists", "once")
        if (isIfNotExists) {
            val call = valueExpr as IrCall
            valueExpr = call.arguments.firstOrNull() ?: valueExpr
        }

        val converted = if (valueExpr is IrConstructorCall) {
            ConstructorConverter.convert(valueExpr, vari)
        } else {
            JustCompiler.convert(valueExpr)
        } ?: return null

        val assignStmt = if (converted is IrExpr.Block && converted.body.lastOrNull() == IrStmt.Expr(vari)) {
            val stmts = converted.body.dropLast(1)
            if (stmts.size == 1) stmts[0] else IrStmt.Expr(IrExpr.Block(stmts))
        } else {
            IrStmt.Assign(vari, converted)
        }

        return if (isIfNotExists) {
            IrStmt.Expr(
                IrExpr.If(
                    branches = listOf(
                        IrExpr.If.Branch(
                            condition = IrExpr.Unary(
                                UnaryOp.NOT,
                                IrExpr.Action(
                                    name = "if_variable_exists",
                                    args = listOf(
                                        IrArgument("variable", vari),
                                        IrArgument("compare_type", IrValue.Enum("ANY"))
                                    )
                                )
                            ),
                            body = IrExpr.Block(listOf(assignStmt))
                        )
                    )
                )
            )
        } else {
            assignStmt
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convertPropertyGet(call: IrCall): IrExpr? {
        val symbol = call.symbol.owner.correspondingPropertySymbol ?: return null

        val desc = VariableRegistry.resolve(symbol)
        if (desc is VariableSignature.Selection) {
            return IrValue.Selection(desc.selection)
        }
        val returnTypeClass = call.type.getClass()
        if (SelectionUtils.isSelectionClass(returnTypeClass)) {
            val fromResolve = SelectionUtils.resolve(call)
            return IrValue.Selection(fromResolve)
        }

        return when (desc) {
            is VariableSignature.Inline -> desc.replacement
            is VariableSignature.Selection -> IrValue.Selection(desc.selection)
            is VariableSignature.Property -> {
                if (desc.getter != null) FunctionUtils.inline(desc.getter, call.arguments)
                else IrExpr.Variable(desc.name, desc.scope)
            }
            is VariableSignature.Basic -> IrExpr.Variable(desc.name, desc.scope)
            is VariableSignature.InlineClass -> IrExpr.Variable(desc.name, desc.scope)
            else -> null
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convertPropertySet(call: IrCall): IrStmt? {
        val symbol = call.symbol.owner.correspondingPropertySymbol ?: return null
        val value = call.arguments.last()?.let { JustCompiler.convert(it) } ?: return null

        return when (val desc = VariableRegistry.resolve(symbol)) {
            is VariableSignature.Inline -> {
                val setter = call.symbol.owner.correspondingPropertySymbol?.owner?.setter
                if (setter != null && setter.body != null) {
                    IrStmt.Expr(
                        FunctionUtils.inline(setter, call.arguments)
                            ?: error("Ошибка, не удалось заинлайнить setter для ${call.symbol.owner.name}")
                    )
                } else {
                    error("Ошибка, нельзя устанавливать значение Inline переменным")
                }
            }
            is VariableSignature.Property -> {
                if (desc.setter != null) IrStmt.Expr(
                    FunctionUtils.inline(desc.setter, call.arguments)
                        ?: error("Ошибка, не удалось заинлайнить setter для ${call.symbol.owner.name}")
                )
                else IrStmt.Assign(IrExpr.Variable(desc.name, desc.scope), value)
            }
            is VariableSignature.Basic -> IrStmt.Assign(IrExpr.Variable(desc.name, desc.scope), value)
            is VariableSignature.InlineClass -> IrStmt.Assign(IrExpr.Variable(desc.name, desc.scope), value)
            else -> null
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(field: IrGetField): IrExpr? {
        val property = InstanceConverter.classProperty(field.symbol.owner.correspondingPropertySymbol?.owner)
        if (property != null) {
            val receiver = field.receiver?.let { JustCompiler.convert(it) } ?: return null
            return IrExpr.CollectionGet(true, receiver, IrValue.Text(property.name.asString()))
        }

        val descriptor = VariableRegistry.resolve(field.symbol)
        if (descriptor is VariableSignature.Selection) {
            return IrValue.Selection(descriptor.selection)
        }
        val returnTypeClass = field.type.getClass()
        if (SelectionUtils.isSelectionClass(returnTypeClass)) {
            val fromResolve = SelectionUtils.resolve(field)
            return IrValue.Selection(fromResolve)
        }

        return when (descriptor) {
            is VariableSignature.Property -> IrExpr.Variable(descriptor.name, descriptor.scope)
            is VariableSignature.Basic -> IrExpr.Variable(descriptor.name, descriptor.scope)
            is VariableSignature.InlineClass -> IrExpr.Variable(descriptor.name, descriptor.scope)
            is VariableSignature.Inline -> descriptor.replacement
            else -> null
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(field: IrSetField): IrStmt? {
        val value = JustCompiler.convert(field.value) ?: return null
        val property = InstanceConverter.classProperty(field.symbol.owner.correspondingPropertySymbol?.owner)
        if (property != null) {
            val receiver = field.receiver?.let { JustCompiler.convert(it) } ?: return null
            return IrStmt.CollectionSet(true, receiver, IrValue.Text(property.name.asString()), value)
        }

        return when (val descriptor = VariableRegistry.resolve(field.symbol)) {
            is VariableSignature.Property -> IrStmt.Assign(IrExpr.Variable(descriptor.name, descriptor.scope), value)
            is VariableSignature.Basic -> IrStmt.Assign(IrExpr.Variable(descriptor.name, descriptor.scope), value)
            is VariableSignature.InlineClass -> IrStmt.Assign(IrExpr.Variable(descriptor.name, descriptor.scope), value)
            else -> null
        }
    }
}
