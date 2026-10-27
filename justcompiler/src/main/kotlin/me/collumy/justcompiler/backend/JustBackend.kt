package me.collumy.justcompiler.backend

import kotlinx.serialization.json.JsonElement
import me.collumy.justcompiler.backend.ir.IrModule
import me.collumy.justcompiler.backend.lower.IrLowerer
import me.collumy.justcompiler.backend.server.JsonGenerator
import me.collumy.justcompiler.backend.utils.ApiUploader
import me.collumy.justcompiler.backend.utils.JsonUtils.anyToJsonElement
import me.collumy.justcompiler.backend.utils.JsonUtils.prettyJson
import java.awt.AWTException
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.StringSelection


object JustBackend {

    fun compile(module: IrModule): Map<String, List<Map<String, Any>>> {
        val lowered = IrLowerer.lower(module)
        return JsonGenerator.generate(lowered)
    }

    fun compileAndUpload(module: IrModule, force: Boolean = true) {
        val compiled = compile(module)
        val forced = if (force) "force " else ""
        val link = "/module loadUrl $forced" + ApiUploader.upload(compiled)
        println(link)
    }

    fun compileAndCopy(module: IrModule, force: Boolean = true) {
        val compiled = compile(module)
        val forced = if (force) "force " else ""
        val link = "/module loadUrl $forced" + ApiUploader.upload(compiled)
        copyAndNotify(link)
    }

    fun compileAndShow(module: IrModule, asJson: Boolean = true) {
        val compiled = compile(module)
        println("\n" + "=".repeat(20) + " [JustCompiler Output] " + "=".repeat(20))

        if (asJson) {
            val jsonElement = anyToJsonElement(compiled)
            val jsonString = prettyJson.encodeToString(JsonElement.serializer(), jsonElement)
            println(jsonString)
        } else {
            printPrettyTree(compiled)
        }

        println("=".repeat(63) + "\n")
    }

    private fun printPrettyTree(compiled: Map<String, List<Map<String, Any>>>) {
        for ((category, blocks) in compiled) {
            println("📂 [$category] (${blocks.size} блоков)")
            blocks.forEachIndexed { index, block ->
                val blockName = block["name"] ?: block["action"] ?: block["type"] ?: "Block #$index"
                println("  ├── #$index: $blockName")

                block.forEach { (key, value) ->
                    if (key != "name" && key != "action" && key != "type") {
                        println("  │    • $key: $value")
                    }
                }
            }
        }
    }

    private fun copyAndNotify(link: String) {
        val sel = StringSelection(link)
        val tk = Toolkit.getDefaultToolkit()
        tk.systemClipboard.setContents(sel, sel)

        if (!SystemTray.isSupported()) {
            println("Системный трей не поддерживается")
            return
        }

        try {
            val image = tk.createImage(ByteArray(0))

            val trayIcon = TrayIcon(image, "Ссылка скопирована")
            trayIcon.setImageAutoSize(true)

            trayIcon.displayMessage(
                "Ссылка скопирована",
                "Ссылка уже в буфере обмена\n$link",
                TrayIcon.MessageType.INFO
            )
        } catch (e: AWTException) {
            e.printStackTrace()
        }
    }
}