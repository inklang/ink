# quill Language Features Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement 6 remaining TODO language features in the quill compiler/VM: Ternary, Maps, Enums, Lambdas/Closures, Imports, and Config.

**Architecture:** Each feature extends the same pipeline (AST → AstLowerer → flat IrInstr list → IrCompiler → Chunk → VM). Features are implemented in order of complexity, each adding new tokens/parser rules/IR nodes/opcodes/VM handlers. A single test file drives all features end-to-end.

**Tech Stack:** Kotlin/JVM 21, register-based bytecode VM (32-bit words, 16 physical registers), Gradle. Config feature adds snakeyaml 2.2 and org.json:json.

---

## File Map

| File | Changes |
|------|---------|
| `src/main/kotlin/org/quill/lang/Token.kt` | Add `QUESTION_MARK`, `KW_CONFIG` |
| `src/main/kotlin/org/quill/lang/Lexer.kt` | Handle `'?'`, add `"config"` keyword |
| `src/main/kotlin/org/quill/lang/AST.kt` | Replace `ImportStmt`/`ImportFromStmt` fields; add `ConfigFieldDecl`; replace `ConfigStmt` |
| `src/main/kotlin/org/quill/lang/Parser.kt` | Ternary weight + case; `L_BRACE` map literal; `KW_FN` lambda; import rewrite; config parsing |
| `src/main/kotlin/org/quill/lang/IR.kt` | Add 8 new `IrInstr` subclasses |
| `src/main/kotlin/org/quill/lang/OpCode.kt` | Add 8 new opcodes |
| `src/main/kotlin/org/quill/lang/Value.kt` | Add `staticFields` body property to `ClassDescriptor`; add `UpvalueCell`, `Value.UpvalueRef`, `Value.Closure`, `Value.Module` |
| `src/main/kotlin/org/quill/lang/Chunk.kt` | Add `ConfigSchemaInfo`, `ConfigFieldInfo`, `configSchemas` |
| `src/main/kotlin/org/quill/ast/AstLowerer.kt` | Implement all 7 `TODO()` cases; add closure capture analysis; add `upvalueMap` field |
| `src/main/kotlin/org/quill/ast/IrCompiler.kt` | Fix `rewriteRegisters` catch-all; add 8 compile + rewrite cases |
| `src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt` | Add liveness cases for 8 new IR nodes |
| `src/main/kotlin/org/quill/ast/VM.kt` | Add `upvalues` to `CallFrame`; fix `GET_INDEX`/`SET_INDEX` for maps; add `GET_FIELD` for `Value.Class`/`Value.Module`; add 8 new opcode handlers; add module cache |
| `src/main/kotlin/org/quill/Main.kt` | Fix `rewrite()` catch-all; add rewrite cases for 8 new IR nodes |
| `build.gradle.kts` | Add snakeyaml 2.2 and org.json:json dependencies (Config only) |
| `src/test/kotlin/org/quill/lang/quillTest.kt` | **Create** — end-to-end test helper + feature tests |

---

## Chunk 1: Foundation, Ternary, and Maps

### Task 1: Fix `rewriteRegisters` catch-alls

Both `IrCompiler.rewriteRegisters` (`src/main/kotlin/org/quill/ast/IrCompiler.kt:168`) and `Main.rewrite` (`src/main/kotlin/org/quill/Main.kt:75`) have `else -> instr` that silently passes unknown IR nodes through without remapping registers, causing silent runtime corruption when new IR nodes are added. Replace with fail-fast errors.

**Files:**
- Modify: `src/main/kotlin/org/quill/ast/IrCompiler.kt`
- Modify: `src/main/kotlin/org/quill/Main.kt`

- [ ] **Step 1: Fix `IrCompiler.kt`**

In `rewriteRegisters` (line ~168), **replace** the final `else` branch AND add explicit pass-through cases for register-less nodes that were previously handled by the catch-all. The complete end of the `rewriteRegisters` function after edits:
```kotlin
                is IrInstr.Return -> instr.copy(src = r(instr.src))
                is IrInstr.JumpIfFalse -> instr.copy(src = r(instr.src))
                is IrInstr.LoadFunc -> instr.copy(dst = r(instr.dst))
                // Register-less nodes: pass through unchanged
                is IrInstr.Jump -> instr
                is IrInstr.Label -> instr
                is IrInstr.Break -> instr
                is IrInstr.Next -> instr
                else -> error("rewriteRegisters: unhandled ${instr::class.simpleName}")
            }
        }
    }
```

- [ ] **Step 2: Fix `Main.kt`**

In the `rewrite()` function (line ~75), **replace** the `else` catch-all AND add pass-through cases for register-less nodes. The complete end of the `rewrite()` function after edits:
```kotlin
            is IrInstr.Return -> instr.copy(src = r(instr.src))
            is IrInstr.JumpIfFalse -> instr.copy(src = r(instr.src))
            is IrInstr.LoadFunc -> instr.copy(dst = r(instr.dst))
            // Register-less nodes: pass through unchanged
            is IrInstr.Jump -> instr
            is IrInstr.Label -> instr
            is IrInstr.Break -> instr
            is IrInstr.Next -> instr
            else -> error("rewrite: unhandled ${instr::class.simpleName}")
        }
    }
}
```

- [ ] **Step 3: Build to verify it compiles**

```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/org/quill/ast/IrCompiler.kt src/main/kotlin/org/quill/Main.kt
git commit -m "fix: fail-fast on unhandled IR nodes in rewriteRegisters"
```

---

### Task 2: Create test infrastructure

Create a single test file with a `runScript()` helper that drives the full pipeline and captures `print()` output.

**Files:**
- Create: `src/test/kotlin/org/quill/lang/quillTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package org.inklang.lang

// Note: IrCompiler and VM are declared in `package org.inklang.lang` (despite living
// in the ast/ directory), so they are in the same package as this test — no import needed.
import org.inklang.ast.AstLowerer
import org.inklang.ast.ConstantFolder
import org.inklang.ast.LivenessAnalyzer
import org.inklang.ast.RegisterAllocator
import org.inklang.rewrite
import kotlin.test.Test
import kotlin.test.assertEquals

class quillTest {

    private fun runScript(source: String): String {
        val tokens = tokenize(source)
        val stmts = Parser(tokens).parse()
        val folded = stmts.map { ConstantFolder().foldStmt(it) }
        val result = AstLowerer().lower(folded)
        val ranges = LivenessAnalyzer().analyze(result.instrs)
        val allocation = RegisterAllocator().allocate(ranges)
        val rewritten = rewrite(result.instrs, allocation)
        val chunk = IrCompiler().compile(AstLowerer.LoweredResult(rewritten, result.constants))
        val output = StringBuilder()
        val vm = VM()
        vm.globals["print"] = Value.NativeFunction { args ->
            output.appendLine(args.joinToString(" ") { it.toString() })
            Value.Null
        }
        vm.execute(chunk)
        return output.toString().trimEnd()
    }

    // ---- Smoke test ----

    @Test
    fun testSmokeTest() {
        val result = runScript("""print("hello")""")
        assertEquals("hello", result)
    }
}
```

- [ ] **Step 2: Run the smoke test**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testSmokeTest"
```
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/org/quill/lang/quillTest.kt
git commit -m "test: add end-to-end test infrastructure"
```

---

### Task 3: Implement Ternary Expressions

**Syntax:** `condition ? thenExpr : elseExpr` — right-associative, no new opcodes.

**Files:**
- Modify: `src/main/kotlin/org/quill/lang/Token.kt`
- Modify: `src/main/kotlin/org/quill/lang/Lexer.kt`
- Modify: `src/main/kotlin/org/quill/lang/Parser.kt`
- Modify: `src/main/kotlin/org/quill/ast/AstLowerer.kt`
- Modify: `src/test/kotlin/org/quill/lang/quillTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `quillTest`:
```kotlin
// ---- Ternary ----

@Test
fun testTernaryTrue() {
    val result = runScript("""print(true ? "yes" : "no")""")
    assertEquals("yes", result)
}

@Test
fun testTernaryFalse() {
    val result = runScript("""print(false ? "yes" : "no")""")
    assertEquals("no", result)
}

@Test
fun testTernaryRightAssociative() {
    // a ? b : c ? d : e  parses as  a ? b : (c ? d : e)
    val result = runScript("""print(false ? "a" : true ? "b" : "c")""")
    assertEquals("b", result)
}
```

- [ ] **Step 2: Run to see tests fail**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testTernaryTrue"
```
Expected: FAIL — parse error (QUESTION_MARK not a known token)

- [ ] **Step 3: Add `QUESTION_MARK` to `Token.kt`**

In `TokenType`, add `QUESTION_MARK` after `COLON`:
```kotlin
COLON,
SEMICOLON,
QUESTION_MARK,     // add this line
INTERPOLATION_START,
```

- [ ] **Step 4: Lex `?` in `Lexer.kt`**

In the `when (c)` block, after the `':'` case:
```kotlin
':' -> addToken(TokenType.COLON)
'?' -> addToken(TokenType.QUESTION_MARK)
```

- [ ] **Step 5: Add ternary to `Parser.kt`**

