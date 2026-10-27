package me.collumy.justcompiler.context.registry

import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.ir.IrArgument
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.context.model.FunctionSignature
import me.collumy.justcompiler.utils.ConverterUtils
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.ir.util.kotlinFqName

/**
 * Реестр сопоставления методов стандартной библиотеки Kotlin и Java (stdlib)
 * с соответствующими встроенными действиями (Action / BuiltIn) JustMC.
 */
object StdlibRegistry {

    fun resolve(fn: IrSimpleFunction): FunctionSignature? {
        val fqName = fn.kotlinFqName.asString()
        val isStdlib = fqName.startsWith("kotlin.") || fqName.startsWith("java.lang.")
        if (!isStdlib) return null

        val name = fn.name.asString()

        // 1. Создание коллекций
        when (name) {
            "listOf", "mutableListOf", "emptyList" ->
                return FunctionSignature.BuiltIn("list")

            "mapOf", "mutableMapOf", "emptyMap" ->
                return FunctionSignature.BuiltIn("map")
        }

        if (fqName == "kotlin.repeat") {
            val amountParam = fn.parameters.firstOrNull { it.name.asString() == "times" }
            val actionParam = fn.parameters.firstOrNull { it.type.isFunctionTypeOrSubtype() }
            return FunctionSignature.Action(
                id = "repeat_multi_times",
                parameters = listOfNotNull(amountParam),
                returns = listOf("variable"),
                lambdaParam = actionParam,
                paramSlotMap = mapOf("times" to "amount")
            )
        }

        if (fqName == "kotlin.error") {
            val msgParam = fn.parameters.firstOrNull { it.name.asString() == "message" }
            return FunctionSignature.Action(
                id = "control_call_exception",
                parameters = listOfNotNull(msgParam),
                extraArgs = listOf(
                    IrArgument("id", IrValue.Text("error")),
                    IrArgument("type", IrValue.Enum("ERROR"))
                ),
                paramSlotMap = mapOf("message" to "message")
            )
        }

        // 2. Свойства и методы коллекций
        when (name) {
            "size", "<get-size>" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return when (receiverType) {
                    ServerType.LIST -> FunctionSignature.Action(
                        id = "set_variable_get_list_length",
                        parameters = emptyList(),
                        returns = listOf("variable"),
                        thisArg = listOf("list")
                    )
                    ServerType.MAP -> FunctionSignature.Action(
                        id = "set_variable_get_map_size",
                        parameters = emptyList(),
                        returns = listOf("variable"),
                        thisArg = listOf("map")
                    )
                    else -> null
                }
            }

            "keys", "<get-keys>" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return if (receiverType == ServerType.MAP) {
                    FunctionSignature.Action(
                        id = "set_variable_get_map_keys",
                        parameters = emptyList(),
                        returns = listOf("variable"),
                        thisArg = listOf("map")
                    )
                } else null
            }

