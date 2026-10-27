package me.collumy.justcompiler.backend.lower.value

import me.collumy.justcompiler.backend.SoundSource
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.backend.lower.IrLowerer
import me.collumy.justcompiler.backend.lower.IrLowerer.ir
import me.collumy.justcompiler.backend.lower.IrLowerer.low
import me.collumy.justcompiler.backend.lower.IrLowerer.server
import me.collumy.justcompiler.backend.lower.Lowered
import me.collumy.justcompiler.backend.server.ServerAction
import me.collumy.justcompiler.backend.server.ServerArgument
import me.collumy.justcompiler.backend.server.ServerValue

object BuiltInLowerer {

    private class Def(val action: String, val result: String, vararg val slots: String)

    private val ZERO = IrValue.Number(0.0)

    private val LOCATION = Def("set_variable_set_all_coordinates", "variable", "x", "y", "z", "yaw", "pitch")
    private val VECTOR   = Def("set_variable_vector", "variable", "x", "y", "z")

    private val POTION_TYPE      = Def("set_variable_set_potion_effect_type", "variable", "potion", "effect_type")
    private val POTION_AMPLIFIER = Def("set_variable_set_potion_effect_amplifier", "variable", "potion", "amplifier")
    private val POTION_DURATION  = Def("set_variable_set_potion_effect_duration", "variable", "potion", "duration")

    private val SOUND_TYPE      = Def("set_variable_set_sound_type", "variable", "sound", "namespace", "value")
    private val SOUND_PITCH     = Def("set_variable_set_sound_pitch", "variable", "sound", "pitch")
    private val SOUND_VOLUME    = Def("set_variable_set_sound_volume_action", "variable", "sound", "volume")
    private val SOUND_VARIATION = Def("set_variable_set_sound_variation", "variable", "sound", "variation")
    private val SOUND_SOURCE    = Def("set_variable_set_sound_source", "variable", "sound", "source")

    private val PARTICLE_TYPE     = Def("set_variable_set_particle_type", "variable", "particle", "type")
    private val PARTICLE_AMOUNT   = Def("set_variable_set_particle_amount", "variable", "particle", "amount")
    private val PARTICLE_SPREAD   = Def("set_variable_set_particle_spread", "variable", "particle", "vertical", "horizontal")
    private val PARTICLE_OFFSET   = Def("set_variable_set_particle_offset", "variable", "particle", "offset")
    private val PARTICLE_SIZE     = Def("set_variable_set_particle_size", "variable", "particle", "size")
    private val PARTICLE_MATERIAL = Def("set_variable_set_particle_material", "variable", "particle", "material")
    private val PARTICLE_COLOR    = Def("set_variable_set_particle_color", "variable", "particle", "hex_color", "color_type")

    private val ITEM_TYPE   = Def("set_variable_set_item_type", "variable", "item", "type")
    private val ITEM_NAME   = Def("set_variable_set_item_name", "variable", "item", "text")
    private val ITEM_LORE   = Def("set_variable_set_item_lore", "variable", "item", "lore")
    private val ITEM_AMOUNT = Def("set_variable_set_item_amount", "variable", "item", "amount")
    private val ITEM_TAG    = Def("set_variable_set_item_custom_tag", "variable", "item", "tag_name", "tag_value")

    // ── цепочка шагов: константная база, поверх неё действия для динамических полей ──

    private class Chain(private val target: IrExpr.Variable?, private val base: ServerValue) {
        private val pre = mutableListOf<ServerAction>()
        private var current: ServerValue = base
        private val dest by lazy { target ?: IrLowerer.temp().ir }

        fun step(def: Def, vararg values: IrExpr) {
            val lowered = values.map { IrLowerer.lower(it) }
            lowered.forEach { pre += it.pre }
            pre += ServerAction(def.action, listOf(
                ServerArgument(def.result, dest.server),
                ServerArgument(def.slots[0], current)) +
                lowered.mapIndexed { i, l -> ServerArgument(def.slots[i + 1], l.value) }
            )
            current = dest.server
        }

        /** Шаг выполняется, если хотя бы одно из полей динамическое; незаданные поля шага получают 0. */
        fun stepIf(def: Def, vararg fields: IrExpr?) {
            if (fields.any { it.dyn() }) step(def, *Array(fields.size) { fields[it] ?: ZERO })
        }

