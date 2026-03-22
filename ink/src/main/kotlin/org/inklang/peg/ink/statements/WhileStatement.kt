package org.inklang.peg.ink.statements

import org.inklang.lang.Expr
import org.inklang.lang.Stmt
import org.inklang.lang.Token
import org.inklang.lang.TokenType
import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser
import org.inklang.peg.PegToken
import org.inklang.peg.ink.expressions.literalToken
import org.inklang.peg.util.SourcePosition

/**
 * Parser for while statement: while expr { stmt* }
 */
fun whileStatement(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwWhile = literalToken("while")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val whileResult = kwWhile.parse(input, position)
            val whileSuccess = whileResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("while", position)

            var currentPos = whileSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse condition expression
            val condResult = expressionParser().parse(input, currentPos)
            if (condResult is ParseResult.Failure) {
                return ParseResult.Failure("expression", currentPos)
            }
            val condSuccess = condResult as ParseResult.Success<Expr>
            currentPos = condSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse body block
            val bodyResult = blockStatement(expressionParser).parse(input, currentPos)
            if (bodyResult is ParseResult.Failure) {
                return ParseResult.Failure("{", currentPos)
            }
            val bodySuccess = bodyResult as ParseResult.Success<Stmt.BlockStmt>

            val sourcePos = SourcePosition.fromOffset(input, position)
            val whileToken = Token(TokenType.KW_WHILE, "while", sourcePos.line, sourcePos.column)
            return ParseResult.Success(
                Stmt.WhileStmt(condSuccess.value, bodySuccess.value),
                bodySuccess.position
            )
        }
    }
}

/**
 * Parser for for-range statement: for x in expr { stmt* }
 */
fun forStatement(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwFor = literalToken("for")
        private val kwIn = literalToken("in")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val forResult = kwFor.parse(input, position)
            val forSuccess = forResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("for", position)

            var currentPos = forSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse variable name (identifier)
            val identResult = identifier().parse(input, currentPos)
            if (identResult is ParseResult.Failure) {
                return ParseResult.Failure("identifier", currentPos)
            }
            val identSuccess = identResult as ParseResult.Success<PegToken>
            val varName = identSuccess.value.lexeme
            currentPos = identSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse 'in' keyword
            val inResult = kwIn.parse(input, currentPos)
            if (inResult is ParseResult.Failure) {
                return ParseResult.Failure("in", currentPos)
            }
            val inSuccess = inResult as ParseResult.Success<PegToken>
            currentPos = inSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse iterable expression
            val iterResult = expressionParser().parse(input, currentPos)
            if (iterResult is ParseResult.Failure) {
                return ParseResult.Failure("expression", currentPos)
            }
            val iterSuccess = iterResult as ParseResult.Success<Expr>
            currentPos = iterSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse body block
            val bodyResult = blockStatement(expressionParser).parse(input, currentPos)
            if (bodyResult is ParseResult.Failure) {
                return ParseResult.Failure("{", currentPos)
            }
            val bodySuccess = bodyResult as ParseResult.Success<Stmt.BlockStmt>

            val sourcePos = SourcePosition.fromOffset(input, position)
            val forToken = Token(TokenType.KW_FOR, "for", sourcePos.line, sourcePos.column)
            val varToken = Token(TokenType.IDENTIFIER, varName, sourcePos.line, sourcePos.column)
            return ParseResult.Success(
                Stmt.ForRangeStmt(varToken, iterSuccess.value, bodySuccess.value),
                bodySuccess.position
            )
        }

        private fun identifier(): Parser<PegToken> {
            return object : BaseParser<PegToken>() {
                private val regex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*")
                override fun parse(input: String, position: Int): ParseResult<PegToken> {
                    val match = regex.find(input, position)
                    return if (match != null && match.range.first == position) {
                        val pegTokenPos = SourcePosition.fromOffset(input, position)
                        val pegToken = PegToken("IDENTIFIER", match.value, pegTokenPos)
                        ParseResult.Success(pegToken, match.range.last + 1)
                    } else {
                        ParseResult.Failure("identifier", position)
                    }
                }
            }
        }
    }
}
