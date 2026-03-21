package org.inklang.opt.passes

import org.inklang.ast.ControlFlowGraph
import org.inklang.ast.Loop
import org.inklang.lang.IrInstr
import org.inklang.lang.IrLabel
import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.opt.OptPass
import org.inklang.opt.OptResult

/**
 * Induction Variable Recognition and Loop Normalization Pass.
 *
 * Recognizes Range iterator loops emitted by lowerForRange and replaces
 * the expensive iterator protocol (.iter(), .hasNext(), .next()) with
 * direct integer arithmetic.
 *
 * This optimization is conservative: it only applies when:
 * 1. The iterable is a Range literal (BinaryOp DOT_DOT with constant bounds)
 * 2. The loop body contains no break/next statements that escape the loop
 * 3. The loop variable is not modified within the body
 */
class InductionVariablePass : OptPass {
    override val name = "InductionVariable"

    override fun run(
        instrs: List<IrInstr>,
        cfg: ControlFlowGraph,
        constants: List<Value>
    ): OptResult {
        if (cfg.blocks.isEmpty()) return OptResult(instrs, constants, false)

        val loops = cfg.naturalLoops()
        if (loops.isEmpty()) return OptResult(instrs, constants, false)

        var changed = false
        var currentInstrs = instrs

        for (loop in loops) {
            val (newInstrs, didChange) = tryNormalizeLoop(currentInstrs, loop, constants)
            if (didChange) {
                currentInstrs = newInstrs
                changed = true
            }
        }

        return OptResult(currentInstrs, constants, changed)
    }

