package me.collumy.justcompiler.backend.lower

import me.collumy.justcompiler.backend.VariableScope
import me.collumy.justcompiler.backend.server.*
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.lower.value.CollectionLowerer.lm

object ExprLowerer {

    fun lower(binary: IrExpr.Binary): Lowered {
        val pre = mutableListOf<ServerAction>()

        val left = IrLowerer.lower(binary.left.lm)
        val right = IrLowerer.lower(binary.right.lm)
        pre += left.pre
        pre += right.pre

        val mathExpr = "${formatOperand(left.value)} ${binary.op.symbol} ${formatOperand(right.value)}"
        return Lowered(ServerValue.Number(0.0, mathExpr), pre)
    }

    fun lower(unary: IrExpr.Unary): Lowered {
        val pre = mutableListOf<ServerAction>()

        val inner = IrLowerer.lower(unary.expr.lm)
        pre += inner.pre

        val mathExpr = "${unary.op.symbol}${formatOperand(inner.value)}"
        return Lowered(ServerValue.Number(0.0, math = mathExpr), pre)
    }

    private fun formatOperand(value: ServerValue): String = when (value) {
        is ServerValue.Number -> if (value.math != null) "(${value.math})" else value.value.toString()
        is ServerValue.Variable -> formatVariable(value.name, value.scope)
        is ServerValue.GameValue -> "%val(${value.value})"
        else -> error("Ошибка, неизвестный операнд в formatOperand $value")
    }

    private fun formatVariable(name: String, scope: VariableScope): String {
        val scopeText = if (scope == VariableScope.GAME) "%var" else "%var_${scope.id}"
        return "$scopeText(${name})"
    }

    fun lower(update: IrExpr.Update): Lowered {
        val targetLowered = IrLowerer.lower(update.target)
        val vari = targetLowered.result as ServerValue.Variable

        val action = ServerAction(
            name = if (update.isIncrement) "set_variable_increment" else "set_variable_decrement",
            args = listOf(
                ServerArgument("variable", vari),
                ServerArgument("number", ServerValue.Number(1.0))
            )
        )

        return if (update.post) {
            val temp = IrLowerer.temp()
            val saveOldAction = ServerAction("set_variable_value", listOf(
                ServerArgument("variable", temp),
                ServerArgument("value", vari)
            ))

            Lowered(temp, targetLowered.pre + saveOldAction + action)
        } else {
            Lowered(vari, targetLowered.pre + action)
        }
    }

    fun lower(template: IrExpr.StringTemplate): Lowered {
        val pre = mutableListOf<ServerAction>()
        val sb = StringBuilder()

        for (part in template.parts) {
            val lowered = IrLowerer.lower(part.lm)
            pre += lowered.pre

            val text = when (val value = lowered.value) {
                is ServerValue.Text -> value.value
                is ServerValue.Number -> if (value.math != null) "%math(${value.math})" else value.value.toString()
                is ServerValue.Variable -> formatVariable(value.name, value.scope)
                is ServerValue.GameValue -> "%val(${value.value})"
                else -> {
                    val temp = IrLowerer.temp()
                    pre += ServerAction("set_variable_value", listOf(
                        ServerArgument("variable", temp),
                        ServerArgument("value", value)
                    ))
                    "%var_line(${temp.name})"
                }
            }

            sb.append(text)
        }

        return Lowered(ServerValue.Text(sb.toString()), pre)
    }

