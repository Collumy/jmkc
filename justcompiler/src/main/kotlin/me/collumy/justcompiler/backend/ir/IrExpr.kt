package me.collumy.justcompiler.backend.ir

import me.collumy.justcompiler.backend.*

sealed interface IrExpr {
    data class Variable(val name: String, val scope: VariableScope = VariableScope.LINE) : IrExpr
    data class StringTemplate(val parts: List<IrExpr> = emptyList()) : IrExpr
    data class Block(val body: List<IrStmt> = emptyList()) : IrExpr
    data class Tuple(val elements: List<IrExpr>) : IrExpr

    data class Binary(val left: IrExpr, val op: BinaryOp, val right: IrExpr) : IrExpr
    data class Unary(val op: UnaryOp, val expr: IrExpr) : IrExpr
    data class Update(val target: Variable, val isIncrement: Boolean = true, val post: Boolean = true) : IrExpr

    data class Action(
        val name: String, val args: List<IrArgument> = emptyList(), val returns: List<String> = emptyList(),
        val selection: ActionSelection? = null, val body: List<IrStmt>? = null
    ) : IrExpr
    data class FunctionCall(val callee: IrExpr, val args: List<IrArgument> = emptyList(), val returnsCount: Int = 0) : IrExpr
    data class StartProcess(val callee: IrExpr, val args: List<IrArgument> = emptyList(), val targetMode: String? = null, val localVariablesMode: String? = null) : IrExpr
    

    data class If(val branches: List<Branch>, val elseExpr: IrExpr? = null) : IrExpr {
        data class Branch(val condition: IrExpr, val body: IrExpr) }
    data class Is(val target: IrExpr, val type: ServerType, val isInverted: Boolean = false, val userClass: String? = null) : IrExpr

    data class PropertyGet(val target: IrExpr, val type: ServerType, val property: String) : IrExpr
    data class CollectionGet(val isMap: Boolean, val collection: IrExpr, val key: IrExpr, val isInline: Boolean = false) : IrExpr
}