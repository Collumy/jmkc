package me.collumy.justcompiler.utils

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.util.fileOrNull

object Diagnostics {
    private var collector: MessageCollector? = null
    var currentFile: IrFile? = null
    var hasErrors: Boolean = false
        private set

    fun init(messageCollector: MessageCollector?) {
        collector = messageCollector
        currentFile = null
        hasErrors = false
    }

    fun reportError(message: String, element: IrElement? = null, file: IrFile? = null): Nothing {
        hasErrors = true
        val targetFile = file ?: currentFile ?: (element as? IrDeclaration)?.fileOrNull
        val location = if (element != null && targetFile != null) {
            val fileEntry = targetFile.fileEntry
            val range = fileEntry.getSourceRangeInfo(element.startOffset, element.endOffset)
            CompilerMessageLocation.create(
                path = fileEntry.name,
                line = range.startLineNumber + 1,
                column = range.startColumnNumber + 1,
                lineContent = null
            )
        } else null

        val mc = collector
        if (mc != null) {
            mc.report(CompilerMessageSeverity.ERROR, message, location)
            throw JustCompilerCompilationException(message)
        } else {
            val locStr = location?.let { " (${it.path}:${it.line}:${it.column})" } ?: ""
            error("$message$locStr")
        }
    }

    fun reportWarning(message: String, element: IrElement? = null, file: IrFile? = null) {
        val targetFile = file ?: currentFile ?: (element as? IrDeclaration)?.fileOrNull
        val location = if (element != null && targetFile != null) {
            val fileEntry = targetFile.fileEntry
            val range = fileEntry.getSourceRangeInfo(element.startOffset, element.endOffset)
            CompilerMessageLocation.create(
                path = fileEntry.name,
                line = range.startLineNumber + 1,
                column = range.startColumnNumber + 1,
                lineContent = null
            )
        } else null

        collector?.report(CompilerMessageSeverity.WARNING, message, location)
    }
}

class JustCompilerCompilationException(message: String) : RuntimeException(message)
