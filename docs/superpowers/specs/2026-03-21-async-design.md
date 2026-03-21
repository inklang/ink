# Async/Await & Spawn Design

## Overview

Inklang uses an explicit async model with `async fn` declarations, `await` as a unary prefix operator, and `spawn` for explicit parallelism. The model is designed for **ease of learning** — admins write code that looks synchronous but doesn't block the server tick thread.

**Runtime:** Virtual threads (Project Loom). The VM runs on its own virtual thread as an **event loop** — frames are parked when awaiting and resumed when futures complete. The server tick thread is never blocked.

---

## Syntax

### Function Declarations

```inklang
// Async function — runs on virtual thread, can await
async fn fetchData(url: string) -> string { ... }

// Regular synchronous function
fn processData(data: string) -> string { ... }

// Async lambda
let fetcher = async fn(url: string) -> string { ... }
```

### Await

```inklang
// await is a unary prefix operator
let result = await http.get("https://...")

// await on a Task variable
let task = http.get("https://...")  // task is Task<string>
let result = await task
```

**Rule:** `await` on a non-Task expression is a **compile-time error**.

### Spawn

```inklang
// spawn on OS thread pool (for CPU-bound or blocking work)
let task = spawn processData("heavy data")

// spawn on virtual thread (for I/O or lightweight concurrent tasks)
let task = spawn virtual http.get(url)

// Fire-and-forget: spawn without storing task
spawn processData("heavy data")
```

**Both forms return a `Task<T>`.**

---

## Type System

### `Task<T>`

Represents a concurrent operation that will produce a value of type `T`.

- Created by: `async fn` call, `spawn`, `spawn virtual`
- Consumed by: `await` operator

`Task<T>` is an opaque type at runtime — erased to `Value.Task`. The type checker enforces that `await` only works on Task, and that `async fn` return type is `Task<T>`.

### Type Rules

| Expression | Type | Notes |
|------------|------|-------|
| `async fn fetch() -> T` | `Task<T>` | Calling it returns Task |
| `await task` | `T` | Unwraps Task to value |
| `spawn expr` | `Task<T>` | expr must be callable, returns Task |
| `spawn virtual expr` | `Task<T>` | same, but on virtual thread |
| `fn fetch() -> T` | `T` | Regular function, blocks caller |

---

## VM Execution Model

### VM as Event Loop on Virtual Thread

The VM executes on its own virtual thread as an event loop. It has two concerns:
1. Execute bytecode from active frames
2. Wait for suspended frames' futures to complete, then resume them

```
Server Tick Thread
    |
    v (starts VM on virtual thread via runBlocking or launch)
Virtual Thread: VM.execute() as event loop
    |
    +---> executes bytecode from frames stack
    |       hits AWAIT
    |         parks frame in suspendedFrames map
    |         frame removed from frames stack
    |         event loop waits for future completion
    |
    | (later: future completes)
    |         frame popped from suspendedFrames
    |         pushed back onto frames stack
    |         execution resumes at saved ip
    |
    +---> ASYNC_CALL --> launches coroutine on virtual thread
    |                     returns Task immediately
    |                     VM continues executing next instruction
    |
    +---> SPAWN / SPAWN_VIRTUAL --> launches on thread pool
    |                                  returns Task immediately
    |                                  VM continues executing next instruction
```

**Key insight:** `await` does NOT block the virtual thread. It parks the frame and the event loop waits for the future to complete, then resumes the frame. The server tick is never blocked.

### VM Structure