    fun lower(action: IrExpr.Action, targets: List<IrExpr.Variable> = emptyList()): Lowered {
        // УСЛОВИЕ
        if (action.name.startsWith("if_")) {
            val stmts = mutableListOf<ServerAction>()

            val targetVar = targets.firstOrNull() ?: run {
                val temp = IrLowerer.temp()
                IrExpr.Variable(temp.name, temp.scope)
            }
            val serverTarget = ServerValue.Variable(targetVar.name, targetVar.scope)

            val inputArgs = action.args.map { (name, value) ->
                val lowered = IrLowerer.lower(value)
                stmts += lowered.pre
                ServerArgument(name, lowered.value)
            }

            stmts += ServerAction("set_variable_value", listOf(
                ServerArgument("variable", serverTarget),
                ServerArgument("value", ServerValue.Number(0.0))
            ))
            stmts += ServerAction(action.name, inputArgs, selection = action.selection, body = listOf(
                ServerAction("set_variable_value", listOf(
                    ServerArgument("variable", serverTarget),
                    ServerArgument("value", ServerValue.Number(1.0))
                ))
            ))

            return Lowered(serverTarget, stmts)
        }

        // СЕЛЕКТ С УСЛОВИЕМ
        if ("select_" in action.name && "_conditional" in action.name) {
            val condExpr = when {
                action.args.size == 1 -> action.args[0].value
                !action.body.isNullOrEmpty() -> {
                    when (val last = action.body.last()) {
                        is IrStmt.Expr -> last.expr
                        is IrStmt.Return -> last.values.firstOrNull() ?: error("Ошибка, в селекторе ${action.name} в return нет значения")
                        else -> error("Ошибка, в селекторе ${action.name} в лямбде последний элемент не expr или return ($last)")
                    }
                }
                else -> error("Ошибка, в селекторе ${action.name} нет аргумента или лямбды с условием")
            }
            val stmts = mutableListOf<ServerAction>()
            if (!action.body.isNullOrEmpty() && action.body.size > 1) {
                stmts += action.body.dropLast(1).flatMap { IrLowerer.lower(it) }
            }
            val cond = ConditionLowerer.lower(condExpr)
            stmts += cond.pre

            stmts += ServerAction(action.name, cond.args,
                conditional = cond.action, isInverted = cond.isInverted, selection = cond.selection
            )

            return Lowered(null, stmts)
        }

        // ОБЫЧНОЕ ДЕЙСТВИЕ
        val stmts = mutableListOf<ServerAction>()
        if (targets.size > action.returns.size)
            error("Ошибка, в действие ${action.name} пришло больше переменных (${targets.size}), чем ожидалось (${action.returns.size})")

        // Таргеты
        val actualTargets = when {
            targets.size == action.returns.size -> targets.map { ServerValue.Variable(it.name, it.scope) }
            targets.isNotEmpty() -> {
                val list = targets.map { ServerValue.Variable(it.name, it.scope) }.toMutableList()
                while (list.size < action.returns.size) {
                    list += IrLowerer.temp()
                }
                list
            }
            action.returns.isNotEmpty() -> action.returns.map { IrLowerer.temp() }
            else -> emptyList()
        }

        // Аргументы
        val loweredArgs = action.args.map { (name, value) ->
            val lowered = IrLowerer.lower(value)
            stmts += lowered.pre
            ServerArgument(name, lowered.value)
        }.toMutableList()

        for ((index, returnSlot) in action.returns.withIndex()) {
            val targetVar = actualTargets.getOrNull(index) ?: continue
            val existingIndex = loweredArgs.indexOfFirst { it.name == returnSlot }
            if (existingIndex != -1) {
                if (targets.isNotEmpty()) {
                    loweredArgs[existingIndex] = ServerArgument(returnSlot, targetVar)
                }
            } else {
                loweredArgs += ServerArgument(returnSlot, targetVar)
            }
        }

        // Действие
        val body = action.body?.flatMap { IrLowerer.lower(it) }
        val serverAction = ServerAction(action.name, loweredArgs, body, action.selection)

        val returnTarget = if (targets.isNotEmpty()) {
            actualTargets.firstOrNull()
        } else {
            val firstReturnSlot = action.returns.firstOrNull()
            val existingInArgs = action.args.find { it.name == firstReturnSlot }?.value as? IrExpr.Variable
            existingInArgs?.let { ServerValue.Variable(it.name, it.scope) } ?: actualTargets.firstOrNull()
        }

        return Lowered(returnTarget, stmts + serverAction)
    }

    fun lower(call: IrExpr.FunctionCall, targets: List<IrExpr.Variable> = emptyList()): Lowered {
        val stmts = mutableListOf<ServerAction>()
        val returnsCount = call.returnsCount
        if (targets.size > returnsCount)
            error("Ошибка: функция ожидает не более $returnsCount возвращаемых переменных, но передано ${targets.size}")

        // Таргеты
        val actualTargets = when {
            targets.isNotEmpty() -> targets.map { ServerValue.Variable(it.name, it.scope) }
            returnsCount > 0 -> listOf(IrLowerer.temp())
            else -> emptyList()
        }

        // Название
        val callee = IrLowerer.lower(call.callee)
        stmts += callee.pre

        // Аргументы
        val args = call.args.map { arg ->
            val lowered = IrLowerer.lower(arg.value)
            stmts += lowered.pre
            ServerValue.Text(arg.name) to lowered.value
        } + actualTargets.mapIndexed { index, targetVar ->
            ServerValue.Text("ret$index") to targetVar
        }

        val call = ServerAction("call_function", listOf(
            ServerArgument("function_name", callee.value),
            ServerArgument("args", ServerValue.MapValue(args))
        ))

        return Lowered(actualTargets.firstOrNull(), stmts + call)
    }

    fun lower(expr: IrExpr.StartProcess): Lowered {
        val stmts = mutableListOf<ServerAction>()

        val callee = IrLowerer.lower(expr.callee)
        stmts += callee.pre

        val loweredArgs = expr.args.map { arg ->
            val lowered = IrLowerer.lower(arg.value)
            stmts += lowered.pre
            ServerValue.Text(arg.name) to lowered.value
        }

        val args = mutableListOf(
            ServerArgument("process_name", callee.value),
            ServerArgument("args", ServerValue.MapValue(loweredArgs))
        )
        if (expr.localVariablesMode != null)
            args += ServerArgument("local_variables_mode", ServerValue.Enum(expr.localVariablesMode))

        if (expr.targetMode != null)
            args += ServerArgument("target_mode", ServerValue.Enum(expr.targetMode))

        val action = ServerAction("start_process", args)
        return Lowered(null, stmts + action)
    }

