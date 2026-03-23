# Ink Exception Handling — Design

> **Spec status:** Draft
> **Date:** 2026-03-23
> **Pipeline stage:** Lexer → Parser → AstLowerer → IR → VM

## 1. Language Syntax (from spec)

```ink
try {
    let result = riskyOperation()
} catch e {
    print("Error: ${e}")
} finally {
    cleanup()
}
```

- `finally` block: always executes on normal or exceptional exit. Optional.
- **"Finally wins" for throws**: if finally throws during unwinding, finally's exception replaces the original and propagates outward following normal exception propagation rules. The original exception is lost.
- `catch` block: handles thrown values. The variable (`e`) binds to the thrown value. Catch variable is optional — `catch { }` is valid.
- `finally` block: always executes on normal or exceptional exit. Optional.
- At least one of `catch` or `finally` must be present after `try`
- `throw` is an expression — it can appear anywhere

```ink
throw "something went wrong"
throw 404
throw ErrorInfo("not found", 404)
```

- Any value can be thrown: strings, numbers, class instances, etc.
- `throw` unwinds the call stack until a matching `try/catch` is found
- Uncaught throws are runtime errors

## 2. Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Register snapshotting on throw | None | No resume semantics — throw is terminal |
| Finally unwinding order | All finallys, innermost to outermost | Consistent with Java/Lua |
| Handler info location | Per-frame handler stack | Cache-friendly, explicit, WASM-like |
| Thrown value storage | `CallFrame.thrownValue` field | Clean, one field per frame |
| Normal try exit | Handler stack pop → finally | Shared mechanism with throw path |
| Return inside try | `EXIT_TRY` → finally → return | Single exit protocol |
| Finally throws/returns | Finally wins | Simplest; explicit rethrow if desired |

## 3. Token and Lexer Changes

### TokenType additions

```kotlin
// In TokenType.kt
KW_TRY,
KW_CATCH,
KW_FINALLY,
KW_THROW,
```

### Lexer changes

In `Lexer.kt`, add keyword recognition:

```kotlin
"try"     -> Token(TokenType.KW_TRY, ...)
"catch"   -> Token(TokenType.KW_CATCH, ...)
"finally" -> Token(TokenType.KW_FINALLY, ...)
"throw"   -> Token(TokenType.KW_THROW, ...)
```

## 4. Parser Changes

### Grammar (from spec)

```
tryCatchStmt = "try" block ( "catch" IDENT? block ("finally" block)?
                            | "finally" block )
```

Parse rule in `Parser.kt`:

```kotlin
private fun parseTryCatch(): Stmt {
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

### Throw expression

```
throwExpr = "throw" expr
```

```kotlin
private fun parseThrow(): Expr {
    advance() // consume 'throw'
    return Expr.ThrowExpr(parseExpression(0))
}
```

Add `KW_THROW` to expression-starting tokens in `parseExpression()` atom section.

## 5. AST Changes

### Existing (already defined in AST.kt)

```kotlin
data class ThrowExpr(val value: Expr) : Expr()

data class TryCatchStmt(
    val body: BlockStmt,
    val catchVar: Token?,       // null if catch has no variable
    val catchBody: BlockStmt?,  // null if no catch clause
    val finallyBody: BlockStmt? // null if no finally clause
) : Stmt()
```

No AST changes needed — these types already exist.

## 6. IR Changes

### New IrInstr subclasses

In `IR.kt`, add:

```kotlin
TRY_START(finallyLabelIdx: Int?, catchLabelIdx: Int?)  // Push handler record, enter try scope
TRY_END()                                      // Pop handler record (used for normal try exit AND catch/finally cleanup)
THROW(src: Int)                                // Store thrown value, unwind via handler
EXIT_TRY(returnDst: Int)                       // Store return value, pop handler, jump finally
```

### IR byte encoding

The existing 32-bit instruction format uses 4 bits each for dst/src1/src2 and 12 bits for immediate. All new instructions are zero-arg or single-immediate, fitting the existing format.

**PC overflow handling:** `TRY_START` stores a *label index* (position in `chunk.labels`) rather than a raw bytecode PC. The label index is bounded by the number of labels in the chunk (at most the number of basic blocks), which is far smaller than bytecode size and well within 12 bits (max 4095 labels). The `IrCompiler` resolves label indices to actual bytecode PCs during emission.

## 7. AstLowerer Changes

### lowerTryCatch (new method)

Register 15 (`THROWN_VALUE_REG`) is the dedicated register for holding the thrown value during catch binding. Register allocation ensures no user code uses register 15 normally.

```kotlin
private const val THROWN_VALUE_REG = 15

