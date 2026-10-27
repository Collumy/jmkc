package me.collumy.justcompiler.convert.expr

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.ActionSelection
import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.*
import me.collumy.justcompiler.convert.ValueConverter
import me.collumy.justcompiler.utils.ArgumentResolver
import me.collumy.justcompiler.utils.BoundArgument
import me.collumy.justcompiler.utils.CollectionCalls
import me.collumy.justcompiler.utils.ConverterUtils
import me.collumy.justcompiler.utils.Diagnostics
import me.collumy.justcompiler.utils.FunctionUtils
import me.collumy.justcompiler.utils.InlineClassUtils
import me.collumy.justcompiler.utils.SelectionUtils
import me.collumy.justcompiler.context.CompilerContext
import me.collumy.justcompiler.context.model.FunctionSignature
import me.collumy.justcompiler.context.registry.FunctionRegistry
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.getNameWithAssert
import org.jetbrains.kotlin.ir.util.kotlinFqName

object CallConverter {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(call: IrCall): IrExpr? {
        parseLambdaInvoke(call)?.also { return it }
        InstanceConverter.get(call)?.also { return it }
        VariableConverter.convertPropertyGet(call)?.also { return it }

        convertTypeConversion(call)?.also { return it }
        convertNumber(call)?.also { return it }
        convertComparison(call)?.also { return it }
        convertRangeContains(call)?.also { return it }

        val symbol = call.symbol.owner.symbol
        var sig = FunctionRegistry.resolve(symbol)
        if (sig is FunctionSignature.Function) {
            val fq = call.symbol.owner.kotlinFqName.asString()
            if (fq.startsWith("kotlin.") || fq.startsWith("java.")) {
                val inlineMatch = FunctionRegistry.findMatchingInline(call)
                if (inlineMatch != null) {
                    sig = FunctionSignature.Inline(inlineMatch)
                }
            }
        }

