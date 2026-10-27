package me.collumy.justcompiler.context.model

import me.collumy.justcompiler.backend.ActionSelection
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.IrExpr
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

sealed class VariableSignature {

    data class Basic(val name: String, val scope: VariableScope) : VariableSignature()

    data class Property(
        val name: String,
        val scope: VariableScope,
        val getter: IrSimpleFunction? = null,
        val setter: IrSimpleFunction? = null,
    ) : VariableSignature()

    data class Inline(val replacement: IrExpr) : VariableSignature()
    data class Selection(val selection: ActionSelection?) : VariableSignature()
    data class InlineClass(val name: String, val scope: VariableScope, val slotName: String) : VariableSignature()
}