private fun lowerTryCatch(stmt: Stmt.TryCatchStmt): Sequence<IrInstr> {
    val tryBody = lowerBlock(stmt.body)
    val catchBody = stmt.catchBody?.let { lowerBlock(it) }
    val finallyBody = stmt.finallyBody?.let { lowerBlock(it) }

    val catchLabel = freshLabel("catch")
    val finallyLabel = freshLabel("finally")
    val endLabel = freshLabel("end_try")

    // Store label indices (not raw PCs) — resolved at compile time
    val finallyLabelIdx = finallyLabel?.let { addLabel(it) }
    val catchLabelIdx = catchBody?.let { addLabel(catchLabel) }

    return sequence {
        yield(TRY_START(finallyLabelIdx, catchLabelIdx))
        yieldAll(tryBody)
        yield(TRY_END())
        yield(IRInstr.Jump(endLabel))
        catchLabel?.let { yield(IRInstr.Label(it)) }
        catchBody?.let { body ->
            // Bind thrown value to catch variable — read from register 15
            if (stmt.catchVar != null) {
                val slot = allocLocal(stmt.catchVar.lexeme)
                yield(MOVE(slot, THROWN_VALUE_REG))
            }
            yieldAll(body)
            freeLocal(slot)  // catch variable scope ends after catch body
        }
        finallyLabel?.let { yield(IRInstr.Label(it)) }
        finallyBody?.let { yieldAll(it) }
        yield(IRInstr.Label(endLabel))
    }
}
```

### lowerThrow

```kotlin
is Expr.ThrowExpr -> {
    lowerExpr(expr.value, dst)
    yield(THROW(dst))
}
```

## 8. VM Changes

### CallFrame additions

```kotlin
data class CallFrame(
    val chunk: Chunk,
    var ip: Int = 0,
    val regs: Array<Value?> = arrayOfNulls(16),
    var returnDst: Int = 0,
    val argBuffer: ArrayDeque<Value> = ArrayDeque(),
    val spills: Array<Value?> = arrayOfNulls(chunk.spillSlotCount),
    var thrownValue: Value? = null,                       // ← new
    var pendingReturnValue: Value? = null,                 // ← new: return value saved across finally
    val handlerStack: MutableList<HandlerRecord> = mutableListOf()  // ← new
) {
    data class HandlerRecord(
        val finallyLabelIdx: Int?,  // index into chunk.labels — resolved at compile time
        val catchLabelIdx: Int?,    // null if no catch clause
        val scopeStartIp: Int
    )
}
```

### New opcodes

```kotlin
TRY_START(0x2B),   // dst = finally label index (0xF = none), imm = catch label index (0xFFF = none)
TRY_END(0x2C),      // pop handler record (shared: normal try exit + catch/finally cleanup)
THROW(0x2D),        // src1 = register containing thrown value
EXIT_TRY(0x2E),     // imm = returnDst register index
```

### THROW opcode handler

```kotlin
OpCode.THROW -> {
    val thrown = frame.regs[src1]
    frame.thrownValue = thrown
    unwind(frame, frames)
}

private fun unwind(frame: CallFrame, frames: ArrayDeque<CallFrame>) {
    if (frame.handlerStack.isNotEmpty()) {
        val record = frame.handlerStack.removeLast()
        if (record.finallyLabelIdx != null) {
            // Jump to finally. After finally runs (if it completes normally,
            // RETURN checks pendingReturnValue and proceeds). If finally throws,
            // THROW is called again with the new thrown value — unwind continues
            // outward with the new exception (the "finally wins" rule).
            frame.ip = frame.chunk.labels[record.finallyLabelIdx]
            return // Execute finally
        }
        if (record.catchLabelIdx != null) {
            frame.ip = frame.chunk.labels[record.catchLabelIdx]
            return // Jump to catch
        }
    }
    // No handler in this frame — unwind to caller
    frames.removeLast()
    if (frames.isNotEmpty()) {
        unwind(frames.last(), frames)  // tail call
    } else {
        error("Uncaught: ${frame.thrownValue}")
    }
}
```
```

