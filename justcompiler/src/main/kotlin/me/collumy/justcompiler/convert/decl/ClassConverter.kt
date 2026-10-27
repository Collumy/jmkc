package me.collumy.justcompiler.convert.decl

import me.collumy.justcompiler.JustCompiler
import me.collumy.justcompiler.backend.ir.IrStmt
import me.collumy.justcompiler.backend.ir.IrTrigger
import me.collumy.justcompiler.context.collect.CompilerCollector.isInline
import me.collumy.justcompiler.context.collect.CompilerCollector.isTuple
import me.collumy.justcompiler.utils.ConverterUtils
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isObject

object ClassConverter {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun convert(clazz: IrClass): Pair<List<IrTrigger>, List<IrStmt>> {
        val triggers = mutableListOf<IrTrigger>()
        val stmts = mutableListOf<IrStmt>()
        val anns = ConverterUtils.getAnnotations(clazz.annotations)
        if (clazz.isInline || clazz.fileOrNull == null || clazz.kind == ClassKind.ENUM_CLASS || clazz.isTuple || "BuiltInValue" in anns) return triggers to stmts

        for (decl in clazz.declarations) {
            when (decl) {
                is IrProperty -> {
                    if (clazz.isObject) {
                        PropertyConverter.convert(decl)?.also { stmts += it }
                    }
                }
                is IrSimpleFunction -> {
                    // Конвертируем только пользовательские методы (исключая геттеры/сеттеры и fake overrides)
                    // Для data-классов методы copy и componentN инлайнятся на месте вызова, триггеры не создаются
                    val name = decl.name.asString()
                    val isDataClassComponentOrCopy = clazz.isData && (name == "copy" || name.matches(Regex("^component\\d+$")))
                    if (!decl.isFakeOverride && decl.correspondingPropertySymbol == null && !clazz.isTuple && !isDataClassComponentOrCopy) {
                        FunctionConverter.convert(decl).also { triggers += it }
                    }
                }
                is IrAnonymousInitializer -> {
                    if (clazz.isObject) {
                        stmts += JustCompiler.convertBody(decl.body)
                    }
                }
                is IrClass -> convert(decl).also { (t, s) -> triggers += t; stmts += s }
            }
        }

        return triggers to stmts
    }
}
