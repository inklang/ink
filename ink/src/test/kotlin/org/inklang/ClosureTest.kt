package org.inklang

import org.inklang.ast.*
import org.inklang.lang.*
import kotlin.test.Test
import kotlin.test.assertEquals

private fun compileAndRun(source: String): List<String> {
    val output = mutableListOf<String>()
    val tokens = tokenize(source)
    val stmts = Parser(tokens).parse()
    val folder = ConstantFolder()
    val folded = stmts.map { folder.foldStmt(it) }
    val result = AstLowerer().lower(folded)

    val (ssaDeconstructed, ssaOptConstants) = IrCompiler.optimizedSsaRoundTrip(result.instrs, result.constants)
    val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, ssaOptConstants)

    val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
    val allocResult = RegisterAllocator().allocate(ranges)
    val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)
    val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaResult.constants))
    chunk.spillSlotCount = allocResult.spillSlotCount

    val vm = VM()
    vm.globals["print"] = Value.NativeFunction { args ->
        output.add(args.joinToString(" ") { valueToString(it) })
        Value.Null
    }
    vm.execute(chunk)
    return output
}

class ClosureTest {

    @Test
    fun `basic capture`() {
        val output = compileAndRun("""
            fn outer() {
                let x = 10
                let f = () -> { x }
                print(f())
            }
        """.trimIndent())
        assertEquals(listOf("10"), output)
    }

    @Test
    fun `multiple captures`() {
        val output = compileAndRun("""
            fn outer() {
                let a = 1
                let b = 2
                let f = () -> { a + b }
                print(f())
            }
        """.trimIndent())
        assertEquals(listOf("3"), output)
    }

    @Test
    fun `parameter shadows outer`() {
        val output = compileAndRun("""
            fn outer() {
                let x = 10
                let f = (x) -> { x }
                print(f(5))
            }
        """.trimIndent())
        assertEquals(listOf("5"), output)
    }
}