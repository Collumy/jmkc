package me.collumy.justcompiler

import me.collumy.justcompiler.backend.JustBackend
import me.collumy.justcompiler.backend.ir.IrExpr
import me.collumy.justcompiler.backend.ir.IrModule
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.ir.IrTrigger
import me.collumy.justcompiler.backend.ir.IrValue
import me.collumy.justcompiler.convert.expr.CallConverter
import me.collumy.justcompiler.convert.expr.ConstructorConverter
import me.collumy.justcompiler.convert.ValueConverter
import me.collumy.justcompiler.convert.decl.ClassConverter
import me.collumy.justcompiler.convert.decl.FunctionConverter
import me.collumy.justcompiler.convert.decl.PropertyConverter
import me.collumy.justcompiler.convert.expr.FlowConverter
import me.collumy.justcompiler.convert.expr.InstanceConverter
import me.collumy.justcompiler.convert.expr.VariableConverter
import me.collumy.justcompiler.utils.CollectionCalls
import me.collumy.justcompiler.utils.ConverterUtils
import me.collumy.justcompiler.utils.SelectionUtils
import me.collumy.justcompiler.context.CompilerContext
import me.collumy.justcompiler.context.collect.CompilerCollector
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isUnit

import me.collumy.justcompiler.utils.Diagnostics
import me.collumy.justcompiler.utils.JustCompilerCompilationException
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression

object JustCompiler {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun process(files: List<IrFile>, messageCollector: MessageCollector? = null) {
        Diagnostics.init(messageCollector)
        try {
            CompilerContext.reset()
            val triggers = mutableListOf<IrTrigger>()
            val stmts = mutableListOf<IrStmt>()

            CompilerContext.collect(files)
            for (file in files) {
                Diagnostics.currentFile = file
                for (decl in file.declarations) {
                    when (decl) {
                        is IrSimpleFunction -> triggers += FunctionConverter.convert(decl)
                        is IrProperty -> PropertyConverter.convert(decl)?.also { stmts += it }
                        is IrClass -> ClassConverter.convert(decl).also { (t, s) -> triggers += t; stmts += s }
                    }
                }
            }

            if (Diagnostics.hasErrors) return

            val module = IrModule(triggers, stmts)
            JustBackend.compileAndCopy(module)
        } catch (e: JustCompilerCompilationException) {
            return
        }
    }


    fun convert(stmt: IrStatement): IrStmt? = when (stmt) {
        is IrVariable -> VariableConverter.convert(stmt)
        is IrSetValue -> VariableConverter.convert(stmt)
        is IrSetField -> VariableConverter.convert(stmt)

        is IrLoop -> FlowConverter.convert(stmt)
        is IrBreak -> IrStmt.Break
        is IrContinue -> IrStmt.Continue
        is IrReturn -> {
            val labelName = (stmt.returnTargetSymbol as? org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol)?.let { CompilerContext.findLabel(it) }
            if (labelName != null) {
                IrStmt.Expr(IrExpr.Action("control_break_label", listOf(me.collumy.justcompiler.backend.ir.IrArgument("label", IrValue.Text(labelName)))))
            } else {
                val returnValue = stmt.value
                val valueExpr = if (returnValue.type.isUnit()) emptyList() else listOfNotNull(JustCompiler.convert(returnValue))
                IrStmt.Return(valueExpr)
            }
        }

        is IrSimpleFunction -> null
        is IrCall -> {
            val propertySet = VariableConverter.convertPropertySet(stmt)
            val instanceSet = InstanceConverter.set(stmt)
            val listMapSet = CollectionCalls.set(stmt)
            propertySet ?: listMapSet ?: instanceSet ?: IrStmt.Expr(convert(stmt as IrExpression) ?: return null)
        }
        is IrTypeOperatorCall -> convert(stmt.argument as IrStatement)
        is IrExpression -> IrStmt.Expr(convert(stmt) ?: return null)

        else -> error("Ошибка! неизвестный stmt $stmt")
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(expr: IrExpression): IrExpr? = when (expr) {
        is IrGetValue -> VariableConverter.convert(expr)
        is IrGetField -> VariableConverter.convert(expr)

        is IrConst -> ValueConverter.convert(expr)
        is IrStringConcatenation -> IrExpr.StringTemplate(expr.arguments.mapNotNull { convert(it) })

        is IrGetObjectValue -> {
            val clazz = expr.symbol.owner
            if (SelectionUtils.isSelectionClass(clazz)) {
                IrValue.Selection(null)
            } else null
        }
        is IrGetEnumValue -> {
            val entry = expr.symbol.owner
            val customValue = ConverterUtils.getAnnotations(entry.annotations)["SlotValue"]?.get("value") as? String
            IrValue.Text(customValue ?: entry.name.asString())
        }

        is IrCall -> CallConverter.convert(expr)
        is IrConstructorCall -> ConstructorConverter.convert(expr)
        is IrContainerExpression -> {
            if (expr.origin == IrStatementOrigin.FOR_LOOP) FlowConverter.convertForLoop(expr)
            else IrExpr.Block(convertStatements(expr.statements))
        }

        is IrWhen -> FlowConverter.convert(expr)
        is IrFunctionExpression -> IrValue.Text(ConverterUtils.getFullFunctionName(expr.function))

        is IrTypeOperatorCall -> FlowConverter.convert(expr)
        is IrVararg -> {
            val listElements = mutableListOf<IrExpr>()
            for (element in expr.elements) {
                when (element) {
                    is IrSpreadElement -> {
                        val converted = convert(element.expression)
                        if (converted is IrValue.ListValue) listElements.addAll(converted.values)
                        else if (converted != null) listElements.add(converted)
                    }
                    is IrExpression -> {
                        val converted = convert(element)
                        if (converted != null) listElements.add(converted)
                    }
                }
            }
            IrValue.ListValue(listElements)
        }
        is IrBreak -> IrExpr.Block(listOf(IrStmt.Break))
        is IrContinue -> IrExpr.Block(listOf(IrStmt.Continue))
        is IrReturn -> {
            val labelName = (expr.returnTargetSymbol as? org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol)?.let { CompilerContext.findLabel(it) }
            if (labelName != null) {
                IrExpr.Action("control_break_label", listOf(me.collumy.justcompiler.backend.ir.IrArgument("label", IrValue.Text(labelName))))
            } else {
                convert(expr.value)
            }
        }
        is IrErrorExpression -> null
        else -> error("Ошибка! неизвестный expr: $expr")
    }

    fun convertBody(stmt: IrStatement?): List<IrStmt> = when (stmt) {
        null -> emptyList()
        is IrContainerExpression -> {
            if (stmt.origin == IrStatementOrigin.FOR_LOOP) listOfNotNull(convert(stmt as IrStatement))
            else convertStatements(stmt.statements)
        }
        else -> listOfNotNull(convert(stmt))
    }

    fun convertBody(body: IrBody?): List<IrStmt> = when (body) {
        null -> emptyList()
        is IrBlockBody -> convertStatements(body.statements)
        is IrExpressionBody -> {
            val converted = JustCompiler.convert(body.expression)
            if (converted != null) listOf(IrStmt.Expr(converted)) else emptyList()
        }

        else -> emptyList()
    }

    fun convertStatements(statements: List<IrStatement>): List<IrStmt> =
        statements.mapNotNull { convert(it) }
}