        fun result() = if (pre.isEmpty()) Lowered(base, emptyList()) else Lowered(dest.server, pre)
    }

    private inline fun build(target: IrExpr.Variable?, base: ServerValue, steps: Chain.() -> Unit): Lowered =
        Chain(target, base).apply(steps).result()

    // ── значения ──

    fun lower(loc: IrValue.Location, target: IrExpr.Variable?): Lowered {
        val f = listOf(loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
        return if (f.none { it.dyn() })
            ServerValue.Location(loc.x.d(0.0), loc.y.d(0.0), loc.z.d(0.0), loc.yaw.d(0.0), loc.pitch.d(0.0)).low
        else create(LOCATION, target, f)
    }

    fun lower(vec: IrValue.Vector, target: IrExpr.Variable?): Lowered {
        val f = listOf(vec.x, vec.y, vec.z)
        return if (f.none { it.dyn() }) ServerValue.Vector(vec.x.d(0.0), vec.y.d(0.0), vec.z.d(0.0)).low
        else create(VECTOR, target, f)
    }

    fun lower(p: IrValue.Potion, target: IrExpr.Variable?): Lowered =
        build(target, ServerValue.Potion(
            p.id.t("speed"), p.level.i(0), p.duration.i(-1)
        )) {
            stepIf(POTION_TYPE, p.id)
            stepIf(POTION_AMPLIFIER, p.level)
            stepIf(POTION_DURATION, p.duration)
        }

    fun lower(s: IrValue.Sound, target: IrExpr.Variable?): Lowered {
        val base = ServerValue.Sound(
            s.id.t("minecraft:ui.button.click"), s.pitch.d(0.0), s.volume.d(1.0), s.variation.t(""),
            SoundSource.fromId(s.source.enumName() ?: "MASTER") ?: SoundSource.MASTER
        )
        return build(target, base) {
            if (s.id.dyn()) step(SOUND_TYPE, IrValue.Text("minecraft"), s.id)
            stepIf(SOUND_PITCH, s.pitch)
            stepIf(SOUND_VOLUME, s.volume)
            stepIf(SOUND_VARIATION, s.variation)
            stepIf(SOUND_SOURCE, s.source)
        }
    }

    fun lower(p: IrValue.Particle, target: IrExpr.Variable?): Lowered {
        val m = (p.motion as? IrValue.Vector)?.takeIf(::isConst)          // константное движение уходит в базу
        val base = ServerValue.Particle(
            p.id.t("flame"), p.amount.i(1), p.spreadX.d(0.0), p.spreadY.d(0.0),
            m?.x?.d(0.0), m?.y?.d(0.0), m?.z?.d(0.0),
            p.material.tn(), p.size.dn(), constColor(p.color), constColor(p.toColor)
        )
        return build(target, base) {
            stepIf(PARTICLE_TYPE, p.id)
            stepIf(PARTICLE_AMOUNT, p.amount)
            stepIf(PARTICLE_SPREAD, p.spreadY, p.spreadX)
            stepIf(PARTICLE_OFFSET, p.motion)
            stepIf(PARTICLE_SIZE, p.size)
            if (p.material.dyn()) step(PARTICLE_MATERIAL, IrValue.Item(id = p.material!!))
            if (p.color.dyn())    step(PARTICLE_COLOR, hexOf(p.color!!), IrValue.Enum("COLOR"))
            if (p.toColor.dyn())  step(PARTICLE_COLOR, hexOf(p.toColor!!), IrValue.Enum("TO_COLOR"))
        }
    }

    fun lower(i: IrValue.Item, target: IrExpr.Variable?): Lowered {
        val tags = (i.tags as? IrValue.MapValue)
        val constTags = tags?.pairs?.all { isConst(it.first) && isConst(it.second) } == true
        val name = i.name as? IrValue.Text
        val constName = isConst(name)
        val lore = i.lore as? IrValue.ListValue
        val constLore = isConst(lore)

        val base = ServerValue.Item(
            id = i.id.t("minecraft:stone"),
            count = i.count.i(1),
            name = i.name.tn(),
            lore = lore?.values?.map { it.t("") } ?: emptyList(),
            tags = if (constTags) tags.pairs.associate { it.first.t("") to it.second.t("") } else emptyMap(),
            components = convertIntoComponents(i),
        )

        return build(target, base) {
            if (i.id.dyn()) step(ITEM_TYPE, IrValue.Item(id = i.id))
            stepIf(ITEM_AMOUNT, i.count)

            if (i.name != null && !constName) step(ITEM_NAME, i.name)
            if (i.lore != null && !constLore) step(ITEM_LORE, i.lore)

            if (tags != null && !constTags) {
                for ((k, v) in tags.pairs) {
                    if (!(isConst(k) && isConst(v))) step(ITEM_TAG, k, v)
                }
            } else if (i.tags.dyn() && tags == null) {
                error("Ошибка, tags у Item должен быть map(...) на месте, а не переменной: теги ставятся по одному")
            }
        }
    }

    private fun convertIntoComponents(item: IrValue.Item): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        if (item.unbreakable) result["minecraft:unbreakable"] = emptyMap<String, Any>()
        if (item.glowing) {
            result["minecraft:enchantment_glint_override"] = true
            result["minecraft:enchantments"] = mapOf("minecraft:luck_of_the_sea" to 1)
            result["minecraft:tooltip_display"] = mapOf("hidden_components" to listOf("minecraft:enchantments"))
        }

        fun convertIrExpr(value: IrExpr): Any = when (value) {
            is IrValue.Text        -> value.value
            is IrValue.Number      -> value.value
            is IrValue.ListValue   -> value.values.map { convertIrExpr(it) }
            is IrValue.MapValue    -> value.pairs.map { (k, v) ->
                ((k as? IrValue.Text)?.value ?: error("Ключ мапы должен быть текстом: $k")) to convertIrExpr(v)
            }
            else                   -> error("Ошибка, нельзя передавать не text, number, list, map в компоненты предмета $item")
        }

        (item.components as? IrValue.MapValue)?.pairs?.forEach { (first, second) ->
            val key = if (first is IrValue.Text) first.value else return@forEach
            result[key] = convertIrExpr(second)
        }

        return result
    }

