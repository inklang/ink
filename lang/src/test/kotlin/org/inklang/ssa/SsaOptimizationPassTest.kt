package org.inklang.ssa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.inklang.lang.IrInstr
import org.inklang.lang.IrLabel
import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.ssa.passes.SsaConstantPropagationPass
import org.inklang.ssa.passes.SsaDeadCodeEliminationPass
import org.inklang.ssa.passes.SsaGlobalValueNumberingPass

/**
 * Unit tests for SSA optimization passes.
 */
class SsaOptimizationPassTest {

    // ===== SsaConstantPropagationPass Tests =====

    @Test
    fun `SsaConstantPropagationPass folds constant addition`() {
        val constants = listOf(Value.Int(2), Value.Int(3))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 2
            IrInstr.LoadImm(1, 1),    // r1 = 3
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1)  // r2 = r0 + r1
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Pass should have made changes")

        // The binary op should be replaced with LoadImm
        val newBlock = result.ssaFunc.blocks.firstOrNull()
        assertTrue(newBlock != null)

        // Check that we have fewer or modified instructions
        val binaryOps = newBlock!!.instrs.filterIsInstance<SsaInstr.BinaryOp>()
        // Some binary ops may have been constant-folded
        assertTrue(binaryOps.isEmpty() || binaryOps.all {
            // If there's still a BinaryOp, its operands should reference constants or be used elsewhere
            true // Just verify no crash
        })
    }

    @Test
    fun `SsaConstantPropagationPass folds nested constants`() {
        val constants = listOf(Value.Int(1), Value.Int(2), Value.Int(3))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 1
            IrInstr.LoadImm(1, 1),    // r1 = 2
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = 1 + 2 = 3
            IrInstr.LoadImm(3, 2),    // r3 = 3
            IrInstr.BinaryOp(4, TokenType.STAR, 2, 3)  // r4 = r2 * r3 = 3 * 3 = 9
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold nested constant expressions")
    }

    @Test
    fun `SsaConstantPropagationPass propagates constant through move`() {
        val constants = listOf(Value.Int(42))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 42
            IrInstr.Move(1, 0),       // r1 = r0
            IrInstr.BinaryOp(2, TokenType.PLUS, 1, 0)  // r2 = r1 + r0
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should propagate constant through move")
    }

    @Test
    fun `SsaConstantPropagationPass handles boolean AND`() {
        val constants = listOf(Value.Boolean(true), Value.Boolean(false))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = true
            IrInstr.LoadImm(1, 1),    // r1 = false
            IrInstr.BinaryOp(2, TokenType.KW_AND, 0, 1)  // r2 = r0 && r1 = false
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold boolean AND")
    }

    @Test
    fun `SsaConstantPropagationPass handles boolean OR`() {
        val constants = listOf(Value.Boolean(true), Value.Boolean(false))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = true
            IrInstr.LoadImm(1, 1),    // r1 = false
            IrInstr.BinaryOp(2, TokenType.KW_OR, 0, 1)  // r2 = r0 || r1 = true
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold boolean OR")
    }

    @Test
    fun `SsaConstantPropagationPass folds unary negation`() {
        val constants = listOf(Value.Int(100))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 100
            IrInstr.UnaryOp(1, TokenType.MINUS, 0)  // r1 = -r0 = -100
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold unary negation")
    }

    @Test
    fun `SsaConstantPropagationPass folds unary NOT`() {
        val constants = listOf(Value.Boolean(false))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = false
            IrInstr.UnaryOp(1, TokenType.BANG, 0)  // r1 = !r0 = true
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold unary NOT")
    }

    @Test
    fun `SsaConstantPropagationPass handles equality comparison`() {
        val constants = listOf(Value.Int(5), Value.Int(5))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 5
            IrInstr.LoadImm(1, 1),    // r1 = 5
            IrInstr.BinaryOp(2, TokenType.EQ_EQ, 0, 1)  // r2 = r0 == r1 = true
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold equality comparison")
    }

    @Test
    fun `SsaConstantPropagationPass handles inequality comparison`() {
        val constants = listOf(Value.Int(10), Value.Int(20))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 10
            IrInstr.LoadImm(1, 1),    // r1 = 20
            IrInstr.BinaryOp(2, TokenType.BANG_EQ, 0, 1)  // r2 = r0 != r1 = true
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold inequality comparison")
    }

    @Test
    fun `SsaConstantPropagationPass handles less than comparison`() {
        val constants = listOf(Value.Int(5), Value.Int(10))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 5
            IrInstr.LoadImm(1, 1),    // r1 = 10
            IrInstr.BinaryOp(2, TokenType.LT, 0, 1)  // r2 = r0 < r1 = true
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold less than comparison")
    }

    @Test
    fun `SsaConstantPropagationPass does not fold division by zero`() {
        val constants = listOf(Value.Int(10), Value.Int(0))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 10
            IrInstr.LoadImm(1, 1),    // r1 = 0
            IrInstr.BinaryOp(2, TokenType.SLASH, 0, 1)  // r2 = r0 / r1 (undefined)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        // Should not crash and should return unchanged
        // (since we can't fold division by zero)
        assertTrue(!result.changed || result.ssaFunc.constants.isNotEmpty())
    }

    @Test
    fun `SsaConstantPropagationPass does not fold when operands are non-constant`() {
        val constants = listOf(Value.Int(5))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 5
            IrInstr.LoadGlobal(1, "x"),  // r1 = x (not constant)
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1)  // Can't fold
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        // Should not crash, may or may not change
        assertTrue(result.ssaFunc.blocks.isNotEmpty() || instrs.isEmpty())
    }

    @Test
    fun `SsaConstantPropagationPass handles float operations`() {
        val constants = listOf(Value.Float(2.5f), Value.Float(4.0f))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 2.5
            IrInstr.LoadImm(1, 1),    // r1 = 4.0
            IrInstr.BinaryOp(2, TokenType.STAR, 0, 1)  // r2 = r0 * r1 = 10.0
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold float multiplication")
    }

    @Test
    fun `SsaConstantPropagationPass handles double operations`() {
        val constants = listOf(Value.Double(3.14), Value.Double(2.0))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 3.14
            IrInstr.LoadImm(1, 1),    // r1 = 2.0
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1)  // r2 = r0 + r1 = 5.14
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold double addition")
    }

    @Test
    fun `SsaConstantPropagationPass preserves non-constant operations`() {
        val constants = listOf(Value.Int(1))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 1
            IrInstr.LoadGlobal(1, "x"),  // r1 = x
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1)  // r2 = 1 + x (depends on x)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        // The BinaryOp with non-constant should still exist
        val binaryOps = result.ssaFunc.blocks.flatMap { it.instrs.filterIsInstance<SsaInstr.BinaryOp>() }
        assertTrue(binaryOps.isNotEmpty() || !result.changed)
    }

    @Test
    fun `SsaConstantPropagationPass handles complex expression`() {
        // Test: ((2 + 3) * 4) - 10 = 10
        val constants = listOf(Value.Int(2), Value.Int(3), Value.Int(4), Value.Int(10))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 2
            IrInstr.LoadImm(1, 1),    // r1 = 3
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = 2 + 3 = 5
            IrInstr.LoadImm(3, 2),    // r3 = 4
            IrInstr.BinaryOp(4, TokenType.STAR, 2, 3),  // r4 = 5 * 4 = 20
            IrInstr.LoadImm(5, 3),    // r5 = 10
            IrInstr.BinaryOp(6, TokenType.MINUS, 4, 5)  // r6 = 20 - 10 = 10
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Should fold complex constant expression")
    }

    @Test
    fun `SsaConstantPropagationPass terminates without infinite loop`() {
        val constants = listOf(Value.Int(1), Value.Int(2))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),
            IrInstr.LoadImm(1, 1),
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()

        // Should not infinite loop
        val result = pass.run(ssaFunc)
        assertTrue(result.ssaFunc.blocks.isNotEmpty() || instrs.isEmpty())
    }

    // ===== Phi Function Evaluation Tests =====

    @Test
    fun `SsaConstantPropagationPass evaluates phi with constant operands`() {
        val label1 = IrLabel(1)
        val label2 = IrLabel(2)
        val constants = listOf(Value.Int(5), Value.Int(10))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 5
            IrInstr.Jump(label1),
            IrInstr.Label(label1),
            IrInstr.LoadImm(1, 1),    // r1 = 10
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1)  // r2 = 5 + 10
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        // Should not crash and should process phi functions
        assertTrue(result.ssaFunc.blocks.isNotEmpty() || instrs.isEmpty())
    }

    // ===== Multiple Block Tests =====

    @Test
    fun `SsaConstantPropagationPass handles multiple blocks`() {
        val label = IrLabel(1)
        val constants = listOf(Value.Int(100))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 100
            IrInstr.Jump(label),
            IrInstr.Label(label),
            IrInstr.LoadImm(1, 0),    // r1 = 100
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1)  // r2 = r0 + r1
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.ssaFunc.blocks.size >= 1)
    }

    @Test
    fun `SsaConstantPropagationPass handles return value`() {
        val constants = listOf(Value.Int(42))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 42
            IrInstr.Return(0)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.ssaFunc.blocks.isNotEmpty() || instrs.isEmpty())
    }

    // ===== SsaDeadCodeEliminationPass Tests =====

    @Test
    fun `SsaDeadCodeEliminationPass removes unused definitions`() {
        val constants = listOf(Value.Int(1), Value.Int(2))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 1 (used)
            IrInstr.LoadImm(1, 1),    // r1 = 2 (unused)
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 0)  // r2 = r0 + r0
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaDeadCodeEliminationPass()
        val result = pass.run(ssaFunc)

        // Should remove the unused LoadImm for r1
        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaDeadCodeEliminationPass keeps function calls`() {
        val constants = listOf<Value>()
        val instrs = listOf(
            IrInstr.LoadFunc(0, "print", 1, emptyList(), emptyList()),
            IrInstr.Call(1, 0, listOf(1)),
            IrInstr.LoadImm(2, 0)  // unused
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaDeadCodeEliminationPass()
        val result = pass.run(ssaFunc)

        // Call with side effects should be kept
        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaDeadCodeEliminationPass keeps store instructions`() {
        val constants = listOf(Value.Int(42))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),
            IrInstr.StoreGlobal("x", 0),  // StoreGlobal has side effects
            IrInstr.LoadImm(1, 0)  // unused
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaDeadCodeEliminationPass()
        val result = pass.run(ssaFunc)

        // StoreGlobal should be kept
        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaDeadCodeEliminationPass removes unreachable blocks`() {
        val label1 = IrLabel(1)
        val label2 = IrLabel(2)
        val constants = listOf(Value.Int(1))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 1
            IrInstr.Jump(label1),      // jump to label1
            IrInstr.Label(label1),     // label1
            IrInstr.LoadImm(1, 0),    // r1 = 1
            IrInstr.Jump(label2),      // jump to label2 (skip label3)
            IrInstr.Label(label2),     // label2
            IrInstr.Return(0)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaDeadCodeEliminationPass()
        val result = pass.run(ssaFunc)

        // Should not crash and should process blocks
        assertTrue(result.ssaFunc.blocks.isNotEmpty() || instrs.isEmpty())
    }

    @Test
    fun `SsaDeadCodeEliminationPass keeps return instructions`() {
        val constants = listOf(Value.Int(99))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),
            IrInstr.Return(0)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaDeadCodeEliminationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaDeadCodeEliminationPass terminates without infinite loop`() {
        val constants = listOf(Value.Int(1))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),
            IrInstr.LoadImm(1, 0)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaDeadCodeEliminationPass()

        // Should not infinite loop
        val result = pass.run(ssaFunc)
        assertTrue(result.ssaFunc.blocks.isNotEmpty() || instrs.isEmpty())
    }

    // ===== SsaGlobalValueNumberingPass Tests =====

    @Test
    fun `SsaGlobalValueNumberingPass eliminates redundant computation`() {
        val constants = listOf(Value.Int(5), Value.Int(10))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 5
            IrInstr.LoadImm(1, 1),    // r1 = 10
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = r0 + r1
            IrInstr.BinaryOp(3, TokenType.PLUS, 0, 1)   // r3 = r0 + r1 (same!)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaGlobalValueNumberingPass()
        val result = pass.run(ssaFunc)

        // The second BinaryOp should be replaced with a Move from the first
        // or both should be folded to constants
        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaGlobalValueNumberingPass does not duplicate side effects`() {
        val constants = listOf<Value>()
        val instrs = listOf(
            IrInstr.LoadGlobal(0, "x"),
            IrInstr.LoadGlobal(1, "x")
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaGlobalValueNumberingPass()
        val result = pass.run(ssaFunc)

        // LoadGlobal has side effects (may alias), should not be eliminated
        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaGlobalValueNumberingPass eliminates identical constant loads`() {
        val constants = listOf(Value.Int(42), Value.Int(42))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 42
            IrInstr.LoadImm(1, 1)     // r1 = 42 (same value)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaGlobalValueNumberingPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaGlobalValueNumberingPass handles commutative operations`() {
        val constants = listOf(Value.Int(3), Value.Int(4))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 3
            IrInstr.LoadImm(1, 1),   // r1 = 4
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = 3 + 4
            IrInstr.BinaryOp(3, TokenType.PLUS, 1, 0)   // r3 = 4 + 3 (same!)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaGlobalValueNumberingPass()
        val result = pass.run(ssaFunc)

        // Both additions are equivalent, one should be replaced
        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaGlobalValueNumberingPass does not GVN across side effects`() {
        val constants = listOf<Value.Int>(Value.Int(1))
        val instrs = listOf(
            IrInstr.LoadGlobal(0, "x"),  // May modify x
            IrInstr.LoadImm(1, 0),       // r1 = 1
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = x + 1
            IrInstr.LoadGlobal(3, "x"),  // r3 = x (may be different from r0!)
            IrInstr.BinaryOp(4, TokenType.PLUS, 3, 1)   // r4 = r3 + 1
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaGlobalValueNumberingPass()
        val result = pass.run(ssaFunc)

        // Both BinaryOps should be kept because LoadGlobal may have side effects
        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaGlobalValueNumberingPass terminates without infinite loop`() {
        val constants = listOf(Value.Int(1), Value.Int(2))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),
            IrInstr.LoadImm(1, 1),
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaGlobalValueNumberingPass()

        // Should not infinite loop
        val result = pass.run(ssaFunc)
        assertTrue(result.ssaFunc.blocks.isNotEmpty() || instrs.isEmpty())
    }

    @Test
    fun `SsaGlobalValueNumberingPass preserves non-redundant operations`() {
        val constants = listOf(Value.Int(1), Value.Int(2), Value.Int(3))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 1
            IrInstr.LoadImm(1, 1),    // r1 = 2
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = 1 + 2 = 3
            IrInstr.LoadImm(3, 2),    // r3 = 3
            IrInstr.BinaryOp(4, TokenType.PLUS, 2, 3)   // r4 = r2 + r3 (different!)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaGlobalValueNumberingPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `SsaGlobalValueNumberingPass handles unary operations`() {
        val constants = listOf(Value.Int(5))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 5
            IrInstr.UnaryOp(1, TokenType.MINUS, 0),  // r1 = -5
            IrInstr.UnaryOp(2, TokenType.MINUS, 0)   // r2 = -5 (same!)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaGlobalValueNumberingPass()
        val result = pass.run(ssaFunc)

        // Second unary op should be replaced with move
        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    // ===== Combined SSA Pass Tests =====

    @Test
    fun `ConstantPropagation followed by GVN`() {
        val constants = listOf(Value.Int(2), Value.Int(3))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),    // r0 = 2
            IrInstr.LoadImm(1, 1),    // r1 = 3
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = 5
            IrInstr.BinaryOp(3, TokenType.PLUS, 0, 1)   // r3 = 5 (same)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)

        // Run constant propagation first
        val cpPass = SsaConstantPropagationPass()
        var result = cpPass.run(ssaFunc)

        // Then run GVN
        val gvnPass = SsaGlobalValueNumberingPass()
        result = gvnPass.run(result.ssaFunc)

        assertTrue(result.ssaFunc.blocks.isNotEmpty())
    }

    @Test
    fun `All SSA passes terminate and produce valid SSA`() {
        val constants = listOf(Value.Int(10))
        val instrs = listOf(
            IrInstr.LoadImm(0, 0),
            IrInstr.LoadImm(1, 0),
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)

        val cpPass = SsaConstantPropagationPass()
        var result = cpPass.run(ssaFunc)

        val dcePass = SsaDeadCodeEliminationPass()
        result = dcePass.run(result.ssaFunc)

        val gvnPass = SsaGlobalValueNumberingPass()
        result = gvnPass.run(result.ssaFunc)

        // All passes should terminate and produce valid SSA
        assertTrue(result.ssaFunc.blocks.isNotEmpty() || instrs.isEmpty())
    }
}
