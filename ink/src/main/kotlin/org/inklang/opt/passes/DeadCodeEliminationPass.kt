package org.inklang.opt.passes

import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.IrInstr
import org.inklang.lang.Value
import org.inklang.opt.OptPass
import org.inklang.opt.OptResult

/**
 * Dead code elimination pass.
 * Removes unreachable code and unused register assignments.
 *
 * Two phases:
 * 1. Remove unreachable blocks (based on CFG reachability)
 * 2. Remove unused register definitions (liveness analysis)
 */
class DeadCodeEliminationPass : OptPass {
    override val name = "DeadCodeElimination"

    override fun run(
        instrs: List<IrInstr>,
        cfg: ControlFlowGraph,
        constants: List<Value>
    ): OptResult {
        // Phase 1: Remove unreachable code
        val reachableResult = removeUnreachable(instrs, cfg)
        val afterReachable = reachableResult.first
        val removedUnreachable = reachableResult.second

        // Phase 2: Remove unused register definitions
        val unusedResult = removeUnusedDefs(afterReachable)

        val changed = removedUnreachable || unusedResult.second
        return OptResult(unusedResult.first, constants, changed)
    }

    /**
     * Remove instructions in unreachable blocks.
     */
    private fun removeUnreachable(
        instrs: List<IrInstr>,
        cfg: ControlFlowGraph
    ): Pair<List<IrInstr>, Boolean> {
        if (cfg.blocks.isEmpty()) return Pair(instrs, false)

        val unreachable = cfg.unreachable()
        if (unreachable.isEmpty()) return Pair(instrs, false)

        // Build set of instruction indices to remove
        val indicesToRemove = mutableSetOf<Int>()
        for (blockId in unreachable) {
            val block = cfg.getBlock(blockId) ?: continue
            for (idx in block.startIndex..block.endIndex) {
                indicesToRemove.add(idx)
            }
        }

        val newInstrs = instrs.filterIndexed { idx, _ -> idx !in indicesToRemove }
        return Pair(newInstrs, true)
    }

    /**
     * Remove register definitions that are never used.
     * Uses backward liveness analysis.
     */
    private fun removeUnusedDefs(instrs: List<IrInstr>): Pair<List<IrInstr>, Boolean> {
        // Compute which registers are used
        val usedRegs = mutableSetOf<Int>()
        for (instr in instrs) {
            when (instr) {
                is IrInstr.BinaryOp -> {
                    usedRegs.add(instr.src1)
                    usedRegs.add(instr.src2)
                }
                is IrInstr.UnaryOp -> usedRegs.add(instr.src)
                is IrInstr.Call -> {
                    usedRegs.add(instr.func)
                    usedRegs.addAll(instr.args)
                }
                is IrInstr.Return -> usedRegs.add(instr.src)
                is IrInstr.JumpIfFalse -> usedRegs.add(instr.src)
                is IrInstr.StoreGlobal -> usedRegs.add(instr.src)
                is IrInstr.Move -> usedRegs.add(instr.src)
                is IrInstr.NewArray -> usedRegs.addAll(instr.elements)
                is IrInstr.GetIndex -> {
                    usedRegs.add(instr.obj)
                    usedRegs.add(instr.index)
                }
                is IrInstr.SetIndex -> {
                    usedRegs.add(instr.obj)
                    usedRegs.add(instr.index)
                    usedRegs.add(instr.src)
                }
                is IrInstr.GetField -> usedRegs.add(instr.obj)
                is IrInstr.SetField -> {
                    usedRegs.add(instr.obj)
                    usedRegs.add(instr.src)
                }
                is IrInstr.NewInstance -> {
                    usedRegs.add(instr.classReg)
                    usedRegs.addAll(instr.args)
                }
                is IrInstr.IsType -> usedRegs.add(instr.src)
                else -> {}
            }
        }

        // Track which registers are defined
        val definedRegs = mutableSetOf<Int>()
        for (instr in instrs) {
            when (instr) {
                is IrInstr.LoadImm -> definedRegs.add(instr.dst)
                is IrInstr.LoadGlobal -> definedRegs.add(instr.dst)
                is IrInstr.LoadFunc -> definedRegs.add(instr.dst)
                is IrInstr.BinaryOp -> definedRegs.add(instr.dst)
                is IrInstr.UnaryOp -> definedRegs.add(instr.dst)
                is IrInstr.Call -> definedRegs.add(instr.dst)
                is IrInstr.Move -> definedRegs.add(instr.dst)
                is IrInstr.NewArray -> definedRegs.add(instr.dst)
                is IrInstr.GetIndex -> definedRegs.add(instr.dst)
                is IrInstr.GetField -> definedRegs.add(instr.dst)
                is IrInstr.NewInstance -> definedRegs.add(instr.dst)
                is IrInstr.IsType -> definedRegs.add(instr.dst)
                is IrInstr.LoadClass -> definedRegs.add(instr.dst)
                else -> {}
            }
        }

        // Find unused registers
        val unusedRegs = definedRegs - usedRegs

        if (unusedRegs.isEmpty()) return Pair(instrs, false)

        // Remove instructions that only define unused registers
        // But keep LoadFunc because it has side effects (defines the function)
        val newInstrs = instrs.filter { instr ->
            when (instr) {
                is IrInstr.LoadImm -> instr.dst in usedRegs
                is IrInstr.LoadGlobal -> instr.dst in usedRegs
                is IrInstr.LoadFunc -> true // Keep function definitions
                is IrInstr.BinaryOp -> instr.dst in usedRegs
                is IrInstr.UnaryOp -> instr.dst in usedRegs
                is IrInstr.Call -> true // Keep calls (may have side effects)
                is IrInstr.Move -> instr.dst in usedRegs
                is IrInstr.NewArray -> instr.dst in usedRegs
                is IrInstr.GetIndex -> instr.dst in usedRegs
                is IrInstr.GetField -> instr.dst in usedRegs
                is IrInstr.NewInstance -> instr.dst in usedRegs
                is IrInstr.IsType -> instr.dst in usedRegs
                is IrInstr.LoadClass -> true // Keep class definitions
                else -> true // Keep all other instructions
            }
        }

        return Pair(newInstrs, newInstrs.size < instrs.size)
    }
}