### TRY_START opcode handler

```kotlin
OpCode.TRY_START -> {
    val finallyLabelIdx = if (dst != 0xF) dst else null   // 0xF = sentinel = no finally
    val catchLabelIdx = if (imm != 0xFFF) imm else null  // 0xFFF = sentinel = no catch
    frame.handlerStack.add(CallFrame.HandlerRecord(finallyLabelIdx, catchLabelIdx, frame.ip))
}
```

### TRY_END / POP_TRY opcode handler

```kotlin
OpCode.TRY_END,
OpCode.POP_TRY -> {
    if (frame.handlerStack.isNotEmpty()) {
        frame.handlerStack.removeLast()
    }
}
```

### EXIT_TRY opcode handler

```kotlin
OpCode.EXIT_TRY -> {
    val returnVal = frame.regs[src1]
    val returnDst = imm
    // Store return value temporarily while finally runs
    frame.pendingReturnValue = returnVal
    // Pop handler and jump to finally
    if (frame.handlerStack.isNotEmpty()) {
        val record = frame.handlerStack.removeLast()
        if (record.finallyLabelIdx != null) {
            frame.ip = frame.chunk.labels[record.finallyLabelIdx]
            return
        }
    }
    // No finally — execute return immediately
    frames.removeLast()
    if (frames.isNotEmpty()) {
        frames.last().regs[returnDst] = returnVal
    }
}
```

### RETURN opcode update

Update RETURN to check for pending return from finally unwinding:

```kotlin
OpCode.RETURN -> {
    val returnVal = frame.pendingReturnValue ?: frame.regs[src1]
    frames.removeLast()
    if (frames.isNotEmpty()) {
        frames.last().regs[frame.returnDst] = returnVal
    }
}
```

### IrCompiler bytecode emission

In `IrCompiler.kt`, add compile cases for all new IR instructions:

```kotlin
is IrInstr.TRY_START -> {
    val word = encodeOp(OpCode.TRY_START, dst = finallyLabelIdx ?: 0xF, imm = catchLabelIdx ?: 0xFFF)
    emit(word)
}
is IrInstr.TRY_END -> {
    emit(encodeOp(OpCode.TRY_END))
}
is IrInstr.THROW -> {
    emit(encodeOp(OpCode.THROW, src1 = src))
}
is IrInstr.EXIT_TRY -> {
    emit(encodeOp(OpCode.EXIT_TRY, imm = returnDst))
}
```

The `encodeOp` helper packs opcode + dst + src1 + src2 + imm into a 32-bit word using the existing format.

## 9. SSA, CFG, and Optimization

### ControlFlowGraph changes

In `findLeaders()`, add `TRY_START` alongside `Label`, `Jump`, `JumpIfFalse`, `Return`, `Break`, `Next` as basic block leaders:

```kotlin
private fun findLeaders(instrs: List<IrInstr>): List<Int> {
    val leaders = mutableSetOf(0) // entry
    for ((i, instr) in instrs.withIndex()) {
        when (instr) {
            is IrInstr.Label,
            is IrInstr.Jump,
            is IrInstr.JumpIfFalse,
            is IrInstr.Return,
            is IrInstr.Break,
            is IrInstr.Next,
            is IrInstr.TRY_START -> leaders.add(i)
            else -> {}
        }
    }
    return leaders.sorted()
}
```

### SSA handling

In `SsaBuilder`, basic blocks should not span `TRY_START` boundaries. When a `TRY_START` is encountered, the current SSA block ends and a new one begins. No phi functions are needed at try boundaries — values from inside a try block are not usable outside it.

In `SsaDeconstructor`, when reconstructing IR from SSA, handler stack operations (`TRY_START`/`TRY_END`) are re-inserted to match the SSA structure.

### Optimization constraints

