package org.inklang.peg

/**
 * Result of a parsing attempt.
 * Either [Success] with the parsed value and new position, or [Failure] with error info.
 */
sealed class ParseResult<out T> {
    /**
     * Successful parse result containing the matched value and the new position.
     */
    data class Success<T>(val value: T, val position: Int) : ParseResult<T>()

    /**
     * Failed parse result containing what was expected and the position where failure occurred.
     */
    data class Failure(val expected: String, val position: Int) : ParseResult<Nothing>()
}
