package me.collumy.justcompiler.backend.ir

import me.collumy.justcompiler.backend.ServerType

sealed interface IrStmt {
    data class Expr(val expr: IrExpr) : IrStmt
    data class Assign(val target: IrExpr.Variable, val value: IrExpr) : IrStmt
    data class MultiAssign(val targets: List<IrExpr.Variable>, val value: IrExpr) : IrStmt

    data class While(val condition: IrExpr, val body: List<IrStmt>, val isDoWhile: Boolean = false) : IrStmt

    data object Break : IrStmt
    data object Continue : IrStmt
    data class Return(val values: List<IrExpr> = emptyList()) : IrStmt

    // Только для Location, Vector, Sound, Particle, Potion, Item, Map (как классы)
    data class PropertySet(val target: IrExpr, val type: ServerType, val property: String, val value: IrExpr) : IrStmt
    data class CollectionSet(val isMap: Boolean, val collection: IrExpr, val key: IrExpr, val value: IrExpr) : IrStmt
}