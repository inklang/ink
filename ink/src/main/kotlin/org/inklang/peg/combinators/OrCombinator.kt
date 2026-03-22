package org.inklang.peg.combinators

import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser

/**
 * Combines two parsers with alternation (OR).
 * Tries the first parser; if it fails, tries the second.
 * Returns the result of the first successful parser.
 */
fun <T> or(p1: Parser<T>, p2: Parser<T>): Parser<T> {
    return object : BaseParser<T>() {
        override fun parse(input: String, position: Int): ParseResult<T> {
            val result1 = p1.parse(input, position)
            return when (result1) {
                is ParseResult.Success -> result1
                is ParseResult.Failure -> {
                    // Try second parser
                    p2.parse(input, position)
                }
            }
        }
    }
}
