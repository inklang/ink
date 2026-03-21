# Closures Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add lexical closures with value capture to Inklang. Lambdas can capture variables from enclosing scope; captured values are copied at creation time.

**Architecture:** Capture detection in AstLowerer tracks enclosing locals. VM copies captured values into Value.Function.upvalues at closure creation. GET_UPVALUE opcode reads from frame.upvalues.

**Tech Stack:** Kotlin/JVM, Inklang bytecode VM

---

## Chunk 1: OpCode and IR Changes

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/OpCode.kt`
- Modify: `ink/src/main/kotlin/org/inklang/lang/IR.kt`

- [ ] **Step 1: Add GET_UPVALUE opcode to OpCode.kt**

In `OpCode.kt` after `REGISTER_EVENT(0x2B)`:

```kotlin
GET_UPVALUE(0x2C),  // dst = frame.upvalues[index]
```

- [ ] **Step 2: Add GetUpvalue IR instruction to IR.kt**

In `IR.kt`, add after `HasCheck`:

```kotlin
data class GetUpvalue(val dst: Int, val index: Int) : IrInstr()
```

- [ ] **Step 3: Update LoadFunc in IR.kt to track captured variables**

Change `LoadFunc` to:

```kotlin
data class LoadFunc(
    val dst: Int,
    val name: String,
    val arity: Int,
    val instrs: List<IrInstr>,
    val constants: List<Value>,
    val defaultValues: List<DefaultValueInfo?> = emptyList(),
    val capturedVars: List<String> = emptyList(),     // names for debugging
    val upvalueRegs: List<Int> = emptyList()          // source registers for captured values
) : IrInstr()
```

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew :ink:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/OpCode.kt ink/src/main/kotlin/org/inklang/lang/IR.kt
git commit -m "feat(closures): add GET_UPVALUE opcode and GetUpvalue IR instruction

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 2: Value and Chunk Changes

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Value.kt`
- Modify: `ink/src/main/kotlin/org/inklang/lang/Chunk.kt`

- [ ] **Step 1: Add upvalues field to Function in Value.kt**

Find `data class Function` in Value.kt (around line 46) and update:

```kotlin
data class Function(
    val chunk: Chunk,
    val arity: kotlin.Int = 0,
    val defaults: FunctionDefaults? = null,
    val upvalues: List<Value> = emptyList()  // captured values
) : Value()
```

- [ ] **Step 2: Add functionUpvalues to Chunk.kt**

In `Chunk.kt`, add field and update `addConstant`:

```kotlin
class Chunk {
    val code = mutableListOf<Int>()
    val constants = mutableListOf<Value>()
    val strings = mutableListOf<String>()
    val functions = mutableListOf<Chunk>()
    val classes = mutableListOf<ClassInfo>()
    val functionDefaults = mutableListOf<FunctionDefaults>()
    val functionUpvalues = mutableMapOf<Int, Pair<Int, List<Int>>>()  // funcIdx -> (upvalueCount, upvalueRegs)
    var spillSlotCount: Int = 0
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :ink:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Value.kt ink/src/main/kotlin/org/inklang/lang/Chunk.kt
git commit -m "feat(closures): add upvalues field to Function and functionUpvalues to Chunk

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 3: AstLowerer - Capture Detection

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt`
- Test: `ink/src/test/kotlin/org/inklang/ClosureTest.kt` (new file)

- [ ] **Step 1: Add capture tracking fields to AstLowerer**

In `AstLowerer.kt`, add after `var lambdaCounter = 0`:

```kotlin
protected var capturedVars: MutableMap<String, Int> = mutableMapOf()  // varName -> upvalueIndex
protected var enclosingLocals: Map<String, Int> = emptyMap()  // snapshot of enclosing scope
protected var fieldNames: Set<String> = emptySet()
```

- [ ] **Step 2: Update lambda lowering to detect captures**

Find `is Expr.LambdaExpr ->` in AstLowerer.kt and replace with:

```kotlin
is Expr.LambdaExpr -> {
    val lambdaName = "__lambda_${lambdaCounter++}"

    // Snapshot current scope for capture detection
    val enclosing = locals.toMap()
    val captured = mutableMapOf<String, Int>()

    val lowerer = AstLowerer()
    lowerer.locals = mutableMapOf()
    lowerer.enclosingLocals = enclosing
    lowerer.capturedVars = captured
    lowerer.fieldNames = fieldNames

    for ((i, param) in expr.params.withIndex()) {
        lowerer.locals[param.name.lexeme] = i
    }
    lowerer.regCounter = expr.params.size

    val result = lowerer.lower(expr.body.stmts)

    // Build captured variable names and source registers
    val capturedNames = captured.keys.toList()
    val upvalueSrcRegs = captured.values.toList()

    emit(IrInstr.LoadFunc(
        dst, lambdaName, expr.params.size, result.instrs,
        result.constants, defaultValues, capturedNames, upvalueSrcRegs
    ))
}
```

- [ ] **Step 3: Update VariableExpr resolution for capture**

Find `is Expr.VariableExpr ->` in AstLowerer.kt and add capture check before the else clause:

