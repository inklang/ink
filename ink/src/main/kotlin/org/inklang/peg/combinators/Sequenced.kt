package org.inklang.peg.combinators

import org.inklang.peg.BaseParser
import org.inklang.peg.ParseResult
import org.inklang.peg.Parser

/**
 * Combines two parsers in sequence. Succeeds only if both parsers succeed.
 * Returns a list containing both parsed values.
 */
fun <T1, T2> seq(p1: Parser<T1>, p2: Parser<T2>): Parser<List<Any?>> {
    return object : BaseParser<List<Any?>>() {
        override fun parse(input: String, position: Int): ParseResult<List<Any?>> {
            val result1 = p1.parse(input, position)
            return when (result1) {
                is ParseResult.Success -> {
                    val result2 = p2.parse(input, result1.position)
                    when (result2) {
                        is ParseResult.Success -> {
                            ParseResult.Success(listOf(result1.value, result2.value), result2.position)
                        }
                        is ParseResult.Failure -> result2
                    }
                }
                is ParseResult.Failure -> result1
            }
        }
    }
}

/**
 * Combines two parsers in sequence. Succeeds only if both parsers succeed.
 * Returns a specialized pair result.
 */
fun <T1, T2> seq2(p1: Parser<T1>, p2: Parser<T2>): Parser<List<Any?>> = seq(p1, p2)

/**
 * Combines three parsers in sequence. Succeeds only if all three parsers succeed.
 * Returns a list containing all three parsed values.
 */
fun <T1, T2, T3> seq3(p1: Parser<T1>, p2: Parser<T2>, p3: Parser<T3>): Parser<List<Any?>> {
    return object : BaseParser<List<Any?>>() {
        override fun parse(input: String, position: Int): ParseResult<List<Any?>> {
            val result1 = p1.parse(input, position)
            return when (result1) {
                is ParseResult.Success -> {
                    val result2 = p2.parse(input, result1.position)
                    when (result2) {
                        is ParseResult.Success -> {
                            val result3 = p3.parse(input, result2.position)
                            when (result3) {
                                is ParseResult.Success -> {
                                    ParseResult.Success(listOf(result1.value, result2.value, result3.value), result3.position)
                                }
                                is ParseResult.Failure -> result3
                            }
                        }
                        is ParseResult.Failure -> result2
                    }
                }
                is ParseResult.Failure -> result1
            }
        }
    }
}

/**
 * Helper class for building sequences with DSL-style block syntax.
 * Provides parser-building methods that automatically register to the sequence.
 */
class SequenceBuilder {
    val parsers = mutableListOf<Parser<*>>()

    /**
     * Matches a literal string and adds it to this sequence.
     */
    fun literal(text: String): Parser<String> {
        val parser = object : BaseParser<String>() {
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
        parsers.add(parser)
        return parser
    }

    /**
     * Matches an identifier and adds it to this sequence.
     */
    fun identifier(): Parser<String> {
        val parser = object : BaseParser<String>() {
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
        parsers.add(parser)
        return parser
    }
}

/**
 * Combines multiple parsers in sequence using a DSL-style block.
 * Allows building sequences with the { literal("a"); literal("b") } syntax.
 */
fun seq(block: SequenceBuilder.() -> Unit): Parser<List<Any?>> {
    val builder = SequenceBuilder()
    block(builder)
    return seq(*builder.parsers.toTypedArray())
}

/**
 * Combines multiple parsers in sequence.
 * @param parsers The parsers to combine in order
 * @return A parser that returns a list of all parsed values
 */
fun seq(vararg parsers: Parser<*>): Parser<List<Any?>> {
    return object : BaseParser<List<Any?>>() {
        override fun parse(input: String, position: Int): ParseResult<List<Any?>> {
            val results = mutableListOf<Any?>()
            var currentPos = position

            for (parser in parsers) {
                val result = parser.parse(input, currentPos)
                when (result) {
                    is ParseResult.Success -> {
                        results.add(result.value)
                        currentPos = result.position
                    }
                    is ParseResult.Failure -> return result
                }
            }

            return ParseResult.Success(results, currentPos)
        }
    }
}
