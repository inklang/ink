package org.inklang.peg.combinators

import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser

/**
 * Negative lookahead - asserts that a parser does NOT match without consuming input.
 * Succeeds if the parser fails, fails if the parser succeeds.
 */
fun not(parser: Parser<*>): Parser<Unit> {
    return object : BaseParser<Unit>() {
        override fun parse(input: String, position: Int): ParseResult<Unit> {
            val result = parser.parse(input, position)
            return when (result) {
                is ParseResult.Success -> {
                    // Parser matched - negative assertion fails
                    ParseResult.Failure("not ${result.value}", position)
                }
                is ParseResult.Failure -> {
                    // Parser didn't match - negative assertion succeeds
                    ParseResult.Success(Unit, position)
                }
            }
        }
    }
}

/**
 * Positive lookahead - asserts that a parser matches without consuming input.
 * Succeeds if the parser succeeds (returning Unit), fails if the parser fails.
 * Does not advance the position regardless of outcome.
 */
fun lookahead(parser: Parser<*>): Parser<Unit> {
    return object : BaseParser<Unit>() {
        override fun parse(input: String, position: Int): ParseResult<Unit> {
            val result = parser.parse(input, position)
            return when (result) {
                is ParseResult.Success -> {
                    // Parser matched - lookahead succeeds, but don't consume
                    ParseResult.Success(Unit, position)
                }
                is ParseResult.Failure -> {
                    // Parser didn't match - lookahead fails
                    ParseResult.Failure(result.expected, position)
                }
            }
        }
    }
}
