package org.inklang.peg

import org.inklang.InkCompiler
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end tests for the PEG-based compiler pipeline.
 *
 * These tests verify that the pipeline: PEG parse → IR → bytecode → VM
 * works correctly end-to-end.
 */
class EndToEndTest {

    /**
     * Helper to compile and run source code using the PEG pipeline.
     */
    private fun compileAndRun(source: String): String {
        val pipeline = CompilerPipeline()
        return pipeline.compileAndRun(source)
    }

    @Test
    fun `parse and execute simple expression`() {
        val result = compileAndRun("print(1 + 2)")
        assertEquals("3", result.trim())
    }

    @Test
    fun `parse and execute variable declaration`() {
        val result = compileAndRun("""
            x = 5
            print(x * 2)
        """.trimIndent())
        assertEquals("10", result.trim())
    }

    @Test
    fun `parse and execute function call`() {
        val result = compileAndRun("""
            fn double(n) = n * 2
            print(double(21))
        """.trimIndent())
        assertEquals("42", result.trim())
    }

    @Test
    fun `parse and execute print string`() {
        val result = compileAndRun("""print("hello")""")
        assertEquals("hello", result.trim())
    }

    @Test
    fun `parse and execute print with multiple arguments`() {
        val result = compileAndRun("print(1, 2, 3)")
        assertEquals("1 2 3", result.trim())
    }

    @Test
    fun `parse and execute arithmetic precedence`() {
        val result = compileAndRun("print(2 + 3 * 4)")
        assertEquals("14", result.trim())
    }

    @Test
    fun `parse and execute subtraction`() {
        val result = compileAndRun("print(10 - 3)")
        assertEquals("7", result.trim())
    }

    @Test
    fun `parse and execute division`() {
        val result = compileAndRun("print(20 / 4)")
        assertEquals("5", result.trim())
    }

    @Test
    fun `parse and execute unary negation`() {
        val result = compileAndRun("print(-5)")
        assertEquals("-5", result.trim())
    }
}
