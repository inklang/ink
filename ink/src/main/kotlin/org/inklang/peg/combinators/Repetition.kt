package org.inklang.peg.combinators

import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser

/**
 * Matches zero or more occurrences of a parser.
 * Always succeeds, returning an empty list if no matches.
 */
fun <T> zeroOrMore(parser: Parser<T>): Parser<List<Any?>> {
    return object : BaseParser<List<Any?>>() {
        override fun parse(input: String, position: Int): ParseResult<List<Any?>> {
            val results = mutableListOf<Any?>()
            var currentPos = position

            while (true) {
                val result = parser.parse(input, currentPos)
                when (result) {
                    is ParseResult.Success -> {
                        results.add(result.value)
                        currentPos = result.position
                    }
                    is ParseResult.Failure -> {
                        // Stop on first failure - this is the key PEG semantics
                        break
                    }
                }
                // Avoid infinite loops - if position doesn't advance, break
                if (currentPos >= input.length) {
                    break
                }
            }

            return ParseResult.Success(results, currentPos)
        }
    }
}

/**
 * Matches one or more occurrences of a parser.
 * Fails if no matches are found.
 */
fun <T> oneOrMore(parser: Parser<T>): Parser<List<Any?>> {
    return object : BaseParser<List<Any?>>() {
        override fun parse(input: String, position: Int): ParseResult<List<Any?>> {
            val results = mutableListOf<Any?>()
            var currentPos = position

            // First match must succeed
            val firstResult = parser.parse(input, currentPos)
            when (firstResult) {
                is ParseResult.Success -> {
                    results.add(firstResult.value)
                    currentPos = firstResult.position
                }
                is ParseResult.Failure -> {
                    return firstResult
                }
            }

            // Subsequent matches are optional
            while (true) {
                val result = parser.parse(input, currentPos)
                when (result) {
                    is ParseResult.Success -> {
                        results.add(result.value)
                        currentPos = result.position
                    }
                    is ParseResult.Failure -> {
                        break
                    }
                }
                if (currentPos >= input.length) {
                    break
                }
            }

            return ParseResult.Success(results, currentPos)
        }
    }
}