In the `weights` companion object, add one entry between `KW_OR` (20) and `ASSIGN` (10):
```kotlin
TokenType.KW_OR to 20,
TokenType.QUESTION_MARK to 15,    // add this line
TokenType.KW_AND to 30,
```

In `parseExpression`, add a `QUESTION_MARK` case **before** the `advance()` + `parseExpression(precedence + 1)` lines (insert after the `KW_IS` block):
```kotlin
// ternary: condition ? then : else
if (token.type == TokenType.QUESTION_MARK) {
    advance()                                   // consume '?'
    val thenBranch = parseExpression(16)        // one above ternary so ':' stops it
    consume(TokenType.COLON, "Expected ':' in ternary expression")
    val elseBranch = parseExpression(15)        // same level → right-associative
    return Expr.TernaryExpr(left, thenBranch, elseBranch)
}
```

The full `parseExpression` after edits:
```kotlin
private fun parseExpression(minPrecedence: Int): Expr {
    var left = parsePrefix()

    while (true) {
        left = parsePostfix(left)

        val token = peek()
        val precedence = weights[token.type] ?: break
        if (precedence < minPrecedence) break

        if (token.type in ASSIGN_OPS) {
            val target = left
            if (target !is Expr.VariableExpr && target !is Expr.GetExpr && target !is Expr.IndexExpr)
                throw error(token, "Invalid assignment target")
            advance()
            val value = parseExpression(precedence)
            return Expr.AssignExpr(target, token, value)
        }

        if (token.type == TokenType.KW_IS) {
            advance()
            val typeName = parseType()
            left = Expr.IsExpr(left, typeName)
            continue
        }

        if (token.type == TokenType.QUESTION_MARK) {
            advance()
            val thenBranch = parseExpression(16)
            consume(TokenType.COLON, "Expected ':' in ternary expression")
            val elseBranch = parseExpression(15)
            return Expr.TernaryExpr(left, thenBranch, elseBranch)
        }

        advance()
        val right = parseExpression(precedence + 1)
        left = Expr.BinaryExpr(left, token, right)
    }

    return left
}
```

- [ ] **Step 6: Implement `TernaryExpr` in `AstLowerer.kt`**

Replace the `is Expr.TernaryExpr -> TODO()` arm in `lowerExpr`:
```kotlin
is Expr.TernaryExpr -> {
    val elseLabel = freshLabel()
    val endLabel = freshLabel()
    val condReg = freshReg()
    lowerExpr(expr.condition, condReg)
    emit(IrInstr.JumpIfFalse(condReg, elseLabel))
    lowerExpr(expr.thenBranch, dst)
    emit(IrInstr.Jump(endLabel))
    emit(IrInstr.Label(elseLabel))
    lowerExpr(expr.elseBranch, dst)
    emit(IrInstr.Label(endLabel))
    dst
}
```

- [ ] **Step 7: Run ternary tests**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testTernary*"
```
Expected: 3 PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/org/quill/lang/Token.kt \
        src/main/kotlin/org/quill/lang/Lexer.kt \
        src/main/kotlin/org/quill/lang/Parser.kt \
        src/main/kotlin/org/quill/ast/AstLowerer.kt \
        src/test/kotlin/org/quill/lang/quillTest.kt
git commit -m "feat: ternary expressions (condition ? then : else)"
```

---

### Task 4: Implement Map Literals

**Syntax:** `{ "key": value, ... }` — non-empty only. Map access uses `GET_INDEX`/`SET_INDEX`. No `GET_FIELD` for maps.

**Files:**
- Modify: `src/main/kotlin/org/quill/lang/IR.kt`
- Modify: `src/main/kotlin/org/quill/lang/OpCode.kt`
- Modify: `src/main/kotlin/org/quill/lang/Parser.kt`
- Modify: `src/main/kotlin/org/quill/ast/AstLowerer.kt`
- Modify: `src/main/kotlin/org/quill/ast/IrCompiler.kt`
- Modify: `src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt`
- Modify: `src/main/kotlin/org/quill/ast/VM.kt`
- Modify: `src/main/kotlin/org/quill/Main.kt`
- Modify: `src/test/kotlin/org/quill/lang/quillTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `quillTest`:
```kotlin
// ---- Maps ----

@Test
fun testMapLiteralGetIndex() {
    val result = runScript("""
        let m = {"name": "Alice", "age": 30}
        print(m["name"])
        print(m["age"])
    """.trimIndent())
    assertEquals("Alice\n30", result)
}

@Test
fun testMapSetIndex() {
    val result = runScript("""
        let m = {"x": 1}
        m["x"] = 99
        print(m["x"])
    """.trimIndent())
    assertEquals("99", result)
}

@Test
fun testMapMissingKeyReturnsNull() {
    val result = runScript("""
        let m = {"a": 1}
        print(m["b"])
    """.trimIndent())
    assertEquals("null", result)
}
```

- [ ] **Step 2: Run to see tests fail**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testMapLiteralGetIndex"
```
Expected: FAIL — parse error (`{` not handled in prefix)

- [ ] **Step 3: Add `NewMap` to `IR.kt`**

After `NewArray`:
```kotlin
data class NewArray(val dst: Int, val elements: List<Int>): IrInstr()
data class NewMap(val dst: Int, val pairs: List<Pair<Int, Int>>): IrInstr()   // add
```

- [ ] **Step 4: Add `BUILD_MAP` to `OpCode.kt`**

After `BUILD_CLASS(0x25)`:
```kotlin
BUILD_CLASS(0x25),
BUILD_MAP(0x26),    // add
```

- [ ] **Step 5: Add map literal parsing to `Parser.kt`**

In `parsePrefix`, add `L_BRACE` case before the `else` throw:
```kotlin
TokenType.L_BRACE -> {
    // After advance() consumed '{', cursor points to first key token.
    // checkAhead(1, COLON) checks the token after the first key.
    if (check(TokenType.R_BRACE)) throw error(peek(), "Empty map '{}' not supported")
    if (!checkAhead(1, TokenType.COLON)) throw error(peek(), "Expected ':' after map key")
    val entries = mutableListOf<Pair<Expr, Expr>>()
    do {
        val key = parseExpression(0)
        consume(TokenType.COLON, "Expected ':' after map key")
        val value = parseExpression(0)
        entries.add(key to value)
    } while (match(TokenType.COMMA))
    consume(TokenType.R_BRACE, "Expected '}'")
    Expr.MapExpr(entries)
}
```

- [ ] **Step 6: Implement `MapExpr` in `AstLowerer.kt`**

Replace `is Expr.MapExpr -> TODO()`:
```kotlin
is Expr.MapExpr -> {
    val pairRegs = expr.entries.map { (k, v) ->
        val keyReg = lowerExpr(k, freshReg())
        val valReg = lowerExpr(v, freshReg())
        keyReg to valReg
    }
    emit(IrInstr.NewMap(dst, pairRegs))
    dst
}
```

- [ ] **Step 7: Add `NewMap` to `LivenessAnalyzer.kt`**

After the `NewArray` case:
```kotlin
is IrInstr.NewArray -> {
    define(instr.dst, idx)
    instr.elements.forEach { use(it, idx) }
}
is IrInstr.NewMap -> {                   // add
    define(instr.dst, idx)
    instr.pairs.forEach { (k, v) ->
        use(k, idx)
        use(v, idx)
    }
}
```

- [ ] **Step 8: Add `NewMap` to `IrCompiler.kt` compile and rewriteRegisters**

In `compile`, after the `NewArray` case:
```kotlin
is IrInstr.NewArray -> {
    for (elem in instr.elements) {
        chunk.write(OpCode.PUSH_ARG, src1 = elem)
    }
    chunk.write(OpCode.NEW_ARRAY, dst = instr.dst, imm = instr.elements.size)
}
is IrInstr.NewMap -> {                   // add
    for ((k, v) in instr.pairs) {
        chunk.write(OpCode.PUSH_ARG, src1 = k)
        chunk.write(OpCode.PUSH_ARG, src1 = v)
    }
    chunk.write(OpCode.BUILD_MAP, dst = instr.dst, imm = instr.pairs.size)
}
```

In `rewriteRegisters`, after the `NewArray` case:
```kotlin
is IrInstr.NewArray -> instr.copy(dst = r(instr.dst), elements = instr.elements.map { r(it) })
is IrInstr.NewMap -> instr.copy(dst = r(instr.dst), pairs = instr.pairs.map { (k, v) -> r(k) to r(v) })   // add
```

- [ ] **Step 9: Add `NewMap` to `Main.kt` rewrite**

After the `NewArray` case in `rewrite()`:
```kotlin
is IrInstr.NewArray -> instr.copy(dst = r(instr.dst), elements = instr.elements.map { r(it) })
is IrInstr.NewMap -> instr.copy(dst = r(instr.dst), pairs = instr.pairs.map { (k, v) -> r(k) to r(v) })   // add
```

- [ ] **Step 10: Add `BUILD_MAP`, `GET_INDEX`/`SET_INDEX` map support to `VM.kt`**

