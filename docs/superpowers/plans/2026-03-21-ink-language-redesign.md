# Ink Language Redesign Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a PEG parser combinator backbone and grammar extension system for Ink, replacing the hand-written Pratt parser while preserving the existing VM/backend.

**Architecture:** The plan builds a parser combinator library in Kotlin on top of which grammars are expressed as composable PEG rules. Package authors register grammar extensions via a typed Kotlin DSL (`ink.registerStatement`, `ink.registerDeclaration`, `ink.registerRule`). At startup, all registered grammars are merged into a combined grammar before parsing begins. The existing VM, IR, register allocator, and SSA infrastructure are reused unchanged.

**Tech Stack:** Kotlin 2.2.21, JVM 21, existing Ink build system (Gradle). No external PEG libraries — parser combinators are built from scratch.

---

## Chunk 1: PEG Parser Combinator Backbone

**Goal:** A minimal working parser combinator library — enough to parse simple expressions — before any Ink-specific grammar.

### File Map

```
lang/src/main/kotlin/org/inklang/
├── peg/                                    # NEW - parser combinator library
│   ├── ParseResult.kt                      # Success/failure result type
│   ├── Parser.kt                          # Base parser interface + combinators
│   ├── combinators/                        # Reusable combinators
│   │   ├── Sequenced.kt                   # seq { } - sequential matching
│   │   ├── OrCombinator.kt                # or { } - alternation
│   │   ├── Repetition.kt                  # zeroOrMore, oneOrMore
│   │   ├── OptionalCombinator.kt          # optional { }
│   │   ├── PredicateCombinators.kt        # not { }, lookahead
│   │   └── TransformCombinator.kt         # map { } - AST transformation
│   └── util/
│       ├── SourcePosition.kt               # Line/column tracking for error messages
│       └── Memoization.kt                 # Packrat parsing cache (optional, v2)
```

- Create: `lang/src/main/kotlin/org/inklang/peg/ParseResult.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/Parser.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/combinators/Sequenced.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/combinators/OrCombinator.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/combinators/Repetition.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/combinators/OptionalCombinator.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/combinators/PredicateCombinators.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/combinators/TransformCombinator.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/util/SourcePosition.kt`
- Test: `lang/src/test/kotlin/org/inklang/peg/ParserCombinatorTest.kt`

- [ ] **Step 0: Add Token type to PEG library**

The existing `org.inklang.lang.Token` class is tightly coupled to the Lexer's output. The PEG library needs its own lightweight `PegToken` for position tracking:

```kotlin
// lang/src/main/kotlin/org/inklang/peg/PegToken.kt
package org.inklang.peg

data class PegToken(
    val type: String,       // "IDENTIFIER", "NUMBER", "STRING", "PLUS", etc.
    val lexeme: String,     // raw text
    val position: Int       // byte offset in source
) {
    val sourcePosition: SourcePosition get() = SourcePosition.fromOffset(offset, position)
}
```

The existing `org.inklang.lang.Token` and `TokenType` should NOT be imported into the PEG library — keep them independent. When the PEG parser produces `org.inklang.lang.Expr` nodes, it will construct Tokens using the existing Token constructor with the PegToken's position info.

- [ ] **Step 1: Write failing tests for ParseResult and Parser**

```kotlin
// lang/src/test/kotlin/org/inklang/peg/ParserCombinatorTest.kt
package org.inklang.peg

import org.inklang.peg.combinators.*
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class ParserCombinatorTest {

    @Test
    fun `literal matches exact string`() {
        val parser = literal("hello")
        val result = parser.parse("hello world")
        assertIs<ParseResult.Success>(result)
        assertEquals("hello", result.value)
        assertEquals(5, result.position)
    }

    @Test
    fun `literal fails on mismatch`() {
        val parser = literal("hello")
        val result = parser.parse("goodbye")
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `seq matches all in order`() {
        val parser = seq { literal("hello"); literal("world") }
        val result = parser.parse("helloworld")
        assertIs<ParseResult.Success>(result)
        assertEquals(listOf("hello", "world"), result.value)
    }

    @Test
    fun `or tries first branch`() {
        val parser = or { literal("hello"); literal("world") }
        val result = parser.parse("hello")
        assertIs<ParseResult.Success>(result)
        assertEquals("hello", result.value)
    }

    @Test
    fun `or falls back to second branch`() {
        val parser = or { literal("hello"); literal("world") }
        val result = parser.parse("world")
        assertIs<ParseResult.Success>(result)
        assertEquals("world", result.value)
    }

    @Test
    fun `zeroOrMore matches zero on no match`() {
        val parser = zeroOrMore { literal("a") }
        val result = parser.parse("bbb")
        assertIs<ParseResult.Success>(result)
        assertEquals(emptyList(), result.value)
    }

    @Test
    fun `zeroOrMore matches multiple`() {
        val parser = zeroOrMore { literal("a") }
        val result = parser.parse("aaab")
        assertEquals(listOf("a", "a", "a"), result.value)
    }

    @Test
    fun `oneOrMore fails on zero matches`() {
        val parser = oneOrMore { literal("a") }
        val result = parser.parse("bbb")
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `map transforms result`() {
        val parser = literal("hello").map { it.length }
        val result = parser.parse("hello")
        assertIs<ParseResult.Success>(result)
        assertEquals(5, result.value)
    }

    @Test
    fun `identifier parses valid identifier`() {
        val parser = identifier()
        val result = parser.parse("foo bar")
        assertEquals("foo", result.value)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :lang:test --tests "org.inklang.peg.ParserCombinatorTest" 2>&1 | head -50`
Expected: FAIL — classes don't exist yet

- [ ] **Step 3: Implement ParseResult**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/ParseResult.kt
package org.inklang.peg

sealed class ParseResult<out T> {
    data class Success<T>(
        val value: T,
        val position: Int,
        val expected: List<String> = emptyList()
    ) : ParseResult<T>()

