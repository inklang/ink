# when Keyword Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the `when` keyword as a reactive condition watcher — fires a block when a boolean condition transitions from false to true, then re-arms when it becomes false again.

**Architecture:** The `when` statement is lowered to two IR nodes: `WhenStart` (initializes the watcher, records initial condition value, arms it) and `WhenCheck` (called each tick to evaluate condition and fire/re-arm). The VM maintains per-watcher state (condition register, previous value, armed flag, block chunk).

**Tech Stack:** Kotlin, register-based bytecode VM, existing IR/opcode infrastructure

---

## Chunk 1: Token, Lexer, Parser, AST

### Task 1: Add KW_WHEN to Token.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Token.kt:32`

- [ ] **Step 1: Add KW_WHEN token type**

In the `TokenType` enum, add `KW_WHEN` after `KW_HAS`:
```kotlin
KW_HAS,
KW_WHEN,  // when keyword for reactive condition watching
KW_TABLE,
```

---

### Task 2: Add "when" keyword to Lexer.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Lexer.kt:51`

- [ ] **Step 1: Add "when" to keywords map**

Add the mapping in the `keywords` map in `Lexer.kt`:
```kotlin
"has" to TokenType.KW_HAS,
"when" to TokenType.KW_WHEN,
"table" to TokenType.KW_TABLE,
```

---

### Task 3: Add WhenStmt to AST.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/AST.kt`

- [ ] **Step 1: Add WhenStmt data class**

In the `Stmt` sealed class, add:
```kotlin
// Reactive condition watcher: fires on false->true transition, re-arms on true->false
data class WhenStmt(
    val condition: Expr,
    val body: Stmt.BlockStmt
) : Stmt()
```

Add it to the `parseStmt` sealed class dispatcher in `Parser.kt`.

---

### Task 4: Add parseWhen() to Parser.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt:201-231`

- [ ] **Step 1: Add KW_WHEN branch to parseStmt()**

In `parseStmt()`, add before the `else` branch:
```kotlin
check(TokenType.KW_WHILE) -> parseWhile()
check(TokenType.KW_FOR) -> parseFor()
check(TokenType.KW_WHEN) -> parseWhen()  // NEW
```

- [ ] **Step 2: Add parseWhen() method**

Add after `parseWhile()`:
```kotlin
private fun parseWhen(): Stmt {
    consume(TokenType.KW_WHEN, "Expected 'when'")
    val condition = parseExpression(0)
    val body = parseBlock()
    return Stmt.WhenStmt(condition, body)
}
```

---

### Task 5: Add WhenStart and WhenCheck to IR.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/IR.kt`

- [ ] **Step 1: Add WhenStart and WhenCheck IR nodes**

In `IR.kt` in the `IrInstr` sealed class, add:
```kotlin
// Reactive watcher: start/initialize a when watcher
data class WhenStart(
    val dst: Int,                    // register to store watcher ID
    val condReg: Int,                // register holding the condition expression
    val blockChunk: List<IrInstr>,   // compiled block body instructions
    val blockConstants: List<Value>,  // constants needed by the block
    val blockArity: Int              // number of registers used by block
) : IrInstr()

// Check the watcher (called each tick)
data class WhenCheck(
    val watcherReg: Int  // register holding the watcher ID from WhenStart
) : IrInstr()
```

---

### Task 6: Add WHEN_START and WHEN_CHECK to OpCode.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/OpCode.kt`

- [ ] **Step 1: Add new opcodes**

In the `OpCode` enum, add after `REGISTER_EVENT`:
```kotlin
REGISTER_EVENT(0x2B),
WHEN_START(0x2C),    // Initialize a when watcher
WHEN_CHECK(0x2D),    // Check and update watcher state each tick
```

---

### Task 7: Add lowerWhen() to AstLowerer.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt`

- [ ] **Step 1: Add WhenStmt case to lowerStmt()**

In `lowerStmt()`, add case:
```kotlin
is Stmt.WhileStmt -> lowerWhile(stmt)
is Stmt.WhenStmt -> lowerWhen(stmt)  // NEW
```

- [ ] **Step 2: Add lowerWhen() method**

Add after `lowerWhile()`:
```kotlin
private fun lowerWhen(stmt: Stmt.WhenStmt) {
    // Lower the condition expression
    val condReg = freshReg()
    lowerExpr(stmt.condition, condReg)

    // Compile the body block to its own IR
    val bodyLowerer = AstLowerer()
    bodyLowerer.regCounter = 0
    val bodyResult = bodyLowerer.lower(stmt.body.stmts)

    // Create a new lowerer for the watcher with fresh state
    val watcherLowerer = AstLowerer()
    // The condition register is in the parent frame - we need to reference it
    // Store the condition in a local that the watcher can access
    val dst = freshReg()

    emit(IrInstr.WhenStart(
        dst = dst,
        condReg = condReg,
        blockChunk = bodyResult.instrs,
        blockConstants = bodyResult.constants,
        blockArity = bodyLowerer.regCounter
    ))
}
```

---

### Task 8: Add WHEN_START and WHEN_CHECK to IrCompiler.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/IrCompiler.kt`

- [ ] **Step 1: Add compile case for WhenStart**

In the `compile()` method's `when (instr)` block, add:
```kotlin
is IrInstr.WhenStart -> {
    // Store watcher metadata in chunk for runtime lookup
    val watcherIdx = chunk.watchers.size
    chunk.watchers.add(
        Chunk.WhenWatcherInfo(
            condReg = instr.condReg,
            blockChunk = instr.blockChunk,
            blockConstants = instr.blockConstants,
            blockArity = instr.blockArity
        )
    )
    chunk.write(OpCode.WHEN_START, dst = instr.dst, imm = watcherIdx)
}
```