```kotlin
is Expr.VariableExpr -> {
    val reg = locals[expr.name.lexeme]
    if (reg != null) {
        // Local variable in lambda scope
        emit(IrInstr.Move(dst, reg))
        dst
    } else if (expr.name.lexeme in fieldNames) {
        // Field access
        emit(IrInstr.GetField(dst, 0, expr.name.lexeme))
        dst
    } else if (expr.name.lexeme in enclosingLocals) {
        // Captured from enclosing scope!
        val upvalueIndex = capturedVars.getOrPut(expr.name.lexeme) {
            capturedVars.size
        }
        emit(IrInstr.GetUpvalue(dst, upvalueIndex))
        dst
    } else {
        emit(LoadGlobal(dst, expr.name.lexeme))
        dst
    }
}
```

- [ ] **Step 4: Create ClosureTest.kt with basic tests**

```kotlin
package org.inklang

import org.inklang.ast.*
import org.inklang.lang.*
import kotlin.test.Test
import kotlin.test.assertEquals

private fun compileAndRun(source: String): List<String> {
    val output = mutableListOf<String>()
    val tokens = tokenize(source)
    val stmts = Parser(tokens).parse()
    val folder = ConstantFolder()
    val folded = stmts.map { folder.foldStmt(it) }
    val result = AstLowerer().lower(folded)

    val (ssaDeconstructed, ssaOptConstants) = IrCompiler.optimizedSsaRoundTrip(result.instrs, result.constants)
    val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, ssaOptConstants)

    val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
    val allocResult = RegisterAllocator().allocate(ranges)
    val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)
    val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaResult.constants))
    chunk.spillSlotCount = allocResult.spillSlotCount

    val vm = VM()
    vm.globals["print"] = Value.NativeFunction { args ->
        output.add(args.joinToString(" ") { valueToString(it) })
        Value.Null
    }
    vm.execute(chunk)
    return output
}

class ClosureTest {

    @Test
    fun `basic capture`() {
        val output = compileAndRun("""
            fn outer() {
                let x = 10
                let f = fn() { x }
                print(f())
            }
        """.trimIndent())
        assertEquals(listOf("10"), output)
    }

    @Test
    fun `multiple captures`() {
        val output = compileAndRun("""
            fn outer() {
                let a = 1
                let b = 2
                let f = fn() { a + b }
                print(f())
            }
        """.trimIndent())
        assertEquals(listOf("3"), output)
    }

    @Test
    fun `parameter shadows outer`() {
        val output = compileAndRun("""
            fn outer() {
                let x = 10
                let f = fn(x) { x }
                print(f(5))
            }
        """.trimIndent())
        assertEquals(listOf("5"), output)
    }
}
```

- [ ] **Step 5: Run tests to verify they fail (expected - closures not wired yet)**

Run: `./gradlew :ink:test --tests "org.inklang.ClosureTest" 2>&1 | tail -30`
Expected: Compilation error (GetUpvalue not handled, Function.upvalues not handled)

- [ ] **Step 6: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt ink/src/test/kotlin/org/inklang/ClosureTest.kt
git commit -m "feat(closures): add capture detection in AstLowerer

