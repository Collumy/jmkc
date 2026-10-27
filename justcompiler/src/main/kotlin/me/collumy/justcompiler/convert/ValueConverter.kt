package me.collumy.justcompiler.convert

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.ActionSelection
import me.collumy.justcompiler.backend.GameValueSelection
import me.collumy.justcompiler.backend.TextParsing
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.utils.ConverterUtils.getAnnotations
import me.collumy.justcompiler.utils.Diagnostics
import me.collumy.justcompiler.utils.SelectionUtils
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrErrorCallExpression
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.constructedClass

object ValueConverter {

    fun convert(expr: IrConst): IrValue = when (val value = expr.value) {
        null -> IrValue.Null
        is Number -> IrValue.Number(value.toDouble())
        is Boolean -> IrValue.Number(if (value) 1.0 else 0.0)
        is String -> IrValue.Text(value)
        else -> IrValue.Text(value.toString())
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(call: IrCall, typeId: String? = null): IrExpr? {
        val function = call.symbol.owner
        val id = typeId ?: (getAnnotations(function.annotations)["BuiltInValue"]?.get("id") as? String) ?: return null

        return when (id) {
            "text" -> {
                val fnName = function.name.asString().lowercase()
                val parsingParam = function.parameters.firstOrNull { it.name.asString() == "parsing" }
                val parsingArg = parsingParam?.let { call.arguments.getOrNull(it.indexInParameters) }
                val parsedFromArg = when (val c = parsingArg?.let { JustCompiler.convert(it) }) {
                    is IrValue.Enum -> TextParsing.fromId(c.value.lowercase())
                    is IrValue.Text -> TextParsing.fromId(c.value.lowercase())
                    else -> null
                }
                val parsing = when (fnName) {
                    "plain" -> TextParsing.PLAIN
                    "legacy" -> TextParsing.LEGACY
                    "json" -> TextParsing.JSON
                    "mini" -> TextParsing.MINI
                    else -> parsedFromArg ?: TextParsing.LEGACY
                }
                val extParam = function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
                val valueParam = function.parameters.firstOrNull { it.name.asString() == "value" }
                val argExpr = valueParam?.let { call.arguments.getOrNull(it.indexInParameters) ?: it.defaultValue?.expression }
                    ?: extParam?.let { call.arguments.getOrNull(it.indexInParameters) }
                    ?: call.arguments.firstOrNull { it != null && it != call.dispatchReceiver }
                val convertedArg = argExpr?.let { JustCompiler.convert(it) }
                val str = extractString(convertedArg)
                IrValue.Text(str, parsing)
            }

            "game_value" -> {
                val name = (call.arguments.getOrNull(0) as? IrConst)?.value?.toString() ?: ""
                var selArg = call.arguments.getOrNull(1)
                while (selArg is IrTypeOperatorCall) {
                    selArg = selArg.argument
                }
                val selConverted = selArg?.let { JustCompiler.convert(it) }

                val (actionSel, isActionSel) = when {
                    selConverted is IrValue.Selection -> selConverted.selection to true
                    selArg != null && SelectionUtils.resolve(selArg) != null -> SelectionUtils.resolve(selArg) to true
                    selArg != null && SelectionUtils.isSelectionClass(selArg.type.getClass()) -> null to true
                    else -> null to (selConverted is IrValue.Selection)
                }

                if (isActionSel) {
                    if (actionSel in listOf(
                            ActionSelection.ALL_PLAYERS,
                            ActionSelection.RANDOM_PLAYER,
                            ActionSelection.ALL_ENTITIES,
                            ActionSelection.ALL_MOBS,
                            ActionSelection.RANDOM_ENTITY
                        )) {
                        Diagnostics.reportError("Ошибка, игровые значения не поддерживают множественную или случайную выборку ($actionSel)", call)
                    }
                    val isEntity = selArg?.type?.getClass()?.name?.asString()?.contains("Entity", ignoreCase = true) == true
                    val gameSelection = SelectionUtils.toGameValueSelection(actionSel, isEntity)
                    IrValue.GameValue(name, gameSelection)
                } else {
                    val selectionName = when (selConverted) {
                        is IrValue.Text -> selConverted.value
                        is IrValue.Enum -> selConverted.value
                        else -> (selArg as? IrGetEnumValue)?.symbol?.owner?.name?.asString()
                    }

                    val selection = selectionName?.let { runCatching { GameValueSelection.valueOf(it) }.getOrNull() }
                    IrValue.GameValue(name, selection)
                }
            }

            "list" -> {
                val elements = mutableListOf<IrExpr>()
                val vararg = call.arguments.firstOrNull() as? IrVararg
                vararg?.elements?.forEach { elem -> if (elem is IrExpression)
                    elements += JustCompiler.convert(elem) ?: return@forEach
                }
                IrValue.ListValue(elements)
            }

            "map" -> {
                val pairs = mutableListOf<Pair<IrExpr, IrExpr>>()
                val vararg = call.arguments.firstOrNull() as? IrVararg
                vararg?.elements?.forEach { elem ->
                    if (elem is IrCall && elem.symbol.owner.name.asString() == "to") {
                        val key = elem.arguments[0]?.let { JustCompiler.convert(it) }
                        val value = elem.arguments[1]?.let { JustCompiler.convert(it) }
                        if (key != null && value != null) pairs.add(key to value)
                    }
                }
                IrValue.MapValue(pairs)
            }

            else -> null
        }
    }


    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(call: IrConstructorCall): IrExpr? {
        val targetClass = call.symbol.owner.constructedClass
        val annotations = getAnnotations(targetClass.annotations)
        val typeId = annotations["BuiltInValue"]?.get("id") as? String ?: return null

        val args = extractConstructorArgs(call)
        return when (typeId.lowercase()) {
            "text" -> parseText(args)
            "location" -> parseLocation(args)
            "vector" -> parseVector(args)
            "potion" -> parsePotion(args)
            "sound" -> parseSound(args)
            "particle" -> parseParticle(args)
            "item" -> parseItem(args)
            else -> null
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun extractConstructorArgs(call: IrConstructorCall): Map<String, IrExpr?> {
        val constructor = call.symbol.owner
        val args = mutableMapOf<String, IrExpr?>()

        for ((i, param) in constructor.parameters.withIndex()) {
            val expr = call.arguments.getOrNull(i) ?: param.defaultValue?.expression
            if (expr is IrErrorExpression) continue
            args[param.name.asString()] = expr?.let {
                JustCompiler.convert(it) ?: error("Ошибка, не удалось конвертировать аргумент '${param.name}' у ${constructor.constructedClass.name}")
            }
        }
        return args
    }

    private fun parseText(a: Map<String, IrExpr?>): IrValue.Text {
        val valExpr = a["value"]
        val str = extractString(valExpr)
        val parsingExpr = a["parsing"]
        val parsing = extractParsing(parsingExpr)
        return IrValue.Text(str, parsing)
    }

    private fun extractString(expr: IrExpr?): String = when (expr) {
        is IrValue.Text -> expr.value
        is IrValue.Number -> if (expr.value % 1.0 == 0.0) expr.value.toLong().toString() else expr.value.toString()
        is IrExpr.Variable -> {
            val scopeText = if (expr.scope == VariableScope.GAME) "%var" else "%var_${expr.scope.id}"
            "$scopeText(${expr.name})"
        }
        is IrExpr.StringTemplate -> {
            val sb = StringBuilder()
            for (part in expr.parts) sb.append(extractString(part))
            sb.toString()
        }
        null -> ""
        else -> ""
    }

    private fun extractParsing(expr: IrExpr?): TextParsing {
        val str = when (expr) {
            is IrValue.Text -> expr.value
            is IrValue.Enum -> expr.value
            else -> null
        } ?: return TextParsing.LEGACY

        return TextParsing.entries.firstOrNull {
            it.name.equals(str, ignoreCase = true) || it.id.equals(str, ignoreCase = true)
        } ?: TextParsing.fromId(str.lowercase()) ?: TextParsing.LEGACY
    }

    private fun parseLocation(a: Map<String, IrExpr?>) = IrValue.Location(
        x = num(a, "x", 0.0), y = num(a, "y", 0.0), z = num(a, "z", 0.0),
        yaw = num(a, "yaw", 0.0), pitch = num(a, "pitch", 0.0)
    )

    private fun parseVector(a: Map<String, IrExpr?>) = IrValue.Vector(
        x = num(a, "x", 0.0), y = num(a, "y", 0.0), z = num(a, "z", 0.0)
    )

    private fun parsePotion(a: Map<String, IrExpr?>) = IrValue.Potion(
        id = need(a, "id"), level = num(a, "level", 0.0), duration = num(a, "duration", -1.0)
    )

    private fun parseSound(a: Map<String, IrExpr?>) = IrValue.Sound(
        id = need(a, "id"), pitch = num(a, "pitch", 0.0), volume = num(a, "volume", 1.0),
        variation = text(a, "variation", ""), source = text(a, "source", "MASTER")
    )


    private fun parseParticle(a: Map<String, IrExpr?>) = IrValue.Particle(
        id = need(a, "id"), amount = num(a, "amount", 1.0),
        spreadX = num(a, "spreadX", 0.0), spreadY = num(a, "spreadY", 0.0),
        motion = opt(a, "motion"), material = opt(a, "material"), size = opt(a, "size"),
        color = opt(a, "color"), toColor = opt(a, "toColor")
    )


    @Suppress("UNCHECKED_CAST")
    private fun parseItem(a: Map<String, IrExpr?>) = IrValue.Item(
        id = need(a, "id"), count = num(a, "count", 1.0),
        name = opt(a, "name"), lore = opt(a, "lore"),
        tags = opt(a, "tags"), unbreakable = bool(a, "unbreakable"),
        glowing = bool(a, "glowing"), components = opt(a, "components")
    )

    private fun opt(a: Map<String, IrExpr?>, key: String): IrExpr? = a[key]
    private fun need(a: Map<String, IrExpr?>, key: String): IrExpr = a[key] ?:
        error("Ошибка, не передан обязательный аргумент '$key'")

    private fun num(a: Map<String, IrExpr?>, key: String, def: Double) = opt(a, key) ?: IrValue.Number(def)
    private fun text(a: Map<String, IrExpr?>, key: String, def: String) = opt(a, key) ?: IrValue.Text(def)
    private fun bool(a: Map<String, IrExpr?>, key: String): Boolean {
        val num = opt(a, key) as? IrValue.Number ?: return false
        return num.value == 1.0
    }
}
