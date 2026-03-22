package org.inklang.peg.ink.expressions

import org.inklang.lang.Expr
import org.inklang.lang.Token
import org.inklang.lang.TokenType
import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser
import org.inklang.peg.PegToken
import org.inklang.peg.util.SourcePosition

/**
 * Parser for parenthesized expressions.
 * Handles: (expr)
 */
fun grouping(sub: Parser<Expr>): Parser<Expr> {
    return object : BaseParser<Expr>() {
        private val lparen = literalToken("(")
        private val rparen = literalToken(")")

        override fun parse(input: String, position: Int): ParseResult<Expr> {
            val lparenResult = lparen.parse(input, position)
            val lparenSuccess = lparenResult as? ParseResult.Success<PegToken>
                ?: return sub.parse(input, position)

            val contentResult = sub.parse(input, lparenSuccess.position)
            val contentSuccess = contentResult as? ParseResult.Success<Expr>
                ?: return contentResult

            val rparenResult = rparen.parse(input, contentSuccess.position)
            val rparenSuccess = rparenResult as? ParseResult.Success<PegToken>
            if (rparenSuccess == null) {
                val failurePos = when (rparenResult) {
                    is ParseResult.Failure -> rparenResult.position
                    else -> contentSuccess.position
                }
                return ParseResult.Failure(")", failurePos)
            }

            val sourcePos = SourcePosition.fromOffset(input, position)
            val parenToken = Token(TokenType.L_PAREN, "()", sourcePos.line, sourcePos.column)
            return ParseResult.Success(
                Expr.GroupExpr(contentSuccess.value),
                rparenSuccess.position
            )
        }
    }
}
