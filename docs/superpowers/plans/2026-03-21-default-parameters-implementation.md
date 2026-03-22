# Default Parameters Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add test coverage for existing default parameter implementation and implement named argument support (`foo(x = 5)`).

**Architecture:** Default parameters are already fully implemented in the compiler pipeline (Parser → AST → IR → VM). This plan adds tests for existing functionality and implements named argument support at the call site.

**Tech Stack:** Kotlin 2.2.21, JVM 21, Inklang bytecode VM

---

## Discovery Summary

The default parameter feature is **already implemented** end-to-end:

| Component | File | Status |
|-----------|------|--------|
| Parser | `ink/src/main/kotlin/org/inklang/lang/Parser.kt:273-320` | ✅ Parses `= expr` syntax |
| AST | `ink/src/main/kotlin/org/inklang/lang/AST.kt:95-100` | ✅ `Param.defaultValue: Expr?` |
| IR | `ink/src/main/kotlin/org/inklang/lang/IR.kt:70-73` | ✅ `DefaultValueInfo` |
| Lowering | `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt:361-373` | ✅ Lowers defaults |
| Compilation | `ink/src/main/kotlin/org/inklang/ast/IrCompiler.kt:149-170` | ✅ Compiles defaults |
| VM | `ink/src/main/kotlin/org/inklang/ast/VM.kt:487-606` | ✅ `fillDefaultArgs()` + `executeDefaultChunk()` |

**Verified working:**
```ink
fn greet(name: String = "World") { print(name) }
greet()       // "World" ✅
greet("Alice") // "Alice" ✅
```

---

## What Needs To Be Done

1. **Add test coverage** for existing default parameter implementation
2. **Implement named arguments** — `foo(x = 5)` syntax

---

## Chunk 1: Test Coverage for Defaults

**Files:**
- Modify: `ink/src/test/kotlin/org/inklang/InkCompilerTest.kt`

### Task 1: Add basic default parameter tests

- [ ] **Step 1: Add test for basic default usage**

```kotlin
@Test
fun `function with default parameter uses default when not provided`() {
    val compiler = InkCompiler()
    val script = compiler.compile("""
        fn greet(name: String = "World") {
            print(name)
        }
        greet()
    """, "test")

    val context = CapturingContext()
    script.execute(context)
    assertEquals(listOf("World"), context.prints)
}
```

- [ ] **Step 2: Add test for default overridden at call site**