    data class Failure(
        val position: Int,
        val expected: List<String>,
        val message: String = "expected one of $expected at position $position"
    ) : ParseResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
}
```

- [ ] **Step 4: Implement base Parser interface and literal/identifier**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/Parser.kt
package org.inklang.peg

interface Parser<out T> {
    fun parse(input: String, position: Int = 0): ParseResult<T>
    fun parse(input: String): ParseResult<T> = parse(input, 0)

    // Combinators
    infix fun <U> map(transform: (T) -> U): Parser<U>
    infix fun <U> flatMap(transform: (T) -> Parser<U>): Parser<U>
    infix fun <U> or(other: Parser<out U>): Parser<T>
    fun optional(): Parser<T?>
    fun zeroOrMore(): Parser<List<T>>
    fun oneOrMore(): Parser<List<T>>
}

abstract class BaseParser<T> : Parser<T> {
    override fun <U> map(transform: (T) -> U): Parser<U> = MapParser(this, transform)
    override fun <U> flatMap(transform: (T) -> Parser<U>): Parser<U> = FlatMapParser(this, transform)
    override infix fun <U> or(other: Parser<out U>): Parser<T> = OrParser(this, other)
    override fun optional(): Parser<T?> = OptionalParser(this)
    override fun zeroOrMore(): Parser<List<T>> = ZeroOrMoreParser(this)
    override fun oneOrMore(): Parser<List<T>> = OneOrMoreParser(this)
}

class LiteralParser(private val literal: String) : BaseParser<String>() {
    override fun parse(input: String, position: Int): ParseResult<String> {
        if (input.regionMatches(position, literal, 0, literal.length)) {
            return ParseResult.Success(literal, position + literal.length)
        }
        return ParseResult.Failure(position, listOf("\"$literal\""))
    }
}

fun literal(s: String): Parser<String> = LiteralParser(s)

fun identifier(): Parser<String> = object : BaseParser<String>() {
    private val regex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*")
    override fun parse(input: String, position: Int): ParseResult<String> {
        val match = regex.find(input, position)
        return if (match != null && match.range.first == position) {
            ParseResult.Success(match.value, match.range.last + 1)
        } else {
            ParseResult.Failure(position, listOf("identifier"))
        }
    }
}
```

- [ ] **Step 5: Implement seq combinator**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/combinators/Sequenced.kt
package org.inklang.peg.combinators

import org.inklang.peg.*

class SequencedParser<T>(
    private val parsers: List<Parser<*>>
) : BaseParser<List<Any?>>() {
    override fun parse(input: String, position: Int): ParseResult<List<Any?>> {
        val results = mutableListOf<Any?>()
        var pos = position
        val expected = mutableListOf<String>()

        for (parser in parsers) {
            when (val result = parser.parse(input, pos)) {
                is ParseResult.Success -> {
                    results.add(result.value)
                    pos = result.position
                }
                is ParseResult.Failure -> {
                    expected.addAll(result.expected)
                    return ParseResult.Failure(position, expected, "sequence failed at position $pos")
                }
            }
        }
        return ParseResult.Success(results, pos)
    }
}

fun seq(vararg parsers: Parser<*>): Parser<List<Any?>> = SequencedParser(parsers.toList())

/** DSL helpers for exactly-2 and exactly-3 sequences — avoids List<Any?> casts */
fun <A, B> seq2(a: Parser<A>, b: Parser<B>): Parser<List<Any?>> = SequencedParser(listOf(a, b))
fun <A, B, C> seq3(a: Parser<A>, b: Parser<B>, c: Parser<C>): Parser<List<Any?>> = SequencedParser(listOf(a, b, c))

- [ ] **Step 6: Implement OrParser, OptionalParser, ZeroOrMoreParser, OneOrMoreParser, MapParser, FlatMapParser**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/combinators/OrCombinator.kt
package org.inklang.peg.combinators
import org.inklang.peg.*

class OrParser<T>(
    private val first: Parser<T>,
    private val second: Parser<out T>
) : BaseParser<T>() {
    override fun parse(input: String, position: Int): ParseResult<T> {
        val result1 = first.parse(input, position)
        if (result1 is ParseResult.Success) return result1
        val result2 = second.parse(input, position)
        if (result2 is ParseResult.Success) return result2
        // Combine expected from both
        val expected = (result1.expected) + (result2.expected)
        return ParseResult.Failure(position, expected)
    }
}
```

```kotlin
// lang/src/main/kotlin/org/inklang/peg/combinators/OptionalCombinator.kt
package org.inklang.peg.combinators
import org.inklang.peg.*

class OptionalParser<T>(private val parser: Parser<T>) : BaseParser<T?>() {
    override fun parse(input: String, position: Int): ParseResult<T?> {
        val result = parser.parse(input, position)
        return when (result) {
            is ParseResult.Success -> result
            is ParseResult.Failure -> ParseResult.Success(null, position)
        }
    }
}
```

```kotlin
// lang/src/main/kotlin/org/inklang/peg/combinators/Repetition.kt
package org.inklang.peg.combinators
import org.inklang.peg.*

class ZeroOrMoreParser<T>(private val parser: Parser<T>) : BaseParser<List<T>>() {
    override fun parse(input: String, position: Int): ParseResult<List<T>> {
        val results = mutableListOf<T>()
        var pos = position
        while (true) {
            val result = parser.parse(input, pos)
            when (result) {
                is ParseResult.Success -> {
                    @Suppress("UNCHECKED_CAST")
                    results.add(result.value as T)
                    pos = result.position
                }
                is ParseResult.Failure -> break
            }
        }
        return ParseResult.Success(results, pos)
    }
}

class OneOrMoreParser<T>(private val parser: Parser<T>) : BaseParser<List<T>>() {
    override fun parse(input: String, position: Int): ParseResult<List<T>> {
        val firstResult = parser.parse(input, position)
        if (firstResult is ParseResult.Failure) return firstResult
        val first = firstResult as ParseResult.Success<T>
        val results = mutableListOf<T>(first.value)
        var pos = first.position
        while (true) {
            val result = parser.parse(input, pos)
            when (result) {
                is ParseResult.Success -> {
                    results.add(result.value)
                    pos = result.position
                }
                is ParseResult.Failure -> break
            }
        }
        return ParseResult.Success(results, pos)
    }
}
```

```kotlin
// lang/src/main/kotlin/org/inklang/peg/combinators/TransformCombinator.kt
package org.inklang.peg.combinators
import org.inklang.peg.*

class MapParser<T, U>(
    private val parser: Parser<T>,
    private val transform: (T) -> U
) : BaseParser<U>() {
    override fun parse(input: String, position: Int): ParseResult<U> {
        return when (val result = parser.parse(input, position)) {
            is ParseResult.Success -> ParseResult.Success(transform(result.value), result.position)
            is ParseResult.Failure -> result
        }
    }
}

class FlatMapParser<T, U>(
    private val parser: Parser<T>,
    private val transform: (T) -> Parser<U>
) : BaseParser<U>() {
    override fun parse(input: String, position: Int): ParseResult<U> {
        return when (val result = parser.parse(input, position)) {
            is ParseResult.Success -> transform(result.value).parse(input, result.position)
            is ParseResult.Failure -> result
        }
    }
}
```

```kotlin
// lang/src/main/kotlin/org/inklang/peg/util/SourcePosition.kt
package org.inklang.peg.util

