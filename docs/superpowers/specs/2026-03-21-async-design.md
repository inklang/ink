# Async/Await & Spawn Design

## Overview

Inklang uses an explicit async model with `async fn` declarations, `await` as a unary prefix operator, and `spawn` for explicit parallelism. The model is designed for **ease of learning** — admins write code that looks synchronous but doesn't block the server tick thread.

**Runtime:** Virtual threads (Project Loom). `async fn` runs on its own virtual thread. `await` blocks the virtual thread (cheap — platform thread is returned to pool). The VM remains a synchronous executor; it simply runs on virtual threads instead of the server tick thread.

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

### Virtual Thread Architecture

```
Server Tick Thread
    |
    v
VM.execute() runs on Virtual Thread
    |
    +---> async fn call ---> Kotlin Coroutine on virtual thread
    |                         |
    |                         +---> await ---> suspends (cheap)
    |                                            platform thread returned
    |
    +---> spawn ---> OS thread pool ---> returns Task immediately
    |
    +---> spawn virtual ---> Virtual thread ---> returns Task immediately
```

**Key insight:** Virtual threads make blocking cheap. The platform thread is returned to the pool while a virtual thread waits on I/O. The server tick is never blocked.

### VM Changes

The VM remains a synchronous executor, but:
- `VM.execute()` runs on a virtual thread (started by the caller)
- `ASYNC_CALL` launches a coroutine via `CoroutineScope.launch`
- `AWAIT` calls `deferred.await()` inside `suspendCoroutine` — blocks virtual thread, not platform thread
- `SPAWN` / `SPAWN_VIRTUAL` run on cached thread pool / virtual threads, return `Task`

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
| `AWAIT` | dst, src1 | Suspend coroutine, wait for Task in src1 to complete, store result in dst |
| `SPAWN` | dst, src1 | Run src1 on OS thread pool, store Task in dst |
| `SPAWN_VIRTUAL` | dst, src1 | Run src1 on virtual thread, store Task in dst |

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

`await` is added to `UNARY_OPS` in the parser.

### Type Checker

- `await <expr>`: `expr` must be `Task<T>`, returns `T` (compile error otherwise)
- `async fn` calls: return type is `Task<T>`
- `spawn <expr>`: expr must be callable, returns `Task<T>`

### IR Lowering

```
AwaitExpr(false)        --> AWAIT
SpawnExpr              --> SPAWN (virtual=false) or SPAWN_VIRTUAL (virtual=true)
async fn call          --> ASYNC_CALL (launches coroutine, returns Task)
```

---

## Standard Library Async I/O

### Built-in Async Functions

```inklang
// Native async functions - implemented as Kotlin suspend functions
async fn http.get(url: string) -> string
async fn http.post(url: string, body: string) -> string
async fn db.query(sql: string) -> Array
async fn file.read(path: string) -> string
async fn file.write(path: string, content: string) -> null
```

### Implementation Pattern

Native async functions are implemented as Kotlin `suspend` functions that:
1. Run blocking I/O on a thread (virtual or pooled)
2. Return the result wrapped in `Task`

```kotlin
val HttpGetFn = Value.NativeSuspendFunction { url: String ->
    // Runs on virtual thread, returns CompletableFuture
    Task(CompletableFuture.supplyAsync {
        Value.String(httpClient.get(url))
    })
}
```

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| `await` on non-Task | Compile error |
| `await` on null Task | Runtime error: "Cannot await null task" |
| Spawned task throws | Exception stored in Task, re-thrown on `await` |
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
| Virtual threads as foundation | Blocking is cheap, minimal VM redesign |
| Explicit `async fn` | Admins know when code is async |
| `await` prefix operator | Familiar from JS/Rust, clear intent |
| `spawn` defaults to OS thread | CPU-bound work goes to thread pool |
| `spawn virtual` for I/O | Lightweight thread for blocking I/O |
| `await` on non-Task is compile error | Catches mistakes early |

---

## Implementation Phases

### Phase 1: Core Infrastructure
1. Add `async`, `await`, `spawn`, `virtual` keywords to Lexer
2. Add `isAsync` to `FuncStmt` and `LambdaExpr`
3. Add `AwaitExpr`, `SpawnExpr` AST nodes
4. Parse `async fn`, `await expr`, `spawn [virtual] expr`
5. Add `Value.Task(deferred: CompletableFuture<Value>)` to Value enum

### Phase 2: VM Integration
1. Wrap `VM.execute()` to run on virtual thread
2. Add `ASYNC_CALL`, `AWAIT`, `SPAWN`, `SPAWN_VIRTUAL` opcodes
3. Implement `ASYNC_CALL` — launch coroutine, return Task immediately
4. Implement `AWAIT` — suspend coroutine via `suspendCoroutine`, call `deferred.await()`
5. Implement `SPAWN` / `SPAWN_VIRTUAL` — run on thread pool, return Task

### Phase 3: Type System
1. Add Task to type checker — `await` requires Task
2. `async fn` return type is `Task<T>`
3. Validate `spawn` target is callable

### Phase 4: Standard Library
1. Implement `http.get`, `http.post` as native suspend functions
2. Implement `db.query`, `file.read`, `file.write`

### Phase 5: Testing
1. Test async function calls with await
2. Test spawn / spawn virtual
3. Test exception propagation through Task
4. Test fire-and-forget error logging
