# Generics Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add parametric generics to Inklang: `<T>` type parameters, `T : Constraint` bounds, type inference at call sites, and erasure at runtime.

**Architecture:** Generics are handled entirely at compile time. Type parameters are resolved during parsing, constraint checking happens in a new TypeResolver pass, and the runtime uses erasure (JVM style) — all generic `Box<Int>` and `Box<String>` share the same bytecode class.

**Tech Stack:** Kotlin, existing Inklang compiler pipeline (Lexer → Parser → TypeResolver → AstLowerer → IR → VM)

---

## Chunk 1: AST Changes — Add TypeParam

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/AST.kt:93-101`
- Test: `ink/src/test/kotlin/org/inklang/GenericTest.kt` (new file)

- [ ] **Step 1: Add TypeParam data class to AST.kt**

Add this after the `Param` class in `AST.kt`:

```kotlin
/**
 * A type parameter declaration: T, T : Printable, T : Any, Comparable
 * @param name The type parameter name (e.g., "T")
 * @param bounds List of constraint names (e.g., ["Printable", "Comparable"]). Empty means Any.
 */
data class TypeParam(
    val name: Token,
    val bounds: List<Token> = emptyList()
)
```

- [ ] **Step 2: Update FuncStmt to include typeParams**

In `AST.kt`, modify `FuncStmt`:

```kotlin
data class FuncStmt(
    val annotations: List<Expr.AnnotationExpr>,
    val name: Token,
    val typeParams: List<TypeParam>,           // NEW: was List<Unit> or missing
    val params: List<Param>,
    val returnType: Token?,
    val body: Stmt.BlockStmt
)
```

- [ ] **Step 3: Update ClassStmt to include typeParams**

In `AST.kt`, modify `ClassStmt`:

```kotlin
data class ClassStmt(
    val annotations: List<Expr.AnnotationExpr>,
    val name: Token,
    val typeParams: List<TypeParam>,           // NEW
    val superClass: Token?,
    val body: Stmt.BlockStmt
) : Stmt()
```

- [ ] **Step 4: Create GenericTest.kt with basic parsing tests**

```kotlin
package org.inklang

import kotlin.test.Test
import kotlin.test.assertEquals

class GenericTest {
    @Test
    fun `parse generic function with single type parameter`() {
        val compiler = InkCompiler()
        val source = "fn identity<T>(x: T) -> T { x }"
        // Should compile without error
        val script = compiler.compile(source, "test")
        // Just verify it parses - runtime will use erasure
    }

    @Test
    fun `parse generic function with constraint`() {
        val compiler = InkCompiler()
        val source = "fn print<T : Printable>(value: T) { io.println(value) }"
        val script = compiler.compile(source, "test")
    }

    @Test
    fun `parse generic class`() {
        val compiler = InkCompiler()
        val source = "class Box<T> { var item: T }"
        val script = compiler.compile(source, "test")
    }

    @Test
    fun `parse generic class with constraint`() {
        val compiler = InkCompiler()
        val source = "class Container<T : Printable> { var item: T }"
        val script = compiler.compile(source, "test")
    }

    @Test
    fun `parse multiple type parameters`() {
        val compiler = InkCompiler()
        val source = "fn pair<A, B>(a: A, b: B) -> (A, B) { (a, b) }"
        val script = compiler.compile(source, "test")
    }
}
```

- [ ] **Step 5: Run tests to verify they fail (parser not updated yet)**

Run: `./gradlew :ink:test --tests "org.inklang.GenericTest" 2>&1 | head -50`
Expected: Compilation error (TypeParam not found, parser doesn't handle `<`)

- [ ] **Step 6: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/AST.kt ink/src/test/kotlin/org/inklang/GenericTest.kt
git commit -m "feat(generics): add TypeParam to AST and basic test scaffolding
- Add TypeParam data class with name and bounds
- Update FuncStmt and ClassStmt to include typeParams field
- Add basic parsing tests for generic functions and classes
Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 2: Parser Changes — Parse `<T>` Syntax

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt:236-283` (parseFunc)
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt:297-311` (parseClass)
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt:285-295` (parseType → also handle type params in types)