data class SourcePosition(
    val offset: Int,
    val line: Int,
    val column: Int
) {
    companion object {
        fun fromOffset(input: String, offset: Int): SourcePosition {
            var line = 1
            var column = 1
            val safeOffset = minOf(offset, input.length.coerceAtLeast(0))
            for (i in 0 until safeOffset) {
                if (input[i] == '\n') {
                    line++
                    column = 1
                } else {
                    column++
                }
            }
            return SourcePosition(offset, line, column)
        }
    }

    override fun toString(): String = "$line:$column"
}
```

- [ ] **Step 7: Run tests — all should pass**

Run: `./gradlew :lang:test --tests "org.inklang.peg.ParserCombinatorTest" 2>&1 | tail -20`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/peg/ lang/src/test/kotlin/org/inklang/peg/
git commit -m "feat: add PEG parser combinator backbone

- ParseResult (Success/Failure) sealed class
- BaseParser with combinator extensions (map, flatMap, or, optional, zeroOrMore, oneOrMore)
- LiteralParser, OrParser, OptionalParser, ZeroOrMoreParser, OneOrMoreParser, MapParser, FlatMapParser
- SourcePosition for error reporting"
```

---

## Chunk 2: Expression Parsing with PEG

**Goal:** Express the base Ink expression grammar (literals, variables, binary ops, function calls) using the combinator library. Validate the framework is expressive enough for a real language subset.

### File Map

```
lang/src/main/kotlin/org/inklang/peg/
├── ExpressionParser.kt                      # Recursive descent expression parser using combinators
├── GrammarContext.kt                        # Mutable grammar registry
├── ink/                                     # Ink-specific grammar rules
│   ├── InkGrammar.kt                        # Root grammar builder
│   ├── expressions/
│   │   ├── Literals.kt                      # number, string, boolean, null
│   │   ├── Variables.kt                     # identifier
│   │   ├── BinaryOps.kt                     # infix operators with precedence
│   │   ├── Calls.kt                         # function calls, arguments
│   │   └── grouping.kt                      # parenthesized expressions
│   └── statements/
│       ├── Print.kt                         # print statement
```

- Create: `lang/src/main/kotlin/org/inklang/peg/GrammarContext.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/ExpressionParser.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/ink/expressions/Literals.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/ink/expressions/Variables.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/ink/expressions/BinaryOps.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/ink/expressions/Calls.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/ink/expressions/Grouping.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/ink/InkGrammar.kt`
- Create: `lang/src/test/kotlin/org/inklang/peg/ink/ExpressionParserTest.kt`

- [ ] **Step 1: Write failing tests for expression parsing**

```kotlin
// lang/src/test/kotlin/org/inklang/peg/ink/ExpressionParserTest.kt
package org.inklang.peg.ink

import org.inklang.peg.*
import org.inklang.peg.ink.*
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class ExpressionParserTest {

    private val grammar = InkGrammar()

    @Test
    fun `integer literal parses`() {
        val result = grammar.expression.parse("42")
        assertIs<ParseResult.Success>(result)
        assertEquals(42, result.value)
    }

    @Test
    fun `string literal parses`() {
        val result = grammar.expression.parse("\"hello\"")
        assertIs<ParseResult.Success>(result)
        assertEquals("hello", result.value)
    }

    @Test
    fun `boolean literals parse`() {
        assertEquals(true, grammar.expression.parse("true").value)
        assertEquals(false, grammar.expression.parse("false").value)
    }

    @Test
    fun `identifier parses`() {
        val result = grammar.expression.parse("foo")
        assertIs<ParseResult.Success>(result)
        assertEquals("foo", result.value)
    }

    @Test
    fun `binary addition parses`() {
        val result = grammar.expression.parse("1 + 2")
        assertIs<ParseResult.Success>(result)
    }

    @Test
    fun `binary precedence - mul before add`() {
        val result = grammar.expression.parse("1 + 2 * 3")
        assertIs<ParseResult.Success>(result)
        // Should be 1 + (2 * 3), not (1 + 2) * 3
    }

    @Test
    fun `parenthesized expression parses`() {
        val result = grammar.expression.parse("(1 + 2)")
        assertIs<ParseResult.Success>(result)
        assertEquals(3, result.value)
    }

    @Test
    fun `function call parses`() {
        val result = grammar.expression.parse("foo(1, 2)")
        assertIs<ParseResult.Success>(result)
    }

    @Test
    fun `chained function calls parse`() {
        val result = grammar.expression.parse("foo(1)(2)")
        assertIs<ParseResult.Success>(result)
    }

    @Test
    fun `null literal parses`() {
        val result = grammar.expression.parse("null")
        assertIs<ParseResult.Success>(result)
        assertEquals(null, result.value)
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

- [ ] **Step 3: Implement GrammarContext — the mutable registry**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/GrammarContext.kt
package org.inklang.peg

/**
 * Mutable registry for grammar rules. Packages register rules here at startup.
 * GrammarContext is passed to the combined grammar builder to resolve all rules.
 */
class GrammarContext {
    private val rules = mutableMapOf<String, Parser<*>>()

    fun register(name: String, parser: Parser<*>) {
        rules[name] = parser
    }

    fun get(name: String): Parser<*>? = rules[name]

    fun build(): Map<String, Parser<*>> = rules.toMap()
}
```

- [ ] **Step 4: Implement literal parsers**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/ink/expressions/Literals.kt
package org.inklang.peg.ink.expressions

import org.inklang.peg.*

fun integerLiteral(): Parser<Int> = object : BaseParser<Int>() {
    private val regex = Regex("^-?[0-9]+")
    override fun parse(input: String, position: Int): ParseResult<Int> {
        val match = regex.find(input, position) ?: return ParseResult.Failure(position, listOf("integer"))
        if (match.range.first != position) return ParseResult.Failure(position, listOf("integer"))
        return ParseResult.Success(match.value.toInt(), match.range.last + 1)
    }
}

fun floatLiteral(): Parser<Double> = object : BaseParser<Double>() {
    private val regex = Regex("^-?[0-9]+\\.[0-9]+")
    override fun parse(input: String, position: Int): ParseResult<Double> {
        val match = regex.find(input, position) ?: return ParseResult.Failure(position, listOf("float"))
        if (match.range.first != position) return ParseResult.Failure(position, listOf("float"))
        return ParseResult.Success(match.value.toDouble(), match.range.last + 1)
    }
}

fun stringLiteral(): Parser<String> = object : BaseParser<String>() {
    override fun parse(input: String, position: Int): ParseResult<String> {
        if (position >= input.length || input[position] != '"') {
            return ParseResult.Failure(position, listOf("string"))
        }
        var pos = position + 1
        val sb = StringBuilder()
        while (pos < input.length && input[pos] != '"') {
            if (input[pos] == '\\' && pos + 1 < input.length) {
                pos++
                when (input[pos]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(input[pos])
                }
            } else {
                sb.append(input[pos])
            }
            pos++
        }
        if (pos >= input.length) return ParseResult.Failure(position, listOf("string"))
        return ParseResult.Success(sb.toString(), pos + 1)
    }
}

fun booleanLiteral(): Parser<Boolean> = or(
    literal("true").map { true },
    literal("false").map { false }
)

