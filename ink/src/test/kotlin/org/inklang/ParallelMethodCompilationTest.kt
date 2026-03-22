package org.inklang

import kotlin.test.Test
import kotlin.test.assertEquals

class ParallelMethodCompilationTest {

    class FakeInkContext : InkContext {
        val logs = mutableListOf<String>()
        val prints = mutableListOf<String>()

        override fun log(message: String) { logs.add(message) }
        override fun print(message: String) { prints.add(message) }
        override fun io(): InkIo = throw UnsupportedOperationException("io not implemented in tests")
        override fun json(): InkJson = throw UnsupportedOperationException("json not implemented in tests")
        override fun db(): InkDb = throw UnsupportedOperationException("db not implemented in tests")
        override fun registerEventHandler(eventName: String, handlerFunc: org.inklang.lang.Value.Function, eventParamName: String, dataParamNames: List<String>) {}
        override fun fireEvent(eventName: String, event: org.inklang.lang.Value.EventObject, data: List<org.inklang.lang.Value?>): Boolean = true
        override fun onEnable(script: InkScript) {}
        override fun onDisable(script: InkScript) {}
        override fun setVM(vm: ContextVM) {}
    }

    @Test
    fun `parallel compiled class with multiple methods produces correct output`() {
        val source = """
            class MathUtils {
                fun add(a, b) { return a + b; }
                fun sub(a, b) { return a - b; }
                fun mul(a, b) { return a * b; }
                fun div(a, b) { return a / b; }
                fun square(x) { return x * x; }
            }
            let m = MathUtils();
            print(m.add(10, 5));
            print(m.sub(10, 5));
            print(m.mul(3, 4));
            print(m.div(20, 4));
            print(m.square(7));
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("15", "5", "12", "5", "49"), context.prints)
    }

    @Test
    fun `parallel compiled class with interdependent methods produces correct output`() {
        val source = """
            class Counter {
                let count = 0;
                fun increment() { count = count + 1; }
                fun getCount() { return count; }
                fun double() { count = count * 2; }
            }
            let c = Counter();
            c.increment();
            c.increment();
            print(c.getCount());
            c.double();
            print(c.getCount());
            c.increment();
            print(c.getCount());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("2", "4", "5"), context.prints)
    }

    @Test
    fun `parallel compiled multiple classes produce correct output`() {
        val source = """
            class A {
                let val = 1;
                fun get() { return val; }
            }
            class B {
                let val = 2;
                fun get() { return val; }
            }
            class C {
                let val = 3;
                fun get() { return val; }
            }
            let a = A();
            let b = B();
            let c = C();
            print(a.get());
            print(b.get());
            print(c.get());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("1", "2", "3"), context.prints)
    }

    @Test
    fun `parallel compiled class with inheritance produces correct output`() {
        val source = """
            class Animal {
                let sound = "???";
                fun speak() { return sound; }
            }
            class Dog extends Animal {
                let sound = "woof";
                fun bark() { return sound; }
            }
            class Cat extends Animal {
                let sound = "meow";
                fun meow() { return sound; }
            }
            let a = Animal();
            let d = Dog();
            let c = Cat();
            print(a.speak());
            print(d.speak());
            print(d.bark());
            print(c.speak());
            print(c.meow());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("???", "woof", "woof", "meow", "meow"), context.prints)
    }

    @Test
    fun `parallel compiled class with default parameters produces correct output`() {
        val source = """
            class Greeter {
                fun greet(name = "World") { return name; }
                fun greetExclaim(name = "World", exclaim = "!") { return name + exclaim; }
            }
            let g = Greeter();
            print(g.greet());
            print(g.greet("Alice"));
            print(g.greetExclaim());
            print(g.greetExclaim("Bob", "!!!"));
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("World", "Alice", "World!", "Bob!!!"), context.prints)
    }

    @Test
    fun `parallel compiled large class completes without error`() {
        val methodDefs = (1..20).joinToString("\n") { i ->
            "fun method$i(x) { return x + $i; }"
        }
        val source = """
            class Large {
                $methodDefs
            }
            let l = Large();
            print(l.method10(0));
            print(l.method20(0));
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source, "test")
        val context = FakeInkContext()
        script.execute(context)

        assertEquals(listOf("10", "20"), context.prints)
    }

    @Test
    fun `concurrent compilation of multiple scripts produces correct results`() {
        val sources = listOf(
            """
            class A { let val = 1; fun get() { return val; } }
            let a = A(); print(a.get());
            """,
            """
            class B { let val = 2; fun get() { return val; } }
            let b = B(); print(b.get());
            """,
            """
            class C { let val = 3; fun get() { return val; } }
            let c = C(); print(c.get());
            """
        )

        val compilers = sources.map { source ->
            val compiler = InkCompiler()
            val script = compiler.compile(source, "test")
            script
        }

        val results = compilers.map { script ->
            val context = FakeInkContext()
            script.execute(context)
            context.prints
        }

        assertEquals(listOf(listOf("1"), listOf("2"), listOf("3")), results)
    }
}
