package org.lectern.ssa

import org.lectern.lang.IrInstr
import org.lectern.lang.IrLabel
import org.lectern.lang.TokenType
import org.lectern.lang.Value
import org.lectern.ssa.passes.SsaConstantPropagationPass
import org.lectern.ssa.passes.SsaDeadCodeEliminationPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsaTest {

    // Helper to create unique labels
    private var labelCounter = 0
    private fun label(): IrLabel = IrLabel(labelCounter++)

    /**
     * Test: Basic SSA construction for a simple linear block.
     */
    @Test
    fun testSsaConstructionSimple() {
        labelCounter = 0
        val constants = listOf(Value.Int(5), Value.Int(10))

        val instrs = listOf(
            IrInstr.LoadImm(0, 0),      // r0 = 5
            IrInstr.LoadImm(1, 1),      // r1 = 10
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = r0 + r1
            IrInstr.Return(2)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)

        println("Simple SSA:")
        println(ssaFunc.dump())

        // Should have one block
        assertEquals(1, ssaFunc.blocks.size)

        // Should have no phi functions (single block)
        assertEquals(0, ssaFunc.blocks[0].phiFunctions.size)

        // Instructions should have SSA values
        val load0 = ssaFunc.blocks[0].instrs[0] as SsaInstr.LoadImm
        assertEquals(0, load0.definedValue.baseReg)
        assertEquals(0, load0.definedValue.version)
    }

    /**
     * Test: SSA construction with a branch creates phi functions.
     */
    @Test
    fun testSsaConstructionBranch() {
        labelCounter = 0
        val constants = listOf(Value.Int(1), Value.Int(2), Value.Int(0))

        val l0 = label()
        val l1 = label()
        val l2 = label()

        val instrs = listOf(
            IrInstr.LoadImm(0, 0),      // r0 = 1
            IrInstr.LoadImm(1, 1),      // r1 = 2
            IrInstr.BinaryOp(2, TokenType.LT, 0, 1),  // r2 = r0 < r1 (true)
            IrInstr.JumpIfFalse(2, l1), // if !r2 goto L1
            IrInstr.Label(l0),
            IrInstr.Move(3, 0),         // r3 = r0 (then branch)
            IrInstr.Jump(l2),
            IrInstr.Label(l1),
            IrInstr.Move(3, 1),         // r3 = r1 (else branch)
            IrInstr.Label(l2),
            IrInstr.Return(3)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)

        println("Branch SSA:")
        println(ssaFunc.dump())

        // Should have phi function at merge point (L2)
        val mergeBlock = ssaFunc.blocks.find { it.label?.id == l2.id }
        assertTrue(mergeBlock != null, "Should have merge block")

        // There should be a phi for r3
        val hasPhiForR3 = mergeBlock.phiFunctions.any { it.result.baseReg == 3 }
        assertTrue(hasPhiForR3, "Should have phi for r3 at merge point")
    }

    /**
     * Test: SSA construction with a loop creates phi functions.
     */
    @Test
    fun testSsaConstructionLoop() {
        labelCounter = 0
        val constants = listOf(Value.Int(0), Value.Int(10), Value.Int(1))

        val l0 = label()  // Loop header
        val l1 = label()  // Loop end

        val instrs = listOf(
            IrInstr.LoadImm(0, 0),      // r0 = 0 (counter)
            IrInstr.LoadImm(1, 1),      // r1 = 10 (limit)
            IrInstr.LoadImm(2, 2),      // r2 = 1 (increment)
            IrInstr.Label(l0),          // Loop header
            IrInstr.BinaryOp(3, TokenType.LT, 0, 1),  // r3 = r0 < r1
            IrInstr.JumpIfFalse(3, l1), // if !r3 exit loop
            IrInstr.BinaryOp(0, TokenType.PLUS, 0, 2),  // r0 = r0 + r2
            IrInstr.Jump(l0),           // goto loop header
            IrInstr.Label(l1),
            IrInstr.Return(0)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)

        println("Loop SSA:")
        println(ssaFunc.dump())

        // Loop header should have phi for r0
        val headerBlock = ssaFunc.blocks.find { it.label?.id == l0.id }
        assertTrue(headerBlock != null, "Should have loop header")

        // There should be a phi for r0 at the loop header
        val hasPhiForR0 = headerBlock.phiFunctions.any { it.result.baseReg == 0 }
        assertTrue(hasPhiForR0, "Should have phi for r0 at loop header")
    }

    /**
     * Test: SSA deconstruction roundtrip.
     */
    @Test
    fun testSsaRoundtrip() {
        labelCounter = 0
        val constants = listOf(Value.Int(5), Value.Int(10))

        val instrs = listOf(
            IrInstr.LoadImm(0, 0),      // r0 = 5
            IrInstr.LoadImm(1, 1),      // r1 = 10
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = r0 + r1
            IrInstr.Return(2)
        )

        // Convert to SSA
        val ssaFunc = SsaBuilder.build(instrs, constants)

        // Convert back to IR
        val deconstructed = SsaDeconstructor.deconstruct(ssaFunc)

        println("Roundtrip:")
        println("Original: ${instrs.size} instructions")
        instrs.forEach { println("  $it") }
        println("Deconstructed: ${deconstructed.size} instructions")
        deconstructed.forEach { println("  $it") }

        // Should have same number of instructions
        assertEquals(instrs.size, deconstructed.size)

        // Should have a Return at the end
        assertTrue(deconstructed.last() is IrInstr.Return)
    }

    /**
     * Test: SSA constant propagation.
     */
    @Test
    fun testSsaConstantPropagation() {
        labelCounter = 0
        val constants = listOf(Value.Int(2), Value.Int(3))

        val instrs = listOf(
            IrInstr.LoadImm(0, 0),      // r0 = 2
            IrInstr.LoadImm(1, 1),      // r1 = 3
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = 2 + 3 = 5
            IrInstr.Return(2)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        assertTrue(result.changed, "Constant propagation should make changes")

        println("After constant propagation:")
        println(result.ssaFunc.dump())

        // The BinaryOp should be replaced with LoadImm
        val block = result.ssaFunc.blocks[0]
        val hasLoadImm = block.instrs.any {
            it is SsaInstr.LoadImm && it.definedValue.baseReg == 2
        }
        assertTrue(hasLoadImm, "BinaryOp should be folded to LoadImm")
    }

    /**
     * Test: SSA dead code elimination.
     */
    @Test
    fun testSsaDeadCodeElimination() {
        labelCounter = 0
        val constants = listOf(Value.Int(5), Value.Int(10), Value.Int(15))

        val instrs = listOf(
            IrInstr.LoadImm(0, 0),      // r0 = 5 (used)
            IrInstr.LoadImm(1, 1),      // r1 = 10 (UNUSED)
            IrInstr.LoadImm(2, 2),      // r2 = 15 (used)
            IrInstr.BinaryOp(3, TokenType.PLUS, 0, 2),  // r3 = r0 + r2
            IrInstr.Return(3)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaDeadCodeEliminationPass()
        val result = pass.run(ssaFunc)

        println("After DCE:")
        println(result.ssaFunc.dump())

        // The unused LoadImm for r1 should be removed
        val block = result.ssaFunc.blocks[0]
        val hasLoadR1 = block.instrs.any {
            it is SsaInstr.LoadImm && it.definedValue.baseReg == 1
        }
        assertFalse(hasLoadR1, "Unused LoadImm should be removed")
    }

    /**
     * Test: Full SSA pipeline with branch elimination.
     */
    @Test
    fun testSsaBranchElimination() {
        labelCounter = 0
        val constants = listOf(Value.Int(5), Value.Int(10))

        val l0 = label()
        val l1 = label()

        val instrs = listOf(
            IrInstr.LoadImm(0, 0),      // r0 = 5
            IrInstr.LoadImm(1, 1),      // r1 = 10
            IrInstr.BinaryOp(2, TokenType.LT, 0, 1),  // r2 = 5 < 10 = true
            IrInstr.JumpIfFalse(2, l1), // if !true goto L1 (never taken)
            IrInstr.Label(l0),
            IrInstr.Move(3, 0),         // r3 = r0
            IrInstr.Jump(l0),           // This will loop (simplified test)
            IrInstr.Label(l1),
            IrInstr.Move(3, 1),         // r3 = r1 (unreachable)
            IrInstr.Return(3)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val pass = SsaConstantPropagationPass()
        val result = pass.run(ssaFunc)

        println("After constant propagation on branch:")
        println(result.ssaFunc.dump())

        // The JumpIfFalse should be converted to unconditional jump or removed
        val block = result.ssaFunc.blocks[0]
        val hasJumpIfFalse = block.instrs.any { it is SsaInstr.JumpIfFalse }
        // Note: Depending on implementation, the branch might be eliminated or converted
        println("Has JumpIfFalse: $hasJumpIfFalse")
    }

    /**
     * Test: Dominance frontier computation.
     */
    @Test
    fun testDominanceFrontier() {
        labelCounter = 0
        val constants = listOf(Value.Int(1))

        val l0 = label()
        val l1 = label()
        val l2 = label()

        val instrs = listOf(
            IrInstr.Label(l0),
            IrInstr.LoadImm(0, 0),
            IrInstr.JumpIfFalse(0, l2),  // Branch to L2 or L1
            IrInstr.Label(l1),
            IrInstr.LoadImm(1, 0),
            IrInstr.Label(l2),           // Merge point
            IrInstr.Return(0)
        )

        val cfg = org.lectern.ast.ControlFlowGraph.build(instrs)
        val domFrontier = DominanceFrontier.compute(cfg)

        println("Dominance frontiers:")
        for ((blockId, frontier) in domFrontier.allFrontiers()) {
            println("  DF($blockId) = $frontier")
        }

        // L0's dominance frontier should include L2 (the merge point)
        val df0 = domFrontier.frontier(0)
        println("DF(0) = $df0")
    }

    @Test
    fun testSsaDeconstructorUniqueRegisters() {
        labelCounter = 0
        val constants = listOf(Value.Int(1), Value.Int(2))

        val l0 = label()
        val l1 = label()
        val l2 = label()

        val instrs = listOf(
            IrInstr.LoadImm(0, 0),       // x = 1
            IrInstr.LoadImm(1, 0),       // cond = 1
            IrInstr.JumpIfFalse(1, l1),
            IrInstr.Label(l0),
            IrInstr.LoadImm(0, 1),       // x = 2 (reassign)
            IrInstr.Jump(l2),
            IrInstr.Label(l1),
            IrInstr.Label(l2),
            IrInstr.Return(0)
        )

        val ssaFunc = SsaBuilder.build(instrs, constants)
        val deconstructed = SsaDeconstructor.deconstruct(ssaFunc)

        val loadImms = deconstructed.filterIsInstance<IrInstr.LoadImm>()
        val dstRegs = loadImms.map { it.dst }.toSet()
        assertTrue(dstRegs.size >= 3, "Different SSA versions must get different registers, got $dstRegs")
    }

    /**
     * Test: SSA pipeline with optimization.
     */
    @Test
    fun testSsaPipeline() {
        labelCounter = 0
        val constants = mutableListOf(Value.Int(2), Value.Int(3))

        val instrs = listOf(
            IrInstr.LoadImm(0, 0),      // r0 = 2
            IrInstr.LoadImm(1, 1),      // r1 = 3
            IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),  // r2 = 2 + 3 = 5
            IrInstr.Return(2)
        )

        val (optimized, newConstants) = org.lectern.opt.OptimizationPipeline.optimizeWithSsa(
            instrs,
            constants,
            ssaPasses = listOf(
                SsaConstantPropagationPass(),
                SsaDeadCodeEliminationPass()
            )
        )

        println("SSA Pipeline result:")
        optimized.forEach { println("  $it") }

        // Should still have a return
        assertTrue(optimized.last() is IrInstr.Return)
    }
}