fun nullLiteral(): Parser<Nothing?> = literal("null").map { null }
```

- [ ] **Step 5: Implement binary operators with precedence**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/ink/expressions/BinaryOps.kt
package org.inklang.peg.ink.expressions

import org.inklang.peg.*

/**
 * Pratt-style precedence climbing implemented with combinators.
 * Each level returns a parser that parses the left side then greedily
 * consumes matching operators.
 */
class BinaryOpParser(
    private val sub: Parser<*>,
    private val op: Parser<*>,
    private val result: (Any?, Any?) -> Any?
) : BaseParser<Any?>() {
    override fun parse(input: String, position: Int): ParseResult<Any?> {
        val leftResult = sub.parse(input, position) as? ParseResult.Success ?: return leftResult
        var left = leftResult.value
        var pos = leftResult.position

        while (true) {
            val opResult = op.parse(input, pos) as? ParseResult.Success ?: break
            val rightResult = sub.parse(input, opResult.position) as? ParseResult.Success ?: break
            left = result(left, rightResult.value)
            pos = rightResult.position
        }
        return ParseResult.Success(left, pos)
    }
}

fun precedenceLevels(levelBuilders: List<(Parser<*>, Parser<*>, (Any?, Any?) -> Any?) -> Parser<*>>): Parser<Any?> {
    // Build from lowest to highest precedence
    return levelBuilders.foldRight(null as Parser<Any?>?) { builder, acc ->
        // builder creates a parser from (sub, op, combine)
    }
}
```

Note: The binary operator implementation needs proper Pratt-style precedence climbing.

**Correct approach: precedence climbing combinator**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/ink/expressions/BinaryOps.kt
package org.inklang.peg.ink.expressions

import org.inklang.peg.*
import org.inklang.lang.TokenType

/**
 * Pratt-style precedence climbing using parser combinators.
 * Each level of precedence is a parser that:
 *   1. Parses the left operand (sub-expression at higher precedence)
 *   2. Loops consuming operators at this precedence level
 *   3. For left-associative ops: continues consuming while operators match
 *   4. For right-associative ops: consumes only one operator then stops
 *
 * Grammar returns org.inklang.lang.Expr directly — NO custom Expr types.
 */

enum class Assoc { LEFT, RIGHT }

/**
 * Build a binary operator expression parser at a given precedence.
 * - sub: parser for sub-expressions (higher precedence)
 * - op: parser that matches the operator token
 * - assoc: LEFT (a+b+c = (a+b)+c) or RIGHT (a=b=c = a=(b=c))
 * - makeExpr: construct a BinaryExpr from left, operator lexeme, and right
 */
fun binaryOp(
    sub: Parser<Expr>,
    op: Parser<PegToken>,
    assoc: Assoc,
    makeExpr: (Expr, PegToken, Expr) -> Expr
): Parser<Expr> = object : BaseParser<Expr>() {
    override fun parse(input: String, position: Int): ParseResult<Expr> {
        val leftResult = sub.parse(input, position) as? ParseResult.Success
            ?: return ParseResult.Failure(position, listOf("expression"))

        var left = leftResult.value
        var pos = leftResult.position

        while (true) {
            val opResult = op.parse(input, pos) as? ParseResult.Success ?: break
            val rightResult = sub.parse(input, opResult.position) as? ParseResult.Success
            if (rightResult == null) break  // valid: "3 +" is failure

            left = makeExpr(left, opResult.value, rightResult.value)
            pos = rightResult.position

            // Right-associative: stop after one op (the recursion handles further ops)
            if (assoc == Assoc.RIGHT) break
        }
        return ParseResult.Success(left, pos)
    }
}

/**
 * Prefix (unary) operator parser.
 * - op: parser matching the operator
 * - sub: parser for the operand
 */
fun prefixOp(op: Parser<PegToken>, sub: Parser<Expr>): Parser<Expr> =
    seq2(op, sub).map { (opTok, operand) ->
        makeUnaryExpr(opTok as PegToken, operand as Expr)
    }

/** Build a complete expression parser with precedence from primary up to assignment */
fun buildExpressionParser(primary: Parser<Expr>): Parser<Expr> {
    // Highest precedence: function calls (postfix)
    val callParser = postfixChain(primary) { base, args ->
        makeCallExpr(base as Expr, args as List<Expr>)
    }

    // Unary (prefix) — highest precedence after calls
    val unary = prefixOp(literalToken("-"), callParser).or(callParser)

    // Mul/div/mod — precedence 7
    val mul = binaryOp(unary, orToken("*", "/", "%"), Assoc.LEFT) { l, op, r ->
        makeBinaryExpr(l, op, r)
    }

    // Add/sub — precedence 6
    val add = binaryOp(mul, orToken("+", "-"), Assoc.LEFT) { l, op, r ->
        makeBinaryExpr(l, op, r)
    }

    // Comparison — precedence 5
    val cmp = binaryOp(add, orToken("<", ">", "<=", ">="), Assoc.LEFT) { l, op, r ->
        makeBinaryExpr(l, op, r)
    }

    // Equality — precedence 4
    val eq = binaryOp(cmp, orToken("==", "!="), Assoc.LEFT) { l, op, r ->
        makeBinaryExpr(l, op, r)
    }

    // Logical AND — precedence 3
    val and = binaryOp(eq, keywordToken("and"), Assoc.LEFT) { l, op, r ->
        makeBinaryExpr(l, op, r)
    }

    // Logical OR — precedence 2
    val or = binaryOp(and, keywordToken("or"), Assoc.LEFT) { l, op, r ->
        makeBinaryExpr(l, op, r)
    }

    // Assignment — precedence 1 (RIGHT associative)
    val assign = binaryOp(or, literalToken("="), Assoc.RIGHT) { l, op, r ->
        makeAssignExpr(l, r)
    }

    return assign
}
```

Key helpers that construct `org.inklang.lang.Expr` directly:

```kotlin
// These helpers MUST produce org.inklang.lang.Expr, NOT custom types
// The existing AstLowerer expects these specific types

private fun makeBinaryExpr(left: Expr, op: PegToken, right: Expr): Expr {
    val token = Token(op.type, op.lexeme, op.position)  // convert PegToken → lang.Token
    return Expr.BinaryExpr(left, token, right)
}

private fun makeUnaryExpr(op: PegToken, operand: Expr): Expr {
    val token = Token(op.type, op.lexeme, op.position)
    return Expr.UnaryExpr(token, operand)
}

private fun makeCallExpr(base: Expr, args: List<Expr>): Expr {
    val paren = Token(TokenType.LPAREN, "(", 0)  // synthetic
    return Expr.CallExpr(base, paren, args, emptyMap())
}

private fun makeAssignExpr(left: Expr, right: Expr): Expr {
    return Expr.AssignExpr(left as Expr.VariableExpr, right)
}

