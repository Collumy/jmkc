package me.collumy.justcompiler.utils

import me.collumy.justcompiler.backend.ActionSelection
import me.collumy.justcompiler.backend.GameValueSelection
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.context.CompilerContext
import me.collumy.justcompiler.context.model.VariableSignature
import me.collumy.justcompiler.context.registry.VariableRegistry
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.getClass

object SelectionUtils {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun isSelectionClass(clazz: IrClass?): Boolean {
        if (clazz == null) return false
        if ("Selection" in ConverterUtils.getAnnotations(clazz.annotations)) return true
        return clazz.superTypes.any { isSelectionClass(it.getClass()) }
    }

    /**
     * Извлекает ActionSelection из выражения-ресивера.
     * Возвращает null, если ресивер не является селектором или представляет DEFAULT.
     */
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun resolve(expr: IrExpression?): ActionSelection? {
        if (expr == null) return null
        var current: IrExpression = expr
        while (current is IrTypeOperatorCall) {
            current = current.argument
        }
        val isEntity = current.type.getClass()?.name?.asString()?.contains("Entity", ignoreCase = true) == true

        return when (current) {
            is IrGetObjectValue -> null

            is IrGetValue -> {
                val inlined = CompilerContext.findArg(current.symbol)
                if (inlined is IrValue.Selection) {
                    inlined.selection
                } else {
                    (VariableRegistry.resolve(current.symbol) as? VariableSignature.Selection)?.selection
                }
            }

            is IrCall -> {
                val fromRegistry = (VariableRegistry.resolve(current.symbol) as? VariableSignature.Selection)?.selection
                if (fromRegistry != null) return fromRegistry

                val prop = current.symbol.owner.correspondingPropertySymbol?.owner
                if (prop != null) {
                    val propName = prop.name.asString()
                    if (propName.equals("DEFAULT", ignoreCase = true)) return null
                    return fromName(propName, isEntity)
                }
                null
            }

            is IrGetField -> {
                val fromRegistry = (VariableRegistry.resolve(current.symbol) as? VariableSignature.Selection)?.selection
                if (fromRegistry != null) return fromRegistry

                val prop = current.symbol.owner.correspondingPropertySymbol?.owner
                if (prop != null) {
                    val propName = prop.name.asString()
                    if (propName.equals("DEFAULT", ignoreCase = true)) return null
                    return fromName(propName, isEntity)
                }
                null
            }

            is IrConstructorCall -> {
                val arg = current.arguments.firstOrNull()
                var actualArg = arg
                while (actualArg is IrTypeOperatorCall) {
                    actualArg = actualArg.argument
                }
                if (actualArg is IrGetEnumValue) {
                    val enumName = actualArg.symbol.owner.name.asString()
                    if (enumName.equals("DEFAULT", ignoreCase = true)) return null
                    return fromName(enumName, isEntity)
                }
                null
            }

            is IrGetEnumValue -> {
                val enumName = current.symbol.owner.name.asString()
                if (enumName.equals("DEFAULT", ignoreCase = true)) return null
                fromName(enumName, isEntity)
            }

            else -> null
        }
    }

    /**
     * Конвертирует ActionSelection в GameValueSelection.
     */
    fun toGameValueSelection(actionSelection: ActionSelection?, isEntityContext: Boolean = false): GameValueSelection = when (actionSelection) {
        null -> if (isEntityContext) GameValueSelection.DEFAULT_ENTITY else GameValueSelection.DEFAULT
        ActionSelection.DEFAULT -> GameValueSelection.DEFAULT
        ActionSelection.DEFAULT_ENTITY -> GameValueSelection.DEFAULT_ENTITY
        ActionSelection.CURRENT -> GameValueSelection.CURRENT
        ActionSelection.KILLER_PLAYER, ActionSelection.KILLER_ENTITY -> GameValueSelection.KILLER
        ActionSelection.DAMAGER_PLAYER, ActionSelection.DAMAGER_ENTITY -> GameValueSelection.DAMAGER
        ActionSelection.SHOOTER_PLAYER, ActionSelection.SHOOTER_ENTITY -> GameValueSelection.SHOOTER
        ActionSelection.VICTIM_PLAYER, ActionSelection.VICTIM_ENTITY -> GameValueSelection.VICTIM
        ActionSelection.PROJECTILE -> GameValueSelection.PROJECTILE
        ActionSelection.LAST_ENTITY -> GameValueSelection.LAST
        else -> if (isEntityContext) GameValueSelection.DEFAULT_ENTITY else GameValueSelection.DEFAULT
    }

    /**
     * Конвертирует имя свойства или enum-константы в ActionSelection.
     */
    fun fromName(rawName: String, isEntityContext: Boolean = false): ActionSelection {
        val snake = toSnakeCase(rawName).lowercase()

        // 1. Прямой поиск по id ("killer_player", "all_entities", "default")
        ActionSelection.fromId(snake)?.let { return it }

        // 2. Поиск по имени enum-элемента (KILLER_PLAYER, DEFAULT_ENTITY)
        ActionSelection.entries.firstOrNull { it.name.equals(rawName, ignoreCase = true) }?.let { return it }

        // 3. Суффиксация сокращений (DEFAULT, KILLER, ALL, CURRENT...)
        val target = when (snake) {
            "default" -> if (isEntityContext) "default_entity" else "default"
            "current" -> "current"
            "all" -> if (isEntityContext) "all_entities" else "all_players"
            "all_players" -> "all_players"
            "all_entities" -> "all_entities"
            "all_mobs" -> "all_mobs"
            "projectile" -> "projectile"
            "last" -> "last_entity"
            else -> if (isEntityContext) "${snake}_entity" else "${snake}_player"
        }

        return ActionSelection.fromId(target)
            ?: if (isEntityContext) ActionSelection.DEFAULT_ENTITY else ActionSelection.DEFAULT
    }

    private fun toSnakeCase(str: String): String =
        str.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}