package org.inklang.opt.passes

import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.IrInstr
import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.opt.OptPass
import org.inklang.opt.OptResult

/**
 * Strength Reduction and Instruction Combination pass.
 *
 * Replaces expensive operations with cheaper equivalents.
 * This pass works on IR (non-SSA) form by tracking which registers
 * currently hold constant values.
 *
 * Optimizations:
 * - x * 2  -> x + x       (multiplication by 2 is cheaper as addition)
 * - x * 1  -> x           (identity)
 * - x / 1  -> x           (identity)
 * - x % 1  -> 0           (constant)
 * - x + 0  -> x           (identity)
 * - x - 0  -> x           (identity)
 * - x - x  -> 0           (constant)
 */
class StrengthReductionPass : OptPass {
    override val name = "StrengthReduction"

    override fun run(
        instrs: List<IrInstr>,
        cfg: ControlFlowGraph,
        constants: List<Value>
    ): OptResult {
        val newConstants = constants.toMutableList()
        val newInstrs = mutableListOf<IrInstr>()
        var changed = false

        // Track which registers hold constant values
        val regConstants = mutableMapOf<Int, Value>()

        fun addConstant(value: Value): Int {
            val idx = newConstants.indexOf(value)
            return if (idx >= 0) idx else {
                newConstants.add(value)
                newConstants.lastIndex
            }
        }

        for (instr in instrs) {
            val optimized = tryReduce(instr, regConstants, newConstants, ::addConstant)
            if (optimized != null && optimized !== instr) {
                newInstrs.add(optimized)
                changed = true
                // Update tracking for the optimized instruction's destination
                updateTracking(optimized, regConstants, newConstants)
            } else {
                newInstrs.add(instr)
                updateTracking(instr, regConstants, newConstants)
            }
        }

        return OptResult(newInstrs, newConstants, changed)
    }

    private fun updateTracking(
        instr: IrInstr,
        regConstants: MutableMap<Int, Value>,
        constants: List<Value>
    ) {
        val dst: Int? = when (instr) {
            is IrInstr.LoadImm -> instr.dst
            is IrInstr.Move -> instr.dst
            is IrInstr.BinaryOp -> instr.dst
            is IrInstr.UnaryOp -> instr.dst
            is IrInstr.LoadGlobal -> instr.dst
            is IrInstr.LoadFunc -> instr.dst
            is IrInstr.LoadClass -> instr.dst
            is IrInstr.Call -> instr.dst
            is IrInstr.NewArray -> instr.dst
            is IrInstr.NewInstance -> instr.dst
            is IrInstr.GetIndex -> instr.dst
            is IrInstr.GetField -> instr.dst
            is IrInstr.IsType -> instr.dst
            is IrInstr.Unspill -> instr.dst
            else -> null
        }

        if (dst == null) return

        when (instr) {
            is IrInstr.LoadImm -> {
                if (instr.index < constants.size) {
                    regConstants[dst] = constants[instr.index]
                } else {
                    regConstants.remove(dst)
                }
            }
            is IrInstr.Move -> {
                val srcConst = regConstants[instr.src]
                if (srcConst != null) {
                    regConstants[dst] = srcConst
                } else {
                    regConstants.remove(dst)
                }
            }
            else -> {
                regConstants.remove(dst)
            }
        }
    }

    private fun tryReduce(
        instr: IrInstr,
        regConstants: Map<Int, Value>,
        constants: List<Value>,
        addConstant: (Value) -> Int
    ): IrInstr? {
        return when (instr) {
            is IrInstr.BinaryOp -> reduceBinaryOp(instr, regConstants, constants, addConstant)
            else -> null
        }
    }

    private fun reduceBinaryOp(
        instr: IrInstr.BinaryOp,
        regConstants: Map<Int, Value>,
        constants: List<Value>,
        addConstant: (Value) -> Int
    ): IrInstr? {
        val leftConst = regConstants[instr.src1]
        val rightConst = regConstants[instr.src2]

        return when (instr.op) {
            // Multiplication optimizations
            TokenType.STAR -> {
                // x * 2 -> x + x (cheaper than multiplication)
                if (isTwo(rightConst)) {
                    return IrInstr.BinaryOp(instr.dst, TokenType.PLUS, instr.src1, instr.src1)
                }
                if (isTwo(leftConst)) {
                    return IrInstr.BinaryOp(instr.dst, TokenType.PLUS, instr.src2, instr.src2)
                }
                // x * 1 -> x
                if (isOne(rightConst)) {
                    return IrInstr.Move(instr.dst, instr.src1)
                }
                if (isOne(leftConst)) {
                    return IrInstr.Move(instr.dst, instr.src2)
                }
                // x * 0 -> 0 (keep operand for potential side effects, DCE will remove)
                if (isZero(rightConst)) {
                    return IrInstr.Move(instr.dst, instr.src1)
                }
                if (isZero(leftConst)) {
                    return IrInstr.Move(instr.dst, instr.src2)
                }
                null
            }

            // Division optimizations
            TokenType.SLASH -> {
                // x / 1 -> x
                if (isOne(rightConst)) {
                    return IrInstr.Move(instr.dst, instr.src1)
                }
                null
            }

            // Remainder optimizations
            TokenType.PERCENT -> {
                // x % 1 -> 0
                if (isOne(rightConst)) {
                    val zeroIdx = addConstant(Value.Int(0))
                    return IrInstr.LoadImm(instr.dst, zeroIdx)
                }
                null
            }

            // Addition optimizations
            TokenType.PLUS -> {
                // x + 0 -> x
                if (isZero(rightConst)) {
                    return IrInstr.Move(instr.dst, instr.src1)
                }
                if (isZero(leftConst)) {
                    return IrInstr.Move(instr.dst, instr.src2)
                }
                null
            }

            // Subtraction optimizations
            TokenType.MINUS -> {
                // x - 0 -> x
                if (isZero(rightConst)) {
                    return IrInstr.Move(instr.dst, instr.src1)
                }
                // x - x -> 0
                if (instr.src1 == instr.src2) {
                    val zeroIdx = addConstant(Value.Int(0))
                    return IrInstr.LoadImm(instr.dst, zeroIdx)
                }
                null
            }

            else -> null
        }
    }

    private fun isZero(v: Value?): Boolean = v is Value.Int && v.value == 0
    private fun isOne(v: Value?): Boolean = v is Value.Int && v.value == 1
    private fun isTwo(v: Value?): Boolean = v is Value.Int && v.value == 2
}
