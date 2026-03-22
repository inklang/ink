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
 * Parser for function call expressions.
 * Handles: expr(args)
 */
fun callExpr(base: Parser<Expr>): Parser<Expr> {
    return object : BaseParser<Expr>() {
        private val lparen = literalToken("(")
        private val rparen = literalToken(")")
        private val comma = literalToken(",")

        override fun parse(input: String, position: Int): ParseResult<Expr> {
            val baseResult = base.parse(input, position)
            val baseSuccess = baseResult as? ParseResult.Success<Expr> ?: return baseResult

            var current = baseSuccess.value
            var currentPos = baseSuccess.position

            while (true) {
                val lparenResult = lparen.parse(input, currentPos)
                val lparenSuccess = lparenResult as? ParseResult.Success<PegToken> ?: break

                val args = mutableListOf<Expr>()

                // Check for empty args
                val rparenCheck = rparen.parse(input, lparenSuccess.position)
                if (rparenCheck is ParseResult.Success) {
                    val rparenCheckSuccess = rparenCheck as ParseResult.Success<PegToken>
                    val sourcePos = SourcePosition.fromOffset(input, position)
                    val parenToken = Token(TokenType.L_PAREN, "(", sourcePos.line, sourcePos.column)
                    current = Expr.CallExpr(current, parenToken, args)
                    currentPos = rparenCheckSuccess.position
                    continue
                }

                // Parse first argument
                val firstArgResult = base.parse(input, lparenSuccess.position)
                val firstArgSuccess = firstArgResult as? ParseResult.Success<Expr> ?: break
                args.add(firstArgSuccess.value)

                var argsPos = firstArgSuccess.position
                while (true) {
                    val commaResult = comma.parse(input, argsPos)
                    val commaSuccess = commaResult as? ParseResult.Success<PegToken> ?: break

                    val argResult = base.parse(input, commaSuccess.position)
                    val argSuccess = argResult as? ParseResult.Success<Expr> ?: return argResult
                    args.add(argSuccess.value)
                    argsPos = argSuccess.position
                }

                val rparenResult = rparen.parse(input, argsPos)
                val rparenSuccess = rparenResult as? ParseResult.Success<PegToken>
                if (rparenSuccess == null) {
                    // Return failure with correct type
                    val failurePos = when (rparenResult) {
                        is ParseResult.Failure -> rparenResult.position
                        else -> argsPos
                    }
                    return ParseResult.Failure(")", failurePos)
                }

                val sourcePos = SourcePosition.fromOffset(input, position)
                val parenToken = Token(TokenType.L_PAREN, "(", sourcePos.line, sourcePos.column)
                current = Expr.CallExpr(current, parenToken, args)
                currentPos = rparenSuccess.position
            }

            return ParseResult.Success(current, currentPos)
        }
    }
}

/**
 * Parser for method call expressions (postfix).
 * Handles: expr.method(args)
 */
fun methodCallExpr(base: Parser<Expr>): Parser<Expr> {
    return object : BaseParser<Expr>() {
        private val dot = literalToken(".")
        private val lparen = literalToken("(")
        private val rparen = literalToken(")")
        private val comma = literalToken(",")

        override fun parse(input: String, position: Int): ParseResult<Expr> {
            val baseResult = base.parse(input, position)
            val baseSuccess = baseResult as? ParseResult.Success<Expr> ?: return baseResult

            var current = baseSuccess.value
            var currentPos = baseSuccess.position

            while (true) {
                val dotResult = dot.parse(input, currentPos)
                val dotSuccess = dotResult as? ParseResult.Success<PegToken> ?: break

                val nameResult = identifier().parse(input, dotSuccess.position)
                val nameSuccess = nameResult as? ParseResult.Success<Expr.VariableExpr>
                if (nameSuccess == null) {
                    return nameResult
                }
                val nameToken = nameSuccess.value.name

                val lparenResult = lparen.parse(input, nameSuccess.position)
                val lparenSuccess = lparenResult as? ParseResult.Success<PegToken>

                if (lparenSuccess == null) {
                    val getToken = Token(TokenType.DOT, ".", dotSuccess.value.position.line, dotSuccess.value.position.column)
                    current = Expr.GetExpr(current, getToken)
                    currentPos = nameSuccess.position
                    continue
                }

                val args = mutableListOf<Expr>()

                val rparenCheck = rparen.parse(input, lparenSuccess.position)
                if (rparenCheck is ParseResult.Success) {
                    val rparenCheckSuccess = rparenCheck as ParseResult.Success<PegToken>
                    val sourcePos = SourcePosition.fromOffset(input, position)
                    val parenToken = Token(TokenType.L_PAREN, "(", sourcePos.line, sourcePos.column)
                    val getExpr = Expr.GetExpr(current, nameToken)
                    current = Expr.CallExpr(getExpr, parenToken, args)
                    currentPos = rparenCheckSuccess.position
                    continue
                }

                val firstArgResult = base.parse(input, lparenSuccess.position)
                val firstArgSuccess = firstArgResult as? ParseResult.Success<Expr> ?: return firstArgResult
                args.add(firstArgSuccess.value)

                var argsPos = firstArgSuccess.position
                while (true) {
                    val commaResult = comma.parse(input, argsPos)
                    val commaSuccess = commaResult as? ParseResult.Success<PegToken> ?: break

                    val argResult = base.parse(input, commaSuccess.position)
                    val argSuccess = argResult as? ParseResult.Success<Expr> ?: return argResult
                    args.add(argSuccess.value)
                    argsPos = argSuccess.position
                }

                val rparenResult = rparen.parse(input, argsPos)
                val rparenSuccess = rparenResult as? ParseResult.Success<PegToken>
                if (rparenSuccess == null) {
                    val failurePos = when (rparenResult) {
                        is ParseResult.Failure -> rparenResult.position
                        else -> argsPos
                    }
                    return ParseResult.Failure(")", failurePos)
                }

                val getExpr = Expr.GetExpr(current, nameToken)
                val sourcePos = SourcePosition.fromOffset(input, position)
                val parenToken = Token(TokenType.L_PAREN, "(", sourcePos.line, sourcePos.column)
                current = Expr.CallExpr(getExpr, parenToken, args)
                currentPos = rparenSuccess.position
            }

            return ParseResult.Success(current, currentPos)
        }
    }
}