Add `BUILD_MAP` handler in the `when (opcode)` block (after `NEW_ARRAY`):
```kotlin
OpCode.BUILD_MAP -> {
    val count = imm
    val map = Value.Map()
    // Each pair is two consecutive PUSH_ARGs: key then value
    for (i in 0 until count) {
        val key = frame.argBuffer.removeFirstOrNull()
            ?: error("Missing map key $i in arg buffer")
        val value = frame.argBuffer.removeFirstOrNull()
            ?: error("Missing map value $i in arg buffer")
        map.entries[key] = value
    }
    frame.regs[dst] = map
}
```

Replace the `GET_INDEX` handler to support both `Value.List` and `Value.Map`:
```kotlin
OpCode.GET_INDEX -> {
    val obj = frame.regs[src1] ?: error("Cannot index null")
    val index = frame.regs[src2] ?: error("Index is null")
    frame.regs[dst] = when (obj) {
        is Value.List -> {
            val idx = (index as? Value.Int)?.value
                ?: error("List index must be an integer: $index")
            obj.value.getOrElse(idx) { Value.Null }
        }
        is Value.Map -> obj.entries[index] ?: Value.Null
        else -> error("Cannot index ${obj::class.simpleName}")
    }
}
```

Replace the `SET_INDEX` handler to support both `Value.List` and `Value.Map`:
```kotlin
OpCode.SET_INDEX -> {
    val obj = frame.regs[src1] ?: error("Cannot index null")
    val index = frame.regs[src2] ?: error("Index is null")
    val value = frame.regs[imm] ?: Value.Null
    when (obj) {
        is Value.List -> {
            val idx = (index as? Value.Int)?.value
                ?: error("List index must be an integer: $index")
            if (idx >= 0 && idx < obj.value.size) obj.value[idx] = value
        }
        is Value.Map -> obj.entries[index] = value
        else -> error("Cannot index ${obj::class.simpleName}")
    }
}
```

- [ ] **Step 11: Run map tests**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testMap*"
```
Expected: 3 PASS

- [ ] **Step 12: Run all tests to check for regressions**

```bash
./gradlew test
```
Expected: All PASS

- [ ] **Step 13: Commit**

```bash
git add src/main/kotlin/org/quill/lang/IR.kt \
        src/main/kotlin/org/quill/lang/OpCode.kt \
        src/main/kotlin/org/quill/lang/Parser.kt \
        src/main/kotlin/org/quill/ast/AstLowerer.kt \
        src/main/kotlin/org/quill/ast/IrCompiler.kt \
        src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt \
        src/main/kotlin/org/quill/ast/VM.kt \
        src/main/kotlin/org/quill/Main.kt \
        src/test/kotlin/org/quill/lang/quillTest.kt
git commit -m "feat: map literals and map indexing"
```

---

## Chunk 2: Enums and Lambdas/Closures

### Task 5: Implement Enums

**Syntax:** `enum Direction { North, South, East, West }` — desugars to a class with static variant fields. Access via `Direction.North`.

**Key design:** `ClassDescriptor` gets a `staticFields` body property (not a constructor param). Kotlin's `copy()` calls the constructor and re-runs class-body initializers, so each `copy()` gets a fresh `mutableMapOf()` — no shared state between copies.

**Files:**
- Modify: `src/main/kotlin/org/quill/lang/Value.kt`
- Modify: `src/main/kotlin/org/quill/lang/IR.kt`
- Modify: `src/main/kotlin/org/quill/lang/OpCode.kt`
- Modify: `src/main/kotlin/org/quill/ast/AstLowerer.kt`
- Modify: `src/main/kotlin/org/quill/ast/IrCompiler.kt`
- Modify: `src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt`
- Modify: `src/main/kotlin/org/quill/ast/VM.kt`
- Modify: `src/main/kotlin/org/quill/Main.kt`
- Modify: `src/test/kotlin/org/quill/lang/quillTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `quillTest`:
```kotlin
// ---- Enums ----

@Test
fun testEnumVariantAccess() {
    val result = runScript("""
        enum Direction { North, South, East, West }
        print(Direction.North)
    """.trimIndent())
    // Instance of Direction class, toString shows class name
    // The exact output depends on Value.Instance.toString() — check it prints something non-null
    assert(result.isNotEmpty())
}

@Test
fun testEnumIdentity() {
    val result = runScript("""
        enum Color { Red, Green, Blue }
        let c = Color.Red
        print(c is Color)
    """.trimIndent())
    assertEquals("true", result)
}
```

- [ ] **Step 2: Run to see tests fail**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testEnumVariantAccess"
```
Expected: FAIL — `EnumStmt` lowering is a TODO

- [ ] **Step 3: Add `staticFields` to `ClassDescriptor` in `Value.kt`**

Change `ClassDescriptor` from:
```kotlin
data class ClassDescriptor(
    val name: String,
    val superClass: ClassDescriptor?,
    val methods: Map<String, Value>
)
```
to:
```kotlin
data class ClassDescriptor(
    val name: String,
    val superClass: ClassDescriptor?,
    val methods: Map<String, Value>
) {
    val staticFields: MutableMap<String, Value> = mutableMapOf()
}
```
This is a class-body property (not a constructor param). Kotlin `copy()` calls the primary constructor which re-runs this initializer — every instance (including copies) gets a fresh empty map.

- [ ] **Step 4: Add `StoreEnumVariant` to `IR.kt`**

After `LoadClass`:
```kotlin
data class LoadClass(
    val dst: Int,
    val name: String,
    val superClass: String?,
    val methods: Map<String, MethodInfo>
) : IrInstr()
data class StoreEnumVariant(val classReg: Int, val variantName: String) : IrInstr()   // add
```

- [ ] **Step 5: Add `STORE_ENUM_VARIANT` to `OpCode.kt`**

```kotlin
BUILD_MAP(0x26),
STORE_ENUM_VARIANT(0x27),    // add
```

- [ ] **Step 6: Implement `EnumStmt` in `AstLowerer.kt`**

Replace `is Stmt.EnumStmt -> TODO()` in `lowerStmt`:
```kotlin
is Stmt.EnumStmt -> {
    val dst = freshReg()
    locals[stmt.name.lexeme] = dst
    emit(IrInstr.LoadClass(dst, stmt.name.lexeme, null, emptyMap()))
    emit(IrInstr.StoreGlobal(stmt.name.lexeme, dst))
    for (variant in stmt.values) {
        emit(IrInstr.StoreEnumVariant(dst, variant.lexeme))
    }
}
```

- [ ] **Step 7: Add `StoreEnumVariant` to `LivenessAnalyzer.kt`**

After the `LoadClass` case:
```kotlin
is IrInstr.LoadClass -> define(instr.dst, idx)
is IrInstr.StoreEnumVariant -> use(instr.classReg, idx)   // add
```

- [ ] **Step 8: Add `StoreEnumVariant` to `IrCompiler.kt` compile and rewriteRegisters**

In `compile`, after `LoadClass`:
```kotlin
is IrInstr.StoreEnumVariant ->
    chunk.write(OpCode.STORE_ENUM_VARIANT, src1 = instr.classReg, imm = chunk.addString(instr.variantName))
```

In `rewriteRegisters`, after `LoadClass`:
```kotlin
is IrInstr.LoadClass -> instr.copy(dst = r(instr.dst))
is IrInstr.StoreEnumVariant -> instr.copy(classReg = r(instr.classReg))   // add
```

- [ ] **Step 9: Add `StoreEnumVariant` to `Main.kt` rewrite**

After the `LoadClass` case:
```kotlin
is IrInstr.LoadClass -> instr.copy(dst = r(instr.dst))
is IrInstr.StoreEnumVariant -> instr.copy(classReg = r(instr.classReg))   // add
```

- [ ] **Step 10: Add `STORE_ENUM_VARIANT` handler and `GET_FIELD` for `Value.Class` in `VM.kt`**

Add `STORE_ENUM_VARIANT` handler (after `BUILD_CLASS`):
```kotlin
OpCode.STORE_ENUM_VARIANT -> {
    val classVal = frame.regs[src1] as? Value.Class
        ?: error("STORE_ENUM_VARIANT: expected Value.Class, got ${frame.regs[src1]}")
    val variantName = frame.chunk.strings[imm]
    classVal.descriptor.staticFields[variantName] = Value.Instance(classVal.descriptor)
}
```

In the `GET_FIELD` handler, add a `Value.Class` branch **before** the `else -> error(...)`:
```kotlin
OpCode.GET_FIELD -> {
    val obj = frame.regs[src1] ?: error("Cannot get field on null")
    val fieldName = frame.chunk.strings[imm]
    frame.regs[dst] = when (obj) {
        is Value.Instance -> {
            obj.fields[fieldName]?.let { it }
                ?: lookupMethod(obj, fieldName)?.let { Value.BoundMethod(obj, it) }
                ?: error("Instance has no field '$fieldName'")
        }
        is Value.Class -> {                                        // add this branch
            obj.descriptor.staticFields[fieldName]
                ?: error("Enum '${obj.descriptor.name}' has no variant '$fieldName'")
        }
        else -> error("Cannot get field on ${obj::class.simpleName}")
    }
}
```

- [ ] **Step 11: Run enum tests**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testEnum*"
```
Expected: 2 PASS

- [ ] **Step 12: Run all tests**

```bash
./gradlew test
```
Expected: All PASS

- [ ] **Step 13: Commit**

