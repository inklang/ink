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
 * Parser for block statements: { stmt* }
 * A block contains zero or more statements enclosed in braces.
 */
fun blockStatement(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val lbrace = literalToken("{")
        private val rbrace = literalToken("}")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val lbraceResult = lbrace.parse(input, position)
            val lbraceSuccess = lbraceResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("{", position)

            val statements = mutableListOf<Stmt>()
            var currentPos = lbraceSuccess.position

            // Parse statements until we hit the closing brace
            while (currentPos < input.length) {
                // Skip whitespace
                while (currentPos < input.length && input[currentPos].isWhitespace()) {
                    currentPos++
                }

                if (currentPos >= input.length) break

                // Check for closing brace
                val rbraceCheck = rbrace.parse(input, currentPos)
                if (rbraceCheck is ParseResult.Success) {
                    val sourcePos = SourcePosition.fromOffset(input, position)
                    val lbraceToken = Token(TokenType.L_BRACE, "{", sourcePos.line, sourcePos.column)
                    return ParseResult.Success(Stmt.BlockStmt(statements), rbraceCheck.position)
                }

                // Try to parse a statement
                val stmtResult = parseStatement(input, currentPos, expressionParser)
                when (stmtResult) {
                    is ParseResult.Success -> {
                        statements.add(stmtResult.value)
                        currentPos = stmtResult.position
                    }
                    is ParseResult.Failure -> {
                        // Skip this token and continue
                        currentPos++
                    }
                }
            }

            return ParseResult.Failure("}", currentPos)
        }

        private fun parseStatement(input: String, position: Int, exprParser: () -> Parser<Expr>): ParseResult<Stmt> {
            // Skip whitespace
            var pos = position
            while (pos < input.length && input[pos].isWhitespace()) {
                pos++
            }

            if (pos >= input.length) {
                return ParseResult.Failure("statement", position)
            }

            // Try return statement
            val returnResult = returnStatement(exprParser).parse(input, pos)
            if (returnResult is ParseResult.Success) return returnResult

            // Try var declaration
            val varResult = varDeclaration(exprParser).parse(input, pos)
            if (varResult is ParseResult.Success) return varResult

            // Try fn declaration
            val fnResult = fnDeclaration(exprParser).parse(input, pos)
            if (fnResult is ParseResult.Success) return fnResult

            // Try if statement
            val ifResult = ifStatement(exprParser).parse(input, pos)
            if (ifResult is ParseResult.Success) return ifResult

            // Try while statement
            val whileResult = whileStatement(exprParser).parse(input, pos)
            if (whileResult is ParseResult.Success) return whileResult

            // Try for statement
            val forResult = forStatement(exprParser).parse(input, pos)
            if (forResult is ParseResult.Success) return forResult

            // Try break/next
            val breakResult = breakStatement().parse(input, pos)
            if (breakResult is ParseResult.Success) return breakResult

            val nextResult = nextStatement().parse(input, pos)
            if (nextResult is ParseResult.Success) return nextResult

            // Try block statement (nested)
            val blockResult = blockStatement(exprParser).parse(input, pos)
            if (blockResult is ParseResult.Success) return blockResult

            // Try expression as statement
            val exprResult = exprParser().parse(input, pos)
            when (exprResult) {
                is ParseResult.Success -> {
                    val sourcePos = SourcePosition.fromOffset(input, position)
                    val exprStmt = Stmt.ExprStmt(exprResult.value)
                    return ParseResult.Success(exprStmt, exprResult.position)
                }
                is ParseResult.Failure -> {
                    return exprResult
                }
            }
        }
    }
}

/**
 * Parser for return statement: return expr?
 */
fun returnStatement(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwReturn = literalToken("return")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val kwResult = kwReturn.parse(input, position)
            val kwSuccess = kwResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("return", position)

            var currentPos = kwSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Check for expression or just return
            if (currentPos >= input.length || input[currentPos] == '}' || input[currentPos] == '\n') {
                val sourcePos = SourcePosition.fromOffset(input, position)
                val returnToken = Token(TokenType.KW_RETURN, "return", sourcePos.line, sourcePos.column)
                return ParseResult.Success(Stmt.ReturnStmt(null), currentPos)
            }

            // Try to parse an expression
            val exprResult = expressionParser().parse(input, currentPos)
            when (exprResult) {
                is ParseResult.Success -> {
                    val sourcePos = SourcePosition.fromOffset(input, position)
                    val returnToken = Token(TokenType.KW_RETURN, "return", sourcePos.line, sourcePos.column)
                    return ParseResult.Success(Stmt.ReturnStmt(exprResult.value), exprResult.position)
                }
                is ParseResult.Failure -> {
                    val sourcePos = SourcePosition.fromOffset(input, position)
                    val returnToken = Token(TokenType.KW_RETURN, "return", sourcePos.line, sourcePos.column)
                    return ParseResult.Success(Stmt.ReturnStmt(null), currentPos)
                }
            }
        }
    }
}

/**
 * Parser for break statement: break
 */
fun breakStatement(): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwBreak = literalToken("break")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val kwResult = kwBreak.parse(input, position)
            val kwSuccess = kwResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("break", position)

            val sourcePos = SourcePosition.fromOffset(input, position)
            val breakToken = Token(TokenType.KW_BREAK, "break", sourcePos.line, sourcePos.column)
            return ParseResult.Success(Stmt.BreakStmt, kwSuccess.position)
        }
    }
}

/**
 * Parser for next statement (continue): next
 */
fun nextStatement(): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwNext = literalToken("next")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val kwResult = kwNext.parse(input, position)
            val kwSuccess = kwResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("next", position)

            val sourcePos = SourcePosition.fromOffset(input, position)
            val nextToken = Token(TokenType.KW_NEXT, "next", sourcePos.line, sourcePos.column)
            return ParseResult.Success(Stmt.NextStmt, kwSuccess.position)
        }
    }
}
