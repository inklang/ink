package org.inklang.peg.combinators

import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser

/**
 * Transforms the result of a parser using a function.
 */
fun <T, R> Parser<T>.map(transform: (T) -> R): Parser<R> {
    return object : BaseParser<R>() {
        override fun parse(input: String, position: Int): ParseResult<R> {
            val result = this@map.parse(input, position)
            return when (result) {
                is ParseResult.Success -> ParseResult.Success(transform(result.value), result.position)
                is ParseResult.Failure -> result
            }
        }
    }
}

/**
 * Chains a second parser based on the result of the first parser.
 * The second parser receives the input at the same position as the first.
 */
fun <T, R> Parser<T>.flatMap(transform: (T) -> Parser<R>): Parser<R> {
    return object : BaseParser<R>() {
        override fun parse(input: String, position: Int): ParseResult<R> {
            val result = this@flatMap.parse(input, position)
            return when (result) {
                is ParseResult.Success -> {
                    val secondParser = transform(result.value)
                    secondParser.parse(input, result.position)
                }
                is ParseResult.Failure -> result
            }
        }
    }
}