```bash
git add src/main/kotlin/org/quill/lang/Value.kt \
        src/main/kotlin/org/quill/lang/IR.kt \
        src/main/kotlin/org/quill/lang/OpCode.kt \
        src/main/kotlin/org/quill/ast/AstLowerer.kt \
        src/main/kotlin/org/quill/ast/IrCompiler.kt \
        src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt \
        src/main/kotlin/org/quill/ast/VM.kt \
        src/main/kotlin/org/quill/Main.kt \
        src/test/kotlin/org/quill/lang/quillTest.kt
git commit -m "feat: enum declarations with static variant fields"
```

---

### Task 6: Implement Lambdas / Closures

**Syntax:** `fn(params) { body }` in expression position (e.g., `let f = fn(x) { x + 1 }`). Reference capture: captured variables are boxed into `UpvalueCell` at closure creation time.

**Constraints:** Max 15 upvalues per closure (`src1` is 4-bit). No default parameters on lambda params.

**Key naming rule:** The field that holds a slot index into `frame.upvalues` is named `upvalueIdx` (not `idx`) to visually distinguish it from register fields. **`upvalueIdx` must NEVER be passed to `r()` in `rewriteRegisters`** — it is an ordinal, not a virtual register.

**Files:**
- Modify: `src/main/kotlin/org/quill/lang/Value.kt`
- Modify: `src/main/kotlin/org/quill/lang/IR.kt`
- Modify: `src/main/kotlin/org/quill/lang/OpCode.kt`
- Modify: `src/main/kotlin/org/quill/lang/Parser.kt`
- Modify: `src/main/kotlin/org/quill/ast/AstLowerer.kt`
- Modify: `src/main/kotlin/org/quill/ast/IrCompiler.kt`
- Modify: `src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt`
- Modify: `src/main/kotlin/org/quill/ast/VM.kt`
- Modify: `src/main/kotlin/org/quill/Main.kt`
- Modify: `src/test/kotlin/org/quill/lang/quillTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `quillTest`:
```kotlin
// ---- Lambdas / Closures ----

@Test
fun testLambdaBasic() {
    val result = runScript("""
        let add = fn(a, b) { return a + b }
        print(add(3, 4))
    """.trimIndent())
    assertEquals("7", result)
}

@Test
fun testLambdaClosesOverVariable() {
    val result = runScript("""
        let x = 10
        let addX = fn(n) { return n + x }
        print(addX(5))
    """.trimIndent())
    assertEquals("15", result)
}

@Test
fun testLambdaAsArgument() {
    val result = runScript("""
        fn apply(f, v) { return f(v) }
        let double = fn(n) { return n * 2 }
        print(apply(double, 7))
    """.trimIndent())
    assertEquals("14", result)
}

@Test
fun testLambdaMutatesCapture() {
    // Exercises StoreUpval and the AssignExpr → VariableExpr upvalue branch
    val result = runScript("""
        let count = 0
        let inc = fn() { count = count + 1 }
        inc()
        inc()
        print(count)
    """.trimIndent())
    assertEquals("2", result)
}
```

- [ ] **Step 2: Run to see tests fail**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testLambdaBasic"
```
Expected: FAIL — `LambdaExpr` lowering is a TODO

- [ ] **Step 3: Add new types to `Value.kt`**

Two separate placements are required:

**Inside `sealed class Value { }`** — add `UpvalueRef` and `Closure` after `NativeFunction` (still inside the sealed class closing `}`):
```kotlin
data class NativeFunction(val fn: (kotlin.collections.List<Value>) -> Value) : Value()

/** Internal-only: wraps an UpvalueCell during CAPTURE_UPVAL / LOAD_CLOSURE */
data class UpvalueRef(val cell: UpvalueCell) : Value()

/** A closure: a function chunk + the captured upvalue cells */
data class Closure(val chunk: Chunk, val upvalues: kotlin.collections.List<UpvalueCell>) : Value()
```

**After the closing `}` of `sealed class Value`** — add `UpvalueCell` as a top-level class in the same file (NOT nested inside `sealed class Value`):
```kotlin
// after the sealed class closing brace:

/** A heap-allocated mutable box for closure reference capture.
 *  Placed outside sealed class Value so it is referenced as `UpvalueCell`, not `Value.UpvalueCell`. */
class UpvalueCell(var value: Value)
```

`UpvalueRef` and `Closure` go inside the sealed class so they are `Value.UpvalueRef` and `Value.Closure`. `UpvalueCell` goes outside so all downstream code can use the unqualified name `UpvalueCell`.

- [ ] **Step 4: Add 4 new IR nodes to `IR.kt`**

After `StoreEnumVariant`:
```kotlin
// --- Closure IR nodes ---
/** Outer function: box the current value of src into an UpvalueCell, store UpvalueRef in dst */
data class CaptureUpval(val dst: Int, val src: Int) : IrInstr()

/** Outer function: create a Closure from the compiled body chunk + upvalue cells drained from argBuffer.
 *  captureRegs: outer virtual registers holding the UpvalueRef cells (emitted as PUSH_ARGs before LOAD_CLOSURE). */
data class LoadClosure(
    val dst: Int,
    val name: String,
    val arity: Int,
    val instrs: List<IrInstr>,
    val constants: List<Value>,
    val captureRegs: List<Int>
) : IrInstr()

/** Inside closure body: load from upvalue slot upvalueIdx into dst.
 *  WARNING: upvalueIdx is a slot ordinal — NEVER pass it to r() in rewriteRegisters. */
data class LoadUpval(val dst: Int, val upvalueIdx: Int) : IrInstr()

/** Inside closure body: store src into upvalue slot upvalueIdx.
 *  WARNING: upvalueIdx is a slot ordinal — NEVER pass it to r() in rewriteRegisters. */
data class StoreUpval(val upvalueIdx: Int, val src: Int) : IrInstr()
```

- [ ] **Step 5: Add 4 new opcodes to `OpCode.kt`**

```kotlin
STORE_ENUM_VARIANT(0x27),
CAPTURE_UPVAL(0x28),    // add
LOAD_UPVAL(0x29),       // add
STORE_UPVAL(0x2A),      // add
LOAD_CLOSURE(0x2B),     // add
```

- [ ] **Step 6: Add `KW_FN` lambda parsing to `Parser.kt`**

In `parsePrefix`, add a `KW_FN` case before the `else` throw:
```kotlin
TokenType.KW_FN -> {
    // Lambda expression: fn(params) { body } — 'fn' already consumed by advance()
    consume(TokenType.L_PAREN, "Expected '(' after 'fn'")
    val params = mutableListOf<Param>()
    if (!check(TokenType.R_PAREN)) {
        do {
            val paramName = consume(TokenType.IDENTIFIER, "Expected parameter name")
            val paramType = if (match(TokenType.COLON)) consume(TokenType.IDENTIFIER, "Expected type") else null
            if (check(TokenType.ASSIGN)) throw error(peek(), "Lambda parameters cannot have default values")
            params.add(Param(paramName, paramType))
        } while (match(TokenType.COMMA))
    }
    consume(TokenType.R_PAREN, "Expected ')'")
    val body = parseBlock()
    Expr.LambdaExpr(params, body)
}
```

- [ ] **Step 7: Extend `AstLowerer.kt` for closures**

Add a `upvalueMap` field to `AstLowerer` (alongside the existing `locals`):
```kotlin
private val locals = mutableMapOf<String, Int>()
private val upvalueMap = mutableMapOf<String, Int>()   // name -> upvalue slot index (lambda bodies only)
```

In `lowerExpr` for `Expr.VariableExpr`, add upvalue check BEFORE the register/global check:
```kotlin
is Expr.VariableExpr -> {
    val upvalIdx = upvalueMap[expr.name.lexeme]
    if (upvalIdx != null) {
        emit(IrInstr.LoadUpval(dst, upvalIdx))
        dst
    } else {
        val reg = locals[expr.name.lexeme]
        if (reg != null) {
            reg
        } else {
            emit(LoadGlobal(dst, expr.name.lexeme))
            dst
        }
    }
}
```

In the `AssignExpr` → `VariableExpr` target branch (inside the `else` (simple assignment) block), add upvalue check:
```kotlin
is Expr.VariableExpr -> {
    val upvalIdx = upvalueMap[expr.target.name.lexeme]
    if (upvalIdx != null) {
        val src = lowerExpr(expr.value, freshReg())
        emit(IrInstr.StoreUpval(upvalIdx, src))
        src
    } else {
        val reg = locals[expr.target.name.lexeme]
        if (reg != null) {
            lowerExpr(expr.value, reg)
            reg
        } else {
            val src = lowerExpr(expr.value, freshReg())
            emit(StoreGlobal(expr.target.name.lexeme, src))
            src
        }
    }
}
```

Replace `is Expr.LambdaExpr -> TODO()` with a call to a new private function:
```kotlin
is Expr.LambdaExpr -> lowerLambda(expr, dst)
```

Add the helper functions to `AstLowerer`:

