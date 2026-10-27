package me.collumy.justcompiler.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

@OptIn(ExperimentalCompilerApi::class)
class JustCompilerRegistrar : CompilerPluginRegistrar() {
    override val pluginId = "me.collumy.justcompiler"
    override val supportsK2 = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector = try {
            val keyField = CommonConfigurationKeys::class.java.getField("MESSAGE_COLLECTOR_KEY")
            @Suppress("UNCHECKED_CAST")
            val key = keyField.get(null) as CompilerConfigurationKey<MessageCollector>
            configuration.get(key, MessageCollector.NONE)
        } catch (_: Throwable) {
            MessageCollector.NONE
        }
        IrGenerationExtension.registerExtension(JustCompilerIrExtension(messageCollector))
    }
}