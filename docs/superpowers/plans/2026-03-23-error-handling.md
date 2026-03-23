# Exception Handling Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `try/catch/finally` and `throw` for the Ink language, covering: tokens, lexer, parser, IR, bytecode compilation, VM execution, CFG, and tests.

**Architecture:** Register-based bytecode VM (16 registers, 32-bit packed instructions). Exception handling uses a per-frame handler stack. Thrown values stored in `CallFrame.thrownValue`. `finally` blocks run on all exit paths via a shared `EXIT_TRY` protocol.

**Tech Stack:** Kotlin/JVM 21, Gradle, kotlin.test, JUnit Platform

---

## Chunk 1: Token, Lexer, Parser

**Goal:** Parse `try/catch/finally` statements and `throw` expressions. No lowering or execution yet.

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/Token.kt`
- Modify: `lang/src/main/kotlin/org/inklang/lang/Lexer.kt`
- Modify: `lang/src/main/kotlin/org/inklang/lang/Parser.kt`
- Test: `lang/src/test/kotlin/org/inklang/ast/VMTest.kt`

---

- [ ] **Step 1: Add exception keywords to TokenType**

Modify: `lang/src/main/kotlin/org/inklang/lang/Token.kt`

Add these entries to the `enum class TokenType` (after `KW_CONFIG`):

```kotlin
KW_TRY,
KW_CATCH,
KW_FINALLY,
KW_THROW,
```

---

- [ ] **Step 2: Lexer recognizes try/catch/finally/throw keywords**

Modify: `lang/src/main/kotlin/org/inklang/lang/Lexer.kt`

In the `lex()` method's keyword switch (after the existing keyword cases), add:

```kotlin
"try"     -> Token(TokenType.KW_TRY, "try", line, column)
"catch"   -> Token(TokenType.KW_CATCH, "catch", line, column)
"finally" -> Token(TokenType.KW_FINALLY, "finally", line, column)
"throw"   -> Token(TokenType.KW_THROW, "throw", line, column)
```

Verify the existing lexer test (`GrammarIRTest`) still passes:
Run: `./gradlew :lang:test --tests "org.inklang.grammar.GrammarIRTest" --console=plain`
Expected: BUILD SUCCESSFUL

---

- [ ] **Step 3: Add `parseTryCatch` to Parser and wire it up**

Modify: `lang/src/main/kotlin/org/inklang/lang/Parser.kt`

**First**, add `KW_TRY` to the statement-start check in `parseStatement()`:

```kotlin
check(TokenType.KW_TRY) -> parseTryCatch()
```

**Second**, add the `parseTryCatch` method. Place it near other statement parsers (e.g., near `parseWhile`):

```kotlin
private fun parseTryCatch(): Stmt {
    consume(TokenType.KW_TRY, "Expected 'try'")
    val tryBody = parseBlock()
    var catchVar: Token? = null
    var catchBody: BlockStmt? = null
    var finallyBody: BlockStmt? = null

    if (match(KW_CATCH)) {
        if (check(TokenType.IDENTIFIER)) {
            catchVar = advance()
        }
        catchBody = parseBlock()
    }

    if (match(KW_FINALLY)) {
        finallyBody = parseBlock()
    }

    if (catchBody == null && finallyBody == null) {
        throw error(peek(), "try block requires at least one of catch or finally")
    }

    return Stmt.TryCatchStmt(tryBody, catchVar, catchBody, finallyBody)
}
```

---

- [ ] **Step 4: Add throw expression parsing**

Modify: `lang/src/main/kotlin/org/inklang/lang/Parser.kt`

In `parseExpression()`, add `KW_THROW` as an atom that starts an expression. Find the atom section (the `when` block that handles individual tokens) and add:

```kotlin
TokenType.KW_THROW -> {
    advance()
    return Expr.ThrowExpr(parseExpression(0))
}
```

Note: `Expr.ThrowExpr` already exists in `AST.kt` — just wire up the parser to create it.

---

- [ ] **Step 5: Write parser tests for try/catch/finally**

Modify: `lang/src/test/kotlin/org/inklang/ast/VMTest.kt`

Add tests that parse but don't execute (use the existing `tokenize` + `Parser` pattern from `testTableBasic`):

```kotlin
@Test
fun testTryCatchParsing() {
    val tokens = tokenize("""
        try {
            throw "error"
        } catch e {
            print(e)
        }
    """.trimIndent())
    val stmts = Parser(tokens).parse()
    assertEquals(1, stmts.size)
    assertTrue(stmts[0] is Stmt.TryCatchStmt)
}