// Helper parsers
fun literalToken(s: String): Parser<PegToken> = object : BaseParser<PegToken>() {
    override fun parse(input: String, position: Int): ParseResult<PegToken> {
        if (input.regionMatches(position, s, 0, s.length)) {
            return ParseResult.Success(PegToken(s, s, position), position + s.length)
        }
        return ParseResult.Failure(position, listOf("\"$s\""))
    }
}

fun keywordToken(kw: String): Parser<PegToken> = literalToken(kw)  // same as literal for now (lexical analysis separates keywords from identifiers)

fun orToken(vararg ops: String): Parser<PegToken> = or(ops.map { literalToken(it) })
```

Important: The above produces `org.inklang.lang.Expr` nodes directly. The `Token` constructor on lines above uses `org.inklang.lang.Token`. The `Expr` types (`BinaryExpr`, `UnaryExpr`, `CallExpr`, `AssignExpr`) are the existing ones from `org.inklang.lang.AST`. No custom AST types are introduced.

- [ ] **Step 6: Implement InkGrammar**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/ink/InkGrammar.kt
package org.inklang.peg.ink

import org.inklang.peg.*
import org.inklang.peg.ink.expressions.*

/**
 * The base Ink grammar expressed as PEG rules using parser combinators.
 * Produces org.inklang.lang.Expr / org.inklang.lang.Stmt directly
 * to drive the existing AstLowerer without any adapter layer.
 */
class InkGrammar {
    val expression: Parser<org.inklang.lang.Expr> = buildExpressionParser()

    private fun primary(): Parser<org.inklang.lang.Expr> {
        return or(
            integerLiteral().map { makeIntLiteral(it) },
            floatLiteral().map { makeFloatLiteral(it) },
            stringLiteral().map { makeStringLiteral(it) },
            booleanLiteral().map { makeBooleanLiteral(it) },
            nullLiteral().map { makeNullLiteral() },
            variableParser().map { makeVariableExpr(it) },
            parenthesizedExpr()
        )
    }

    private fun buildExpressionParser(): Parser<org.inklang.lang.Expr> {
        val primaryParser = primary()
        return buildExpressionParser(primaryParser)
    }
}
```

See `BinaryOps.kt` above for the full `buildExpressionParser` implementation with precedence climbing.

- [ ] **Step 7: Run expression parser tests — all pass**

- [ ] **Step 8: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/peg/GrammarContext.kt lang/src/main/kotlin/org/inklang/peg/ink/ lang/src/test/kotlin/org/inklang/peg/ink/
git commit -m "feat: add expression parser using PEG combinators

- GrammarContext as mutable rule registry
- Literal parsers (integer, float, string, boolean, null)
- Binary operator precedence parsing
- InkGrammar with full expression grammar"
```

---

## Chunk 3: Grammar Extension Registration API

**Goal:** Build the `ink.registerStatement`, `ink.registerDeclaration`, `ink.registerRule` API that packages use to contribute grammar. Implement the combined grammar builder that merges all package grammars at startup.

### File Map

```
lang/src/main/kotlin/org/inklang/peg/
├── InkExtensionContext.kt                   # Package author's API (registerStatement etc)
├── CombinedGrammarBuilder.kt               # Merges all registered grammars
├── ExtensionRegistry.kt                    # Global registry of all registered extensions
```

- Create: `lang/src/main/kotlin/org/inklang/peg/InkExtensionContext.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/CombinedGrammarBuilder.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/ExtensionRegistry.kt`
- Modify: `lang/src/main/kotlin/org/inklang/InkCompiler.kt` — add extension loading
- Test: `lang/src/test/kotlin/org/inklang/peg/GrammarExtensionTest.kt`

- [ ] **Step 1: Write failing tests for grammar extension registration**

```kotlin
// lang/src/test/kotlin/org/inklang/peg/GrammarExtensionTest.kt
package org.inklang.peg

import org.inklang.peg.ink.*
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class GrammarExtensionTest {

    @Test
    fun `registerStatement adds statement to grammar`() {
        val ctx = InkExtensionContext()
        ctx.registerStatement("print") {
            pattern = seq(literal("print"), grammar.expression)
            lower { expr ->
                PrintStatement(expr as Expr)
            }
        }
        val registry = ctx.build()
        assertEquals(true, registry.statements.containsKey("print"))
    }

    @Test
    fun `registerDeclaration adds declaration shell`() {
        val ctx = InkExtensionContext()
        ctx.registerDeclaration("mob") {
            fields { field("health", "Int"); field("name", "String") }
            blocks { block("on spawn"); block("on death") }
        }
        val registry = ctx.build()
        assertEquals(true, registry.declarations.containsKey("mob"))
    }

    @Test
    fun `CombinedGrammarBuilder merges multiple extensions`() {
        val ctx1 = InkExtensionContext()
        ctx1.registerStatement("when") { ... }
        val ctx2 = InkExtensionContext()
        ctx2.registerStatement("given") { ... }
        val builder = CombinedGrammarBuilder()
        val grammar = builder.merge(listOf(ctx1.build(), ctx2.build()))
        assertEquals(true, grammar.statements.containsKey("when"))
        assertEquals(true, grammar.statements.containsKey("given"))
    }

    @Test
    fun `keyword conflict is detected`() {
        val ctx1 = InkExtensionContext()
        ctx1.registerStatement("when") { ... }
        val ctx2 = InkExtensionContext()
        ctx2.registerStatement("when") { ... }
        val builder = CombinedGrammarBuilder()
        // Should throw or return error on conflict
        val result = builder.tryMerge(listOf(ctx1.build(), ctx2.build()))
        assertIs<CombinedGrammarBuilder.MergeError>(result)
    }
}
```

- [ ] **Step 2: Implement InkExtensionContext**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/InkExtensionContext.kt
package org.inklang.peg

import org.inklang.peg.ink.*

/**
 * The API package authors use to register grammar extensions.
 * Thread-safe for use during package initialization.
 */
class InkExtensionContext {
    private val _statements = mutableMapOf<String, StatementExtension>()
    private val _declarations = mutableMapOf<String, DeclarationExtension>()
    private val _rules = mutableMapOf<String, Parser<*>>()

    val statements: Map<String, StatementExtension> get() = _statements
    val declarations: Map<String, DeclarationExtension> get() = _declarations
    val rules: Map<String, Parser<*>> get() = _rules

    fun registerStatement(name: String, block: StatementExtensionBuilder.() -> Unit) {
        val builder = StatementExtensionBuilder().apply(block)
        _statements[name] = builder.build()
    }

    fun registerDeclaration(name: String, block: DeclarationExtensionBuilder.() -> Unit) {
        val builder = DeclarationExtensionBuilder().apply(block)
        _declarations[name] = builder.build()
    }

    fun registerRule(name: String, parser: Parser<*>) {
        _rules[name] = parser
    }

    fun build(): PackageGrammar {
        return PackageGrammar(_statements.toMap(), _declarations.toMap(), _rules.toMap())
    }
}

data class PackageGrammar(
    val statements: Map<String, StatementExtension>,
    val declarations: Map<String, DeclarationExtension>,
    val rules: Map<String, Parser<*>>
)

class StatementExtensionBuilder {
    lateinit var pattern: Parser<*>
    var lower: (List<Any?>) -> Stmt = { Stmt.Expr(it.first() as Expr) }

    fun build(): StatementExtension = StatementExtension(pattern, lower)
}

class DeclarationExtensionBuilder {
    lateinit var pattern: Parser<*>   // PEG pattern for the declaration body
    val fields = mutableListOf<FieldDef>()
    val blocks = mutableListOf<String>()
    var lower: (List<Any?>) -> org.inklang.lang.Stmt = { args ->
        // Default lowering: produce a declaration statement
        org.inklang.lang.Stmt.ExprStmt(
            org.inklang.lang.Expr.LiteralExpr(
                org.inklang.lang.Value.String("decl: ${args.first()}")
            )
        )
    }

    fun fields(vararg f: FieldDef) { fields.addAll(f) }
    fun blocks(vararg b: String) { blocks.addAll(b) }

    fun build(): DeclarationExtension = DeclarationExtension(pattern, fields.toList(), blocks.toList(), lower)
}

data class StatementExtension(
    val pattern: Parser<*>,
    val lower: (List<Any?>) -> org.inklang.lang.Stmt
)

data class DeclarationExtension(
    val pattern: Parser<*>,         // pattern for parsing the declaration body
    val fields: List<FieldDef>,
    val blocks: List<String>,
    val lower: (List<Any?>) -> org.inklang.lang.Stmt  // how to lower to Stmt
)

data class FieldDef(val name: String, val type: String)
```

