package me.collumy.justcompiler.backend.utils

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

object NbtWriter {

    fun toBase64Gzip(compound: Map<String, Any>): String {
        val rawBuffer = ByteArrayOutputStream()
        val base64Stream = Base64.getEncoder().wrap(rawBuffer)
        GZIPOutputStream(base64Stream).use { gzip ->
            gzip.write(0x0A)
            gzip.writeUtf("")
            gzip.writeCompound(compound)
        }
        return rawBuffer.toString(Charsets.UTF_8)
    }

    private fun OutputStream.writeCompound(data: Map<String, Any>) {
        data.forEach { (key, value) -> writeTag(key, value) }
        write(0x00) // TAG_End
    }

    private fun OutputStream.writeUtf(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        write((bytes.size shr 8) and 0xFF)
        write(bytes.size and 0xFF)
        write(bytes)
    }

    private fun OutputStream.writeInt(value: Int) {
        write((value shr 24) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun OutputStream.writeDouble(value: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (i in 7 downTo 0) {
            write(((bits shr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun OutputStream.writeTag(name: String, value: Any) {
        when (value) {
            is Boolean -> {
                write(0x01) // TAG_Byte
                writeUtf(name)
                write(if (value) 1 else 0)
            }
            is Int -> {
                write(0x03) // TAG_Int
                writeUtf(name)
                writeInt(value)
            }
            is Double -> {
                write(0x06) // TAG_Double
                writeUtf(name)
                writeDouble(value)
            }
            is String -> {
                write(0x08) // TAG_String
                writeUtf(name)
                writeUtf(value)
            }
            is List<*> -> {
                write(0x09) // TAG_List
                writeUtf(name)
                if (value.isEmpty()) {
                    write(0x00)
                    writeInt(0)
                } else {
                    writeListPayload(value)
                }
            }
            is Map<*, *> -> {
                write(0x0A) // TAG_Compound
                writeUtf(name)
                @Suppress("UNCHECKED_CAST")
                writeCompound(value as Map<String, Any>)
            }
            else -> error("Неподдерживаемый тип NBT: ${value::class.simpleName}")
        }
    }

    private fun OutputStream.writeListPayload(list: List<*>) {
        when (val first = list.first()) {
            is String -> {
                write(0x08)
                writeInt(list.size)
                list.forEach { writeUtf(it as String) }
            }
            is Int -> {
                write(0x03)
                writeInt(list.size)
                list.forEach { writeInt(it as Int) }
            }
            is Map<*, *> -> {
                write(0x0A)
                writeInt(list.size)
                list.forEach {
                    @Suppress("UNCHECKED_CAST")
                    writeCompound(it as Map<String, Any>)
                }
            }
            else -> error("Неподдерживаемый тип элемента в NBT List: ${first!!::class.simpleName}")
        }
    }
}