@Test
fun testTryFinallyParsing() {
    val tokens = tokenize("""
        try {
            risky()
        } finally {
            cleanup()
        }
    """.trimIndent())
    val stmts = Parser(tokens).parse()
    assertEquals(1, stmts.size)
    assertTrue(stmts[0] is Stmt.TryCatchStmt)
}

@Test
fun testThrowExpressionParsing() {
    val tokens = tokenize("throw 42")
    val stmts = Parser(tokens).parse()
    assertTrue(stmts[0] is Stmt.ExprStmt)
    val expr = (stmts[0] as Stmt.ExprStmt).expr
    assertTrue(expr is Expr.ThrowExpr)
}

@Test
fun testThrowInIfParsing() {
    val tokens = tokenize("if x { throw 42 } else { ok() }")
    val stmts = Parser(tokens).parse()
    // verify it parses without error
    assertEquals(1, stmts.size)
}
```

Run: `./gradlew :lang:test --tests "org.inklang.ast.VMTest" --console=plain`
Expected: BUILD SUCCESSFUL (or FAIL if lexer isn't wired yet — fix token/lexer first)

---

## Chunk 2: IR Instructions and Bytecode Compilation

**Goal:** Define the IR instructions and emit bytecode for try/catch/finally. Lowering and VM execution are separate chunks.

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/IR.kt`
- Modify: `lang/src/main/kotlin/org/inklang/lang/OpCode.kt`
- Modify: `lang/src/main/kotlin/org/inklang/lang/Chunk.kt`
- Modify: `lang/src/main/kotlin/org/inklang/ast/IrCompiler.kt`

---

- [ ] **Step 6: Add new IR instruction classes**

Modify: `lang/src/main/kotlin/org/inklang/lang/IR.kt`

Add these entries to the `sealed class IrInstr` body (before the closing `}`):

```kotlin
data class TryStart(val finallyLabelIdx: Int?, val catchLabelIdx: Int?) : IrInstr()
object TryEnd : IrInstr()
data class ThrowInstr(val src: Int) : IrInstr()
data class ExitTry(val returnDst: Int) : IrInstr()
```

Note: Using `ThrowInstr` (not `Throw`) because `throw` is a Kotlin keyword and can't be used as a class name.

---

- [ ] **Step 7: Add new opcodes**

Modify: `lang/src/main/kotlin/org/inklang/lang/OpCode.kt`

Add these entries to the `enum class OpCode` (after `CALL_HANDLER(0x2A)`):

```kotlin
TRY_START(0x2B),
TRY_END(0x2C),
THROW(0x2D),
EXIT_TRY(0x2E),
```

---

- [ ] **Step 8: Add labelOffsets field to Chunk**

Modify: `lang/src/main/kotlin/org/inklang/lang/Chunk.kt`

The Chunk needs to store label offsets so the VM can resolve label indices at runtime.

Add a new field and a method:

```kotlin
class Chunk {
    // ... existing fields ...
    val cstTable = mutableListOf<org.inklang.grammar.CstNode>()
    var spillSlotCount: Int = 0
    val labelOffsets = mutableMapOf<Int, Int>()  // IrLabel.id -> bytecode offset

    // ... existing methods ...
}
```

---

- [ ] **Step 9: IrCompiler emits bytecode for new instructions**

