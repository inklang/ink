package org.inklang.ssa

import org.inklang.lang.IrInstr
import org.inklang.lang.IrLabel
import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.lang.DefaultValueInfo
import org.inklang.lang.MethodInfo

/**
 * SSA-form instruction.
 * Similar to IrInstr but uses SsaValue instead of Int for registers.
 * Each instruction defines exactly one SsaValue (or none for terminators).
 */
sealed class SsaInstr {
    /**
     * The SSA value defined by this instruction, or null if none.
     */
    abstract val definedValue: SsaValue?

    /**
     * All SSA values used (read) by this instruction.
     */
    abstract val usedValues: List<SsaValue>

    data class LoadImm(override val definedValue: SsaValue, val constIndex: Int) : SsaInstr() {
        override val usedValues: List<SsaValue> = emptyList()
    }

    data class LoadGlobal(override val definedValue: SsaValue, val name: String) : SsaInstr() {
        override val usedValues: List<SsaValue> = emptyList()
    }

    data class StoreGlobal(val name: String, val src: SsaValue) : SsaInstr() {
        override val definedValue: SsaValue? = null
        override val usedValues: List<SsaValue> = listOf(src)
    }

    data class BinaryOp(
        override val definedValue: SsaValue,
        val op: TokenType,
        val src1: SsaValue,
        val src2: SsaValue
    ) : SsaInstr() {
        override val usedValues: List<SsaValue> = listOf(src1, src2)
    }

    data class UnaryOp(
        override val definedValue: SsaValue,
        val op: TokenType,
        val src: SsaValue
    ) : SsaInstr() {
        override val usedValues: List<SsaValue> = listOf(src)
    }

    data class Jump(val target: IrLabel) : SsaInstr() {
        override val definedValue: SsaValue? = null
        override val usedValues: List<SsaValue> = emptyList()
    }

    data class JumpIfFalse(val src: SsaValue, val target: IrLabel) : SsaInstr() {
        override val definedValue: SsaValue? = null
        override val usedValues: List<SsaValue> = listOf(src)
    }

    data class Label(val label: IrLabel) : SsaInstr() {
        override val definedValue: SsaValue? = null
        override val usedValues: List<SsaValue> = emptyList()
    }

    data class LoadFunc(
        override val definedValue: SsaValue,
        val name: String,
        val arity: Int,
        val instrs: List<IrInstr>,  // Keep nested functions in IR form
        val constants: List<Value>,
        val defaultValues: List<DefaultValueInfo?> = emptyList()
    ) : SsaInstr() {
        override val usedValues: List<SsaValue> = emptyList()
    }

    data class Call(
        override val definedValue: SsaValue,
        val func: SsaValue,
        val args: List<SsaValue>
    ) : SsaInstr() {
        override val usedValues: List<SsaValue> = listOf(func) + args
    }

    data class Return(val src: SsaValue) : SsaInstr() {
        override val definedValue: SsaValue? = null
        override val usedValues: List<SsaValue> = listOf(src)
    }

    data class Move(override val definedValue: SsaValue, val src: SsaValue) : SsaInstr() {
        override val usedValues: List<SsaValue> = listOf(src)
    }

    data class GetIndex(
        override val definedValue: SsaValue,
        val obj: SsaValue,
        val index: SsaValue
    ) : SsaInstr() {
        override val usedValues: List<SsaValue> = listOf(obj, index)
    }

    data class SetIndex(val obj: SsaValue, val index: SsaValue, val src: SsaValue) : SsaInstr() {
        override val definedValue: SsaValue? = null
        override val usedValues: List<SsaValue> = listOf(obj, index, src)
    }

    data class NewArray(override val definedValue: SsaValue, val elements: List<SsaValue>) : SsaInstr() {
        override val usedValues: List<SsaValue> = elements
    }

    data class GetField(
        override val definedValue: SsaValue,
        val obj: SsaValue,
        val name: String
    ) : SsaInstr() {
        override val usedValues: List<SsaValue> = listOf(obj)
    }

    data class SetField(val obj: SsaValue, val name: String, val src: SsaValue) : SsaInstr() {
        override val definedValue: SsaValue? = null
        override val usedValues: List<SsaValue> = listOf(obj, src)
    }

    data class NewInstance(
        override val definedValue: SsaValue,
        val classReg: SsaValue,
        val args: List<SsaValue>
    ) : SsaInstr() {
        override val usedValues: List<SsaValue> = listOf(classReg) + args
    }

    data class IsType(
        override val definedValue: SsaValue,
        val src: SsaValue,
        val typeName: String
    ) : SsaInstr() {
        override val usedValues: List<SsaValue> = listOf(src)
    }

    data class LoadClass(
        override val definedValue: SsaValue,
        val name: String,
        val superClass: String?,
        val methods: Map<String, MethodInfo>
    ) : SsaInstr() {
        override val usedValues: List<SsaValue> = emptyList()
    }

    object Break : SsaInstr() {
        override val definedValue: SsaValue? = null
        override val usedValues: List<SsaValue> = emptyList()
    }

    object Next : SsaInstr() {
        override val definedValue: SsaValue? = null
        override val usedValues: List<SsaValue> = emptyList()
    }
}

/**
 * Phi function for merging values at control flow joins.
 * result = phi(src1 from block1, src2 from block2, ...)
 *
 * In SSA form, phi functions appear at the start of blocks that have
 * multiple predecessors. Each phi selects the value from the predecessor
 * that was actually taken at runtime.
 */
data class PhiFunction(
    val result: SsaValue,
    val operands: Map<Int, SsaValue>  // predecessor block ID -> value from that predecessor
) {
    val definedValue: SsaValue get() = result
    val usedValues: List<SsaValue> get() = operands.values.toList()

    /**
     * Get the operand for a specific predecessor block.
     */
    fun operandFor(blockId: Int): SsaValue? = operands[blockId]

    /**
     * Create a new phi with an operand replaced.
     */
    fun withOperand(blockId: Int, value: SsaValue): PhiFunction {
        return PhiFunction(result, operands + (blockId to value))
    }

    override fun toString(): String {
        val operandStr = operands.entries.joinToString(", ") { (blockId, value) ->
            "$value from B$blockId"
        }
        return "$result = phi($operandStr)"
    }
}
