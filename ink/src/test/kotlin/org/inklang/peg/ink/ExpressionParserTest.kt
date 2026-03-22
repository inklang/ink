package org.inklang.peg.ink

import org.inklang.lang.Expr
import org.inklang.lang.Value
import org.inklang.peg.ParseResult
import org.inklang.peg.ink.expressions.floatLiteral
import org.inklang.peg.ink.expressions.integerLiteral
import org.inklang.peg.ink.expressions.stringLiteral
import org.inklang.peg.ink.expressions.booleanLiteral
import org.inklang.peg.ink.expressions.nullLiteral
import org.inklang.peg.ink.expressions.identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExpressionParserTest {

    private val grammar = InkGrammar()

    // ============== Literal Parsing Tests ==============

    @Test
    fun `integer literal parses correctly`() {
        val result = integerLiteral().parse("42", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        val literal = result.value as Expr.LiteralExpr
        assertEquals(Value.Int(42), literal.literal)
        assertEquals(2, result.position)
    }

    @Test
    fun `negative integer returns failure at prefix level`() {
        // Negative numbers should be handled by unary expression parser
        val result = integerLiteral().parse("-42", 0)
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `float literal parses correctly`() {
        val result = floatLiteral().parse("3.14", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        val literal = result.value as Expr.LiteralExpr
        assertEquals(Value.Double(3.14), literal.literal)
        assertEquals(4, result.position)
    }

    @Test
    fun `float literal without fractional part fails`() {
        val result = floatLiteral().parse("3.", 0)
        assertIs<ParseResult.Failure>(result)
    }

    @Test
    fun `string literal parses correctly`() {
        val result = stringLiteral().parse("\"hello\"", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        val literal = result.value as Expr.LiteralExpr
        assertEquals(Value.String("hello"), literal.literal)
        assertEquals(7, result.position)
    }

    @Test
    fun `string literal with escape sequences`() {
        val result = stringLiteral().parse("\"hello\\nworld\"", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        val literal = result.value as Expr.LiteralExpr
        assertEquals("hello\nworld", (literal.literal as Value.String).value)
    }

    @Test
    fun `boolean true parses correctly`() {
        val result = booleanLiteral().parse("true", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        val literal = result.value as Expr.LiteralExpr
        assertEquals(Value.Boolean(true), literal.literal)
        assertEquals(4, result.position)
    }

    @Test
    fun `boolean false parses correctly`() {
        val result = booleanLiteral().parse("false", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        val literal = result.value as Expr.LiteralExpr
        assertEquals(Value.Boolean(false), literal.literal)
        assertEquals(5, result.position)
    }

    @Test
    fun `null literal parses correctly`() {
        val result = nullLiteral().parse("null", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        val literal = result.value as Expr.LiteralExpr
        assertEquals(Value.Null, literal.literal)
        assertEquals(4, result.position)
    }

    // ============== Identifier Parsing Tests ==============

    @Test
    fun `identifier parses correctly`() {
        val result = identifier().parse("foo", 0)
        assertIs<ParseResult.Success<Expr.VariableExpr>>(result)
        assertEquals("foo", result.value.name.lexeme)
        assertEquals(3, result.position)
    }

    @Test
    fun `identifier with underscores parses correctly`() {
        val result = identifier().parse("my_variable_123", 0)
        assertIs<ParseResult.Success<Expr.VariableExpr>>(result)
        assertEquals("my_variable_123", result.value.name.lexeme)
    }

    @Test
    fun `identifier starting with underscore parses correctly`() {
        val result = identifier().parse("_private", 0)
        assertIs<ParseResult.Success<Expr.VariableExpr>>(result)
        assertEquals("_private", result.value.name.lexeme)
    }

    @Test
    fun `identifier starting with number fails`() {
        val result = identifier().parse("123abc", 0)
        assertIs<ParseResult.Failure>(result)
    }

    // ============== Grammar Expression Parsing Tests ==============

    @Test
    fun `grammar expression parses integer literal`() {
        val result = grammar.expression.parse("42", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        assertEquals(Value.Int(42), (result.value as Expr.LiteralExpr).literal)
    }

    @Test
    fun `grammar expression parses string literal`() {
        val result = grammar.expression.parse("\"hello\"", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        assertEquals(Value.String("hello"), (result.value as Expr.LiteralExpr).literal)
    }

    @Test
    fun `grammar expression parses identifier`() {
        val result = grammar.expression.parse("foo", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.VariableExpr>(result.value)
        assertEquals("foo", result.value.name.lexeme)
    }

    @Test
    fun `grammar expression parses simple addition`() {
        val result = grammar.expression.parse("1 + 2", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("+", result.value.op.lexeme)
    }

    @Test
    fun `grammar expression parses addition and subtraction`() {
        val result = grammar.expression.parse("1 + 2 - 3", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("-", result.value.op.lexeme)
    }

    @Test
    fun `grammar expression respects precedence - multiplication before addition`() {
        val result = grammar.expression.parse("1 + 2 * 3", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("+", result.value.op.lexeme)

        // Left side should be 1
        assertIs<Expr.LiteralExpr>(result.value.left)
        assertEquals(Value.Int(1), (result.value.left as Expr.LiteralExpr).literal)

        // Right side should be 2 * 3
        assertIs<Expr.BinaryExpr>(result.value.right)
        assertEquals("*", result.value.right.op.lexeme)
    }

    @Test
    fun `grammar expression respects precedence - parentheses override`() {
        val result = grammar.expression.parse("(1 + 2) * 3", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("*", result.value.op.lexeme)

        // Left side should be (1 + 2)
        assertIs<Expr.GroupExpr>(result.value.left)
        val groupExpr = result.value.left as Expr.GroupExpr
        assertIs<Expr.BinaryExpr>(groupExpr.expr)
        assertEquals("+", groupExpr.expr.op.lexeme)
    }

    @Test
    fun `grammar expression parses comparison operators`() {
        val result = grammar.expression.parse("1 < 2", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("<", result.value.op.lexeme)
    }

    @Test
    fun `grammar expression parses equality operators`() {
        val result = grammar.expression.parse("1 == 2", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("==", result.value.op.lexeme)
    }

    @Test
    fun `grammar expression parses logical AND`() {
        val result = grammar.expression.parse("true and false", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("and", result.value.op.lexeme)
    }

    @Test
    fun `grammar expression parses logical OR`() {
        val result = grammar.expression.parse("true or false", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("or", result.value.op.lexeme)
    }

    @Test
    fun `grammar expression parses complex expression with multiple operators`() {
        val result = grammar.expression.parse("1 + 2 * 3 - 4 / 2", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("-", result.value.op.lexeme)
    }

    @Test
    fun `grammar expression parses unary negation`() {
        val result = grammar.expression.parse("-42", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.UnaryExpr>(result.value)
        assertEquals("-", result.value.op.lexeme)
        assertIs<Expr.LiteralExpr>(result.value.right)
        assertEquals(Value.Int(42), (result.value.right as Expr.LiteralExpr).literal)
    }

    @Test
    fun `grammar expression parses double negation`() {
        val result = grammar.expression.parse("--42", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.UnaryExpr>(result.value)
        assertIs<Expr.UnaryExpr>(result.value.right)
    }

    @Test
    fun `grammar expression parses variable assignment`() {
        val result = grammar.expression.parse("x = 42", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.AssignExpr>(result.value)
        assertEquals("x", (result.value.target as Expr.VariableExpr).name.lexeme)
        assertIs<Expr.LiteralExpr>(result.value.value)
    }

    @Test
    fun `grammar expression parses function call`() {
        val result = grammar.expression.parse("foo()", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.CallExpr>(result.value)
        assertIs<Expr.VariableExpr>(result.value.callee)
        assertEquals("foo", (result.value.callee as Expr.VariableExpr).name.lexeme)
        assertTrue(result.value.arguments.isEmpty())
    }

    @Test
    fun `grammar expression parses function call with arguments`() {
        val result = grammar.expression.parse("foo(1, 2, 3)", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.CallExpr>(result.value)
        assertEquals(3, result.value.arguments.size)
    }

    @Test
    fun `grammar expression parses nested function calls`() {
        val result = grammar.expression.parse("foo(bar())", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.CallExpr>(result.value)
        val innerCall = result.value.arguments[0]
        assertIs<Expr.CallExpr>(innerCall)
    }

    @Test
    fun `grammar expression parses method call`() {
        val result = grammar.expression.parse("obj.method()", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.CallExpr>(result.value)
        assertIs<Expr.GetExpr>(result.value.callee)
    }

    @Test
    fun `grammar expression parses parenthesized expression`() {
        val result = grammar.expression.parse("(42)", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.GroupExpr>(result.value)
        assertIs<Expr.LiteralExpr>(result.value.expr)
    }

    @Test
    fun `grammar expression parses complex nested expression`() {
        val result = grammar.expression.parse("((1 + 2) * (3 - 4)) / 5", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("/", result.value.op.lexeme)
    }

    @Test
    fun `grammar expression parses boolean and comparison`() {
        val result = grammar.expression.parse("true and 1 < 2", 0)
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.BinaryExpr>(result.value)
        assertEquals("and", result.value.op.lexeme)
    }

    @Test
    fun `parseExpression helper works correctly`() {
        val result = grammar.parseExpression("42")
        assertIs<ParseResult.Success<Expr>>(result)
        assertIs<Expr.LiteralExpr>(result.value)
        assertEquals(Value.Int(42), (result.value as Expr.LiteralExpr).literal)
    }
}
