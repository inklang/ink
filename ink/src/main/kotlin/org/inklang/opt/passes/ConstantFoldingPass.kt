package org.inklang.opt.passes

import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.IrInstr
import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.opt.OptPass
import org.inklang.opt.OptResult

/**
 * Constant folding optimization pass.
 * Evaluates constant expressions at compile time.
 *
 * Example:
 *   LoadImm r0, 2        ; load constant 2
 *   LoadImm r1, 3        ; load constant 3
 *   BinaryOp r2, PLUS, r0, r1
 *
 * Becomes:
 *   LoadImm r2, 5        ; folded to 5
 */
class ConstantFoldingPass : OptPass {
    override val name = "ConstantFolding"

    override fun run(
        instrs: List<IrInstr>,
        cfg: ControlFlowGraph,
        constants: List<Value>
    ): OptResult {
        // Track which registers hold constant values
        val regConstants = mutableMapOf<Int, Value>()
        var changed = false
        val newInstrs = mutableListOf<IrInstr>()
        val newConstants = constants.toMutableList()

        fun addConstant(value: Value): Int {
            val idx = newConstants.indexOf(value)
            return if (idx >= 0) idx else {
                newConstants.add(value)
                newConstants.lastIndex
            }
        }

        for (instr in instrs) {
            when (instr) {
                is IrInstr.LoadImm -> {
                    if (instr.index < constants.size) {
                        regConstants[instr.dst] = constants[instr.index]
                    }
                    newInstrs.add(instr)
                }

                is IrInstr.BinaryOp -> {
                    val leftConst = regConstants[instr.src1]
                    val rightConst = regConstants[instr.src2]

                    if (leftConst != null && rightConst != null) {
                        val result = evaluateBinary(instr.op, leftConst, rightConst)
                        if (result != null) {
                            // Fold to constant
                            val constIndex = addConstant(result)
                            newInstrs.add(IrInstr.LoadImm(instr.dst, constIndex))
                            regConstants[instr.dst] = result
                            changed = true
                            continue
                        }
                    }

                    // Clear destination register's constant status
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }

                is IrInstr.UnaryOp -> {
                    val srcConst = regConstants[instr.src]

                    if (srcConst != null) {
                        val result = evaluateUnary(instr.op, srcConst)
                        if (result != null) {
                            val constIndex = addConstant(result)
                            newInstrs.add(IrInstr.LoadImm(instr.dst, constIndex))
                            regConstants[instr.dst] = result
                            changed = true
                            continue
                        }
                    }

                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }

                is IrInstr.Move -> {
                    val srcConst = regConstants[instr.src]
                    if (srcConst != null) {
                        regConstants[instr.dst] = srcConst
                    } else {
                        regConstants.remove(instr.dst)
                    }
                    newInstrs.add(instr)
                }

                is IrInstr.JumpIfFalse -> {
                    val condConst = regConstants[instr.src]
                    if (condConst != null) {
                        // Can potentially eliminate dead branches
                        if (condConst is Value.Boolean) {
                            if (!condConst.value) {
                                // Condition is always false, always jump
                                newInstrs.add(IrInstr.Jump(instr.target))
                                changed = true
                                continue
                            }
                            // Condition is always true, never jump - can remove entirely
                            // But we need to keep labels for CFG, so just skip this instruction
                            changed = true
                            continue
                        }
                    }
                    newInstrs.add(instr)
                }

                // Instructions that define registers but we can't track
                is IrInstr.LoadGlobal -> {
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }
                is IrInstr.LoadFunc -> {
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }
                is IrInstr.Call -> {
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }
                is IrInstr.GetIndex -> {
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }
                is IrInstr.GetField -> {
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }
                is IrInstr.NewArray -> {
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }
                is IrInstr.NewInstance -> {
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }
                is IrInstr.IsType -> {
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }
                is IrInstr.LoadClass -> {
                    regConstants.remove(instr.dst)
                    newInstrs.add(instr)
                }

                // Instructions that don't affect register constants
                else -> newInstrs.add(instr)
            }
        }

        return OptResult(newInstrs, newConstants, changed)
    }

