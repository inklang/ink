package org.inklang.peg

import org.inklang.lang.Stmt
import org.inklang.peg.combinators.or
import org.inklang.peg.combinators.seq
import org.inklang.peg.combinators.zeroOrMore
import org.inklang.peg.combinators.map
import org.inklang.peg.combinators.optional
import org.inklang.peg.BaseParser

/**
 * Result of attempting to merge multiple package grammars.
 */
sealed class MergeResult {
    /**
     * Merge succeeded with the combined grammar.
     */
    data class Ok(val grammar: CombinedGrammar) : MergeResult()

    /**
     * Merge failed due to conflicting keywords across packages.
     */
    data class MergeError(val conflictingKeyword: String, val packages: List<String>) : MergeResult()
}

/**
 * Combined grammar from multiple packages.
 * Allows building a parser that can handle statements and declarations from all packages.
 */
class CombinedGrammar private constructor(
    private val statements: Map<String, StatementExtension>,
    private val declarations: Map<String, DeclarationExtension>,
    private val rules: Map<String, Parser<*>>
) {
    /**
     * Builds a parser that can parse statements using all registered extensions.
     * @return A parser that returns a list of AST statements
     */
    fun buildParser(): Parser<List<Stmt>> {
        return object : BaseParser<List<Stmt>>() {
            override fun parse(input: String, position: Int): ParseResult<List<Stmt>> {
                val results = mutableListOf<Stmt>()
                var currentPos = position

                // Parse zero or more statements until we can't parse anymore
                while (currentPos < input.length) {
                    // Try to skip whitespace
                    val wsResult = parseWhitespace(input, currentPos)
                    currentPos = wsResult

                    if (currentPos >= input.length) break

                    // Try to parse a statement
                    val stmtResult = parseStatement(input, currentPos)
                    when (stmtResult) {
                        is ParseResult.Success -> {
                            results.add(stmtResult.value)
                            currentPos = stmtResult.position
                        }
                        is ParseResult.Failure -> {
                            // Stop parsing - can't parse any more statements
                            break
                        }
                    }
                }

                return ParseResult.Success(results, currentPos)
            }

            private fun parseWhitespace(input: String, position: Int): Int {
                var pos = position
                while (pos < input.length && input[pos].isWhitespace()) {
                    pos++
                }
                return pos
            }

            private fun parseStatement(input: String, position: Int): ParseResult<Stmt> {
                // Try each registered statement pattern
                for ((_, extension) in statements) {
                    val result = extension.pattern.parse(input, position)
                    when (result) {
                        is ParseResult.Success -> {
                            // Apply the lowering function
                            val stmt = extension.lower(result.value as? List<Any?> ?: listOf(result.value))
                            return ParseResult.Success(stmt, result.position)
                        }
                        is ParseResult.Failure -> {
                            // Try next statement type
                        }
                    }
                }

                // Try declaration patterns
                for ((_, extension) in declarations) {
                    val result = extension.pattern.parse(input, position)
                    when (result) {
                        is ParseResult.Success -> {
                            val stmt = extension.lower(result.value as? List<Any?> ?: listOf(result.value))
                            return ParseResult.Success(stmt, result.position)
                        }
                        is ParseResult.Failure -> {
                            // Try next declaration type
                        }
                    }
                }

                return ParseResult.Failure("statement", position)
            }
        }
    }

    companion object {
        /**
         * Creates a CombinedGrammar from multiple package grammars.
         * @param packages List of package grammars to combine
         * @return A CombinedGrammar instance
         */
        fun from(packages: List<PackageGrammar>): CombinedGrammar {
            val combinedStatements = mutableMapOf<String, StatementExtension>()
            val combinedDeclarations = mutableMapOf<String, DeclarationExtension>()
            val combinedRules = mutableMapOf<String, Parser<*>>()

            for (pkg in packages) {
                combinedStatements.putAll(pkg.statements)
                combinedDeclarations.putAll(pkg.declarations)
                combinedRules.putAll(pkg.rules)
            }

            return CombinedGrammar(combinedStatements, combinedDeclarations, combinedRules)
        }
    }
}

/**
 * Builder for combining multiple package grammars.
 * Detects conflicts and provides error handling.
 */
class CombinedGrammarBuilder {

    /**
     * Attempts to merge multiple packages' grammars.
     * Returns MergeError if there are conflicting keywords between packages.
     * @param packages The list of package grammars to merge
     * @return MergeResult indicating success or error with conflict details
     */
    fun tryMerge(packages: List<PackageGrammar>): MergeResult {
        // Check for conflicts in statement names
        val statementConflicts = findConflicts(packages.map { it.statements.keys to it })
        if (statementConflicts != null) return statementConflicts

        // Check for conflicts in declaration names
        val declarationConflicts = findConflicts(packages.map { it.declarations.keys to it })
        if (declarationConflicts != null) return declarationConflicts

        // Check for conflicts in rule names
        val ruleConflicts = findConflicts(packages.map { it.rules.keys to it })
        if (ruleConflicts != null) return ruleConflicts

        // All clear - create combined grammar
        val combinedGrammar = CombinedGrammar.from(packages)
        return MergeResult.Ok(combinedGrammar)
    }

    /**
     * Merges multiple packages' grammars.
     * Throws IllegalStateException if there are conflicting keywords.
     * @param packages The list of package grammars to merge
     * @return The combined grammar
     * @throws IllegalStateException if there are keyword conflicts
     */
    fun merge(packages: List<PackageGrammar>): CombinedGrammar {
        val result = tryMerge(packages)
        return when (result) {
            is MergeResult.Ok -> result.grammar
            is MergeResult.MergeError -> throw IllegalStateException(
                "Cannot merge grammars: keyword '${result.conflictingKeyword}' is defined in multiple packages: ${result.packages}"
            )
        }
    }

    private fun findConflicts(
        nameGroups: List<Pair<Collection<String>, PackageGrammar>>
    ): MergeResult.MergeError? {
        val nameToPackages = mutableMapOf<String, MutableList<String>>()

        for ((names, pkg) in nameGroups) {
            for (name in names) {
                nameToPackages.getOrPut(name) { mutableListOf() }.add(pkg.name)
            }
        }

        // Find names defined in multiple packages
        for ((name, pkgs) in nameToPackages) {
            if (pkgs.size > 1) {
                return MergeResult.MergeError(name, pkgs)
            }
        }

        return null
    }
}
