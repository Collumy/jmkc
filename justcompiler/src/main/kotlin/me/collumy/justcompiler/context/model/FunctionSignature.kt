package me.collumy.justcompiler.context.model

import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.ir.IrArgument
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter

sealed class FunctionSignature {

    data class Action(
        val id: String,
        val parameters: List<IrValueParameter>,
        val returns: List<String> = emptyList(),
        val thisArg: List<String> = emptyList(),
        val hasSelectionReceiver: Boolean = false,
        val selectionParam: IrValueParameter? = null,
        val lambdaParam: IrValueParameter? = null,
        val extraArgs: List<IrArgument> = emptyList(),
        val paramSlotMap: Map<String, String> = emptyMap()
    ) : FunctionSignature()

    data class Inline(val function: IrSimpleFunction) : FunctionSignature()

    data class BuiltIn(val id: String) : FunctionSignature()

    data class Function(
        val name: String,
        val parameters: List<IrValueParameter>,
        val returns: List<ServerType> = emptyList(),
        val isProcess: Boolean = false,
        val hasInstanceReceiver: Boolean = false
    ) : FunctionSignature() {
        val returnsCount: Int get() = returns.size
    }

    data object Event : FunctionSignature()

    data object Ignored : FunctionSignature()
}
