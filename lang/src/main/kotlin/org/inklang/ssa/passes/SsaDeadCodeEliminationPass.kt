package org.inklang.ssa.passes

import org.inklang.ssa.*

/**
 * SSA-based Dead Code Elimination pass.
 *
 * In SSA form, dead code elimination is simpler because each definition
 * has a single reaching use chain. This pass:
 * 1. Marks all values used in terminators, stores, and side-effecting instructions
 * 2. Propagates liveness backwards through the def-use chains
 * 3. Removes instructions that define unused values
 * 4. Removes phi functions for unused values
 * 5. Removes unreachable blocks
 */
class SsaDeadCodeEliminationPass : SsaOptPass {
    override val name = "SsaDeadCodeElimination"

    override fun run(ssaFunc: SsaFunction): SsaOptResult {
        // Find reachable blocks
        val reachable = findReachableBlocks(ssaFunc)

        // Find live values (used by reachable code)
        val liveValues = findLiveValues(ssaFunc, reachable)

        // Remove dead instructions and unreachable blocks
        val (newBlocks, changed) = removeDeadCode(ssaFunc, reachable, liveValues)

        if (!changed) {
            return SsaOptResult(ssaFunc, false)
        }

        val newFunc = SsaFunction(newBlocks, ssaFunc.constants, ssaFunc.entryBlock, ssaFunc.exitBlocks, ssaFunc.cfg)
        return SsaOptResult(newFunc, true)
    }

    /**
     * Find all reachable blocks using a worklist algorithm.
     */
    private fun findReachableBlocks(ssaFunc: SsaFunction): Set<Int> {
        val reachable = mutableSetOf<Int>()
        val worklist = mutableListOf(ssaFunc.entryBlock)

        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeLast()
            if (blockId in reachable) continue
            reachable.add(blockId)

            val block = ssaFunc.getBlock(blockId) ?: continue

            // Check for conditional branches that may be eliminated
            val lastInstr = block.instrs.lastOrNull()
            if (lastInstr is SsaInstr.JumpIfFalse) {
                // Both successors are potentially reachable
                for (succ in block.successors) {
                    if (succ !in reachable) {
                        worklist.add(succ)
                    }
                }
            } else {
                // All successors are reachable
                for (succ in block.successors) {
                    if (succ !in reachable) {
                        worklist.add(succ)
                    }
                }
            }
        }

        return reachable
    }

    /**
     * Find all live SSA values using backward liveness analysis.
     */
    private fun findLiveValues(ssaFunc: SsaFunction, reachable: Set<Int>): Set<SsaValue> {
        val live = mutableSetOf<SsaValue>()
        var changed = true

        while (changed) {
            changed = false

            for (blockId in reachable) {
                val block = ssaFunc.getBlock(blockId) ?: continue

                // Process instructions in reverse order
                for (instr in block.instrs.reversed()) {
                    // If instruction has side effects or is a terminator, mark its uses as live
                    if (hasSideEffects(instr) || isTerminator(instr)) {
                        for (used in instr.usedValues) {
                            if (live.add(used)) {
                                changed = true
                            }
                        }
                    }

                    // If defined value is live, mark uses as live
                    val defined = instr.definedValue
                    if (defined != null && defined in live) {
                        for (used in instr.usedValues) {
                            if (live.add(used)) {
                                changed = true
                            }
                        }
                    }
                }

                // Process phi functions
                for (phi in block.phiFunctions) {
                    if (phi.result in live) {
                        // Only mark operands from reachable predecessors as live
                        for ((predId, operand) in phi.operands) {
                            if (predId in reachable && live.add(operand)) {
                                changed = true
                            }
                        }
                    }
                }
            }
        }

        return live
    }

    /**
     * Check if an instruction has side effects.
     */
    private fun hasSideEffects(instr: SsaInstr): Boolean = when (instr) {
        is SsaInstr.StoreGlobal -> true
        is SsaInstr.SetIndex -> true
        is SsaInstr.SetField -> true
        is SsaInstr.Call -> true  // Conservative: assume all calls have side effects
        is SsaInstr.Return -> true
        is SsaInstr.LoadFunc -> true  // Function definitions should be kept
        is SsaInstr.LoadClass -> true // Class definitions should be kept
        else -> false
    }

    /**
     * Check if an instruction is a terminator.
     */
    private fun isTerminator(instr: SsaInstr): Boolean = when (instr) {
        is SsaInstr.Return, is SsaInstr.Jump, is SsaInstr.JumpIfFalse,
        is SsaInstr.Break, is SsaInstr.Next -> true
        else -> false
    }

    /**
     * Remove dead code (unreachable blocks and unused definitions).
     */
    private fun removeDeadCode(
        ssaFunc: SsaFunction,
        reachable: Set<Int>,
        liveValues: Set<SsaValue>
    ): Pair<List<SsaBlock>, Boolean> {
        var changed = false
        val newBlocks = mutableListOf<SsaBlock>()

        for (block in ssaFunc.blocks) {
            if (block.id !in reachable) {
                changed = true
                continue  // Remove unreachable block
            }

            val newBlock = SsaBlock(block.id, block.label)
            newBlock.predecessors.addAll(block.predecessors.intersect(reachable))
            newBlock.successors.addAll(block.successors.intersect(reachable))

            // Keep only live phi functions
            for (phi in block.phiFunctions) {
                if (phi.result in liveValues) {
                    newBlock.phiFunctions.add(phi)
                } else {
                    changed = true
                }
            }

            // Keep only instructions that are live or have side effects
            for (instr in block.instrs) {
                val defined = instr.definedValue
                val shouldKeep = hasSideEffects(instr) ||
                                 isTerminator(instr) ||
                                 defined == null ||
                                 defined in liveValues

                if (shouldKeep) {
                    newBlock.instrs.add(instr)
                } else {
                    changed = true
                }
            }

            newBlocks.add(newBlock)
        }

        return Pair(newBlocks, changed)
    }
}
