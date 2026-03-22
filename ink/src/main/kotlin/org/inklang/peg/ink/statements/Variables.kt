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
 * Parser for variable declarations: val name = expr or var name = expr
 */
fun varDeclaration(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwVal = literalToken("val")
        private val kwVar = literalToken("var")
        private val assign = literalToken("=")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            // Try val first
            val valResult = kwVal.parse(input, position)
            var isVar = false
            var kwSuccess: ParseResult.Success<PegToken>? = null

            when (valResult) {
                is ParseResult.Success -> {
                    kwSuccess = valResult
                    isVar = false
                }
                is ParseResult.Failure -> {
                    // Try var
                    val varResult = kwVar.parse(input, position)
                    when (varResult) {
                        is ParseResult.Success -> {
                            kwSuccess = varResult
                            isVar = true
                        }
                        is ParseResult.Failure -> {
                            return ParseResult.Failure("val/var", position)
                        }
                    }
                }
            }

            var currentPos = kwSuccess!!.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse variable name
            val nameResult = identifier().parse(input, currentPos)
            if (nameResult is ParseResult.Failure) {
                return ParseResult.Failure("identifier", currentPos)
            }
            val nameSuccess = nameResult as ParseResult.Success<PegToken>
            val varName = nameSuccess.value.lexeme
            currentPos = nameSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse optional initializer
            val assignResult = assign.parse(input, currentPos)
            var initializer: Expr? = null

            if (assignResult is ParseResult.Success) {
                currentPos = assignResult.position

                // Skip whitespace
                while (currentPos < input.length && input[currentPos].isWhitespace()) {
                    currentPos++
                }

                // Parse initializer expression
                val exprResult = expressionParser().parse(input, currentPos)
                when (exprResult) {
                    is ParseResult.Success -> {
                        initializer = exprResult.value
                        currentPos = exprResult.position
                    }
                    is ParseResult.Failure -> {
                        // Expression failed to parse, but assignment was present
                        // Leave initializer as null
                    }
                }
            }

            val sourcePos = SourcePosition.fromOffset(input, position)
            val keyword = if (isVar) {
                Token(TokenType.KW_CONST, "const", sourcePos.line, sourcePos.column)
            } else {
                Token(TokenType.KW_LET, "let", sourcePos.line, sourcePos.column)
            }
            val name = Token(TokenType.IDENTIFIER, varName, sourcePos.line, sourcePos.column)

            return ParseResult.Success(
                Stmt.VarStmt(
                    annotations = emptyList(),
                    keyword = keyword,
                    name = name,
                    value = initializer
                ),
                currentPos
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
