package org.inklang.ast

import org.inklang.lang.IrInstr
import org.inklang.lang.IrLabel

/**
 * Represents a basic block in the control flow graph.
 * A basic block is a sequence of instructions with:
 * - Single entry point (only the first instruction can be reached from outside)
 * - Single exit point (only the last instruction can transfer control elsewhere)
 */
data class BasicBlock(
    val id: Int,                           // unique identifier for this block
    val label: IrLabel?,                   // entry label (if block starts with a label)
    val instrs: List<IrInstr>,             // instructions in this block (excluding labels and terminal jumps)
    val startIndex: Int,                   // starting index in original IR list
    val endIndex: Int,                     // ending index in original IR (inclusive)
    val successors: MutableSet<Int> = mutableSetOf(),   // block IDs this block can jump to
    val predecessors: MutableSet<Int> = mutableSetOf()  // block IDs that can reach this block
) {
    /**
     * Check if this block ends with a terminal instruction (no fall-through)
     */
    fun isTerminal(): Boolean {
        val last = instrs.lastOrNull() ?: return false
        return last is IrInstr.Return ||
               last is IrInstr.Break ||
               last is IrInstr.Next ||
               (last is IrInstr.Jump && instrs.none { it is IrInstr.JumpIfFalse })
    }

    /**
     * Get the terminal instruction if this block ends with one
     */
    fun getTerminalInstr(): IrInstr? {
        return instrs.lastOrNull()?.takeIf {
            it is IrInstr.Return || it is IrInstr.Jump || it is IrInstr.JumpIfFalse ||
            it is IrInstr.Break || it is IrInstr.Next
        }
    }

    override fun toString(): String {
        val labelStr = label?.let { "L${it.id}" } ?: ""
        return "Block$id($labelStr: ${instrs.size} instrs, succ=${successors}, pred=${predecessors})"
    }
}