```kotlin
class VM {
    val globals = mutableMapOf<String, Value>(...)
    private val suspendedFrames = mutableMapOf<CompletableFuture<Value>, CallFrame>()
    private val executorService = Executors.newCachedThreadPool()

    data class CallFrame(
        val chunk: Chunk,
        var ip: Int = 0,
        val regs: Array<Value?> = arrayOfNulls(16),
        var returnDst: Int = 0,
        val argBuffer: ArrayDeque<Value> = ArrayDeque()
    ) {
        val spills: Array<Value?> = arrayOfNulls(chunk.spillSlotCount)
    }

    fun execute(chunk: Chunk) {
        val frames = ArrayDeque<CallFrame>()
        frames.addLast(CallFrame(chunk))

        while (frames.isNotEmpty() || suspendedFrames.isNotEmpty()) {
            // Execute instructions from active frames
            while (frames.isNotEmpty()) {
                val frame = frames.last()

                val word = frame.chunk.code[frame.ip++]
                val opcode = OpCode.entries.find { it.code == (word and 0xFF).toByte() }
                val dst = (word shr 8) and 0x0F
                val src1 = (word shr 12) and 0x0F
                val src2 = (word shr 16) and 0x0F
                val imm = (word shr 20) and 0xFFF

                when (opcode) {
                    OpCode.AWAIT -> {
                        val task = frame.regs[src1]
                        if (task == null || task == Value.Null) {
                            error("Cannot await null task")
                        }
                        val future = (task as Value.Task).deferred
                        suspendedFrames[future] = frame
                        frames.removeLast()
                        break  // exit inner loop, event loop takes over
                    }

                    OpCode.ASYNC_CALL -> {
                        // Launch async function as coroutine
                        val func = frame.regs[src1] as Value.Function
                        val task = CompletableFuture<Value>()

                        // Launch on virtual thread
                        Thread.startVirtualThread {
                            try {
                                val result = runAsyncFunction(func, task)
                                task.complete(result)
                            } catch (e: Throwable) {
                                task.completeExceptionally(e)
                            }
                        }

                        frame.regs[dst] = Value.Task(task)
                        // Continue to next instruction while coroutine runs
                    }

                    OpCode.SPAWN -> {
                        val func = frame.regs[src1] as? Value.Function
                            ?: error("spawn requires a function")
                        val task = CompletableFuture<Value>()

                        executorService.submit {
                            try {
                                val result = runAsyncFunction(func, task)
                                task.complete(result)
                            } catch (e: Throwable) {
                                task.completeExceptionally(e)
                            }
                        }

                        frame.regs[dst] = Value.Task(task)
                    }

                    OpCode.SPAWN_VIRTUAL -> {
                        val func = frame.regs[src1] as? Value.Function
                            ?: error("spawn virtual requires a function")
                        val task = CompletableFuture<Value>()

                        Thread.startVirtualThread {
                            try {
                                val result = runAsyncFunction(func, task)
                                task.complete(result)
                            } catch (e: Throwable) {
                                task.completeExceptionally(e)
                            }
                        }

                        frame.regs[dst] = Value.Task(task)
                    }

                    OpCode.RETURN -> {
                        val returnVal = frame.regs[src1]
                        frames.removeLast()
                        if (frames.isNotEmpty()) {
                            frames.last().regs[frame.returnDst] = returnVal
                        }
                    }

                    // ... other opcodes unchanged
                }
            }

            // Event loop: wait for any suspended frame to complete
            if (frames.isEmpty() && suspendedFrames.isNotEmpty()) {
                // Wait for any future to complete (using CompletableFuture.orTimeout)
                val completed = waitForAnyFuture(suspendedFrames.keys)
                val resumedFrame = suspendedFrames.remove(completed)!!

                // Get result from future
                val result = if (completed.isCompletedExceptionally) {
                    try { completed.join() }
                    catch (e: Throwable) { Value.String(e.message ?: "Unknown error") }
                } else {
                    completed.join()
                }

                resumedFrame.regs[resumedFrame.returnDst] = result
                frames.addLast(resumedFrame)
            }
        }
    }
}
```

### Await Semantics

1. `AWAIT` extracts the `CompletableFuture` from `Value.Task`
2. Current frame is removed from active stack, stored in `suspendedFrames` map keyed by future
3. VM's inner execution loop exits
4. Event loop polls/waits for any suspended future to complete
5. On completion: frame is popped from `suspendedFrames`, pushed back onto `frames`, execution resumes at saved ip
6. Result (or exception as string) is stored in frame's register via the saved `returnDst`

### Async Function Execution

`async fn` bodies **remain as bytecode** — they compile to chunks like regular functions. The difference is call semantics:

- `ASYNC_CALL` launches the async function's bytecode in a **new virtual thread**
- The async function runs its bytecode until it hits `AWAIT` or completes
- On `AWAIT`, the async VM instance parks its frame and waits for the future
- On completion, the async VM resumes and eventually calls `task.complete(result)`
- `ASYNC_CALL` stores the Task immediately and returns — calling frame continues executing

