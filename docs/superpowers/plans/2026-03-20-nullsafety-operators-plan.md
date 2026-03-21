# Null Safety Operators Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `?.` safe call and `??` elvis null safety operators to the Ink language.

**Architecture:** Operators are parsed into new AST nodes (SafeCallExpr, ElvisExpr) and desugared to existing IR instructions (conditionals, jumps, null checks) in the AstLowerer. No VM changes needed.

**Tech Stack:** Kotlin, Gradle, Ink language compiler

---

## Chunk 1: Token and Lexer Changes

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/Token.kt`
- Modify: `lang/src/main/kotlin/org/inklang/lang/Lexer.kt`

### Task 1: Add QUESTION_QUESTION token type

- [ ] **Step 1: Add token type**

Modify `Token.kt` line 74 (after QUESTION):

```kotlin
QUESTION,             // ? (ternary operator)
QUESTION_QUESTION,    // ?? (elvis operator)
```

---

### Task 2: Add ?? recognition in Lexer

- [ ] **Step 1: Update ? handling in Lexer**

Modify `Lexer.kt` line 121 (the `?` handling):

```kotlin
'?' -> when {
    match('?') -> addToken(TokenType.QUESTION_QUESTION)
    else -> addToken(TokenType.QUESTION)
}
```

---

## Chunk 2: AST Changes

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/AST.kt`

### Task 3: Add SafeCallExpr and ElvisExpr to AST

- [ ] **Step 1: Add new expression types to Expr sealed class**

Add after `Expr.HasExpr` (line 80):

```kotlin
data class SafeCallExpr(val obj: Expr, val name: Token) : Expr()

data class ElvisExpr(val left: Expr, val right: Expr) : Expr()
```

---

## Chunk 3: Parser Changes

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/Parser.kt`

### Task 4: Add elvis operator precedence

- [ ] **Step 1: Add elvis weight to Parser.companion.weights**

Modify `Parser.kt` around line 32. Add after QUESTION weight 15:

```kotlin
TokenType.QUESTION_QUESTION to 15,  // elvis operator, same precedence as ternary
```

### Task 5: Handle safe call (?.) in parsePostfix

- [ ] **Step 1: Update DOT handling in parsePostfix**

Modify `Parser.kt` around line 343-351. Change the DOT case:

```kotlin
match(TokenType.DOT) -> {
    // After a dot, allow IDENTIFIER or keywords that can be used as method names
    // Check for safe call (?.)
    if (match(TokenType.QUESTION)) {
        // Safe call: obj?.name
        val name = when {
            match(TokenType.IDENTIFIER) -> previous()
            match(TokenType.KW_HAS) -> previous()  // has can be used as method name
            match(TokenType.KW_IS) -> previous()    // is can be used as method name
            else -> throw error(peek(), "Expected field name after '?.'")
        }
        Expr.SafeCallExpr(expr, name)
    } else {
        // Regular property access: obj.name
        val name = when {
            match(TokenType.IDENTIFIER) -> previous()
            match(TokenType.KW_HAS) -> previous()  // has can be used as method name
            match(TokenType.KW_IS) -> previous()    // is can be used as method name
            else -> throw error(peek(), "Expected field name after '.'")
        }
        Expr.GetExpr(expr, name)
    }
}
```

### Task 6: Handle elvis operator (??) in parseExpression

- [ ] **Step 1: Add elvis handling in parseExpression**

Add after the ternary handling (around line 319):

```kotlin
// Elvis: left ?? right
if (token.type == TokenType.QUESTION_QUESTION) {
    advance()  // consume ??
    val right = parseExpression(15)  // same precedence as ternary
    left = Expr.ElvisExpr(left, right)
    continue
}
```

---

## Chunk 4: AST Lowerer Changes

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt`

### Task 7: Lower SafeCallExpr

- [ ] **Step 1: Add SafeCallExpr case in lowerExpr**

Add after the `Expr.HasExpr` case (around line 504):

```kotlin
is Expr.SafeCallExpr -> {
    // obj?.name desugars to:
    // temp = obj
    // if (temp == null) goto null_label
    // result = temp.name
    // goto end_label
    // null_label:
    // result = null
    // end_label:
    val nullLabel = freshLabel()
    val endLabel = freshLabel()
    val tempReg = freshReg()
    lowerExpr(expr.obj, tempReg)
    // Check if null: compare tempReg to null constant
    val nullConstIdx = addConstant(Value.Null)
    val nullCheckReg = freshReg()
    emit(BinaryOp(nullCheckReg, TokenType.BANG_EQ, tempReg, freshReg()))
    emit(LoadImm(freshReg(), nullConstIdx))
    emit(JumpIfFalse(nullCheckReg, nullLabel))
    // Not null: get the field
    lowerExpr(Expr.GetExpr(Expr.VariableExpr(Token("IDENTIFIER", "_", 0, 0)), expr.name), tempReg) // use tempReg as obj
    emit(Jump(endLabel))
    emit(Label(nullLabel))
    // Null: load null
    emit(LoadImm(tempReg, nullConstIdx))
    emit(Label(endLabel))
    dst
}
```

