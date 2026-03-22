package org.inklang.peg.ink.statements

import org.inklang.lang.Expr
import org.inklang.lang.Param
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
 * Parser for function declaration: fn name(args) = expr
 */
fun fnDeclaration(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwFn = literalToken("fn")
        private val lparen = literalToken("(")
        private val rparen = literalToken(")")
        private val comma = literalToken(",")
        private val assign = literalToken("=")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val fnResult = kwFn.parse(input, position)
            val fnSuccess = fnResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("fn", position)

            var currentPos = fnSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse function name
            val nameResult = identifier().parse(input, currentPos)
            if (nameResult is ParseResult.Failure) {
                return ParseResult.Failure("identifier", currentPos)
            }
            val nameSuccess = nameResult as ParseResult.Success<PegToken>
            val funcName = nameSuccess.value.lexeme
            currentPos = nameSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse opening parenthesis
            val lparenResult = lparen.parse(input, currentPos)
            if (lparenResult is ParseResult.Failure) {
                return ParseResult.Failure("(", currentPos)
            }
            currentPos = (lparenResult as ParseResult.Success).position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse parameter list using Param type
            val params = mutableListOf<Param>()
            var expectComma = false

            while (currentPos < input.length) {
                // Check for closing paren
                val rparenCheck = rparen.parse(input, currentPos)
                if (rparenCheck is ParseResult.Success) {
                    currentPos = rparenCheck.position
                    break
                }

                if (expectComma) {
                    val commaResult = comma.parse(input, currentPos)
                    if (commaResult is ParseResult.Failure) {
                        return ParseResult.Failure(",", currentPos)
                    }
                    currentPos = (commaResult as ParseResult.Success).position
                    // Skip whitespace
                    while (currentPos < input.length && input[currentPos].isWhitespace()) {
                        currentPos++
                    }
                }

                // Parse parameter: name
                val paramNameResult = identifier().parse(input, currentPos)
                if (paramNameResult is ParseResult.Failure) {
                    if (params.isEmpty()) {
                        // Empty param list is OK
                        break
                    }
                    return ParseResult.Failure("identifier", currentPos)
                }
                val paramNameSuccess = paramNameResult as ParseResult.Success<PegToken>
                val paramName = paramNameSuccess.value.lexeme
                currentPos = paramNameSuccess.position

                // Create param Token
                val sourcePos = SourcePosition.fromOffset(input, position)
                val nameToken = Token(TokenType.IDENTIFIER, paramName, sourcePos.line, sourcePos.column)
                params.add(Param(
                    annotations = emptyList(),
                    name = nameToken,
                    type = null,  // TODO: type annotations
                    defaultValue = null
                ))

                expectComma = true
            }

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse return type annotation (optional)
            var returnType: Token? = null
            // For now, skip type annotation - just look for =
            val assignResult = assign.parse(input, currentPos)
            if (assignResult is ParseResult.Failure) {
                return ParseResult.Failure("=", currentPos)
            }
            currentPos = (assignResult as ParseResult.Success).position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse body expression
            val bodyResult = expressionParser().parse(input, currentPos)
            if (bodyResult is ParseResult.Failure) {
                return ParseResult.Failure("expression", currentPos)
            }
            val bodySuccess = bodyResult as ParseResult.Success<Expr>

            // Wrap the expression body in a BlockStmt with return
            val fnSourcePos = SourcePosition.fromOffset(input, position)
            val fnToken = Token(TokenType.KW_FN, "fn", fnSourcePos.line, fnSourcePos.column)
            val nameToken = Token(TokenType.IDENTIFIER, funcName, fnSourcePos.line, fnSourcePos.column)
            val bodyBlock = Stmt.BlockStmt(listOf(Stmt.ReturnStmt(bodySuccess.value)))

            return ParseResult.Success(
                Stmt.FuncStmt(
                    annotations = emptyList(),
                    name = nameToken,
                    params = params.toList(),
                    returnType = returnType,
                    body = bodyBlock
                ),
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