```kotlin
/** Walk the lambda body AST to find variable names used from outer scope */
private fun findCapturedVars(body: Stmt.BlockStmt, outerLocals: Map<String, Int>): List<String> {
    val used = linkedSetOf<String>()       // preserve declaration order
    val locallyDeclared = mutableSetOf<String>()

    fun walkExpr(expr: Expr) {
        when (expr) {
            is Expr.VariableExpr  -> used.add(expr.name.lexeme)
            is Expr.BinaryExpr    -> { walkExpr(expr.left); walkExpr(expr.right) }
            is Expr.UnaryExpr     -> walkExpr(expr.right)
            is Expr.TernaryExpr   -> { walkExpr(expr.condition); walkExpr(expr.thenBranch); walkExpr(expr.elseBranch) }
            is Expr.CallExpr      -> { walkExpr(expr.callee); expr.arguments.forEach { walkExpr(it) } }
            is Expr.AssignExpr    -> { walkExpr(expr.target); walkExpr(expr.value) }
            is Expr.GetExpr       -> walkExpr(expr.obj)
            is Expr.IndexExpr     -> { walkExpr(expr.obj); walkExpr(expr.index) }
            is Expr.GroupExpr     -> walkExpr(expr.expr)
            is Expr.ListExpr      -> expr.elements.forEach { walkExpr(it) }
            is Expr.MapExpr       -> expr.entries.forEach { (k, v) -> walkExpr(k); walkExpr(v) }
            is Expr.LiteralExpr, is Expr.LambdaExpr, is Expr.IsExpr -> {}
        }
    }

    fun walkStmt(stmt: Stmt) {
        when (stmt) {
            is Stmt.VarStmt      -> { stmt.value?.let { walkExpr(it) }; locallyDeclared.add(stmt.name.lexeme) }
            is Stmt.ExprStmt     -> walkExpr(stmt.expr)
            is Stmt.BlockStmt    -> stmt.stmts.forEach { walkStmt(it) }
            is Stmt.ReturnStmt   -> stmt.value?.let { walkExpr(it) }
            is Stmt.IfStmt       -> {
                walkExpr(stmt.condition); walkStmt(stmt.then)
                when (val e = stmt.elseBranch) {
                    is Stmt.ElseBranch.Else  -> walkStmt(e.block)
                    is Stmt.ElseBranch.ElseIf -> walkStmt(e.stmt)
                    null -> {}
                }
            }
            is Stmt.WhileStmt    -> { walkExpr(stmt.condition); walkStmt(stmt.body) }
            is Stmt.ForRangeStmt -> { walkExpr(stmt.iterable); walkStmt(stmt.body) }
            else                 -> {}
        }
    }

    for (stmt in body.stmts) walkStmt(stmt)
    return used.filter { it in outerLocals && it !in locallyDeclared }
}

private fun lowerLambda(expr: Expr.LambdaExpr, dst: Int): Int {
    // 1. Capture analysis
    val capturedNames = findCapturedVars(expr.body, locals)
    if (capturedNames.size > 15) error("Closure captures too many variables (max 15, got ${capturedNames.size})")

    // 2. Per captured variable: box into UpvalueCell, collect capture registers
    val captureRegs = mutableListOf<Int>()
    for (name in capturedNames) {
        val varReg = locals[name]!!
        val cellReg = freshReg()
        emit(IrInstr.CaptureUpval(cellReg, varReg))
        captureRegs.add(cellReg)
    }

    // 3. Compile lambda body in a child lowerer with upvalue access
    val lambdaLowerer = AstLowerer()
    capturedNames.forEachIndexed { idx, name -> lambdaLowerer.upvalueMap[name] = idx }
    for ((i, param) in expr.params.withIndex()) {
        lambdaLowerer.locals[param.name.lexeme] = i
    }
    lambdaLowerer.regCounter = expr.params.size
    val lambdaResult = lambdaLowerer.lower(expr.body.stmts)

    // 4. Emit LoadClosure
    emit(IrInstr.LoadClosure(dst, "<lambda>", expr.params.size, lambdaResult.instrs, lambdaResult.constants, captureRegs))
    return dst
}
```

- [ ] **Step 8: Add closure IR nodes to `LivenessAnalyzer.kt`**

Add after `StoreEnumVariant`:
```kotlin
is IrInstr.StoreEnumVariant -> use(instr.classReg, idx)
is IrInstr.CaptureUpval -> {                        // add
    define(instr.dst, idx)
    use(instr.src, idx)
}
is IrInstr.LoadClosure -> {                         // add
    define(instr.dst, idx)
    instr.captureRegs.forEach { use(it, idx) }
}
is IrInstr.LoadUpval -> define(instr.dst, idx)      // upvalueIdx is NOT a register
is IrInstr.StoreUpval -> use(instr.src, idx)        // upvalueIdx is NOT a register
```

- [ ] **Step 9: Add closure IR nodes to `IrCompiler.kt` compile and rewriteRegisters**

In `compile`, add after `StoreEnumVariant`:
```kotlin
is IrInstr.CaptureUpval ->
    chunk.write(OpCode.CAPTURE_UPVAL, dst = instr.dst, src1 = instr.src)

is IrInstr.LoadClosure -> {
    // Run register allocation on lambda body
    val funcRanges = LivenessAnalyzer().analyze(instr.instrs)
    val funcAllocation = RegisterAllocator().allocate(funcRanges, instr.arity)
    val funcRewritten = rewriteRegisters(instr.instrs, funcAllocation)
    val funcResult = AstLowerer.LoweredResult(funcRewritten, instr.constants)
    val funcChunk = IrCompiler().compile(funcResult)
    val funcIdx = chunk.functions.size
    chunk.functions.add(funcChunk)
    // Push capture cells as args before LOAD_CLOSURE
    for (reg in instr.captureRegs) {
        chunk.write(OpCode.PUSH_ARG, src1 = reg)
    }
    chunk.write(OpCode.LOAD_CLOSURE, dst = instr.dst, imm = funcIdx, src1 = instr.captureRegs.size)
}

is IrInstr.LoadUpval ->
    chunk.write(OpCode.LOAD_UPVAL, dst = instr.dst, imm = instr.upvalueIdx)
    // NOTE: imm = upvalueIdx (slot ordinal), NOT a register

is IrInstr.StoreUpval ->
    chunk.write(OpCode.STORE_UPVAL, src1 = instr.src, imm = instr.upvalueIdx)
    // NOTE: imm = upvalueIdx (slot ordinal), NOT a register
```

In `rewriteRegisters`, add after `StoreEnumVariant`:
```kotlin
is IrInstr.StoreEnumVariant -> instr.copy(classReg = r(instr.classReg))
is IrInstr.CaptureUpval -> instr.copy(dst = r(instr.dst), src = r(instr.src))
is IrInstr.LoadClosure -> instr.copy(dst = r(instr.dst), captureRegs = instr.captureRegs.map { r(it) })
is IrInstr.LoadUpval -> instr.copy(dst = r(instr.dst))   // upvalueIdx is NOT a register — do NOT call r() on it
is IrInstr.StoreUpval -> instr.copy(src = r(instr.src))  // upvalueIdx is NOT a register — do NOT call r() on it
```

- [ ] **Step 10: Add closure IR nodes to `Main.kt` rewrite**

After `StoreEnumVariant`:
```kotlin
is IrInstr.StoreEnumVariant -> instr.copy(classReg = r(instr.classReg))
is IrInstr.CaptureUpval -> instr.copy(dst = r(instr.dst), src = r(instr.src))
is IrInstr.LoadClosure -> instr.copy(dst = r(instr.dst), captureRegs = instr.captureRegs.map { r(it) })
is IrInstr.LoadUpval -> instr.copy(dst = r(instr.dst))
is IrInstr.StoreUpval -> instr.copy(src = r(instr.src))
```

- [ ] **Step 11: Add `upvalues` to `CallFrame` in `VM.kt`**

Change the `CallFrame` data class:
```kotlin
data class CallFrame(
    val chunk: Chunk,
    var ip: Int = 0,
    val regs: Array<Value?> = arrayOfNulls(16),
    var returnDst: Int = 0,
    val argBuffer: ArrayDeque<Value> = ArrayDeque(),
    var upvalues: List<UpvalueCell>? = null    // add
)
```

Add the 4 closure opcode handlers in `VM.execute`'s `when (opcode)` block:

```kotlin
OpCode.CAPTURE_UPVAL -> {
    val cell = UpvalueCell(frame.regs[src1] ?: Value.Null)
    frame.regs[dst] = Value.UpvalueRef(cell)
}

OpCode.LOAD_CLOSURE -> {
    val upvalueCount = src1
    val cells = (0 until upvalueCount).map { i ->
        val ref = frame.argBuffer.removeFirstOrNull()
            ?: error("Missing upvalue $i in arg buffer for LOAD_CLOSURE")
        (ref as? Value.UpvalueRef)?.cell
            ?: error("Expected UpvalueRef in arg buffer, got ${ref::class.simpleName}")
    }
    val closureChunk = frame.chunk.functions[imm]
    frame.regs[dst] = Value.Closure(closureChunk, cells)
}

OpCode.LOAD_UPVAL -> {
    val cells = frame.upvalues ?: error("LOAD_UPVAL in non-closure frame")
    frame.regs[dst] = cells[imm].value
}

OpCode.STORE_UPVAL -> {
    val cells = frame.upvalues ?: error("STORE_UPVAL in non-closure frame")
    cells[imm].value = frame.regs[src1] ?: Value.Null
}
```