    /** Одно действие с несколькими слотами (Location, Vector); незаданные слоты = 0. */
    private fun create(def: Def, target: IrExpr.Variable?, values: List<IrExpr?>): Lowered {
        require(values.size == def.slots.size) {
            "${def.action}: ожидалось ${def.slots.size} аргументов, передано ${values.size}"
        }
        val dest = target?.server ?: IrLowerer.temp()
        val pre = mutableListOf<ServerAction>()
        val args = mutableListOf(ServerArgument(def.result, dest))

        for ((slot, expr) in def.slots.zip(values)) {
            val lowered = IrLowerer.lower(expr ?: ZERO)
            pre += lowered.pre
            args += ServerArgument(slot, lowered.value)
        }
        pre += ServerAction(def.action, args)
        return Lowered(dest, pre)
    }

    // ── помощники ──

    private fun isConst(e: IrExpr?): Boolean = when (e) {
        null, is IrValue.Number, is IrValue.Text, is IrValue.Null -> true
        is IrValue.Enum -> e.raw == null
        is IrValue.ListValue -> e.values.all { isConst(it) }
        is IrValue.Vector -> isConst(e.x) && isConst(e.y) && isConst(e.z)
        else -> false
    }
    private fun IrExpr?.dyn() = !isConst(this)                            // null = «не задано», это не динамика

    private fun IrExpr?.d(default: Double) = (this as? IrValue.Number)?.value ?: default
    private fun IrExpr?.i(default: Int) = d(default.toDouble()).toInt()
    private fun IrExpr?.t(default: String): String = when (this) {
        is IrValue.Text -> value
        is IrValue.Enum -> if (raw == null) value else default
        else -> default
    }
    private fun IrExpr?.dn() = (this as? IrValue.Number)?.value
    private fun IrExpr?.tn() = (this as? IrValue.Text)?.value
    private fun IrExpr?.enumName() = when (this) {
        is IrValue.Text -> value
        is IrValue.Enum -> if (raw == null) value else null
        else -> null
    }

    private fun hexOf(e: IrExpr): IrExpr =
        if (e is IrValue.Number) IrValue.Text("#%06X".format(e.value.toInt())) else e

    private fun constColor(e: IrExpr?): Double? = when (e) {
        is IrValue.Number -> e.value
        is IrValue.Text -> e.value.removePrefix("#").toIntOrNull(16)?.toDouble()
        else -> null
    }
}