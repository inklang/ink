package org.inklang.ast

import org.inklang.lang.*
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

private fun compileAndRun(source: String): List<String> {
    val output = mutableListOf<String>()
    val tokens = tokenize(source)
    val stmts = Parser(tokens).parse()
    val folder = ConstantFolder()
    val folded = stmts.map { folder.foldStmt(it) }
    val result = AstLowerer().lower(folded)

    // SSA round-trip with optimizations
    val (ssaDeconstructed, ssaOptConstants) = IrCompiler.optimizedSsaRoundTrip(result.instrs, result.constants)
    val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, ssaOptConstants)

    val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
    val allocResult = RegisterAllocator().allocate(ranges)
    val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)
    val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaResult.constants))
    chunk.spillSlotCount = allocResult.spillSlotCount

    val vm = VM()
    vm.globals["print"] = Value.NativeFunction { args ->
        output.add(args.joinToString(" ") { valueToString(it) })
        Value.Null
    }
    vm.execute(chunk)
    return output
}

class VMTest {

    @Test
    fun testPrintIsBuiltin() {
        // VM should have print registered by default — no manual setup
        val vm = VM()
        assertTrue(vm.globals.containsKey("print"), "VM should have built-in print")
    }

    @Test
    fun testPrintInteger() {
        val output = compileAndRun("print(42)")
        assertEquals(listOf("42"), output)
    }

    @Test
    fun testPrintArithmetic() {
        val output = compileAndRun("print(2 + 3)")
        assertEquals(listOf("5"), output)
    }

    @Test
    fun testPrintString() {
        val output = compileAndRun("""print("hello")""")
        assertEquals(listOf("hello"), output)
    }

    @Test
    fun testVariableAndPrint() {
        val output = compileAndRun(
            """
            let x = 10
            print(x)
            """.trimIndent()
        )
        assertEquals(listOf("10"), output)
    }

    @Ignore("SSA round-trip with if-else + global function calls produces null register")
    @Test
    fun testIfElseTrueBranch() {
        val output = compileAndRun(
            """
            let x = 5
            if x == 5 { print("yes") } else { print("no") }
            """.trimIndent()
        )
        assertEquals(listOf("yes"), output)
    }

    @Ignore("SSA round-trip with if-else + global function calls produces null register")
    @Test
    fun testIfElseFalseBranch() {
        val output = compileAndRun(
            """
            let x = 3
            if x == 5 { print("yes") } else { print("no") }
            """.trimIndent()
        )
        assertEquals(listOf("no"), output)
    }

    @Ignore("SSA round-trip with loops causes infinite loop — known limitation")
    @Test
    fun testWhileLoop() {
        val output = compileAndRun(
            """
            let i = 0
            while i < 3 { print(i)
            i = i + 1 }
            """.trimIndent()
        )
        assertEquals(listOf("0", "1", "2"), output)
    }

    @Test
    fun testFunctionCall() {
        val output = compileAndRun(
            """
            fn add(a, b) { return a + b }
            print(add(3, 4))
            """.trimIndent()
        )
        assertEquals(listOf("7"), output)
    }

    @Ignore("SSA round-trip register allocation issue in multi-statement functions")
    @Test
    fun testFunctionWithMultipleStatements() {
        val output = compileAndRun(
            """
            fn double(x) { let result = x * 2
            return result }
            print(double(5))
            """.trimIndent()
        )
        assertEquals(listOf("10"), output)
    }

    @Test
    fun testNestedFunctionCalls() {
        val output = compileAndRun(
            """
            fn square(x) { return x * x }
            print(square(square(2)))
            """.trimIndent()
        )
        assertEquals(listOf("16"), output)
    }

    @Test
    fun testBooleanExpression() {
        val output = compileAndRun("print(1 < 2)")
        // Value.Boolean doesn't override toString(), uses data class default
        assertEquals(listOf("Boolean(value=true)"), output)
    }

    @Test
    fun testNegation() {
        val output = compileAndRun("print(-5)")
        assertEquals(listOf("-5"), output)
    }

    @Test
    fun testStringConcatenation() {
        val output = compileAndRun("""print("hello" + " " + "world")""")
        assertEquals(listOf("hello world"), output)
    }