```kotlin
private fun runAsyncFunction(func: Value.Function, completion: CompletableFuture<Value>): Value {
    val frames = ArrayDeque<CallFrame>()
    frames.addLast(CallFrame(func.chunk))

    while (frames.isNotEmpty()) {
        val frame = frames.last()

        val word = frame.chunk.code[frame.ip++]
        val opcode = OpCode.entries.find { it.code == (word and 0xFF).toByte() }
        val dst = (word shr 8) and 0x0F
        val src1 = (word shr 12) and 0x0F
        val src2 = (word shr 16) and 0x0F
        val imm = (word shr 20) and 0xFFF

        when (opcode) {
            OpCode.AWAIT -> {
                val task = frame.regs[src1] as? Value.Task
                    ?: error("Cannot await non-Task value")
                // For simplicity, await blocks the virtual thread directly
                // (this is the async function's own thread, not the VM thread)
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

            // ... delegate all other opcodes to shared VM logic
        }
    }
    return Value.Null
}
```

---

## Value Type

### New Variant

```kotlin
sealed class Value {
    // ... existing variants ...

    /** A handle to an in-progress async operation */
    data class Task(val deferred: CompletableFuture<Value>) : Value()
}
```

`Task` wraps a `CompletableFuture<Value>`. The `T` generic is compile-time only — at runtime, `Task<String>` and `Task<Int>` are both just `Value.Task`.

---

## New Opcodes

| Opcode | Args | Description |
|--------|------|-------------|
| `ASYNC_CALL` | dst, src1, imm | Launch async function coroutine, store Task in dst |
| `AWAIT` | dst, src1 | Suspend frame, wait for Task in src1 to complete, store result in dst |
| `SPAWN` | dst, src1 | Run src1 on OS thread pool, store Task in dst |
| `SPAWN_VIRTUAL` | dst, src1 | Run src1 on virtual thread, store Task in dst |

Opcode numbers: starting at 0x2C (after REGISTER_EVENT at 0x2B).

---

## Compiler Changes

### Lexer

New keywords: `async`, `await`, `spawn`, `virtual`

New tokens:
- `KW_ASYNC`
- `KW_AWAIT`
- `KW_SPAWN`
- `KW_VIRTUAL`

### Parser

```kotlin
// Function declaration
data class FuncStmt(
    val annotations: List<Expr.AnnotationExpr>,
    val name: Token,
    val params: List<Param>,
    val returnType: Token?,
    val body: Stmt.BlockStmt,
    val isAsync: Boolean = false  // NEW
) : Stmt()

// Lambda expression
data class LambdaExpr(
    val params: List<Param>,
    val body: Stmt.BlockStmt,
    val isAsync: Boolean = false  // NEW
) : Expr()

// Await expression
data class AwaitExpr(val expr: Expr) : Expr()

// Spawn expression
data class SpawnExpr(val expr: Expr, val virtual: Boolean) : Expr()
```

Grammar additions:
```
AwaitExpr    := 'await' Expr
SpawnExpr    := 'spawn' ['virtual'] Expr
FuncDecl     := 'async'? 'fn' Ident '(' Params? ')' ('->' Type)? Block
LambdaExpr   := 'async'? 'fn' ...
```

`await` is added to `UNARY_OPS` in the parser with lowest precedence (binds least tightly).

### Type Checker

- `await <expr>`: `expr` must be `Task<T>`, returns `T` (compile error otherwise)
- `async fn` calls: return type is `Task<T>`
- `spawn <expr>`: expr must be callable, returns `Task<T>`

### IR Lowering

```
AwaitExpr        --> AWAIT
SpawnExpr        --> SPAWN (virtual=false) or SPAWN_VIRTUAL (virtual=true)
async fn call    --> ASYNC_CALL (launches coroutine, returns Task)
```

---

## Standard Library Async I/O

### Built-in Async Functions

```inklang
// Native async functions - implemented as NativeFunction returning Task
async fn http.get(url: string) -> string
async fn http.post(url: string, body: string) -> string
async fn db.query(sql: string) -> Array
async fn file.read(path: string) -> string
async fn file.write(path: string, content: string) -> null
```

### Implementation Pattern

Native async functions are `NativeFunction` values that return `Task<Value>`:

```kotlin
val httpGet = Value.NativeFunction { args ->
    val url = (args[0] as Value.String).value
    val task = CompletableFuture<Value>()

    Thread.startVirtualThread {
        try {
            val result = httpClient.get(url)
            task.complete(Value.String(result))
        } catch (e: Throwable) {
            task.completeExceptionally(e)
        }
    }

    Value.Task(task)
}
```

