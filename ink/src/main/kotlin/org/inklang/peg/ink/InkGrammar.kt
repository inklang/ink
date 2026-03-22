package org.inklang.peg.ink

import org.inklang.lang.Expr
import org.inklang.lang.Stmt
import org.inklang.peg.ink.expressions.binaryOp
import org.inklang.peg.ink.expressions.booleanLiteral
import org.inklang.peg.ink.expressions.buildExpressionParser
import org.inklang.peg.ink.expressions.callExpr
import org.inklang.peg.ink.expressions.floatLiteral
import org.inklang.peg.ink.expressions.grouping
import org.inklang.peg.ink.expressions.identifier
import org.inklang.peg.ink.expressions.integerLiteral
import org.inklang.peg.ink.expressions.literalToken
import org.inklang.peg.ink.expressions.nullLiteral
import org.inklang.peg.ink.expressions.orToken
import org.inklang.peg.ink.expressions.prefixOp
import org.inklang.peg.ink.expressions.stringLiteral
import org.inklang.peg.ink.statements.blockStatement
import org.inklang.peg.ink.statements.returnStatement
import org.inklang.peg.ink.statements.breakStatement
import org.inklang.peg.ink.statements.nextStatement
import org.inklang.peg.ink.statements.ifStatement
import org.inklang.peg.ink.statements.whileStatement
import org.inklang.peg.ink.statements.forStatement
import org.inklang.peg.ink.statements.varDeclaration
import org.inklang.peg.ink.statements.fnDeclaration
import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser
import org.inklang.peg.PegToken
import org.inklang.peg.util.SourcePosition
import org.inklang.lang.Token
import org.inklang.lang.TokenType

/**
 * Root grammar builder for Ink expressions.
 * Combines all expression parsers into a cohesive grammar.
 */
class InkGrammar {
    // Helper to convert PegToken to Token for Expr construction
    private fun toToken(pegToken: PegToken): Token {
        val type = when (pegToken.lexeme) {
            "+" -> TokenType.PLUS
            "-" -> TokenType.MINUS
            "*" -> TokenType.STAR
            "/" -> TokenType.SLASH
            "%" -> TokenType.PERCENT
            "==" -> TokenType.EQ_EQ
            "!=" -> TokenType.BANG_EQ
            "<" -> TokenType.LT
            ">" -> TokenType.GT
            "<=" -> TokenType.LTE
            ">=" -> TokenType.GTE
            "and" -> TokenType.KW_AND
            "or" -> TokenType.KW_OR
            "=" -> TokenType.ASSIGN
            else -> TokenType.IDENTIFIER
        }
        return Token(type, pegToken.lexeme, pegToken.position.line, pegToken.position.column)
    }

    // Primary expression parser - handles literals, variables, and parenthesized expressions
    private fun primary(): Parser<Expr> {
        return object : BaseParser<Expr>() {
            private val integer = integerLiteral()
            private val float = floatLiteral()
            private val string = stringLiteral()
            private val bool = booleanLiteral()
            private val nullLit = nullLiteral()
            private val ident = identifier()
            private val lparen = literalToken("(")

            override fun parse(input: String, position: Int): ParseResult<Expr> {
                // Check for opening paren first - if present, parse grouped expression
                val lparenResult = lparen.parse(input, position)
                if (lparenResult is ParseResult.Success<PegToken>) {
                    // Parse expression inside parentheses
                    val innerResult = expression.parse(input, lparenResult.position)
                    return when (innerResult) {
                        is ParseResult.Success<Expr> -> {
                            val rparenResult = literalToken(")").parse(input, innerResult.position)
                            when (rparenResult) {
                                is ParseResult.Success<PegToken> -> ParseResult.Success(
                                    Expr.GroupExpr(innerResult.value),
                                    rparenResult.position
                                )
                                is ParseResult.Failure -> ParseResult.Failure(")", rparenResult.position)
                            }
                        }
                        is ParseResult.Failure -> ParseResult.Failure(")", innerResult.position)
                    }
                }

                // Try literals first in order of specificity
                // Float before integer since floats can look like integers with a decimal

                val floatResult = float.parse(input, position)
                if (floatResult is ParseResult.Success) return floatResult

                val integerResult = integer.parse(input, position)
                if (integerResult is ParseResult.Success) return integerResult

                val stringResult = string.parse(input, position)
                if (stringResult is ParseResult.Success) return stringResult

                val boolResult = bool.parse(input, position)
                if (boolResult is ParseResult.Success) return boolResult

                val nullResult = nullLit.parse(input, position)
                if (nullResult is ParseResult.Success) return nullResult

                val identResult = ident.parse(input, position)
                if (identResult is ParseResult.Success) return identResult

                return ParseResult.Failure("primary expression", position)
            }
        }
    }