            "values", "<get-values>" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return if (receiverType == ServerType.MAP) {
                    FunctionSignature.Action(
                        id = "set_variable_get_map_values",
                        parameters = emptyList(),
                        returns = listOf("variable"),
                        thisArg = listOf("map")
                    )
                } else null
            }

            "clear" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return if (receiverType == ServerType.MAP) {
                    FunctionSignature.Action(
                        id = "set_variable_clear_map",
                        parameters = emptyList(),
                        returns = emptyList(),
                        thisArg = listOf("map")
                    )
                } else null
            }

            "removeAt" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return if (receiverType == ServerType.LIST) {
                    FunctionSignature.Action(
                        id = "set_variable_remove_list_value_at_index",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = listOf("removed_value"),
                        thisArg = listOf("variable", "list"),
                        paramSlotMap = mapOf("index" to "index")
                    )
                } else null
            }

            "indexOf" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return if (receiverType == ServerType.LIST) {
                    FunctionSignature.Action(
                        id = "set_variable_get_list_index_of_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = listOf("variable"),
                        thisArg = listOf("list"),
                        paramSlotMap = mapOf("element" to "value"),
                        extraArgs = listOf(IrArgument("search_mode", IrValue.Enum("FIRST")))
                    )
                } else null
            }

            "lastIndexOf" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return if (receiverType == ServerType.LIST) {
                    FunctionSignature.Action(
                        id = "set_variable_get_list_index_of_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = listOf("variable"),
                        thisArg = listOf("list"),
                        paramSlotMap = mapOf("element" to "value"),
                        extraArgs = listOf(IrArgument("search_mode", IrValue.Enum("LAST")))
                    )
                } else null
            }

            "get" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return when (receiverType) {
                    ServerType.LIST -> FunctionSignature.Action(
                        id = "set_variable_get_list_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = listOf("variable"),
                        thisArg = listOf("list"),
                        paramSlotMap = mapOf("index" to "number")
                    )
                    ServerType.MAP -> FunctionSignature.Action(
                        id = "set_variable_get_map_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = listOf("variable"),
                        thisArg = listOf("map")
                    )
                    else -> null
                }
            }

            "set" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return when (receiverType) {
                    ServerType.MAP -> FunctionSignature.Action(
                        id = "set_variable_set_map_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = listOf("variable"),
                        thisArg = listOf("map"),
                        paramSlotMap = mapOf("key" to "key", "value" to "value")
                    )
                    ServerType.LIST -> FunctionSignature.Action(
                        id = "set_variable_set_list_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = listOf("variable"),
                        thisArg = listOf("list"),
                        paramSlotMap = mapOf("index" to "number", "element" to "value")
                    )
                    else -> null
                }
            }

            "put" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                if (receiverType == ServerType.MAP) {
                    return FunctionSignature.Action(
                        id = "set_variable_put_map_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = listOf("replaced_value"),
                        thisArg = listOf("map"),
                        paramSlotMap = mapOf("key" to "key", "value" to "value")
                    )
                }
            }

            "add" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                if (receiverType == ServerType.LIST) {
                    return FunctionSignature.Action(
                        id = "set_variable_append_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = emptyList(),
                        thisArg = listOf("variable"),
                        paramSlotMap = mapOf("element" to "values", "value" to "values")
                    )
                }
            }

            "addAll" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                if (receiverType == ServerType.LIST) {
                    return FunctionSignature.Action(
                        id = "set_variable_append_list",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = emptyList(),
                        thisArg = listOf("variable", "list_1"),
                        paramSlotMap = mapOf("elements" to "list_2")
                    )
                }
            }

            "remove" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                if (receiverType == ServerType.MAP) {
                    return FunctionSignature.Action(
                        id = "set_variable_remove_map_entry",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = listOf("removed_value"),
                        thisArg = listOf("map")
                    )
                }
                if (receiverType == ServerType.LIST) {
                    return FunctionSignature.Action(
                        id = "set_variable_remove_list_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        returns = emptyList(),
                        thisArg = listOf("variable", "list"),
                        paramSlotMap = mapOf("element" to "value")
                    )
                }
            }

            "iterator" -> {
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                return when {
                    receiverType == ServerType.MAP -> FunctionSignature.Action(
                        id = "repeat_for_each_map_entry",
                        parameters = emptyList(),
                        returns = emptyList(),
                        thisArg = listOf("map")
                    )
                    receiverType == ServerType.LIST || fqName.contains("Iterable") || fqName.contains("Collection") || fqName.contains("List") || fqName.contains("Set") ->
                        FunctionSignature.Action(
                            id = "repeat_for_each_in_list",
                            parameters = emptyList(),
                            returns = emptyList(),
                            thisArg = listOf("list")
                        )
                    else -> null
                }
            }

            "joinToString" -> {
                return FunctionSignature.Action(
                    id = "set_variable_join_text",
                    parameters = fn.parameters.filter {
                        it.name.asString() in listOf("separator", "prefix", "postfix", "suffix", "limit", "truncated")
                    },
                    returns = listOf("variable"),
                    thisArg = listOf("list"),
                    paramSlotMap = mapOf("suffix" to "postfix")
                )
            }
        }

        // 3. Строковые операции и преобразования чисел
        when (name) {
            "uppercase", "toUpperCase" -> {
                return FunctionSignature.Action(
                    id = "set_variable_text_case",
                    parameters = emptyList(),
                    returns = listOf("variable"),
                    thisArg = listOf("text"),
                    extraArgs = listOf(IrArgument("case_type", IrValue.Enum("UPPER")))
                )
            }

            "lowercase", "toLowerCase" -> {
                return FunctionSignature.Action(
                    id = "set_variable_text_case",
                    parameters = emptyList(),
                    returns = listOf("variable"),
                    thisArg = listOf("text"),
                    extraArgs = listOf(IrArgument("case_type", IrValue.Enum("LOWER")))
                )
            }

            "toDouble", "toInt", "toFloat", "toLong", "toDoubleOrNull", "toIntOrNull",
            "toShort", "toByte", "toUInt", "toULong", "toUShort", "toUByte" -> {
                val receiverParam = fn.parameters.firstOrNull()
                val isTextReceiver = receiverParam?.type?.let { ConverterUtils.getSingleType(it) == ServerType.TEXT } == true ||
                        fqName.startsWith("kotlin.text.") || fqName.startsWith("java.lang.String")
                if (isTextReceiver) {
                    return FunctionSignature.Action(
                        id = "set_variable_convert_text_to_number",
                        parameters = fn.parameters.filter { it.name.asString() == "radix" },
                        returns = listOf("variable"),
                        thisArg = listOf("text"),
                        extraArgs = listOf(IrArgument("radix", IrValue.Number(10.0)))
                    )
                }
                return null
            }

            "toString" -> {
                val receiverParam = fn.parameters.firstOrNull()
                val receiverType = receiverParam?.let { ConverterUtils.getSingleType(it.type) }
                val isNumberReceiver = receiverType == ServerType.NUMBER ||
                        fqName.startsWith("kotlin.Double.") ||
                        fqName.startsWith("kotlin.Int.") ||
                        fqName.startsWith("kotlin.Number.") ||
                        fqName.startsWith("kotlin.Float.") ||
                        fqName.startsWith("kotlin.Long.") ||
                        fqName.startsWith("kotlin.Short.") ||
                        fqName.startsWith("kotlin.Byte.")
                if (isNumberReceiver) {
                    return FunctionSignature.Action(
                        id = "set_variable_convert_number_to_text",
                        parameters = fn.parameters.filter { it.name.asString() == "radix" },
                        returns = listOf("variable"),
                        thisArg = listOf("number"),
                        extraArgs = listOf(IrArgument("radix", IrValue.Number(10.0)))
                    )
                }
            }

            "length", "<get-length>" -> {
                val firstParam = fn.parameters.firstOrNull()
                if (firstParam?.type?.isString() == true || fqName.startsWith("kotlin.text.") || fqName.startsWith("java.lang.")) {
                    return FunctionSignature.Action(
                        id = "set_variable_text_length",
                        parameters = emptyList(),
                        returns = listOf("variable"),
                        thisArg = listOf("text")
                    )
                }
            }

            "trim" -> {
                return FunctionSignature.Action(
                    id = "set_variable_strip_text",
                    parameters = emptyList(),
                    returns = listOf("variable"),
                    thisArg = listOf("text"),
                    extraArgs = listOf(IrArgument("strip_type", IrValue.Enum("ALL")))
                )
            }

            "repeat" -> {
                return FunctionSignature.Action(
                    id = "set_variable_repeat_text",
                    parameters = fn.parameters.filter { it.name.asString() in listOf("n", "repeat") },
                    returns = listOf("variable"),
                    thisArg = listOf("text")
                )
            }

            "substring" -> {
                return FunctionSignature.Action(
                    id = "set_variable_trim_text",
                    parameters = fn.parameters.filter {
                        it.name.asString() in listOf("startIndex", "endIndex", "start", "end")
                    },
                    returns = listOf("variable"),
                    thisArg = listOf("text")
                )
            }

            "replace" -> {
                if (fn.parameters.any { it.name.asString() == "oldValue" }) {
                    return FunctionSignature.Action(
                        id = "set_variable_replace_text",
                        parameters = fn.parameters.filter {
                            it.name.asString() in listOf("oldValue", "newValue", "ignoreCase", "first", "replace", "replacement")
                        },
                        returns = listOf("variable"),
                        thisArg = listOf("text"),
                        paramSlotMap = mapOf("oldValue" to "replace", "newValue" to "replacement")
                    )
                }
            }

            "contains" -> {
                if (fqName.startsWith("kotlin.text.") || fqName.startsWith("java.lang.String")) {
                    return FunctionSignature.Action(
                        id = "if_variable_text_contains",
                        parameters = fn.parameters.filter {
                            it.name.asString() in listOf("other", "charSequence", "compare", "ignoreCase")
                        },
                        thisArg = listOf("value"),
                        paramSlotMap = mapOf("other" to "compare", "charSequence" to "compare")
                    )
                }
                val receiverType = fn.parameters.firstOrNull()?.let { ConverterUtils.getSingleType(it.type) }
                if (receiverType == ServerType.LIST || fqName.contains("Collection") || fqName.contains("List") || fqName.contains("Set")) {
                    return FunctionSignature.Action(
                        id = "if_variable_list_contains_value",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        thisArg = listOf("list"),
                        paramSlotMap = mapOf("element" to "values", "value" to "values")
                    )
                }
                if (receiverType == ServerType.MAP || fqName.contains("Map")) {
                    return FunctionSignature.Action(
                        id = "if_variable_map_has_key",
                        parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                        thisArg = listOf("map")
                    )
                }
            }

            "containsKey" -> {
                return FunctionSignature.Action(
                    id = "if_variable_map_has_key",
                    parameters = fn.parameters.filter { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.ExtensionReceiver },
                    thisArg = listOf("map")
                )
            }

            "startsWith" -> {
                if (fqName.startsWith("kotlin.text.") || fqName.startsWith("java.lang.String")) {
                    return FunctionSignature.Action(
                        id = "if_variable_text_starts_with",
                        parameters = fn.parameters.filter {
                            it.name.asString() in listOf("prefix", "compare", "ignoreCase")
                        },
                        thisArg = listOf("value"),
                        paramSlotMap = mapOf("prefix" to "compare")
                    )
                }
            }

            "endsWith" -> {
                if (fqName.startsWith("kotlin.text.") || fqName.startsWith("java.lang.String")) {
                    return FunctionSignature.Action(
                        id = "if_variable_text_ends_with",
                        parameters = fn.parameters.filter {
                            it.name.asString() in listOf("suffix", "compare", "ignoreCase")
                        },
                        thisArg = listOf("value"),
                        paramSlotMap = mapOf("suffix" to "compare")
                    )
                }
            }

            "matches" -> {
                if (fqName.startsWith("kotlin.text.") || fqName.startsWith("java.lang.String")) {
                    return FunctionSignature.Action(
                        id = "if_variable_text_matches",
                        parameters = fn.parameters.filter {
                            it.name.asString() in listOf("regex", "pattern", "values", "ignoreCase", "regularExpressions")
                        },
                        thisArg = listOf("match"),
                        extraArgs = listOf(IrArgument("regular_expressions", IrValue.Enum("TRUE"))),
                        paramSlotMap = mapOf("regex" to "values", "pattern" to "values")
                    )
                }
            }
        }

        return null
    }
}