- [ ] **Step 3: Implement CombinedGrammarBuilder**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/CombinedGrammarBuilder.kt
package org.inklang.peg

/**
 * Merges all registered package grammars into a combined grammar.
 * Detects keyword conflicts and reports them as build errors.
 */
class CombinedGrammarBuilder {

    sealed class MergeResult
    data class Ok(val grammar: CombinedGrammar) : MergeResult()
    data class MergeError(
        val conflictingKeyword: String,
        val packages: List<String>
    ) : MergeResult()

    fun tryMerge(packages: List<PackageGrammar>): MergeResult {
        val allStatements = mutableMapOf<String, Pair<String, StatementExtension>>()  // keyword -> (package, ext)
        val allDeclarations = mutableMapOf<String, Pair<String, DeclarationExtension>>()
        val allRules = mutableMapOf<String, Pair<String, Parser<*>>>()

        for (pkg in packages) {
            // Add package name BEFORE conflict check (bug fix)
            for ((keyword, ext) in pkg.statements) {
                val existing = allStatements[keyword]
                if (existing != null) {
                    return MergeError(keyword, listOf(existing.first, pkg.name))
                }
                allStatements[keyword] = pkg.name to ext
            }
            for ((keyword, ext) in pkg.declarations) {
                val existing = allDeclarations[keyword]
                if (existing != null) {
                    return MergeError(keyword, listOf(existing.first, pkg.name))
                }
                allDeclarations[keyword] = pkg.name to ext
            }
            for ((keyword, parser) in pkg.rules) {
                val existing = allRules[keyword]
                if (existing != null) {
                    return MergeError(keyword, listOf(existing.first, pkg.name))
                }
                allRules[keyword] = pkg.name to parser
            }
        }
        return Ok(CombinedGrammar(allStatements, allDeclarations, allRules))
    }

    fun merge(packages: List<PackageGrammar>): CombinedGrammar {
        return when (val result = tryMerge(packages)) {
            is MergeResult.Ok -> result.grammar
            is MergeResult.MergeError -> throw IllegalStateException(
                "Keyword conflict: '${result.conflictingKeyword}' registered by multiple packages: ${result.packages}"
            )
        }
    }
}

class CombinedGrammar(
    val statements: Map<String, StatementExtension>,
    val declarations: Map<String, DeclarationExtension>,
    val rules: Map<String, Parser<*>>
) {
    /**
     * Build a complete program parser from all registered grammar rules.
     * Statement keywords take precedence over expression keywords at the top level.
     * Returns a parser that produces List<org.inklang.lang.Stmt>.
     */
    fun buildParser(): Parser<List<org.inklang.lang.Stmt>> {
        val statementParsers = statements.mapValues { (_, ext) -> ext.pattern }
        val declarationParsers = declarations.mapValues { (_, ext) -> ext.pattern }

        return object : BaseParser<List<org.inklang.lang.Stmt>>() {
            override fun parse(input: String, position: Int): ParseResult<List<org.inklang.lang.Stmt>> {
                val stmts = mutableListOf<org.inklang.lang.Stmt>()
                var pos = position

                while (pos < input.length) {
                    // Skip whitespace
                    while (pos < input.length && input[pos].isWhitespace()) pos++
                    if (pos >= input.length) break

                    // Try declaration first (e.g., "mob { ... }")
                    var matched = false
                    for ((keyword, parser) in declarationParsers) {
                        if (input.regionMatches(pos, keyword, 0, keyword.length) &&
                            (pos + keyword.length >= input.length || !input[pos + keyword.length].isLetter())) {
                            val result = parser.parse(input, pos)
                            if (result is ParseResult.Success) {
                                val ext = declarations[keyword]!!
                                stmts.add(ext.lower(listOf(result.value)))
                                pos = result.position
                                matched = true
                                break
                            }
                        }
                    }

                    // Try statement (e.g., "print(...)", "when ...")
                    if (!matched) {
                        for ((keyword, parser) in statementParsers) {
                            if (input.regionMatches(pos, keyword, 0, keyword.length) &&
                                (pos + keyword.length >= input.length || !input[pos + keyword.length].isLetter())) {
                                val result = parser.parse(input, pos)
                                if (result is ParseResult.Success) {
                                    @Suppress("UNCHECKED_CAST")
                                    stmts.add(result.value as org.inklang.lang.Stmt)
                                    pos = result.position
                                    matched = true
                                    break
                                }
                            }
                        }
                    }

                    // Fall back to expression as statement
                    if (!matched) {
                        val exprParser = rules["expression"] as? Parser<org.inklang.lang.Expr>
                            ?: return ParseResult.Failure(pos, listOf("statement, declaration, or expression"))
                        val result = exprParser.parse(input, pos)
                        if (result is ParseResult.Success) {
                            stmts.add(org.inklang.lang.Stmt.ExprStmt(result.value))
                            pos = result.position
                        } else {
                            return ParseResult.Failure(pos, listOf("parseable content"))
                        }
                    }
                }
                return ParseResult.Success(stmts, pos)
            }
        }
    }
}
```

- [ ] **Step 4: Implement ExtensionRegistry (singleton)**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/ExtensionRegistry.kt
package org.inklang.peg

/**
 * Global registry of all loaded package grammars.
 * Packages register during their static initialization phase.
 */
object ExtensionRegistry {
    private val grammars = mutableListOf<PackageGrammar>()

    @Synchronized
    fun register(pkg: PackageGrammar) {
        grammars.add(pkg)
    }

    @Synchronized
    fun getRegistered(): List<PackageGrammar> = grammars.toList()

    @Synchronized
    fun clear() {
        grammars.clear()  // for testing
    }
}
```