Wait - that's not quite right. Let me fix the approach. The SafeCallExpr needs to:
1. Evaluate obj into a temp
2. Check if null - if so jump to null path
3. Otherwise do field access
4. Merge at end

Better approach:

```kotlin
is Expr.SafeCallExpr -> {
    // obj?.name desugars to:
    // temp = obj
    // if (temp == null) goto null_label
    // result = temp.name
    // goto end_label
    // null_label:
    // result = null
    // end_label:
    val nullLabel = freshLabel()
    val endLabel = freshLabel()
    val objReg = freshReg()
    lowerExpr(expr.obj, objReg)
    // Check if null
    val nullConstIdx = addConstant(Value.Null)
    val cmpReg = freshReg()
    emit(BinaryOp(cmpReg, TokenType.BANG_EQ, objReg, objReg)) // compare objReg to itself - wait this is wrong
```

Actually I need to compare to null. Let me use a proper comparison:

```kotlin
is Expr.SafeCallExpr -> {
    // obj?.name desugars to:
    // temp = obj
    // if (temp == null) goto null_label
    // result = temp.name
    // goto end_label
    // null_label:
    // result = null
    // end_label:
    val nullLabel = freshLabel()
    val endLabel = freshLabel()
    val objReg = freshReg()
    lowerExpr(expr.obj, objReg)
    // Check if null: need to compare to null constant
    val nullConstIdx = addConstant(Value.Null)
    val nullTempReg = freshReg()
    emit(LoadImm(nullTempReg, nullConstIdx))
    val cmpReg = freshReg()
    emit(BinaryOp(cmpReg, TokenType.BANG_EQ, objReg, nullTempReg))
    emit(JumpIfFalse(cmpReg, nullLabel))
    // Not null: do field access
    val fieldReg = freshReg()
    emit(IrInstr.GetField(fieldReg, objReg, expr.name.lexeme))
    emit(Move(dst, fieldReg))
    emit(Jump(endLabel))
    emit(Label(nullLabel))
    // Null: load null into dst
    emit(LoadImm(dst, nullConstIdx))
    emit(Label(endLabel))
    dst
}
```

### Task 8: Lower ElvisExpr

- [ ] **Step 1: Add ElvisExpr case in lowerExpr**

Add after the SafeCallExpr case:

```kotlin
is Expr.ElvisExpr -> {
    // left ?? right desugars to:
    // temp = left
    // if (temp != null) goto end_label
    // temp = right
    // end_label:
    // result = temp
    val endLabel = freshLabel()
    val tempReg = freshReg()
    lowerExpr(expr.left, tempReg)
    // Check if not null
    val nullConstIdx = addConstant(Value.Null)
    val nullTempReg = freshReg()
    emit(LoadImm(nullTempReg, nullConstIdx))
    val cmpReg = freshReg()
    emit(BinaryOp(cmpReg, TokenType.BANG_EQ, tempReg, nullTempReg))
    emit(JumpIfFalse(cmpReg, endLabel)) // if null, evaluate right
    // Not null: keep tempReg value, jump to end
    emit(Jump(endLabel))
    emit(Label(endLabel))
    emit(Move(dst, tempReg))
    dst
}
```

---

## Chunk 5: Tests

**Files:**
- Modify: `lang/src/test/kotlin/org/inklang/InkCompilerTest.kt`

### Task 9: Add tests for null safety operators

- [ ] **Step 1: Add test for safe call with null receiver**

Add test after `string interpolation` test (line 167):

```kotlin
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
```

- [ ] **Step 2: Add test for safe call with non-null receiver**

```kotlin
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
```

- [ ] **Step 3: Add test for elvis with null left side**

```kotlin
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
```

- [ ] **Step 4: Add test for elvis with non-null left side**

```kotlin
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
```

- [ ] **Step 5: Add test for chained safe calls**

```kotlin
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
```

- [ ] **Step 6: Add test combining safe call and elvis**

```kotlin
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
```

- [ ] **Step 7: Run tests to verify**

Run: `./gradlew :lang:test --tests "org.inklang.InkCompilerTest" -v`

Expected: All null safety tests pass

---

## Chunk 6: Build and Verify

### Task 10: Build and full test

- [ ] **Step 1: Run full build**

Run: `./gradlew build -v`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all tests**

Run: `./gradlew :lang:test -v`

Expected: All tests pass including new null safety tests

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add null safety operators (?. and ??)"
```