    private fun evaluateBinary(op: TokenType, left: Value, right: Value): Value? {
        return when (op) {
            TokenType.PLUS -> evaluateArith(left, right) { a, b -> a + b }
            TokenType.MINUS -> evaluateArith(left, right) { a, b -> a - b }
            TokenType.STAR -> evaluateArith(left, right) { a, b -> a * b }
            TokenType.SLASH -> {
                val r = when (right) {
                    is Value.Int -> right.value.toDouble()
                    is Value.Float -> right.value.toDouble()
                    is Value.Double -> right.value
                    else -> return null
                }
                if (r == 0.0) return null // Don't fold division by zero
                evaluateArith(left, right) { a, b -> a / b }
            }
            TokenType.PERCENT -> {
                val r = when (right) {
                    is Value.Int -> right.value.toDouble()
                    is Value.Float -> right.value.toDouble()
                    is Value.Double -> right.value
                    else -> return null
                }
                if (r == 0.0) return null
                evaluateArith(left, right) { a, b -> a % b }
            }
            TokenType.LT -> evaluateCompare(left, right) { a, b -> a < b }
            TokenType.LTE -> evaluateCompare(left, right) { a, b -> a <= b }
            TokenType.GT -> evaluateCompare(left, right) { a, b -> a > b }
            TokenType.GTE -> evaluateCompare(left, right) { a, b -> a >= b }
            TokenType.EQ_EQ -> evaluateEqual(left, right, true)
            TokenType.BANG_EQ -> evaluateEqual(left, right, false)
            TokenType.KW_AND -> evaluateLogical(left, right, true)
            TokenType.KW_OR -> evaluateLogical(left, right, false)
            else -> null
        }
    }

    private fun evaluateUnary(op: TokenType, value: Value): Value? {
        return when (op) {
            TokenType.MINUS -> when (value) {
                is Value.Int -> Value.Int(-value.value)
                is Value.Float -> Value.Float(-value.value)
                is Value.Double -> Value.Double(-value.value)
                else -> null
            }
            TokenType.BANG, TokenType.KW_NOT -> when (value) {
                is Value.Boolean -> Value.Boolean(!value.value)
                else -> null
            }
            else -> null
        }
    }

    private fun evaluateArith(left: Value, right: Value, op: (Double, Double) -> Double): Value? {
        val l = toDouble(left) ?: return null
        val r = toDouble(right) ?: return null
        val result = op(l, r)

        return when {
            left is Value.Int && right is Value.Int -> Value.Int(result.toInt())
            left is Value.Float || right is Value.Float -> Value.Float(result.toFloat())
            else -> Value.Double(result)
        }
    }

    private fun evaluateCompare(left: Value, right: Value, op: (Double, Double) -> Boolean): Value? {
        val l = toDouble(left) ?: return null
        val r = toDouble(right) ?: return null
        return Value.Boolean(op(l, r))
    }

    private fun evaluateEqual(left: Value, right: Value, equal: Boolean): Value? {
        val result = when {
            left is Value.Null && right is Value.Null -> true
            left is Value.Null || right is Value.Null -> false
            left is Value.Boolean && right is Value.Boolean -> left.value == right.value
            left is Value.Int && right is Value.Int -> left.value == right.value
            left is Value.Double && right is Value.Double -> left.value == right.value
            left is Value.Float && right is Value.Float -> left.value == right.value
            left is Value.String && right is Value.String -> left.value == right.value
            else -> return null // Can't compare at compile time
        }
        return Value.Boolean(if (equal) result else !result)
    }

    private fun evaluateLogical(left: Value, right: Value, isAnd: Boolean): Value? {
        if (left !is Value.Boolean || right !is Value.Boolean) return null
        return Value.Boolean(if (isAnd) left.value && right.value else left.value || right.value)
    }

    private fun toDouble(v: Value): Double? = when (v) {
        is Value.Int -> v.value.toDouble()
        is Value.Float -> v.value.toDouble()
        is Value.Double -> v.value
        else -> null
    }
}