    fun lower(ifExpr: IrExpr.If, targets: List<IrExpr.Variable> = emptyList()): Lowered {
        if (ifExpr.branches.isEmpty()) {
            val elseStmts = ifExpr.elseExpr?.let { IrLowerer.lower(it).pre } ?: emptyList<ServerAction>()
            val targetVar = targets.firstOrNull()?.let { ServerValue.Variable(it.name, it.scope) }
            return Lowered(targetVar, elseStmts)
        }

        val stmts = mutableListOf<ServerAction>()

        // Если targets переданы явно ИЛИ есть elseExpr — значит if используется как выражение
        val isExpression = targets.isNotEmpty() || ifExpr.elseExpr != null

        // Определяем таргеты: либо явные, либо синтезируем temp() для выражения
        val actualTargets = when {
            targets.isNotEmpty() -> targets
            isExpression -> {
                val temp = IrLowerer.temp()
                listOf(IrExpr.Variable(temp.name, temp.scope))
            }
            else -> emptyList()
        }

        // Понижение ветки: если это выражение — проталкиваем присваивание в таргеты
        fun lowerBranch(expr: IrExpr): List<ServerAction> = if (actualTargets.isNotEmpty()) {
            val stmt = if (actualTargets.size == 1) {
                IrStmt.Assign(actualTargets[0], expr)
            } else {
                IrStmt.MultiAssign(actualTargets, expr)
            }
            IrLowerer.lower(stmt)
        } else {
            when (expr) {
                is IrExpr.Block -> expr.body.flatMap { IrLowerer.lower(it) }
                else -> IrLowerer.lower(IrStmt.Expr(expr))
            }
        }

        // 1. Первая ветка (if)
        val firstBranch = ifExpr.branches.first()
        val firstCond = ConditionLowerer.lower(firstBranch.condition)
        stmts += firstCond.pre

        val firstBody = lowerBranch(firstBranch.body)
        stmts += ServerAction(
            firstCond.action,
            firstCond.args,
            firstBody,
            isInverted = firstCond.isInverted,
            selection = firstCond.selection,
        )

        // 2. Последующие ветки (else if / elif)
        for (i in 1 until ifExpr.branches.size) {
            val branch = ifExpr.branches[i]
            val cond = ConditionLowerer.lower(branch.condition)
            stmts += cond.pre

            val branchBody = lowerBranch(branch.body)
            stmts += ServerAction(
                "else",
                cond.args,
                branchBody,
                conditional = cond.action,
                isInverted = cond.isInverted,
                selection = cond.selection
            )
        }

        // 3. Финальный else
        if (ifExpr.elseExpr != null) {
            val elseBody = lowerBranch(ifExpr.elseExpr)
            stmts += ServerAction("else", body = elseBody)
        }

        // Возвращаем переменную-результат, если if был выражением
        val resultVar = if (actualTargets.isNotEmpty()) ServerValue.Variable(actualTargets[0].name, actualTargets[0].scope) else null
        return Lowered(resultVar, stmts)
    }

    fun lower(isExpr: IrExpr.Is, targets: List<IrExpr.Variable> = emptyList()): Lowered {
        val stmts = mutableListOf<ServerAction>()

        val targetVar = targets.firstOrNull() ?: run {
            val temp = IrLowerer.temp()
            IrExpr.Variable(temp.name, temp.scope)
        }

        val serverTarget = ServerValue.Variable(targetVar.name, targetVar.scope)
        val cond = ConditionLowerer.lower(isExpr)
        stmts += cond.pre

        stmts += ServerAction("set_variable_value", listOf(
            ServerArgument("variable", serverTarget),
            ServerArgument("value", ServerValue.Number(0.0))
        ))
        stmts += ServerAction(cond.action, cond.args, isInverted = isExpr.isInverted,
            selection = cond.selection, body = listOf(
                ServerAction("set_variable_value", listOf(
                    ServerArgument("variable", serverTarget),
                    ServerArgument("value", ServerValue.Number(1.0))
                ))
            )
        )

        return Lowered(serverTarget, stmts)
    }

    fun lower(block: IrExpr.Block): Lowered {
        if (block.body.isEmpty()) return Lowered(null, emptyList())
        val stmts = mutableListOf<ServerAction>()

        val body = block.body.toMutableList()
        val last = body.removeLast()

        stmts += IrLowerer.lower(body, false)
        return if (last is IrStmt.Expr) {
            val lowered = IrLowerer.lower(last.expr)
            Lowered(lowered.result, stmts + lowered.pre)
        } else {
            Lowered(null, stmts + IrLowerer.lower(last))
        }
    }
}