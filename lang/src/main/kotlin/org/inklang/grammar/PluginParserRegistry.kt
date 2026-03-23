package org.inklang.grammar

import org.inklang.lang.Token
import org.inklang.lang.TokenType

/**
 * Bridge between the base Ink parser and the DynamicParser for plugin grammars.
 *
 * Constructed from a MergedGrammar (produced by PackageRegistry.merge()),
 * this registry tells the base parser which identifiers are plugin declaration
 * keywords, and parses them on demand by bridging the base token stream into
 * DynamicParser's token format.
 */
class PluginParserRegistry(private val merged: MergedGrammar) {

    private val grammar = GrammarPackage(
        version = 1,
        packageName = "__merged__",
        keywords = merged.keywords.toList(),
        rules = merged.rules,
        declarations = merged.declarations
    )

    private val declKeywords: Set<String> = merged.declarations.map { it.keyword }.toSet()

    fun isPluginKeyword(lexeme: String): Boolean = lexeme in declKeywords

    /**
     * Parse a plugin declaration starting at [startPos] in the base [tokens] list.
     *
     * Returns a pair of (parsed CST declaration, number of base tokens consumed).
     * The base parser should advance its cursor by the consumed count.
     */
    fun parseDeclaration(tokens: List<Token>, startPos: Int): Pair<CstNode.Declaration, Int> {
        val bridged = bridgeTokens(tokens, startPos)
        val stream = TokenStream(bridged)
        val parser = DynamicParser(grammar)
        val decl = parser.parseOneDeclaration(stream)
        // How many base tokens were consumed? The bridge maps 1:1 with base tokens
        // (skipping ASI semicolons that DynamicParser doesn't know about)
        val consumed = stream.pos - countSkipped(tokens, startPos, stream.pos)
        return Pair(decl, actualBaseTokensConsumed(tokens, startPos, stream.pos, bridged))
    }

    /**
     * Bridge base tokens starting at [startPos] into DynTokens.
     * Maps base TokenTypes to DynTokenTypes. Skips ASI-inserted semicolons.
     * Identifiers that match plugin keywords are mapped to KEYWORD.
     */
    private fun bridgeTokens(tokens: List<Token>, startPos: Int): List<DynToken> {
        val result = mutableListOf<DynToken>()
        var i = startPos
        while (i < tokens.size) {
            val tok = tokens[i]
            val dynType = when (tok.type) {
                TokenType.IDENTIFIER -> {
                    if (tok.lexeme in merged.keywords) DynTokenType.KEYWORD
                    else DynTokenType.IDENTIFIER
                }
                TokenType.L_BRACE -> DynTokenType.LBRACE
                TokenType.R_BRACE -> DynTokenType.RBRACE
                TokenType.KW_INT -> DynTokenType.INT
                TokenType.KW_DOUBLE -> DynTokenType.FLOAT
                TokenType.KW_STRING -> DynTokenType.STRING
                TokenType.EOF -> DynTokenType.EOF
                TokenType.SEMICOLON -> {
                    // Skip ASI semicolons — DynamicParser doesn't expect them
                    i++
                    continue
                }
                else -> {
                    // Unknown token type — emit EOF to stop DynamicParser cleanly
                    result.add(DynToken(DynTokenType.EOF, "", tok.line, tok.column))
                    break
                }
            }
            result.add(DynToken(dynType, tok.lexeme, tok.line, tok.column))
            if (dynType == DynTokenType.EOF) break
            i++
        }
        if (result.isEmpty() || result.last().type != DynTokenType.EOF) {
            val last = if (tokens.isNotEmpty()) tokens.last() else Token(TokenType.EOF, "", 0, 0)
            result.add(DynToken(DynTokenType.EOF, "", last.line, last.column))
        }
        return result
    }

    /**
     * Calculate how many base tokens were actually consumed given the bridge consumed
     * [bridgeConsumed] DynTokens. We need to account for skipped semicolons.
     */
    private fun actualBaseTokensConsumed(
        tokens: List<Token>,
        startPos: Int,
        bridgeConsumed: Int,
        bridged: List<DynToken>
    ): Int {
        // Walk through base tokens, counting how many map to the first bridgeConsumed DynTokens
        var baseCount = 0
        var dynCount = 0
        var i = startPos
        while (i < tokens.size && dynCount < bridgeConsumed) {
            val tok = tokens[i]
            if (tok.type == TokenType.SEMICOLON) {
                // Skipped in bridge — still consumed from base
                baseCount++
                i++
                continue
            }
            if (tok.type == TokenType.EOF) break
            // Check if this maps to an unknown type that caused early EOF
            val dynType = when (tok.type) {
                TokenType.IDENTIFIER, TokenType.L_BRACE, TokenType.R_BRACE,
                TokenType.KW_INT, TokenType.KW_DOUBLE, TokenType.KW_STRING -> true
                else -> false
            }
            if (!dynType) break
            baseCount++
            dynCount++
            i++
        }
        return baseCount
    }

    private fun countSkipped(tokens: List<Token>, startPos: Int, bridgePos: Int): Int = 0
}
