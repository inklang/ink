package org.inklang.ast

import org.inklang.lang.IrInstr
import org.inklang.lang.IrLabel

data class LiveRange(val reg: Int, val start: Int, var end: Int)
class LivenessAnalyzer {
    fun analyze(instrs: List<IrInstr>): Map<Int, LiveRange> {
        val ranges = mutableMapOf<Int, LiveRange>()
        fun define(reg: Int, idx: Int) {
            ranges.getOrPut(reg) {
                LiveRange(reg, idx, idx)
            }
        }

        fun use(reg: Int, idx: Int) {
            ranges.getOrPut(reg) {
                LiveRange(reg, idx, idx)
            }.end = idx
        }

        // First pass: build label index and find loops
        val labelIndices = mutableMapOf<Int, Int>() // label.id -> instruction index
        val loops = mutableListOf<Pair<Int, Int>>() // (loopStart, loopEnd) pairs

        for ((idx, instr) in instrs.withIndex()) {
            if (instr is IrInstr.Label) {
                labelIndices[instr.label.id] = idx
            }
        }

        // Find backward jumps (loops)
        for ((idx, instr) in instrs.withIndex()) {
            if (instr is IrInstr.Jump) {
                val targetIdx = labelIndices[instr.target.id]
                if (targetIdx != null && targetIdx < idx) {
                    // This is a loop: targetIdx is loop start, idx is loop end
                    loops.add(Pair(targetIdx, idx))
                }
            }
        }

        // Second pass: analyze instructions
        for ((idx, instr) in instrs.withIndex()) {
            when (instr) {
                is IrInstr.LoadImm -> define(instr.dst, idx)
                is IrInstr.LoadGlobal -> define(instr.dst, idx)
                is IrInstr.StoreGlobal -> use(instr.src, idx)
                is IrInstr.LoadFunc -> define(instr.dst, idx)
                is IrInstr.BinaryOp -> {
                    define(instr.dst, idx)
                    use(instr.src1, idx)
                    use(instr.src2, idx)
                }
                is IrInstr.UnaryOp -> {
                    define(instr.dst, idx)
                    use(instr.src, idx)
                }
                is IrInstr.Call -> {
                    define(instr.dst, idx)
                    use(instr.func, idx)
                    instr.args.forEach { use(it, idx) }
                }
                is IrInstr.Return -> use(instr.src, idx)
                is IrInstr.JumpIfFalse -> use(instr.src, idx)
                is IrInstr.Jump -> {}
                is IrInstr.Label -> {}
                is IrInstr.Break -> {}
                is IrInstr.Next -> {}
                is IrInstr.Move -> {
                    define(instr.dst, idx)
                    use(instr.src, idx)
                }
                is IrInstr.NewArray -> {
                    define(instr.dst, idx)
                    instr.elements.forEach { use(it, idx) }
                }
                is IrInstr.GetIndex -> {
                    define(instr.dst, idx)
                    use(instr.obj, idx)
                    use(instr.index, idx)
                }
                is IrInstr.SetIndex -> {
                    use(instr.obj, idx)
                    use(instr.index, idx)
                    use(instr.src, idx)
                }
                is IrInstr.GetField -> {
                    define(instr.dst, idx)
                    use(instr.obj, idx)
                }
                is IrInstr.SetField -> {
                    use(instr.obj, idx)
                    use(instr.src, idx)
                }
                is IrInstr.NewInstance -> {
                    define(instr.dst, idx)
                    use(instr.classReg, idx)
                    instr.args.forEach { use(it, idx) }
                }
                is IrInstr.IsType -> {
                    define(instr.dst, idx)
                    use(instr.src, idx)
                }
                is IrInstr.HasCheck -> {
                    define(instr.dst, idx)
                    use(instr.obj, idx)
                }
                is IrInstr.LoadClass -> define(instr.dst, idx)
                is IrInstr.Spill   -> use(instr.src, idx)
                is IrInstr.Unspill -> define(instr.dst, idx)
            }
        }

        // Extend live ranges for variables that span loop back-edges
        // A variable needs to be extended if:
        // 1. It's defined before the loop AND used after the back-edge (loop variable)
        // 2. It's defined inside the loop AND used after the back-edge
        for ((loopStart, loopEnd) in loops) {
            for ((reg, range) in ranges) {
                // Only extend if the variable is defined before the loop start
                // AND used inside the loop (potentially across iterations)
                if (range.start < loopStart && range.end >= loopStart && range.end <= loopEnd) {
                    // This is likely a loop variable or outer-scope variable used in loop
                    range.end = loopEnd
                }
            }
        }

        return ranges
    }
}