package me.collumy.justcompiler.backend.server

import me.collumy.justcompiler.backend.*
import me.collumy.justcompiler.backend.utils.NbtWriter

object ItemGenerator {

    // Цветовые коды
    private val HEX_COLOR_PATTERN = Regex("""&#([0-9A-Fa-f]{6})""")
    private val AMPERSAND_COLOR_PATTERN = Regex("""&([0-9a-fk-or])""", RegexOption.IGNORE_CASE)

    fun generate(item: ServerValue.Item): String {
        val components = buildComponents(item.name, item.lore, item.tags, item.components)

        val data = buildMap {
            put("id", item.id)
            put("count", item.count)
            if (components.isNotEmpty()) put("components", components)
        }

        return NbtWriter.toBase64Gzip(data)
    }

    private fun formatLoreLine(rawText: String): String {
        val coloredText = convertColors("&7$rawText")
        return """{"text":"$coloredText","italic":false}"""
    }

    private fun convertColors(text: String): String {
        var result = AMPERSAND_COLOR_PATTERN.replace(text) { "§${it.groupValues[1]}" }
        result = HEX_COLOR_PATTERN.replace(result) { match ->
            "§x" + match.groupValues[1].map { "§$it" }.joinToString("")
        }
        return result
    }


    private fun buildComponents(
        itemName: String?, itemLore: List<String>?,
        tags: Map<String, String>?, components: Map<String, Any>?
    ): Map<String, Any> = buildMap {
        if (itemName != null) put("minecraft:custom_name", convertColors(itemName))
        if (itemLore != null) put("minecraft:lore", itemLore.map { formatLoreLine(it) })

        if (!tags.isNullOrEmpty()) put("minecraft:custom_data", mapOf(
            "PublicBukkitValues" to tags.entries.associate { (k, v) -> "justcreativeplus:$k" to v }
        ))

        if (!components.isNullOrEmpty()) components.forEach { put(it.key, it.value) }
    }
}