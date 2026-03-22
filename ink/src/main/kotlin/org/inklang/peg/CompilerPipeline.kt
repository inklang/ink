package org.inklang.peg

import org.inklang.CompilationException
import org.inklang.CompiledScript
import org.inklang.ast.AstLowerer
import org.inklang.ast.ConstantFolder
import org.inklang.ast.LivenessAnalyzer
import org.inklang.ast.RegisterAllocator
import org.inklang.ast.SpillInserter
import org.inklang.lang.IrCompiler
import org.inklang.lang.Stmt
import org.inklang.lang.Value
import org.inklang.lang.VM
import org.inklang.lang.valueToString
import org.inklang.peg.ink.InkGrammar

/**
 * Bridge from PEG parsing to the existing IR → bytecode → VM pipeline.
 *
 * Takes source code, parses it using the PEG grammar (which produces AST statements),
 * then passes those through the existing lowering and compilation steps.
 */
class CompilerPipeline {
    private val inkGrammar = InkGrammar()

    /**
     * Parse source code to a list of AST statements.
     *
     * Uses InkGrammar.program to parse multiple statements.
     *
     * @param source The source code to parse
     * @return List of parsed statements
     */
    fun parse(source: String): List<Stmt> {
        val result = inkGrammar.parseProgram(source)
        return when (result) {
            is ParseResult.Success -> {
                result.value
            }
            is ParseResult.Failure -> {
                throw CompilationException(
                    "Parse error at position ${result.position}: expected ${result.expected}"
                )
            }
        }
    }

    /**
     * Parse source code using a provided CombinedGrammar.
     *
     * @param source The source code to parse
     * @param grammar The grammar to use (from CombinedGrammarBuilder.merge)
     * @return List of parsed statements
     */
    fun parse(source: String, grammar: CombinedGrammar): List<Stmt> {
        val parser = grammar.buildParser()
        val result = parser.parse(source, 0)
        return when (result) {
            is ParseResult.Success -> result.value
            is ParseResult.Failure -> {
                throw CompilationException(
                    "Parse error at position ${result.position}: expected ${result.expected}"
                )
            }
        }
    }

    /**
     * Lower AST statements to IR instructions using the existing AstLowerer.
     *
     * @param stmts The AST statements to lower
     * @return The lowered result containing IR instructions and constants
     */
    fun lower(stmts: List<Stmt>): AstLowerer.LoweredResult {
        // Apply constant folding first
        val folder = ConstantFolder()
        val folded = stmts.map { folder.foldStmt(it) }

        // Lower to IR
        return AstLowerer().lower(folded)
    }

    /**
     * Compile source code through the full pipeline: parse → lower → SSA → register alloc → bytecode.
     *
     * Uses InkGrammar.expression to parse the source as a single expression.
     *
     * @param source The source code to compile
     * @return A CompiledScript ready for execution
     */
    fun compile(source: String): CompiledScript {
        // Parse the expression
        val statements = parse(source)

        // Lower to IR
        val result = lower(statements)

        // SSA round-trip with optimizations
        val (ssaDeconstructed, ssaOptConstants) = IrCompiler.optimizedSsaRoundTrip(
            result.instrs,
            result.constants
        )
        val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, ssaOptConstants)

        // Liveness analysis and register allocation
        val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
        val allocResult = RegisterAllocator().allocate(ranges)
        val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)

        // Compile to bytecode
        val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaResult.constants))
        chunk.spillSlotCount = allocResult.spillSlotCount

        return CompiledScript(chunk)
    }

    /**
     * Compile and run source code, returning captured output as a string.
     *
     * @param source The source code to compile and run
     * @return The captured stdout output from execution
     */
    fun compileAndRun(source: String): String {
        val script = compile(source)
        val output = StringBuilder()

        // Create a simple VM with print captured
        val vm = VM()
        vm.globals["print"] = Value.NativeFunction { args ->
            val msg = args.joinToString(" ") { valueToString(it) }
            output.appendLine(msg)
            Value.Null
        }

        vm.execute(script.getChunk())
        return output.toString()
    }
}
