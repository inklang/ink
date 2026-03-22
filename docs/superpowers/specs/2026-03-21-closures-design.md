# Closures with Lexical Scoping - Design Spec

> Lexical closures with value capture for Inklang.

## Overview

Add proper closure support. When a lambda captures variables from an enclosing scope, those values are copied at lambda creation time. Captured values are immutable copies.

## Example

```ink
fn outer() {
    let x = 10
    let f = fn() { x }
    print(f())  // 10
}
```

Currently breaks because `x` is a local variable, but lambdas can't access enclosing locals.

## Design Summary

1. **Capture detection** — When lowering a lambda, detect variables referenced from enclosing scope
2. **Copy at creation** — Emit IR to copy captured values into registers before creating closure
3. **Store in Function** — Add `upvalues` field to `Value.Function` holding captured values
4. **Pass to frame** — When VM calls a closure, pass its upvalues to the new CallFrame
5. **GetUpvalue instruction** — New opcode reads from `frame.upvalues`

## Changes

### 1. OpCode.kt

Add new opcode:

```kotlin
GET_UPVALUE(0x2C)   // dst = frame.upvalues[index]
```

Note: 0x2B is already REGISTER_EVENT.

### 2. IR.kt

Add GetUpvalue instruction:

```kotlin
data class GetUpvalue(val dst: Int, val index: Int) : IrInstr()
```

Update LoadFunc to track captured variables and their source registers:

```kotlin
data class LoadFunc(
    val dst: Int,
    val name: String,
    val arity: Int,
    val instrs: List<IrInstr>,
    val constants: List<Value>,
    val defaultValues: List<DefaultValueInfo?> = emptyList(),
    val capturedVars: List<String> = emptyList(),      // names for debugging
    val upvalueRegs: List<Int> = emptyList()          // source registers for captured values
) : IrInstr()
```

### 3. Value.kt

Add `upvalues` field to Function:

```kotlin
data class Function(
    val chunk: Chunk,
    val arity: Int = 0,
    val defaults: FunctionDefaults? = null,
    val upvalues: List<Value> = emptyList()  // captured values
) : Value()
```

### 4. Chunk.kt

Track upvalue count and source registers per function:

```kotlin
data class Chunk(
    val code: List<Int>,
    val constants: MutableList<Value>,
    val functions: MutableList<Chunk>,
    val functionDefaults: MutableList<FunctionDefaults>,
    val spillSlotCount: Int = 0,
    val functionUpvalues: MutableMap<Int, Pair<Int, List<Int>>> = mutableMapOf()
    // funcIdx -> (upvalueCount, listOfSourceRegisters)
)
```

### 5. AstLowerer.kt

**Add fields for capture tracking:**

```kotlin
protected var capturedVars: MutableMap<String, Int> = mutableMapOf()  // varName -> upvalueIndex
protected var enclosingLocals: Map<String, Int> = emptyMap()  // snapshot of enclosing scope
protected var fieldNames: Set<String> = emptySet()
```

**Lambda lowering:**

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

    // Build upvalue names and their source registers
    val capturedNames = captured.keys.toList()
    val upvalueSrcRegs = captured.values.toList()  // enclosing registers holding captured values

    emit(IrInstr.LoadFunc(
        dst, lambdaName, expr.params.size, result.instrs,
        result.constants, defaultValues, capturedNames, upvalueSrcRegs
    ))
}
```

**Variable resolution for capture:**

```kotlin
is Expr.VariableExpr -> {
    val reg = locals[expr.name.lexeme]
    if (reg != null) {
        emit(IrInstr.Move(dst, reg))
        dst
    } else if (expr.name.lexeme in fieldNames) {
        emit(IrInstr.GetField(dst, 0, expr.name.lexeme))
        dst
    } else if (expr.name.lexeme in enclosingLocals) {
        // Capture from enclosing scope
        val upvalueIndex = capturedVars.getOrPut(expr.name.lexeme) {
            capturedVars.size
        }
        emit(GetUpvalue(dst, upvalueIndex))
        dst
    } else {
        emit(LoadGlobal(dst, expr.name.lexeme))
        dst
    }
}
```

### 6. IrCompiler.kt

**Handle GET_UPVALUE:**

```kotlin
OpCode.GET_UPVALUE -> {
    val dst = readByte()
    val index = readByte()
    frame.regs[dst] = frame.upvalues.getOrNull(index) ?: Value.Null
}
```

**Handle LoadFunc:**

When compiling LoadFunc, track upvalue count AND source registers:

```kotlin
is IrInstr.LoadFunc -> {
    // ... existing function compilation ...
    val funcChunk = IrCompiler().compile(funcResult)
    funcChunk.spillSlotCount = funcAllocResult.spillSlotCount

    val idx = chunk.functions.size
    chunk.functions.add(funcChunk)

    // Record upvalue info for runtime extraction
    // upvalueRegs tells VM which registers (in caller's frame) hold captured values
    chunk.functionUpvalues[idx] = Pair(instr.capturedVars.size, instr.upvalueRegs)
}
```

Update Chunk to store both count and register indices:

```kotlin
data class Chunk(
    // ... existing fields ...
    val functionUpvalues: MutableMap<Int, Pair<Int, List<Int>>> = mutableMapOf()
    // funcIdx -> (upvalueCount, listOfSourceRegisters)
)
```

### 7. VM.kt

**CallFrame stores upvalues:**

```kotlin
data class CallFrame(
    val chunk: Chunk,
    var ip: Int = 0,
    val regs: Array<Value?> = arrayOfNulls(16),
    var returnDst: Int = 0,
    val argBuffer: ArrayDeque<Value> = ArrayDeque(),
    val spills: Array<Value?> = arrayOfNulls(chunk.spillSlotCount),
    val upvalues: List<Value> = emptyList()  // captured values passed from closure
)
```

**Handle LOAD_FUNC (closure creation):**

When LOAD_FUNC executes (creating a closure), extract captured values from current frame's registers:

```kotlin
is OpCode.LOAD_FUNC -> {
    val dst = readByte()
    val funcIdx = readShort()  // function index in chunk
    val func = frame.chunk.functions[funcIdx]
    val arity = readByte()

    // Get upvalue info: count and source registers
    val (upvalueCount, upvalueSrcRegs) = frame.chunk.functionUpvalues[funcIdx] ?: Pair(0, emptyList())

    // Extract captured values from current frame's registers
    val upvalues = upvalueSrcRegs.map { reg -> frame.regs[reg] ?: Value.Null }

    val closure = Value.Function(func, arity, null, upvalues)
    frame.regs[dst] = closure
}
```

**Handle CALL with upvalues:**

When calling a function, pass its upvalues to the new frame:

```kotlin
is OpCode.CALL -> {
    val funcReg = instr.src1
    val func = frame.regs[funcReg]

    val upvalues = when (func) {
        is Value.Function -> func.upvalues
        is Value.BoundMethod -> (func.method as? Value.Function)?.upvalues ?: emptyList()
        else -> emptyList()
    }

    val newFrame = CallFrame(
        chunk = func.chunk,
        upvalues = upvalues
    )
    // ... rest of call setup
}
```

**Handle GET_UPVALUE:**

```kotlin
OpCode.GET_UPVALUE -> {
    val dst = readByte()
    val index = readByte()
    frame.regs[dst] = frame.upvalues.getOrNull(index) ?: Value.Null
}
```

## Key Design Decisions

1. **Upvalues stored in Value.Function** — Captured values live with the closure object
2. **Passed to CallFrame at call time** — VM passes closure's upvalues to the new frame
3. **Read via frame.upvalues** — GET_UPVALUE reads from current frame's upvalues list
4. **Value semantics** — Captured values are copies; modifying outer variables doesn't affect closures

## Example Transformation

**Source:**
```ink
fn outer() {
    let x = 10
    let f = fn() { x }
    print(f())
}
```

**IR (after lowering):**
```
// outer body
r0 = 10              // x (allocated to register 0)
r1 = LoadFunc(__lambda_0, captured=["x"], upvalueRegs=[0])
f = r1
call print(f)

