# Async/Await Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement async/await/spawn for Inklang with VM-as-event-loop model

**Architecture:** VM runs on virtual thread as event loop. await parks frames in suspendedFrames map, event loop waits on completionQueue. async fn bodies stay as bytecode, launched via ASYNC_CALL on virtual threads.

**Tech Stack:** Kotlin 2.2.21, JVM 21, Project Loom virtual threads, CompletableFuture

---

## File Map

| File | Responsibility |
|------|---------------|
| `ink/src/main/kotlin/org/inklang/lang/Token.kt` | TokenType enum - add KW_ASYNC, KW_AWAIT, KW_SPAWN, KW_VIRTUAL |
| `ink/src/main/kotlin/org/inklang/lang/AST.kt` | AST nodes - add isAsync to FuncStmt/LambdaExpr, AwaitExpr, SpawnExpr |
| `ink/src/main/kotlin/org/inklang/lang/Lexer.kt` | Tokenizer - add keyword recognition |
| `ink/src/main/kotlin/org/inklang/lang/Parser.kt` | Parser - parse async fn, await, spawn |
| `ink/src/main/kotlin/org/inklang/lang/Value.kt` | Value sealed class - add Value.Task |
| `ink/src/main/kotlin/org/inklang/lang/OpCode.kt` | OpCode enum - add ASYNC_CALL, AWAIT, SPAWN, SPAWN_VIRTUAL |
| `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt` | AST→IR lowering - lower AwaitExpr, SpawnExpr, async fn |
| `ink/src/main/kotlin/org/inklang/ast/IrCompiler.kt` | IR→bytecode - emit new opcodes |
| `ink/src/main/kotlin/org/inklang/ast/VM.kt` | VM execution - implement event loop, new opcodes |
| `ink/src/main/kotlin/org/inklang/AnnotationChecker.kt` | Type checking - validate await/spawn usage |

---

## Chunk 1: Token and Lexer

### Task 1.1: Add async keywords to TokenType

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Token.kt:38-42`

- [ ] **Step 1: Add token types**

```kotlin
// In TokenType enum, after KW_EVENT:
KW_ASYNC,
KW_AWAIT,
KW_SPAWN,
KW_VIRTUAL,
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Token.kt
git commit -m "feat: add async/await/spawn tokens"
```

### Task 1.2: Add keywords to Lexer

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Lexer.kt:59-61`

- [ ] **Step 1: Add keywords map entries**

```kotlin
"async" to TokenType.KW_ASYNC,
"await" to TokenType.KW_AWAIT,
"spawn" to TokenType.KW_SPAWN,
"virtual" to TokenType.KW_VIRTUAL,
```

Add these to the `keywords` map in the Lexer companion object.

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Lexer.kt
git commit -m "feat: recognize async/await/spawn/virtual keywords"
```

---

## Chunk 2: AST Changes

### Task 2.1: Add Value.Task

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Value.kt:106-112`

- [ ] **Step 1: Add Value.Task variant**

```kotlin
/** A handle to an in-progress async operation */
data class Task(val deferred: java.util.concurrent.CompletableFuture<Value>) : Value()
```

Add this as the last variant in the `Value` sealed class.

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Value.kt
git commit -m "feat: add Value.Task for async operations"
```

### Task 2.2: Add isAsync to FuncStmt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/AST.kt:128-134`

- [ ] **Step 1: Add isAsync to FuncStmt**

```kotlin
data class FuncStmt(
    val annotations: List<Expr.AnnotationExpr>,
    val name: Token,
    val params: List<Param>,
    val returnType: Token?,
    val body: BlockStmt,
    val isAsync: Boolean = false  // NEW
) : Stmt()
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/AST.kt
git commit -m "feat: add isAsync flag to FuncStmt"
```

