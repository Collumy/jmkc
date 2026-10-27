package me.collumy.justcompiler.context.registry

import me.collumy.justcompiler.utils.ConverterUtils
import me.collumy.justcompiler.context.collect.CompilerCollector.isInline
import me.collumy.justcompiler.context.collect.CompilerCollector.isInlineOrObject
import me.collumy.justcompiler.context.model.FunctionSignature
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.fileOrNull
import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.ir.IrArgument
import me.collumy.justcompiler.backend.ir.IrValue
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass

import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.types.IrSimpleType

object FunctionRegistry {
    private val signatures = mutableMapOf<IrSimpleFunctionSymbol, FunctionSignature>()
    private val nameCounts = mutableMapOf<String, Int>()
    val inlineCollectionFunctions = mutableListOf<IrSimpleFunction>()

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun resolve(symbol: IrSimpleFunctionSymbol): FunctionSignature =
        signatures[symbol] ?: register(symbol.owner)

    fun reset() {
        signatures.clear()
        nameCounts.clear()
        inlineCollectionFunctions.clear()
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun findMatchingInline(call: IrCall): IrSimpleFunction? {
        val targetName = call.symbol.owner.name.asString()
        val extParam = call.symbol.owner.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
        val callReceiver = call.dispatchReceiver
            ?: extParam?.let { call.arguments.getOrNull(it.indexInParameters) }
            ?: call.arguments.firstOrNull()
        val receiverType = callReceiver?.type?.let { ConverterUtils.getSingleType(it) } ?: return null

        val candidates = inlineCollectionFunctions.filter { candidate ->
            if (candidate.name.asString() != targetName) return@filter false
            val candExt = candidate.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
            val candRecParam = candExt ?: candidate.parameters.firstOrNull()
            val candReceiver = candRecParam?.type?.let { ConverterUtils.getSingleType(it) }
            candReceiver == receiverType
        }

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        val callLambda = call.arguments.mapNotNull {
            var cur = it
            while (cur is org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall) {
                cur = cur.argument
            }
            cur as? IrFunctionExpression
        }.firstOrNull()
        if (callLambda != null) {
            val callLambdaParamCount = callLambda.function.parameters.count {
                it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver
            }
            val matchedByLambda = candidates.firstOrNull { candidate ->
                val candLambdaParam = candidate.parameters.firstOrNull { it.type.isFunctionTypeOrSubtype() }
                val candFunctionalType = candLambdaParam?.type as? IrSimpleType
                val candParamCount = (candFunctionalType?.arguments?.size?.minus(1)) ?: -1
                candParamCount == callLambdaParamCount
            }
            if (matchedByLambda != null) return matchedByLambda
        }

        return candidates.first()
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun register(fn: IrSimpleFunction): FunctionSignature {
        signatures[fn.symbol]?.let { return it }

        if (fn.isFakeOverride && fn.overriddenSymbols.isNotEmpty()) {
            var cur = fn
            while (cur.isFakeOverride && cur.overriddenSymbols.isNotEmpty()) {
                cur = cur.overriddenSymbols.first().owner
            }
            val realSig = resolve(cur.symbol)
            signatures[fn.symbol] = realSig
            return realSig
        }

        val anns = ConverterUtils.getAnnotations(fn.annotations)

        if ("Action" !in anns && (fn.overriddenSymbols.any { it.owner.parentAsClass.name.asString() == "Any" } || (fn.name.asString() in listOf("equals", "hashCode", "toString") && fn.parameters.none { it.name.asString() == "radix" }))) {
            val sig = FunctionSignature.Ignored
            signatures[fn.symbol] = sig
            return sig
        }

        val parentClass = fn.parent as? IrClass
        if (parentClass != null && ConverterUtils.isTuple(parentClass)) {
            val name = fn.name.asString()
            if (name.matches(Regex("^component\\d+$"))) {
                val sig = FunctionSignature.Inline(fn)
                signatures[fn.symbol] = sig
                return sig
            }
            val sig = FunctionSignature.Ignored
            signatures[fn.symbol] = sig
            return sig
        }

        if (parentClass?.isData == true) {
            val name = fn.name.asString()
            if (name == "copy" || name.matches(Regex("^component\\d+$"))) {
                val sig = FunctionSignature.Inline(fn)
                signatures[fn.symbol] = sig
                return sig
            }
        }

        if ((fn.isInline || "Inline" in anns) && fn.body != null) {
            val sig = FunctionSignature.Inline(fn)
            signatures[fn.symbol] = sig
            inlineCollectionFunctions.add(fn)
            return sig
        }

        val builtInId = anns["BuiltInValue"]?.get("id") as? String
        if (builtInId != null) {
            val sig = FunctionSignature.BuiltIn(builtInId)
            signatures[fn.symbol] = sig
            return sig
        }

        val parentName = parentClass?.name?.asString()
        val isTextCompanion = (parentName == "Companion" && (parentClass.parent as? IrClass)?.name?.asString() == "Text") || parentName == "Text"
        if (isTextCompanion && fn.name.asString() in listOf("plain", "legacy", "json", "mini")) {
            val sig = FunctionSignature.BuiltIn("text")
            signatures[fn.symbol] = sig
            return sig
        }
        if (fn.name.asString() in listOf("plain", "legacy", "json", "mini") && fn.returnType.getClass()?.name?.asString() == "Text") {
            val sig = FunctionSignature.BuiltIn("text")
            signatures[fn.symbol] = sig
            return sig
        }
        StdlibRegistry.resolve(fn)?.let { sig ->
            signatures[fn.symbol] = sig
            return sig
        }

        if ("EventHandler" in anns) {
            val sig = FunctionSignature.Event
            signatures[fn.symbol] = sig
            return sig
        }

        if ("Action" in anns) {
            val actionMeta = anns["Action"]!!
            val actionId = actionMeta["id"] as? String ?: error("Action без id: ${fn.name}")
            @Suppress("UNCHECKED_CAST") val returns = (actionMeta["returns"] as? List<String>).orEmpty()
            @Suppress("UNCHECKED_CAST") val thisArg = (actionMeta["thisArg"] as? List<String>).orEmpty()

            val receiverClass = fn.dispatchReceiverParameter?.type?.getClass()
                ?: fn.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }?.type?.getClass()
            val receiverAnns = receiverClass?.let { ConverterUtils.getAnnotations(it.annotations) }.orEmpty()
            val hasSelection = receiverClass != null && "Selection" in receiverAnns

            val lambdaParam = fn.parameters.firstOrNull { it.type.isFunctionTypeOrSubtype() }
            val selectionParam = fn.parameters.firstOrNull { it.name.asString() == "selection" }

            val params = fn.parameters.filter { param ->
                val name = param.name.asString()
                val isReceiver = name == "<this>" || param.kind == IrParameterKind.DispatchReceiver || param.kind == IrParameterKind.ExtensionReceiver
                !isReceiver && name != "selection" && param != lambdaParam
            }

            val sig = FunctionSignature.Action(
                id = actionId,
                parameters = params,
                returns = returns,
                thisArg = thisArg,
                hasSelectionReceiver = hasSelection,
                selectionParam = selectionParam,
                lambdaParam = lambdaParam
            )
            signatures[fn.symbol] = sig
            return sig
        }

        val isObject = parentClass?.isObject == true
        val isUserClass = parentClass != null && !isObject && !parentClass.isInline && parentClass.fileOrNull != null
        val extReceiverClass = fn.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }?.type?.getClass()
        val extAnns = extReceiverClass?.let { ConverterUtils.getAnnotations(it.annotations) }.orEmpty()
        val isExtensionOnUserClass = extReceiverClass != null && !extReceiverClass.isObject && !extReceiverClass.isInline && "BuiltInValue" !in extAnns && extReceiverClass.fileOrNull != null
        val hasReceiver = isUserClass || isExtensionOnUserClass

        val realParams = fn.parameters.filter { param ->
            val isDispatch = param.name.asString() == "<this>" || param.kind == IrParameterKind.DispatchReceiver
            val isExtension = param.kind == IrParameterKind.ExtensionReceiver
            if (isObject) !isDispatch
            else if (hasReceiver) !isDispatch && !isExtension
            else true
        }

        val returns = ConverterUtils.getType(fn.returnType)
        val isProcess = "Process" in anns

        val baseName = ConverterUtils.getFullFunctionName(fn)
        val count = nameCounts.getOrDefault(baseName, 0)
        nameCounts[baseName] = count + 1
        val finalName = if (count == 0) baseName else "${baseName}_$count"

        val sig = FunctionSignature.Function(
            name = finalName,
            parameters = realParams,
            returns = returns,
            isProcess = isProcess,
            hasInstanceReceiver = hasReceiver
        )
        signatures[fn.symbol] = sig
        return sig
    }
}