    // Unary expression - handles prefix operators like negation
    private fun unary(): Parser<Expr> {
        val sub = primary()

        return object : BaseParser<Expr>() {
            override fun parse(input: String, position: Int): ParseResult<Expr> {
                // Check for unary minus
                val minusResult = literalToken("-").parse(input, position)
                if (minusResult is ParseResult.Success) {
                    val minusSuccess = minusResult as ParseResult.Success<PegToken>
                    val operandResult = unary().parse(input, minusSuccess.position)
                    if (operandResult is ParseResult.Success) {
                        val operandSuccess = operandResult as ParseResult.Success<Expr>
                        val sourcePos = SourcePosition.fromOffset(input, position)
                        val token = Token(TokenType.MINUS, "-", sourcePos.line, sourcePos.column)
                        return ParseResult.Success(
                            Expr.UnaryExpr(token, operandSuccess.value),
                            operandSuccess.position
                        )
                    }
                }

                // Try call expression (primary with postfix calls)
                return callExpr(sub).parse(input, position)
            }
        }
    }

    // Multiplication/division/modulo
    private fun multiplication(sub: Parser<Expr>): Parser<Expr> {
        return object : BaseParser<Expr>() {
            override fun parse(input: String, position: Int): ParseResult<Expr> {
                val leftResult = sub.parse(input, position)
                if (leftResult is ParseResult.Failure) return leftResult
                val leftSuccess = leftResult as ParseResult.Success<Expr>

                var left = leftSuccess.value
                var currentPos = leftSuccess.position

                while (true) {
                    val opResult = orToken("*", "/", "%").parse(input, currentPos)
                    if (opResult is ParseResult.Failure) break
                    val opSuccess = opResult as ParseResult.Success<PegToken>

                    val rightResult = sub.parse(input, opSuccess.position)
                    if (rightResult is ParseResult.Failure) break
                    val rightSuccess = rightResult as ParseResult.Success<Expr>

                    left = Expr.BinaryExpr(left, toToken(opSuccess.value), rightSuccess.value)
                    currentPos = rightSuccess.position
                }

                return ParseResult.Success(left, currentPos)
            }
        }
    }

    // Addition/subtraction
    private fun addition(sub: Parser<Expr>): Parser<Expr> {
        return object : BaseParser<Expr>() {
            override fun parse(input: String, position: Int): ParseResult<Expr> {
                val leftResult = sub.parse(input, position)
                if (leftResult is ParseResult.Failure) return leftResult
                val leftSuccess = leftResult as ParseResult.Success<Expr>

                var left = leftSuccess.value
                var currentPos = leftSuccess.position

                while (true) {
                    val opResult = orToken("+", "-").parse(input, currentPos)
                    if (opResult is ParseResult.Failure) break
                    val opSuccess = opResult as ParseResult.Success<PegToken>

                    val rightResult = sub.parse(input, opSuccess.position)
                    if (rightResult is ParseResult.Failure) break
                    val rightSuccess = rightResult as ParseResult.Success<Expr>

                    left = Expr.BinaryExpr(left, toToken(opSuccess.value), rightSuccess.value)
                    currentPos = rightSuccess.position
                }

                return ParseResult.Success(left, currentPos)
            }
        }
    }