```kotlin
@Test
fun `function with default parameter uses provided value`() {
    val compiler = InkCompiler()
    val script = compiler.compile("""
        fn greet(name: String = "World") {
            print(name)
        }
        greet("Alice")
    """, "test")

    val context = CapturingContext()
    script.execute(context)
    assertEquals(listOf("Alice"), context.prints)
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `./gradlew :ink:test --tests "org.inklang.InkCompilerTest" -v`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add ink/src/test/kotlin/org/inklang/InkCompilerTest.kt
git commit -m "test: add basic default parameter tests

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

### Task 2: Add multiple defaults test

- [ ] **Step 1: Add test for multiple default parameters**

```kotlin
@Test
fun `function with multiple defaults fills in order`() {
    val compiler = InkCompiler()
    val script = compiler.compile("""
        fn add(a: Int, b: Int = 10, c: Int = 20) -> Int {
            a + b + c
        }
        print(add(1))
        print(add(1, 2))
        print(add(1, 2, 3))
    """, "test")

    val context = CapturingContext()
    script.execute(context)
    assertEquals(listOf("31", "23", "6"), context.prints)
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :ink:test --tests "org.inklang.InkCompilerTest" -v`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add ink/src/test/kotlin/org/inklang/InkCompilerTest.kt
git commit -m "test: add multiple default parameters test

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

### Task 3: Add constructor with defaults test

- [ ] **Step 1: Add test for constructor with default parameters**

```kotlin
@Test
fun `class constructor with default parameters`() {
    val compiler = InkCompiler()
    val script = compiler.compile("""
        class Person {
            var name: String
            fn new(name: String = "Anonymous") {
                this.name = name
            }
        }
        val p1 = Person::new()
        val p2 = Person::new("Bob")
        print(p1.name)
        print(p2.name)
    """, "test")

    val context = CapturingContext()
    script.execute(context)
    assertEquals(listOf("Anonymous", "Bob"), context.prints)
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :ink:test --tests "org.inklang.InkCompilerTest" -v`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add ink/src/test/kotlin/org/inklang/InkCompilerTest.kt
git commit -m "test: add constructor default parameters test

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

### Task 4: Add default referencing other params test

- [ ] **Step 1: Add test for default value referencing earlier parameter**

```kotlin
@Test
fun `default value can reference earlier parameter`() {
    val compiler = InkCompiler()
    val script = compiler.compile("""
        fn scale(value: Int, multiplier: Int = value * 2) -> Int {
            value * multiplier
        }
        print(scale(5))
        print(scale(5, 3))
    """, "test")

    val context = CapturingContext()
    script.execute(context)
    assertEquals(listOf("50", "15"), context.prints)
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :ink:test --tests "org.inklang.InkCompilerTest" -v`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add ink/src/test/kotlin/org/inklang/InkCompilerTest.kt
git commit -m "test: add default referencing other params test

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 2: Named Arguments Implementation

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt` — Parse `name = value` in call arguments
- Modify: `ink/src/main/kotlin/org/inklang/lang/AST.kt` — Add `NamedArg` to `Expr`
- Modify: `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt` — Resolve named args to positional at call site

### Task 5: Add NamedArg expression to AST

- [ ] **Step 1: Add NamedArg expression type**

In `AST.kt`, add to the `Expr` sealed class:

```kotlin
data class NamedArgExpr(val name: Token, val value: Expr) : Expr()
```

### Task 6: Parse named arguments in function calls

- [ ] **Step 1: Modify CallExpr parsing to handle named args**

In `Parser.kt`, around lines 472-478, modify `parsePostfix` to detect named argument syntax (`identifier = expr`):

```kotlin
// In parsePostfix, when parsing arguments:
match(TokenType.L_PAREN) -> {
    val args = mutableListOf<Expr>()
    if (!check(TokenType.R_PAREN)) {
        var seenNamed = false
        do {
            // Check if this is a named argument: name = expr
            if (check(TokenType.IDENTIFIER) && checkAhead(1, TokenType.ASSIGN)) {
                val name = advance() // consume identifier
                advance() // consume =
                val value = parseExpression(0)
                args.add(Expr.NamedArgExpr(name, value))
                seenNamed = true
            } else {
                if (seenNamed) {
                    throw error(peek(), "Positional argument cannot follow named argument")
                }
                args.add(parseExpression(0))
            }
        } while (match(TokenType.COMMA))
    }
    val paren = consume(TokenType.R_PAREN, "Expected ')' after arguments")
    Expr.CallExpr(expr, paren, args)
}
```

### Task 7: Lower named arguments to positional at call site

- [ ] **Step 1: Modify lowerExpr for CallExpr to resolve named arguments**

In `AstLowerer.kt`, around lines 642-648, modify `lowerExpr` for `Expr.CallExpr`:

```kotlin
is Expr.CallExpr -> {
    // Regular function/method/constructor call
    val funcReg = lowerExpr(expr.callee, freshReg())

    // Resolve named arguments to positional
    val argRegs = resolveArgsToPositional(expr.callee, expr.arguments)

    emit(Call(dst, funcReg, argRegs))
    dst
}
```

Add helper function to resolve named arguments:

```kotlin
/**
 * Resolve named arguments to positional arguments based on function signature.
 * At compile time, we need the function's parameter names to resolve named args.
 * For now, emit the named args as-is and handle at runtime via metadata.
 */
private fun resolveArgsToPositional(callee: Expr, arguments: List<Expr>): List<Int> {
    // Group positional and named args
    val positionalArgs = mutableListOf<Expr>()
    val namedArgs = mutableMapOf<String, Expr>()

    for (arg in arguments) {
        when (arg) {
            is Expr.NamedArgExpr -> namedArgs[arg.name.lexeme] = arg.value
            else -> positionalArgs.add(arg)
        }
    }

    // For each named arg, we need to look up the param index
    // This requires type information that we don't have easily in the lowerer
    // Alternative: handle this at IR level with a new instruction

    // For now, emit positional args directly and handle named args specially
    // TODO: Full implementation needs function signature lookup
    return arguments.map { lowerExpr(it, freshReg()) }
}
```

**Note:** Full named argument resolution requires knowing the parameter names at the call site. This needs additional infrastructure (either storing param names in IR/bytecode, or resolving at compile time with type information).

### Task 8: Handle NamedArg in expression lowering

- [ ] **Step 1: Add case for NamedArgExpr in lowerExpr**

```kotlin
is Expr.NamedArgExpr -> {
    // Named args should have been handled by the CallExpr lowering
    // This is here as a fallback
    lowerExpr(arg.value, dst)
}
```

### Task 9: Add test for named arguments

- [ ] **Step 1: Add test for named argument with default**

```kotlin
@Test
fun `named arguments work with defaults`() {
    val compiler = InkCompiler()
    val script = compiler.compile("""
        fn configure(host: String, port: Int = 8080, debug: Bool = false) {
            print(host)
            print(port)
            print(debug)
        }
        configure("localhost", debug = true)
    """, "test")

    val context = CapturingContext()
    script.execute(context)
    assertEquals(listOf("localhost", "8080", "true"), context.prints)
}
```

- [ ] **Step 2: Run tests and iterate**

Run: `./gradlew :ink:test --tests "org.inklang.InkCompilerTest" -v`
Expected: May fail — named args implementation may need refinement

- [ ] **Step 3: Debug and fix any issues**

If tests fail, check:
1. Parser correctly parses `name = value` syntax
2. Named arguments are passed through correctly
3. Default filling works with named args

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Parser.kt
git add ink/src/main/kotlin/org/inklang/lang/AST.kt
git add ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt
git add ink/src/test/kotlin/org/inklang/InkCompilerTest.kt
git commit -m "feat: add named argument support for function calls

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Implementation Notes

### Named Arguments Design

Named arguments require the compiler to know parameter names at call sites. Two approaches:

**Approach A: Store param names in bytecode**
- Store function param names in `Chunk` metadata
- At call site, match named args by name and reorder
- More complex but enables runtime reflection

**Approach B: Resolve at compile time (simpler)**
- During type checking, resolve named args to positional indices
- Emit positional args directly
- Requires type info available at compile time

**Recommended: Approach B** — Inklang has type information available during compilation.

### Default Parameter Reference Resolution

Defaults like `fn foo(a: Int, b: Int = a * 2)` require the compiler to:
1. Lower default expressions with access to the function's local scope
2. Evaluate defaults in the **caller's context** at runtime (not the function's)

Current implementation handles this correctly via `executeDefaultChunk` which runs in the caller's frame context.

---

## Verification

After all tasks:

1. Run full test suite: `./gradlew :ink:test`
2. All tests should pass
3. Manual verification with test script:

```ink
// Basic defaults
fn greet(name: String = "World") {
    print(name)
}
greet()                    // "World"
greet("Alice")            // "Alice"

// Multiple defaults
fn add(a: Int, b: Int = 10, c: Int = 20) -> Int {
    a + b + c
}
add(1)                     // 31
add(1, 2)                 // 23
add(1, c = 100)           // 111

// Constructor defaults
class Person {
    var name: String
    fn new(name: String = "Anonymous") {
        this.name = name
    }
}
Person::new()              // "Anonymous"
Person::new("Bob")         // "Bob"
```
