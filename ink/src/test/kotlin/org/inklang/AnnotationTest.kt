package org.inklang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnnotationTest {
    class FakeInkContext : InkContext {
        val logs = mutableListOf<String>()
        val prints = mutableListOf<String>()

        override fun log(message: String) {
            logs.add(message)
        }

        override fun print(message: String) {
            prints.add(message)
        }

        override fun io(): InkIo = throw UnsupportedOperationException("io not implemented in tests")
        override fun json(): InkJson = throw UnsupportedOperationException("json not implemented in tests")
        override fun db(): InkDb = throw UnsupportedOperationException("db not implemented in tests")
    }

    @Test
    fun `simple annotation compiles and runs`() {
        val compiler = InkCompiler()
        val source = """
            @deprecated(reason: "Use newAdd instead")
            fn add(a, b) {
                return a + b;
            }
            print(add(10, 20));
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("30"), context.prints)
    }

    @Test
    fun `multiple annotations on function`() {
        val compiler = InkCompiler()
        val source = """
            @inline(level: 2)
            @pure
            fn multiply(a, b) {
                return a * b;
            }
            print(multiply(3, 4));
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("12"), context.prints)
    }

    @Test
    fun `inline level 1 is valid`() {
        val compiler = InkCompiler()
        val source = """
            @inline(level: 1)
            fn inc(x) {
                return x + 1;
            }
            print(inc(5));
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("6"), context.prints)
    }

    @Test
    fun `inline level 3 is valid`() {
        val compiler = InkCompiler()
        val source = """
            @inline(level: 3)
            fn dec(x) {
                return x - 1;
            }
            print(dec(5));
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("4"), context.prints)
    }

    @Test
    fun `inline level 0 is invalid`() {
        val compiler = InkCompiler()
        val source = """
            @inline(level: 0)
            fn inc(x) {
                return x + 1;
            }
        """.trimIndent()
        assertFailsWith<CompilationException> {
            compiler.compile(source, "test")
        }
    }

    @Test
    fun `inline level 4 is invalid`() {
        val compiler = InkCompiler()
        val source = """
            @inline(level: 4)
            fn inc(x) {
                return x + 1;
            }
        """.trimIndent()
        assertFailsWith<CompilationException> {
            compiler.compile(source, "test")
        }
    }

    @Test
    fun `pure function with print fails`() {
        val compiler = InkCompiler()
        val source = """
            @pure
            fn loggedAdd(a, b) {
                print(a);
                return a + b;
            }
        """.trimIndent()
        assertFailsWith<CompilationException> {
            compiler.compile(source, "test")
        }
    }

    @Test
    fun `pure function with arithmetic succeeds`() {
        val compiler = InkCompiler()
        val source = """
            @pure
            fn add(a, b) {
                return a + b;
            }
            print(add(10, 20));
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("30"), context.prints)
    }

    @Test
    fun `pure function with ternary succeeds`() {
        val compiler = InkCompiler()
        val source = """
            @pure
            fn max(a, b) {
                return a > b ? a : b;
            }
            print(max(10, 20));
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("20"), context.prints)
    }

    @Test
    fun `pure function with elvis succeeds`() {
        val compiler = InkCompiler()
        val source = """
            @pure
            fn nullOrValue(x) {
                return x ?? 42;
            }
            print(nullOrValue(null));
            print(nullOrValue(10));
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("42", "10"), context.prints)
    }

    @Test
    fun `annotation on class`() {
        val compiler = InkCompiler()
        val source = """
            @deprecated(reason: "Use NewCounter")
            class Counter {
                let count = 0;
            }
            let c = Counter();
            print(c.count);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("0"), context.prints)
    }

    @Test
    fun `annotation on variable`() {
        val compiler = InkCompiler()
        val source = """
            @deprecated(reason: "Use newX")
            let x = 10;
            print(x);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("10"), context.prints)
    }

    @Test
    fun `user defined annotation declaration`() {
        val compiler = InkCompiler()
        val source = """
            annotation MyAnnotation {
                value: int,
                label: string = "default"
            }
            @MyAnnotation(value: 42)
            fn test() {
                return 1;
            }
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf(), context.prints)
    }

    @Test
    fun `user defined annotation with default value`() {
        val compiler = InkCompiler()
        val source = """
            annotation Config {
                enabled: bool = true
            }
            @Config
            fn test() {
                return 1;
            }
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf(), context.prints)
    }

    @Test
    fun `annotation with wrong field type fails`() {
        val compiler = InkCompiler()
        val source = """
            @inline(level: "high")
            fn test() {
                return 1;
            }
        """.trimIndent()
        assertFailsWith<CompilationException> {
            compiler.compile(source, "test")
        }
    }

    @Test
    fun `annotation with missing required field fails`() {
        val compiler = InkCompiler()
        val source = """
            @deprecated
            fn test() {
                return 1;
            }
        """.trimIndent()
        assertFailsWith<CompilationException> {
            compiler.compile(source, "test")
        }
    }

    @Test
    fun `unknown annotation on function without warn mode is ignored`() {
        val compiler = InkCompiler()
        val source = """
            @unknownAnnotation
            fn test() {
                return 1;
            }
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf(), context.prints)
    }

    @Test
    fun `inline without level uses default`() {
        val compiler = InkCompiler()
        val source = """
            @inline
            fn inc(x) {
                return x + 1;
            }
            print(inc(5));
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("6"), context.prints)
    }
}
