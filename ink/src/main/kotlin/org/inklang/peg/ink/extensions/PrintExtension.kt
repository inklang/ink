package org.inklang.peg.ink.extensions

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
 * Built-in print statement extension: print(expr, expr, ...)
 * Example: print("Hello, ", name, "!")
 */
fun printStatement(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwPrint = literalToken("print")
        private val lparen = literalToken("(")
        private val rparen = literalToken(")")
        private val comma = literalToken(",")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val printResult = kwPrint.parse(input, position)
            val printSuccess = printResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("print", position)

            var currentPos = printSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse opening paren
            val lparenResult = lparen.parse(input, currentPos)
            if (lparenResult is ParseResult.Failure) {
                return ParseResult.Failure("(", currentPos)
            }
            currentPos = (lparenResult as ParseResult.Success).position

            // Parse arguments
            val args = mutableListOf<Expr>()

            while (currentPos < input.length) {
                // Check for closing paren
                val rparenCheck = rparen.parse(input, currentPos)
                if (rparenCheck is ParseResult.Success) {
                    currentPos = rparenCheck.position
                    break
                }

                // Parse expression argument
                val argResult = expressionParser().parse(input, currentPos)
                when (argResult) {
                    is ParseResult.Success -> {
                        args.add(argResult.value)
                        currentPos = argResult.position
                    }
                    is ParseResult.Failure -> {
                        return ParseResult.Failure("expression", currentPos)
                    }
                }

                // Check for comma or closing paren
                val commaResult = comma.parse(input, currentPos)
                when (commaResult) {
                    is ParseResult.Success -> {
                        currentPos = commaResult.position
                    }
                    is ParseResult.Failure -> {
                        // Check if we should end with rparen
                        val rparenFinalCheck = rparen.parse(input, currentPos)
                        if (rparenFinalCheck is ParseResult.Success) {
                            currentPos = rparenFinalCheck.position
                            break
                        } else {
                            return ParseResult.Failure(", or )", currentPos)
                        }
                    }
                }
            }

            // Create the statement - we use ExprStmt with a call to print
            val sourcePos = SourcePosition.fromOffset(input, position)
            val printToken = Token(TokenType.IDENTIFIER, "print", sourcePos.line, sourcePos.column)
            return ParseResult.Success(
                Stmt.ExprStmt(Expr.CallExpr(
                    Expr.VariableExpr(printToken),
                    printToken,
                    args
                )),
                currentPos
            )
        }
    }
}

/**
 * Every N seconds statement extension: every seconds { body }
 * Example: every 5 { print("tick") }
 */
fun everyStatement(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwEvery = literalToken("every")
        private val kwSeconds = literalToken("seconds")
        private val lbrace = literalToken("{")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val everyResult = kwEvery.parse(input, position)
            val everySuccess = everyResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("every", position)

            var currentPos = everySuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse number of seconds
            val numResult = parseNumber(input, currentPos)
            if (numResult is ParseResult.Failure) {
                return ParseResult.Failure("number", currentPos)
            }
            val numSuccess = numResult as ParseResult.Success<Int>
            currentPos = numSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse "seconds" keyword (optional)
            val secondsResult = kwSeconds.parse(input, currentPos)
            if (secondsResult is ParseResult.Success) {
                currentPos = secondsResult.position
            }

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse block
            val lbraceResult = lbrace.parse(input, currentPos)
            if (lbraceResult is ParseResult.Failure) {
                return ParseResult.Failure("{", currentPos)
            }
            currentPos = (lbraceResult as ParseResult.Success).position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // For now, we just capture the body as a string representation
            // In a real implementation, this would parse the block statements
            val sourcePos = SourcePosition.fromOffset(input, position)
            val everyToken = Token(TokenType.IDENTIFIER, "every", sourcePos.line, sourcePos.column)

            return ParseResult.Success(
                Stmt.ExprStmt(Expr.CallExpr(
                    Expr.VariableExpr(everyToken),
                    everyToken,
                    listOf(Expr.LiteralExpr(org.inklang.lang.Value.Int(numSuccess.value)))
                )),
                currentPos
            )
        }

        private fun parseNumber(input: String, position: Int): ParseResult<Int> {
            var pos = position
            val numBuilder = StringBuilder()

            while (pos < input.length && input[pos].isDigit()) {
                numBuilder.append(input[pos])
                pos++
            }

            return if (numBuilder.isNotEmpty()) {
                ParseResult.Success(numBuilder.toString().toInt(), pos)
            } else {
                ParseResult.Failure("number", position)
            }
        }
    }
}

/**
 * Simple "spawn" statement: spawn { body }
 * Example: spawn { print("Running in background") }
 */
fun spawnStatement(expressionParser: () -> Parser<Expr>): Parser<Stmt> {
    return object : BaseParser<Stmt>() {
        private val kwSpawn = literalToken("spawn")
        private val lbrace = literalToken("{")

        override fun parse(input: String, position: Int): ParseResult<Stmt> {
            val spawnResult = kwSpawn.parse(input, position)
            val spawnSuccess = spawnResult as? ParseResult.Success<PegToken>
                ?: return ParseResult.Failure("spawn", position)

            var currentPos = spawnSuccess.position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            // Parse block
            val lbraceResult = lbrace.parse(input, currentPos)
            if (lbraceResult is ParseResult.Failure) {
                return ParseResult.Failure("{", currentPos)
            }
            currentPos = (lbraceResult as ParseResult.Success).position

            // Skip whitespace
            while (currentPos < input.length && input[currentPos].isWhitespace()) {
                currentPos++
            }

            val sourcePos = SourcePosition.fromOffset(input, position)
            val spawnToken = Token(TokenType.IDENTIFIER, "spawn", sourcePos.line, sourcePos.column)

            return ParseResult.Success(
                Stmt.ExprStmt(Expr.CallExpr(
                    Expr.VariableExpr(spawnToken),
                    spawnToken,
                    emptyList()
                )),
                currentPos
            )
        }
    }
}
