package me.collumy.justcompiler.backend.server

import me.collumy.justcompiler.backend.*

sealed interface ServerValue {

    data class Variable(val name: String, val scope: VariableScope = VariableScope.LINE) : ServerValue
    data class Enum(val value: String, val raw: Variable? = null) : ServerValue
    data class GameValue(val value: String, val selection: GameValueSelection? = null) : ServerValue

    data class Text(val value: String, val parsing: TextParsing = TextParsing.LEGACY) : ServerValue
    data class Number(val value: Double = 0.0, val math: String? = null) : ServerValue
    data class Location(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0, val yaw: Double = 0.0, val pitch: Double = 0.0) : ServerValue
    data class Vector(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) : ServerValue

    data class Potion(val name: String, val level: Int = 0, val duration: Int = -1) : ServerValue
    data class Sound(val name: String, val pitch: Double = 0.0, val volume: Double = 1.0, val variation: String = "", val source: SoundSource = SoundSource.MASTER) : ServerValue

    data class Particle(
        val id: String, val count: Int = 1, val spreadX: Double = 0.0, val spreadY: Double = 0.0,
        val motionX: Double? = null, val motionY: Double? = null, val motionZ: Double? = null,
        val material: String? = null, val size: Double? = null, val color: Double? = null, val toColor: Double? = null
    ) : ServerValue

    data class Item(
        val id: String, val count: Int = 1, val name: String? = null, val lore: List<String> = emptyList(),
        val tags: Map<String, String> = emptyMap(), val components: Map<String, Any> = emptyMap()
    ) : ServerValue

    data class ListValue(val values: List<ServerValue> = emptyList()) : ServerValue
    data class MapValue(val pairs: List<Pair<ServerValue, ServerValue>> = emptyList()) : ServerValue
    data object Null : ServerValue
}


sealed interface ServerParameter : ServerValue {
    data class Singular(
        val name: String, val slot: Int, val descSlot: Int, val type: ServerType = ServerType.ANY,
        val display: ServerValue.Text? = null, val default: ServerValue? = null,
    ) : ServerParameter

    data class Plural(
        val name: String, val slots: List<Int>, val descSlots: List<Int>, val type: ServerType = ServerType.ANY,
        val display: ServerValue.Text? = null, val ignoreEmpty: Boolean = true, val default: ServerValue = ServerValue.ListValue(),
    ) : ServerParameter

    data class Enum(
        val name: String, val slot: Int, val elements: List<Element> = emptyList(),
        val display: ServerValue.Text? = null, val default: String? = null,
    ) : ServerParameter {
        data class Element(val name: String, val display: ServerValue.Text? = null, val icon: ServerValue.Item? = null)
    }
}