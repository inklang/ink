package org.lectern

import org.lectern.ast.AstLowerer
import org.lectern.ast.ConstantFolder
import org.lectern.ast.LivenessAnalyzer
import org.lectern.ast.RegisterAllocator
import org.lectern.ast.SpillInserter
import org.lectern.lang.IrCompiler
import org.lectern.ssa.SsaBuilder
import org.lectern.ssa.SsaDeconstructor
import org.lectern.lang.Parser
import org.lectern.lang.VM
import org.lectern.lang.Value
import org.lectern.lang.tokenize
import java.io.File

fun main(args: Array<String>) {
    val filename = if (args.isNotEmpty()) args[0] else "test.lec"
    val source = File(filename).readText()
    println("=== Tokenizing ===")
    val tokens = tokenize(source)
    print(tokens)
    println("Tokens: ${tokens.size}")

    println("\n=== Parsing ===")
    val parser = Parser(tokens)
    val statements = parser.parse()
    println("Statements: ${statements.size}")
    for (stmt in statements) {
        println("  $stmt")
    }
    val folder = ConstantFolder()
    val folded = statements.map { folder.foldStmt(it) }
    println("Folded Statements: ${folded.size}")
    for (stmt in folded) {
        println("  $stmt")
    }
    val result = AstLowerer().lower(folded)

    // SSA round-trip: IR -> SSA -> IR (proves correctness)
    val ssaFunc = SsaBuilder.build(result.instrs, result.constants)
    val ssaDeconstructed = SsaDeconstructor.deconstruct(ssaFunc)
    val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, result.constants)

    val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
    val allocResult = RegisterAllocator().allocate(ranges)
    val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)
    val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaResult.constants))
    chunk.spillSlotCount = allocResult.spillSlotCount
    println("\n=== Bytecode ===")
    chunk.disassemble()
    println("\n=== Execution ===")
    val vm = VM()
    vm.execute(chunk)
}
