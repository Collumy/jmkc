package me.collumy.justcompiler.backend.server

import me.collumy.justcompiler.backend.TriggerType
import me.collumy.justcompiler.backend.utils.JsonUtils.escape

object JsonGenerator {

    fun generate(code: ServerCode): Map<String, List<Map<String, Any>>> =
        mapOf("handlers" to code.handlers.mapIndexed { i, t -> generate(t, i) })

    fun generate(trigger: ServerTrigger, position: Int): Map<String, Any> = buildMap {
        put("type", trigger.type.id)
        put("position", position)
        put(if (trigger.type == TriggerType.EVENT) "event" else "name", trigger.name)
        put("operations", trigger.body.map { generate(it) })

        val args = mutableListOf<Map<String, Any>>()
        if (trigger.display != null) {
            val dis = trigger.display

            if (dis.name != null) args += dis.name.toLocalized()
            if (dis.lore != null) args += dis.lore.toLocalized()
            if (dis.icon != null) args += generate(ServerArgument("icon", dis.icon))
            if (dis.hidden) args += generate(ServerArgument("is_hidden", ServerValue.Enum("true")))
        }

        if (trigger.args.isNotEmpty())
            args += generate(ServerArgument("parameters", ServerValue.ListValue(trigger.args)))

        if (args.isNotEmpty()) put("values", args)
    }

    fun generate(action: ServerAction): Map<String, Any> = buildMap {
        put("action", action.name)
        put("values", action.args.map { generate(it) })
        if (action.body != null) put("operations", action.body.map { generate(it) })
        if (action.selection != null) put("selection", mapOf("type" to action.selection.id))

        if (action.conditional != null) put("conditional", mapOf("action" to action.conditional, "is_inverted" to action.isInverted))
        else if (action.isInverted) put("is_inverted", true)
    }

    fun generate(argument: ServerArgument): Map<String, Any> = buildMap {
        put("name", argument.name)
        put("value", generate(argument.value))
    }


    fun generate(value: ServerValue): Map<String, Any> = when (value) {
        is ServerValue.Variable -> mapOf("type" to "variable", "variable" to value.name, "scope" to value.scope.id)
        is ServerValue.Enum -> buildMap {
            put("type", "enum"); put("enum", value.value.uppercase())
            if (value.raw != null) {
                put("variable", value.raw.name); put("scope", value.raw.scope.id)
            }
        }
        is ServerValue.GameValue -> mapOf(
            "type" to "game_value", "game_value" to value.value,
            "selection" to if (value.selection == null) "null" else mapOf("type" to value.selection.id).escape()
        )

        is ServerValue.Text -> mapOf("type" to "text", "text" to value.value, "parsing" to value.parsing.id)
        is ServerValue.Number -> {
            val number = if (value.math == null) value.value else "%math(${value.math})"
            mapOf("type" to "number", "number" to number)
        }
        is ServerValue.Location -> mapOf("type" to "location", "x" to value.x, "y" to value.y, "z" to value.z, "yaw" to value.yaw, "pitch" to value.pitch)
        is ServerValue.Vector -> mapOf("type" to "vector", "x" to value.x, "y" to value.y, "z" to value.z)

        is ServerValue.Potion -> mapOf("type" to "potion", "potion" to value.name, "amplifier" to value.level, "duration" to value.duration)
        is ServerValue.Sound -> mapOf(
            "type" to "sound", "sound" to value.name, "pitch" to value.pitch, "volume" to value.volume,
            "variation" to value.variation, "source" to value.source.id.uppercase()
        )
        is ServerValue.Particle -> buildMap {
            put("type", "particle")
            put("particle_type", value.id)
            put("count", value.count)
            put("first_spread", value.spreadX)
            put("second_spread", value.spreadY)
            if (value.motionX != null) put("motion_x", value.motionX)
            if (value.motionY != null) put("motion_y", value.motionY)
            if (value.motionZ != null) put("motion_z", value.motionZ)
            if (value.material != null) put("material", value.material)
            if (value.size != null) put("size", value.size)
            if (value.color != null) put("color", value.color)
            if (value.toColor != null) put("to_color", value.toColor)
        }

        is ServerValue.ListValue -> mapOf("type" to "array", "values" to value.values.map { generate(it) })
        is ServerValue.MapValue -> mapOf("type" to "map", "values" to buildMap {
            for ((k, v) in value.pairs) put(generate(k).escape(), generate(v)) }
        )
        is ServerValue.Null -> emptyMap()
        is ServerValue.Item -> mapOf("type" to "item", "item" to ItemGenerator.generate(value))



        is ServerParameter.Singular -> mapOf(
            "type" to "parameter", "type_key" to "singular",
            "description" to if (value.display != null) value.display.toLocalized()["data"]!! else "",
            "name" to value.name, "value_type" to value.type.id,
            "is_required" to "${value.default == null}",
            "default_value" to if (value.default == null) "{}" else generate(value.default).escape(),
            "slot" to value.slot, "description_slot" to value.descSlot
        )
        is ServerParameter.Plural -> mapOf(
            "type" to "parameter", "type_key" to "plural",
            "description" to if (value.display != null) value.display.toLocalized()["data"]!! else "",
            "name" to value.name, "value_type" to value.type.id,
            "is_required" to "false", "ignore_empty_values" to "${value.ignoreEmpty}",
            "default_value" to generate(value.default).escape(),
            "slots" to value.slots.joinToString(",","[","]"),
            "description_slots" to value.descSlots.joinToString(",","[","]")
        )
        is ServerParameter.Enum -> mapOf(
            "type" to "parameter", "type_key" to "enum",
            "description" to if (value.display != null) value.display.toLocalized()["data"]!! else "",
            "name" to value.name, "slot" to value.slot,
            "elements" to value.elements.map { generate(it) }.escape(),
            "default_element" to (value.default ?: ""),
        )
    }

    fun generate(element: ServerParameter.Enum.Element): String = mapOf(
        "name" to element.name,
        "display_name" to element.display.toLocalized(false)["data"],
        "icon" to generate(element.icon ?: ServerValue.Item("lapis_lazuli"))["item"]
    ).escape()


    fun ServerValue.Text?.toLocalized(escapeData: Boolean = true): Map<String, Any> {
        if (this == null) return mapOf("translations" to emptyMap<String, Any>())

        val data = mapOf(
            "translations" to emptyMap<String, Any>(),
            "fallback" to mapOf("rawText" to value, "parsingType" to parsing.id.uppercase())
        )

        return mapOf("type" to "localized_text", "data" to if (escapeData) data.escape() else data)
    }
}
