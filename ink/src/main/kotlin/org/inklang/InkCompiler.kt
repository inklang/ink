package org.inklang

import org.inklang.ast.AstLowerer
import org.inklang.ast.ConstantFolder
import org.inklang.ast.LivenessAnalyzer
import org.inklang.ast.RegisterAllocator
import org.inklang.ast.SpillInserter
import org.inklang.lang.ConfigFieldDef
import org.inklang.lang.Expr
import org.inklang.lang.IrCompiler
import org.inklang.lang.Parser
import org.inklang.lang.Stmt
import org.inklang.lang.tokenize

/**
 * Exception thrown when compilation fails.
 */
class CompilationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Main compiler API for Quill scripts.
 * Compiles source code to InkScript instances that can be executed.
 */
class InkCompiler {
    /**
     * Compile Quill source code to a InkScript.
     *
     * @param source The Quill source code
     * @param name Script name (defaults to "main")
     * @return InkScript ready for execution
     * @throws CompilationException if compilation fails
     */
    fun compile(source: String, name: String = "main"): InkScript {
        try {
            // Tokenize
            val tokens = tokenize(source)

            // Parse
            val parser = Parser(tokens)
            val statements = parser.parse()

            // Check annotations (before constant folding)
            AnnotationChecker().check(statements)

            // Constant fold
            val folder = ConstantFolder()
            val folded = statements.map { folder.foldStmt(it) }

            // Lower to IR
            val result = AstLowerer().lower(folded)

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

            // Extract config field definitions for runtime loading
            val configDefinitions = mutableMapOf<String, List<ConfigFieldDef>>()
            for (stmt in statements) {
                if (stmt is Stmt.ConfigStmt) {
                    val fields = stmt.fields.map { field ->
                        ConfigFieldDef(
                            name = field.name.lexeme,
                            type = field.type.lexeme,
                            defaultValue = field.defaultValue?.let { expr ->
                                // Evaluate constant expression to Value
                                val folded = folder.fold(expr)
                                (folded as? Expr.LiteralExpr)?.literal
                            }
                        )
                    }
                    configDefinitions[stmt.name.lexeme] = fields
                }
            }

            return InkScript(name, chunk, configDefinitions)
        } catch (e: CompilationException) {
            throw e
        } catch (e: Exception) {
            throw CompilationException("Compilation failed: ${e.message}", e)
        }
    }
}
