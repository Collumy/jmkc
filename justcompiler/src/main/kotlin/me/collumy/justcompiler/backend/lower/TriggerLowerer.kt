package me.collumy.justcompiler.backend.lower

import me.collumy.justcompiler.backend.ServerType
import me.collumy.justcompiler.backend.TriggerType
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.server.*
import me.collumy.justcompiler.backend.ir.IrParameter
import me.collumy.justcompiler.backend.ir.IrTrigger
import me.collumy.justcompiler.backend.ir.IrTriggerDisplay
import me.collumy.justcompiler.backend.lower.IrLowerer.delRet
import org.jetbrains.kotlin.ir.declarations.IrVariable

object TriggerLowerer {

    fun lower(trigger: IrTrigger): List<ServerTrigger> = when (trigger) {
        is IrTrigger.Event -> {
            val body = IrLowerer.lower(trigger.body)
            trigger.names.map { ServerTrigger(TriggerType.EVENT, it, body) }
        }
        is IrTrigger.Function -> ServerTrigger(
            type = TriggerType.FUNCTION,
            name = trigger.name,
            args =  assignParameterSlots(trigger.params, trigger.returns),
            display = trigger.display?.let { convertDisplay(it) },
            body = IrLowerer.lower(trigger.body).delRet()
        ).list
        is IrTrigger.Process -> ServerTrigger(
            type = TriggerType.PROCESS,
            name = trigger.name,
            args = assignParameterSlots(trigger.params),
            display = trigger.display?.let { convertDisplay(it) },
            body = IrLowerer.lower(trigger.body).delRet()
        ).list
    }

    private val ServerTrigger.list get() = listOf(this)

    private fun convertDisplay(display: IrTriggerDisplay): ServerTrigger.Display =
        ServerTrigger.Display(
            name = display.name?.let { IrLowerer.lower(it).result as ServerValue.Text },
            lore = display.lore?.let { IrLowerer.lower(it).result as ServerValue.Text },
            icon = display.icon?.let { IrLowerer.lower(it).result as ServerValue.Item },
            hidden = display.hidden
        )

    /**
     * Автоматическое назначение слотов в GUI меню аргументов
     */
    private fun assignParameterSlots(rawParams: List<IrParameter>, returns: List<ServerType> = emptyList()): List<ServerParameter> {
        val allParams = rawParams + List(returns.size) { index ->
            IrParameter.Singular("ret$index", ServerType.VARIABLE)
        }

        require(allParams.size <= 27) {
            "Превышен лимит параметров меню: максимум 27 (передано ${rawParams.size})"
        }

        val result = allParams.mapIndexed { index, param ->
            val tier = index / 9
            val col = index % 9

            val slot = tier * 18 + col
            val descSlot = slot + 9

            when (param) {
                is IrParameter.Singular -> {
                    ServerParameter.Singular(
                        name = param.name,
                        type = param.type,
                        slot = slot,
                        descSlot = descSlot,
                        display = param.display?.let { IrLowerer.lower(it).result as ServerValue.Text },
                        default = param.default?.let { IrLowerer.lower(it).result }
                    )
                }

                is IrParameter.Plural -> {
                    ServerParameter.Plural(
                        name = param.name,
                        type = param.type,
                        slots = listOf(slot),
                        descSlots = listOf(descSlot),
                        display = param.display?.let { IrLowerer.lower(it).result as ServerValue.Text },
                        ignoreEmpty = param.ignoreEmpty,
                        default = IrLowerer.lower(param.default).value
                    )
                }

                is IrParameter.Enum -> {
                    ServerParameter.Enum(
                        name = param.name,
                        slot = slot,
                        display = param.display?.let { IrLowerer.lower(it).result as ServerValue.Text },
                        default = param.default,
                        elements = param.elements.map { el ->
                            ServerParameter.Enum.Element(
                                name = el.name,
                                display = el.display?.let { IrLowerer.lower(it).result as ServerValue.Text },
                                icon = el.icon?.let { IrLowerer.lower(it).result as ServerValue.Item }
                            )
                        }
                    )
                }
            }
        }

        return result
    }
}