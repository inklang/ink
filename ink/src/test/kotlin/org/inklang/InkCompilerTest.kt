package org.inklang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class InkCompilerTest {
    /**
     * Fake context for testing - captures output for assertions
     */
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
        override fun registerEventHandler(eventName: String, handlerFunc: org.inklang.lang.Value.Function, eventParamName: String, dataParamNames: List<String>) {}
        override fun fireEvent(eventName: String, event: org.inklang.lang.Value.EventObject, data: List<org.inklang.lang.Value?>): Boolean = true
    }

    @Test
    fun `compile and execute simple print`() {
        val compiler = InkCompiler()
        val script = compiler.compile("print(\"Hello, World!\")", "test")

        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("Hello, World!"), context.prints)
    }

    @Test
    fun `compile and execute with log`() {
        val compiler = InkCompiler()
        val script = compiler.compile("log(\"Server started\")", "test")

        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("Server started"), context.logs)
    }

    @Test
    fun `compile and execute arithmetic`() {
        val compiler = InkCompiler()
        val script = compiler.compile("print(2 + 3 * 4)", "test")

        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("14"), context.prints)
    }

    @Test
    fun `compile and execute function`() {
        val compiler = InkCompiler()
        val source = """
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
    fun `compile and execute class`() {
        val compiler = InkCompiler()
        val source = """
            class Counter {
                let count = 0;
                fun increment() {
                    count = count + 1;
                }
            }
            let c = Counter();
            c.increment();
            c.increment();
            print(c.count);
        """.trimIndent()
        val script = compiler.compile(source, "test")

        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("2"), context.prints)
    }

    @Test
    fun `compile and execute for loop`() {
        val compiler = InkCompiler()
        val source = """
            let sum = 0;
            for i in 1..5 {
                sum = sum + i;
            }
            print(sum);
        """.trimIndent()
        val script = compiler.compile(source, "test")

        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("15"), context.prints)
    }

    @Test
    fun `compilation error on syntax error`() {
        val compiler = InkCompiler()

        assertFailsWith<CompilationException> {
            compiler.compile("let x =", "test")
        }
    }

    @Test
    fun `runtime error on undefined variable`() {
        val compiler = InkCompiler()
        val script = compiler.compile("print(undefinedVar)", "test")

        val context = FakeInkContext()

        assertFailsWith<ScriptException> {
            script.execute(context)
        }
    }

    @Test
    fun `timeout protection`() {
        val compiler = InkCompiler()
        val source = """
            while (true) {
                let x = 1
            }
        """.trimIndent()
        val script = compiler.compile(source, "test")

        val context = FakeInkContext()

        assertFailsWith<ScriptTimeoutException> {
            script.execute(context, maxInstructions = 1000)
        }
    }

    @Test
    fun `string interpolation`() {
        val compiler = InkCompiler()
        val source = """
            let name = "World";
            print("Hello, ${'$'}{name}!");
        """.trimIndent()
        val script = compiler.compile(source, "test")

        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("Hello, World!"), context.prints)
    }

    @Test
    fun `safe call returns null when receiver is null`() {
        val compiler = InkCompiler()
        val source = """
            let user = null;
            print(user?.name);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("null"), context.prints)
    }

    @Test
    fun `safe call returns field when receiver is not null`() {
        val compiler = InkCompiler()
        val source = """
            class User {
                let name = "Alice";
            }
            let user = User();
            print(user?.name);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("Alice"), context.prints)
    }

    @Test
    fun `elvis operator returns right side when left is null`() {
        val compiler = InkCompiler()
        val source = """
            let value = null;
            print(value ?? "default");
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("default"), context.prints)
    }

    @Test
    fun `elvis operator returns left side when not null`() {
        val compiler = InkCompiler()
        val source = """
            let value = "hello";
            print(value ?? "default");
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("hello"), context.prints)
    }

    @Test
    fun `chained safe calls`() {
        val compiler = InkCompiler()
        val source = """
            class Address {
                let city = "NYC";
            }
            class User {
                let address = Address();
            }
            let user = User();
            print(user?.address?.city);
            let noAddress = null;
            print(noAddress?.city);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("NYC", "null"), context.prints)
    }

    @Test
    fun `safe call combined with elvis`() {
        val compiler = InkCompiler()
        val source = """
            let user = null;
            print(user?.name ?? "anonymous");
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("anonymous"), context.prints)
    }

    @Test
    fun `compile and execute async function call`() {
        val compiler = InkCompiler()
        val source = """
            async fn getValue() {
                return 42;
            }
            let task = getValue();
            print("got task");
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("got task"), context.prints)
    }

    @Test
    fun `compile and execute spawn`() {
        val compiler = InkCompiler()
        // Note: spawn currently prints to stdout, not context
        // This test just verifies spawn doesn't crash
        val source = """
            fn simple() {
                print("simple spawn");
            }
            spawn simple();
            print("done");
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        // Just verify "done" was printed to context
        assertTrue(context.prints.contains("done"), "Expected 'done', got: ${context.prints}")
    }

    @Test
    fun `compile and execute async function with await`() {
        val compiler = InkCompiler()
        val source = """
            async fn getValue() {
                return 42;
            }
            let task = getValue();
            let result = await task;
            print(result);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("42"), context.prints)
    }

    @Test
    fun `compile and execute spawn virtual`() {
        val compiler = InkCompiler()
        val source = """
            fn work() {
                return 99;
            }
            let task = spawn virtual work();
            let result = await task;
            print(result);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("99"), context.prints)
    }

    @Test
    fun `async function with arithmetic`() {
        val compiler = InkCompiler()
        val source = """
            async fn compute() {
                let x = 10 + 5;
                let y = x * 2;
                return y;
            }
            let task = compute();
            let result = await task;
            print(result);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("30"), context.prints)
    }

    @Test
    fun `multiple async tasks spawned sequentially`() {
        val compiler = InkCompiler()
        val source = """
            async fn task1() { return 1; }
            async fn task2() { return 2; }
            let t1 = task1();
            let t2 = task2();
            let r1 = await t1;
            let r2 = await t2;
            print(r1);
            print(r2);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("1", "2"), context.prints)
    }

    @Test
    fun `async function calling another async function`() {
        val compiler = InkCompiler()
        val source = """
            async fn inner() {
                return 100;
            }
            async fn outer() {
                let x = await inner();
                return x + 50;
            }
            let task = outer();
            let result = await task;
            print(result);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("150"), context.prints)
    }

    @Test
    fun `async function with conditional logic`() {
        val compiler = InkCompiler()
        val source = """
            async fn getValue(flag) {
                if (flag) {
                    return 42;
                } else {
                    return 99;
                }
            }
            let t1 = getValue(true);
            let t2 = getValue(false);
            let r1 = await t1;
            let r2 = await t2;
            print(r1);
            print(r2);
        """.trimIndent()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)
        assertEquals(listOf("42", "99"), context.prints)
    }
}
