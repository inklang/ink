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
 * Associativity for binary operators.
 */
enum class Assoc {
    LEFT,
    RIGHT
}

/**
 * Creates a binary operator parser using Pratt precedence climbing.
 *
 * @param sub The sub-expression parser (higher precedence)
 * @param op The operator parser
 * @param assoc The associativity of the operator
 * @param makeExpr Function to create the binary expression from left, operator, and right
 */
fun binaryOp(
    sub: Parser<Expr>,
    op: Parser<PegToken>,
    assoc: Assoc,
    makeExpr: (Expr, PegToken, Expr) -> Expr
): Parser<Expr> {
    return object : BaseParser<Expr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr> {
            return when (val leftResult = sub.parse(input, position)) {
                is ParseResult.Failure -> leftResult
                is ParseResult.Success -> {
                    var left = leftResult.value
                    var currentPos = leftResult.position

                    while (true) {
                        val opResult = op.parse(input, currentPos)
                        val opSuccess = opResult as? ParseResult.Success<PegToken> ?: break

                        val rightResult = sub.parse(input, opSuccess.position)
                        val rightSuccess = rightResult as? ParseResult.Success<Expr> ?: break

                        left = makeExpr(left, opSuccess.value, rightSuccess.value)
                        currentPos = rightSuccess.position
                    }

                    ParseResult.Success(left, currentPos)
                }
            }
        }
    }
}

/**
 * Creates a prefix operator parser (unary operators).
 *
 * @param op The operator parser
 * @param sub The sub-expression parser (operand)
 */
fun prefixOp(
    op: Parser<PegToken>,
    sub: Parser<Expr>
): Parser<Expr> {
    return object : BaseParser<Expr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr> {
            val opResult = op.parse(input, position)
            val opSuccess = opResult as? ParseResult.Success<PegToken> ?: return sub.parse(input, position)

            val operandResult = sub.parse(input, opSuccess.position)
            val operandSuccess = operandResult as? ParseResult.Success<Expr> ?: return sub.parse(input, position)

            val token = Token(
                TokenType.IDENTIFIER, // Will be fixed by operator type
                opSuccess.value.lexeme,
                SourcePosition.fromOffset(input, position).line,
                SourcePosition.fromOffset(input, position).column
            )
            return ParseResult.Success(
                Expr.UnaryExpr(token, operandSuccess.value),
                operandSuccess.position
            )
        }
    }
}

/**
 * Returns a parser that matches a literal token (returns PegToken, not String).
 */
fun literalToken(s: String): Parser<PegToken> {
    return object : BaseParser<PegToken>() {
        override fun parse(input: String, position: Int): ParseResult<PegToken> {
            if (position + s.length > input.length) {
                return ParseResult.Failure(s, position)
            }
            val matched = input.substring(position, position + s.length)
            if (matched == s) {
                val sourcePos = SourcePosition.fromOffset(input, position)
                return ParseResult.Success(PegToken(s, s, sourcePos), position + s.length)
            }
            return ParseResult.Failure(s, position)
        }
    }
}

/**
 * Returns a parser that matches a keyword (like literalToken but for keywords).
 * Same implementation for now.
 */
fun keywordToken(kw: String): Parser<PegToken> = literalToken(kw)

/**
 * Returns a parser that matches any of the given operators.
 */
fun orToken(vararg ops: String): Parser<PegToken> {
    return object : BaseParser<PegToken>() {
        private val alternatives = ops.map { literalToken(it) }

        override fun parse(input: String, position: Int): ParseResult<PegToken> {
            for (alt in alternatives) {
                val result = alt.parse(input, position)
                if (result is ParseResult.Success) {
                    return result
                }
            }
            return ParseResult.Failure(ops.joinToString("|"), position)
        }
    }
}

/**
 * Builds a full expression parser with Pratt precedence climbing.
 *
 * Precedence order (lowest to highest):
 * 1. Assignment (=) - RIGHT associative
 * 2. OR (or)
 * 3. AND (and)
 * 4. Equality (==, !=)
 * 5. Comparison (<, >, <=, >=)
 * 6. Addition (+, -)
 * 7. Multiplication (*, /, %)
 * 8. Unary (-)
 * 9. Function calls (postfix) - handled by primary
 *
 * @param primary The primary expression parser (literals, variables, parenthesized)
 */
fun buildExpressionParser(primary: Parser<Expr>): Parser<Expr> {
    // Helper to convert PegToken to Token for Expr construction
    fun toToken(pegToken: PegToken, input: String): Token {
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

    // Level 8: Unary (prefix)
    val unaryExpr = prefixOp(orToken("-"), primary)

    // Level 7: Multiplication (*, /, %)
    val mulExpr = binaryOp(
        unaryExpr,
        orToken("*", "/", "%"),
        Assoc.LEFT
    ) { left, op, right ->
        Expr.BinaryExpr(left, toToken(op, ""), right)
    }

    // Level 6: Addition (+, -)
    val addExpr = binaryOp(
        mulExpr,
        orToken("+", "-"),
        Assoc.LEFT
    ) { left, op, right ->
        Expr.BinaryExpr(left, toToken(op, ""), right)
    }

    // Level 5: Comparison (<, >, <=, >=)
    val cmpExpr = binaryOp(
        addExpr,
        orToken("<", ">", "<=", ">="),
        Assoc.LEFT
    ) { left, op, right ->
        Expr.BinaryExpr(left, toToken(op, ""), right)
    }

    // Level 4: Equality (==, !=)
    val eqExpr = binaryOp(
        cmpExpr,
        orToken("==", "!="),
        Assoc.LEFT
    ) { left, op, right ->
        Expr.BinaryExpr(left, toToken(op, ""), right)
    }

    // Level 3: AND (and)
    val andExpr = binaryOp(
        eqExpr,
        keywordToken("and"),
        Assoc.LEFT
    ) { left, op, right ->
        Expr.BinaryExpr(left, toToken(op, ""), right)
    }

    // Level 2: OR (or)
    val orExpr = binaryOp(
        andExpr,
        keywordToken("or"),
        Assoc.LEFT
    ) { left, op, right ->
        Expr.BinaryExpr(left, toToken(op, ""), right)
    }

    // Level 1: Assignment (=) - RIGHT associative
    // For right-associativity, we parse lhs, then check for =, and recursively continue
    // Use a holder to allow self-reference
    val assignHolder = object {
        lateinit var parser: Parser<Expr>
    }

    val assignParser = object : BaseParser<Expr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr> {
            return when (val leftResult = orExpr.parse(input, position)) {
                is ParseResult.Failure -> leftResult
                is ParseResult.Success -> {
                    val assignResult = orToken("=").parse(input, leftResult.position)
                    val assignSuccess = assignResult as? ParseResult.Success<PegToken> ?: return leftResult

                    val rightResult = assignHolder.parser.parse(input, assignSuccess.position)
                    val rightSuccess = rightResult as? ParseResult.Success<Expr> ?: return leftResult

                    val target = leftResult.value
                    if (target !is Expr.VariableExpr && target !is Expr.GetExpr && target !is Expr.IndexExpr) {
                        return leftResult
                    }

                    ParseResult.Success(
                        Expr.AssignExpr(target, toToken(assignSuccess.value, input), rightSuccess.value),
                        rightSuccess.position
                    )
                }
            }
        }
    }

    assignHolder.parser = assignParser
    return assignParser
}