- Add capturedVars, enclosingLocals, fieldNames tracking fields
- Lambda lowering detects enclosing scope variables
- VariableExpr resolution emits GetUpvalue for captured vars
- Add ClosureTest with basic capture tests

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 4: IrCompiler - Handle GET_UPVALUE

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/IrCompiler.kt`

- [ ] **Step 1: Add GET_UPVALUE handling in IrCompiler**

Find the existing `is IrInstr.LoadFunc` block in IrCompiler.kt and modify it to store upvalue info. Also add a new case for GetUpvalue:

Add before the existing `LoadFunc` handling:

```kotlin
is IrInstr.GetUpvalue -> {
    chunk.write(OpCode.GET_UPVALUE, dst = instr.dst, imm = instr.index)
}
```

Modify the `is IrInstr.LoadFunc` block to add functionUpvalues after the function is added:

```kotlin
is IrInstr.LoadFunc -> {
    // ... existing code (SSA round-trip, register allocation) ...

    val idx = chunk.functions.size
    chunk.functions.add(funcChunk)
    chunk.functionDefaults[idx] = FunctionDefaults(defaultChunkIndices)

    // Store upvalue info for runtime extraction
    if (instr.capturedVars.isNotEmpty()) {
        chunk.functionUpvalues[idx] = Pair(instr.capturedVars.size, instr.upvalueRegs)
    }

    chunk.write(OpCode.LOAD_FUNC, dst = instr.dst, imm = idx)
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :ink:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/IrCompiler.kt
git commit -m "feat(closures): handle GET_UPVALUE opcode and store upvalue info in IrCompiler

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 5: VM - Runtime Support

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ast/VM.kt`

- [ ] **Step 1: Add upvalues to CallFrame**

Modify `CallFrame` in VM.kt:

```kotlin
data class CallFrame(
    val chunk: Chunk,
    var ip: Int = 0,
    val regs: Array<Value?> = arrayOfNulls(16),
    var returnDst: Int = 0,
    val argBuffer: ArrayDeque<Value> = ArrayDeque(),
    val spills: Array<Value?> = arrayOfNulls(chunk.spillSlotCount),
    val upvalues: List<Value> = emptyList()  // captured values
)
```

- [ ] **Step 2: Update Function creation to include upvalues**

Find where `Value.Function` is created (around line 162 in VM.kt) and update:

```kotlin
is OpCode.LOAD_FUNC -> {
    val dst = (word shr 8) and 0x0F
    val funcIdx = (word shr 20) and 0xFFF
    val func = frame.chunk.functions[funcIdx]
    val arity = func.functionDefaults?.defaultChunks?.size ?: 0

    // Get upvalue info
    val (upvalueCount, upvalueRegs) = frame.chunk.functionUpvalues[funcIdx] ?: Pair(0, emptyList())
    val upvalues = upvalueRegs.map { reg -> frame.regs[reg] ?: Value.Null }

    frame.regs[dst] = Value.Function(func, arity, func.functionDefaults, upvalues)
}
```

- [ ] **Step 3: Update CALL to pass upvalues to new frame**

Find the `OpCode.CALL` handling in VM.kt and update where `CallFrame` is created:

For `Value.Function`:
```kotlin
is Value.Function -> {
    val totalParams = func.defaults?.defaultChunks?.size ?: passedArgCount
    val finalArgs = fillDefaultArgs(args, func, totalParams, frame, frames)
    val newFrame = CallFrame(func.chunk, upvalues = func.upvalues)  // pass upvalues
    newFrame.returnDst = dst
    finalArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
    frames.addLast(newFrame)
}
```

For `Value.BoundMethod` (where it calls a Function):
```kotlin
is Value.Function -> {
    val totalParams = method.defaults?.defaultChunks?.size ?: boundArgs.size
    val finalArgs = fillDefaultArgs(boundArgs, method, totalParams, frame, frames)
    val newFrame = CallFrame(method.chunk, upvalues = method.upvalues)  // pass upvalues
    newFrame.returnDst = dst
    finalArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
    frames.addLast(newFrame)
}
```

- [ ] **Step 4: Add GET_UPVALUE opcode handling**

Add in the VM's opcode dispatch (around line 300 or wherever opcodes are handled):

```kotlin
OpCode.GET_UPVALUE -> {
    val word = frame.chunk.code[frame.ip]
    frame.ip++
    val dst = (word shr 8) and 0x0F
    val index = (word shr 20) and 0xFFF
    frame.regs[dst] = frame.upvalues.getOrNull(index) ?: Value.Null
}
```

- [ ] **Step 5: Run closure tests**

Run: `./gradlew :ink:test --tests "org.inklang.ClosureTest" 2>&1 | tail -30`
Expected: Tests pass

- [ ] **Step 6: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/VM.kt
git commit -m "feat(closures): add runtime support for closures

- CallFrame stores upvalues list
- LOAD_FUNC extracts captured values from registers into Function.upvalues
- CALL passes upvalues to new frame
- GET_UPVALUE reads from frame.upvalues

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 6: Nested Closures and Loop Capture Tests

**Files:**
- Modify: `ink/src/test/kotlin/org/inklang/ClosureTest.kt`

- [ ] **Step 1: Add nested closure test**

```kotlin
@Test
fun `nested closures`() {
    val output = compileAndRun("""
        fn outer() {
            let x = 1
            let f = fn() {
                let g = fn() { x }
                g()
            }
            print(f())
        }
    """.trimIndent())
    assertEquals(listOf("1"), output)
}
```

- [ ] **Step 2: Add loop capture test**

```kotlin
@Test
fun `loop capture`() {
    val output = compileAndRun("""
        fn outer() {
            let funcs = []
            for i in 0..3 {
                funcs.push(fn() { i })
            }
            print(funcs[0]())
            print(funcs[2]())
        }
    """.trimIndent())
    assertEquals(listOf("0", "2"), output)
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :ink:test --tests "org.inklang.ClosureTest" 2>&1 | tail -40`
Expected: All tests pass

- [ ] **Step 4: Run full test suite**

Run: `./gradlew :ink:test 2>&1 | tail -20`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add ink/src/test/kotlin/org/inklang/ClosureTest.kt
git commit -m "test(closures): add nested closure and loop capture tests

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Summary

| Chunk | Files | What |
|-------|-------|------|
| 1 | OpCode.kt, IR.kt | GET_UPVALUE opcode, GetUpvalue IR, updated LoadFunc |
| 2 | Value.kt, Chunk.kt | Function.upvalues field, Chunk.functionUpvalues map |
| 3 | AstLowerer.kt | Capture detection, VariableExpr capture check |
| 4 | IrCompiler.kt | GET_UPVALUE bytecode, functionUpvalues storage |
| 5 | VM.kt | CallFrame.upvalues, LOAD_FUNC upvalue extraction, CALL passes upvalues |
| 6 | ClosureTest.kt | Nested closure and loop capture tests |
