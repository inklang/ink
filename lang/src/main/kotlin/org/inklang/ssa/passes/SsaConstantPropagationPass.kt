package org.inklang.ssa.passes

import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.ssa.*

/**
 * Sparse Conditional Constant Propagation (SCCP) pass.
 *
 * In SSA form, each variable is defined exactly once, making constant propagation
 * much simpler and more effective. This pass:
 * 1. Tracks which SSA values hold constant values
 * 2. Propagates constants through phi functions where possible
 * 3. Evaluates constant expressions at compile time
 * 4. Eliminates dead branches based on constant conditions
 */
class SsaConstantPropagationPass : SsaOptPass {
    override val name = "SsaConstantPropagation"

    // Current SSA function being processed
    private lateinit var currentFunc: SsaFunction

    // Map from SSA value to its constant value (if known)
    private val constants = mutableMapOf<SsaValue, Value>()

    // Set of blocks that are reachable
    private val reachableBlocks = mutableSetOf<Int>()

    override fun run(ssaFunc: SsaFunction): SsaOptResult {
        currentFunc = ssaFunc
        constants.clear()
        reachableBlocks.clear()

        var changed = false

        // Iterate until fixed point
        var iterationChanged = true
        while (iterationChanged) {
            iterationChanged = false

            // Propagate constants through blocks
            for (block in ssaFunc.blocks) {
                // Skip unreachable blocks (after first iteration)
                if (reachableBlocks.isNotEmpty() && block.id !in reachableBlocks) {
                    continue
                }

                // Entry block is always reachable
                if (block.id == ssaFunc.entryBlock) {
                    reachableBlocks.add(block.id)
                }

                // Process phi functions
                for (phi in block.phiFunctions) {
                    val constValue = evaluatePhi(phi)
                    if (constValue != null && constants[phi.result] != constValue) {
                        constants[phi.result] = constValue
                        iterationChanged = true
                        changed = true
                    }
                }

                // Process instructions
                for (instr in block.instrs) {
                    val (newConstants, instrChanged) = processInstr(instr)
                    if (instrChanged) {
                        iterationChanged = true
                        changed = true
                    }
                    for ((value, const) in newConstants) {
                        if (constants[value] != const) {
                            constants[value] = const
                        }
                    }
                }

                // Update reachability based on branch conditions
                val lastInstr = block.instrs.lastOrNull()
                if (lastInstr is SsaInstr.JumpIfFalse) {
                    val condConst = constants[lastInstr.src]
                    if (condConst is Value.Boolean) {
                        // Find successor blocks
                        val successors = block.successors.toList()
                        if (successors.size >= 1) {
                            if (condConst.value) {
                                // Condition is always true - only fall-through is reachable
                                // (JumpIfFalse jumps when false, so fall-through when true)
                                // The fall-through is typically the next block
                                val fallThrough = successors.firstOrNull { it == block.id + 1 }
                                if (fallThrough != null) {
                                    if (reachableBlocks.add(fallThrough)) iterationChanged = true
                                }
                            } else {
                                // Condition is always false - only jump target is reachable
                                val jumpTarget = successors.firstOrNull { it != block.id + 1 }
                                if (jumpTarget != null) {
                                    if (reachableBlocks.add(jumpTarget)) iterationChanged = true
                                }
                            }
                        }
                    } else {
                        // Unknown condition - both successors are reachable
                        for (succ in block.successors) {
                            if (reachableBlocks.add(succ)) iterationChanged = true
                        }
                    }
                } else {
                    // Non-conditional or unknown - all successors are reachable
                    for (succ in block.successors) {
                        if (reachableBlocks.add(succ)) iterationChanged = true
                    }
                }
            }
        }

        // If no constants were discovered, return unchanged
        if (!changed) {
            return SsaOptResult(ssaFunc, false)
        }

        // Apply optimizations: replace constant expressions with LoadImm
        val newBlocks = ssaFunc.blocks.map { block ->
            if (block.id !in reachableBlocks) {
                // Keep unreachable blocks as-is (they'll be removed by DCE)
                block
            } else {
                optimizeBlock(block)
            }
        }

        val newFunc = SsaFunction(newBlocks, ssaFunc.constants, ssaFunc.entryBlock, ssaFunc.exitBlocks, ssaFunc.cfg)
        return SsaOptResult(newFunc, true)
    }

    /**
     * Evaluate a phi function to see if all operands have the same constant value.
     */
    private fun evaluatePhi(phi: PhiFunction): Value? {
        var constValue: Value? = null
        for ((_, operand) in phi.operands) {
            val opConst = constants[operand]
            if (opConst == null) {
                return null  // Non-constant operand
            }
            if (constValue == null) {
                constValue = opConst
            } else if (constValue != opConst) {
                return null  // Different constants
            }
        }
        return constValue
    }