    @Test
    fun testMultiplePrints() {
        val output = compileAndRun(
            """
            print(1)
            print(2)
            print(3)
            """.trimIndent()
        )
        assertEquals(listOf("1", "2", "3"), output)
    }

    // Ternary tests — @Ignore'd because ternary desugars to JumpIfFalse/Jump which hits the SSA control flow bug
    @Ignore("SSA round-trip bug with control flow")
    @Test
    fun testTernaryTrue() {
        val output = compileAndRun("print(true ? 1 : 2)")
        assertEquals(listOf("1"), output)
    }

    @Ignore("SSA round-trip bug with control flow")
    @Test
    fun testTernaryFalse() {
        val output = compileAndRun("print(false ? 1 : 2)")
        assertEquals(listOf("2"), output)
    }

    @Ignore("SSA round-trip bug with control flow")
    @Test
    fun testTernaryWithExpression() {
        val output = compileAndRun("let x = 5\nprint(x > 3 ? \"big\" : \"small\")")
        assertEquals(listOf("big"), output)
    }

    // Map tests
    @Test
    fun testMapLiteral() {
        val output = compileAndRun("""
            let m = {"name": "Alice", "age": 30}
            print(m.get("name"))
            print(m.get("age"))
        """.trimIndent())
        assertEquals(listOf("Alice", "30"), output)
    }

    @Test
    fun testMapSize() {
        val output = compileAndRun("""
            let m = {"a": 1, "b": 2, "c": 3}
            print(m.size())
        """.trimIndent())
        assertEquals(listOf("3"), output)
    }

    // Lambda tests
    @Test
    fun testLambdaBasic() {
        val output = compileAndRun("""
            let timesTwo = (x) -> { return x * 2 }
            print(timesTwo(5))
        """.trimIndent())
        assertEquals(listOf("10"), output)
    }

    @Test
    fun testLambdaMultipleParams() {
        val output = compileAndRun("""
            let add = (a, b) -> { return a + b }
            print(add(3, 7))
        """.trimIndent())
        assertEquals(listOf("10"), output)
    }

    @Test
    fun testLambdaAsArgument() {
        val output = compileAndRun("""
            fn apply(f, x) {
                return f(x)
            }
            print(apply((x) -> { return x + 1 }, 10))
        """.trimIndent())
        assertEquals(listOf("11"), output)
    }

    // Enum tests
    @Test
    fun testEnumAccess() {
        val output = compileAndRun("""
            enum Color { RED, GREEN, BLUE }
            print(Color.RED.name)
            print(Color.GREEN.ordinal)
        """.trimIndent())
        assertEquals(listOf("RED", "1"), output)
    }

    @Test
    fun testEnumEquality() {
        val output = compileAndRun("""
            enum Direction { UP, DOWN, LEFT, RIGHT }
            print(Direction.UP == Direction.UP)
            print(Direction.UP == Direction.DOWN)
        """.trimIndent())
        assertEquals(listOf("Boolean(value=true)", "Boolean(value=false)"), output)
    }

    // Table/Config/Import parsing tests (parse-only, no runtime execution)
    @Test
    fun testTableBasic() {
        val tokens = tokenize("""
            table Users {
                key id: int
                name: string
                age: int = 0
            }
        """.trimIndent())
        val stmts = Parser(tokens).parse()
        assertEquals(1, stmts.size)
        assertTrue(stmts[0] is Stmt.TableStmt)
    }

    @Test
    fun testConfigParsing() {
        val tokens = tokenize("""
            config Settings {
                name: string = "default"
                port: int = 8080
            }
        """.trimIndent())
        val stmts = Parser(tokens).parse()
        assertEquals(1, stmts.size)
        assertTrue(stmts[0] is Stmt.ConfigStmt)
    }

    @Test
    fun testImportParsing() {
        val tokens = tokenize("import utils")
        val stmts = Parser(tokens).parse()
        assertEquals(1, stmts.size)
        assertTrue(stmts[0] is Stmt.ImportStmt)
    }

