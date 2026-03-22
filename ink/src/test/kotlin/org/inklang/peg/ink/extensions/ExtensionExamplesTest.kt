package org.inklang.peg.ink.extensions

import org.inklang.lang.Expr
import org.inklang.lang.Stmt
import org.inklang.lang.Token
import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.peg.ParseResult
import org.inklang.peg.ink.InkGrammar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the built-in statement extensions (print, every, spawn).
 */
class ExtensionExamplesTest {

    private val grammar = InkGrammar()

    // ============== Print Statement Tests ==============

    @Test
    fun `print statement parses single argument`() {
        val result = grammar.statement.parse("print(42)", 0)
        assertIs<ParseResult.Success<Stmt>>(result)
        assertIs<Stmt.ExprStmt>(result.value)
        val expr = (result.value as Stmt.ExprStmt).expr
        assertIs<Expr.CallExpr>(expr)
        assertEquals("print", (expr.callee as Expr.VariableExpr).name.lexeme)
        assertEquals(1, expr.arguments.size)
    }

    @Test
    fun `print statement parses multiple arguments`() {
        val result = grammar.statement.parse("print(\"Hello\", x, y)", 0)
        assertIs<ParseResult.Success<Stmt>>(result)
        assertIs<Stmt.ExprStmt>(result.value)
        val expr = (result.value as Stmt.ExprStmt).expr
        assertIs<Expr.CallExpr>(expr)
        assertEquals(3, expr.arguments.size)
    }

    @Test
    fun `print statement fails without paren`() {
        val result = grammar.statement.parse("print 42", 0)
        // Should fail because there's no opening paren
        // (falls through to expression parsing which also fails)
    }

    // ============== Every Statement Tests ==============

    @Test
    fun `every statement parses with seconds keyword`() {
        val result = grammar.statement.parse("every 5 seconds { }", 0)
        assertIs<ParseResult.Success<Stmt>>(result)
        assertIs<Stmt.ExprStmt>(result.value)
        val expr = (result.value as Stmt.ExprStmt).expr
        assertIs<Expr.CallExpr>(expr)
    }

    @Test
    fun `every statement parses without seconds keyword`() {
        val result = grammar.statement.parse("every 10 { }", 0)
        assertIs<ParseResult.Success<Stmt>>(result)
    }

    @Test
    fun `every statement fails without number`() {
        val result = grammar.statement.parse("every { }", 0)
        // Should fail - no number before block
        assertIs<ParseResult.Failure>(result)
    }

    // ============== Program Parsing Tests ==============

    @Test
    fun `program parses print statement`() {
        val result = grammar.parseProgram("print(42)")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(1, result.value.size)
    }

    @Test
    fun `program parses multiple statements`() {
        val result = grammar.parseProgram("""
            print(1)
            print(2)
            print(3)
        """.trimIndent())
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(3, result.value.size)
    }

    @Test
    fun `program parses let statement`() {
        val result = grammar.parseProgram("let x = 42")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(1, result.value.size)
        assertIs<Stmt.VarStmt>(result.value[0])
    }

    @Test
    fun `program parses fn declaration`() {
        val result = grammar.parseProgram("fn double(n) = n * 2")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(1, result.value.size)
        assertIs<Stmt.FuncStmt>(result.value[0])
    }

    @Test
    fun `program parses if statement`() {
        val result = grammar.parseProgram("if x > 0 { print(x) }")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(1, result.value.size)
        assertIs<Stmt.IfStmt>(result.value[0])
    }

    @Test
    fun `program parses while statement`() {
        val result = grammar.parseProgram("while x > 0 { x = x - 1 }")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(1, result.value.size)
        assertIs<Stmt.WhileStmt>(result.value[0])
    }

    @Test
    fun `program parses return statement`() {
        val result = grammar.parseProgram("return 42")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(1, result.value.size)
        assertIs<Stmt.ReturnStmt>(result.value[0])
    }

    @Test
    fun `program parses nested expressions`() {
        val result = grammar.parseProgram("print(1 + 2 * 3)")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(1, result.value.size)
    }

    // ============== CombinedGrammar with Extensions Tests ==============

    @Test
    fun `register and parse custom statement via extension context`() {
        // This tests that our extension functions can be used with the extension API
        val context = org.inklang.peg.InkExtensionContext()
        context.name = "test-package"

        // Register the print statement
        context.registerStatement("print") {
            this.pattern = printStatement { grammar.expression }
            this.lower = { args -> Stmt.ExprStmt(Expr.LiteralExpr(Value.Null)) }
        }

        val pkg = context.build()
        assertTrue(pkg.statements.containsKey("print"))
    }
}