    /**
     * Process an instruction and return any new constant bindings.
     */
    private fun processInstr(instr: SsaInstr): Pair<Map<SsaValue, Value>, Boolean> {
        var changed = false
        val newConstants = mutableMapOf<SsaValue, Value>()

        when (instr) {
            is SsaInstr.LoadImm -> {
                val const = currentFunc.constants.getOrNull(instr.constIndex)
                if (const != null) {
                    newConstants[instr.definedValue] = const
                    if (constants[instr.definedValue] != const) {
                        changed = true
                    }
                }
            }
            is SsaInstr.Move -> {
                val srcConst = constants[instr.src]
                if (srcConst != null) {
                    newConstants[instr.definedValue] = srcConst
                    if (constants[instr.definedValue] != srcConst) {
                        changed = true
                    }
                }
            }
            is SsaInstr.BinaryOp -> {
                val leftConst = constants[instr.src1]
                val rightConst = constants[instr.src2]
                if (leftConst != null && rightConst != null) {
                    val result = evaluateBinary(instr.op, leftConst, rightConst)
                    if (result != null) {
                        newConstants[instr.definedValue] = result
                        if (constants[instr.definedValue] != result) {
                            changed = true
                        }
                    }
                }
            }
            is SsaInstr.UnaryOp -> {
                val srcConst = constants[instr.src]
                if (srcConst != null) {
                    val result = evaluateUnary(instr.op, srcConst)
                    if (result != null) {
                        newConstants[instr.definedValue] = result
                        if (constants[instr.definedValue] != result) {
                            changed = true
                        }
                    }
                }
            }
            else -> {}
        }

        return Pair(newConstants, changed)
    }

    /**
     * Optimize a block by replacing constant expressions with LoadImm.
     */
    private fun optimizeBlock(block: SsaBlock): SsaBlock {
        val newBlock = SsaBlock(block.id, block.label)

        // Copy phi functions (they may be optimized in future iterations)
        newBlock.phiFunctions.addAll(block.phiFunctions)
        newBlock.predecessors.addAll(block.predecessors)
        newBlock.successors.addAll(block.successors)

        for (instr in block.instrs) {
            val optimized = optimizeInstr(instr)
            newBlock.instrs.addAll(optimized)
        }

        return newBlock
    }

    /**
     * Optimize an instruction, potentially replacing it with LoadImm.
     */
    private fun optimizeInstr(instr: SsaInstr): List<SsaInstr> {
        return when (instr) {
            is SsaInstr.BinaryOp -> {
                val leftConst = constants[instr.src1]
                val rightConst = constants[instr.src2]
                if (leftConst != null && rightConst != null) {
                    val result = evaluateBinary(instr.op, leftConst, rightConst)
                    if (result != null) {
                        val constIndex = currentFunc.constants.indexOf(result)
                            .takeIf { it >= 0 } ?: currentFunc.constants.size
                        listOf(SsaInstr.LoadImm(instr.definedValue, constIndex))
                    } else {
                        listOf(instr)
                    }
                } else {
                    listOf(instr)
                }
            }
            is SsaInstr.UnaryOp -> {
                val srcConst = constants[instr.src]
                if (srcConst != null) {
                    val result = evaluateUnary(instr.op, srcConst)
                    if (result != null) {
                        val constIndex = currentFunc.constants.indexOf(result)
                            .takeIf { it >= 0 } ?: currentFunc.constants.size
                        listOf(SsaInstr.LoadImm(instr.definedValue, constIndex))
                    } else {
                        listOf(instr)
                    }
                } else {
                    listOf(instr)
                }
            }
            is SsaInstr.Move -> {
                // If source is constant, replace with LoadImm
                val srcConst = constants[instr.src]
                if (srcConst != null) {
                    val constIndex = currentFunc.constants.indexOf(srcConst)
                        .takeIf { it >= 0 } ?: currentFunc.constants.size
                    listOf(SsaInstr.LoadImm(instr.definedValue, constIndex))
                } else {
                    listOf(instr)
                }
            }
            is SsaInstr.JumpIfFalse -> {
                val condConst = constants[instr.src]
                if (condConst is Value.Boolean) {
                    if (condConst.value) {
                        // Condition is always true - remove the jump (fall through)
                        emptyList()
                    } else {
                        // Condition is always false - convert to unconditional jump
                        listOf(SsaInstr.Jump(instr.target))
                    }
                } else {
                    listOf(instr)
                }
            }
            else -> listOf(instr)
        }
    }

    private fun evaluateBinary(op: TokenType, left: Value, right: Value): Value? {
        return when (op) {
            TokenType.PLUS -> evaluateArith(left, right) { a, b -> a + b }
            TokenType.MINUS -> evaluateArith(left, right) { a, b -> a - b }
            TokenType.STAR -> evaluateArith(left, right) { a, b -> a * b }
            TokenType.SLASH -> {
                val r = toDouble(right) ?: return null
                if (r == 0.0) return null
                evaluateArith(left, right) { a, b -> a / b }
            }
            TokenType.PERCENT -> {
                val r = toDouble(right) ?: return null
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
            else -> return null
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
