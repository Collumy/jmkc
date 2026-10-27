package me.collumy.justcompiler.backend.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.collumy.justcompiler.backend.utils.JsonUtils
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object ApiUploader {

    fun upload(code: Map<String, Any?>): String {
        val url = URI.create("https://m.justmc.ru/api/upload").toURL()
        val connection = url.openConnection() as HttpURLConnection

        // 1. Настройка таймаутов
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000

        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true

        // 2. Подготовка и отправка данных
        val jsonString = JsonUtils.anyToJsonElement(code).toString()
        val bytes = jsonString.toByteArray(Charsets.UTF_8)

        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { out ->
            out.write(bytes)
            out.flush()
        }

        // 3. Безопасное чтение ответа с учетом статус-кода
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: error("Сервер вернул код $responseCode без тела ответа")

        val response = stream.bufferedReader().readText()

        if (responseCode !in 200..299) {
            error("Ошибка сервера ($responseCode): $response")
        }

        val json = Json.parseToJsonElement(response).jsonObject
        val id = json["id"]?.jsonPrimitive?.content ?: error("Поле 'id' отсутствует в ответе: $response")
        return "https://m.justmc.ru/api/$id"
    }

}