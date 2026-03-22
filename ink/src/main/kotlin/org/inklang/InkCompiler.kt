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
import org.inklang.peg.CompilerPipeline

/**
 * Exception thrown when compilation fails.
 */
class CompilationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Result of plugin script validation.
 */
data class ValidationResult(val errors: List<String>) {
    fun isValid() = errors.isEmpty()
}

/**
 * Main compiler API for Quill scripts.
 * Compiles source code to InkScript instances that can be executed.
 */
class InkCompiler {
    /**
     * Parse source code to a list of AST statements.
     *
     * @param source The Quill source code
     * @return List of parsed statements
     */
    fun parse(source: String): List<Stmt> {
        val tokens = tokenize(source)
        val parser = Parser(tokens)
        return parser.parse()
    }

    /**
     * Validates that a plugin script has required enable and disable blocks.
     * @param statements The parsed AST statements
     * @return ValidationResult indicating success or list of errors
     */
    fun validatePluginScript(statements: List<Stmt>): ValidationResult {
        var hasEnable = false
        var hasDisable = false

        for (stmt in statements) {
            when (stmt) {
                is Stmt.EnableStmt -> hasEnable = true
                is Stmt.DisableStmt -> hasDisable = true
                else -> {}
            }
        }

        val errors = mutableListOf<String>()
        if (!hasEnable) errors.add("Plugin must have an 'enable {}' block")
        if (!hasDisable) errors.add("Plugin must have a 'disable {}' block")

        return ValidationResult(errors)
    }

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

    /**
     * Compile using the PEG-based pipeline (CompilerPipeline).
     *
     * This method uses the PEG-based CompilerPipeline which parses source code
     * using InkGrammar.expression and passes the result through the existing
     * IR pipeline.
     *
     * @param source The Quill source code
     * @return CompiledScript ready for execution
     * @throws CompilationException if compilation fails
     */
    fun compileWithPeg(source: String): CompiledScript {
        val pipeline = CompilerPipeline()
        return pipeline.compile(source)
    }
}