In the `CALL` handler, add a `Value.Closure` branch alongside `Value.Function`:
```kotlin
is Value.Closure -> {
    val newFrame = CallFrame(func.chunk)
    newFrame.returnDst = dst
    newFrame.upvalues = func.upvalues
    args.forEachIndexed { i, v -> newFrame.regs[i] = v }
    frames.addLast(newFrame)
}
```

- [ ] **Step 12: Run lambda tests**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testLambda*"
```
Expected: 4 PASS

- [ ] **Step 13: Run all tests**

```bash
./gradlew test
```
Expected: All PASS

- [ ] **Step 14: Commit**

```bash
git add src/main/kotlin/org/quill/lang/Value.kt \
        src/main/kotlin/org/quill/lang/IR.kt \
        src/main/kotlin/org/quill/lang/OpCode.kt \
        src/main/kotlin/org/quill/lang/Parser.kt \
        src/main/kotlin/org/quill/ast/AstLowerer.kt \
        src/main/kotlin/org/quill/ast/IrCompiler.kt \
        src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt \
        src/main/kotlin/org/quill/ast/VM.kt \
        src/main/kotlin/org/quill/Main.kt \
        src/test/kotlin/org/quill/lang/quillTest.kt
git commit -m "feat: lambdas and closures with upvalue capture"
```

---

## Chunk 3: Imports and Config

### Task 7: Implement Imports

**Syntax:**
```
import "utils"              // binds module as namespace: utils.foo
from "utils" import foo     // pulls single name into scope
```

The import system compiles and executes modules in isolated VMs, caches results, and detects circular imports.

**AST change:** Replace identifier-based import syntax with string-path-based. The current `ImportStmt(namespace: Token)` and `ImportFromStmt(namespace: Token, tokens: List<Token>)` use identifier tokens. The new versions use a string path token.

**Files:**
- Modify: `src/main/kotlin/org/quill/lang/AST.kt`
- Modify: `src/main/kotlin/org/quill/lang/Value.kt`
- Modify: `src/main/kotlin/org/quill/lang/IR.kt`
- Modify: `src/main/kotlin/org/quill/lang/OpCode.kt`
- Modify: `src/main/kotlin/org/quill/lang/Parser.kt`
- Modify: `src/main/kotlin/org/quill/ast/AstLowerer.kt`
- Modify: `src/main/kotlin/org/quill/ast/IrCompiler.kt`
- Modify: `src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt`
- Modify: `src/main/kotlin/org/quill/ast/VM.kt`
- Modify: `src/main/kotlin/org/quill/Main.kt`
- Modify: `src/test/kotlin/org/quill/lang/quillTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `quillTest`:
```kotlin
// ---- Imports ----

@Test
fun testImportModule() {
    // Write a small module file to disk, then import it
    val moduleFile = java.io.File("test_module_import.quill")
    moduleFile.writeText("""
        fn greet(name) { return "Hello, " + name }
    """.trimIndent())
    try {
        val result = runScript("""
            import "test_module_import"
            print(test_module_import.greet("World"))
        """.trimIndent())
        assertEquals("Hello, World", result)
    } finally {
        moduleFile.delete()
    }
}

@Test
fun testFromImport() {
    val moduleFile = java.io.File("test_module_from.quill")
    moduleFile.writeText("""fn double(n) { return n * 2 }""")
    try {
        val result = runScript("""
            from "test_module_from" import double
            print(double(5))
        """.trimIndent())
        assertEquals("10", result)
    } finally {
        moduleFile.delete()
    }
}
```

- [ ] **Step 2: Run to see tests fail**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testImportModule"
```
Expected: FAIL — `ImportStmt` lowering is a TODO

- [ ] **Step 3: Update `AST.kt` for string-path imports**

Replace the existing `ImportStmt` and `ImportFromStmt`:
```kotlin
// OLD:
// data class ImportStmt(val namespace: Token) : Stmt()
// data class ImportFromStmt(val namespace: Token, val tokens: List<Token>) : Stmt() { init { ... } }

// NEW:
data class ImportStmt(val path: Token) : Stmt()          // path.type == KW_STRING
data class ImportFromStmt(val path: Token, val name: Token) : Stmt()  // single-name only; no init block
```

- [ ] **Step 4: Add `Value.Module` to `Value.kt`**

After `Value.Closure`:
```kotlin
data class Closure(val chunk: Chunk, val upvalues: kotlin.collections.List<UpvalueCell>) : Value()
data class Module(val exports: kotlin.collections.Map<kotlin.String, Value>) : Value()   // add
```

- [ ] **Step 5: Add `Import` IR node to `IR.kt`**

After `StoreUpval`:
```kotlin
data class StoreUpval(val upvalueIdx: Int, val src: Int) : IrInstr()
data class Import(val dst: Int, val path: String) : IrInstr()   // add
```

- [ ] **Step 6: Add `IMPORT` opcode to `OpCode.kt`**

```kotlin
LOAD_CLOSURE(0x2B),
IMPORT(0x2C),    // add
```

- [ ] **Step 7: Rewrite `parseImport()` and add `KW_FROM` dispatch in `Parser.kt`**

In `parseStmt`, add the `KW_FROM` branch **before** the `else` clause (the `KW_IMPORT` branch already exists):
```kotlin
check(TokenType.KW_IMPORT) -> parseImport()
check(TokenType.KW_FROM) -> parseImport()          // add this line
```

Replace `parseImport()` entirely:
```kotlin
private fun parseImport(): Stmt {
    // Called with cursor pointing at KW_FROM or KW_IMPORT (not pre-consumed)
    if (check(TokenType.KW_FROM)) {
        consume(TokenType.KW_FROM, "Expected 'from'")
        val path = consume(TokenType.KW_STRING, "Expected module path string after 'from'")
        consume(TokenType.KW_IMPORT, "Expected 'import'")
        val name = consume(TokenType.IDENTIFIER, "Expected identifier after 'import'")
        if (check(TokenType.SEMICOLON)) advance()
        return Stmt.ImportFromStmt(path, name)
    } else {
        consume(TokenType.KW_IMPORT, "Expected 'import'")
        val path = consume(TokenType.KW_STRING, "Expected module path string after 'import'")
        if (check(TokenType.SEMICOLON)) advance()
        return Stmt.ImportStmt(path)
    }
}
```

- [ ] **Step 8: Implement `ImportStmt` and `ImportFromStmt` in `AstLowerer.kt`**

Replace `is Stmt.ImportFromStmt -> TODO()` and `is Stmt.ImportStmt -> TODO()`:

```kotlin
is Stmt.ImportStmt -> {
    // import "path"  →  let <basename> = IMPORT("path")
    val pathStr = stmt.path.lexeme.trim('"')
    val baseName = pathStr.substringAfterLast('/').substringAfterLast('\\')
    val dst = freshReg()
    locals[baseName] = dst
    emit(IrInstr.Import(dst, pathStr))
}

is Stmt.ImportFromStmt -> {
    // from "path" import name  →  let name = IMPORT("path")["name"]
    val pathStr = stmt.path.lexeme.trim('"')
    val moduleReg = freshReg()
    emit(IrInstr.Import(moduleReg, pathStr))
    // Extract the named export via GET_FIELD
    val dst = freshReg()
    locals[stmt.name.lexeme] = dst
    emit(IrInstr.GetField(dst, moduleReg, stmt.name.lexeme))
}
```

- [ ] **Step 9: Add `Import` to `LivenessAnalyzer.kt`**

After `StoreUpval`:
```kotlin
is IrInstr.StoreUpval -> use(instr.src, idx)
is IrInstr.Import -> define(instr.dst, idx)   // add
```

- [ ] **Step 10: Add `Import` to `IrCompiler.kt` compile and rewriteRegisters**

In `compile`:
```kotlin
is IrInstr.Import ->
    chunk.write(OpCode.IMPORT, dst = instr.dst, imm = chunk.addString(instr.path))
