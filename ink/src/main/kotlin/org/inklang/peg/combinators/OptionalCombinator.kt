package org.inklang.peg.combinators

import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser

/**
 * Makes a parser optional.
 * If the parser succeeds, returns its value; if it fails, returns null without consuming input.
 */
fun <T> optional(parser: Parser<T>): Parser<T?> {
    return object : BaseParser<T?>() {
        override fun parse(input: String, position: Int): ParseResult<T?> {
            val result = parser.parse(input, position)
            return when (result) {
                is ParseResult.Success -> result
                is ParseResult.Failure -> {
                    // Return null without advancing position
                    ParseResult.Success(null, position)
                }
            }
        }
    }
}