    /**
     * Try to normalize a Range iterator loop to arithmetic form.
     *
     * Range loop pattern (from lowerForRange):
     *   GetField(iterReg, rangeReg, "iter")
     *   Call(iterReg, iterReg, [])
     *   Label loopHeader
     *   GetField(condReg, iterReg, "hasNext")
     *   Call(condReg, condReg, [])
     *   JumpIfFalse(condReg, loopEnd)
     *   GetField(valueReg, iterReg, "next")
     *   Call(valueReg, valueReg, [])
     *   ; body instructions
     *   Jump loopHeader
     *   Label loopEnd
     *
     * Transformation to arithmetic:
     *   LoadImm(valueReg, startConstIdx)
     *   Label loopHeader
     *   LoadImm(endReg, endConstIdx)       ; reload end (may be in a reg that was reused)
     *   BinaryOp(tempReg, GT, valueReg, endReg)
     *   JumpIfFalse(tempReg, loopEnd)
     *   ; body instructions (valueReg is loop variable)
     *   BinaryOp(incReg, PLUS, valueReg, 1)
     *   Move(valueReg, incReg)
     *   Jump loopHeader
     *   Label loopEnd
     */
    private fun tryNormalizeLoop(
        instrs: List<IrInstr>,
        loop: Loop,
        constants: List<Value>
    ): Pair<List<IrInstr>, Boolean> {
        // Find the iterator GetField+Call pattern
        // These appear BEFORE the loop header label
        var rangeGetFieldIdx: Int? = null
        var rangeGetCallIdx: Int? = null

        for ((idx, instr) in instrs.withIndex()) {
            if (instr is IrInstr.GetField && instr.name == "iter") {
                rangeGetFieldIdx = idx
            }
            if (rangeGetFieldIdx != null && instr is IrInstr.Call) {
                rangeGetCallIdx = idx
                break
            }
        }

        if (rangeGetFieldIdx == null || rangeGetCallIdx == null) {
            return Pair(instrs, false)
        }

        // Find the loop header label
        var loopHeaderLabelIdx: Int? = null
        for ((idx, instr) in instrs.withIndex()) {
            if (instr is IrInstr.Label && loop.header == blockIdAtIndex(instrs, idx)) {
                loopHeaderLabelIdx = idx
                break
            }
        }

        if (loopHeaderLabelIdx == null) {
            return Pair(instrs, false)
        }

        // Find hasNext GetField + Call + JumpIfFalse pattern at loop header
        var hasNextGetIdx: Int? = null
        var hasNextCallIdx: Int? = null
        var jumpIfFalseIdx: Int? = null
        var jumpTarget: IrLabel? = null

        var phase = 0 // 0: find GetField(hasNext), 1: find Call, 2: find JumpIfFalse
        for (idx in (loopHeaderLabelIdx + 1) until instrs.size) {
            val instr = instrs[idx]
            when (phase) {
                0 -> {
                    if (instr is IrInstr.GetField && instr.name == "hasNext") {
                        hasNextGetIdx = idx
                        phase = 1
                    } else if (instr !is IrInstr.Label) {
                        break
                    }
                }
                1 -> {
                    if (instr is IrInstr.Call) {
                        hasNextCallIdx = idx
                        phase = 2
                    } else if (instr !is IrInstr.Label) {
                        break
                    }
                }
                2 -> {
                    if (instr is IrInstr.JumpIfFalse) {
                        jumpIfFalseIdx = idx
                        jumpTarget = instr.target
                        break
                    } else if (instr is IrInstr.Label) {
                        break
                    }
                }
            }
        }

        if (hasNextGetIdx == null || hasNextCallIdx == null || jumpIfFalseIdx == null) {
            return Pair(instrs, false)
        }

        // Find next() GetField + Call pattern
        var nextGetIdx: Int? = null
        var nextCallIdx: Int? = null

        for (idx in (hasNextCallIdx + 1) until instrs.size) {
            val instr = instrs[idx]
            if (instr is IrInstr.GetField && instr.name == "next") {
                nextGetIdx = idx
                if (idx + 1 < instrs.size && instrs[idx + 1] is IrInstr.Call) {
                    nextCallIdx = idx + 1
                }
                break
            }
            if (instr is IrInstr.Label || instr is IrInstr.Jump) {
                break
            }
        }

        if (nextGetIdx == null || nextCallIdx == null) {
            return Pair(instrs, false)
        }

        // Find back-edge Jump to loop header
        val loopHeaderLabel = (instrs[loopHeaderLabelIdx] as IrInstr.Label).label
        var backJumpIdx: Int? = null

        for (idx in (nextCallIdx + 1) until instrs.size) {
            val instr = instrs[idx]
            if (instr is IrInstr.Jump && instr.target == loopHeaderLabel) {
                backJumpIdx = idx
                break
            }
            if (instr is IrInstr.Label) {
                break
            }
        }

        if (backJumpIdx == null) {
            return Pair(instrs, false)
        }

        // Check body has no break/next
        for (idx in (nextCallIdx + 1) until backJumpIdx) {
            val instr = instrs[idx]
            if (instr is IrInstr.Break || instr is IrInstr.Next) {
                return Pair(instrs, false)
            }
        }

        // Extract Range bounds from the GetField("iter") instruction
        val iterGetInstr = instrs[rangeGetFieldIdx] as IrInstr.GetField
        val rangeReg = iterGetInstr.obj

        // Find the BinaryOp(DOT_DOT) that created the Range
        var rangeStartReg: Int? = null
        var rangeEndReg: Int? = null

        for (idx in 0 until rangeGetFieldIdx) {
            val instr = instrs[idx]
            if (instr is IrInstr.BinaryOp && instr.op == TokenType.DOT_DOT && instr.dst == rangeReg) {
                rangeStartReg = instr.src1
                rangeEndReg = instr.src2
            }
        }

        if (rangeStartReg == null || rangeEndReg == null) {
            return Pair(instrs, false)
        }

        // Verify bounds are constants
        val startConstIdx = (instrs.find { it is IrInstr.LoadImm && it.dst == rangeStartReg } as? IrInstr.LoadImm)?.index
        val endConstIdx = (instrs.find { it is IrInstr.LoadImm && it.dst == rangeEndReg } as? IrInstr.LoadImm)?.index

        if (startConstIdx == null || endConstIdx == null) {
            return Pair(instrs, false)
        }

        val startConst = constants.getOrNull(startConstIdx) as? Value.Int ?: return Pair(instrs, false)
        val endConst = constants.getOrNull(endConstIdx) as? Value.Int ?: return Pair(instrs, false)

        // Get the valueReg from next() Call
        val nextCallInstr = instrs[nextCallIdx] as IrInstr.Call
        val valueReg = nextCallInstr.dst

        // Verify loop variable is not modified in body
        for (idx in (nextCallIdx + 1) until backJumpIdx) {
            val defined = instrs[idx].let { instr ->
                when (instr) {
                    is IrInstr.LoadImm -> instr.dst
                    is IrInstr.BinaryOp -> instr.dst
                    is IrInstr.UnaryOp -> instr.dst
                    is IrInstr.Call -> instr.dst
                    is IrInstr.Move -> instr.dst
                    is IrInstr.GetField -> instr.dst
                    is IrInstr.GetIndex -> instr.dst
                    is IrInstr.NewArray -> instr.dst
                    is IrInstr.NewInstance -> instr.dst
                    is IrInstr.IsType -> instr.dst
                    else -> null
                }
            }
            if (defined == valueReg) {
                return Pair(instrs, false)
            }
        }

        // Build the transformed instruction list
        // Instructions to remove entirely:
        val removeIdxs = setOf(
            rangeGetFieldIdx, rangeGetCallIdx,
            hasNextGetIdx, hasNextCallIdx,
            nextGetIdx, nextCallIdx,
            backJumpIdx
        )

        // Indices of body instructions (between nextCallIdx+1 and backJumpIdx-1)
        val bodyRange = (nextCallIdx + 1) until backJumpIdx

        // Find a free register for the increment temp
        val usedRegs = mutableSetOf<Int>()
        for (instr in instrs) {
            when (instr) {
                is IrInstr.LoadImm -> usedRegs.add(instr.dst)
                is IrInstr.BinaryOp -> usedRegs.add(instr.dst)
                is IrInstr.UnaryOp -> usedRegs.add(instr.dst)
                is IrInstr.Call -> usedRegs.add(instr.dst)
                is IrInstr.Move -> usedRegs.add(instr.dst)
                is IrInstr.GetField -> usedRegs.add(instr.dst)
                is IrInstr.GetIndex -> usedRegs.add(instr.dst)
                is IrInstr.NewArray -> usedRegs.add(instr.dst)
                is IrInstr.NewInstance -> usedRegs.add(instr.dst)
                is IrInstr.IsType -> usedRegs.add(instr.dst)
                else -> {}
            }
        }
        val tempReg = (0..200).first { it !in usedRegs && it != valueReg && it != rangeEndReg }
        val oneIdx = constants.indexOf(Value.Int(1)).let { if (it >= 0) it else constants.size }

        val newInstrs = mutableListOf<IrInstr>()

        for ((idx, instr) in instrs.withIndex()) {
            when {
                // At loop header label: insert start value load, then label
                idx == loopHeaderLabelIdx -> {
                    newInstrs.add(IrInstr.LoadImm(valueReg, startConstIdx))
                    newInstrs.add(instr)
                }
                // Replace JumpIfFalse with: temp = valueReg > endReg; if !temp goto end
                idx == jumpIfFalseIdx -> {
                    newInstrs.add(IrInstr.BinaryOp(tempReg, TokenType.GT, valueReg, rangeEndReg))
                    newInstrs.add(IrInstr.JumpIfFalse(tempReg, jumpTarget!!))
                }
                // At end of body (before back jump was): add increment
                idx == nextCallIdx + 1 && bodyRange.any { instrs[it].let { i -> i is IrInstr.Label || i is IrInstr.Jump } } -> {
                    // Body is empty or starts with label/jump - this is complex, skip
                    return Pair(instrs, false)
                }
                // After body content ends: insert increment before body ends
                idx in bodyRange -> {
                    // Copy body instructions
                    newInstrs.add(instr)
                    // After last body instruction (right before backJumpIdx): add increment
                    if (idx == bodyRange.last) {
                        newInstrs.add(IrInstr.BinaryOp(tempReg, TokenType.PLUS, valueReg, valueReg))
                        newInstrs.add(IrInstr.Move(valueReg, tempReg))
                    }
                }
                idx !in removeIdxs && idx !in bodyRange -> {
                    newInstrs.add(instr)
                }
            }
        }

        return Pair(newInstrs, true)
    }

    /**
     * Compute the block ID at a given instruction index.
     * Block IDs are assigned based on leader order: first leader = block 0, second = block 1, etc.
     */
    private fun blockIdAtIndex(instrs: List<IrInstr>, targetIdx: Int): Int {
        // Find all leaders
        val leaders = mutableSetOf(0)
        for ((idx, instr) in instrs.withIndex()) {
            when (instr) {
                is IrInstr.Label -> leaders.add(idx)
                is IrInstr.Jump, is IrInstr.JumpIfFalse, is IrInstr.Return,
                is IrInstr.Break, is IrInstr.Next -> {
                    if (idx + 1 < instrs.size) leaders.add(idx + 1)
                }
                else -> {}
            }
        }
        val sortedLeaders = leaders.sorted()

        // Binary search to find which block this index belongs to
        var lo = 0
        var hi = sortedLeaders.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (sortedLeaders[mid] <= targetIdx) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return lo
    }
}