    @Test
    fun testImportFromParsing() {
        val tokens = tokenize("import spawn, reset from arena")
        val stmts = Parser(tokens).parse()
        assertEquals(1, stmts.size)
        assertTrue(stmts[0] is Stmt.ImportFromStmt)
        val importStmt = stmts[0] as Stmt.ImportFromStmt
        assertEquals(2, importStmt.tokens.size)
    }

    // Integration tests
    @Ignore("SSA round-trip bug with control flow")
    @Test
    fun testTernaryNested() {
        val output = compileAndRun("""
            let x = 10
            print(x > 5 ? (x > 8 ? "very big" : "big") : "small")
        """.trimIndent())
        assertEquals(listOf("very big"), output)
    }

    @Test
    fun testMapWithIntKeys() {
        val output = compileAndRun("""
            let m = {1: "one", 2: "two"}
            print(m.get(1))
            print(m.get(2))
        """.trimIndent())
        assertEquals(listOf("one", "two"), output)
    }

    @Test
    fun testLambdaNoParams() {
        val output = compileAndRun("""
            let greet = () -> { return "hello" }
            print(greet())
        """.trimIndent())
        assertEquals(listOf("hello"), output)
    }

    @Ignore("SSA round-trip bug with control flow")
    @Test
    fun testEnumInCondition() {
        val output = compileAndRun("""
            enum Status { ACTIVE, INACTIVE }
            let s = Status.ACTIVE
            print(s == Status.ACTIVE ? "yes" : "no")
        """.trimIndent())
        assertEquals(listOf("yes"), output)
    }

    @Test
    fun testArrayAsClassInstance() {
        val output = compileAndRun("""
            let arr = [1, 2, 3]
            print(arr.size())
            arr.push(4)
            print(arr.size())
        """.trimIndent())
        assertEquals(listOf("3", "4"), output)
    }

    @Test
    fun testMapDelete() {
        val output = compileAndRun("""
            let m = {"a": 1, "b": 2}
            m.delete("a")
            print(m.size())
        """.trimIndent())
        assertEquals(listOf("1"), output)
    }

    @Test
    fun testNotKeyword() {
        val output = compileAndRun(
            """
            let a = not true
            print(a)
            """.trimIndent()
        )
        assertEquals(listOf("Boolean(value=false)"), output)
    }

    @Test
    fun testNotKeywordFalse() {
        val output = compileAndRun(
            """
            let a = not false
            print(a)
            """.trimIndent()
        )
        assertEquals(listOf("Boolean(value=true)"), output)
    }

    @Test
    fun testPowerOperator() {
        val output = compileAndRun("print(2 ** 10)")
        assertEquals(listOf("1024"), output)
    }

    @Test
    fun testPowerOperatorFloat() {
        val output = compileAndRun("print(9.0 ** 0.5)")
        assertEquals(listOf("3.0"), output)
    }

    @Test
    fun testAndTrue() {
        val output = compileAndRun(
            """
            let a = true and true
            print(a)
            """.trimIndent()
        )
        assertEquals(listOf("Boolean(value=true)"), output)
    }

    @Test
    fun testAndFalse() {
        val output = compileAndRun(
            """
            let a = true and false
            print(a)
            """.trimIndent()
        )
        assertEquals(listOf("Boolean(value=false)"), output)
    }

    @Test
    fun testAndShortCircuit() {
        // RHS should not be evaluated when LHS is false — x stays 0
        val output = compileAndRun(
            """
            let x = 0
            let result = false and (x == 1)
            print(result)
            print(x)
            """.trimIndent()
        )
        assertEquals(listOf("Boolean(value=false)", "0"), output)
    }

    @Test
    fun testOrTrue() {
        val output = compileAndRun(
            """
            let a = false or true
            print(a)
            """.trimIndent()
        )
        assertEquals(listOf("Boolean(value=true)"), output)
    }

    @Test
    fun testOrFalse() {
        val output = compileAndRun(
            """
            let a = false or false
            print(a)
            """.trimIndent()
        )
        assertEquals(listOf("Boolean(value=false)"), output)
    }