- [ ] **Step 5: Run tests — all pass**

- [ ] **Step 6: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/peg/InkExtensionContext.kt lang/src/main/kotlin/org/inklang/peg/CombinedGrammarBuilder.kt lang/src/main/kotlin/org/inklang/peg/ExtensionRegistry.kt
git commit -m "feat: add grammar extension registration API

- InkExtensionContext: registerStatement, registerDeclaration, registerRule
- CombinedGrammarBuilder: merges all package grammars, detects keyword conflicts
- ExtensionRegistry: global singleton for package registration
- Build-time error on keyword conflict"
```

---

## Chunk 4: Package Manifest Parsing

**Goal:** Parse TOML package manifests, collect grammar extensions, build the combined grammar, and wire it into the existing InkCompiler pipeline.

### File Map

```
lang/src/main/kotlin/org/inklang/peg/
├── PackageLoader.kt                         # Discovers and loads packages
├── PackageManifest.kt                       # Parsed manifest data class
├── manifest.toml                            # (test file)
```

- Create: `lang/src/main/kotlin/org/inklang/peg/PackageManifest.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/PackageLoader.kt`
- Create: `lang/src/test/resources/test-manifest.toml`
- Modify: `lang/src/main/kotlin/org/inklang/InkCompiler.kt` — wire extension loading
- Test: `lang/src/test/kotlin/org/inklang/peg/PackageLoaderTest.kt`

- [ ] **Step 1: Write failing test for TOML manifest parsing**

```kotlin
// lang/src/test/kotlin/org/inklang/peg/PackageLoaderTest.kt
package org.inklang.peg

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PackageLoaderTest {

    @Test
    fun `parse valid manifest`() {
        val toml = """
            [package]
            name = "mobs"
            version = "1.0.0"
            ink_version = ">=0.3.0"

            [dependencies]
            ink-core = "0.3.0"
        """.trimIndent()

        val manifest = PackageManifest.parse(toml)
        assertEquals("mobs", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertEquals(listOf("ink-core" to "0.3.0"), manifest.dependencies)
    }
}
```

- [ ] **Step 2: Implement TOML parser (minimal, hand-rolled)**

Use a minimal hand-rolled TOML parser for manifest.toml — no external library dependency. TOML spec is complex so implement only what's needed: string/int key-value pairs, table headers, arrays of strings.

```kotlin
// lang/src/main/kotlin/org/inklang/peg/PackageManifest.kt
package org.inklang.peg

data class PackageManifest(
    val name: String,
    val version: String,
    val inkVersion: String,
    val description: String = "",
    val dependencies: List<Pair<String, String>> = emptyList(),
    val visibility: Visibility = Visibility()
) {
    data class Visibility(
        val pubScript: List<String> = emptyList(),
        val pub: List<String> = emptyList()
    )

    companion object {
        fun parse(input: String): PackageManifest {
            val lines = input.lines()
            var section = ""
            var name = ""
            var version = ""
            var inkVersion = ""
            var description = ""
            val deps = mutableListOf<String to String>()
            var visPubScript = emptyList<String>()
            var visPub = emptyList<String>()

            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("[package]") -> section = "package"
                    trimmed.startsWith("[dependencies]") -> section = "dependencies"
                    trimmed.startsWith("[visibility]") -> section = "visibility"
                    section == "package" && trimmed.contains('=') -> {
                        val (k, v) = trimmed.split('=', limit = 2)
                        when (k.trim()) {
                            "name" -> name = v.trim().removeSurrounding("\"")
                            "version" -> version = v.trim().removeSurrounding("\"")
                            "ink_version" -> inkVersion = v.trim().removeSurrounding("\"")
                            "description" -> description = v.trim().removeSurrounding("\"")
                        }
                    }
                    section == "dependencies" && trimmed.contains('=') -> {
                        val (k, v) = trimmed.split('=', limit = 2)
                        deps.add(k.trim() to v.trim().removeSurrounding("\""))
                    }
                }
            }
            return PackageManifest(name, version, inkVersion, description, deps, Visibility(visPubScript, visPub))
        }
    }
}
```

- [ ] **Step 3: Implement PackageLoader**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/PackageLoader.kt
package org.inklang.peg

import java.io.File
import java.util.jar.JarFile

class PackageLoader(private val packagesDir: File) {

    data class LoadedPackage(
        val manifest: PackageManifest,
        val grammar: PackageGrammar,
        val classLoader: ClassLoader
    )

    fun loadAll(): List<LoadedPackage> {
        if (!packagesDir.exists()) return emptyList()
        val jars = packagesDir.listFiles { f -> f.extension == "jar" } ?: return emptyList()
        return jars.mapNotNull { loadPackage(it) }
    }

    private fun loadPackage(jar: File): LoadedPackage? {
        return try {
            val jarFile = JarFile(jar)
            val manifestEntry = jarFile.getEntry("manifest.toml")
                ?: return null  // not an Ink package
            val toml = jarFile.getInputStream(manifestEntry).bufferedReader().readText()
            val manifest = PackageManifest.parse(toml)

            // Load Kotlin classes via classloader
            val classLoader = object : ClassLoader() {
                // Load classes from jar
            }

            // Package must provide an InkPackage implementation that registers grammar
            val packageClass = classLoader.loadClass("${manifest.name}.InkPackage")
            val inkPackage = packageClass.getDeclaredConstructor().newInstance() as InkPackage
            val grammar = inkPackage.register(ExtensionRegistry)

            LoadedPackage(manifest, grammar, classLoader)
        } catch (e: Exception) {
            null
        }
    }

    fun topologicalSort(packages: List<LoadedPackage>): List<LoadedPackage> {
        // Topological sort by dependencies
        // Detect cycles and throw build-time error
    }
}

interface InkPackage {
    fun register(registry: ExtensionRegistry): PackageGrammar
}
```

- [ ] **Step 4: Wire into InkCompiler**

Modify `InkCompiler.kt` to:
1. Call `PackageLoader.loadAll()` at startup
2. Build combined grammar via `CombinedGrammarBuilder.merge()`
3. Use the combined grammar for parsing instead of the hardcoded grammar

```kotlin
// lang/src/main/kotlin/org/inklang/InkCompiler.kt
class InkCompiler {
    fun compile(source: String): CompiledScript {
        val packages = PackageLoader(File("packages")).loadAll()
        val combinedGrammar = CombinedGrammarBuilder().merge(packages.map { it.grammar })
        val parser = combinedGrammar.buildParser()
        val ast = parser.parse(source)
        // ... rest of pipeline
    }
}
```

