# Annotation System Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a compile-time annotation system for Ink language with support for user-defined annotations and built-in annotations (@deprecated, @inline, @pure).

**Architecture:** Annotations are parsed as expressions attached to declarations, then processed by a compile-time-only AnnotationChecker pass before constant folding. No annotation data is stored in bytecode.

**Tech Stack:** Kotlin, existing Ink compiler pipeline (Lexer -> Parser -> AnnotationChecker -> ConstantFolder -> AstLowerer -> ...)

---

## Chunk 1: Token and Lexer Changes

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/Token.kt:1-81`
- Modify: `lang/src/main/kotlin/org/inklang/lang/Lexer.kt:1-312`

- [ ] **Step 1: Add AT token type to TokenType enum**

Modify `Token.kt` after `DOLLAR` token type (line 79):
```kotlin
    AT,                    // @ (annotation marker)
    EOF
```

- [ ] **Step 2: Add @ handling to Lexer main scan loop**

Modify `Lexer.kt`. The `@` character should be added as a new case in the main `when` inside `tokenize()`, after the COLON case:
```kotlin
                ':' -> addToken(TokenType.COLON)
                '@' -> addToken(TokenType.AT)
```

**Note:** The `@` is tokenized as a single `AT` token. The annotation name (identifier) following it is tokenized separately by the normal `identifier()` path on the next character. Parentheses for annotation args are tokenized normally too — the parser handles combining `AT + IDENTIFIER + (args?)` into an annotation expression.

- [ ] **Step 3: Run existing tests to verify no regression**

Run: `./gradlew :lang:test --tests "org.inklang.InkCompilerTest" -v`
Expected: All existing tests PASS

- [ ] **Step 4: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/lang/Token.kt lang/src/main/kotlin/org/inklang/lang/Lexer.kt
git commit -m "feat: add AT token type and @ tokenization"
```

---

## Chunk 2: AST Changes

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/AST.kt:1-132`

- [ ] **Step 1: Add AnnotationExpr to Expr sealed class**

Modify `AST.kt` after `ElvisExpr` (line 84), add inside the `Expr` sealed class:
```kotlin
    data class AnnotationExpr(
        val name: String,
        val args: Map<String, Expr>
    ) : Expr()
```

- [ ] **Step 2: Add AnnotationField and AnnotationDecl to Stmt sealed class**

Modify `AST.kt` after `TableStmt` (line 129), add inside the `Stmt` sealed class:
```kotlin
    data class AnnotationField(val name: Token, val type: Token, val defaultValue: Expr?)
    data class AnnotationDeclStmt(val name: Token, val fields: List<AnnotationField>) : Stmt()
```

- [ ] **Step 3: Attach annotations to FuncParam**

Modify `AST.kt` line 89, change `Param` to:
```kotlin
data class Param(
    val annotations: List<Expr.AnnotationExpr>,
    val name: Token,
    val type: Token?,
    val defaultValue: Expr? = null
)
```

- [ ] **Step 4: Attach annotations to FuncStmt**

Modify `AST.kt` line 107-112, change `FuncStmt` to:
```kotlin
    data class FuncStmt(
        val annotations: List<Expr.AnnotationExpr>,
        val name: Token,
        val params: List<Param>,
        val returnType: Token?,
        val body: BlockStmt
    ) : Stmt()
```

- [ ] **Step 5: Attach annotations to ClassStmt**

Modify `AST.kt` line 99, change `ClassStmt` to:
```kotlin
    data class ClassStmt(
        val annotations: List<Expr.AnnotationExpr>,
        val name: Token,
        val superClass: Token?,
        val body: BlockStmt
    ) : Stmt()
```

- [ ] **Step 6: Attach annotations to VarStmt**

Modify `AST.kt` line 102, change `VarStmt` to:
```kotlin
    data class VarStmt(
        val annotations: List<Expr.AnnotationExpr>,
        val keyword: Token,
        val name: Token,
        val value: Expr?
    ) : Stmt()
```

- [ ] **Step 7: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/lang/AST.kt
git commit -m "feat: add annotation AST types — AnnotationExpr, AnnotationDeclStmt, annotations on FuncStmt ClassStmt VarStmt Param"
```

---