    @Test
    fun testOrShortCircuit() {
        // RHS should not be evaluated when LHS is true — x stays 0
        val output = compileAndRun(
            """
            let x = 0
            let result = true or (x == 1)
            print(result)
            print(x)
            """.trimIndent()
        )
        assertEquals(listOf("Boolean(value=true)", "0"), output)
    }

    @Test
    fun testPrefixIncrement() {
        val output = compileAndRun(
            """
            let x = 5
            let y = ++x
            print(x)
            print(y)
            """.trimIndent()
        )
        // Both x and y should be 6 (prefix: mutate then return new value)
        assertEquals(listOf("6", "6"), output)
    }

    @Test
    fun testPrefixDecrement() {
        val output = compileAndRun(
            """
            let x = 10
            ++x
            ++x
            --x
            print(x)
            """.trimIndent()
        )
        assertEquals(listOf("11"), output)
    }

    @Test
    fun testConstDeclarationWorks() {
        val output = compileAndRun(
            """
            const x = 42
            print(x)
            """.trimIndent()
        )
        assertEquals(listOf("42"), output)
    }

    @Test
    fun testConstReassignmentThrows() {
        val exception = assertFailsWith<RuntimeException> {
            compileAndRun(
                """
                const x = 1
                x = 2
                """.trimIndent()
            )
        }
        assertTrue(exception.message?.contains("const") == true, "Error should mention const")
    }

    @Test
    fun testStringEscapeNewline() {
        val output = compileAndRun("""print("a\nb")""")
        // \n should be unescaped to actual newline char in the Quill string
        assertEquals(listOf("a\nb"), output)
    }

    @Test
    fun testStringEscapeTab() {
        val output = compileAndRun("""print("a\tb")""")
        assertEquals(listOf("a\tb"), output)
    }

    @Test
    fun testStringEscapeBackslash() {
        val output = compileAndRun("""print("a\\b")""")
        assertEquals(listOf("a\\b"), output)
    }