    // Comparison
    private fun comparison(sub: Parser<Expr>): Parser<Expr> {
        return object : BaseParser<Expr>() {
            override fun parse(input: String, position: Int): ParseResult<Expr> {
                val leftResult = sub.parse(input, position)
                if (leftResult is ParseResult.Failure) return leftResult
                val leftSuccess = leftResult as ParseResult.Success<Expr>

                var left = leftSuccess.value
                var currentPos = leftSuccess.position

                while (true) {
                    val opResult = orToken("<", ">", "<=", ">=").parse(input, currentPos)
                    if (opResult is ParseResult.Failure) break
                    val opSuccess = opResult as ParseResult.Success<PegToken>

                    val rightResult = sub.parse(input, opSuccess.position)
                    if (rightResult is ParseResult.Failure) break
                    val rightSuccess = rightResult as ParseResult.Success<Expr>

                    left = Expr.BinaryExpr(left, toToken(opSuccess.value), rightSuccess.value)
                    currentPos = rightSuccess.position
                }

                return ParseResult.Success(left, currentPos)
            }
        }
    }

    // Equality
    private fun equality(sub: Parser<Expr>): Parser<Expr> {
        return object : BaseParser<Expr>() {
            override fun parse(input: String, position: Int): ParseResult<Expr> {
                val leftResult = sub.parse(input, position)
                if (leftResult is ParseResult.Failure) return leftResult
                val leftSuccess = leftResult as ParseResult.Success<Expr>

                var left = leftSuccess.value
                var currentPos = leftSuccess.position

                while (true) {
                    val opResult = orToken("==", "!=").parse(input, currentPos)
                    if (opResult is ParseResult.Failure) break
                    val opSuccess = opResult as ParseResult.Success<PegToken>

                    val rightResult = sub.parse(input, opSuccess.position)
                    if (rightResult is ParseResult.Failure) break
                    val rightSuccess = rightResult as ParseResult.Success<Expr>

                    left = Expr.BinaryExpr(left, toToken(opSuccess.value), rightSuccess.value)
                    currentPos = rightSuccess.position
                }

                return ParseResult.Success(left, currentPos)
            }
        }
    }

    // Logical AND
    private fun conjunction(sub: Parser<Expr>): Parser<Expr> {
        return object : BaseParser<Expr>() {
            override fun parse(input: String, position: Int): ParseResult<Expr> {
                val leftResult = sub.parse(input, position)
                if (leftResult is ParseResult.Failure) return leftResult
                val leftSuccess = leftResult as ParseResult.Success<Expr>

                var left = leftSuccess.value
                var currentPos = leftSuccess.position

                while (true) {
                    val andResult = literalToken("and").parse(input, currentPos)
                    if (andResult is ParseResult.Failure) break
                    val andSuccess = andResult as ParseResult.Success<PegToken>

                    val rightResult = sub.parse(input, andSuccess.position)
                    if (rightResult is ParseResult.Failure) break
                    val rightSuccess = rightResult as ParseResult.Success<Expr>

                    left = Expr.BinaryExpr(left, toToken(andSuccess.value), rightSuccess.value)
                    currentPos = rightSuccess.position
                }

                return ParseResult.Success(left, currentPos)
            }
        }
    }

    // Logical OR
    private fun disjunction(sub: Parser<Expr>): Parser<Expr> {
        return object : BaseParser<Expr>() {
            override fun parse(input: String, position: Int): ParseResult<Expr> {
                val leftResult = sub.parse(input, position)
                if (leftResult is ParseResult.Failure) return leftResult
                val leftSuccess = leftResult as ParseResult.Success<Expr>

                var left = leftSuccess.value
                var currentPos = leftSuccess.position

                while (true) {
                    val orResult = literalToken("or").parse(input, currentPos)
                    if (orResult is ParseResult.Failure) break
                    val orSuccess = orResult as ParseResult.Success<PegToken>

                    val rightResult = sub.parse(input, orSuccess.position)
                    if (rightResult is ParseResult.Failure) break
                    val rightSuccess = rightResult as ParseResult.Success<Expr>

                    left = Expr.BinaryExpr(left, toToken(orSuccess.value), rightSuccess.value)
                    currentPos = rightSuccess.position
                }

                return ParseResult.Success(left, currentPos)
            }
        }
    }