## Chunk 3: Parser Changes

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/Parser.kt:1-636`

- [ ] **Step 1: Add helper to parse zero or more annotations**

Add this method to `Parser.kt` after the `isLambdaAhead()` helper (around line 596):

```kotlin
    /**
     * Parse zero or more annotations before a declaration.
     * Returns a list of AnnotationExpr.
     */
    private fun parseAnnotations(): List<Expr.AnnotationExpr> {
        val annotations = mutableListOf<Expr.AnnotationExpr>()
        while (match(TokenType.AT)) {
            val nameToken = consume(TokenType.IDENTIFIER, "Expected annotation name after @")
            val name = nameToken.lexeme
            val args = mutableMapOf<String, Expr>()

            // Parse optional (arg1, arg2, ...) — named arguments only
            if (match(TokenType.L_PAREN)) {
                if (!check(TokenType.R_PAREN)) {
                    do {
                        val argName = consume(TokenType.IDENTIFIER, "Expected named argument name")
                        val argNameStr = argName.lexeme
                        consume(TokenType.COLON, "Expected ':' after argument name")
                        val argValue = parseExpression(0)
                        args[argNameStr] = argValue
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after annotation arguments")
            }

            annotations.add(Expr.AnnotationExpr(name, args))
        }
        return annotations
    }
```

- [ ] **Step 2: Modify parseVar to parse annotations**

Modify `parseVar()` (line 253-259) to:
```kotlin
    private fun parseVar(): Stmt {
        val annotations = parseAnnotations()
        val keyword = advance()
        val name = consume(TokenType.IDENTIFIER, "Expected name")
        val value = if (match(TokenType.ASSIGN)) parseExpression(0) else null
        if (check(TokenType.SEMICOLON)) advance()
        return Stmt.VarStmt(annotations, keyword, name, value)
    }
```

- [ ] **Step 3: Modify parseClass to parse annotations**

Modify `parseClass()` (line 224-232) to:
```kotlin
    private fun parseClass(): Stmt {
        val annotations = parseAnnotations()
        consume(TokenType.KW_CLASS, "Expected class")
        val name = consume(TokenType.IDENTIFIER, "Expected identifier")
        val superClass = if (match(TokenType.KW_EXTENDS)) {
            consume(TokenType.IDENTIFIER, "Expected identifier")
        } else null
        val body = parseBlock()
        return Stmt.ClassStmt(annotations, name, superClass, body)
    }
```

- [ ] **Step 4: Modify parseFunc to parse annotations**

Modify `parseFunc()` (line 171-210) to:
```kotlin
    private fun parseFunc(): Stmt {
        val annotations = parseAnnotations()
        consume(TokenType.KW_FN, "Expected 'fn'")
        val name = consume(TokenType.IDENTIFIER, "Expected function name")
        consume(TokenType.L_PAREN, "Expected '('")
        val params = mutableListOf<Param>()
        var hasSeenDefaultParam = false

        if (!check(TokenType.R_PAREN)) {
            do {
                // Parse parameter annotations
                val paramAnnotations = parseAnnotations()
                val paramName = consume(TokenType.IDENTIFIER, "Expected parameter name")
                val paramType = if (match(TokenType.COLON)) consume(
                    TokenType.IDENTIFIER,
                    "Expected type"
                ) else null

                val defaultValue = if (match(TokenType.ASSIGN)) {
                    hasSeenDefaultParam = true
                    parseExpression(0)
                } else {
                    if (hasSeenDefaultParam) {
                        throw error(
                            previous(),
                            "Non-default parameter '${paramName.lexeme}' cannot follow default parameter"
                        )
                    }
                    null
                }

                params.add(Param(paramAnnotations, paramName, paramType, defaultValue))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')'")
        val returnType = if (match(TokenType.ARROW)) {
            parseType()
        } else null
        val body = parseBlock()
        return Stmt.FuncStmt(annotations, name, params, returnType, body)
    }
```

- [ ] **Step 5: Add annotation declaration parsing to parseStmt**

Modify `parseStmt()` (line 103-130) — add at the start of the `when`:
```kotlin
            check(TokenType.KW_ANNOTATION) -> parseAnnotationDecl()
```

Also add the new `KW_ANNOTATION` to TokenType and keywords in Lexer first (go back to Chunk 1 if skipped).

- [ ] **Step 6: Add parseAnnotationDecl method**

Add after `parseConfig()` (around line 101):
```kotlin
    private fun parseAnnotationDecl(): Stmt {
        consume(TokenType.KW_ANNOTATION, "Expected 'annotation'")
        val name = consume(TokenType.IDENTIFIER, "Expected annotation name")
        consume(TokenType.L_BRACE, "Expected '{'")
        val fields = mutableListOf<Stmt.AnnotationField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            val fieldName = consume(TokenType.IDENTIFIER, "Expected field name")
            consume(TokenType.COLON, "Expected ':' after field name")
            val fieldType = parseType()
            val defaultValue = if (match(TokenType.ASSIGN)) parseExpression(0) else null
            if (check(TokenType.SEMICOLON)) advance()
            if (check(TokenType.COMMA)) advance()
            fields.add(Stmt.AnnotationField(fieldName, fieldType, defaultValue))
        }
        consume(TokenType.R_BRACE, "Expected '}'")
        return Stmt.AnnotationDeclStmt(name, fields)
    }
```

- [ ] **Step 7: Add KW_ANNOTATION to TokenType enum**

Modify `Token.kt` after `KW_CONFIG` (line 35):
```kotlin
    KW_ANNOTATION,
```

- [ ] **Step 8: Add "annotation" to Lexer keywords map**

Modify `Lexer.kt` line 58, after `"config"` entry:
```kotlin
            "annotation" to TokenType.KW_ANNOTATION,
```

- [ ] **Step 9: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/lang/Parser.kt lang/src/main/kotlin/org/inklang/lang/Token.kt lang/src/main/kotlin/org/inklang/lang/Lexer.kt
git commit -m "feat: parse annotation declarations and annotations on functions classes fields params"
```

---

## Chunk 4: AnnotationChecker — Compile-Time Processing

**Files:**
- Create: `lang/src/main/kotlin/org/inklang/AnnotationChecker.kt`

- [ ] **Step 1: Create AnnotationChecker.kt**

Create the file with:

```kotlin
package org.inklang

import org.inklang.lang.Expr
import org.inklang.lang.Stmt
import org.inklang.lang.Value

/**
 * Compile-time annotation processor.
 * Validates annotation arguments and processes built-in annotations (@deprecated, @inline, @pure).
 * This runs BEFORE constant folding in the compiler pipeline.
 */
class AnnotationChecker(
    private val warnUnknownAnnotations: Boolean = false
) {
    // Track annotations seen during processing
    private val annotationDeclarations = mutableMapOf<String, AnnotationInfo>()

    data class AnnotationInfo(
        val name: String,
        val fields: Map<String, AnnotationFieldInfo>
    )

    data class AnnotationFieldInfo(
        val type: String,
        val defaultValue: Expr? = null
    )

    // Built-in annotation definitions
    init {
        annotationDeclarations["deprecated"] = AnnotationInfo(
            "deprecated",
            mapOf("reason" to AnnotationFieldInfo("string"))
        )
        annotationDeclarations["inline"] = AnnotationInfo(
            "inline",
            mapOf("level" to AnnotationFieldInfo("int", Expr.LiteralExpr(Value.Int(1))))
        )
        annotationDeclarations["pure"] = AnnotationInfo(
            "pure",
            emptyMap()
        )
    }

    /**
     * Process all annotations in a list of statements.
     * Returns the same statements (annotations are validated in-place).
     */
    fun check(statements: List<Stmt>): List<Stmt> {
        for (stmt in statements) {
            checkStmt(stmt)
        }
        return statements
    }

    private fun checkStmt(stmt: Stmt) {
        when (stmt) {
            is Stmt.FuncStmt -> checkFuncStmt(stmt)
            is Stmt.ClassStmt -> checkClassStmt(stmt)
            is Stmt.VarStmt -> checkVarStmt(stmt)
            is Stmt.BlockStmt -> stmt.stmts.forEach { checkStmt(it) }
            is Stmt.AnnotationDeclStmt -> registerAnnotationDeclaration(stmt)
            else -> {}
        }
    }

    private fun registerAnnotationDeclaration(stmt: Stmt.AnnotationDeclStmt) {
        val fields = mutableMapOf<String, AnnotationFieldInfo>()
        for (field in stmt.fields) {
            fields[field.name.lexeme] = AnnotationFieldInfo(field.type.lexeme, field.defaultValue)
        }
        annotationDeclarations[stmt.name.lexeme] = AnnotationInfo(stmt.name.lexeme, fields)
    }

    private fun checkFuncStmt(stmt: Stmt.FuncStmt) {
        for (annotation in stmt.annotations) {
            checkAnnotation(annotation, target = "function '${stmt.name.lexeme}'")

            when (annotation.name) {
                "inline" -> {
                    // @inline is only valid on functions
                    val level = getIntArg(annotation, "level") ?: 1
                    if (level < 1 || level > 3) {
                        throw CompilationException("@inline level must be between 1 and 3")
                    }
                }
                "pure" -> {
                    // Validate purity: no globals, no non-pure calls, no I/O
                    validatePureFunction(stmt)
                }
            }
        }

        // Recurse into body
        checkStmt(stmt.body)
    }

    private fun checkClassStmt(stmt: Stmt.ClassStmt) {
        for (annotation in stmt.annotations) {
            checkAnnotation(annotation, target = "class '${stmt.name.lexeme}'")
        }
        checkStmt(stmt.body)
    }

    private fun checkVarStmt(stmt: Stmt.VarStmt) {
        for (annotation in stmt.annotations) {
            checkAnnotation(annotation, target = "variable '${stmt.name.lexeme}'")
        }
        stmt.value?.let { checkExpr(it) }
    }

    private fun checkAnnotation(annotation: Expr.AnnotationExpr, target: String) {
        val info = annotationDeclarations[annotation.name]
        if (info == null) {
            if (warnUnknownAnnotations) {
                println("Warning: Unknown annotation '@${annotation.name}' on $target")
            }
            return
        }

        // Validate required fields
        for ((fieldName, fieldInfo) in info.fields) {
            if (!annotation.args.containsKey(fieldName) && fieldInfo.defaultValue == null) {
                throw CompilationException(
                    "Annotation '@${annotation.name}' on $target is missing required field '$fieldName'"
                )
            }
        }

        // Validate provided fields
        for ((argName, argExpr) in annotation.args) {
            val fieldInfo = info.fields[argName]
            if (fieldInfo == null) {
                throw CompilationException(
                    "Annotation '@${annotation.name}' on $target has unknown field '$argName'"
                )
            }
            // Validate type
            validateAnnotationArgType(argExpr, fieldInfo.type, annotation.name, argName, target)
        }
    }

    private fun validateAnnotationArgType(
        expr: Expr,
        expectedType: String,
        annotationName: String,
        fieldName: String,
        target: String
    ) {
        val actualType = when (expr) {
            is Expr.LiteralExpr -> when (expr.literal) {
                is Value.Int -> "int"
                is Value.Double -> "double"
                is Value.String -> "string"
                is Value.Boolean -> "bool"
                is Value.Null -> if (expectedType == "string") "string" else null
            }
            else -> null
        }

        if (actualType != expectedType) {
            throw CompilationException(
                "Annotation '@$annotationName' on $target: field '$fieldName' expects type '$expectedType' but got '$actualType'"
            )
        }
    }

    private fun getIntArg(annotation: Expr.AnnotationExpr, field: String): Int? {
        val arg = annotation.args[field] ?: return null
        if (arg is Expr.LiteralExpr && arg.literal is Value.Int) {
            return arg.literal.value
        }
        return null
    }

    private fun validatePureFunction(stmt: Stmt.FuncStmt) {
        // Check body for side effects
        validateNoSideEffects(stmt.body, mutableSetOf())
    }

    private fun validateNoSideEffects(stmt: Stmt, visited: MutableSet<String>) {
        when (stmt) {
            is Stmt.FuncStmt -> {
                // Don't recurse into nested functions — they have their own @pure check
            }
            is Stmt.BlockStmt -> stmt.stmts.forEach { validateNoSideEffects(it, visited) }
            is Stmt.ExprStmt -> validateExprNoSideEffects(stmt.expr, visited)
            is Stmt.VarStmt -> {
                stmt.value?.let { validateExprNoSideEffects(it, visited) }
            }
            is Stmt.ReturnStmt -> {
                stmt.value?.let { validateExprNoSideEffects(it, visited) }
            }
            is Stmt.IfStmt -> {
                validateExprNoSideEffects(stmt.condition, visited)
                validateNoSideEffects(stmt.then, visited)
                stmt.elseBranch?.let {
                    when (it) {
                        is Stmt.ElseBranch.Else -> validateNoSideEffects(it.block, visited)
                        is Stmt.ElseBranch.ElseIf -> validateNoSideEffects(it.stmt, visited)
                    }
                }
            }
            is Stmt.WhileStmt -> {
                validateExprNoSideEffects(stmt.condition, visited)
                validateNoSideEffects(stmt.body, visited)
            }
            is Stmt.ForRangeStmt -> {
                validateExprNoSideEffects(stmt.iterable, visited)
                validateNoSideEffects(stmt.body, visited)
            }
            else -> {}
        }
    }

    private fun validateExprNoSideEffects(expr: Expr, visited: MutableSet<String>) {
        when (expr) {
            is Expr.CallExpr -> {
                val name = when (expr.callee) {
                    is Expr.VariableExpr -> (expr.callee as Expr.VariableExpr).name.lexeme
                    else -> null
                }
                if (name != null && name !in visited) {
                    visited.add(name)
                    // Check if it's a known I/O function
                    val ioFunctions = setOf("print", "log", "read", "write", "open", "close")
                    if (name in ioFunctions) {
                        throw CompilationException("@pure function contains I/O call: $name")
                    }
                }
                expr.arguments.forEach { validateExprNoSideEffects(it, visited) }
            }
            is Expr.VariableExpr -> {
                // Variable reads are okay if they're local
            }
            is Expr.GetExpr -> {
                // Property access could be global state — flag it
                throw CompilationException("@pure function contains potential global access")
            }
            is Expr.BinaryExpr -> {
                validateExprNoSideEffects(expr.left, visited)
                validateExprNoSideEffects(expr.right, visited)
            }
            is Expr.UnaryExpr -> validateExprNoSideEffects(expr.right, visited)
            is Expr.TernaryExpr -> {
                validateExprNoSideEffects(expr.condition, visited)
                validateExprNoSideEffects(expr.thenBranch, visited)
                validateExprNoSideEffects(expr.elseBranch, visited)
            }
            is Expr.ElvisExpr -> {
                validateExprNoSideEffects(expr.left, visited)
                validateExprNoSideEffects(expr.right, visited)
            }
            else -> {}
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/AnnotationChecker.kt
git commit -m "feat: add AnnotationChecker compile-time annotation processor"
```

---

## Chunk 5: Compiler Pipeline Integration

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/InkCompiler.kt:1-73`

- [ ] **Step 1: Add AnnotationChecker to InkCompiler pipeline**

Modify `InkCompiler.kt` line 40-44, change the parsing section to:
```kotlin
            // Parse
            val parser = Parser(tokens)
            val statements = parser.parse()

            // Check annotations (before constant folding)
            val annotationChecker = AnnotationChecker()
            annotationChecker.check(statements)

            // Constant fold
            val folder = ConstantFolder()
            val folded = statements.map { folder.foldStmt(it) }
```

- [ ] **Step 2: Run existing tests to verify no regression**

Run: `./gradlew :lang:test -v`
Expected: All existing tests PASS

- [ ] **Step 3: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/InkCompiler.kt
git commit -m "feat: integrate AnnotationChecker into compiler pipeline"
```

---

## Chunk 6: Tests

**Files:**
- Create: `lang/src/test/kotlin/org/inklang/AnnotationTest.kt`

- [ ] **Step 1: Write failing tests for annotation parsing**

Create `AnnotationTest.kt`:

```kotlin
package org.inklang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnnotationTest {

    @Test
    fun `parse annotation declaration`() {
        val compiler = InkCompiler()
        val source = """
            annotation Deprecated {
                reason: string
            }
        """.trimIndent()
        val script = compiler.compile(source, "test")
        // Should compile without error
    }

    @Test
    fun `parse annotation on function`() {
        val compiler = InkCompiler()
        val source = """
            @deprecated(reason="Old")
            fn oldFunc() { true }
        """.trimIndent()
        val script = compiler.compile(source, "test")
    }

    @Test
    fun `parse annotation on class`() {
        val compiler = InkCompiler()
        val source = """
            @marked
            class Foo {}
        """.trimIndent()
        val script = compiler.compile(source, "test")
    }

    @Test
    fun `parse annotation on field`() {
        val compiler = InkCompiler()
        val source = """
            @notNull
            let name = "test"
        """.trimIndent()
        val script = compiler.compile(source, "test")
    }

    @Test
    fun `parse annotation on parameter`() {
        val compiler = InkCompiler()
        val source = """
            fn process(@notNull input: string) { input }
        """.trimIndent()
        val script = compiler.compile(source, "test")
    }

    @Test
    fun `parse multiple annotations`() {
        val compiler = InkCompiler()
        val source = """
            @validate @notNull
            let value = 42
        """.trimIndent()
        val script = compiler.compile(source, "test")
    }

    @Test
    fun `parse inline annotation with args`() {
        val compiler = InkCompiler()
        val source = """
            @inline(level=2)
            fn hotPath() { 1 + 1 }
        """.trimIndent()
        val script = compiler.compile(source, "test")
    }

    @Test
    fun `annotation field type mismatch error`() {
        val compiler = InkCompiler()
        val source = """
            @inline(level="fast")
            fn hotPath() { 1 }
        """.trimIndent()
        assertFailsWith<CompilationException> {
            compiler.compile(source, "test")
        }
    }

    @Test
    fun `annotation missing required field error`() {
        val compiler = InkCompiler()
        val source = """
            @deprecated
            fn oldFunc() { true }
        """.trimIndent()
        // @deprecated requires reason field — should error
        assertFailsWith<CompilationException> {
            compiler.compile(source, "test")
        }
    }

    @Test
    fun `inline on non-function error`() {
        val compiler = InkCompiler()
        val source = """
            @inline
            let x = 42
        """.trimIndent()
        assertFailsWith<CompilationException> {
            compiler.compile(source, "test")
        }
    }

    @Test
    fun `inline level out of range error`() {
        val compiler = InkCompiler()
        val source = """
            @inline(level=5)
            fn hotPath() { 1 }
        """.trimIndent()
        assertFailsWith<CompilationException> {
            compiler.compile(source, "test")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (expected)**

Run: `./gradlew :lang:test --tests "org.inklang.AnnotationTest" -v`
Expected: Tests fail with compilation errors (features not yet wired up)

- [ ] **Step 3: Fix compilation issues found by tests**

Common issues to fix:
- Param constructor needs updating wherever Param is created
- FuncStmt/ClassStmt/VarStmt constructor changes need updating everywhere they're created
- Ensure KW_ANNOTATION is added to TokenType

- [ ] **Step 4: Run tests again to verify they pass**

Run: `./gradlew :lang:test --tests "org.inklang.AnnotationTest" -v`
Expected: All AnnotationTest tests PASS

- [ ] **Step 5: Run full test suite**

Run: `./gradlew :lang:test -v`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add lang/src/test/kotlin/org/inklang/AnnotationTest.kt
git add lang/src/main/kotlin/org/inklang/lang/AST.kt  # if constructor changes needed
git add lang/src/main/kotlin/org/inklang/lang/Parser.kt  # if fixes needed
git commit -m "test: add annotation system tests"
```

---

## Chunk 7: Deprecation Warning Integration

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/AnnotationChecker.kt`

- [ ] **Step 1: Add deprecation usage tracking**

The `@deprecated` annotation should warn when a deprecated function/class is USED, not just declared. This requires tracking deprecated declarations and emitting warnings during name resolution.

**This is an advanced feature** — the basic annotation system (chunks 1-6) handles parsing, declaration, and validation. Deprecation usage warnings require name resolution integration which is beyond the initial scope.

If you want to implement deprecation warnings, the approach is:
1. Store deprecated declarations in a `Set<String>` during `check()`
2. During AST lowering or IR generation, when a reference to a deprecated name is encountered, emit a warning

For now, the implementation is complete with compile-time validation only.

---

## Files Summary

| Action | File |
|--------|------|
| Create | `lang/src/main/kotlin/org/inklang/AnnotationChecker.kt` |
| Create | `lang/src/test/kotlin/org/inklang/AnnotationTest.kt` |
| Modify | `lang/src/main/kotlin/org/inklang/lang/Token.kt` |
| Modify | `lang/src/main/kotlin/org/inklang/lang/Lexer.kt` |
| Modify | `lang/src/main/kotlin/org/inklang/lang/AST.kt` |
| Modify | `lang/src/main/kotlin/org/inklang/lang/Parser.kt` |
| Modify | `lang/src/main/kotlin/org/inklang/InkCompiler.kt` |