package me.collumy.justcompiler.backend.server

import me.collumy.justcompiler.backend.*

data class ServerCode(val handlers: List<ServerTrigger> = emptyList())

data class ServerTrigger(
    val type: TriggerType, val name: String, val body: List<ServerAction> = emptyList(),
    val args: List<ServerParameter> = emptyList(), val display: Display? = null
) {
    data class Display(
        val name: ServerValue.Text? = null, val lore: ServerValue.Text? = null,
        val icon: ServerValue.Item? = null, val hidden: Boolean = false
    )
}

data class ServerAction(
    val name: String, val args: List<ServerArgument> = emptyList(), val body: List<ServerAction>? = null,
    val selection: ActionSelection? = null, val isInverted: Boolean = false, val conditional: String? = null
)

data class ServerArgument(val name: String, val value: ServerValue)