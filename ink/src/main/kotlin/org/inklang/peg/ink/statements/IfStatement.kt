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
 * Parser for if statement: if expr { stmt* } else { stmt* }
 * Supports: if expr { }, if expr { } else { }, if expr { } else if expr { }
 */
fun ifStatement(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwIf = literalToken("if")
        private val kwElse = literalToken("else")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val ifResult = kwIf.parse(input, position)
            val ifSuccess = ifResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("if", position)

            var currentPos = ifSuccess.position

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

            // Parse then block
            val thenResult = blockStatement(expressionParser).parse(input, currentPos)
            if (thenResult is ParseResult.Failure) {
                return ParseResult.Failure("{", currentPos)
            }
            val thenSuccess = thenResult as ParseResult.Success<Stmt.BlockStmt>
            currentPos = thenSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Check for else clause
            val elseResult = kwElse.parse(input, currentPos)
            if (elseResult is ParseResult.Failure) {
                // No else clause
                val sourcePos = SourcePosition.fromOffset(input, position)
                val ifToken = Token(TokenType.KW_IF, "if", sourcePos.line, sourcePos.column)
                return ParseResult.Success(
                    Stmt.IfStmt(condSuccess.value, thenSuccess.value, null),
                    currentPos
                )
            }
            val elseSuccess = elseResult as ParseResult.Success<PegToken>
            currentPos = elseSuccess.position

            // Skip whitespace after else
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Check if it's else if or else block
            val elseIfCheck = kwIf.parse(input, currentPos)
            when (elseIfCheck) {
                is ParseResult.Success -> {
                    // It's else if - recursively parse the else-if as an IfStmt
                    val elseIfResult = parse(input, elseSuccess.position)
                    if (elseIfResult is ParseResult.Success) {
                        val elseIfStmt = elseIfResult.value as Stmt.IfStmt
                        val sourcePos = SourcePosition.fromOffset(input, position)
                        val ifToken = Token(TokenType.KW_IF, "if", sourcePos.line, sourcePos.column)
                        return ParseResult.Success(
                            Stmt.IfStmt(
                                condSuccess.value,
                                thenSuccess.value,
                                Stmt.ElseBranch.ElseIf(elseIfStmt)
                            ),
                            elseIfResult.position
                        )
                    }
                }
                is ParseResult.Failure -> {
                    // It's else block - parse the else block
                    val elseBlockResult = blockStatement(expressionParser).parse(input, currentPos)
                    when (elseBlockResult) {
                        is ParseResult.Success -> {
                            val elseBlock = elseBlockResult.value as Stmt.BlockStmt
                            val sourcePos = SourcePosition.fromOffset(input, position)
                            val ifToken = Token(TokenType.KW_IF, "if", sourcePos.line, sourcePos.column)
                            return ParseResult.Success(
                                Stmt.IfStmt(
                                    condSuccess.value,
                                    thenSuccess.value,
                                    Stmt.ElseBranch.Else(elseBlock)
                                ),
                                elseBlockResult.position
                            )
                        }
                        is ParseResult.Failure -> {
                            return ParseResult.Failure("{", currentPos)
                        }
                    }
                }
            }

            return ParseResult.Failure("if/else", currentPos)
        }
    }
}