Modify: `lang/src/main/kotlin/org/inklang/ast/IrCompiler.kt`

**First**, update the first pass to build a `labelOffsets` map (from `IrLabel.id` to bytecode offset) and store it on the chunk:

```kotlin
// first pass: resolve label positions
val labelOffsets = mutableMapOf<Int, Int>()
var offset = 0
for (instr in result.instrs) {
    when (instr) {
        is IrInstr.Label -> labelOffsets[instr.label.id] = offset
        else -> offset++
    }
}
chunk.labelOffsets.putAll(labelOffsets)
```

**Second**, in the second pass `when (instr)` block, add cases before the closing `}`:

```kotlin
is IrInstr.TryStart -> {
    val finallyPc = instr.finallyLabelIdx?.let { labelOffsets[it] } ?: 0xFFF
    val catchPc = instr.catchLabelIdx?.let { labelOffsets[it] } ?: 0xFFF
    chunk.write(OpCode.TRY_START, dst = finallyPc, imm = catchPc)
}
is IrInstr.TryEnd -> {
    chunk.write(OpCode.TRY_END)
}
is IrInstr.ThrowInstr -> {
    chunk.write(OpCode.THROW, src1 = instr.src)
}
is IrInstr.ExitTry -> {
    chunk.write(OpCode.EXIT_TRY, imm = instr.returnDst)
}
```

Note: `finallyPc` and `catchPc` are bytecode offsets resolved at compile time from label IDs. The 12-bit limit (4095) is sufficient for practical function sizes.

**Third**, update `HandlerRecord` to store `finallyPc` and `catchPc` as bytecode offsets:

```kotlin
data class HandlerRecord(
    val finallyPc: Int?,   // bytecode offset, null if no finally
    val catchPc: Int?     // bytecode offset, null if no catch
)
```

Run: `./gradlew :lang:test --tests "org.inklang.grammar.GrammarIRTest" --console=plain`
Expected: BUILD SUCCESSFUL (confirms no compilation breakage)

---

## Chunk 3: AstLowerer — Lower Try/Catch and Throw

**Goal:** Implement `lowerTryCatch` and `lowerThrow` in AstLowerer, replacing the stub.

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt`

---

- [ ] **Step 10: Implement lowerTryCatch**

Modify: `lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt`

Replace the stub at the `Stmt.TryCatchStmt` case with:

```kotlin
is Stmt.TryCatchStmt -> lowerTryCatch(stmt)
```

Add these new fields and methods to `AstLowerer`:

```kotlin
private var activeFinally: IrLabel? = null

private const val THROWN_VALUE_REG = 15