- [ ] **Step 5: Run tests — all pass**

- [ ] **Step 6: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/peg/PackageManifest.kt lang/src/main/kotlin/org/inklang/peg/PackageLoader.kt
git commit -m "feat: add package manifest parsing and loader

- PackageManifest: minimal TOML parser for manifest.toml
- PackageLoader: discovers .jar packages, loads manifest.toml and Kotlin classes
- topological sort with cycle detection
- InkPackage interface for package entry point"
```

---

## Chunk 5: End-to-End Pipeline

**Goal:** Wire the complete pipeline — load packages → build grammar → parse → lower to IR → compile bytecode → execute in VM. Produce a working "hello world" that uses the new PEG-based frontend.

### File Map

```
lang/src/main/kotlin/org/inklang/
├── InkCompiler.kt                           # MODIFIED - wire new frontend
├── peg/
│   └── CompilerPipeline.kt                   # Parse → AST → IR → Bytecode
```

- Modify: `lang/src/main/kotlin/org/inklang/InkCompiler.kt`
- Create: `lang/src/main/kotlin/org/inklang/peg/CompilerPipeline.kt`
- Create: `test-redesign.ink` (test script)
- Test: `lang/src/test/kotlin/org/inklang/peg/EndToEndTest.kt`

- [ ] **Step 1: Write failing end-to-end test**

```kotlin
// lang/src/test/kotlin/org/inklang/peg/EndToEndTest.kt
package org.inklang.peg

import org.inklang.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EndToEndTest {

    // compileAndRun is a test helper that compiles and executes with captured stdout
    // The actual implementation is in CompilerPipeline (Chunk 5, Step 3).
    // This test assumes: InkCompiler has compileAndRun(source: String): String
    // which returns stdout output.

    @Test
    fun `parse and execute simple expression`() {
        val compiler = InkCompiler()
        val result = compiler.compileAndRun("print(1 + 2)")
        assertEquals("3", result.trim())
    }

    @Test
    fun `parse and execute variable declaration`() {
        val compiler = InkCompiler()
        val result = compiler.compileAndRun("""
            x = 5
            print(x * 2)
        """.trimIndent())
        assertEquals("10", result.trim())
    }

    @Test
    fun `parse and execute function call`() {
        val compiler = InkCompiler()
        val result = compiler.compileAndRun("""
            fn double(n) = n * 2
            print(double(21))
        """.trimIndent())
        assertEquals("42", result.trim())
    }
}
```

- [ ] **Step 2: Implement CompilerPipeline — bridge PEG parse to IR**

```kotlin
// lang/src/main/kotlin/org/inklang/peg/CompilerPipeline.kt
package org.inklang.peg

import org.inklang.lang.AST
import org.inklang.ast.AstLowerer

/**
 * Bridges the PEG parser output (Expr/Stmt) to the existing IR pipeline.
 * Transforms Expr nodes to AST.Expr, Stmt nodes to AST.Stmt,
 * then uses the existing AstLowerer to produce IR.
 */
class CompilerPipeline {

    fun parseAndLower(source: String, grammar: CombinedGrammar): List<org.inklang.lang.AST.Stmt> {
        val parser = grammar.buildParser()
        val result = parser.parse(source)
        require(result is ParseResult.Success) { "Parse error: $result" }
        @Suppress("UNCHECKED_CAST")
        return result.value as List<org.inklang.lang.AST.Stmt>
    }

    fun lowerToIr(stmts: List<org.inklang.lang.AST.Stmt>): List<org.inklang.lang.IR.IrInstr> {
        val lowerer = AstLowerer()
        return stmts.flatMap { lowerer.lower(it) }
    }

    // Remaining pipeline uses existing: IR → SSA → optimize → allocate → spill → compile → VM
}
```

- [ ] **Step 3: Wire InkCompiler to use new frontend**

Modify `InkCompiler.kt` to detect if PEG grammar is available and use it, otherwise fall back to existing parser. For this chunk, replace entirely with PEG-based pipeline.

- [ ] **Step 4: Run end-to-end tests — all pass**

- [ ] **Step 5: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/peg/CompilerPipeline.kt lang/src/main/kotlin/org/inklang/InkCompiler.kt lang/src/test/kotlin/org/inklang/peg/EndToEndTest.kt
git commit -m "feat: wire complete PEG-to-VM pipeline

- CompilerPipeline bridges PEG parse to existing IR pipeline
- InkCompiler uses PEG-based parser with CombinedGrammar
- End-to-end tests pass for expressions, variables, functions"
```

---

## Chunk 6: Full Base Grammar (Functions, Control Flow, Declarations)

**Goal:** Complete the base Ink grammar to support what the spec requires: functions (def), variables (var/val), if/while/for, return, basic types. At this point the new frontend should handle the same features as the existing parser.

### File Map

```
lang/src/main/kotlin/org/inklang/peg/ink/
├── statements/
│   ├── Functions.kt                         # def name(args) = expr
│   ├── Variables.kt                         # val/var name = expr
│   ├── IfStatement.kt                       # if expr { } else { }
│   ├── WhileStatement.kt                    # while expr { }
│   ├── ForStatement.kt                      # for x in collection { }
│   ├── ReturnStatement.kt                   # return expr
│   └── BlockStatement.kt                    # { stmt* }
```

- Create: `lang/src/main/kotlin/org/inklang/peg/ink/statements/*.kt`
- Modify: `lang/src/main/kotlin/org/inklang/peg/ink/InkGrammar.kt`
- Test: Update existing tests and add new ones
- [ ] Each statement type: write failing test, implement, pass test, commit

This chunk is mechanical — each statement follows the same pattern:
1. Write PEG rule for the syntax
2. Write AST node (or reuse existing AST type)
3. Write lowering rule
4. Test

See the existing `Parser.kt` and `AST.kt` in the codebase for reference on what AST nodes exist and how `AstLowerer` handles them.

- [ ] **Commit each statement type individually**

---

## Summary: Deliverables Per Chunk

| Chunk | Files Created | Key Test |
|-------|--------------|----------|
| 1: PEG backbone | ~10 files in `peg/` | `ParserCombinatorTest` — all pass |
| 2: Expression parsing | `InkGrammar.kt`, expression parsers | `ExpressionParserTest` — all pass |
| 3: Extension API | `InkExtensionContext`, `CombinedGrammarBuilder`, `ExtensionRegistry` | `GrammarExtensionTest` — all pass |
| 4: Package loading | `PackageManifest`, `PackageLoader`, `InkPackage` interface | `PackageLoaderTest` — all pass |
| 5: E2E pipeline | `CompilerPipeline`, wired `InkCompiler` | `EndToEndTest` — all pass |
| 6: Full grammar | All statement parsers | Full test suite passes |

**Total: ~6 chunks, each producing working code, each committed separately.**