### Task 2.3: Add isAsync to LambdaExpr and new AST nodes

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/AST.kt:73`, `ink/src/main/kotlin/org/inklang/lang/AST.kt:93`

- [ ] **Step 1: Add isAsync to LambdaExpr**

```kotlin
data class LambdaExpr(
    val params: List<Param>,
    val body: Stmt.BlockStmt,
    val isAsync: Boolean = false  // NEW
) : Expr()
```

- [ ] **Step 2: Add AwaitExpr and SpawnExpr**

Add these new data classes to the `Expr` sealed class:

```kotlin
/** await expression - unary prefix operator */
data class AwaitExpr(val expr: Expr) : Expr()

/** spawn [virtual] expression */
data class SpawnExpr(val expr: Expr, val virtual: Boolean = false) : Expr()
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/AST.kt
git commit -m "feat: add AwaitExpr, SpawnExpr, isAsync to LambdaExpr"
```

---

## Chunk 3: Parser Changes

### Task 3.1: Parse async fn declarations

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt:249-296`

- [ ] **Step 1: Modify parseFunc to handle async**

Change the `parseFunc` function to accept optional `isAsync` parameter and check for `KW_ASYNC` before `KW_FN`:

In `parseStmt()`, change:
```kotlin
check(TokenType.KW_FN) -> parseFunc(leadingAnnotations)
```

To:
```kotlin
check(TokenType.KW_ASYNC) || check(TokenType.KW_FN) -> parseFunc(leadingAnnotations)
```

In `parseFunc()`:
```kotlin
private fun parseFunc(leadingAnnotations: List<Expr.AnnotationExpr> = emptyList(), isAsync: Boolean = false): Stmt {
    // Check for async modifier
    val asyncModifier = if (check(TokenType.KW_ASYNC)) {
        advance()
        true
    } else {
        isAsync
    }
    consume(TokenType.KW_FN, "Expected 'fn'")
    // ... rest unchanged, pass asyncModifier to FuncStmt
    return Stmt.FuncStmt(annotations, name, params, returnType, body, asyncModifier)
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Parser.kt
git commit -m "feat: parse async fn declarations"
```

### Task 3.2: Parse await expression

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt:486-625`

- [ ] **Step 1: Add KW_AWAIT to UNARY_OPS in parsePrefix**

In `parsePrefix()`, add to the `when` block:

```kotlin
TokenType.KW_AWAIT -> Expr.AwaitExpr(parseExpression(90))  // await binds tightly
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Parser.kt
git commit -m "feat: parse await expressions"
```

### Task 3.3: Parse spawn and spawn virtual

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt:486-625`

- [ ] **Step 1: Add spawn parsing to parsePrefix**

In `parsePrefix()`, add to the `when` block:

```kotlin
TokenType.KW_SPAWN -> {
    val isVirtual = match(TokenType.KW_VIRTUAL)
    Expr.SpawnExpr(parseExpression(0), isVirtual)
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Parser.kt
git commit -m "feat: parse spawn and spawn virtual expressions"
```