- [ ] **Step 2: Add compile case for WhenCheck**

```kotlin
is IrInstr.WhenCheck -> {
    chunk.write(OpCode.WHEN_CHECK, dst = instr.watcherReg, imm = 0)
}
```

---

### Task 9: Add WhenWatcherInfo to Chunk.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Chunk.kt`

- [ ] **Step 1: Add WhenWatcherInfo data class**

Add in `Chunk.kt`:
```kotlin
data class WhenWatcherInfo(
    val condReg: Int,                 // register holding condition in outer frame
    val blockChunk: List<IrInstr>,   // compiled block body
    val blockConstants: List<Value>, // constants for the block
    val blockArity: Int               // register count for the block
)

val watchers: MutableList<WhenWatcherInfo> = mutableListOf()
```

---

### Task 10: Add WHEN_START and WHEN_CHECK handlers to VM.kt

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/VM.kt`

- [ ] **Step 1: Add WhenWatcher runtime state class**

Add in `VM.kt`:
```kotlin
data class WhenWatcher(
    val condReg: Int,        // register in the frame where WhenStart was called
    var wasTrue: Boolean,   // previous condition value
    var isArmed: Boolean,   // true = armed, false = fired
    val blockChunk: Chunk,  // compiled block to execute on fire
    val blockFrame: CallFrame  // frame for executing block (shares registers with outer)
)
```

Add `private val whenWatchers = mutableListOf<WhenWatcher>()` to VM state.

- [ ] **Step 2: Add WHEN_START handler**

```kotlin
OpCode.WHEN_START -> {
    val watcherIdx = imm
    val watcherInfo = frame.chunk.watchers[watcherIdx]
    // Evaluate condition once to get initial value
    val condValue = frame.regs[watcherInfo.condReg]
    val isTrue = !isFalsy(condValue)
    // Create watcher
    val blockChunk = Chunk()
    blockChunk.constants.addAll(watcherInfo.blockConstants)
    // Pre-compile block chunk (simple approach: execute IR directly)
    val watcher = WhenWatcher(
        condReg = watcherInfo.condReg,
        wasTrue = isTrue,
        isArmed = true,
        blockChunk = blockChunk,
        blockFrame = CallFrame(blockChunk)
    )
    val watcherId = whenWatchers.size
    whenWatchers.add(watcher)
    frame.regs[dst] = Value.Int(watcherId)
}
```

- [ ] **Step 3: Add WHEN_CHECK handler**

```kotlin
OpCode.WHEN_CHECK -> {
    val watcherId = (frame.regs[dst] as? Value.Int)?.value?.toInt() ?: return
    val watcher = whenWatchers.getOrNull(watcherId) ?: return
    // Re-evaluate condition using the original register from the caller's frame
    // We need to find the caller's frame to access the condition register
    val callerFrame = frames.lastOrNull() ?: return
    val isTrue = !isFalsy(callerFrame.regs[watcher.condReg] ?: Value.Null)

    if (watcher.isArmed && isTrue) {
        // false->true transition: FIRE
        watcher.isArmed = false
        // Execute the block
        val blockFrame = CallFrame(watcher.blockChunk)
        executeBlock(blockFrame, frames)
    } else if (!watcher.isArmed && !isTrue) {
        // true->false transition: re-arm
        watcher.isArmed = true
    }
}
```

- [ ] **Step 4: Add executeBlock helper**

```kotlin
private fun executeBlock(blockFrame: CallFrame, frames: ArrayDeque<CallFrame>) {
    // Run the block body in a minimal VM loop
    while (blockFrame.ip < blockFrame.chunk.code.size) {
        val word = blockFrame.chunk.code[blockFrame.ip++]
        val opcode = OpCode.entries.find { it.code == (word and 0xFF).toByte() }
            ?: error("Unknown opcode in when block: ${word and 0xFF}")
        val dst = (word shr 8) and 0x0F
        val src1 = (word shr 12) and 0x0F
        val src2 = (word shr 16) and 0x0F
        val imm = (word shr 20) and 0xFFF
        // ... handle opcodes needed for the block
    }
}
```

---

## Chunk 2: Testing

### Task 11: Add basic tests for when keyword

**Files:**
- Modify: `ink/src/test/kotlin/org/inklang/InkCompilerTest.kt` (or find existing test file)

- [ ] **Step 1: Add test for when with simple condition**

```kotlin
@Test
fun `when fires on condition true`(){
    val result = InkCompiler().run("""
        let x = 0
        when true {
            x = 1
        }
        x
    """.trimIndent())
    assertEquals(1, result)
}
```

- [ ] **Step 2: Add test for when with comparison**

```kotlin
@Test
fun `when fires on comparison`(){
    val result = InkCompiler().run("""
        let x = 5
        when x > 3 {
            x = 10
        }
        x
    """.trimIndent())
    assertEquals(10, result)
}
```

- [ ] **Step 3: Add test for when with AND condition**

```kotlin
@Test
fun `when fires with AND condition`(){
    val result = InkCompiler().run("""
        let x = 5
        let y = 10
        when x > 3 and y < 20 {
            x = 100
        }
        x
    """.trimIndent())
    assertEquals(100, result)
}
```

---

## Chunk 3: Integration and Polish

### Task 12: Verify all files compile

**Files:**
- Build: `./gradlew :ink:compileKotlin`

- [ ] **Step 1: Run full build**

Run: `./gradlew :ink:build`
Expected: BUILD SUCCESSFUL

---

## Dependencies

- Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 7 → Task 8 → Task 9 → Task 10 → Task 11 → Task 12

## Verification

After implementation:
1. Run `./gradlew :ink:test --tests "*When*"` - should pass
2. Run `./gradlew :ink:build` - should succeed
3. Test manually: `when player.health < 20 { warn() }`
