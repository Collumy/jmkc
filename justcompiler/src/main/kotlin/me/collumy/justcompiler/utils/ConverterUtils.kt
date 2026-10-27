package me.collumy.justcompiler.utils

import me.collumy.justcompiler.backend.ServerType
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import java.util.IdentityHashMap

object ConverterUtils {

    fun reset() {
        lambdaCounter = 0
        lambdaNames.clear()
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun getAnnotations(annotations: List<IrConstructorCall>): Map<String, Map<String, Any>> {
        val result = mutableMapOf<String, Map<String, Any>>()

        for (annotation in annotations) {
            val annotationName = annotation.type.classFqName?.shortName()?.asString() ?: continue
            val properties = mutableMapOf<String, Any>()

            val constructor = annotation.symbol.owner
            for (index in annotation.arguments.indices) {
                val param = constructor.parameters.getOrNull(index) ?: continue
                val paramName = param.name.asString()
                val argumentExpr = annotation.arguments[index] ?: continue

                val parsedValue = extractAnnotationValue(argumentExpr)
                if (parsedValue != null) {
                    properties[paramName] = parsedValue
                }
            }

            result[annotationName] = properties
        }

        return result
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun extractAnnotationValue(expression: IrExpression): Any? {
        return when (expression) {
            // Примитивы и строки (Int, String, Double, Boolean и т.д.)
            is IrConst -> expression.value

            // Значения enum (например: Priority.HIGH -> "HIGH")
            is IrGetEnumValue -> expression.symbol.owner.name.asString()

            // Массивы аргументов: @Tags(["a", "b"])
            is IrVararg -> expression.elements.mapNotNull { element ->
                if (element is IrExpression) extractAnnotationValue(element) else null
            }

            else -> expression.dumpKotlinLike()
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun isTuple(clazz: IrClass?): Boolean {
        if (clazz == null) return false
        val anns = getAnnotations(clazz.annotations)
        return "Tuple" in anns
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun getType(type: IrType): List<ServerType> {
        if (type.isUnit()) return emptyList()

        val simpleType = type as? IrSimpleType
        val irClass = (simpleType?.classifierOrNull as? IrClassSymbol)?.owner

        if (irClass != null) {
            val className = irClass.name.asString()
            if (className in listOf("Pair", "Triple") || irClass.isTuple) {

                return simpleType.arguments.map { typeArg ->
                    val innerType = when (typeArg) {
                        is IrTypeProjection -> typeArg.type
                        is IrStarProjection -> Diagnostics.reportError("Star projection (*) is not supported in $className", irClass)
                    }

                    val innerIrClass = (innerType.classifierOrNull as? IrClassSymbol)?.owner
                    val innerName = innerIrClass?.name?.asString()
                    if (innerName in listOf("Pair", "Triple") || innerIrClass?.isTuple == true) {
                        Diagnostics.reportError("Вложенные кортежи $innerName внутри $className запрещены!", irClass)
                    }

                    getSingleType(innerType)
                }
            }
        }

        return listOf(getSingleType(type))
    }

    fun List<ServerType>.main(): ServerType = if (size == 1) first() else error("в $this больше не 1 тип")

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun getSingleType(type: IrType): ServerType {
        val type = type.makeNotNull()

        if (type.isString() || type.isChar() || type.isFunction())
            return ServerType.TEXT

        if (type.isInt() || type.isLong() || type.isFloat() || type.isDouble() || type.isShort() || type.isByte() || type.isBoolean())
            return ServerType.NUMBER

        if (type.isArray() || type.isPrimitiveArray())
            return ServerType.LIST

        if (type.isAny())
            return ServerType.ANY

        val irClass = (type.classifierOrNull as? IrClassSymbol)?.owner
        if (irClass != null) {
            val fqName = irClass.name.identifier

            when (fqName) {
                "List", "MutableList", "Set", "MutableSet", "Collection", "Iterable" -> return ServerType.LIST
                "Map", "MutableMap" -> return ServerType.MAP
            }

            val id = getAnnotations(irClass.annotations)["BuiltInValue"]?.get("id") as? String
            if (id != null) ServerType.fromId(id)?.let { return it }

            return ServerType.MAP
        }

        return ServerType.ANY
    }

    private var lambdaCounter = 0
    private val lambdaNames = IdentityHashMap<IrFunction, String>()

    fun getFullFunctionName(function: IrFunction): String {
        val parts = mutableListOf<String>()

        val rawName = function.name.asString()
        val cleanName = if (rawName.startsWith("<anonymous>")) {
            lambdaNames.getOrPut(function) { "lambda_${++lambdaCounter}" }
        } else rawName
        parts.add(cleanName)

        var current: IrDeclarationParent? = function.parent
        while (current != null && current !is IrFile) {
            when (current) {
                is IrFunction -> {
                    val parentRawName = current.name.asString()
                    val parentCleanName = if (parentRawName.startsWith("<anonymous>")) {
                        lambdaNames.getOrPut(current) { "lambda_${++lambdaCounter}" }
                    } else {
                        parentRawName
                    }
                    parts.add(parentCleanName)
                    current = current.parent
                }
                is IrClass -> {
                    parts.add(current.name.asString())
                    current = current.parent
                }
                is IrDeclaration -> {
                    current = current.parent
                }
                else -> break
            }
        }

        return parts.reversed().joinToString(".")
    }

    fun cleanParamName(param: IrValueParameter, index: Int = -1, isAnon: Boolean = false): String {
        val rawName = param.name.asString()
        return when {
            rawName == "<this>" || param.kind == IrParameterKind.DispatchReceiver || param.kind == IrParameterKind.ExtensionReceiver -> "this"
            isAnon && index >= 0 -> "arg$index"
            else -> rawName
        }
    }
}

val IrClass.isTuple: Boolean get() = ConverterUtils.isTuple(this)