- DCE and copy propagation must not move code "backward" across a `TRY_START`
- Induction variable analysis treats try blocks as opaque — do not analyze loop variables inside try blocks
- Loop-invariant code motion must not hoist code into a try block
- Note: if a loop body uses the same CallFrame across iterations (via `for` desugaring), a `break`/`continue` must unwind all active handler records before transferring control. This is handled by `EXIT_TRY` in the generated code for break/continue paths.

## 10. Test Cases

### Basic try-catch

```ink
try {
    throw "oops"
} catch e {
    print(e)
}
// Expected output: oops
```

### Throw any value

```ink
try {
    throw 42
} catch e {
    print(e)
}
// Expected output: 42
```

### Finally always runs

```ink
let x = 0
try {
    x = 1
} finally {
    x = 2
}
print(x)
// Expected output: 2
```

### Exception in finally (finally wins)

```ink
let x = 0
try {
    throw "original"
} catch e {
    print("caught: ${e}")
} finally {
    throw "from finally"
}
// Expected output: caught: original
// (finally's throw replaces original)
```

### Nested try with finally unwinding order

```ink
try {
    try {
        throw "inner"
    } finally {
        print("inner finally")
    }
} catch e {
    print("caught: ${e}")
}
// Expected output: inner finally, caught: inner
```

### Return inside try with finally

```ink
fn foo() {
    try {
        return 42
    } finally {
        print("cleanup")
    }
}
print(foo())
// Expected output: cleanup, 42
```

### Uncaught throw

```ink
throw "top level"
// Expected: runtime error "Uncaught: top level"
```

### Break inside try with finally

```ink
var result = 0
for i in [1, 2, 3] {
    try {
        if i == 2 { break }
        result = result + i
    } finally {
        result = result + 100  // finally runs before break exits loop
    }
}
print(result)
// i=1: result=1, finally: result=101
// i=2: finally runs before break: result=201, loop exits
// Expected output: 201
```

### Continue inside try with finally

```ink
var sum = 0
for i in [1, 2, 3] {
    try {
        if i == 2 { continue }
        sum = sum + i
    } finally {
        sum = sum + 10
    }
}
print(sum)
// i=1: sum=1, finally: sum=11
// i=2: finally runs before continue: sum=21, continue
// i=3: sum=21+3=24, finally: sum=34
// Expected output: 34
```

## 11. File Map

| File | Changes |
|------|---------|
| `lang/src/main/kotlin/org/inklang/lang/Token.kt` | Add `KW_TRY`, `KW_CATCH`, `KW_FINALLY`, `KW_THROW` |
| `lang/src/main/kotlin/org/inklang/lang/Lexer.kt` | Add keyword rules for `try`, `catch`, `finally`, `throw` |
| `lang/src/main/kotlin/org/inklang/lang/Parser.kt` | Add `parseTryCatch()` and throw expression rules |
| `lang/src/main/kotlin/org/inklang/lang/AST.kt` | No changes (types already exist) |
| `lang/src/main/kotlin/org/inklang/lang/IR.kt` | Add `TRY_START`, `TRY_END`, `THROW`, `EXIT_TRY` |
| `lang/src/main/kotlin/org/inklang/lang/OpCode.kt` | Add `TRY_START`, `TRY_END`, `THROW`, `EXIT_TRY` opcodes |
| `lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt` | Implement `lowerTryCatch` and `lowerThrow` |
| `lang/src/main/kotlin/org/inklang/ast/VM.kt` | Add handler stack, thrown value, implement throw/unwind |
| `lang/src/main/kotlin/org/inklang/lang/IrCompiler.kt` | Add bytecode emission for `TRY_START`, `TRY_END`, `THROW`, `EXIT_TRY` |
| `lang/src/main/kotlin/org/inklang/ast/ControlFlowGraph.kt` | Add `TRY_START` as a leader in `findLeaders()` |
| `lang/src/main/kotlin/org/inklang/ssa/SsaBuilder.kt` | End SSA blocks at `TRY_START` boundaries |
| `lang/src/main/kotlin/org/inklang/ssa/SsaDeconstructor.kt` | Re-insert handler operations |
| `lang/src/main/kotlin/org/inklang/opt/passes/DeadCodeEliminationPass.kt` | Don't DCE across try boundaries without analysis |
| `lang/src/test/kotlin/org/inklang/ast/VMTest.kt` | Add exception handling tests |
