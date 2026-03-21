package org.quill

import org.quill.ast.AstLowerer
import org.quill.ast.ConstantFolder
import org.quill.ast.LivenessAnalyzer
import org.quill.ast.RegisterAllocator
import org.quill.ast.SpillInserter
import org.quill.lang.IrCompiler
import org.quill.lang.Parser
import org.quill.lang.Token
import org.quill.lang.tokenize

/**
 * Compiler for Ink scripts.
 */
class InkCompiler {
    /**
     * Compile an Ink script from source text.
     */
    fun compile(source: String): CompiledScript {
        val tokens = tokenize(source)
        val parser = Parser(tokens)
        val statements = parser.parse()

        // Constant folding
        val folder = ConstantFolder()
        val folded = statements.map { folder.foldStmt(it) }

        // Lower to IR
        val result = AstLowerer().lower(folded)

        // SSA round-trip with optimizations
        val (ssaDeconstructed, ssaOptConstants) = IrCompiler.optimizedSsaRoundTrip(result.instrs, result.constants)
        val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, ssaOptConstants)

        // Register allocation
        val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
        val allocResult = RegisterAllocator().allocate(ranges)
        val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)

        // Compile to chunk
        val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaResult.constants))
        chunk.spillSlotCount = allocResult.spillSlotCount

        return CompiledScript(chunk)
    }
}