When bytecode calls `http.get(url)`, it executes the `NativeFunction` which returns a `Value.Task` immediately. The VM continues executing while the HTTP request runs on a virtual thread.

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| `await` on non-Task | Compile error |
| `await` on `Value.Null` | Runtime error: "Cannot await null task" |
| Awaited future throws | Exception converted to string, stored in register |
| Fire-and-forget spawn throws | Exception logged to console, lost |

---

## Edge Cases

### Await in Lambdas

```inklang
// Allowed — async lambda
let fetcher = async fn(url: string) {
    return await http.get(url)
}

// Await in non-async lambda is compile error
let bad = fn(url: string) {
    return await http.get(url)  // ERROR
}
```

### Async Methods in Classes

```inklang
class Player {
    async fn fetchData() -> string { ... }
}

let p = Player()
let data = await p.fetchData()  // works
```

### Await in Loop Conditions

```inklang
// Allowed
while await hasMore() {
    let item = await next()
    process(item)
}
```

### Nested Await

```inklang
async fn outer() -> string {
    let a = await fetchA()  // suspends, waits for A
    let b = await fetchB()  // suspends, waits for B
    return a + b
}
```

Each `await` suspends the same async function's execution. The async VM runs inside its own virtual thread and parks on each await.

---

## Examples

### Basic Async Function

```inklang
async fn fetchPlayer(name: string) -> string {
    let data = await http.get("https://api.example.com/player/" + name)
    return data
}

fn main() {
    let player = await fetchPlayer("Notch")
    print(player)
}
```

### Parallel Spawning

```inklang
fn computeHash(data: string) -> string { ... }

fn main() {
    let task1 = spawn computeHash("data1")
    let task2 = spawn computeHash("data2")
    let task3 = spawn computeHash("data3")

    let result1 = await task1
    let result2 = await task2
    let result3 = await task3
    print(result1 + result2 + result3)
}
```

### Virtual Thread for I/O

```inklang
fn main() {
    let results = []
    for url in urls {
        let task = spawn virtual http.get(url)
        results.push(task)
    }

    for task in results {
        print(await task)
    }
}
```

---

## Out of Scope

- Channels / message passing
- `select` / race conditions
- Cancellation tokens
- OS thread pool tuning

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| VM as event loop | Suspend/resume frames without blocking server tick |
| Virtual thread for VM | Event loop runs on its own virtual thread, doesn't block server |
| `async fn` bodies stay as bytecode | Consistent with existing VM architecture, no separate suspend function compilation |
| `await` parks frame, event loop waits | Clean separation between bytecode execution and I/O waiting |
| `spawn` / `spawn virtual` for threading | Explicit control over where work runs |
| `await` on non-Task is compile error | Catches mistakes early |

---

## Implementation Phases

### Phase 1: Core Infrastructure
1. Add `async`, `await`, `spawn`, `virtual` keywords to Lexer (TokenType.kt)
2. Add `isAsync` to `FuncStmt` and `LambdaExpr` (AST.kt)
3. Add `AwaitExpr`, `SpawnExpr` AST nodes (AST.kt)
4. Add `await` to `UNARY_OPS` in Parser
5. Parse `async fn`, `await expr`, `spawn [virtual] expr`
6. Add `Value.Task(deferred: CompletableFuture<Value>)` to Value enum

### Phase 2: VM Integration
1. Add `ASYNC_CALL`, `AWAIT`, `SPAWN`, `SPAWN_VIRTUAL` opcodes (OpCode.kt, IrCompiler.kt)
2. Add `suspendedFrames` map to VM
3. Implement `AWAIT` — park frame, register with future, exit inner loop
4. Implement event loop continuation — wait for future, resume frame
5. Implement `ASYNC_CALL` — launch async function on virtual thread, return Task
6. Implement `SPAWN` — run function on cached thread pool, return Task
7. Implement `SPAWN_VIRTUAL` — run function on virtual thread, return Task
8. Implement `runAsyncFunction` helper for async execution

### Phase 3: Type System
1. Add Task to type checker — `await` requires Task
2. `async fn` return type is `Task<T>`
3. Validate `spawn` target is callable

### Phase 4: Standard Library
1. Implement `http.get`, `http.post` as `NativeFunction` returning `Task`
2. Implement `db.query`, `file.read`, `file.write`
3. Wire into VM globals

### Phase 5: Testing
1. Test async function calls with await
2. Test spawn / spawn virtual
3. Test exception propagation through Task
4. Test fire-and-forget error logging
5. Integration tests with Minecraft event handlers