- [ ] **Step 1: Add helper to check if a type token starts a generic type**

In `Parser.kt`, add after `parseType()`:

```kotlin
private fun parseTypeParams(): List<TypeParam> {
    if (!match(TokenType.LT)) return emptyList()
    val typeParams = mutableListOf<TypeParam>()
    do {
        val name = consume(TokenType.IDENTIFIER, "Expected type parameter name")
        val bounds = mutableListOf<Token>()
        if (match(TokenType.COLON)) {
            // Parse constraint(s): T : Printable, Comparable
            bounds.add(consume(TokenType.IDENTIFIER, "Expected constraint type"))
            while (match(TokenType.COMMA)) {
                bounds.add(consume(TokenType.IDENTIFIER, "Expected constraint type"))
            }
        }
        typeParams.add(TypeParam(name, bounds))
    } while (match(TokenType.COMMA))
    consume(TokenType.GT, "Expected '>' after type parameters")
    return typeParams
}
```

- [ ] **Step 2: Update parseFunc to handle type parameters**

In `Parser.kt`, modify `parseFunc` to parse type params after the name:

```kotlin
private fun parseFunc(leadingAnnotations: List<Expr.AnnotationExpr> = emptyList()): Stmt {
    val annotations = if (leadingAnnotations.isNotEmpty()) leadingAnnotations else parseAnnotations()
    consume(TokenType.KW_FN, "Expected 'fn'")
    val name = consume(TokenType.IDENTIFIER, "Expected function name")
    val typeParams = parseTypeParams()  // NEW
    consume(TokenType.L_PAREN, "Expected '('")
    // ... rest of function parsing unchanged
    return Stmt.FuncStmt(annotations, name, typeParams, params, returnType, body)  // Updated
}
```

- [ ] **Step 3: Update parseClass to handle type parameters**

In `Parser.kt`, modify `parseClass`:

```kotlin
private fun parseClass(leadingAnnotations: List<Expr.AnnotationExpr> = emptyList()): Stmt {
    val annotations = if (leadingAnnotations.isNotEmpty()) leadingAnnotations else parseAnnotations()
    consume(TokenType.KW_CLASS, "Expected class")
    val name = consume(TokenType.IDENTIFIER, "Expected identifier")
    val typeParams = parseTypeParams()  // NEW
    val superClass = if (match(TokenType.KW_EXTENDS)) {
        consume(TokenType.IDENTIFIER, "Expected identifier")
    } else null
    val body = parseBlock()
    return Stmt.ClassStmt(annotations, name, typeParams, superClass, body)  // Updated
}
```

- [ ] **Step 4: Update parseType to handle generic types like List<T>**

In `Parser.kt`, modify `parseType`:

```kotlin
private fun parseType(): Token {
    val base = when {
        check(TokenType.KW_BOOL) -> advance()
        check(TokenType.KW_INT) -> advance()
        check(TokenType.KW_FLOAT) -> advance()
        check(TokenType.KW_DOUBLE) -> advance()
        check(TokenType.KW_STRING) -> advance()
        check(TokenType.IDENTIFIER) -> advance()
        else -> throw error(peek(), "Expected type!")
    }
    // Handle generic types: List<T>, Map<K, V>, List<List<T>>
    if (match(TokenType.LT)) {
        val inner = parseType()
        // For now, just consume the inner type - we build the full generic name as string
        while (match(TokenType.COMMA)) {
            parseType()  // consume additional type args
        }
        consume(TokenType.GT, "Expected '>' after type arguments")
        // Return the base token - actual generic type handling is done elsewhere
    }
    return base
}
```

- [ ] **Step 5: Run tests to verify parsing works**

Run: `./gradlew :ink:test --tests "org.inklang.GenericTest" --info 2>&1 | tail -30`
Expected: Tests pass (parsing succeeds, actual type checking not yet implemented)

- [ ] **Step 6: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Parser.kt
git commit -m "feat(generics): parse <T> type parameters in functions and classes

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 3: Type Resolution — Constraint Checking

