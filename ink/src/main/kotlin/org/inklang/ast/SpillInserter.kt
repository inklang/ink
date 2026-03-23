package org.inklang.ast

import org.inklang.lang.IrInstr

/**
 * Resolves all virtual registers to physical registers, injecting Spill/Unspill
 * instructions for spilled virtuals. Replaces rewrite() and rewriteRegisters().
 *
 * After insert() returns, no virtual register numbers remain in the instruction list.
 */
class SpillInserter {

    fun insert(
        instrs: List<IrInstr>,
        allocResult: RegisterAllocator.AllocResult,
        ranges: Map<Int, LiveRange>
    ): List<IrInstr> {
        val allocation = allocResult.allocation
        val spills = allocResult.spills

        // Precompute which physical registers are live at each original instruction index.
        // livePhysAt[i] = set of physical regs whose virtual is live at instruction i.
        // Spilled virtuals are excluded: they don't occupy a physical register continuously
        // (the physical they were initially assigned was stolen by another virtual).
        val livePhysAt: Array<Set<Int>> = Array(instrs.size) { i ->
            ranges.values
                .filter { it.start <= i && i <= it.end && it.reg !in spills }
                .mapNotNull { allocation[it.reg] }
                .toSet()
        }

        val result = mutableListOf<IrInstr>()

        for ((i, instr) in instrs.withIndex()) {
            // Per-instruction: track which temp regs have been claimed this instruction
            // to prevent two spilled operands from picking the same temp.
            val claimedTemps = mutableSetOf<Int>()
            val preInstrs  = mutableListOf<IrInstr>()
            val postInstrs = mutableListOf<IrInstr>()

            fun pickTemp(): Int =
                (0..15).firstOrNull { it !in livePhysAt[i] && it !in claimedTemps }
                    ?.also { claimedTemps.add(it) }
                    ?: error("Function exceeds register pressure: all 16 registers live simultaneously at instruction $i")

            // Resolve a source register to a physical register.
            // If spilled: insert Unspill before this instruction.
            fun resolveSrc(reg: Int): Int {
                if (reg in spills) {
                    val temp = pickTemp()
                    preInstrs.add(IrInstr.Unspill(temp, spills[reg]!!))
                    return temp
                }
                return allocation[reg] ?: error("Virtual register v$reg has no physical allocation")
            }

            // Resolve a destination register to a physical register.
            // If spilled: insert Spill after this instruction.
            fun resolveDst(reg: Int): Int {
                if (reg in spills) {
                    val temp = pickTemp()
                    postInstrs.add(IrInstr.Spill(spills[reg]!!, temp))
                    return temp
                }
                return allocation[reg] ?: error("Virtual register v$reg has no physical allocation")
            }

            val rewritten: IrInstr = when (instr) {
                is IrInstr.LoadImm     -> instr.copy(dst = resolveDst(instr.dst))
                is IrInstr.LoadGlobal  -> instr.copy(dst = resolveDst(instr.dst))
                is IrInstr.StoreGlobal -> instr.copy(src = resolveSrc(instr.src))
                is IrInstr.Move        -> instr.copy(src = resolveSrc(instr.src), dst = resolveDst(instr.dst))
                is IrInstr.BinaryOp    -> instr.copy(
                    src1 = resolveSrc(instr.src1),
                    src2 = resolveSrc(instr.src2),
                    dst  = resolveDst(instr.dst)
                )
                is IrInstr.UnaryOp     -> instr.copy(src = resolveSrc(instr.src), dst = resolveDst(instr.dst))
                is IrInstr.Call        -> instr.copy(
                    func = resolveSrc(instr.func),
                    args = instr.args.map { resolveSrc(it) },
                    dst  = resolveDst(instr.dst)
                )
                is IrInstr.NewArray    -> instr.copy(
                    elements = instr.elements.map { resolveSrc(it) },
                    dst      = resolveDst(instr.dst)
                )
                is IrInstr.GetIndex    -> instr.copy(
                    obj   = resolveSrc(instr.obj),
                    index = resolveSrc(instr.index),
                    dst   = resolveDst(instr.dst)
                )
                is IrInstr.SetIndex    -> instr.copy(
                    obj   = resolveSrc(instr.obj),
                    index = resolveSrc(instr.index),
                    src   = resolveSrc(instr.src)
                )
                is IrInstr.GetField    -> instr.copy(obj = resolveSrc(instr.obj), dst = resolveDst(instr.dst))
                is IrInstr.SetField    -> instr.copy(obj = resolveSrc(instr.obj), src = resolveSrc(instr.src))
                is IrInstr.NewInstance -> instr.copy(
                    classReg = resolveSrc(instr.classReg),
                    args     = instr.args.map { resolveSrc(it) },
                    dst      = resolveDst(instr.dst)
                )
                is IrInstr.IsType      -> instr.copy(src = resolveSrc(instr.src), dst = resolveDst(instr.dst))
                is IrInstr.HasCheck     -> instr.copy(obj = resolveSrc(instr.obj), dst = resolveDst(instr.dst))
                is IrInstr.LoadClass   -> instr.copy(dst = resolveDst(instr.dst))
                is IrInstr.Return      -> instr.copy(src = resolveSrc(instr.src))
                is IrInstr.JumpIfFalse -> instr.copy(src = resolveSrc(instr.src))
                is IrInstr.LoadFunc    -> instr.copy(dst = resolveDst(instr.dst))
                is IrInstr.Label,
                is IrInstr.Jump,
                is IrInstr.Break,
                is IrInstr.Next,
                is IrInstr.Spill,
                is IrInstr.Unspill,
                is IrInstr.Throw -> instr
                is IrInstr.RegisterEventHandler,
                is IrInstr.InvokeEventHandler,
                is IrInstr.GetUpvalue,
                is IrInstr.AwaitInstr,
                is IrInstr.SpawnInstr,
                is IrInstr.AsyncCallInstr -> instr
            }

            result.addAll(preInstrs)
            result.add(rewritten)
            result.addAll(postInstrs)
        }

        return result
    }
}
