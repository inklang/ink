package org.inklang.ssa

import org.inklang.lang.*

/**
 * Deconstructs SSA form back to normal IR.
 *
 * Algorithm:
 * 1. Resolve phi functions into copy pairs keyed by predecessor block ID
 * 2. Assign unique registers to all SSA values (including phi operands)
 * 3. Emit IR with phi-resolution moves inserted in predecessor blocks before terminals
 * 4. Sequentialize parallel copies to handle dependencies
 */
class SsaDeconstructor(private val ssaFunc: SsaFunction) {
    // Map from (baseReg, version) to a new register number
    private val regMap = mutableMapOf<Pair<Int, Int>, Int>()

    // Next available register number
    private var nextReg = 0

    // Stores (dstSsaValue, srcSsaValue) per predecessor block
    private val phiCopies = mutableMapOf<Int, MutableList<Pair<SsaValue, SsaValue>>>()

    /**
     * Deconstruct SSA form back to IR instructions.
     */
    fun deconstruct(): List<IrInstr> {
        if (ssaFunc.blocks.isEmpty()) {
            return emptyList()
        }

        // Step 1: Resolve phis — collect copy pairs per predecessor
        resolvePhis()

        // Step 2: Assign registers (covers all SSA values including phi operands)
        assignRegisters()

        // Step 3: Emit IR with phi-resolution moves in predecessor blocks
        val result = mutableListOf<IrInstr>()

        for (block in ssaFunc.blocks) {
            if (block.label != null) {
                result.add(IrInstr.Label(block.label))
            }

            val instrCount = block.instrs.size
            for ((i, instr) in block.instrs.withIndex()) {
                val isTerminal = i == instrCount - 1 && isTerminalInstr(instr)

                // Insert phi-resolution moves before the terminal instruction
                if (isTerminal) {
                    emitPhiMoves(block.id, result)
                }

                val irInstr = convertInstr(instr)
                if (irInstr != null) {
                    result.add(irInstr)
                }
            }

            // Fallthrough blocks: emit phi moves at end
            if (block.instrs.isEmpty() || !isTerminalInstr(block.instrs.last())) {
                emitPhiMoves(block.id, result)
            }
        }

        return result
    }

    /**
     * Resolve phi functions into copy pairs per predecessor block.
     */
    private fun resolvePhis() {
        for (block in ssaFunc.blocks) {
            if (block.phiFunctions.isEmpty()) continue
            for (phi in block.phiFunctions) {
                for ((predId, srcValue) in phi.operands) {
                    if (srcValue == SsaValue.UNDEFINED) continue
                    phiCopies.getOrPut(predId) { mutableListOf() }
                        .add(Pair(phi.result, srcValue))
                }
            }
        }
    }

    /**
     * Emit phi-resolution moves for a given block.
     */
    private fun emitPhiMoves(blockId: Int, result: MutableList<IrInstr>) {
        val copies = phiCopies[blockId] ?: return
        val moves = sequentializeCopies(copies)
        result.addAll(moves)
    }

