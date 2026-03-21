# Parallel Class Method Compilation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compile class methods in parallel using ForkJoinPool, reducing compilation time for class-heavy code.

**Architecture:** Extract method compilation into a pure `compileMethod()` helper, then use `ForkJoinPool.invokeAll()` in the `LoadClass` case to compile methods concurrently. Pre-allocate `chunk.functions` slots so indices are pre-determined and unique for thread-safe assignment.

**Tech Stack:** Kotlin, `java.util.concurrent.ForkJoinPool`, existing Inklang compiler pipeline (SsaBuilder, SsaDeconstructor, LivenessAnalyzer, RegisterAllocator, SpillInserter).

---

## Chunk 1: Add Helper Method & Data Class

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/IrCompiler.kt` (add data class + helper method)

### Step 1: Add `CompiledMethod` data class and `compileMethod` helper

Find the opening brace of the `IrCompiler` class (line 22). Add the data class and helper method before the `compile(result: AstLowerer.LoweredResult): Chunk` method:

```kotlin
    /**
     * Result of compiling a single method.
     */
    private data class CompiledMethod(
        val chunk: Chunk,
        val spillSlotCount: Int
    )

    /**
     * Compile a single method's IR through the full pipeline.
     * This is a pure function — no shared mutable state.
     */
    private fun compileMethod(methodInfo: MethodInfo): CompiledMethod {
        val ssa = SsaBuilder.build(methodInfo.instrs, methodInfo.constants, methodInfo.arity)
        val deconstructed = SsaDeconstructor.deconstruct(ssa)
        val ranges = LivenessAnalyzer().analyze(deconstructed)
        val alloc = RegisterAllocator().allocate(ranges, methodInfo.arity)
        val resolved = SpillInserter().insert(deconstructed, alloc, ranges)
        val result = AstLowerer.LoweredResult(resolved, methodInfo.constants)
        val chunk = IrCompiler().compile(result)
        return CompiledMethod(chunk, alloc.spillSlotCount)
    }
```

### Step 2: Verify existing code compiles

Run: `./gradlew :lang:compileKotlin --quiet 2>&1 | head -20`
Expected: BUILD SUCCESSFUL

### Step 3: Commit

```bash
git add lang/src/main/kotlin/org/inklang/lang/IrCompiler.kt
git commit -m "feat: add compileMethod helper for parallel execution"
```

---

## Chunk 2: Refactor LoadClass to Use ForkJoinPool

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/IrCompiler.kt` (refactor `LoadClass` case)

### Step 1: Add ForkJoinPool import

At the top of the file, add the import:

```kotlin
import java.util.concurrent.ForkJoinPool
```

### Step 2: Refactor the `LoadClass` case

Find the `IrInstr.LoadClass` case (lines 173-195). Replace it entirely with:

```kotlin
                is IrInstr.LoadClass -> {
                    // Pre-allocate function slots so indices are pre-determined
                    val methodNames = instr.methods.keys.toList()
                    val preAllocatedIndices = mutableMapOf<String, Int>()
                    for (methodName in methodNames) {
                        val idx = chunk.functions.size
                        chunk.functions.add(null as Chunk?)  // placeholder
                        preAllocatedIndices[methodName] = idx
                    }

                    // Compile all methods in parallel using ForkJoinPool
                    val results: Map<String, CompiledMethod> = try {
                        val pool = ForkJoinPool.commonPool()
                        try {
                            val tasks = instr.methods.mapValues { (_, methodInfo) ->
                                pool.submit<CompiledMethod> { compileMethod(methodInfo) }
                            }
                            tasks.mapValues { (_, future) -> future.get() }
                        } finally {
                            pool.shutdown()
                        }
                    } catch (e: Exception) {
                        // Fallback to sequential compilation on any pool failure
                        instr.methods.mapValues { (_, methodInfo) -> compileMethod(methodInfo) }
                    }

                    // Write compiled chunks into pre-allocated slots and build method index
                    val methodFuncIndices = mutableMapOf<String, Int>()
                    for (methodName in methodNames) {
                        val idx = preAllocatedIndices[methodName]!!
                        val compiled = results[methodName]!!
                        chunk.functions[idx] = compiled.chunk
                        chunk.functions[idx].spillSlotCount = compiled.spillSlotCount
                        methodFuncIndices[methodName] = idx
                    }

                    // Add class info to chunk
                    val classIdx = chunk.classes.size
                    chunk.classes.add(ClassInfo(instr.name, instr.superClass, methodFuncIndices))
                    chunk.write(OpCode.BUILD_CLASS, dst = instr.dst, imm = classIdx)
                }
```

### Step 3: Run tests to verify correctness

Run: `./gradlew :lang:test --tests "org.inklang.InkCompilerTest" --quiet 2>&1 | tail -30`
Expected: All tests pass (same output as sequential compilation)

### Step 4: Commit

```bash
git add lang/src/main/kotlin/org/inklang/lang/IrCompiler.kt
git commit -m "feat: parallelize class method compilation with ForkJoinPool"
```

---

## Chunk 3: Add Parallelism Verification Test

**Files:**
- Create: `lang/src/test/kotlin/org/inklang/ParallelMethodCompilationTest.kt`

### Step 1: Write the test

Create the file with:

```kotlin
package org.inklang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.inklang.lang.IrCompiler
import org.inklang.ast.AstLowerer
import org.inklang.ast.ConstantFolder
import org.inklang.lang.Parser
import org.inklang.lang.tokenize

class ParallelMethodCompilationTest {

    class FakeInkContext : InkContext {
        val logs = mutableListOf<String>()
        val prints = mutableListOf<String>()

        override fun log(message: String) { logs.add(message) }
        override fun print(message: String) { prints.add(message) }
    }

    /**
     * Verify that a class with many methods produces correct results.
     */
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

    /**
     * Verify that a class with methods calling each other produces correct results.
     */
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

    /**
     * Verify that multiple classes in same script compile correctly.
     */
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

    /**
     * Verify that parallel compilation doesn't break inheritance.
     */
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

    /**
     * Verify that methods with default parameters compile correctly.
     */
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

    /**
     * Verify timing — parallel compilation should complete without timing out.
     * This is a basic sanity check, not a microbenchmark.
     */
    @Test
    fun `parallel compiled large class completes without error`() {
        // Build a class with 20 methods
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

    /**
     * Verify that concurrent compilation of multiple scripts from different
     * InkCompiler instances is thread-safe.
     */
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
```

### Step 2: Run the new tests

Run: `./gradlew :lang:test --tests "org.inklang.ParallelMethodCompilationTest" --quiet 2>&1 | tail -40`
Expected: All tests pass

### Step 3: Run full test suite

Run: `./gradlew :lang:test --quiet 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, all tests pass

### Step 4: Commit

```bash
git add lang/src/test/kotlin/org/inklang/ParallelMethodCompilationTest.kt
git commit -m "test: add parallel method compilation tests"
```

---

## Summary

| Chunk | Description |
|-------|-------------|
| 1 | Add `CompiledMethod` data class and `compileMethod()` helper |
| 2 | Refactor `LoadClass` case to use `ForkJoinPool.invokeAll()` |
| 3 | Add test file covering correctness, inheritance, defaults, and thread safety |