private fun lowerTryCatch(stmt: Stmt.TryCatchStmt): Unit {
    val catchLabel = stmt.catchBody?.let { freshLabel() }
    val finallyLabel = stmt.finallyBody?.let { freshLabel() }
    val endLabel = freshLabel()

    // Save any enclosing finally; install ours
    val prevFinally = activeFinally
    activeFinally = finallyLabel

    // TRY_START stores label indices (not bytecode offsets) — resolved at emission time
    emit(IrInstr.TryStart(finallyLabel?.id, catchLabel?.id))
    lowerBlock(stmt.body)
    emit(IrInstr.TryEnd())
    emit(IrInstr.Jump(endLabel))

    activeFinally = prevFinally  // finally label no longer active during catch/finally emission

    catchLabel?.let {
        emit(IrInstr.Label(it))
        if (stmt.catchVar != null) {
            val slot = freshReg()
            locals[stmt.catchVar.lexeme] = slot
            emit(IrInstr.Move(slot, THROWN_VALUE_REG))
        }
        lowerBlock(stmt.catchBody!!)
        freeLocal(stmt.catchVar?.lexeme)
    }

    finallyLabel?.let {
        emit(IrInstr.Label(it))
        activeFinally = it  // restore activeFinally for nested try/catch inside finally
        lowerBlock(stmt.finallyBody!!)
        activeFinally = prevFinally
    }

    emit(IrInstr.Label(endLabel))
}
```

**Important:** `freshLabel()` returns an `IrLabel`. Its `.id` field is an `Int` (the label's sequence number). The `IrCompiler`'s first pass collects all label offsets — when it encounters a `TryStart`, it records the label IDs so the second pass can resolve them to bytecode offsets. See Chunk 3 Step 9 for the `labelOffsets` resolution logic.

---

- [ ] **Step 11: Add throw lowering**

In `lowerExpr`, replace the existing stub:

```kotlin
is Expr.ThrowExpr -> {
    val src = lowerExpr(expr.value, THROWN_VALUE_REG)
    emit(IrInstr.ThrowInstr(src))
}
```

Note: `lowerExpr` returns the register holding the expression result. By passing `THROWN_VALUE_REG` as the destination, the throw value lands directly in the dedicated register.

---

- [ ] **Step 12: Add break/continue inside try support**

In `lowerStmt`, update `BreakStmt` and `NextStmt`:

```kotlin
Stmt.BreakStmt -> {
    if (activeFinally != null) {
        emit(IrInstr.ExitTry(0))  // 0 = no return value for break/continue
    }
    emit(IrInstr.Jump(breakLabel ?: error("break outside loop")))
}
Stmt.NextStmt -> {
    if (activeFinally != null) {
        emit(IrInstr.ExitTry(0))
    }
    emit(IrInstr.Jump(nextLabel ?: error("next outside loop")))
}
```

---

## Chunk 4: VM — Handler Stack and Exception Unwinding

**Goal:** Implement the VM execution of the new opcodes: handler stack management, throw propagation, finally execution.

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/ast/VM.kt`

---

- [ ] **Step 13: Add handler record and thrown value to CallFrame**

Modify: `lang/src/main/kotlin/org/inklang/ast/VM.kt`

Update `CallFrame`:

```kotlin
data class CallFrame(
    val chunk: Chunk,
    var ip: Int = 0,
    val regs: Array<Value?> = arrayOfNulls(16),
    var returnDst: Int = 0,
    val argBuffer: ArrayDeque<Value> = ArrayDeque(),
    val spills: Array<Value?> = arrayOfNulls(chunk.spillSlotCount),
    var thrownValue: Value? = null,
    var pendingReturnValue: Value? = null,
    val handlerStack: MutableList<HandlerRecord> = mutableListOf()
) {
    data class HandlerRecord(
        val finallyLabelIdx: Int?,
        val catchLabelIdx: Int?,
        val scopeStartIp: Int
    )
}
```

---

- [ ] **Step 14: Implement unwind function**

Add to the `VM` class:

```kotlin
private fun unwind(frame: CallFrame, frames: ArrayDeque<CallFrame>) {
    if (frame.handlerStack.isNotEmpty()) {
        val record = frame.handlerStack.removeLast()
        if (record.finallyLabelIdx != null) {
            frame.ip = frame.chunk.labelOffsets[record.finallyLabelIdx] ?: error("Unknown finally label: ${record.finallyLabelIdx}")
            return
        }
        if (record.catchLabelIdx != null) {
            frame.ip = frame.chunk.labelOffsets[record.catchLabelIdx] ?: error("Unknown catch label: ${record.catchLabelIdx}")
            return
        }
    }
    frames.removeLast()
    if (frames.isNotEmpty()) {
        unwind(frames.last(), frames)
    } else {
        throw RuntimeException("Uncaught: ${frame.thrownValue}")
    }
}
```

---

- [ ] **Step 15: Add opcode handlers**

In the `execute` method's `when (opcode)` block, add cases before the closing `}`:

