package me.collumy.justcompiler.convert.decl

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.IrArgument
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.backend.ir.UnaryOp
import me.collumy.justcompiler.utils.ConverterUtils
import me.collumy.justcompiler.context.CompilerContext
import me.collumy.justcompiler.context.model.VariableSignature
import me.collumy.justcompiler.context.registry.VariableRegistry
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrCall
import kotlin.collections.contains
import kotlin.collections.set

object PropertyConverter {

    fun convert(decl: IrProperty): IrStmt? {
        val field = decl.backingField ?: return null
        var initializerExpr = field.initializer?.expression ?: return null
        val isIfNotExists = initializerExpr is IrCall && initializerExpr.symbol.owner.name.asString() in listOf("ifNotExists", "once")
        if (isIfNotExists) {
            val call = initializerExpr as IrCall
            initializerExpr = call.arguments.firstOrNull() ?: initializerExpr
        }

        val value = JustCompiler.convert(initializerExpr)
        if (value != null) {
            val resolved = VariableRegistry.resolve(decl.symbol) ?: return null
            val vari = when (resolved) {
                is VariableSignature.Property -> IrExpr.Variable(resolved.name, resolved.scope)
                else -> return null
            }
            val assignStmt = IrStmt.Assign(vari, value)
            return if (isIfNotExists) {
                IrStmt.Expr(
                    IrExpr.If(
                        branches = listOf(
                            IrExpr.If.Branch(
                                condition = IrExpr.Unary(
                                    UnaryOp.NOT,
                                    IrExpr.Action(
                                        name = "if_variable_exists",
                                        args = listOf(
                                            IrArgument("variable", vari),
                                            IrArgument("compare_type", IrValue.Enum("ANY"))
                                        )
                                    )
                                ),
                                body = IrExpr.Block(listOf(assignStmt))
                            )
                        )
                    )
                )
            } else {
                assignStmt
            }
        }

        return null
    }
}