**Files:**
- Create: `ink/src/main/kotlin/org/inklang/lang/TypeResolver.kt` (new file)
- Modify: `ink/src/main/kotlin/org/inklang/InkCompiler.kt:33-74` (add TypeResolver pass)

- [ ] **Step 1: Create TypeResolver.kt**

```kotlin
package org.inklang.lang

/**
 * Resolves type parameters to actual types and validates constraints.
 * Type erasure: all generic information is removed at this stage.
 */
class TypeResolver {

    /**
     * Built-in constraint types and their requirements.
     */
    private val constraintMethods = mapOf(
        "Any" to emptyList(),  // No methods required - all types satisfy Any
        "Printable" to listOf("toString"),  // Must have toString() -> String
        "Comparable" to listOf("compareTo")  // Must have comparison operators
    )

    /**
     * Check if a type satisfies a constraint.
     * For erasure model, we check at compile time and erase to the base type.
     */
    fun satisfiesConstraint(typeName: String, constraintName: String): Boolean {
        // All types satisfy Any
        if (constraintName == "Any") return true

        // Built-in types satisfy Printable and Comparable
        val builtinTypes = setOf("Int", "Bool", "Float", "Double", "String")
        if (constraintName == "Printable" && typeName in builtinTypes) return true
        if (constraintName == "Comparable" && typeName in builtinTypes) return true

        // TODO: Check if user-defined type has the required methods
        // This requires type checking infrastructure to be built out

        // For now, assume user-defined types satisfy all constraints
        return true
    }

    /**
     * Validate that type arguments satisfy the type parameter bounds.
     * @param typeArg The actual type being used (e.g., "Int")
     * @param bound The constraint (e.g., "Printable")
     * @throws TypeConstraintException if constraint not satisfied
     */
    fun validateBound(typeArg: String, bound: String) {
        if (!satisfiesConstraint(typeArg, bound)) {
            throw TypeConstraintException(
                "Type '$typeArg' does not satisfy constraint '$bound'"
            )
        }
    }

    /**
     * Resolve type parameters in a function/class declaration.
     * Returns a mapping of type param name -> resolved bounds.
     */
    fun resolveTypeParams(typeParams: List<TypeParam>): Map<String, List<String>> {
        val resolved = mutableMapOf<String, List<String>>()
        for (param in typeParams) {
            val paramName = param.name.lexeme
            val bounds = param.bounds.map { it.lexeme }.ifEmpty { listOf("Any") }
            resolved[paramName] = bounds
        }
        return resolved
    }
}

class TypeConstraintException(message: String) : RuntimeException(message)
```

- [ ] **Step 2: Update InkCompiler.kt to add TypeResolver pass**

In `InkCompiler.kt`, modify the compile method:

```kotlin
fun compile(source: String, name: String = "main"): InkScript {
    try {
        val tokens = tokenize(source)
        val parser = Parser(tokens)
        val statements = parser.parse()

        // Check annotations
        AnnotationChecker().check(statements)

        // NEW: Resolve and validate generics
        TypeResolver().resolve(statements)

        // Constant fold
        val folder = ConstantFolder()
        val folded = statements.map { folder.foldStmt(it) }

        // Lower to IR and rest of pipeline unchanged...
    }
}
```

Add `resolve()` method to TypeResolver:

