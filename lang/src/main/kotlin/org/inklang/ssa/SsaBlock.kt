package org.inklang.ssa

import org.inklang.lang.IrLabel

/**
 * A basic block in SSA form.
 * Contains phi functions at the start, followed by regular SSA instructions.
 */
class SsaBlock(
    val id: Int,
    val label: IrLabel?,
    val phiFunctions: MutableList<PhiFunction> = mutableListOf(),
    val instrs: MutableList<SsaInstr> = mutableListOf(),
    // IR-only instructions (TryStart, TryEnd, etc.) with the SSA instruction index they precede
    val preservedIrInstrs: MutableList<Pair<Int, org.inklang.lang.IrInstr>> = mutableListOf()
) {
    val predecessors: MutableSet<Int> = mutableSetOf()
    val successors: MutableSet<Int> = mutableSetOf()

    /**
     * Get all instructions including phi functions.
     */
    fun allInstrs(): List<SsaInstr> {
        // Note: PhiFunctions are not SsaInstrs, so we return them separately
        // This is mainly for iteration purposes
        return instrs.toList()
    }

    /**
     * Get all values defined in this block (both phi results and regular definitions).
     */
    fun definedValues(): List<SsaValue> {
        val result = mutableListOf<SsaValue>()
        result.addAll(phiFunctions.map { it.result })
        for (instr in instrs) {
            instr.definedValue?.let { result.add(it) }
        }
        return result
    }

    /**
     * Get the terminal instruction if this block ends with one.
     */
    fun getTerminalInstr(): SsaInstr? {
        return instrs.lastOrNull()?.takeIf {
            it is SsaInstr.Return || it is SsaInstr.Jump || it is SsaInstr.JumpIfFalse ||
            it is SsaInstr.Break || it is SsaInstr.Next
        }
    }

    /**
     * Check if this block ends with a terminal instruction.
     */
    fun isTerminal(): Boolean {
        val last = instrs.lastOrNull() ?: return phiFunctions.isEmpty()
        return last is SsaInstr.Return ||
               last is SsaInstr.Break ||
               last is SsaInstr.Next ||
               (last is SsaInstr.Jump && instrs.none { it is SsaInstr.JumpIfFalse })
    }

    override fun toString(): String {
        val labelStr = label?.let { "L${it.id}" } ?: ""
        return "SsaBlock$id($labelStr: ${phiFunctions.size} phis, ${instrs.size} instrs)"
    }
}
