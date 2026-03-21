package org.inklang.opt.passes

import org.inklang.ast.ControlFlowGraph
import org.inklang.ast.Loop
import org.inklang.lang.IrInstr
import org.inklang.lang.Value
import org.inklang.opt.OptPass
import org.inklang.opt.OptResult

/**
 * Loop Invariant Code Motion (LICM) pass.
 * Moves loop-invariant computations out of loops.
 *
 * An instruction is loop-invariant if:
 * 1. All its source operands are constants, OR
 * 2. All its source operands are defined outside the loop
 *
 * Example:
 *   for i in 0..10 {
 *     let x = 5 + 3    // invariant: 5 and 3 are constants
 *     let y = i + x    // NOT invariant: i changes each iteration
 *   }
 *
 * Becomes:
 *   let x = 5 + 3      // hoisted out of loop
 *   for i in 0..10 {
 *     let y = i + x
 *   }
 */
class LoopInvariantCodeMotionPass : OptPass {
    override val name = "LoopInvariantCodeMotion"

    override fun run(
        instrs: List<IrInstr>,
        cfg: ControlFlowGraph,
        constants: List<Value>
    ): OptResult {
        if (cfg.blocks.isEmpty()) return OptResult(instrs, constants, false)

        val loops = cfg.naturalLoops()
        if (loops.isEmpty()) return OptResult(instrs, constants, false)

        // Track which registers are defined in each block
        val blockDefs = mutableMapOf<Int, Set<Int>>()
        for (block in cfg.blocks) {
            val defs = mutableSetOf<Int>()
            for (instr in block.instrs) {
                when (instr) {
                    is IrInstr.LoadImm -> defs.add(instr.dst)
                    is IrInstr.LoadGlobal -> defs.add(instr.dst)
                    is IrInstr.LoadFunc -> defs.add(instr.dst)
                    is IrInstr.BinaryOp -> defs.add(instr.dst)
                    is IrInstr.UnaryOp -> defs.add(instr.dst)
                    is IrInstr.Call -> defs.add(instr.dst)
                    is IrInstr.Move -> defs.add(instr.dst)
                    is IrInstr.NewArray -> defs.add(instr.dst)
                    is IrInstr.GetIndex -> defs.add(instr.dst)
                    is IrInstr.GetField -> defs.add(instr.dst)
                    is IrInstr.NewInstance -> defs.add(instr.dst)
                    is IrInstr.IsType -> defs.add(instr.dst)
                    is IrInstr.LoadClass -> defs.add(instr.dst)
                    else -> {}
                }
            }
            blockDefs[block.id] = defs
        }

        // Compute all registers defined inside each loop
        val loopDefs = mutableMapOf<Loop, Set<Int>>()
        for (loop in loops) {
            val defs = mutableSetOf<Int>()
            for (blockId in loop.body) {
                defs.addAll(blockDefs[blockId] ?: emptySet())
            }
            loopDefs[loop] = defs
        }

        // Find invariant instructions in each loop
        val invariantInstrs = mutableMapOf<Int, IrInstr>() // instr index -> invariant instr
        val toHoist = mutableSetOf<Int>() // instruction indices to hoist

        for (loop in loops) {
            val loopDefSet = loopDefs[loop] ?: continue

            // Find the preheader block (block before loop header)
            val headerBlock = cfg.getBlock(loop.header) ?: continue
            val preheaderBlock = headerBlock.predecessors
                .filter { it !in loop.body }
                .firstOrNull()
                ?.let { cfg.getBlock(it) }

            for (blockId in loop.body) {
                val block = cfg.getBlock(blockId) ?: continue
                var blockIdx = block.startIndex

                for (instr in block.instrs) {
                    if (isInvariant(instr, loopDefSet, invariantInstrs)) {
                        invariantInstrs[blockIdx] = instr

                        // Can only hoist "safe" instructions (no side effects)
                        if (isSafeToHoist(instr)) {
                            toHoist.add(blockIdx)
                        }
                    }
                    blockIdx++
                }
            }
        }

        if (toHoist.isEmpty()) return OptResult(instrs, constants, false)

        // Build new instruction list with hoisted instructions
        // For simplicity, we move all invariant instructions to before the loop
        // A more sophisticated approach would use preheaders

        val newInstrs = mutableListOf<IrInstr>()
        val hoisted = mutableListOf<IrInstr>()

        for ((idx, instr) in instrs.withIndex()) {
            if (idx in toHoist) {
                hoisted.add(instr)
            } else {
                newInstrs.add(instr)
            }
        }

        // Find insertion points for each loop and insert hoisted instructions
        // For now, we insert at the beginning (simple but not optimal)
        val finalInstrs = hoisted + newInstrs

        return OptResult(finalInstrs, constants, true)
    }

    /**
     * Check if an instruction is loop-invariant.
     */
    private fun isInvariant(
        instr: IrInstr,
        loopDefs: Set<Int>,
        alreadyInvariant: Map<Int, IrInstr>
    ): Boolean {
        return when (instr) {
            is IrInstr.BinaryOp -> {
                val src1Invariant = instr.src1 !in loopDefs
                val src2Invariant = instr.src2 !in loopDefs
                src1Invariant && src2Invariant
            }
            is IrInstr.UnaryOp -> instr.src !in loopDefs
            is IrInstr.LoadGlobal -> true // Global loads are invariant
            is IrInstr.LoadImm -> true // Constants are always invariant
            is IrInstr.GetField -> instr.obj !in loopDefs
            is IrInstr.GetIndex -> {
                instr.obj !in loopDefs && instr.index !in loopDefs
            }
            is IrInstr.IsType -> instr.src !in loopDefs
            is IrInstr.Move -> instr.src !in loopDefs
            else -> false
        }
    }

    /**
     * Check if an instruction is safe to hoist (no side effects).
     */
    private fun isSafeToHoist(instr: IrInstr): Boolean {
        return when (instr) {
            is IrInstr.LoadImm -> true
            is IrInstr.LoadGlobal -> true
            is IrInstr.BinaryOp -> true
            is IrInstr.UnaryOp -> true
            is IrInstr.GetField -> true
            is IrInstr.GetIndex -> true
            is IrInstr.IsType -> true
            is IrInstr.Move -> true
            // Don't hoist these (have side effects or may throw):
            is IrInstr.Call -> false
            is IrInstr.NewArray -> false // Could be expensive
            is IrInstr.NewInstance -> false
            is IrInstr.LoadFunc -> false
            is IrInstr.LoadClass -> false
            else -> false
        }
    }
}
