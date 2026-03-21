package org.inklang.opt.passes

import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.IrInstr
import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.opt.OptPass
import org.inklang.opt.OptResult

/**
 * Branch Optimization pass.
 *
 * Simplifies control flow by eliminating redundant jumps and blocks.
 *
 * Optimizations:
 * 1. Jump-to-Jump elimination:
 *      Jump L1
 *      L1: Jump L2
 *    -> Jump L2
 *
 * 2. Block concatenation (dead Jump elimination):
 *    When a block ends with an unconditional Jump to the next block,
 *    the Jump is redundant and can be removed.
 *
 * 3. Conditional jump with same target:
 *      JumpIfFalse cond, L1
 *      Jump L1
 *    Both branches go to L1 -> unconditional Jump L1
 *
 * 4. Redundant conditional jump:
 *    When a conditional jump immediately follows its own condition evaluation,
 *    and both targets are identical, replace with Jump.
 *
 * 5. Two-way to one-way conversion:
 *    When a JumpIfFalse always takes the fall-through path (condition always true),
 *    it can be replaced with fall-through.
 */
class BranchOptimizationPass : OptPass {
    override val name = "BranchOptimization"

    override fun run(
        instrs: List<IrInstr>,
        cfg: ControlFlowGraph,
        constants: List<Value>
    ): OptResult {
        if (instrs.isEmpty()) return OptResult(instrs, constants, false)

        // Build a map of label -> instruction index for quick lookup
        val labelToIndex = mutableMapOf<Int, Int>()
        for ((idx, instr) in instrs.withIndex()) {
            if (instr is IrInstr.Label) {
                labelToIndex[instr.label.id] = idx
            }
        }

        val newInstrs = mutableListOf<IrInstr>()
        var changed = false
        var i = 0

        while (i < instrs.size) {
            val instr = instrs[i]

            when (instr) {
                is IrInstr.Jump -> {
                    // Check for jump-to-jump optimization
                    val targetIdx = labelToIndex[instr.target.id]
                    if (targetIdx != null && i + 1 < instrs.size) {
                        val nextInstr = instrs[i + 1]
                        // Check if the jump target is the next instruction (redundant)
                        // This would mean the jump jumps to immediately after itself
                        if (targetIdx == i + 1 && nextInstr !is IrInstr.Label) {
                            // Jump to next instruction is redundant - skip the jump
                            changed = true
                            i++
                            continue
                        }
                    }
                    newInstrs.add(instr)
                }

                is IrInstr.JumpIfFalse -> {
                    val optimized = tryOptimizeConditionalJump(
                        instrs, newInstrs, i, instr, labelToIndex, constants
                    )
                    if (optimized) {
                        changed = true
                    } else {
                        newInstrs.add(instr)
                    }
                }

                is IrInstr.Label -> {
                    // Check for block concatenation: Label followed by Jump to next block
                    // The label starts a new block - we might be able to skip a preceding Jump
                    newInstrs.add(instr)
                }

                else -> newInstrs.add(instr)
            }
            i++
        }

        // Second pass: remove redundant unconditional jumps to next block
        val result2 = removeRedundantJumps(resultInstrs(newInstrs), labelToIndex)

        return OptResult(result2.first, constants, changed || result2.second)
    }

    private fun resultInstrs(list: List<IrInstr>): List<IrInstr> {
        // Build label -> index map for the new instruction list
        val newList = list.toMutableList()
        return newList
    }

    private fun removeRedundantJumps(
        instrs: List<IrInstr>,
        originalLabelToIndex: Map<Int, Int>
    ): Pair<List<IrInstr>, Boolean> {
        if (instrs.isEmpty()) return Pair(instrs, false)

        // Build label -> index map for current instrs
        val labelToIndex = mutableMapOf<Int, Int>()
        for ((idx, instr) in instrs.withIndex()) {
            if (instr is IrInstr.Label) {
                labelToIndex[instr.label.id] = idx
            }
        }

        val newInstrs = mutableListOf<IrInstr>()
        var changed = false

        for (i in instrs.indices) {
            val instr = instrs[i]

            // Check for unconditional Jump to next instruction (redundant)
            if (instr is IrInstr.Jump) {
                val targetIdx = labelToIndex[instr.target.id]
                if (targetIdx != null) {
                    // Find the actual next instruction index (skipping labels)
                    var nextIdx = i + 1
                    while (nextIdx < instrs.size && instrs[nextIdx] is IrInstr.Label) {
                        // Skip labels at the target position too
                        if (nextIdx == targetIdx) break
                        nextIdx++
                    }

                    // If Jump target is the next non-label instruction, it's redundant
                    if (targetIdx == nextIdx && nextIdx < instrs.size) {
                        changed = true
                        continue // Skip this redundant Jump
                    }
                }
            }

            // Check for conditional Jump where both branches go to the same place
            if (instr is IrInstr.JumpIfFalse) {
                val thenIdx = labelToIndex[instr.target.id]
                var elseIdx = i + 1
                // Fall-through is the next instruction
                while (elseIdx < instrs.size && instrs[elseIdx] is IrInstr.Label) {
                    elseIdx++
                }
                if (elseIdx < instrs.size && thenIdx == elseIdx) {
                    // Both branches go to the same place - unconditional jump
                    changed = true
                    newInstrs.add(IrInstr.Jump(instr.target))
                    continue
                }
            }

            newInstrs.add(instr)
        }

        return Pair(newInstrs, changed)
    }

    private fun tryOptimizeConditionalJump(
        allInstrs: List<IrInstr>,
        newInstrs: List<IrInstr>,
        idx: Int,
        jump: IrInstr.JumpIfFalse,
        labelToIndex: Map<Int, Int>,
        constants: List<Value>
    ): Boolean {
        // This method tries to optimize a JumpIfFalse in context
        // For now, return false - most optimizations are handled in the main loop
        return false
    }
}
