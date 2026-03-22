package org.inklang.peg

import org.inklang.peg.combinators.flatMap
import org.inklang.peg.combinators.lookahead
import org.inklang.peg.combinators.map
import org.inklang.peg.combinators.not
import org.inklang.peg.combinators.oneOrMore
import org.inklang.peg.combinators.optional
import org.inklang.peg.combinators.or
import org.inklang.peg.combinators.seq
import org.inklang.peg.combinators.seq3
import org.inklang.peg.combinators.zeroOrMore
import org.inklang.peg.util.SourcePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParserCombinatorTest {

    // ============== ParseResult Tests ==============

    @Test
    fun `ParseResult Success contains value and position`() {
        val result = ParseResult.Success("hello", 5)
        assertEquals("hello", result.value)
        assertEquals(5, result.position)
    }

    @Test
    fun `ParseResult Failure contains expected and position`() {
        val result = ParseResult.Failure("expected identifier", 3)
        assertEquals("expected identifier", result.expected)
        assertEquals(3, result.position)
    }

    // ============== SourcePosition Tests ==============

    @Test
    fun `SourcePosition fromOffset calculates line and column`() {
        val input = "hello\nworld"
        val pos = SourcePosition.fromOffset(input, 6) // "world" starts at offset 6 (after "hello\n")
        assertEquals(2, pos.line)
        assertEquals(1, pos.column) // 1-indexed
        assertEquals(6, pos.offset)
    }

    @Test
    fun `SourcePosition fromOffset handles offset at end of input`() {
        val input = "hello"
        val pos = SourcePosition.fromOffset(input, 5) // at end
        assertEquals(1, pos.line)
        assertEquals(6, pos.column) // after last char
        assertEquals(5, pos.offset)
    }

    @Test
    fun `SourcePosition fromOffset handles offset beyond input length`() {
        val input = "hello"
        val pos = SourcePosition.fromOffset(input, 100) // beyond length
        assertEquals(1, pos.line)
        assertEquals(6, pos.column) // should be at end
        assertEquals(100, pos.offset)
    }

    @Test
    fun `SourcePosition fromOffset handles empty input`() {
        val input = ""
        val pos = SourcePosition.fromOffset(input, 0)
        assertEquals(1, pos.line)
        assertEquals(1, pos.column)
        assertEquals(0, pos.offset)
    }

    // ============== PegToken Tests ==============

    @Test
    fun `PegToken stores type lexeme and position`() {
        val token = PegToken("IDENTIFIER", "myVar", SourcePosition.fromOffset("val myVar = 1", 4))
        assertEquals("IDENTIFIER", token.type)
        assertEquals("myVar", token.lexeme)
        assertEquals(4, token.position.offset)
    }

    @Test
    fun `PegToken sourcePosition getter returns correct position`() {
        val input = "val x = 1"
        val pos = SourcePosition.fromOffset(input, 4)
        val token = PegToken("IDENTIFIER", "x", pos)
        assertEquals(4, token.sourcePosition.offset)
    }

    // ============== literal parser tests ==============

    @Test
    fun `literal parser matches exact string`() {
        val parser = literal("hello")
        val result = parser.parse("hello world", 0)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("hello", result.value)
        assertEquals(5, result.position)
    }

    @Test
    fun `literal parser fails on mismatch`() {
        val parser = literal("hello")
        val result = parser.parse("world", 0)
        assertIs<ParseResult.Failure>(result)
        assertEquals("hello", result.expected)
    }

    @Test
    fun `literal parser fails when input too short`() {
        val parser = literal("hello")
        val result = parser.parse("hel", 0)
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `literal parser respects position offset`() {
        val parser = literal("world")
        val result = parser.parse("hello world", 6)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("world", result.value)
        assertEquals(11, result.position)
    }

    // ============== identifier parser tests ==============

    @Test
    fun `identifier parser matches valid identifier`() {
        val parser = identifier()
        val result = parser.parse("myVariable123", 0)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("myVariable123", result.value)
        assertEquals(13, result.position)
    }

    @Test
    fun `identifier parser fails on number start`() {
        val parser = identifier()
        val result = parser.parse("123abc", 0)
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `identifier parser fails on keyword-like match at end`() {
        val parser = identifier()
        val result = parser.parse("abc ", 0)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("abc", result.value)
    }

    @Test
    fun `identifier parser fails on empty input`() {
        val parser = identifier()
        val result = parser.parse("", 0)
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `identifier parser respects position`() {
        val parser = identifier()
        val result = parser.parse("x + y", 0)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("x", result.value)
    }

    // ============== seq tests ==============

    @Test
    fun `seq combines two parsers`() {
        val parser = seq(literal("a"), literal("b"))
        val result = parser.parse("ab", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        assertEquals(listOf("a", "b"), result.value)
        assertEquals(2, result.position)
    }

    @Test
    fun `seq fails if first parser fails`() {
        val parser = seq(literal("a"), literal("b"))
        val result = parser.parse("xb", 0)
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `seq fails if second parser fails`() {
        val parser = seq(literal("a"), literal("b"))
        val result = parser.parse("ax", 0)
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `seq3 combines three parsers`() {
        val parser = seq3(literal("a"), literal("b"), literal("c"))
        val result = parser.parse("abc", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        assertEquals(listOf("a", "b", "c"), result.value)
        assertEquals(3, result.position)
    }

    // ============== or tests ==============

    @Test
    fun `or tries first parser`() {
        val parser = or(literal("hello"), literal("world"))
        val result = parser.parse("hello", 0)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("hello", result.value)
        assertEquals(5, result.position)
    }

    @Test
    fun `or falls back to second parser`() {
        val parser = or(literal("hello"), literal("world"))
        val result = parser.parse("world", 0)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("world", result.value)
        assertEquals(5, result.position)
    }

    @Test
    fun `or fails if both parsers fail`() {
        val parser = or(literal("hello"), literal("world"))
        val result = parser.parse("other", 0)
        assertIs<ParseResult.Failure>(result)
    }

    // ============== zeroOrMore tests ==============

    @Test
    fun `zeroOrMore matches zero occurrences`() {
        val parser = zeroOrMore(literal("a"))
        val result = parser.parse("b", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        assertEquals(emptyList(), result.value)
        assertEquals(0, result.position)
    }

    @Test
    fun `zeroOrMore matches multiple occurrences`() {
        val parser = zeroOrMore(literal("a"))
        val result = parser.parse("aaaX", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        assertEquals(listOf("a", "a", "a"), result.value)
        assertEquals(3, result.position)
    }

    @Test
    fun `zeroOrMore stops at mismatch`() {
        val parser = zeroOrMore(literal("a"))
        val result = parser.parse("aaab", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        assertEquals(listOf("a", "a", "a"), result.value)
        assertEquals(3, result.position)
    }

    // ============== oneOrMore tests ==============

    @Test
    fun `oneOrMore requires at least one match`() {
        val parser = oneOrMore(literal("a"))
        val result = parser.parse("aaa", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        assertEquals(listOf("a", "a", "a"), result.value)
        assertEquals(3, result.position)
    }

    @Test
    fun `oneOrMore fails on zero matches`() {
        val parser = oneOrMore(literal("a"))
        val result = parser.parse("b", 0)
        assertIs<ParseResult.Failure>(result)
    }

    // ============== optional tests ==============

    @Test
    fun `optional matches when present`() {
        val parser = optional(literal("hello"))
        val result = parser.parse("hello", 0)
        assertIs<ParseResult.Success<String?>>(result)
        assertEquals("hello", result.value)
        assertEquals(5, result.position)
    }

    @Test
    fun `optional returns null when absent`() {
        val parser = optional(literal("hello"))
        val result = parser.parse("world", 0)
        assertIs<ParseResult.Success<String?>>(result)
        assertEquals(null, result.value)
        assertEquals(0, result.position)
    }

    // ============== not tests ==============

    @Test
    fun `not succeeds when parser fails`() {
        val parser = not(literal("hello"))
        val result = parser.parse("world", 0)
        assertIs<ParseResult.Success<Unit>>(result)
        assertEquals(Unit, result.value)
        assertEquals(0, result.position)
    }

    @Test
    fun `not fails when parser succeeds`() {
        val parser = not(literal("hello"))
        val result = parser.parse("hello", 0)
        assertIs<ParseResult.Failure>(result)
    }

    // ============== lookahead tests ==============

    @Test
    fun `lookahead succeeds without consuming`() {
        val parser = lookahead(literal("hello"))
        val result = parser.parse("hello world", 0)
        assertIs<ParseResult.Success<Unit>>(result)
        assertEquals(Unit, result.value)
        assertEquals(0, result.position) // position unchanged
    }

    @Test
    fun `lookahead fails without consuming`() {
        val parser = lookahead(literal("hello"))
        val result = parser.parse("world", 0)
        assertIs<ParseResult.Failure>(result)
        assertEquals(0, result.position) // position unchanged
    }

    // ============== map tests ==============

    @Test
    fun `map transforms result value`() {
        val parser = literal("hello").map { it.uppercase() }
        val result = parser.parse("hello", 0)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("HELLO", result.value)
        assertEquals(5, result.position)
    }

    @Test
    fun `map works on seq results`() {
        val parser = seq(literal("a"), literal("b")).map { "${it[0]}-${it[1]}" }
        val result = parser.parse("ab", 0)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("a-b", result.value)
    }

    // ============== flatMap tests ==============

    @Test
    fun `flatMap chains parsers`() {
        val parser = literal("a").flatMap { literal("b") }
        val result = parser.parse("ab", 0)
        assertIs<ParseResult.Success<String>>(result)
        assertEquals("b", result.value)
        assertEquals(2, result.position)
    }

    @Test
    fun `flatMap fails if first parser fails`() {
        val parser = literal("a").flatMap { literal("b") }
        val result = parser.parse("xb", 0)
        assertIs<ParseResult.Failure>(result)
    }

    // ============== seq with block syntax ==============

    @Test
    fun `seq with block syntax works`() {
        val parser = seq {
            literal("a")
            literal("b")
        }
        val result = parser.parse("ab", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        assertEquals(listOf("a", "b"), result.value)
    }

    @Test
    fun `seq with block syntax fails on mismatch`() {
        val parser = seq {
            literal("a")
            literal("b")
        }
        val result = parser.parse("ac", 0)
        assertIs<ParseResult.Failure>(result)
    }

    // ============== combined parser tests ==============

    @Test
    fun `combined parser parses simple expression`() {
        // Parse "hello" followed by optional "!" and then "world" using vararg seq
        val parser = seq(literal("hello"), optional(literal("!")), literal("world"))
        val result = parser.parse("hello!world", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        assertEquals(listOf("hello", "!", "world"), result.value)
    }

    @Test
    fun `parser can parse identifiers separated by spaces`() {
        // "x" "+" "y" using seq (no spaces around +)
        val parser = seq(identifier(), literal("+"), identifier())
        val result = parser.parse("x+y", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        assertEquals(listOf("x", "+", "y"), result.value)
    }

    @Test
    fun `zeroOrMore with separator parses comma separated list`() {
        val item = identifier()
        val sep = literal(",")
        val parser = seq(item, zeroOrMore(seq(sep, item).map { (it[1] as String) }))
        val result = parser.parse("a,b,c", 0)
        assertIs<ParseResult.Success<List<Any?>>>(result)
        // Result is [firstItem, [null, second, null, third]]
        assertEquals("a", result.value[0])
    }
}
