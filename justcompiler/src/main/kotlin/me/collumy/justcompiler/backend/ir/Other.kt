package me.collumy.justcompiler.backend.ir

import me.collumy.justcompiler.backend.ServerType

data class IrModule(val triggers: List<IrTrigger>, val stmts: List<IrStmt> = emptyList())
data class IrArgument(val name: String, val value: IrExpr)

data class IrTriggerDisplay(
    val name: IrValue.Text? = null, val lore: IrValue.Text? = null,
    val icon: IrValue.Item? = null, val hidden: Boolean = false
)

sealed interface IrParameter {
    data class Singular(
        val name: String, val type: ServerType = ServerType.ANY,
        val display: IrValue.Text? = null, val default: IrValue? = null,
    ) : IrParameter

    data class Plural(
        val name: String, val type: ServerType = ServerType.ANY,
        val display: IrValue.Text? = null, val ignoreEmpty: Boolean = true, val default: IrValue = IrValue.ListValue(),
    ) : IrParameter

    data class Enum(
        val name: String, val elements: List<Element> = emptyList(),
        val display: IrValue.Text? = null, val default: String? = null,
    ) : IrParameter {
        data class Element(val name: String, val display: IrValue.Text? = null, val icon: IrValue.Item? = null)
    }
}

enum class BinaryOp(val symbol: String) {
    PLUS("+"), MINUS("-"), MUL("*"), DIV("/"), REM("%"),
    EQ("=="), NEQ("!="), GT(">"), GTE(">="), LT("<"), LTE("<="),
    AND("&&"), OR("||")
}

enum class UnaryOp(val symbol: String) {
    NOT("!"), NEG("-")
}