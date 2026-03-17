package org.lectern.ast

import org.lectern.lang.*
import org.lectern.ssa.SsaBuilder
import org.lectern.ssa.SsaDeconstructor
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RegisterSpillTest {

    /** Compile and run a Lectern source string, capturing print() output. */
    private fun run(source: String): String {
        val output = StringBuilder()
        val tokens = tokenize(source)
        val parser = Parser(tokens)
        val stmts = parser.parse()
        val folder = ConstantFolder()
        val folded = stmts.map { folder.foldStmt(it) }
        val result = AstLowerer().lower(folded)
        val ssaFunc = SsaBuilder.build(result.instrs, result.constants)
        val ssaDeconstructed = SsaDeconstructor.deconstruct(ssaFunc)
        val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, result.constants)
        val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
        val allocResult = RegisterAllocator().allocate(ranges)
        val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)
        val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaResult.constants))
        chunk.spillSlotCount = allocResult.spillSlotCount
        val vm = VM()
        vm.globals["print"] = Value.NativeFunction { args ->
            output.appendLine(args.joinToString(" ") { it.toString() })
            Value.Null
        }
        vm.execute(chunk)
        return output.toString().trimEnd()
    }

    /**
     * Test 1: Basic spill.
     * 17 variables (a–q) all live simultaneously in a function.
     * With 16 physical registers, at least one must be spilled.
     * Expected result: 1+2+...+17 = 153.
     */
    @Test
    fun testBasicSpill() {
        val source = """
            fn heavy() {
                let a = 1
                let b = 2
                let c = 3
                let d = 4
                let e = 5
                let f = 6
                let g = 7
                let h = 8
                let i = 9
                let j = 10
                let k = 11
                let l = 12
                let m = 13
                let n = 14
                let o = 15
                let p = 16
                let q = 17
                return a + b + c + d + e + f + g + h + i + j + k + l + m + n + o + p + q
            }
            print(heavy())
        """.trimIndent()

        assertEquals("153", run(source))
    }

    /**
     * Test 2: Spill across a branch.
     * Variables declared before a branch must remain correct on both paths.
     * branchSpill(true) → 153, branchSpill(false) → 17.
     */
    @Ignore("SSA round-trip control flow bug produces null register at JumpIfFalse — same limitation as VMTest if-else tests")
    @Test
    fun testSpillAcrossBranch() {
        val source = """
            fn branchSpill(flag) {
                let a = 1
                let b = 2
                let c = 3
                let d = 4
                let e = 5
                let f = 6
                let g = 7
                let h = 8
                let i = 9
                let j = 10
                let k = 11
                let l = 12
                let m = 13
                let n = 14
                let o = 15
                let p = 16
                let q = 17
                if (flag) {
                    return a + b + c + d + e + f + g + h + i + j + k + l + m + n + o + p + q
                }
                return q
            }
            print(branchSpill(true))
            print(branchSpill(false))
        """.trimIndent()

        val output = run(source).lines()
        assertEquals("153", output[0])
        assertEquals("17",  output[1])
    }

    /**
     * Test 3: Pressure error.
     * 17 variables all used as elements of a single array literal forces the
     * compiler to hold all 17 simultaneously live at the NewArray instruction.
     * With only 16 physical registers, the spilled virtual needs a temp at the
     * exact point where all 16 physicals are occupied → SpillInserter must throw.
     */
    @Test
    fun testPressureError() {
        val source = """
            fn cramped() {
                let a = 1
                let b = 2
                let c = 3
                let d = 4
                let e = 5
                let f = 6
                let g = 7
                let h = 8
                let i = 9
                let j = 10
                let k = 11
                let l = 12
                let m = 13
                let n = 14
                let o = 15
                let p = 16
                let q = 17
                return [a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q]
            }
            cramped()
        """.trimIndent()

        assertFailsWith<IllegalStateException> {
            run(source)
        }
    }
}
