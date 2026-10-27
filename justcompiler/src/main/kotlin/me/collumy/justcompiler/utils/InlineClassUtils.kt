package me.collumy.justcompiler.utils

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.primaryConstructor

object InlineClassUtils {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun isInlineClass(clazz: IrClass?): Boolean {
        if (clazz == null) return false
        val anns = ConverterUtils.getAnnotations(clazz.annotations)
        return "InlineClass" in anns || "Inline" in anns
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun getInlineSlot(clazz: IrClass?): String? {
        if (clazz == null) return null
        val anns = ConverterUtils.getAnnotations(clazz.annotations)
        val explicitSlot = (anns["InlineClass"]?.get("slot") as? String)?.takeIf { it.isNotEmpty() }
            ?: (anns["Inline"]?.get("slot") as? String)?.takeIf { it.isNotEmpty() }
        if (explicitSlot != null) return explicitSlot

        val primaryConstructor = clazz.primaryConstructor ?: return null
        val firstParam = primaryConstructor.parameters.firstOrNull { it.name.asString() != "<this>" } ?: return null
        val rawName = firstParam.name.asString()
        return rawName.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun getInlineProperty(clazz: IrClass?): IrProperty? {
        if (clazz == null) return null
        val primaryConstructor = clazz.primaryConstructor ?: return null
        val firstParam = primaryConstructor.parameters.firstOrNull { it.name.asString() != "<this>" } ?: return null
        val paramName = firstParam.name.asString()
        return clazz.declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == paramName }
    }
}
