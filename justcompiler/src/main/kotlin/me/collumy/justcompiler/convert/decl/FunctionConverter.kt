package me.collumy.justcompiler.convert.decl

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.*
import me.collumy.justcompiler.utils.ConverterUtils
import me.collumy.justcompiler.utils.ConverterUtils.main
import me.collumy.justcompiler.utils.FunctionUtils
import me.collumy.justcompiler.context.CompilerContext
import me.collumy.justcompiler.context.collect.CompilerCollector.isInline
import me.collumy.justcompiler.context.model.FunctionSignature
import me.collumy.justcompiler.context.registry.FunctionRegistry
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

object FunctionConverter {

    fun convert(fn: IrSimpleFunction): List<IrTrigger> {
        val sig = FunctionRegistry.resolve(fn.symbol)
        if (sig is FunctionSignature.Action || sig is FunctionSignature.BuiltIn ||
            sig is FunctionSignature.Inline || sig is FunctionSignature.Ignored) {
            return emptyList()
        }

        fun processFunction(): List<IrTrigger> {
            val triggers = mutableListOf<IrTrigger>()

            val rawBody = JustCompiler.convertBody(fn.body)
            triggers += extractLocalFunctions(fn.body)

            if (sig is FunctionSignature.Event) {
                val names = fn.parameters.filter { it.name.asString() != "<this>" }.map {
                    val clazz = it.type.getClass() ?: error("Ошибка, не получилось получить класс у @EventHandler ${fn.name}")
                    val anns = ConverterUtils.getAnnotations(clazz.annotations)
                    anns["Event"]?.get("id") as? String ?: error("Ошибка, неизвестный айди события у @EventHandler ${fn.name}")
                }

                return triggers + IrTrigger.Event(names, rawBody)
            }

            val isFuncSig = sig as? FunctionSignature.Function
            val hasInstanceReceiver = isFuncSig?.hasInstanceReceiver == true

            val name = isFuncSig?.name ?: ConverterUtils.getFullFunctionName(fn)
            val isAnon = fn.name.asString().startsWith("<anonymous>")

            val parameters = if (hasInstanceReceiver) {
                val otherParams = fn.parameters.filter { param ->
                    val isReceiver = param.name.asString() == "<this>" ||
                            param.kind == IrParameterKind.DispatchReceiver ||
                            param.kind == IrParameterKind.ExtensionReceiver
                    !isReceiver
                }.mapIndexed { index, param -> convertParameter(param, index, isAnon) }

                listOf(
                    IrParameter.Singular("this", ServerType.VARIABLE),
                    IrParameter.Singular("this_value", ServerType.MAP)
                ) + otherParams
            } else {
                val parentClass = fn.parent as? IrClass
                val isObject = parentClass?.isObject == true
                fn.parameters.filter { param ->
                    val isReceiver = param.name.asString() == "<this>" || param.kind == IrParameterKind.DispatchReceiver
                    !(isReceiver && isObject)
                }.mapIndexed { index, param -> convertParameter(param, index, isAnon) }
            }

            val body = if (hasInstanceReceiver) wrapInstanceMethodBody(rawBody) else rawBody
            val returns = isFuncSig?.returns ?: ConverterUtils.getType(fn.returnType)
            val isProcess = isFuncSig?.isProcess == true

            return if (isProcess) {
                if (returns.isNotEmpty()) error("Ошибка, процесс не может возвращать значения $name")
                triggers + IrTrigger.Process(name, body, parameters, getDisplay(fn))
            } else {
                triggers + IrTrigger.Function(name, body, parameters, returns, getDisplay(fn))
            }
        }

        val captured = if (fn.parent !is IrFunction) FunctionUtils.findCaptures(fn) else emptySet()
        return CompilerContext.withCaptured(captured) { processFunction() }
    }

    private fun extractLocalFunctions(body: IrBody?): List<IrTrigger> {
        if (body == null) return emptyList()
        val localFunctions = mutableListOf<IrSimpleFunction>()

        body.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
            override fun visitSimpleFunction(declaration: IrSimpleFunction) { localFunctions += declaration }
            override fun visitFunctionExpression(expression: IrFunctionExpression) {
                if (expression.function in CompilerContext.actionLambdas) return
                if (expression.function in CompilerContext.inlinedLambdas) return
                localFunctions += expression.function
            }
        })

        return localFunctions.flatMap { convert(it) }
    }

    private fun getDisplay(fn: IrSimpleFunction): IrTriggerDisplay? {
        val annotations = ConverterUtils.getAnnotations(fn.annotations)
        val displayMeta = annotations["Display"]

        val isPrivate = fn.visibility == DescriptorVisibilities.PRIVATE

        if (displayMeta == null && !isPrivate) return null

        val rawName = displayMeta?.get("name") as? String
        val rawLore = displayMeta?.get("lore") as? String
        val rawIcon = displayMeta?.get("icon") as? String

        val name = rawName?.takeIf { it.isNotBlank() }?.let { IrValue.Text(it) }
        val lore = rawLore?.takeIf { it.isNotBlank() }?.let { IrValue.Text(it) }
        val icon = rawIcon?.takeIf { it.isNotBlank() }?.let { IrValue.Item(id = IrValue.Text(it)) }

        return IrTriggerDisplay(name, lore, icon, isPrivate)
    }

    private fun wrapInstanceMethodBody(body: List<IrStmt>): List<IrStmt> {
        val pre = listOf(
            IrStmt.Assign(IrExpr.Variable("this", VariableScope.LINE), IrExpr.Variable("this_value", VariableScope.LINE))
        )
        return pre + body
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun convertParameter(param: IrValueParameter, index: Int, isAnon: Boolean): IrParameter {
        val name = ConverterUtils.cleanParamName(param, index, isAnon)
        val irClass = (param.type.classifierOrNull as? IrClassSymbol)?.owner

        // 1. Enum параметр
        if (irClass?.kind == ClassKind.ENUM_CLASS) {
            val elements = irClass.declarations.filterIsInstance<IrEnumEntry>().map {
                IrParameter.Enum.Element(it.name.identifier)
            }

            val defaultEntry = (param.defaultValue?.expression as? IrGetEnumValue)?.symbol?.owner?.name?.identifier
            return IrParameter.Enum(name, elements, default = defaultEntry)
        }

        // 2. Vararg (Plural) параметр
        if (param.varargElementType != null) {
            val elemType = param.varargElementType?.let { ConverterUtils.getType(it) }?.main() ?: ServerType.ANY
            return IrParameter.Plural(name, elemType)
        }

        // 3. Обычный одиночный (Singular) параметр
        val type = ConverterUtils.getType(param.type).main()
        val defaultValue = param.defaultValue?.expression?.let { expr ->
            (JustCompiler.convert(expr) as? IrValue)
        }

        return IrParameter.Singular(name, type, default = defaultValue)
    }
}

