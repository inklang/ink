package org.inklang.peg

/**
 * Base interface for all PEG parsers.
 * @param T The type of value produced by successful parsing
 */
interface Parser<out T> {
    /**
     * Parse the input string starting at the given position.
     * @param input The input string to parse
     * @param position The starting position in the input (default 0)
     * @return [ParseResult.Success] with the parsed value and new position, or [ParseResult.Failure]
     */
    fun parse(input: String, position: Int = 0): ParseResult<T>
}

/**
 * Abstract base class providing common functionality for parsers.
 */
abstract class BaseParser<T> : Parser<T>

/**
 * Matches a literal string exactly.
 * Returns the matched literal text as the value.
 */
fun literal(text: String): Parser<String> = object : BaseParser<String>() {
    override fun parse(input: String, position: Int): ParseResult<String> {
        if (position >= input.length) {
            return ParseResult.Failure(text, position)
        }
        val endIndex = minOf(position + text.length, input.length)
        val matched = input.substring(position, endIndex)
        return if (matched == text) {
            ParseResult.Success(text, position + text.length)
        } else {
            ParseResult.Failure(text, position)
        }
    }
}

/**
 * Matches a valid identifier: starts with letter or underscore, followed by letters, digits, underscores.
 * Returns the identifier text as the value.
 */
fun identifier(): Parser<String> = object : BaseParser<String>() {
    override fun parse(input: String, position: Int): ParseResult<String> {
        if (position >= input.length) {
            return ParseResult.Failure("identifier", position)
        }

        val c = input[position]
        if (!isIdentifierStart(c)) {
            return ParseResult.Failure("identifier", position)
        }

        var end = position + 1
        while (end < input.length && isIdentifierChar(input[end])) {
            end++
        }

        return ParseResult.Success(input.substring(position, end), end)
    }

    private fun isIdentifierStart(c: Char): Boolean {
        return c.isLetter() || c == '_'
    }

    private fun isIdentifierChar(c: Char): Boolean {
        return c.isLetter() || c.isDigit() || c == '_'
    }
}