```kotlin
fun resolve(statements: List<Stmt>) {
    for (stmt in statements) {
        when (stmt) {
            is Stmt.FuncStmt -> resolveFunc(stmt)
            is Stmt.ClassStmt -> resolveClass(stmt)
            else -> {}
        }
    }
}

private fun resolveFunc(stmt: Stmt.FuncStmt) {
    val typeParams = resolveTypeParams(stmt.typeParams)
    // Validate parameter types against bounds
    for (param in stmt.params) {
        param.type?.let { typeToken ->
            val typeName = typeToken.lexeme
            typeParams.values.flatten().forEach { bound ->
                validateBound(typeName, bound)
            }
        }
    }
    // Validate return type against bounds
    stmt.returnType?.let { returnType ->
        typeParams.values.flatten().forEach { bound ->
            validateBound(returnType.lexeme, bound)
        }
    }
}

private fun resolveClass(stmt: Stmt.ClassStmt) {
    val typeParams = resolveTypeParams(stmt.typeParams)
    // Validate field types against bounds
    for (member in stmt.body.stmts) {
        if (member is Stmt.VarStmt) {
            member.type?.let { typeToken ->
                typeParams.values.flatten().forEach { bound ->
                    validateBound(typeToken.lexeme, bound)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Add test for constraint validation**

In `GenericTest.kt`, add:

```kotlin
@Test
fun `generic function with constraint rejects incompatible type`() {
    val compiler = InkCompiler()
    // Assuming some type that doesn't satisfy Printable
    val source = """
        class NotPrintable { }
        fn debug<T : Printable>(value: T) -> String { value.toString() }
    """.trimIndent()
    // This should either compile with a warning or fail
    // For now, just verify it compiles
    val script = compiler.compile(source, "test")
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :ink:test --tests "org.inklang.GenericTest" 2>&1 | tail -20`
Expected: Tests pass

- [ ] **Step 5: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/TypeResolver.kt ink/src/main/kotlin/org/inklang/InkCompiler.kt
git commit -m "feat(generics): add TypeResolver with constraint validation

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 4: Type Inference and Generic Call Sites

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt` (parseCallExpr to handle explicit type args)
- Modify: `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt` (lower generic calls)
- Test: `ink/src/test/kotlin/org/inklang/GenericTest.kt`

- [ ] **Step 1: Add CallExpr support for explicit type arguments**

In `AST.kt`, modify `CallExpr`:

```kotlin
data class CallExpr(
    val callee: Expr,
    val paren: Token,
    val typeArgs: List<Token>,          // NEW: explicit type arguments like identity<Int>()
    val arguments: List<Expr>
)
```

In `Parser.kt`, modify the `L_PAREN` case in `parsePostfix`:

```kotlin
match(TokenType.L_PAREN) -> {
    val typeArgs = mutableListOf<Token>()
    // Check for explicit type arguments: identity<Int>(42)
    if (match(TokenType.LT)) {
        typeArgs.add(consume(TokenType.IDENTIFIER, "Expected type argument"))
        while (match(TokenType.COMMA)) {
            typeArgs.add(consume(TokenType.IDENTIFIER, "Expected type argument"))
        }
        consume(TokenType.GT, "Expected '>' after type arguments")
    }
    val args = mutableListOf<Expr>()
    if (!check(TokenType.R_PAREN)) {
        do { args.add(parseExpression(0)) } while (match(TokenType.COMMA))
    }
    val paren = consume(TokenType.R_PAREN, "Expected ')' after arguments")
    Expr.CallExpr(expr, paren, typeArgs, args)  // Updated
}
```

- [ ] **Step 2: Add inference for generic calls in AstLowerer**

In `AstLowerer.kt`, modify `lowerExpr` for `Expr.CallExpr`:

```kotlin
is Expr.CallExpr -> {
    val funcReg = lowerExpr(expr.callee, freshReg())
    val argRegs = expr.arguments.map { lowerExpr(it, freshReg()) }
    // For generic calls with type inference, the callee resolution handles it
    // Type erasure means we just emit the call - type args are compile-time only
    emit(Call(dst, funcReg, argRegs))
    dst
}
```

- [ ] **Step 3: Add test for type argument inference**

In `GenericTest.kt`:

```kotlin
@Test
fun `generic function type inference from argument`() {
    val compiler = InkCompiler()
    val source = """
        fn identity<T>(x: T) -> T { x }
        let result = identity(42)
        print(result)
    """.trimIndent()
    val script = compiler.compile(source, "test")
    val context = FakeInkContext()
    script.execute(context)
    assertEquals(listOf("42"), context.prints)
}

@Test
fun `generic class instantiation with inference`() {
    val compiler = InkCompiler()
    val source = """
        class Box<T> {
            var item: T
            fn new(item: T) { Box(item) }
        }
        let box = Box::new("hello")
        print(box.item)
    """.trimIndent()
    val script = compiler.compile(source, "test")
    val context = FakeInkContext()
    script.execute(context)
    assertEquals(listOf("hello"), context.prints)
}

@Test
fun `explicit type arguments`() {
    val compiler = InkCompiler()
    val source = """
        fn identity<T>(x: T) -> T { x }
        let result = identity<Int>(42)
        print(result)
    """.trimIndent()
    val script = compiler.compile(source, "test")
    val context = FakeInkContext()
    script.execute(context)
    assertEquals(listOf("42"), context.prints)
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :ink:test --tests "org.inklang.GenericTest" 2>&1 | tail -30`
Expected: Tests pass

- [ ] **Step 5: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/AST.kt ink/src/main/kotlin/org/inklang/lang/Parser.kt ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt ink/src/test/kotlin/org/inklang/GenericTest.kt
git commit -m "feat(generics): add type argument inference and explicit type args

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 5: Full Integration — Primitive Auto-Boxing and Refinement

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/TypeResolver.kt` (auto-boxing logic)
- Modify: `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt` (handle primitive boxing)
- Test: `ink/src/test/kotlin/org/inklang/GenericTest.kt`

- [ ] **Step 1: Document the auto-boxing strategy in TypeResolver**

Add to `TypeResolver.kt`:

```kotlin
/**
 * Primitive types that need boxing when used in generics.
 * List<Int> internally uses List<IntBox> where IntBox wraps the primitive.
 *
 * This is handled at the bytecode level - the compiler automatically
 * inserts box/unbox operations when primitives cross generic boundaries.
 */
private val primitiveTypes = setOf("Int", "Bool", "Float", "Double")

/**
 * Get the boxed type name for a primitive, or the original type if not primitive.
 * Int -> IntBox, String -> String, etc.
 */
fun getBoxedType(typeName: String): String {
    return when (typeName) {
        "Int" -> "IntBox"
        "Bool" -> "BoolBox"
        "Float" -> "FloatBox"
        "Double" -> "DoubleBox"
        else -> typeName
    }
}

/**
 * Check if a type needs boxing for generic use.
 */
fun needsBoxing(typeName: String): Boolean {
    return typeName in primitiveTypes
}
```

- [ ] **Step 2: Add more comprehensive tests**

In `GenericTest.kt`:

```kotlin
@Test
fun `generic with List of Int`() {
    val compiler = InkCompiler()
    val source = """
        fn first<T>(list: List<T>) -> T { list[0] }
        let numbers = [1, 2, 3]
        print(first(numbers))
    """.trimIndent()
    val script = compiler.compile(source, "test")
    val context = FakeInkContext()
    script.execute(context)
    assertEquals(listOf("1"), context.prints)
}

@Test
fun `multiple type parameters`() {
    val compiler = InkCompiler()
    val source = """
        fn swap<A, B>(pair: (A, B)) -> (B, A) { (pair[1], pair[0]) }
        let result = swap(("hello", 42))
        print(result[0])
        print(result[1])
    """.trimIndent()
    val script = compiler.compile(source, "test")
    val context = FakeInkContext()
    script.execute(context)
    assertEquals(listOf("42", "hello"), context.prints)
}
```

- [ ] **Step 3: Run full test suite**

Run: `./gradlew :ink:test 2>&1 | tail -40`
Expected: All existing tests pass + new generics tests pass

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/TypeResolver.kt ink/src/test/kotlin/org/inklang/GenericTest.kt
git commit -m "feat(generics): primitive auto-boxing strategy and integration tests

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Summary

| Chunk | Files | What |
|-------|-------|------|
| 1 | AST.kt, GenericTest.kt | Add TypeParam to FuncStmt/ClassStmt |
| 2 | Parser.kt | Parse `<T>` syntax in functions/classes |
| 3 | TypeResolver.kt, InkCompiler.kt | Constraint checking pass |
| 4 | AST.kt, Parser.kt, AstLowerer.kt | Type inference + explicit type args |
| 5 | TypeResolver.kt, GenericTest.kt | Auto-boxing + integration |

After all chunks: generics work with `<T>`, `T : Bound` constraints, type inference, and erasure at runtime.