```

In `rewriteRegisters`:
```kotlin
is IrInstr.Import -> instr.copy(dst = r(instr.dst))
```

- [ ] **Step 11: Add `Import` to `Main.kt` rewrite**

```kotlin
is IrInstr.Import -> instr.copy(dst = r(instr.dst))
```

- [ ] **Step 12: Add module cache, `IMPORT` handler, and `GET_FIELD` for `Value.Module` in `VM.kt`**

Add module cache fields to the `VM` class (alongside `val globals`):
```kotlin
val globals = mutableMapOf<String, Value>()
private val moduleCache: MutableMap<String, Value.Module> = mutableMapOf()
private val modulesInProgress: MutableSet<String> = mutableSetOf()
```

Add `IMPORT` handler in `when (opcode)`:
```kotlin
OpCode.IMPORT -> {
    val path = frame.chunk.strings[imm]
    val module = moduleCache[path] ?: run {
        if (path in modulesInProgress) error("Circular import detected: $path")
        modulesInProgress.add(path)
        val source = java.io.File("$path.quill").readText()
        val tokens = tokenize(source)
        val stmts = Parser(tokens).parse()
        val folded = stmts.map { org.inklang.ast.ConstantFolder().foldStmt(it) }
        val result = org.inklang.ast.AstLowerer().lower(folded)
        val ranges = org.inklang.ast.LivenessAnalyzer().analyze(result.instrs)
        val allocation = org.inklang.ast.RegisterAllocator().allocate(ranges)
        val rewritten = org.inklang.rewrite(result.instrs, allocation)
        val chunk = IrCompiler().compile(AstLowerer.LoweredResult(rewritten, result.constants))
        val moduleVm = VM()
        // Share builtins with the module VM
        moduleVm.globals["print"] = globals["print"] ?: Value.NativeFunction { args ->
            println(args.joinToString(" ") { it.toString() }); Value.Null
        }
        moduleVm.execute(chunk)
        val exports = moduleVm.globals.toMap()
        val mod = Value.Module(exports)
        modulesInProgress.remove(path)
        moduleCache[path] = mod
        mod
    }
    frame.regs[dst] = module
}
```

Add `Value.Module` branch to `GET_FIELD` handler, before `else -> error(...)`:
```kotlin
is Value.Module -> {
    obj.exports[fieldName]
        ?: error("Module has no export '$fieldName'")
}
```

The complete updated `GET_FIELD` handler:
```kotlin
OpCode.GET_FIELD -> {
    val obj = frame.regs[src1] ?: error("Cannot get field on null")
    val fieldName = frame.chunk.strings[imm]
    frame.regs[dst] = when (obj) {
        is Value.Instance -> {
            obj.fields[fieldName]?.let { it }
                ?: lookupMethod(obj, fieldName)?.let { Value.BoundMethod(obj, it) }
                ?: error("Instance has no field '$fieldName'")
        }
        is Value.Class -> {
            obj.descriptor.staticFields[fieldName]
                ?: error("Enum '${obj.descriptor.name}' has no variant '$fieldName'")
        }
        is Value.Module -> {
            obj.exports[fieldName]
                ?: error("Module has no export '$fieldName'")
        }
        else -> error("Cannot get field on ${obj::class.simpleName}")
    }
}
```

The `IMPORT` handler uses classes from other packages. Add any missing imports at the top of `VM.kt`. Note that `IrCompiler` and `VM` are already in `package org.inklang.lang` (same package as `VM.kt`), so no import is needed for `IrCompiler`. The ones that do need importing (if not already present):
```kotlin
import org.inklang.ast.AstLowerer
import org.inklang.ast.ConstantFolder
import org.inklang.ast.LivenessAnalyzer
import org.inklang.ast.RegisterAllocator
import org.inklang.rewrite
// Parser and tokenize are in org.inklang.lang — same package, no import needed
```

- [ ] **Step 13: Run import tests**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testImport*"
```
Expected: 2 PASS

- [ ] **Step 14: Run all tests**

```bash
./gradlew test
```
Expected: All PASS

- [ ] **Step 15: Commit**

```bash
git add src/main/kotlin/org/quill/lang/AST.kt \
        src/main/kotlin/org/quill/lang/Value.kt \
        src/main/kotlin/org/quill/lang/IR.kt \
        src/main/kotlin/org/quill/lang/OpCode.kt \
        src/main/kotlin/org/quill/lang/Parser.kt \
        src/main/kotlin/org/quill/ast/AstLowerer.kt \
        src/main/kotlin/org/quill/ast/IrCompiler.kt \
        src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt \
        src/main/kotlin/org/quill/ast/VM.kt \
        src/main/kotlin/org/quill/Main.kt \
        src/test/kotlin/org/quill/lang/quillTest.kt
git commit -m "feat: import system with module cache and circular import detection"
```

---

### Task 8: Implement Config

**Syntax:**
```
config "settings.yml" {
    host: string = "localhost",
    port: int = 8080,
    debug: bool = false
}
```
Reads/writes a YAML or JSON config file. Fields are extracted into local variables. Absent file is created with defaults.

**Files:**
- Modify: `src/main/kotlin/org/quill/lang/Token.kt`
- Modify: `src/main/kotlin/org/quill/lang/Lexer.kt`
- Modify: `src/main/kotlin/org/quill/lang/AST.kt`
- Modify: `src/main/kotlin/org/quill/lang/Parser.kt`
- Modify: `src/main/kotlin/org/quill/lang/Chunk.kt`
- Modify: `src/main/kotlin/org/quill/lang/IR.kt`
- Modify: `src/main/kotlin/org/quill/lang/OpCode.kt`
- Modify: `src/main/kotlin/org/quill/ast/AstLowerer.kt`
- Modify: `src/main/kotlin/org/quill/ast/IrCompiler.kt`
- Modify: `src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt`
- Modify: `src/main/kotlin/org/quill/ast/VM.kt`
- Modify: `src/main/kotlin/org/quill/Main.kt`
- Modify: `build.gradle.kts`
- Modify: `src/test/kotlin/org/quill/lang/quillTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `quillTest`:
```kotlin
// ---- Config ----

@Test
fun testConfigCreatesFileWithDefaults() {
    val configFile = java.io.File("test_config_defaults.yml")
    configFile.delete()
    try {
        val result = runScript("""
            config "test_config_defaults.yml" {
                host: string = "localhost",
                port: int = 8080
            }
            print(host)
            print(port)
        """.trimIndent())
        assertEquals("localhost\n8080", result)
        assert(configFile.exists()) { "Config file should have been created" }
    } finally {
        configFile.delete()
    }
}

@Test
fun testConfigReadsExistingFile() {
    val configFile = java.io.File("test_config_read.yml")
    configFile.writeText("host: myserver\nport: 9090\n")
    try {
        val result = runScript("""
            config "test_config_read.yml" {
                host: string = "localhost",
                port: int = 8080
            }
            print(host)
            print(port)
        """.trimIndent())
        assertEquals("myserver\n9090", result)
    } finally {
        configFile.delete()
    }
}
```

- [ ] **Step 2: Run to see tests fail**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testConfigCreatesFileWithDefaults"
```
Expected: FAIL — `KW_CONFIG` not a known token

- [ ] **Step 3: Add dependencies to `build.gradle.kts`**

In the `dependencies` block, add:
```kotlin
dependencies {
    testImplementation(kotlin("test"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("org.yaml:snakeyaml:2.2")                 // add
    implementation("org.json:json:20231013")                  // add
}
```

Run `./gradlew build` to download the new dependencies.

- [ ] **Step 4: Add `KW_CONFIG` to `Token.kt`**

```kotlin
KW_IMPORT,
KW_FROM,
KW_IS,
KW_CONFIG,    // add
IDENTIFIER,
```

- [ ] **Step 5: Add `"config"` keyword to `Lexer.kt`**

In the `keywords` map:
```kotlin
"from" to TokenType.KW_FROM,
"is" to TokenType.KW_IS,
"config" to TokenType.KW_CONFIG,    // add
```

Also add `KW_CONFIG` to `STATEMENT_ENDERS`? No — `config` is a statement keyword, not a value expression. It does NOT need to be in `STATEMENT_ENDERS`.

- [ ] **Step 6: Update `AST.kt` — add `ConfigFieldDecl` and replace `ConfigStmt`**

Add `ConfigFieldDecl` before `ConfigStmt`:
```kotlin
data class ConfigFieldDecl(
    val name: Token,       // the field name identifier
    val type: Token,       // the type keyword (KW_STRING, KW_INT, KW_BOOL, etc.)
    val default: Expr?     // optional default expression
)
```

Replace the existing `ConfigStmt`:
```kotlin
// OLD: data class ConfigStmt(val name: Token, val body: List<VarStmt>) : Stmt()
// NEW:
data class ConfigStmt(val path: Token, val fields: List<ConfigFieldDecl>) : Stmt()
```
`path` holds the `KW_STRING` path token (e.g., `"settings.yml"`).

- [ ] **Step 7: Add `parseConfig()` and `KW_CONFIG` dispatch to `Parser.kt`**

In `parseStmt`, add before the `else` clause:
```kotlin
check(TokenType.KW_CONFIG) -> parseConfig()
```

Add the `parseConfig()` function:
```kotlin
private fun parseConfig(): Stmt {
    consume(TokenType.KW_CONFIG, "Expected 'config'")
    val path = consume(TokenType.KW_STRING, "Expected config file path string")
    consume(TokenType.L_BRACE, "Expected '{' after config path")
    val fields = mutableListOf<ConfigFieldDecl>()
    while (!check(TokenType.R_BRACE) && !isAtEnd()) {
        val name = consume(TokenType.IDENTIFIER, "Expected field name")
        consume(TokenType.COLON, "Expected ':' after field name")
        val type = parseType()
        val default = if (match(TokenType.ASSIGN)) parseExpression(0) else null
        fields.add(ConfigFieldDecl(name, type, default))
        if (!check(TokenType.R_BRACE)) {
            consume(TokenType.COMMA, "Expected ',' or '}' after field declaration")
        }
    }
    consume(TokenType.R_BRACE, "Expected '}'")
    if (check(TokenType.SEMICOLON)) advance()
    return Stmt.ConfigStmt(path, fields)
}
```

- [ ] **Step 8: Add `ConfigSchemaInfo`/`ConfigFieldInfo` to `Chunk.kt`**

Add before the `Chunk` class:
```kotlin
data class ConfigFieldInfo(val name: String, val type: String, val hasDefault: Boolean)
data class ConfigSchemaInfo(
    val path: String,
    val fields: List<ConfigFieldInfo>,
    val defaultRegMap: List<Int?>   // physical register index per field; null = no default
)
```

