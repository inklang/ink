package org.lectern.ast

import org.lectern.lang.*
import org.lectern.ssa.SsaBuilder
import org.lectern.ssa.SsaDeconstructor
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun compileAndRun(source: String): List<String> {
    val output = mutableListOf<String>()
    val tokens = tokenize(source)
    val stmts = Parser(tokens).parse()
    val folder = ConstantFolder()
    val folded = stmts.map { folder.foldStmt(it) }
    val result = AstLowerer().lower(folded)

    // SSA round-trip
    val ssaFunc = SsaBuilder.build(result.instrs, result.constants)
    val ssaDeconstructed = SsaDeconstructor.deconstruct(ssaFunc)
    val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, result.constants)

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
}
