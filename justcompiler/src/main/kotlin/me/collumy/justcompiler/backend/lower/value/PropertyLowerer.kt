package me.collumy.justcompiler.backend.lower.value

import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.lower.IrLowerer
import me.collumy.justcompiler.backend.lower.IrLowerer.server
import me.collumy.justcompiler.backend.lower.Lowered
import me.collumy.justcompiler.backend.server.ServerAction
import me.collumy.justcompiler.backend.server.ServerArgument
import me.collumy.justcompiler.backend.server.ServerValue

object PropertyLowerer {

    fun lower(get: IrExpr.PropertyGet, targets: List<IrExpr.Variable> = emptyList()): Lowered {
        val target = IrLowerer.lower(get.target)
        val result = targets.singleOrNull()?.server ?: IrLowerer.temp()

        val action = when (get.type) {
            ServerType.MAP -> ServerAction("set_variable_get_map_value", listOf(
                ServerArgument("variable", result),
                ServerArgument("map", target.value),
                ServerArgument("key", ServerValue.Text(get.property))
            ))
            ServerType.LOCATION -> ServerAction("set_variable_get_coordinate", listOf(
                ServerArgument("variable", result),
                ServerArgument("location", target.value),
                ServerArgument("type", ServerValue.Enum(get.property))
            ))
            ServerType.VECTOR -> ServerAction("set_variable_get_vector_component", listOf(
                ServerArgument("variable", result),
                ServerArgument("vector", target.value),
                ServerArgument("vector_component", ServerValue.Enum(get.property))
            ))
            ServerType.POTION -> {
                val name = when (get.property) {
                    "id" -> "set_variable_get_potion_effect_type"
                    "level" -> "set_variable_get_potion_effect_amplifier"
                    "duration" -> "set_variable_get_potion_effect_duration"
                    else -> error("Неизвестное поле '${get.property}' у Potion")
                }

                ServerAction(name, listOf(
                    ServerArgument("variable", result),
                    ServerArgument("potion", target.value)
                ))
            }
            ServerType.SOUND -> {
                val name = when (get.property) {
                    "id" -> "set_variable_get_sound_type"
                    "pitch" -> "set_variable_get_sound_pitch"
                    "volume" -> "set_variable_get_sound_volume_action"
                    "variation" -> "set_variable_get_sound_variation"
                    "source" -> "set_variable_get_sound_source"
                    else -> error("Неизвестное поле '${get.property}' у Sound")
                }

                ServerAction(name, listOf(
                    ServerArgument("variable", result),
                    ServerArgument("sound", target.value)
                ))
            }
            ServerType.PARTICLE -> {
                val name = when (get.property) {
                    "id" -> "set_variable_get_particle_type"
                    "amount" -> "set_variable_get_particle_amount"
                    "spreadX" -> "set_variable_get_particle_spread"
                    "spreadY" -> "set_variable_get_particle_spread"
                    "motion" -> "set_variable_get_particle_offset"
                    "material" -> "set_variable_get_particle_material"
                    "size" -> "set_variable_get_particle_size"
                    "color" -> "set_variable_get_particle_color"
                    "toColor" -> "set_variable_get_particle_color"
                    else -> error("Неизвестное поле '${get.property}' у Particle")
                }

                val enum = when (get.property) {
                    "spreadX" -> ServerArgument("type", ServerValue.Enum("horizontal"))
                    "spreadY" -> ServerArgument("type", ServerValue.Enum("vertical"))
                    "color" -> ServerArgument("color_type", ServerValue.Enum("color"))
                    "toColor" -> ServerArgument("color_type", ServerValue.Enum("to_color"))
                    else -> null
                }

                ServerAction(name, listOf(
                    ServerArgument("variable", result),
                    ServerArgument("particle", target.value)
                ) + listOfNotNull(enum))
            }
            ServerType.ITEM -> {
                val (name, argName) = when (get.property) {
                    "id" -> "set_variable_get_item_type" to "type"
                    "count" -> "set_variable_get_item_amount" to "item"
                    "name" -> "set_variable_get_item_name" to "item"
                    "lore" -> "set_variable_get_item_lore" to "item"
                    "tags" -> "set_variable_get_item_custom_tags" to "item"
                    else -> error("Неизвестное поле '${get.property}' у Item")
                }

                ServerAction(name, listOf(
                    ServerArgument("variable", result),
                    ServerArgument(argName, target.value)
                ))
            }
            else -> error("Ошибка, в PropertyLowerer.lower(get) попал не тот тип ${get.type}")
        }

        return Lowered(result, target.pre + action)
    }