Add a field to the `Chunk` class:
```kotlin
val configSchemas: MutableList<ConfigSchemaInfo> = mutableListOf()
```

- [ ] **Step 9: Add `LoadConfig` IR node to `IR.kt`**

After `Import`:
```kotlin
data class Import(val dst: Int, val path: String) : IrInstr()
data class LoadConfig(
    val dst: Int,
    val schemaPath: String,
    val fields: List<ConfigFieldDecl>,      // for building ConfigSchemaInfo in IrCompiler
    val defaultRegs: List<Int?>             // virtual register per field; null = no default
) : IrInstr()
```

- [ ] **Step 10: Add `LOAD_CONFIG` opcode to `OpCode.kt`**

```kotlin
IMPORT(0x2C),
LOAD_CONFIG(0x2D),    // add
```

- [ ] **Step 11: Implement `ConfigStmt` in `AstLowerer.kt`**

Replace `is Stmt.ConfigStmt -> TODO()`:
```kotlin
is Stmt.ConfigStmt -> {
    val pathStr = stmt.path.lexeme.trim('"')

    // Lower each default expression to a fresh register (null if no default)
    val defaultRegs = stmt.fields.map { field ->
        field.default?.let { lowerExpr(it, freshReg()) }
    }

    // Emit LoadConfig
    val dst = freshReg()
    emit(IrInstr.LoadConfig(dst, pathStr, stmt.fields, defaultRegs))

    // Extract each field into a local variable via GET_INDEX
    for (field in stmt.fields) {
        val keyReg = freshReg()
        val keyIdx = addConstant(Value.String(field.name.lexeme))
        emit(IrInstr.LoadImm(keyReg, keyIdx))
        val fieldReg = freshReg()
        emit(IrInstr.GetIndex(fieldReg, dst, keyReg))
        locals[field.name.lexeme] = fieldReg
    }
}
```

- [ ] **Step 12: Add `LoadConfig` to `LivenessAnalyzer.kt`**

After `Import`:
```kotlin
is IrInstr.Import -> define(instr.dst, idx)
is IrInstr.LoadConfig -> {                     // add
    define(instr.dst, idx)
    instr.defaultRegs.forEach { reg -> reg?.let { use(it, idx) } }
}
```

- [ ] **Step 13: Add `LoadConfig` to `IrCompiler.kt` compile and rewriteRegisters**

In `compile`:
```kotlin
is IrInstr.LoadConfig -> {
    val fieldInfos = instr.fields.map { f ->
        ConfigFieldInfo(f.name.lexeme, f.type.lexeme, f.default != null)
    }
    val schema = ConfigSchemaInfo(instr.schemaPath, fieldInfos, instr.defaultRegs)
    val schemaIdx = chunk.configSchemas.size
    chunk.configSchemas.add(schema)
    chunk.write(OpCode.LOAD_CONFIG, dst = instr.dst, imm = schemaIdx)
}
```

In `rewriteRegisters`:
```kotlin
is IrInstr.Import -> instr.copy(dst = r(instr.dst))
is IrInstr.LoadConfig -> instr.copy(          // add
    dst = r(instr.dst),
    defaultRegs = instr.defaultRegs.map { reg -> reg?.let { r(it) } }
)
```

- [ ] **Step 14: Add `LoadConfig` to `Main.kt` rewrite**

```kotlin
is IrInstr.Import -> instr.copy(dst = r(instr.dst))
is IrInstr.LoadConfig -> instr.copy(
    dst = r(instr.dst),
    defaultRegs = instr.defaultRegs.map { reg -> reg?.let { r(it) } }
)
```

- [ ] **Step 15: Add `LOAD_CONFIG` handler to `VM.kt`**

Add at top of VM.kt (if not present):
```kotlin
import org.yaml.snakeyaml.Yaml
import org.json.JSONObject
```

Add `LOAD_CONFIG` handler in `when (opcode)`:
```kotlin
OpCode.LOAD_CONFIG -> {
    val schema = frame.chunk.configSchemas[imm]
    val file = java.io.File(schema.path)

    // Parse file if it exists
    val fileValues: Map<String, Any?> = if (file.exists()) {
        when {
            schema.path.endsWith(".yml") || schema.path.endsWith(".yaml") ->
                @Suppress("UNCHECKED_CAST")
                (Yaml().load(file.readText()) as? Map<String, Any?>) ?: emptyMap()
            schema.path.endsWith(".json") -> {
                val obj = JSONObject(file.readText())
                obj.keySet().associateWith { obj.get(it) }
            }
            else -> error("Unsupported config format: ${schema.path}")
        }
    } else emptyMap()

    // Build result map
    val result = mutableMapOf<Value, Value>()
    for ((i, fieldInfo) in schema.fields.withIndex()) {
        val rawValue = fileValues[fieldInfo.name]
        val value: Value = if (rawValue != null) {
            // Type-coerce from file
            when (fieldInfo.type) {
                "string" -> Value.String(rawValue.toString())
                "int"    -> when (rawValue) {
                    is Int    -> Value.Int(rawValue)
                    is Long   -> Value.Int(rawValue.toInt())
                    else      -> error("Config field '${fieldInfo.name}': expected int, got $rawValue")
                }
                "float"  -> when (rawValue) {
                    is Int    -> Value.Float(rawValue.toFloat())
                    is Long   -> Value.Float(rawValue.toFloat())
                    is Double -> Value.Float(rawValue.toFloat())
                    is Float  -> Value.Float(rawValue)
                    else      -> error("Config field '${fieldInfo.name}': expected float, got $rawValue")
                }
                "double" -> when (rawValue) {
                    is Int    -> Value.Double(rawValue.toDouble())
                    is Long   -> Value.Double(rawValue.toDouble())
                    is Double -> Value.Double(rawValue)
                    is Float  -> Value.Double(rawValue.toDouble())
                    else      -> error("Config field '${fieldInfo.name}': expected double, got $rawValue")
                }
                "bool"   -> when (rawValue) {
                    is Boolean -> if (rawValue) Value.Boolean.TRUE else Value.Boolean.FALSE
                    else       -> error("Config field '${fieldInfo.name}': expected bool, got $rawValue")
                }
                else -> Value.String(rawValue.toString())
            }
        } else {
            // Use default if present
            val defaultReg = schema.defaultRegMap[i]
                ?: error("Config field '${fieldInfo.name}' is missing from '${schema.path}' and has no default")
            frame.regs[defaultReg] ?: Value.Null
        }
        result[Value.String(fieldInfo.name)] = value
    }

    // Write file if it didn't exist
    if (!file.exists()) {
        val yamlMap = result.entries.associate { (k, v) ->
            (k as Value.String).value to when (v) {
                is Value.String  -> v.value
                is Value.Int     -> v.value
                is Value.Float   -> v.value
                is Value.Double  -> v.value
                is Value.Boolean -> v.value
                else             -> v.toString()
            }
        }
        file.writeText(Yaml().dump(yamlMap))
    }

    frame.regs[dst] = Value.Map(result.toMutableMap())
}
```

- [ ] **Step 16: Run config tests**

```bash
./gradlew test --tests "org.inklang.lang.quillTest.testConfig*"
```
Expected: 2 PASS

- [ ] **Step 17: Run all tests**

```bash
./gradlew test
```
Expected: All PASS

- [ ] **Step 18: Commit**

```bash
git add build.gradle.kts \
        src/main/kotlin/org/quill/lang/Token.kt \
        src/main/kotlin/org/quill/lang/Lexer.kt \
        src/main/kotlin/org/quill/lang/AST.kt \
        src/main/kotlin/org/quill/lang/Parser.kt \
        src/main/kotlin/org/quill/lang/Chunk.kt \
        src/main/kotlin/org/quill/lang/IR.kt \
        src/main/kotlin/org/quill/lang/OpCode.kt \
        src/main/kotlin/org/quill/ast/AstLowerer.kt \
        src/main/kotlin/org/quill/ast/IrCompiler.kt \
        src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt \
        src/main/kotlin/org/quill/ast/VM.kt \
        src/main/kotlin/org/quill/Main.kt \
        src/test/kotlin/org/quill/lang/quillTest.kt
git commit -m "feat: config blocks with YAML/JSON file I/O"
```

---

## Cross-cutting checklist

After implementing all tasks, verify:

- [ ] All 8 new `IrInstr` subclasses have cases in:
  - `AstLowerer.lowerStmt` or `lowerExpr`
  - `LivenessAnalyzer.analyze`
  - `IrCompiler.compile`
  - `IrCompiler.rewriteRegisters`
  - `Main.rewrite`
  - `VM.execute` (via their opcodes)

- [ ] `upvalueIdx` fields in `LoadUpval`/`StoreUpval` are **never** passed to `r()` — only `dst` and `src` registers are remapped

- [ ] `Value.Map` access uses `GET_INDEX`/`SET_INDEX`, not `GET_FIELD`

- [ ] `ClassDescriptor.staticFields` is a class-body property (not a constructor param)

- [ ] Final full test run passes:

```bash
./gradlew test
```
Expected: All PASS, 0 failures
