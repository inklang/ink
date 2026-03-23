package org.inklang.ast

import org.inklang.lang.*
import org.inklang.opt.OptimizationPipeline
import org.inklang.opt.passes.*
import org.inklang.ssa.SsaBuilder
import org.inklang.ssa.SsaDeconstructor
import org.inklang.ssa.passes.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SsaDebugTest {

    private fun dumpIr(label: String, instrs: List<IrInstr>, constants: List<Value>) {
        println("=== $label ===")
        for ((i, instr) in instrs.withIndex()) {
            println("  $i: $instr")
        }
        println("  constants: $constants")
        println()
    }

    @Test
    fun testWhileLoopDiagnostic() {
        val source = """
            let i = 0
            while i < 3 { print(i)
            i = i + 1 }
        """.trimIndent()
        
        val tokens = tokenize(source)
        val stmts = Parser(tokens).parse()
        val folder = ConstantFolder()
        val folded = stmts.map { folder.foldStmt(it) }
        val result = AstLowerer().lower(folded)
        
        dumpIr("After lowering", result.instrs, result.constants)
        
        // Pre-SSA passes one at a time
        val cfg1 = ControlFlowGraph.build(result.instrs)
        val cf = ConstantFoldingPass().run(result.instrs, cfg1, result.constants)
        dumpIr("After pre-SSA ConstantFolding", cf.instrs, cf.constants)
        
        val cfg2 = ControlFlowGraph.build(cf.instrs)
        val iv = InductionVariablePass().run(cf.instrs, cfg2, cf.constants)
        dumpIr("After pre-SSA InductionVariable", iv.instrs, iv.constants)
        
        // SSA round-trip
        val ssaFunc = SsaBuilder.build(iv.instrs, iv.constants)
        println("=== SSA form ===")
        println(ssaFunc.dump())
        
        // SSA passes
        var currentSsa = ssaFunc
        for (pass in listOf(SsaConstantPropagationPass(), SsaGlobalValueNumberingPass(), SsaCrossBlockGvnPass(), SsaDeadCodeEliminationPass())) {
            val r = pass.run(currentSsa)
            if (r.changed) println("SSA pass ${pass.name} made changes")
            currentSsa = r.ssaFunc
        }
        println("=== SSA after passes ===")
        println(currentSsa.dump())
        
        val deconstructed = SsaDeconstructor.deconstruct(currentSsa)
        dumpIr("After SSA deconstruct", deconstructed, currentSsa.constants)
        
        // Post-SSA passes one at a time
        var currentInstrs = deconstructed
        var currentConstants = currentSsa.constants
        
        for (pass in listOf(
            DeadCodeEliminationPass(),
            CopyPropagationPass(),
            StrengthReductionPass(),
            LoopInvariantCodeMotionPass(),
            BranchOptimizationPass(),
            DeadCodeEliminationPass()
        )) {
            val cfg = ControlFlowGraph.build(currentInstrs)
            val r = pass.run(currentInstrs, cfg, currentConstants)
            if (r.changed) {
                println("Post-SSA pass ${pass.name} made changes")
                dumpIr("After ${pass.name}", r.instrs, r.constants)
            }
            currentInstrs = r.instrs
            currentConstants = r.constants
        }
        
        dumpIr("FINAL IR", currentInstrs, currentConstants)
        
        // Now compile and run
        val ranges = LivenessAnalyzer().analyze(currentInstrs)
        val allocResult = RegisterAllocator().allocate(ranges)
        val resolved = SpillInserter().insert(currentInstrs, allocResult, ranges)
        val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, currentConstants))
        chunk.spillSlotCount = allocResult.spillSlotCount

        val output = mutableListOf<String>()
        val vm = VM()
        vm.globals["print"] = Value.NativeFunction { args ->
            if (output.size > 100) error("infinite loop detected: ${output.take(10)}")
            output.add(args.joinToString(" ") { valueToString(it) })
            Value.Null
        }
        vm.execute(chunk)
        assertEquals(listOf("0", "1", "2"), output)
    }
}
