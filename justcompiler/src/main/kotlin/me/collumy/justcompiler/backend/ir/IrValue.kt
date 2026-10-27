package me.collumy.justcompiler.backend.ir

import me.collumy.justcompiler.backend.*

sealed interface IrValue : IrExpr {
    data class Enum(val value: String, val raw: IrExpr.Variable? = null) : IrValue
    data class GameValue(val value: String, val selection: GameValueSelection? = null) : IrValue
    data class Selection(val selection: ActionSelection? = null) : IrValue
    data object Null : IrValue

    data class Number(val value: Double) : IrValue
    data class Text(val value: String, val parsing: TextParsing = TextParsing.LEGACY) : IrValue
    data class ListValue(val values: List<IrExpr> = emptyList()) : IrValue
    data class MapValue(val pairs: List<Pair<IrExpr, IrExpr>> = emptyList()) : IrValue

    data class Location(
        val x: IrExpr = Number(0.0), val y: IrExpr = Number(0.0), val z: IrExpr = Number(0.0),
        val yaw: IrExpr = Number(0.0), val pitch: IrExpr = Number(0.0)
    ) : IrValue

    data class Vector(
        val x: IrExpr = Number(0.0), val y: IrExpr = Number(0.0), val z: IrExpr = Number(0.0)
    ) : IrValue

    data class Potion(
        val id: IrExpr = Text("speed"), val level: IrExpr = Number(0.0), val duration: IrExpr = Number(-1.0)
    ) : IrValue

    data class Sound(
        val id: IrExpr = Text("minecraft:ui.button.click"),
        val pitch: IrExpr = Number(0.0), val volume: IrExpr = Number(1.0),
        val variation: IrExpr = Text(""), val source: IrExpr = Enum("MASTER")
    ) : IrValue

    data class Particle(
        val id: IrExpr = Text("flame"), val amount: IrExpr = Number(1.0),
        val spreadX: IrExpr = Number(0.0), val spreadY: IrExpr = Number(0.0),
        val motion: IrExpr? = null, val material: IrExpr? = null, val size: IrExpr? = null,
        val color: IrExpr? = null, val toColor: IrExpr? = null,
    ) : IrValue

    data class Item(
        val id: IrExpr = Text("diamond"), val count: IrExpr = Number(1.0),
        val name: IrExpr? = null, val lore: IrExpr? = null, val tags: IrExpr? = null,
        val unbreakable: Boolean = false, val glowing: Boolean = false, val components: IrExpr? = null
    ) : IrValue
}