### Task 3.4: Parse async lambda

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt:560-582`

- [ ] **Step 1: Handle async in lambda parsing**

In the `isLambdaAhead()` and lambda parsing section, check for async modifier:

```kotlin
TokenType.L_PAREN -> {
    // Could be: grouped expression, lambda, or tuple
    // Lambda: async? (params) -> { body }
    if (isLambdaAhead()) {
        val isAsync = match(TokenType.KW_ASYNC)  // NEW - check for async before params
        // ... rest of lambda parsing, pass isAsync to LambdaExpr
        Expr.LambdaExpr(params, body, isAsync)
    }
    // ...
}
```

- [ ] **Step 2: Update isLambdaAhead to handle async**

```kotlin
private fun isLambdaAhead(): Boolean {
    // Handle async lambda: async (params) -> { body }
    if (check(TokenType.KW_ASYNC) && checkAhead(1, TokenType.L_PAREN)) {
        return true
    }
    // ... existing logic
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Parser.kt
git commit -m "feat: parse async lambda expressions"
```

---

## Chunk 4: OpCode and IR Changes

### Task 4.1: Add new opcodes

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/OpCode.kt`

- [ ] **Step 1: Add new opcodes after REGISTER_EVENT**

```kotlin
ASYNC_CALL(0x2C),     // Launch async function, store Task in dst
AWAIT(0x2D),          // Suspend frame, wait for Task, store result in dst
SPAWN(0x2E),          // Run on OS thread pool, store Task in dst
SPAWN_VIRTUAL(0x2F),  // Run on virtual thread, store Task in dst
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/OpCode.kt
git commit -m "feat: add ASYNC_CALL, AWAIT, SPAWN, SPAWN_VIRTUAL opcodes"
```

### Task 4.2: Lower AwaitExpr and SpawnExpr in AstLowerer

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt`

- [ ] **Step 1: Add IR instruction types for async**

Add to `sealed class Instr`:
```kotlin
data class AwaitInstr(val result: Temp, val task: Temp) : Instr()
data class SpawnInstr(val result: Temp, val func: Temp, val virtual: Boolean) : Instr()
data class AsyncCallInstr(val result: Temp, val func: Temp) : Instr()
```

- [ ] **Step 2: Add lowering for AwaitExpr and SpawnExpr**

In the `lower` function, add cases:

```kotlin
is Expr.AwaitExpr -> {
    val task = lowerExpr(expr.expr)
    val result = newTemp()
    currentBlock.add(AwaitInstr(result, task.temp))
    ExprResult(result, exprTypes[expr] ?: Type.TASK)
}

is Expr.SpawnExpr -> {
    val func = lowerExpr(expr.expr)
    val result = newTemp()
    currentBlock.add(SpawnInstr(result, func.temp, expr.virtual))
    ExprResult(result, Type.TASK)
}
```

- [ ] **Step 3: Handle async function call (ASYNC_CALL)**

For `Expr.CallExpr` where callee is an async function, lower to `AsyncCallInstr`:
- Check if the function being called is async (needs function metadata tracking)
- For now, emit ASYNC_CALL for any function call and ASYNC_CALL will be selected at call time based on function type

- [ ] **Step 4: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt
git commit -m "feat: lower AwaitExpr and SpawnExpr to IR"
```

### Task 4.3: Emit new opcodes in IrCompiler

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/IrCompiler.kt`

- [ ] **Step 1: Add opcode emission for new IR instructions**

In the `compileInstr` method, add cases:

```kotlin
is AwaitInstr -> emitAsyncAwait(result.reg, task.reg)
is SpawnInstr -> emitSpawn(result.reg, func.reg, virtual)
is AsyncCallInstr -> emitAsyncCall(result.reg, func.reg)
```

Add helper methods:
```kotlin
private fun emitAsyncAwait(dst: Int, src: Int) {
    emit(OpCode.AWAIT, dst, src, 0, 0)
}

private fun emitSpawn(dst: Int, src: Int, virtual: Boolean) {
    emit(if (virtual) OpCode.SPAWN_VIRTUAL else OpCode.SPAWN, dst, src, 0, 0)
}

private fun emitAsyncCall(dst: Int, src: Int) {
    emit(OpCode.ASYNC_CALL, dst, src, 0, 0)
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/IrCompiler.kt
git commit -m "feat: emit ASYNC_CALL, AWAIT, SPAWN, SPAWN_VIRTUAL opcodes"
```

---

## Chunk 5: VM Integration

### Task 5.1: Add async infrastructure to VM

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/VM.kt`

- [ ] **Step 1: Add imports and async fields**

```kotlin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

class VM {
    // ... existing fields ...
    private val suspendedFrames = mutableMapOf<CompletableFuture<Value>, CallFrame>()
    private val completionQueue = LinkedBlockingQueue<CompletableFuture<Value>>()
    private val executorService = java.util.concurrent.Executors.newCachedThreadPool()
```

- [ ] **Step 2: Add Value.Task to globals check in IS_TYPE**

Update `IS_TYPE` opcode handling to include `Value.Task`:
```kotlin
is Value.Task -> typeName == "Task"
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/VM.kt
git commit -m "feat: add async infrastructure to VM"
```

### Task 5.2: Implement AWAIT opcode

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/VM.kt`

- [ ] **Step 1: Add AWAIT handling in execute loop**

Add new case in the `when (opcode)` block:

```kotlin
OpCode.AWAIT -> {
    val task = frame.regs[src1]
    if (task == null || task == Value.Null) {
        error("Cannot await null task")
    }
    val future = (task as Value.Task).deferred
    suspendedFrames[future] = frame
    // Register completion callback
    future.whenComplete { _, _ ->
        completionQueue.put(future)
    }
    frames.removeLast()
    // Exit inner loop - event loop takes over
    break
}
```

- [ ] **Step 2: Add event loop continuation after inner loop**

After the inner `while (frames.isNotEmpty())` loop ends, add:

```kotlin
// Event loop: wait for suspended frames to complete
if (frames.isEmpty() && suspendedFrames.isNotEmpty()) {
    val completed = completionQueue.take()
    val resumedFrame = suspendedFrames.remove(completed)!!
    val result = try {
        completed.join()
    } catch (e: Throwable) {
        Value.String(e.message ?: "Unknown error")
    }
    resumedFrame.regs[resumedFrame.returnDst] = result
    frames.addLast(resumedFrame)
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/VM.kt
git commit -m "feat: implement AWAIT opcode with event loop"
```

### Task 5.3: Implement SPAWN and SPAWN_VIRTUAL opcodes

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/VM.kt`

- [ ] **Step 1: Add SPAWN handling**

```kotlin
OpCode.SPAWN -> {
    val func = frame.regs[src1] as? Value.Function
        ?: error("spawn requires a function")
    val task = CompletableFuture<Value>()

    executorService.submit {
        try {
            val result = runAsyncFunction(func)
            task.complete(result)
        } catch (e: Throwable) {
            task.completeExceptionally(e)
        }
    }

    frame.regs[dst] = Value.Task(task)
}
```

- [ ] **Step 2: Add SPAWN_VIRTUAL handling**

```kotlin
OpCode.SPAWN_VIRTUAL -> {
    val func = frame.regs[src1] as? Value.Function
        ?: error("spawn virtual requires a function")
    val task = CompletableFuture<Value>()

    Thread.startVirtualThread {
        try {
            val result = runAsyncFunction(func)
            task.complete(result)
        } catch (e: Throwable) {
            task.completeExceptionally(e)
        }
    }

    frame.regs[dst] = Value.Task(task)
}
```

- [ ] **Step 3: Add runAsyncFunction helper**

```kotlin
private fun runAsyncFunction(func: Value.Function): Value {
    val frames = ArrayDeque<CallFrame>()
    frames.addLast(CallFrame(func.chunk))

    while (frames.isNotEmpty()) {
        val frame = frames.last()
        if (frame.ip >= frame.chunk.code.size) {
            frames.removeLast()
            continue
        }

        val word = frame.chunk.code[frame.ip++]
        val opcode = OpCode.entries.find { it.code == (word and 0xFF).toByte() }
            ?: error("Unknown opcode: ${word and 0xFF}")
        val dst = (word shr 8) and 0x0F
        val src1 = (word shr 12) and 0x0F
        val src2 = (word shr 16) and 0x0F
        val imm = (word shr 20) and 0xFFF

        // Execute instruction - delegate to existing VM logic
        // For now, implement a minimal set that supports async functions
        when (opcode) {
            OpCode.AWAIT -> {
                val task = frame.regs[src1] as? Value.Task
                    ?: error("Cannot await non-Task")
                // Block this virtual thread (cheap)
                try {
                    frame.regs[dst] = task.deferred.join()
                } catch (e: Throwable) {
                    frame.regs[dst] = Value.String(e.message ?: "Unknown error")
                }
            }
            OpCode.RETURN -> {
                val returnVal = frame.regs[src1]
                frames.removeLast()
                if (frames.isEmpty()) {
                    return returnVal ?: Value.Null
                }
                frames.last().regs[frame.returnDst] = returnVal
            }
            OpCode.LOAD_IMM -> frame.regs[dst] = frame.chunk.constants[imm]
            OpCode.LOAD_GLOBAL -> frame.regs[dst] = globals[frame.chunk.strings[imm]]
                ?: error("Undefined global: ${frame.chunk.strings[imm]}")
            OpCode.MOVE -> frame.regs[dst] = frame.regs[src1]
            // Add more opcodes as needed for basic async functions
            else -> {
                // For now, unsupported in async - error
                error("Opcode $opcode not supported in async function")
            }
        }
    }
    return Value.Null
}
```

- [ ] **Step 4: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/VM.kt
git commit -m "feat: implement SPAWN and SPAWN_VIRTUAL opcodes"
```

### Task 5.4: Implement ASYNC_CALL opcode

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/VM.kt`

- [ ] **Step 1: Add ASYNC_CALL handling**

```kotlin
OpCode.ASYNC_CALL -> {
    val func = frame.regs[src1] as? Value.Function
        ?: error("async call requires a function")
    val task = CompletableFuture<Value>()

    Thread.startVirtualThread {
        try {
            val result = runAsyncFunction(func)
            task.complete(result)
        } catch (e: Throwable) {
            task.completeExceptionally(e)
        }
    }

    frame.regs[dst] = Value.Task(task)
    // VM continues to next instruction while async function runs
}
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/VM.kt
git commit -m "feat: implement ASYNC_CALL opcode"
```

---

## Chunk 6: Type Checking

### Task 6.1: Add await validation to AnnotationChecker

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/AnnotationChecker.kt`

- [ ] **Step 1: Add await type check**

In the `check` method (or appropriate validation method), add:

```kotlin
is Expr.AwaitExpr -> {
    // Check that expr is Task type
    val exprType = getType(expr.expr)
    if (exprType !is Type.Task) {
        error("await requires Task type, got $exprType")
    }
}
```

- [ ] **Step 2: Add spawn validation**

```kotlin
is Expr.SpawnExpr -> {
    // Check that expr is callable (Function type)
    val exprType = getType(expr.expr)
    if (exprType !is Type.Function) {
        error("spawn requires a function")
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :ink:compileKotlin --no-daemon -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/AnnotationChecker.kt
git commit -m "feat: validate await and spawn expressions"
```

---

## Chunk 7: Basic Testing

### Task 7.1: Write basic async test

**Files:**
- Modify: `ink/src/test/kotlin/org/inklang/InkCompilerTest.kt`

- [ ] **Step 1: Add async parsing test**

```kotlin
@Test
fun `test async fn parsing`() {
    val source = """
        async fn test() -> int {
            return 42
        }
    """.trimIndent()
    val result = InkCompiler.compile(source)
    assertNotNull(result.script)
}
```

- [ ] **Step 2: Add await parsing test**

```kotlin
@Test
fun `test await expression`() {
    val source = """
        fn getTask() -> Task<int> { return null }
        fn main() {
            let x = await getTask()
        }
    """.trimIndent()
    val result = InkCompiler.compile(source)
    assertNotNull(result.script)
}
```

- [ ] **Step 3: Add spawn parsing test**

```kotlin
@Test
fun `test spawn expression`() {
    val source = """
        fn work() -> int { return 1 }
        fn main() {
            let t = spawn work()
        }
    """.trimIndent()
    val result = InkCompiler.compile(source)
    assertNotNull(result.script)
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :ink:test --tests "org.inklang.InkCompilerTest" --no-daemon`
Expected: Tests pass

- [ ] **Step 5: Commit**

```bash
git add ink/src/test/kotlin/org/inklang/InkCompilerTest.kt
git commit -m "test: add async/await/spawn parsing tests"
```

---

## Verification

After all chunks are complete, verify the implementation:

```bash
./gradlew :ink:test --no-daemon
```

Expected: All tests pass

---

## Notes

- The `runAsyncFunction` helper in VM is minimal - it only supports a subset of opcodes needed for basic async functions. Full opcode support should be added incrementally as needed.
- The type system uses a simple `Type` sealed class - `Type.Task` and `Type.Function` need to be added.
- For MVP, the async function call lowering (ASYNC_CALL) should check if the function has `isAsync=true` flag and emit ASYNC_CALL accordingly.
