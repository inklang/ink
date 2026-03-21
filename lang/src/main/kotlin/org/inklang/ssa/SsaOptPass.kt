package org.inklang.ssa

import org.inklang.lang.Value

/**
 * Interface for SSA-based optimization passes.
 * SSA passes operate on SsaFunction instead of raw IR.
 */
interface SsaOptPass {
    val name: String

    /**
     * Run this optimization pass on an SSA function.
     * @param ssaFunc The SSA form function to optimize
     * @return The optimized SSA function and whether any changes were made
     */
    fun run(ssaFunc: SsaFunction): SsaOptResult

    /**
     * Whether this pass should re-run if it made changes.
     */
    fun shouldRerunIfChanged(): Boolean = true
}

/**
 * Result of an SSA optimization pass.
 */
data class SsaOptResult(
    val ssaFunc: SsaFunction,
    val changed: Boolean
)