```kotlin
OpCode.TRY_START -> {
    val finallyLabelIdx = if (dst != 0xF) dst else null
    val catchLabelIdx = if (imm != 0xFFF) imm else null
    frame.handlerStack.add(CallFrame.HandlerRecord(finallyLabelIdx, catchLabelIdx, frame.ip))
}

OpCode.TRY_END -> {
    if (frame.handlerStack.isNotEmpty()) {
        frame.handlerStack.removeLast()
    }
}

OpCode.THROW -> {
    val thrown = frame.regs[src1]
    frame.thrownValue = thrown
    unwind(frame, frames)
}

OpCode.EXIT_TRY -> {
    val returnVal = frame.regs[src1]
    frame.pendingReturnValue = returnVal
    if (frame.handlerStack.isNotEmpty()) {
        val record = frame.handlerStack.removeLast()
        if (record.finallyLabelIdx != null) {
            frame.ip = frame.chunk.labelOffsets[record.finallyLabelIdx] ?: error("Unknown finally label")
            return
        }
    }
    // No finally — proceed to break/continue/return target
    // (fall through to normal flow after EXIT_TRY)
    frame.pendingReturnValue = null  // no finally means no pending return
}
```

---

- [ ] **Step 16: Update RETURN to handle pending return value**

In the existing `OpCode.RETURN` handler, update to check `pendingReturnValue`:

```kotlin
OpCode.RETURN -> {
    val returnVal = frame.pendingReturnValue ?: frame.regs[src1]
    frame.pendingReturnValue = null
    val retDst = frame.returnDst
    frames.removeLast()
    if (frames.isNotEmpty()) {
        frames.last().regs[retDst] = returnVal
    }
}
```

---

## Chunk 5: CFG and SSA Integration

