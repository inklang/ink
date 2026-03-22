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
 * Parser for identifier/variable references.
 * Matches valid identifiers and returns Expr.VariableExpr.
 */
fun identifier(): Parser<Expr.VariableExpr> {
    return object : BaseParser<Expr.VariableExpr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr.VariableExpr> {
            if (position >= input.length) {
                return ParseResult.Failure("identifier", position)
            }

            val c = input[position]
            if (!isIdentifierStart(c)) {
                return ParseResult.Failure("identifier", position)
            }

            var end = position + 1
            while (end < input.length && isIdentifierChar(input[end])) {
                end++
            }

            val lexeme = input.substring(position, end)
            val sourcePos = SourcePosition.fromOffset(input, position)
            val token = Token(TokenType.IDENTIFIER, lexeme, sourcePos.line, sourcePos.column)

            return ParseResult.Success(Expr.VariableExpr(token), end)
        }

        private fun isIdentifierStart(c: Char): Boolean {
            return c.isLetter() || c == '_'
        }

        private fun isIdentifierChar(c: Char): Boolean {
            return c.isLetter() || c.isDigit() || c == '_'
        }
    }
}

/**
 * Parser that recognizes keywords but returns them as identifiers.
 * Used for primary expressions where keywords like 'true', 'false', 'null'
 * are handled by the literal parser.
 */
fun identifierOrKeyword(): Parser<Expr.VariableExpr> {
    return object : BaseParser<Expr.VariableExpr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr.VariableExpr> {
            if (position >= input.length) {
                return ParseResult.Failure("identifier", position)
            }

            val c = input[position]
            if (!isIdentifierStart(c)) {
                return ParseResult.Failure("identifier", position)
            }

            var end = position + 1
            while (end < input.length && isIdentifierChar(input[end])) {
                end++
            }

            val lexeme = input.substring(position, end)
            val sourcePos = SourcePosition.fromOffset(input, position)
            val token = Token(TokenType.IDENTIFIER, lexeme, sourcePos.line, sourcePos.column)

            return ParseResult.Success(Expr.VariableExpr(token), end)
        }

        private fun isIdentifierStart(c: Char): Boolean {
            return c.isLetter() || c == '_'
        }

        private fun isIdentifierChar(c: Char): Boolean {
            return c.isLetter() || c.isDigit() || c == '_'
        }
    }
}