    /**
     * Sequentialize parallel copies to handle dependencies.
     * Handles non-conflicting moves first, then circular dependencies with a temp register.
     */
    private fun sequentializeCopies(copies: List<Pair<SsaValue, SsaValue>>): List<IrInstr> {
        val moves = mutableListOf<IrInstr>()
        val emitted = mutableSetOf<Int>()

        // First pass: emit non-conflicting moves
        var changed = true
        while (changed) {
            changed = false
            for ((i, pair) in copies.withIndex()) {
                if (i in emitted) continue
                val (dst, src) = pair
                val dstReg = mapReg(dst)
                val srcReg = mapReg(src)
                if (dstReg == srcReg) {
                    emitted.add(i)
                    changed = true
                    continue
                }
                val conflictsWithOther = copies.indices.any { j ->
                    j !in emitted && j != i && mapReg(copies[j].second) == dstReg
                }
                if (!conflictsWithOther) {
                    moves.add(IrInstr.Move(dstReg, srcReg))
                    emitted.add(i)
                    changed = true
                }
            }
        }

        // Second pass: handle circular dependencies with temp register
        val remaining = copies.indices.filter { it !in emitted }
        if (remaining.isNotEmpty()) {
            val firstIdx = remaining.first()
            val (firstDst, firstSrc) = copies[firstIdx]
            val firstSrcReg = mapReg(firstSrc)
            val firstDstReg = mapReg(firstDst)
            val tempReg = nextReg++
            moves.add(IrInstr.Move(tempReg, firstSrcReg))

            var currentDst = firstDstReg
            val chainEmitted = mutableSetOf(firstIdx)
            var foundNext = true
            while (foundNext) {
                foundNext = false
                for (j in remaining) {
                    if (j in chainEmitted) continue
                    val (dst, src) = copies[j]
                    if (mapReg(src) == currentDst) {
                        moves.add(IrInstr.Move(mapReg(dst), mapReg(src)))
                        currentDst = mapReg(dst)
                        chainEmitted.add(j)
                        foundNext = true
                        break
                    }
                }
            }
            moves.add(IrInstr.Move(firstDstReg, tempReg))

            // Any remaining non-cycle copies
            for (j in remaining) {
                if (j in chainEmitted) continue
                val (dst, src) = copies[j]
                val dstReg = mapReg(dst)
                val srcReg = mapReg(src)
                if (dstReg != srcReg) {
                    moves.add(IrInstr.Move(dstReg, srcReg))
                }
            }
        }

        return moves
    }

    /**
     * Check if an instruction is a terminal (block-ending) instruction.
     */
    private fun isTerminalInstr(instr: SsaInstr): Boolean =
        instr is SsaInstr.Jump || instr is SsaInstr.JumpIfFalse ||
        instr is SsaInstr.Return || instr is SsaInstr.Break || instr is SsaInstr.Next

    /**
     * Assign register numbers to all SSA values.
     */
    private fun assignRegisters() {
        val allValues = mutableSetOf<SsaValue>()

        for (block in ssaFunc.blocks) {
            for (phi in block.phiFunctions) {
                allValues.add(phi.result)
                allValues.addAll(phi.operands.values)
            }
            for (instr in block.instrs) {
                instr.definedValue?.let { allValues.add(it) }
                allValues.addAll(instr.usedValues)
            }
        }

        // Also collect SSA values from phi copies
        for ((_, copies) in phiCopies) {
            for ((dst, src) in copies) {
                allValues.add(dst)
                allValues.add(src)
            }
        }

        // Pre-assign parameter registers: SsaValue(i, 0) -> register i for i in 0..arity-1
        val arity = ssaFunc.arity
        for (i in 0 until arity) {
            regMap[Pair(i, 0)] = i
        }
        nextReg = arity  // Start fresh registers after parameter registers

        // Each unique (baseReg, version) pair gets its own register
        for (value in allValues) {
            val key = Pair(value.baseReg, value.version)
            if (key !in regMap) {
                regMap[key] = nextReg++
            }
        }

        // Handle undefined values
        regMap[Pair(-1, -1)] = regMap[Pair(-1, -1)] ?: nextReg++
    }

