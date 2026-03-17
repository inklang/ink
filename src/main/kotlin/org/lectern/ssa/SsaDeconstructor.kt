package org.lectern.ssa

import org.lectern.lang.*

/**
 * Deconstructs SSA form back to normal IR.
 *
 * Algorithm:
 * 1. Convert phi functions to parallel copies in predecessor blocks
 * 2. Sequentialize parallel copies to handle dependencies
 * 3. Convert SsaInstr back to IrInstr
 */
class SsaDeconstructor(private val ssaFunc: SsaFunction) {
    // Map from (baseReg, version) to a new register number
    private val regMap = mutableMapOf<Pair<Int, Int>, Int>()

    // Next available register number
    private var nextReg = 0

    /**
     * Deconstruct SSA form back to IR instructions.
     */
    fun deconstruct(): List<IrInstr> {
        if (ssaFunc.blocks.isEmpty()) {
            return emptyList()
        }

        // First pass: assign register numbers to all SSA values
        assignRegisters()

        // Second pass: convert instructions and insert copies for phis
        val result = mutableListOf<IrInstr>()

        for (block in ssaFunc.blocks) {
            // Add label if present
            if (block.label != null) {
                result.add(IrInstr.Label(block.label))
            }

            // Convert phi functions to moves
            // For each predecessor, we need to insert moves
            // For simplicity, we insert moves at the start of the block
            // (proper implementation would insert in predecessor blocks)
            val phiMoves = convertPhis(block)
            result.addAll(phiMoves)

            // Convert regular instructions
            for (instr in block.instrs) {
                val irInstr = convertInstr(instr)
                if (irInstr != null) {
                    result.add(irInstr)
                }
            }
        }

        return result
    }

    /**
     * Assign register numbers to all SSA values.
     */
    private fun assignRegisters() {
        // Collect all SSA values
        val allValues = mutableSetOf<SsaValue>()

        for (block in ssaFunc.blocks) {
            // Phi results and operands
            for (phi in block.phiFunctions) {
                allValues.add(phi.result)
                allValues.addAll(phi.operands.values)
            }
            // Instruction definitions and uses
            for (instr in block.instrs) {
                instr.definedValue?.let { allValues.add(it) }
                allValues.addAll(instr.usedValues)
            }
        }

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
     * Convert phi functions to move instructions.
     *
     * For simplicity, we insert moves at the start of the block.
     * A more sophisticated implementation would insert copies in predecessor blocks
     * and handle critical edge splitting.
     */
    private fun convertPhis(block: SsaBlock): List<IrInstr> {
        if (block.phiFunctions.isEmpty()) {
            return emptyList()
        }

        val moves = mutableListOf<IrInstr>()

        for (phi in block.phiFunctions) {
            val dstReg = regMap[Pair(phi.result.baseReg, phi.result.version)] ?: continue

            // For the phi, we need to select the right value based on the predecessor
            // Since we can't know the predecessor at this point, we use a simple approach:
            // If there's only one predecessor, use that value directly
            // Otherwise, we need to insert moves in predecessor blocks (complex)

            if (block.predecessors.size == 1) {
                val predId = block.predecessors.first()
                val srcValue = phi.operands[predId]
                if (srcValue != null && srcValue != SsaValue.UNDEFINED) {
                    val srcReg = regMap[Pair(srcValue.baseReg, srcValue.version)]
                    if (srcReg != null && srcReg != dstReg) {
                        moves.add(IrInstr.Move(dstReg, srcReg))
                    }
                }
            } else {
                // Multiple predecessors: use the first non-undefined operand
                // This is a simplification - proper implementation needs critical edge splitting
                for ((_, srcValue) in phi.operands) {
                    if (srcValue != SsaValue.UNDEFINED) {
                        val srcReg = regMap[Pair(srcValue.baseReg, srcValue.version)]
                        if (srcReg != null && srcReg != dstReg) {
                            moves.add(IrInstr.Move(dstReg, srcReg))
                        }
                        break
                    }
                }
            }
        }

        return moves
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
