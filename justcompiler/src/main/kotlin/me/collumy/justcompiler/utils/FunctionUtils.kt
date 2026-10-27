package me.collumy.justcompiler.utils

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.context.CompilerContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.visitors.*

object FunctionUtils {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun findCaptures(topFunction: IrSimpleFunction): Set<IrValueSymbol> {
        val captured = mutableSetOf<IrValueDeclaration>()

        // 1. Находим все локальные функции
        val localFunctions = mutableListOf<IrSimpleFunction>()
        topFunction.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (declaration != topFunction) {
                    localFunctions.add(declaration)
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitFunctionExpression(expression: IrFunctionExpression) {
                localFunctions.add(expression.function)
                expression.acceptChildrenVoid(this)
            }
        })

        if (localFunctions.isEmpty()) return emptySet()

        // 2. Для каждой вложенной функции ищем переменные, объявленные вне её скоупа
        for (localFn in localFunctions) {
            localFn.acceptChildrenVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                // Чтение переменной: a, x
                override fun visitGetValue(expression: IrGetValue) {
                    checkAndAdd(expression.symbol.owner, localFn)
                    super.visitGetValue(expression)
                }

                // Запись в переменную: a = 2, a += 1
                override fun visitSetValue(expression: IrSetValue) {
                    checkAndAdd(expression.symbol.owner, localFn)
                    super.visitSetValue(expression)
                }

                private fun checkAndAdd(owner: IrValueDeclaration, currentFn: IrSimpleFunction) {
                    if (owner !is IrVariable) return
                    if (!isDeclaredInside(owner, currentFn)) captured.add(owner)
                }
            })
        }

        return captured.map { it.symbol }.toSet()
    }

    private fun isDeclaredInside(variable: IrVariable, function: IrSimpleFunction): Boolean {
        var parent: IrDeclarationParent? = variable.parent
        while (parent != null) {
            if (parent == function) return true
            parent = (parent as? IrDeclaration)?.parent
        }
        return false
    }

    fun inline(function: IrFunction, callArgs: List<IrExpression?>): IrExpr? {
        val body = function.body ?: return IrExpr.Block()
        val id = CompilerContext.nextId()

        val prependedStmts = mutableListOf<IrStmt>()
        val argsMap = mutableMapOf<IrValueSymbol, IrExpr>()
        val lambdaMap = mutableMapOf<IrValueSymbol, IrFunction>()

        for (i in function.parameters.indices) {
            val param = function.parameters[i]
            val arg = callArgs.getOrNull(i) ?: continue
            var actualArg = arg
            while (actualArg is IrTypeOperatorCall) {
                actualArg = actualArg.argument
            }

            if (!param.isNoinline && actualArg is IrFunctionExpression) {
                lambdaMap[param.symbol] = actualArg.function
                CompilerContext.inlinedLambdas.add(actualArg.function)
            } else {
                val rawExpr = JustCompiler.convert(arg) ?: continue

                if (isTrivialExpression(rawExpr)) {
                    argsMap[param.symbol] = rawExpr
                } else {
                    val rawName = param.name.asString()
                    val pName = if (rawName == "<this>") "this" else rawName
                    val tempVarName = "__arg_${id}_$pName"
                    val tempVar = IrExpr.Variable(tempVarName, VariableScope.LINE)
                    prependedStmts += IrStmt.Assign(tempVar, rawExpr)
                    argsMap[param.symbol] = tempVar
                }
            }
        }

        return CompilerContext.withInline(function, id, argsMap, lambdaMap) {
            for (i in function.parameters.indices) {
                val param = function.parameters[i]
                if (param.symbol in argsMap || param.symbol in lambdaMap) continue

                val defaultExpr = param.defaultValue?.expression ?: continue
                val rawExpr = JustCompiler.convert(defaultExpr) ?: continue

                if (isTrivialExpression(rawExpr)) {
                    argsMap[param.symbol] = rawExpr
                    CompilerContext.registerArg(param.symbol, rawExpr)
                } else {
                    val rawName = param.name.asString()
                    val pName = if (rawName == "<this>") "this" else rawName
                    val tempVarName = "__arg_${id}_$pName"
                    val tempVar = IrExpr.Variable(tempVarName, VariableScope.LINE)
                    prependedStmts += IrStmt.Assign(tempVar, rawExpr)
                    argsMap[param.symbol] = tempVar
                    CompilerContext.registerArg(param.symbol, tempVar)
                }
            }

            when (body) {
                is IrExpressionBody -> {
                    val expr = JustCompiler.convert(body.expression) ?: return@withInline null
                    if (prependedStmts.isEmpty()) expr else IrExpr.Block(prependedStmts + IrStmt.Expr(expr))
                }

                is IrBlockBody -> {
                    val stmts = mutableListOf<IrStmt>()
                    stmts.addAll(prependedStmts)
                    var ret: IrExpr? = null

                    for (stmt in body.statements) {
                        if (stmt is IrReturn) {
                            if (!stmt.value.type.isUnit()) {
                                ret = JustCompiler.convert(stmt.value)
                            } else {
                                JustCompiler.convert(stmt.value)?.let { stmts += IrStmt.Expr(it) }
                            }
                            break
                        }
                        JustCompiler.convert(stmt)?.let { stmts += it }
                    }

                    if (ret == null && !function.returnType.isUnit() && stmts.isNotEmpty()) {
                        val last = stmts.last()
                        if (last is IrStmt.Expr) {
                            stmts.removeLast()
                            ret = last.expr
                        }
                    }

                    when {
                        stmts.isEmpty() && ret != null -> ret
                        ret != null -> IrExpr.Block(stmts + IrStmt.Expr(ret))
                        else -> if (stmts.isEmpty()) null else IrExpr.Block(stmts)
                    }
                }
                else -> null
            }
        }
    }

    private fun isTrivialExpression(expr: IrExpr): Boolean = when (expr) {
        is IrValue.Number, is IrValue.Text, is IrValue.Enum, is IrValue.Selection, is IrExpr.Variable -> true
        is IrValue.ListValue -> expr.values.all { isTrivialExpression(it) }
        is IrValue.MapValue -> expr.pairs.all { (k, v) -> isTrivialExpression(k) && isTrivialExpression(v) }
        else -> false
    }
}