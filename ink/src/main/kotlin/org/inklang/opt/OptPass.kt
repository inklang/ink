package org.inklang.opt

import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.IrInstr
import org.inklang.lang.Value

/**
 * Interface for IR optimization passes.
 * Each pass takes the current IR and CFG, returns optimized IR.
 */
interface OptPass {
    val name: String

    /**
     * Run this optimization pass.
     * @param instrs Current IR instructions
     * @param cfg Control flow graph for the current IR
     * @param constants Current constant pool (may be modified)
     * @return Pair of (optimized instructions, modified constants)
     */
    fun run(
        instrs: List<IrInstr>,
        cfg: ControlFlowGraph,
        constants: List<Value>
    ): OptResult

    /**
     * Whether this pass should re-run if it made changes.
     * Most passes should re-run until fixed point.
     */
    fun shouldRerunIfChanged(): Boolean = true
}

/**
 * Result of an optimization pass.
 */
data class OptResult(
    val instrs: List<IrInstr>,
    val constants: List<Value>,
    val changed: Boolean
)