    fun lower(set: IrStmt.PropertySet): List<ServerAction> {
        val target = IrLowerer.lower(set.target)
        val value = IrLowerer.lower(set.value)
        if (set.target !is IrExpr.Variable)
            error("Ошибка, в нельзя устанавливать property builtinvalue не для переменной")

        val action = when (set.type) {
            ServerType.MAP -> ServerAction("set_variable_set_map_value", listOf(
                ServerArgument("variable", target.value),
                ServerArgument("map", target.value),
                ServerArgument("key", ServerValue.Text(set.property)),
                ServerArgument("value", value.value)
            ))
            ServerType.LOCATION -> ServerAction("set_variable_set_coordinate", listOf(
                ServerArgument("variable", target.value),
                ServerArgument("location", target.value),
                ServerArgument("coordinate", value.value),
                ServerArgument("type", ServerValue.Enum(set.property))
            ))
            ServerType.VECTOR -> ServerAction("set_variable_set_vector_component", listOf(
                ServerArgument("variable", target.value),
                ServerArgument("vector", target.value),
                ServerArgument("value", value.value),
                ServerArgument("vector_component", ServerValue.Enum(set.property))
            ))
            ServerType.POTION -> {
                val (name, argName) = when (set.property) {
                    "id" -> "set_variable_set_potion_effect_type" to "effect_type"
                    "level" -> "set_variable_set_potion_effect_amplifier" to "amplifier"
                    "duration" -> "set_variable_set_potion_effect_duration" to "duration"
                    else -> error("Неизвестное поле '${set.property}' у Potion")
                }

                ServerAction(name, listOf(
                    ServerArgument("variable", target.value),
                    ServerArgument("potion", target.value),
                    ServerArgument(argName, value.value)
                ))
            }
            ServerType.SOUND -> {
                val (name, argName) = when (set.property) {
                    "id" -> "set_variable_set_sound_type" to "value"
                    "pitch" -> "set_variable_set_sound_pitch" to "pitch"
                    "volume" -> "set_variable_set_sound_volume_action" to "volume"
                    "variation" -> "set_variable_set_sound_variation" to "variation"
                    "source" -> "set_variable_set_sound_source" to "source"
                    else -> error("Неизвестное поле '${set.property}' у Sound")
                }

                val argValue = if (set.property != "source") value.value else when (value.value) {
                    is ServerValue.Text -> ServerValue.Enum((value.value as ServerValue.Text).value)
                    is ServerValue.Variable -> ServerValue.Enum("MASTER", value.value as ServerValue.Variable)
                    else -> error("Source должен быть enum-константой, получено: ${value.value}")
                }

                ServerAction(name, listOf(
                    ServerArgument("variable", target.value),
                    ServerArgument("sound", target.value),
                    ServerArgument(argName, argValue)
                ))
            }
            ServerType.PARTICLE -> {
                val (name, argName) = when (set.property) {
                    "id" -> "set_variable_set_particle_type" to "type"
                    "amount" -> "set_variable_set_particle_amount" to "amount"
                    "spreadX" -> "set_variable_set_particle_spread" to "horizontal"
                    "spreadY" -> "set_variable_set_particle_spread" to "vertical"
                    "motion" -> "set_variable_set_particle_offset" to "offset"
                    "material" -> "set_variable_set_particle_material" to "material"
                    "size" -> "set_variable_set_particle_size" to "size"
                    "color" -> "set_variable_set_particle_color" to "hex_color"
                    "toColor" -> "set_variable_set_particle_color" to "hex_color"
                    else -> error("Неизвестное поле '${set.property}' у Particle")
                }

                val enum = when (set.property) {
                    "color" -> ServerArgument("color_type", ServerValue.Enum("color"))
                    "toColor" -> ServerArgument("color_type", ServerValue.Enum("to_color"))
                    else -> null
                }

                ServerAction(name, listOf(
                    ServerArgument("variable", target.value),
                    ServerArgument("particle", target.value),
                    ServerArgument(argName, target.value)
                ) + listOfNotNull(enum))
            }
            ServerType.ITEM -> {
                val (name, argName) = when (set.property) {
                    "id" -> "set_variable_set_item_type" to "type"
                    "count" -> "set_variable_set_item_amount" to "amount"
                    "name" -> "set_variable_set_item_name" to "text"
                    "lore" -> "set_variable_set_item_lore" to "lore"
                    else -> error("Неизвестное поле '${set.property}' у Item")
                }

                ServerAction(name, listOf(
                    ServerArgument("variable", target.value),
                    ServerArgument("item", target.value),
                    ServerArgument(argName, value.value)
                ))
            }
            else -> error("Ошибка, в PropertyLowerer.lower(set) попал не тот тип ${set.type}")
        }

        return target.pre + value.pre + action
    }
}