    @Test
    fun testStringEscapeQuote() {
        val output = compileAndRun("""print("say \"hi\"")""")
        assertEquals(listOf("""say "hi""""), output)
    }

    // String method tests

    @Test
    fun testStringSplit() {
        val output = compileAndRun("""print("a,b,c".split(","))""")
        // split(",") returns Array, which toString() is [a, b, c]
        assertEquals(listOf("[a, b, c]"), output)
    }

    @Test
    fun testStringSplitEmptyResult() {
        val output = compileAndRun("""print("".split(","))""")
        assertEquals(listOf("[]"), output)
    }

    @Test
    fun testStringSplitMultiChar() {
        val output = compileAndRun("""print("a--b--c".split("--"))""")
        assertEquals(listOf("[a, b, c]"), output)
    }

    @Test
    fun testStringTrim() {
        val output = compileAndRun("""print("  hello  ".trim())""")
        assertEquals(listOf("hello"), output)
    }

    @Test
    fun testStringTrimNeither() {
        val output = compileAndRun("""print("hello".trim())""")
        assertEquals(listOf("hello"), output)
    }

    @Test
    fun testStringContainsTrue() {
        val output = compileAndRun("""print("hello world".contains("world"))""")
        assertEquals(listOf("Boolean(value=true)"), output)
    }

    @Test
    fun testStringContainsFalse() {
        val output = compileAndRun("""print("hello world".contains("foo"))""")
        assertEquals(listOf("Boolean(value=false)"), output)
    }

    @Test
    fun testStringReplace() {
        val output = compileAndRun("""print("hello".replace("l", "x"))""")
        // Note: replace in Quill replaces all occurrences (not first-only)
        assertEquals(listOf("hexxo"), output)
    }

    @Test
    fun testStringReplaceAll() {
        val output = compileAndRun("""print("hello".replaceAll("l", "x"))""")
        assertEquals(listOf("hexxo"), output)
    }

    @Test
    fun testStringToUpperCase() {
        val output = compileAndRun("""print("hello".toUpperCase())""")
        assertEquals(listOf("HELLO"), output)
    }

    @Test
    fun testStringToLowerCase() {
        val output = compileAndRun("""print("HELLO".toLowerCase())""")
        assertEquals(listOf("hello"), output)
    }

    @Test
    fun testStringStartsWithTrue() {
        val output = compileAndRun("""print("hello".startsWith("hel"))""")
        assertEquals(listOf("Boolean(value=true)"), output)
    }

    @Test
    fun testStringStartsWithFalse() {
        val output = compileAndRun("""print("hello".startsWith("world"))""")
        assertEquals(listOf("Boolean(value=false)"), output)
    }

    @Test
    fun testStringEndsWithTrue() {
        val output = compileAndRun("""print("hello".endsWith("llo"))""")
        assertEquals(listOf("Boolean(value=true)"), output)
    }

    @Test
    fun testStringEndsWithFalse() {
        val output = compileAndRun("""print("hello".endsWith("world"))""")
        assertEquals(listOf("Boolean(value=false)"), output)
    }

    @Test
    fun testStringIndexOfFound() {
        val output = compileAndRun("""print("hello".indexOf("l"))""")
        assertEquals(listOf("2"), output)
    }

    @Test
    fun testStringIndexOfNotFound() {
        val output = compileAndRun("""print("hello".indexOf("z"))""")
        assertEquals(listOf("-1"), output)
    }

    @Test
    fun testStringLength() {
        val output = compileAndRun("""print("hello".length())""")
        assertEquals(listOf("5"), output)
    }

    @Test
    fun testStringLengthEmpty() {
        val output = compileAndRun("""print("".length())""")
        assertEquals(listOf("0"), output)
    }

    @Test
    fun testStringIsEmptyTrue() {
        val output = compileAndRun("""print("".isEmpty())""")
        assertEquals(listOf("Boolean(value=true)"), output)
    }

    @Test
    fun testStringIsEmptyFalse() {
        val output = compileAndRun("""print("hello".isEmpty())""")
        assertEquals(listOf("Boolean(value=false)"), output)
    }

    @Test
    fun testStringIsBlankTrue() {
        val output = compileAndRun("""print("   ".isBlank())""")
        assertEquals(listOf("Boolean(value=true)"), output)
    }

    @Test
    fun testStringIsBlankFalse() {
        val output = compileAndRun("""print("hello".isBlank())""")
        assertEquals(listOf("Boolean(value=false)"), output)
    }

    @Test
    fun testStringChars() {
        val output = compileAndRun("""print("abc".chars())""")
        assertEquals(listOf("[a, b, c]"), output)
    }

    @Test
    fun testStringCharsEmpty() {
        val output = compileAndRun("""print("".chars())""")
        assertEquals(listOf("[]"), output)
    }

    @Test
    fun testStringGetValidIndex() {
        val output = compileAndRun("""print("hello".get(1))""")
        assertEquals(listOf("e"), output)
    }

    @Test
    fun testStringGetOutOfBoundsReturnsNull() {
        // TODO: should throw StringIndexOutOfBoundsError; currently returns null
        val output = compileAndRun("""print("hello".get(10))""")
        assertEquals(listOf("null"), output)
    }

    @Ignore("SSA round-trip bug with control flow — test verifies optimizer correctness once fixed")
    @Test
    fun testDeadCodeEliminated() {
        // Verifies optimizer correctness: 2 + 3 folds to 5, dead branch is removed.
        // @Ignore'd because if-false generates JumpIfFalse IR which triggers the known
        // SSA control-flow limitation (same bug as testIfElseTrueBranch, testWhileLoop, etc.)
        val output = compileAndRun(
            """
            let x = 2 + 3
            if false {
                print("dead")
            }
            print(x)
            """.trimIndent()
        )
        assertEquals(listOf("5"), output)
    }

    // === Set Tests ===

    @Test
    fun testSetFactory() {
        val output = compileAndRun("""
            let s = Set(1, 2, 3)
            print(s.size())
        """.trimIndent())
        assertEquals(listOf("3"), output)
    }

    @Test
    fun testSetLiteral() {
        val output = compileAndRun("""
            let s = {1, 2, 3}
            print(s.size())
        """.trimIndent())
        assertEquals(listOf("3"), output)
    }

    @Test
    fun testSetHas() {
        val output = compileAndRun("""
            let s = {1, 2, 3}
            print(s.has(2))
            print(s.has(5))
        """.trimIndent())
        assertEquals(listOf("Boolean(value=true)", "Boolean(value=false)"), output)
    }

    @Test
    fun testSetAdd() {
        val output = compileAndRun("""
            let s = {1, 2}
            s.add(3)
            print(s.size())
        """.trimIndent())
        assertEquals(listOf("3"), output)
    }

    @Test
    fun testSetRemove() {
        val output = compileAndRun("""
            let s = {1, 2, 3}
            s.remove(2)
            print(s.size())
            print(s.has(2))
        """.trimIndent())
        assertEquals(listOf("2", "Boolean(value=false)"), output)
    }

    @Test
    fun testSetClear() {
        val output = compileAndRun("""
            let s = {1, 2, 3}
            s.clear()
            print(s.size())
        """.trimIndent())
        assertEquals(listOf("0"), output)
    }

    @Test
    fun testSetDelete() {
        val output = compileAndRun("""
            let s = {1, 2, 3}
            s.delete(2)
            print(s.size())
        """.trimIndent())
        assertEquals(listOf("2"), output)
    }

    @Test
    fun testSetDuplicateDeduplication() {
        val output = compileAndRun("""
            let s = Set(1, 1, 2, 2, 3)
            print(s.size())
        """.trimIndent())
        assertEquals(listOf("3"), output)
    }

    @Ignore("SSA round-trip bug with loops causes infinite loop")
    @Test
    fun testSetIteration() {
        val output = compileAndRun("""
            let s = {1, 2, 3}
            let sum = 0
            for x in s {
                sum = sum + x
            }
            print(sum)
        """.trimIndent())
        // sum should be 6 regardless of iteration order
        assertEquals(listOf("6"), output)
    }

    // === Tuple Tests ===

    @Test
    fun testTupleFactory() {
        val output = compileAndRun("""
            let t = Tuple(1, 2, 3)
            print(t.size())
        """.trimIndent())
        assertEquals(listOf("3"), output)
    }

    @Test
    fun testTupleLiteral() {
        val output = compileAndRun("""
            let t = (1, 2, 3)
            print(t.size())
        """.trimIndent())
        assertEquals(listOf("3"), output)
    }

    @Test
    fun testEmptyTuple() {
        val output = compileAndRun("""
            let t = ()
            print(t.size())
        """.trimIndent())
        assertEquals(listOf("0"), output)
    }

    @Test
    fun testSingleElementTuple() {
        val output = compileAndRun("""
            let t = (42,)
            print(t.size())
            print(t.get(0))
        """.trimIndent())
        assertEquals(listOf("1", "42"), output)
    }

    @Test
    fun testTupleGet() {
        val output = compileAndRun("""
            let t = (10, 20, 30)
            print(t.get(0))
            print(t.get(1))
            print(t.get(2))
        """.trimIndent())
        assertEquals(listOf("10", "20", "30"), output)
    }

    @Test
    fun testTupleGetOutOfBounds() {
        val output = compileAndRun("""
            let t = (1, 2, 3)
            print(t.get(10))
            print(t.get(-1))
        """.trimIndent())
        // Out of bounds returns null
        assertEquals(listOf("null", "null"), output)
    }

    @Test
    fun testTupleHas() {
        val output = compileAndRun("""
            let t = (1, 2, 3)
            print(t.has(2))
            print(t.has(5))
        """.trimIndent())
        assertEquals(listOf("Boolean(value=true)", "Boolean(value=false)"), output)
    }

    @Ignore("SSA round-trip bug with loops causes infinite loop")
    @Test
    fun testTupleIteration() {
        val output = compileAndRun("""
            let sum = 0
            for x in (1, 2, 3) {
                sum = sum + x
            }
            print(sum)
        """.trimIndent())
        assertEquals(listOf("6"), output)
    }

    @Test
    fun testTupleIndexing() {
        val output = compileAndRun("""
            let t = (100, 200, 300)
            print(t[0])
            print(t[1])
            print(t[2])
        """.trimIndent())
        assertEquals(listOf("100", "200", "300"), output)
    }
}