    /**
     * Convert an SSA instruction to an IR instruction.
     */
    private fun convertInstr(instr: SsaInstr): IrInstr? = when (instr) {
        is SsaInstr.LoadImm -> {
            val dst = mapReg(instr.definedValue)
            IrInstr.LoadImm(dst, instr.constIndex)
        }
        is SsaInstr.LoadGlobal -> {
            val dst = mapReg(instr.definedValue)
            IrInstr.LoadGlobal(dst, instr.name)
        }
        is SsaInstr.StoreGlobal -> {
            val src = mapReg(instr.src)
            IrInstr.StoreGlobal(instr.name, src)
        }
        is SsaInstr.BinaryOp -> {
            val dst = mapReg(instr.definedValue)
            val src1 = mapReg(instr.src1)
            val src2 = mapReg(instr.src2)
            IrInstr.BinaryOp(dst, instr.op, src1, src2)
        }
        is SsaInstr.UnaryOp -> {
            val dst = mapReg(instr.definedValue)
            val src = mapReg(instr.src)
            IrInstr.UnaryOp(dst, instr.op, src)
        }
        is SsaInstr.Jump -> IrInstr.Jump(instr.target)
        is SsaInstr.JumpIfFalse -> {
            val src = mapReg(instr.src)
            IrInstr.JumpIfFalse(src, instr.target)
        }
        is SsaInstr.Label -> IrInstr.Label(instr.label)
        is SsaInstr.LoadFunc -> {
            val dst = mapReg(instr.definedValue)
            // Nested functions should already be in IR form (not SSA)
            IrInstr.LoadFunc(dst, instr.name, instr.arity, instr.instrs as List<IrInstr>, instr.constants, instr.defaultValues)
        }
        is SsaInstr.Call -> {
            val dst = mapReg(instr.definedValue)
            val func = mapReg(instr.func)
            val args = instr.args.map { mapReg(it) }
            IrInstr.Call(dst, func, args)
        }
        is SsaInstr.Return -> {
            val src = mapReg(instr.src)
            IrInstr.Return(src)
        }
        is SsaInstr.Move -> {
            val dst = mapReg(instr.definedValue)
            val src = mapReg(instr.src)
            if (dst != src) IrInstr.Move(dst, src) else null
        }
        is SsaInstr.GetIndex -> {
            val dst = mapReg(instr.definedValue)
            val obj = mapReg(instr.obj)
            val index = mapReg(instr.index)
            IrInstr.GetIndex(dst, obj, index)
        }
        is SsaInstr.SetIndex -> {
            val obj = mapReg(instr.obj)
            val index = mapReg(instr.index)
            val src = mapReg(instr.src)
            IrInstr.SetIndex(obj, index, src)
        }
        is SsaInstr.NewArray -> {
            val dst = mapReg(instr.definedValue)
            val elements = instr.elements.map { mapReg(it) }
            IrInstr.NewArray(dst, elements)
        }
        is SsaInstr.GetField -> {
            val dst = mapReg(instr.definedValue)
            val obj = mapReg(instr.obj)
            IrInstr.GetField(dst, obj, instr.name)
        }
        is SsaInstr.SetField -> {
            val obj = mapReg(instr.obj)
            val src = mapReg(instr.src)
            IrInstr.SetField(obj, instr.name, src)
        }
        is SsaInstr.NewInstance -> {
            val dst = mapReg(instr.definedValue)
            val classReg = mapReg(instr.classReg)
            val args = instr.args.map { mapReg(it) }
            IrInstr.NewInstance(dst, classReg, args)
        }
        is SsaInstr.IsType -> {
            val dst = mapReg(instr.definedValue)
            val src = mapReg(instr.src)
            IrInstr.IsType(dst, src, instr.typeName)
        }
        is SsaInstr.LoadClass -> {
            val dst = mapReg(instr.definedValue)
            IrInstr.LoadClass(dst, instr.name, instr.superClass, instr.methods)
        }
        is SsaInstr.Break -> IrInstr.Break
        is SsaInstr.Next -> IrInstr.Next
    }

    /**
     * Map an SSA value to a register number.
     */
    private fun mapReg(value: SsaValue): Int {
        return regMap[Pair(value.baseReg, value.version)] ?: 0
    }

    companion object {
        /**
         * Deconstruct SSA form to IR instructions.
         */
        fun deconstruct(ssaFunc: SsaFunction): List<IrInstr> {
            return SsaDeconstructor(ssaFunc).deconstruct()
        }
    }
}
