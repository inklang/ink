package org.inklang.peg.ink

import org.inklang.lang.Expr
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

            override fun parse(input: String, position: Int): ParseResult<Expr> {
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

    // Full expression parser
    val expression: Parser<Expr> = assignment(unary())

    /**
     * Parses a complete expression string.
     */
    fun parseExpression(input: String): ParseResult<Expr> {
        return expression.parse(input, 0)
    }
}