        return when (sig) {
            is FunctionSignature.Action -> convertActionBySignature(call, sig)
            is FunctionSignature.Inline -> FunctionUtils.inline(sig.function, call.arguments)
            is FunctionSignature.BuiltIn -> ValueConverter.convert(call, sig.id)
            is FunctionSignature.Function -> convertFunctionCall(call, sig)
            is FunctionSignature.Event -> null
            is FunctionSignature.Ignored -> null
            null -> error("Ошибка, неизвестный вызов функции ${call.symbol.owner.name}")
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convertActionBySignature(call: IrCall, sig: FunctionSignature.Action): IrExpr.Action {
        val function = call.symbol.owner
        val extParam = function.parameters.firstOrNull { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.ExtensionReceiver }
        val extReceiver = extParam?.let { call.arguments.getOrNull(it.indexInParameters) }
        val receiverExpr = call.dispatchReceiver ?: extReceiver
        val convertedReceiver = receiverExpr?.let { JustCompiler.convert(it) }
        val actionArgs = mutableListOf<IrArgument>()

        // 1. Ресивер как аргумент слота (например, location -> "location")
        if (convertedReceiver != null) {
            for (arg in sig.thisArg) actionArgs += IrArgument(arg, convertedReceiver)
        }

        // 2. Обычные аргументы экшена
        val bindings = sig.parameters.map { param ->
            val slotAnnotation = ConverterUtils.getAnnotations(param.annotations)["Slot"]?.get("name") as? String
            val rawSlotName = ConverterUtils.cleanParamName(param)
            val snakeSlotName = rawSlotName
                .replace(Regex("([a-z])([A-Z])"), "$1_$2")
                .replace(Regex("([a-zA-Z])([0-9])"), "$1_$2")
                .lowercase()
            val slotName = slotAnnotation ?: sig.paramSlotMap[rawSlotName] ?: sig.paramSlotMap[snakeSlotName] ?: snakeSlotName
            BoundArgument(slotName, call.arguments.getOrNull(param.indexInParameters), param)
        }
        actionArgs += ArgumentResolver.resolveArgs(bindings, isAction = true)
        actionArgs += sig.extraArgs

        // 2.5 Автоматическое добавление поля inline-класса (если слота еще нет в actionArgs)
        val receiverClass = receiverExpr?.type?.getClass()
        if (convertedReceiver != null && receiverClass != null && InlineClassUtils.isInlineClass(receiverClass)) {
            val inlineSlot = InlineClassUtils.getInlineSlot(receiverClass)
            if (inlineSlot != null) {
                val hasSlot = actionArgs.any { it.name == inlineSlot }
                val hasPluralSlot = actionArgs.any { it.name == "${inlineSlot}s" }
                if (!hasSlot && !hasPluralSlot) {
                    actionArgs += IrArgument(inlineSlot, convertedReceiver)
                }
            }
        }

        // Слияние аргументов с одинаковым слотом (например, несколько компонентов в components)
        val mergedArgs = mutableListOf<IrArgument>()
        val grouped = actionArgs.groupBy { it.name }
        for ((name, group) in grouped) {
            if (group.size == 1) {
                mergedArgs += group.first()
            } else {
                val listElements = mutableListOf<IrExpr>()
                for (arg in group) {
                    when (val v = arg.value) {
                        is IrValue.ListValue -> listElements.addAll(v.values)
                        else -> listElements.add(v)
                    }
                }
                mergedArgs += IrArgument(name, IrValue.ListValue(listElements))
            }
        }

        // 3. Селектор: сначала явный аргумент (selection), затем ресивер-селектор
        val explicitSelection = sig.selectionParam?.let {
            val arg = call.arguments.getOrNull(it.indexInParameters)
            if (arg != null) SelectionUtils.resolve(arg) else null
        }

        val selection = explicitSelection ?: if (sig.hasSelectionReceiver) SelectionUtils.resolve(receiverExpr) else null

        // 4. Лямбда (если есть)
        val lambdaArg = sig.lambdaParam?.let { call.arguments.getOrNull(it.indexInParameters) }
        val lambdaFn = (lambdaArg as? IrFunctionExpression)?.function

        val hasLambdaParams = lambdaFn != null && lambdaFn.parameters.isNotEmpty()
        val actionBody = if (lambdaFn != null) {
            val isConditionalSelect = "select_" in sig.id && "_conditional" in sig.id
            if (!isConditionalSelect && hasLambdaParams && lambdaFn.parameters.size != sig.returns.size) {
                error("В action ${sig.id} кол-во параметров лямбды (${lambdaFn.parameters.size}) и returns (${sig.returns.size}) не равны")
            }
            CompilerContext.actionLambdas += lambdaFn
            if (hasLambdaParams) {
                mergedArgs += sig.returns.mapIndexed { idx, slot ->
                    val paramName = CompilerContext.declare(lambdaFn.parameters[idx].symbol)
                    IrArgument(slot, IrExpr.Variable(paramName, VariableScope.LINE))
                }
            }

            if (sig.id == "controller_label") {
                val labelArg = mergedArgs.find { it.name == "label" }?.value as? IrValue.Text
                val labelName = labelArg?.value ?: "label"
                CompilerContext.registerLabel(lambdaFn.symbol, labelName)
            }

            val body = JustCompiler.convertBody(lambdaFn.body)

            if (sig.id == "controller_label") {
                CompilerContext.activeLabels.remove(lambdaFn.symbol)
            }
            body
        } else null

        val returns = if (hasLambdaParams) emptyList() else sig.returns

        return IrExpr.Action(sig.id, mergedArgs, returns, selection, actionBody)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun convertFunctionCall(call: IrCall, sig: FunctionSignature.Function): IrExpr {
        val callee = IrValue.Text(sig.name)
        val bindings = sig.parameters.map { param ->
            val slotName = ConverterUtils.cleanParamName(param)
            BoundArgument(slotName, call.arguments.getOrNull(param.indexInParameters), param)
        }
        val userArgs = ArgumentResolver.resolveArgs(bindings, isAction = false)

        if (sig.hasInstanceReceiver) {
            val receiverArg = call.dispatchReceiver
                ?: call.arguments.firstOrNull()
                ?: error("Ошибка: не передан ресивер для ${sig.name}")
            val convertedReceiver = JustCompiler.convert(receiverArg)
                ?: error("Ошибка, не удалось конвертировать ресивер для ${sig.name}")

            if (convertedReceiver is IrExpr.Variable) {
                val allArgs = listOf(
                    IrArgument("this", convertedReceiver),
                    IrArgument("this_value", convertedReceiver)
                ) + userArgs
                return if (sig.isProcess) IrExpr.StartProcess(callee, allArgs)
                else IrExpr.FunctionCall(callee, allArgs, sig.returnsCount)
            } else {
                val tempVar = IrExpr.Variable("__recv_${CompilerContext.nextId()}", VariableScope.LINE)
                val allArgs = listOf(
                    IrArgument("this", tempVar),
                    IrArgument("this_value", tempVar)
                ) + userArgs
                val callExpr = if (sig.isProcess) IrExpr.StartProcess(callee, allArgs)
                else IrExpr.FunctionCall(callee, allArgs, sig.returnsCount)
                return IrExpr.Block(listOf(
                    IrStmt.Assign(tempVar, convertedReceiver),
                    IrStmt.Expr(callExpr)
                ))
            }
        }

        return if (sig.isProcess) IrExpr.StartProcess(callee, userArgs)
        else IrExpr.FunctionCall(callee, userArgs, sig.returnsCount)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun parseLambdaInvoke(call: IrCall): IrExpr? {
        val function = call.symbol.owner
        if (function.name.asString() != "invoke") return null
        val receiver = call.dispatchReceiver ?: return null
        if (!isFunctionalType(receiver.type)) return null

        if (receiver is IrGetValue) {
            CompilerContext.findLambda(receiver.symbol)?.let { lambda ->
                val lambdaCallArgs = if (call.dispatchReceiver != null) call.arguments.drop(1) else call.arguments
                return FunctionUtils.inline(lambda, lambdaCallArgs)
            }
        }

        val allArgs = ArgumentResolver.resolveArgs(call, isAction = false)
        val callee = allArgs.find { it.name == "<this>" }?.value ?: return null
        val args = allArgs.filter { it.name != "<this>" }.mapIndexed { index, arg -> IrArgument("arg$index", arg.value) }

        val returnsCount = ConverterUtils.getType(call.type).size
        return IrExpr.FunctionCall(callee, args, returnsCount)
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun isFunctionalType(type: IrType): Boolean {
        val classifier = type.classifierOrNull ?: return false
        val fqName = classifier.owner.let { (it as? IrDeclaration)?.getNameWithAssert()?.toString() } ?: return false
        return fqName.startsWith("Function")
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun convertComparison(call: IrCall): IrExpr? {
        val function = call.symbol.owner
        val parentClass = function.parent as? IrClass
        val isPrimitiveOrNumber = function.fileOrNull == null || parentClass?.name?.asString() in listOf("Double", "Int", "Float", "Long", "Number", "Boolean")
        if (!isPrimitiveOrNumber) return null

        val fnName = function.name.asString()

        val op = when (fnName) {
            "less", "<" -> BinaryOp.LT
            "lessOrEqual", "<=" -> BinaryOp.LTE
            "greater", ">" -> BinaryOp.GT
            "greaterOrEqual", ">=" -> BinaryOp.GTE
            "equals", "==", "EQEQ", "EQEQEQ" -> BinaryOp.EQ
            "notEquals", "!=", "NOT_EQUALS" -> BinaryOp.NEQ
            else -> null
        }

        if (op != null) {
            val left = call.arguments[0]?.let { JustCompiler.convert(it) } ?: return null
            val right = call.arguments[1]?.let { JustCompiler.convert(it) } ?: return null
            return IrExpr.Binary(left, op, right)
        }

        return null
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun convertTypeConversion(call: IrCall): IrExpr? {
        val function = call.symbol.owner
        val fnName = function.name.asString()
        val extParam = function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
        val extReceiver = extParam?.let { call.arguments.getOrNull(it.indexInParameters) }
        val receiver = call.dispatchReceiver ?: extReceiver ?: call.arguments.firstOrNull() ?: return null

        when (fnName) {
            "toDouble", "toInt", "toFloat", "toLong", "toShort", "toByte",
            "toDoubleOrNull", "toIntOrNull", "toFloatOrNull", "toLongOrNull",
            "toUInt", "toULong", "toUShort", "toUByte",
            "toUIntOrNull", "toULongOrNull", "toUShortOrNull", "toUByteOrNull" -> {
                val receiverType = ConverterUtils.getSingleType(receiver.type)
                return if (receiverType == ServerType.TEXT) {
                    val convertedReceiver = JustCompiler.convert(receiver) ?: return null
                    val radixParam = function.parameters.firstOrNull { it.name.asString() == "radix" }
                    val radixArg = radixParam?.let { call.arguments.getOrNull(it.indexInParameters) }?.let { JustCompiler.convert(it) }
                        ?: IrValue.Number(10.0)
                    IrExpr.Action(
                        name = "set_variable_convert_text_to_number",
                        args = listOf(
                            IrArgument("text", convertedReceiver),
                            IrArgument("radix", radixArg)
                        ),
                        returns = listOf("variable")
                    )
                } else {
                    JustCompiler.convert(receiver)
                }
            }

            "toString" -> {
                val receiverType = ConverterUtils.getSingleType(receiver.type)
                if (receiverType == ServerType.TEXT) {
                    return JustCompiler.convert(receiver)
                }
                if (receiverType == ServerType.NUMBER) {
                    val convertedReceiver = JustCompiler.convert(receiver) ?: return null
                    val radixParam = function.parameters.firstOrNull { it.name.asString() == "radix" }
                    val radixArg = radixParam?.let { call.arguments.getOrNull(it.indexInParameters) }?.let { JustCompiler.convert(it) }
                        ?: IrValue.Number(10.0)
                    return IrExpr.Action(
                        name = "set_variable_convert_number_to_text",
                        args = listOf(
                            IrArgument("number", convertedReceiver),
                            IrArgument("radix", radixArg)
                        ),
                        returns = listOf("variable")
                    )
                }
            }

            "toList", "toMutableList" -> {
                val receiverType = ConverterUtils.getSingleType(receiver.type)
                if (receiverType == ServerType.LIST) {
                    return JustCompiler.convert(receiver)
                }
            }

            "toMap", "toMutableMap" -> {
                val receiverType = ConverterUtils.getSingleType(receiver.type)
                if (receiverType == ServerType.MAP) {
                    return JustCompiler.convert(receiver)
                }
            }

            "toSet", "toMutableSet" -> {
                val receiverType = ConverterUtils.getSingleType(receiver.type)
                if (receiverType == ServerType.LIST) {
                    return JustCompiler.convert(receiver)
                }
            }
        }

        return null
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun convertNumber(call: IrCall): IrExpr? {
        val function = call.symbol.owner
        val parentClass = function.parent as? IrClass
        val isPrimitiveOrNumber = function.fileOrNull == null || parentClass?.name?.asString() in listOf("Double", "Int", "Float", "Long", "Number", "Boolean", "Short", "Byte")
        if (!isPrimitiveOrNumber) return null

        val functionName = function.name.asString()
        val singleType = ConverterUtils.getSingleType(call.type)

        if (call.arguments.size == 1) {
            when (functionName) {
                "CHECK_NOT_NULL" -> return JustCompiler.convert(call.arguments[0]!!)
                "toDouble", "toInt", "toFloat", "toLong", "toShort", "toByte" -> {
                    return JustCompiler.convert(call.arguments[0]!!)
                }
                "not" -> {
                    val target = JustCompiler.convert(call.arguments[0]!!) ?: return null
                    if (target is IrExpr.Binary && target.op == BinaryOp.EQ)
                        return IrExpr.Binary(target.left, BinaryOp.NEQ, target.right)
                    return IrExpr.Unary(UnaryOp.NOT, target)
                }

                "unaryMinus" -> {
                    if (singleType != ServerType.NUMBER) return null
                    val target = JustCompiler.convert(call.arguments[0]!!) ?: return null
                    if (target is IrValue.Number)
                        return IrValue.Number(-target.value)
                    return IrExpr.Unary(UnaryOp.NEG, target)
                }
                "inc" -> {
                    if (singleType != ServerType.NUMBER) return null
                    val target = JustCompiler.convert(call.arguments[0]!!) ?: return null
                    return IrExpr.Binary(target, BinaryOp.PLUS, IrValue.Number(1.0))
                }
                "dec" -> {
                    if (singleType != ServerType.NUMBER) return null
                    val target = JustCompiler.convert(call.arguments[0]!!) ?: return null
                    return IrExpr.Binary(target, BinaryOp.MINUS, IrValue.Number(1.0))
                }
            }
        }

        if (call.arguments.size == 2) {
            val binaryOp = when (functionName) {
                // Числовые
                "plus" -> if (singleType == ServerType.NUMBER) BinaryOp.PLUS else null
                "minus" -> if (singleType == ServerType.NUMBER) BinaryOp.MINUS else null
                "times" -> if (singleType == ServerType.NUMBER) BinaryOp.MUL else null
                "div" -> if (singleType == ServerType.NUMBER) BinaryOp.DIV else null
                "rem" -> if (singleType == ServerType.NUMBER) BinaryOp.REM else null

                "equals", "EQEQ", "EQEQEQ" -> BinaryOp.EQ
                "notEquals", "NOT_EQUALS" -> BinaryOp.NEQ
                "and" -> BinaryOp.AND
                "or" -> BinaryOp.OR

                else -> null
            }

            if (binaryOp != null) {
                val left = JustCompiler.convert(call.arguments[0]!!) ?: return null
                val right = JustCompiler.convert(call.arguments[1]!!) ?: return null
                return IrExpr.Binary(left, binaryOp, right)
            }
        }

        return null
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun convertRangeContains(call: IrCall): IrExpr? {
        val function = call.symbol.owner
        if (function.name.asString() != "contains") return null

        val parentName = (function.parent as? IrClass)?.name?.asString().orEmpty()
        val isRange = parentName.contains("Range") || parentName.contains("Progression")
        if (!isRange) return null

        val rangeExpr = call.dispatchReceiver ?: call.arguments.firstOrNull() ?: return null
        val rangeCall = rangeExpr as? IrCall ?: return null
        val rangeFnName = rangeCall.symbol.owner.name.asString()

        val includingMode = when (rangeFnName) {
            "rangeTo", ".." -> "INCLUDE_ALL"
            "rangeUntil", "until", "..<" -> "INCLUDE_FIRST"
            else -> return null
        }

        val minExpr = if (rangeCall.arguments.size >= 2) rangeCall.arguments[0] else (rangeCall.dispatchReceiver ?: rangeCall.arguments.firstOrNull())
        val maxExpr = if (rangeCall.arguments.size >= 2) rangeCall.arguments[1] else rangeCall.arguments.lastOrNull()
        val valueExpr = if (call.arguments.size >= 2) call.arguments[1] else call.arguments.lastOrNull()

        if (minExpr == null || maxExpr == null || valueExpr == null) return null

        val min = JustCompiler.convert(minExpr) ?: return null
        val max = JustCompiler.convert(maxExpr) ?: return null
        val value = JustCompiler.convert(valueExpr) ?: return null

        return IrExpr.Action(
            name = "if_variable_number_in_range",
            args = listOf(
                IrArgument("value", value),
                IrArgument("min", min),
                IrArgument("max", max),
                IrArgument("including", IrValue.Enum(includingMode))
            )
        )
    }
}
