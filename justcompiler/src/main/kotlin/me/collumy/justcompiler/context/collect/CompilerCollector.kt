package me.collumy.justcompiler.context.collect

import me.collumy.justcompiler.utils.ConverterUtils
import me.collumy.justcompiler.context.registry.FunctionRegistry
import me.collumy.justcompiler.context.registry.VariableRegistry
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.isObject

import me.collumy.justcompiler.utils.InlineClassUtils

object CompilerCollector {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun collect(file: IrFile) {
        for (declaration in file.declarations) {
            when (declaration) {
                is IrProperty -> VariableRegistry.registerProperty(declaration)
                is IrClass -> indexClass(declaration)
                is IrSimpleFunction -> FunctionRegistry.register(declaration)
            }
        }
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun indexClass(clazz: IrClass, prefix: String = "") {
        if (clazz.kind == ClassKind.ENUM_CLASS || clazz.isTuple) return
        val name = prefix + clazz.name.asString() + "."

        for (decl in clazz.declarations) {
            when (decl) {
                is IrProperty -> when {
                    clazz.isInline -> VariableRegistry.registerVirtualProperty(decl)
                    clazz.isObject -> VariableRegistry.registerProperty(decl, name)
                }
                is IrSimpleFunction -> FunctionRegistry.register(decl)
                is IrClass -> indexClass(decl, name)
            }
        }
    }

    val IrClass.isInlineClass: Boolean get() = InlineClassUtils.isInlineClass(this)
    val IrClass.isSelection: Boolean get() = "Selection" in ConverterUtils.getAnnotations(this.annotations)
    val IrClass.isEvent: Boolean get() = "Event" in ConverterUtils.getAnnotations(this.annotations)

    val IrClass.isInline: Boolean get() {
        return isEvent || isSelection || isInlineClass
    }

    val IrClass.isTuple: Boolean get() = ConverterUtils.isTuple(this)

    val IrClass?.isInlineOrObject: Boolean get() = this != null && (this.isObject || this.isInline)

}