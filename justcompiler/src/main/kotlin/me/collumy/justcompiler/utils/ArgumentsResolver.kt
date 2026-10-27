package me.collumy.justcompiler.utils

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.ir.IrArgument
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrValue
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classifierOrNull

data class BoundArgument(val slotName: String, val expr: IrExpression?, val parameter: IrValueParameter? = null)

object ArgumentResolver {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun resolveArgs(call: IrFunctionAccessExpression, isAction: Boolean = false): List<IrArgument> {
        val function = call.symbol.owner
        val bindings = function.parameters.mapIndexed { index, param ->
            BoundArgument(ConverterUtils.cleanParamName(param, index), call.arguments.getOrNull(index), param)
        }
        return resolveArgs(bindings, isAction)
    }

    fun resolveArgs(bindings: List<BoundArgument>, isAction: Boolean): List<IrArgument> {
        val args = mutableListOf<IrArgument>()

        for ((slotName, expr, parameter) in bindings) {
            val validExpr = if (expr is IrErrorExpression) null else expr
            val defaultExpr = parameter?.defaultValue?.expression.takeIf { it !is IrErrorExpression }
            val finalExpr = validExpr ?: defaultExpr ?: continue
            val convertedValue = resolveArgumentValue(finalExpr, parameter, isAction, slotName) ?: continue
            val finalValue = if (isAction && slotName in listOf("compare", "values", "components") && convertedValue !is IrValue.ListValue) {
                IrValue.ListValue(listOf(convertedValue))
            } else convertedValue
            args += IrArgument(slotName, finalValue)
        }

        return args
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun resolveArgumentValue(expr: IrExpression, param: IrValueParameter?, isAction: Boolean, slotName: String = ""): IrExpr? {
        // 1. Обработка vararg (vararg messages: String -> ListValue)
        if (param?.varargElementType != null && expr is IrVararg) {
            val listElements = mutableListOf<IrExpr>()
            for (element in expr.elements) {
                when (element) {
                    is IrSpreadElement -> {
                        val converted = JustCompiler.convert(element.expression)
                        if (converted is IrValue.ListValue) listElements.addAll(converted.values)
                        else if (converted != null) listElements.add(converted)
                    }
                    is IrExpression -> {
                        val converted = JustCompiler.convert(element)
                        if (converted != null) listElements.add(converted)
                    }
                }
            }
            return IrValue.ListValue(listElements)
        }

        val converted = JustCompiler.convert(expr) ?: return null

        // 2. Обработка Enum-констант и переменных для экшенов
        val irClass = (param?.type?.classifierOrNull as? IrClassSymbol)?.owner
        if (irClass?.kind == ClassKind.ENUM_CLASS && isAction) {
            if (expr is IrGetEnumValue) {
                val entry = expr.symbol.owner
                val customValue = ConverterUtils.getAnnotations(entry.annotations)["SlotValue"]?.get("value") as? String
                val enumName = customValue ?: entry.name.toString()
                return IrValue.Enum(enumName)
            } else if (converted is IrValue.Enum) {
                return converted
            } else if (converted is IrValue.Text) {
                return IrValue.Enum(converted.value)
            } else if (converted is IrExpr.Variable) {
                val firstEntry = irClass.declarations.filterIsInstance<IrEnumEntry>().firstOrNull()
                val customValue = firstEntry?.let { ConverterUtils.getAnnotations(it.annotations)["SlotValue"]?.get("value") as? String }
                val defaultValue = customValue ?: firstEntry?.name?.toString() ?: ""
                return IrValue.Enum(defaultValue, converted)
            }
        }

        if (irClass?.name?.asString() == "Boolean" && isAction) {
            val constExpr = expr as? IrConst
            if (constExpr?.value is Boolean) {
                return IrValue.Enum(if (constExpr.value as Boolean) "TRUE" else "FALSE")
            } else if (converted is IrValue.Enum) {
                return converted
            } else if (converted is IrValue.Number) {
                return IrValue.Enum(if (converted.value != 0.0) "TRUE" else "FALSE")
            } else if (converted is IrValue.Text) {
                return IrValue.Enum(converted.value.uppercase())
            }
        }

        return converted
    }
}
