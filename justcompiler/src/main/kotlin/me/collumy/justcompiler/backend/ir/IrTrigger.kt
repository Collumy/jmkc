package me.collumy.justcompiler.backend.ir

import me.collumy.justcompiler.backend.ServerType

sealed interface IrTrigger {

    data class Event(
        val names: List<String>,
        val body: List<IrStmt>
    ) : IrTrigger

    data class Function(
        val name: String,
        val body: List<IrStmt>,
        val params: List<IrParameter> = emptyList(),
        val returns: List<ServerType> = emptyList(),
        val display: IrTriggerDisplay? = null,
    ) : IrTrigger

    data class Process(
        val name: String,
        val body: List<IrStmt>,
        val params: List<IrParameter> = emptyList(),
        val display: IrTriggerDisplay? = null,
    ) : IrTrigger
}