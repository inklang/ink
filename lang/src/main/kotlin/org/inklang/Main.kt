package org.inklang

import org.inklang.ast.AstLowerer
import org.inklang.ast.ConstantFolder
import org.inklang.ast.LivenessAnalyzer
import org.inklang.ast.RegisterAllocator
import org.inklang.ast.SpillInserter
import org.inklang.lang.IrCompiler
import org.inklang.grammar.PackageRegistry
import org.inklang.grammar.PluginParserRegistry
import org.inklang.lang.Parser
import org.inklang.lang.VM
import org.inklang.lang.Value
import org.inklang.lang.tokenize
import java.io.File

fun main(args: Array<String>) {
    val filename = if (args.isNotEmpty()) args[0] else "test.lec"
    val source = File(filename).readText()
    println("=== Tokenizing ===")
    val tokens = tokenize(source)
    print(tokens)
    println("Tokens: ${tokens.size}")

    // Load plugin grammars if a plugins directory is provided
    val pluginRegistry = if (args.size > 1) {
        val pluginsDir = File(args[1])
        val pkgRegistry = PackageRegistry()
        pkgRegistry.loadAll(pluginsDir)
        PluginParserRegistry(pkgRegistry.merge())
    } else null

    println("\n=== Parsing ===")
    val parser = Parser(tokens, pluginRegistry)
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

    // SSA round-trip with optimizations: IR -> SSA -> IR
    val (ssaDeconstructed, ssaOptConstants) = IrCompiler.optimizedSsaRoundTrip(result.instrs, result.constants)
    val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, ssaOptConstants)

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
