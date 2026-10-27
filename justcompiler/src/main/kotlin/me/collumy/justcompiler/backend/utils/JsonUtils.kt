package me.collumy.justcompiler.backend.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

object JsonUtils {

    @OptIn(ExperimentalSerializationApi::class)
    val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun Map<String, Any?>.escape(): String = anyToJsonElement(this).toString()
    fun List<Any?>.escape(): String = anyToJsonElement(this).toString()

    fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) ->
                if (k != null) {
                    put(k.toString(), anyToJsonElement(v))
                }
            }
        }
        is Iterable<*> -> buildJsonArray {
            value.forEach { add(anyToJsonElement(it)) }
        }
        is Array<*> -> buildJsonArray {
            value.forEach { add(anyToJsonElement(it)) }
        }
        else -> JsonPrimitive(value.toString())
    }
}