    // Assignment (right-associative)
    private fun assignment(sub: Parser<Expr>): Parser<Expr> {
        return object : BaseParser<Expr>() {
            override fun parse(input: String, position: Int): ParseResult<Expr> {
                // First try to parse as disjunction
                val leftResult = disjunction(sub).parse(input, position)
                if (leftResult is ParseResult.Failure) return leftResult
                val leftSuccess = leftResult as ParseResult.Success<Expr>

                // Check for assignment operator
                val assignResult = literalToken("=").parse(input, leftSuccess.position)
                if (assignResult is ParseResult.Failure) {
                    return leftResult
                }
                val assignSuccess = assignResult as ParseResult.Success<PegToken>

                // Recursively parse RHS (for right-associativity)
                val rightResult = assignment(sub).parse(input, assignSuccess.position)
                if (rightResult is ParseResult.Failure) {
                    // Not a valid assignment, return just the LHS
                    return leftResult
                }
                val rightSuccess = rightResult as ParseResult.Success<Expr>

                val target = leftSuccess.value
                // Only allow assignment to variables, get expressions, or index expressions
                if (target !is Expr.VariableExpr && target !is Expr.GetExpr && target !is Expr.IndexExpr) {
                    return leftResult
                }

                return ParseResult.Success(
                    Expr.AssignExpr(target, toToken(assignSuccess.value), rightSuccess.value),
                    rightSuccess.position
                )
            }
        }
    }

    // Full expression parser - lazy to break initialization cycle with primary()
    // primary() calls grouping(expression), so expression must be accessible when primary().parse() runs
    val expression: Parser<Expr> by lazy { assignment(unary()) }

    /**
     * Parses a single statement.
     */
    val statement: Parser<Stmt> by lazy {
        object : BaseParser<Stmt>() {
            override fun parse(input: String, position: Int): ParseResult<Stmt> {
                var pos = position

                // Skip whitespace and newlines
                while (pos < input.length && (input[pos].isWhitespace() || input[pos] == '\n')) {
                    pos++
                }

                if (pos >= input.length) {
                    return ParseResult.Failure("statement", position)
                }

                // Try return statement
                val retResult = returnStatement { expression }.parse(input, pos)
                if (retResult is ParseResult.Success) return retResult

                // Try var declaration
                val varResult = varDeclaration { expression }.parse(input, pos)
                if (varResult is ParseResult.Success) return varResult

                // Try fn declaration
                val fnResult = fnDeclaration { expression }.parse(input, pos)
                if (fnResult is ParseResult.Success) return fnResult

                // Try if statement
                val ifResult = ifStatement { expression }.parse(input, pos)
                if (ifResult is ParseResult.Success) return ifResult

                // Try while statement
                val whileResult = whileStatement { expression }.parse(input, pos)
                if (whileResult is ParseResult.Success) return whileResult

                // Try for statement
                val forResult = forStatement { expression }.parse(input, pos)
                if (forResult is ParseResult.Success) return forResult

                // Try break
                val breakResult = breakStatement().parse(input, pos)
                if (breakResult is ParseResult.Success) return breakResult

                // Try next
                val nextResult = nextStatement().parse(input, pos)
                if (nextResult is ParseResult.Success) return nextResult

                // Try block statement (nested)
                val blockResult = blockStatement { expression }.parse(input, pos)
                if (blockResult is ParseResult.Success) return blockResult

                // Try expression as statement
                val exprResult = expression.parse(input, pos)
                when (exprResult) {
                    is ParseResult.Success -> {
                        return ParseResult.Success(Stmt.ExprStmt(exprResult.value), exprResult.position)
                    }
                    is ParseResult.Failure -> {
                        return exprResult
                    }
                }
            }
        }
    }

    /**
     * Parses a complete program (multiple statements).
     */
    val program: Parser<List<Stmt>> by lazy {
        object : BaseParser<List<Stmt>>() {
            override fun parse(input: String, position: Int): ParseResult<List<Stmt>> {
                val results = mutableListOf<Stmt>()
                var currentPos = position

                while (currentPos < input.length) {
                    // Skip whitespace and newlines
                    while (currentPos < input.length && (input[currentPos].isWhitespace() || input[currentPos] == '\n')) {
                        currentPos++
                    }

                    if (currentPos >= input.length) break

                    val stmtResult = statement.parse(input, currentPos)
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
        }
    }

    /**
     * Parses a complete expression string.
     */
    fun parseExpression(input: String): ParseResult<Expr> {
        return expression.parse(input, 0)
    }

    /**
     * Parses a complete program string.
     */
    fun parseProgram(input: String): ParseResult<List<Stmt>> {
        return program.parse(input, 0)
    }
}