// __lambda_0 body
r0 = GetUpvalue(0)   // read x's captured value
call print(r0)
return r0
```

**Runtime:**
- At LoadFunc execution: VM reads register 0's value (10) from current frame, stores in closure.upvalues
- Closure created: `Value.Function(chunk=__lambda_0, upvalues=[10])`
- Called: `CallFrame.upvalues = [10]`
- GetUpvalue(0) reads `frame.upvalues[0]` = 10

## Loop Capture Test Case

```ink
fn outer() {
    let funcs = []
    for i in 0..3 {
        funcs.push(fn() { i })
    }
    print(funcs[0]())  // 0
    print(funcs[2]())  // 2
}
```

Each iteration creates a new register binding for `i` (via SSA form). Each closure captures its own `i`'s register, giving it the value at that iteration.

## Nested Closures

```ink
fn outer() {
    let x = 1
    let f = fn() {
        let g = fn() { x }
        g()
    }
    f()  // 1
}
```

**Scope resolution:** When lowering `g`'s body `{ x }`:
1. `x` not in `g`'s locals
2. `x` not in `f`'s locals (f has no `let x`)
3. `f`'s `enclosingLocals` = `outer`'s snapshot `{x: reg0}` → found!

So `g` captures `outer`'s `x = 1` directly. `f` captures nothing (f's body doesn't reference `x`).

- `g.upvalues = [1]` (captures `outer`'s x)
- `f.upvalues = []` (f doesn't reference x)

**Note:** A variable is only captured if the enclosing function's body directly references it. Nested lambdas capture from their immediate enclosing scope's snapshot.

## Implementation Order

1. **OpCode.kt** — Add GET_UPVALUE(0x2C)
2. **IR.kt** — Add GetUpvalue; update LoadFunc with capturedVars
3. **Value.kt** — Add upvalues field to Function
4. **Chunk.kt** — Add functionUpvalues map
5. **AstLowerer.kt** — Add capture tracking fields; emit Move+GetUpvalue; pass capturedVars to LoadFunc
6. **IrCompiler.kt** — Handle GET_UPVALUE opcode; populate functionUpvalues
7. **VM.kt** — Add upvalues to CallFrame; handle GET_UPVALUE; pass upvalues in CALL

## Test Cases

```ink
// Basic capture
fn test1() {
    let x = 10
    let f = fn() { x }
    assert(f() == 10)
}

// Multiple captures
fn test2() {
    let a = 1
    let b = 2
    let f = fn() { a + b }
    assert(f() == 3)
}

// Nested closures
fn test3() {
    let x = 1
    let f = fn() {
        let g = fn() { x }
        g()
    }
    assert(f() == 1)
}

// Loop capture
fn test4() {
    let funcs = []
    for i in 0..3 {
        funcs.push(fn() { i })
    }
    assert(funcs[0]() == 0)
    assert(funcs[2]() == 2)
}

// Parameter shadows outer
fn test5() {
    let x = 10
    let f = fn(x) { x }
    assert(f(5) == 5)
}
```