**Goal:** Ensure the CFG and SSA passes don't break with the new control flow.

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/ast/ControlFlowGraph.kt`
- Modify: `lang/src/main/kotlin/org/inklang/ssa/SsaBuilder.kt`

---

- [ ] **Step 17: Add TRY_START to CFG leaders**

Modify: `lang/src/main/kotlin/org/inklang/ast/ControlFlowGraph.kt`

In `findLeaders()`, add `TRY_START` to the when block:

```kotlin
private fun findLeaders(instrs: List<IrInstr>): Set<Int> {
    val leaders = mutableSetOf(0)

    for ((idx, instr) in instrs.withIndex()) {
        when (instr) {
            is IrInstr.Label -> leaders.add(idx)
            is IrInstr.Jump, is IrInstr.JumpIfFalse, is IrInstr.Return,
            is IrInstr.Break, is IrInstr.Next,
            is IrInstr.TryStart -> {  // ADD THIS LINE
                if (idx + 1 < instrs.size) {
                    leaders.add(idx + 1)
                }
            }
            else -> {}
        }
    }

    return leaders
}
```

---

- [ ] **Step 18: SsaBuilder handles TryStart**

Modify: `lang/src/main/kotlin/org/inklang/ssa/SsaBuilder.kt`

Find where basic blocks are split and ensure `TryStart` ends the current block. The exact location depends on the SsaBuilder implementation — look for where `Jump`, `Return`, `Break`, `Next` cause a block split, and add `TryStart` there.

Run: `./gradlew :lang:test --tests "org.inklang.ast.SsaDebugTest" --console=plain`
Expected: BUILD SUCCESSFUL

---

## Chunk 6: End-to-End Tests

**Goal:** Verify the full implementation works correctly.

**Files:**
- Modify: `lang/src/test/kotlin/org/inklang/ast/VMTest.kt`

---

- [ ] **Step 19: Basic try-catch test**

```kotlin
@Test
fun testTryCatchBasic() {
    val output = compileAndRun("""
        try {
            throw "oops"
        } catch e {
            print(e)
        }
    """.trimIndent())
    assertEquals(listOf("oops"), output)
}
```

---

- [ ] **Step 20: Throw any value**

```kotlin
@Test
fun testThrowInt() {
    val output = compileAndRun("""
        try {
            throw 42
        } catch e {
            print(e)
        }
    """.trimIndent())
    assertEquals(listOf("42"), output)
}
```

---

- [ ] **Step 21: Finally always runs**

```kotlin
@Test
fun testFinallyRuns() {
    val output = compileAndRun("""
        let x = 0
        try {
            x = 1
        } finally {
            x = x + 10
        }
        print(x)
    """.trimIndent())
    assertEquals(listOf("11"), output)
}
```

---

- [ ] **Step 22: Finally wins when it throws**

```kotlin
@Test
fun testFinallyThrowsWins() {
    val exception = assertFailsWith<RuntimeException> {
        compileAndRun("""
            try {
                throw "original"
            } catch e {
                print("caught: ${'$'}e")
            } finally {
                throw "from finally"
            }
        """.trimIndent())
    }
    assertTrue(exception.message?.contains("from finally") == true)
}
```

---

- [ ] **Step 23: Nested try with finally unwinding order**

```kotlin
@Test
fun testNestedTryFinallyUnwindOrder() {
    val output = compileAndRun("""
        try {
            try {
                throw "inner"
            } finally {
                print("inner finally")
            }
        } catch e {
            print("caught: ${'$'}e")
        }
    """.trimIndent())
    assertEquals(listOf("inner finally", "caught: inner"), output)
}
```

---

- [ ] **Step 24: Return inside try with finally**

```kotlin
@Test
fun testReturnInsideTryFinally() {
    val output = compileAndRun("""
        fn foo() {
            try {
                return 42
            } finally {
                print("cleanup")
            }
        }
        print(foo())
    """.trimIndent())
    assertEquals(listOf("cleanup", "42"), output)
}
```

---

- [ ] **Step 25: Break inside try with finally**

```kotlin
@Test
fun testBreakInsideTry() {
    val output = compileAndRun("""
        var result = 0
        for i in [1, 2] {
            try {
                if i == 1 { break }
                result = result + 100
            } finally {
                result = result + 10
            }
        }
        print(result)
    """.trimIndent())
    // i=0: no break, finally: result=10
    // i=1: break triggers finally: result=20, loop exits
    assertEquals(listOf("20"), output)
}
```

---

- [ ] **Step 26: Uncaught throw**

```kotlin
@Test
fun testUncaughtThrow() {
    val exception = assertFailsWith<RuntimeException> {
        compileAndRun("""throw "top level" """)
    }
    assertTrue(exception.message?.contains("Uncaught") == true)
}
```

---

- [ ] **Step 27: Catch with no variable**

```kotlin
@Test
fun testCatchNoVariable() {
    val output = compileAndRun("""
        try {
            throw 123
        } catch {
            print("caught something")
        }
    """.trimIndent())
    assertEquals(listOf("caught something"), output)
}
```

---

- [ ] **Step 28: Try without catch (finally only)**

```kotlin
@Test
fun testTryFinallyOnly() {
    val output = compileAndRun("""
        var x = 0
        try {
            x = 5
        } finally {
            print("finally")
        }
        print(x)
    """.trimIndent())
    assertEquals(listOf("finally", "5"), output)
}
```

---

Run all tests: `./gradlew :lang:test --console=plain`
Expected: All new tests pass. GrammarIRTest still passes.

---

## Chunk 7: Integration and Verification

---

- [ ] **Step 29: Run full test suite**

Run: `./gradlew :lang:test --console=plain`
Expected: All tests pass (except possibly pre-existing failures in VMTest — `testFunctionWithMultipleStatements` and `testTupleIteration` are pre-existing)

---

- [ ] **Step 30: Commit chunk 1-7**

```bash
git add -A
git commit -m "feat: implement try/catch/finally + throw end-to-end

- Token, lexer, parser for try/catch/finally/throw
- IR: TryStart, TryEnd, ThrowInstr, ExitTry
- Bytecode compilation with label index resolution
- AstLowerer: lowerTryCatch, lowerThrow, break/continue in try
- VM: per-frame handler stack, unwind, finally execution
- CFG: TryStart as basic block leader
- Full test suite: basic, nested, return/break/continue, finally wins
"
```
