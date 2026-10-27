package me.collumy.justcompiler.plugin

import me.collumy.justcompiler.JustCompiler
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

class JustCompilerIrExtension(private val messageCollector: MessageCollector? = null) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        JustCompiler.process(moduleFragment.files, messageCollector)
    }
}