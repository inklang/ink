package org.inklang.peg.ink.expressions

import org.inklang.lang.Expr
import org.inklang.lang.Token
import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser
import org.inklang.peg.PegToken
import org.inklang.peg.combinators.map
import org.inklang.peg.util.SourcePosition

/**
 * Parser for integer literals.
 * Matches sequences of digits and returns Expr.LiteralExpr with Value.Int.
 */
fun integerLiteral(): Parser<Expr> {
    return object : BaseParser<Expr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr> {
            if (position >= input.length) {
                return ParseResult.Failure("integer literal", position)
            }

            var end = position
            val startChar = input[end]

            // Handle optional leading minus for negative numbers (handled separately as unary)
            if (startChar == '-') {
                return ParseResult.Failure("integer literal", position)
            }

            if (!startChar.isDigit()) {
                return ParseResult.Failure("integer literal", position)
            }

            end++
            while (end < input.length && input[end].isDigit()) {
                end++
            }

            val lexeme = input.substring(position, end)
            val value = lexeme.toIntOrNull() ?: return ParseResult.Failure("integer literal", position)

            return ParseResult.Success(
                Expr.LiteralExpr(Value.Int(value)),
                end
            )
        }
    }
}

/**
 * Parser for floating-point literals.
 * Matches digits, optional decimal point, and optional fractional part.
 */
fun floatLiteral(): Parser<Expr> {
    return object : BaseParser<Expr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr> {
            if (position >= input.length) {
                return ParseResult.Failure("float literal", position)
            }

            var end = position

            // Must start with digit
            if (!input[end].isDigit()) {
                return ParseResult.Failure("float literal", position)
            }

            // Parse integer part
            while (end < input.length && input[end].isDigit()) {
                end++
            }

            // Must have decimal point
            if (end >= input.length || input[end] != '.') {
                return ParseResult.Failure("float literal", position)
            }
            end++

            // Must have at least one digit after decimal
            if (end >= input.length || !input[end].isDigit()) {
                return ParseResult.Failure("float literal", position)
            }

            while (end < input.length && input[end].isDigit()) {
                end++
            }

            val lexeme = input.substring(position, end)
            val value = lexeme.toDoubleOrNull() ?: return ParseResult.Failure("float literal", position)

            return ParseResult.Success(
                Expr.LiteralExpr(Value.Float(value.toFloat())),
                end
            )
        }
    }
}

/**
 * Parser for string literals.
 * Matches text between double quotes, handling escape sequences.
 */
fun stringLiteral(): Parser<Expr> {
    return object : BaseParser<Expr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr> {
            if (position >= input.length || input[position] != '"') {
                return ParseResult.Failure("string literal", position)
            }

            var end = position + 1
            val result = StringBuilder()

            while (end < input.length) {
                val c = input[end]
                when (c) {
                    '"' -> {
                        // End of string
                        val value = result.toString()
                        return ParseResult.Success(
                            Expr.LiteralExpr(Value.String(value)),
                            end + 1
                        )
                    }
                    '\\' -> {
                        // Escape sequence
                        end++
                        if (end >= input.length) {
                            return ParseResult.Failure("string literal", position)
                        }
                        when (input[end]) {
                            'n' -> result.append('\n')
                            't' -> result.append('\t')
                            'r' -> result.append('\r')
                            '"' -> result.append('"')
                            '\\' -> result.append('\\')
                            'u' -> {
                                // Unicode escape \uXXXX
                                end++
                                if (end + 4 > input.length) {
                                    return ParseResult.Failure("string literal", position)
                                }
                                val hex = input.substring(end, end + 4)
                                val codePoint = hex.toInt(16)
                                result.append(codePoint.toChar())
                                end += 3
                            }
                            else -> result.append(input[end])
                        }
                    }
                    '\n' -> {
                        // Unterminated string at newline
                        return ParseResult.Failure("string literal", position)
                    }
                    else -> result.append(c)
                }
                end++
            }

            return ParseResult.Failure("string literal", position)
        }
    }
}

/**
 * Parser for boolean literals: true and false.
 */
fun booleanLiteral(): Parser<Expr> {
    return object : BaseParser<Expr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr> {
            if (position + 4 <= input.length && input.substring(position, position + 4) == "true") {
                return ParseResult.Success(Expr.LiteralExpr(Value.Boolean(true)), position + 4)
            }
            if (position + 5 <= input.length && input.substring(position, position + 5) == "false") {
                return ParseResult.Success(Expr.LiteralExpr(Value.Boolean(false)), position + 5)
            }
            return ParseResult.Failure("boolean literal", position)
        }
    }
}

/**
 * Parser for null literal.
 */
fun nullLiteral(): Parser<Expr> {
    return object : BaseParser<Expr>() {
        override fun parse(input: String, position: Int): ParseResult<Expr> {
            if (position + 4 <= input.length && input.substring(position, position + 4) == "null") {
                return ParseResult.Success(Expr.LiteralExpr(Value.Null), position + 4)
            }
            return ParseResult.Failure("null literal", position)
        }
    }
}

/**
 * Combined parser for all literal types.
 * Tries float first (since floats can look like integers), then string, then boolean, then null.
 */
fun literal(): Parser<Expr> {
    return object : BaseParser<Expr>() {
        private val float = floatLiteral()
        private val string = stringLiteral()
        private val boolean = booleanLiteral()
        private val nullLit = nullLiteral()

        override fun parse(input: String, position: Int): ParseResult<Expr> {
            // Try float first (since 1.0 could be mistaken for integer followed by something else)
            val floatResult = float.parse(input, position)
            if (floatResult is ParseResult.Success) return floatResult

            // Try string literal
            val stringResult = string.parse(input, position)
            if (stringResult is ParseResult.Success) return stringResult

            // Try boolean
            val boolResult = boolean.parse(input, position)
            if (boolResult is ParseResult.Success) return boolResult

            // Try null
            val nullResult = nullLit.parse(input, position)
            if (nullResult is ParseResult.Success) return nullResult

            return ParseResult.Failure("literal", position)
        }
    }
}
