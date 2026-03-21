# Async/Await & Spawn Design

## Overview

inklang uses an explicit async model with `async fn` declarations, `await` as a unary prefix operator, and `spawn` for explicit threading. Virtual threads are available via `spawn virtual`. The model is intentionally discrete — the programmer always knows when code is running asynchronously.

**Runtime:** Kotlin coroutines on the JVM. `async fn` maps to `suspend` functions. `await` suspends the coroutine without blocking the thread. This avoids redesigning the VM into an event-loop — the VM itself remains a simple synchronous executor, but supports coroutine suspension points.

---

## Syntax

### Function Declarations

```inklang
// Async function — must be called with await
async fn fetchData(url: string) -> string { ... }

// Regular synchronous function
fn processData(data: string) -> string { ... }

// Async lambda
let fetcher = async fn(url: string) -> string { ... }
```

### Await

```inklang
// await is a unary prefix operator
let result = await fetchData("http://...")

// await works on any Task
let task = fetchData("http://...")  // task is Task<string>
let result = await task
```

**Rule:** `await` on a non-Task expression is a **compile-time error**.

### Spawn

```inklang
// spawn on OS thread (default — for CPU-bound or blocking work)
let task = spawn processData("heavy data")

// spawn on virtual thread (for I/O or lightweight concurrent tasks)
let task = spawn virtual processData("data")

// Fire-and-forget: spawn without storing task (result discarded on completion)
spawn processData("heavy data")

// Await the spawned task
let result = await task
```

**Both forms return a `Task<T>`.**

---

## Types

### `Task<T>`

Represents a concurrent operation that will produce a value of type `T`.

- Created by: `async fn` call, `spawn`, `spawn virtual`
- Consumed by: `await` operator

`Task<T>` is an opaque type — users interact with it only via `await`.

### Implicit Behavior

| Expression | Thread | Returns |
|------------|--------|---------|
| `async fn` call without await | Starts async, returns immediately | `Task<T>` |
| `async fn` call with `await` | Suspends coroutine, resumes when complete | `T` |
| `spawn expr` | OS thread pool | `Task<T>` |
| `spawn virtual expr` | Virtual thread | `Task<T>` |
| Regular `fn` call | Current thread | `T` (blocks) |

---

## Runtime Architecture

### Kotlin Coroutines Integration

The VM delegates async execution to Kotlin coroutines:

```
inklang code
    |
    v
VM.execute() [synchronous]
    |---> async fn call
    |         |
    |         v
    |     Kotlin Coroutine (suspend function)
    |         |
    |         v
    |     AWAIT opcode --> suspends coroutine, returns to VM
    |                        VM continues other work
    |                        ...
    |     later: coroutine resumes on completion
    |
    |---> spawn
              |
              v
          Kotlin coroutine scope.launch()
              |
              v
          returns Task<T> immediately
```

### Threading Model

- **`async fn`**: Runs on the **calling thread** (which may be a virtual thread from a previous `spawn virtual`). When `await` is encountered, the coroutine **suspends** and yields control back to the VM. The thread is NOT blocked.

- **`spawn`**: Runs on a **cached thread pool** (`Executors.newCachedThreadPool()`). Returns a `Task<T>` immediately.

- **`spawn virtual`**: Runs on a **virtual thread** (`Thread.ofVirtual().start { ... }`). Returns a `Task<T>` immediately.

### `Task<T>` Implementation

```kotlin
// Value.Task wraps a Kotlin CompletableFuture
data class Task(val future: CompletableFuture<Value>) : Value()

// Task.await() uses future.get() — blocking on the VM thread
// BUT: since async fn uses suspend functions, the VM itself never blocks
// It only calls await on already-suspended coroutines
```

### Await Semantics

`await` does **NOT** block the VM thread. Instead:

1. The coroutine is already suspended at the `await` point (from `ASYNC_CALL`)
2. `AWAIT` calls `future.join()` on a **virtual thread** that was handling the async work
3. Since it's a virtual thread, blocking is cheap
4. The result is stored in the call frame's register
5. The coroutine resumes

**Implementation path:** The VM gains a `CoroutineScope` or `KotlinCoroutineContext`. `async fn` calls create a new coroutine via `scope.launch { asyncFn(...) }`. The returned `Task<T>` holds the `CompletableFuture`. `AWAIT` suspends the calling coroutine by calling `await(task.future)` — but this is inside a `suspendCoroutine` block, so it doesn't block the VM.

### VM Changes

The VM itself stays mostly the same — it gains:
- A `CoroutineScope` field for launching async work
- `ASYNC_CALL` opcode to launch coroutines without blocking
- `AWAIT` opcode that suspends the current coroutine
- `SPAWN` / `SPAWN_VIRTUAL` opcodes that launch in background and return `Task`
- `Value.Task` variant wrapping `CompletableFuture<Value>`

---

## Value Type

### New Variant

```kotlin
sealed class Value {
    // ... existing variants ...

    /** A handle to an in-progress async operation */
    data class Task(val future: CompletableFuture<Value>) : Value()
}
```

### Type System

- `Task<T>` is treated as a first-class type, similar to `int`, `string`, etc.
- `await` requires `Task<T>` — compile error otherwise
- `async fn fetchData() -> string` has return type `Task<string>` at the type level
- The type checker validates `await` expressions

---

## Compiler Changes

### Lexer

New keywords: `async`, `await`, `spawn`, `virtual`

### Parser

```kotlin
// Function declaration
data class FuncStmt(
    val name: String,
    val params: List<Param>,
    val returnType: Type?,
    val body: Stmt.BlockStmt,
    val isAsync: Boolean  // NEW: async modifier
) : Stmt()

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
```

### Type Checker

- `await <expr>`: `expr` must be `Task<T>`, returns `T`
- `async fn` calls: return type is `Task<T>` (where T is the declared return type)
- `spawn <expr>`: expr must be a callable (function), returns `Task<T>`
- `spawn virtual <expr>`: same, but schedules on virtual thread

### IR Lowering

```
AwaitExpr        --> AWAIT
SpawnExpr(false) --> SPAWN
SpawnExpr(true)  --> SPAWN_VIRTUAL
async fn call    --> ASYNC_CALL (returns Task, does not suspend)
```

---

## Opcodes

| Opcode | Args | Description |
|--------|------|-------------|
| `ASYNC_CALL` | dst, src1, imm | Launch async function, store Task in dst. Does not suspend. |
| `AWAIT` | dst, src1 | Suspend coroutine until Task in src1 completes, store result in dst |
| `SPAWN` | dst, src1 | Run src1 (must be function) on OS thread pool, store Task in dst |
| `SPAWN_VIRTUAL` | dst, src1 | Run src1 on virtual thread, store Task in dst |

Existing opcodes unchanged.

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| `await` on non-Task | Compile error |
| `await` on null Task | Runtime error: "Cannot await null task" |
| Spawned task throws | Exception stored in Task, re-thrown on `await` |
| Fire-and-forget spawn throws | Exception lost (no Task to await) |

**Note:** For fire-and-forget, consider logging uncaught exceptions to help debugging, but this is implementation detail.

---

## Edge Cases

### Await in Lambdas

```inklang
// Allowed — lambda that awaits
let fetcher = async fn(url: string) {
    return await http.get(url)
}

// Await in non-async lambda is compile error
let bad = fn(url: string) {
    return await http.get(url)  // ERROR: cannot await in non-async lambda
}
```

### Async Methods in Classes

```inklang
class Player {
    async fn fetchData() -> string { ... }
}

let p = Player()
let data = await p.fetchData()  // works via GET_FIELD + ASYNC_CALL
```

The method's `isAsync` flag is preserved in the class descriptor. Method invocation via `GET_FIELD` + `CALL` / `ASYNC_CALL` checks the flag.

### Await in Loop Conditions

```inklang
// Allowed
while await hasMore() {
    let item = await next()
    process(item)
}
```

`await` can appear anywhere an expression is allowed, including loop conditions and `if` conditions.

### Nested Await

```inklang
async fn outer() -> string {
    let a = await fetchA()  // suspends, waits for A
    let b = await fetchB()  // suspends, waits for B
    return a + b
}
```

Each `await` suspends the same coroutine. No additional threads are used within `async fn`.

---

## Standard Library

### Built-in Async Functions (Native)

```inklang
// Native async functions implemented as Kotlin suspend functions
async fn db.query(sql: string) -> Array
async fn http.get(url: string) -> string
async fn http.post(url: string, body: string) -> string
async fn file.read(path: string) -> string
async fn file.write(path: string, content: string) -> null
```

These are implemented as `Value.NativeFunction` that internally launch Kotlin coroutines. The `async fn` syntax is syntactic sugar over the native function pattern.

### Example Implementation

```kotlin
val HttpGetFn = Value.NativeFunction { args ->
    val url = (args[0] as Value.String).value
    // Returns a Task that the VM can await
    Task(CompletableFuture.supplyAsync {
        Value.String(httpGetSync(url))  // blocking HTTP on some thread
    })
}
```

For native async functions, the Kotlin implementation decides whether to use a virtual thread or the OS pool internally.

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
        // spawn virtual = lightweight thread for I/O
        let task = spawn virtual http.get(url)
        results.push(task)
    }

    for task in results {
        print(await task)
    }
}
```

### Mixing Async and Spawn

```inklang
async fn fetchAll(urls: Array) -> Array {
    let tasks = []
    for url in urls {
        tasks.push(spawn virtual http.get(url))
    }

    let results = []
    for task in tasks {
        results.push(await task)
    }
    return results
}
```

---

## Out of Scope

- Channels / message passing — future consideration
- `select` / race conditions — future consideration
- Cancellation tokens — future consideration
- OS thread pool tuning — JVM defaults used

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Kotlin coroutines as foundation | Clean async/suspend without redesigning the VM |
| Explicit `async fn` | New programmers know when code is async |
| `await` prefix operator | Familiar from JS/Rust; clear intent |
| `spawn` defaults to OS thread | Most powerful default; virtual is opt-in |
| `spawn virtual` for virtual threads | Lightweight for I/O; explicit when you want it |
| `Task<T>` as opaque return type | Users only interact via `await` |
| `await` on non-Task is compile error | Catches mistakes early |
| Task holds `CompletableFuture` | Standard JVM async primitive, works with coroutines |

---

## Implementation Phases

### Phase 1: Core Infrastructure
1. Add `async`, `await`, `spawn`, `virtual` keywords to Lexer
2. Add `FuncStmt.isAsync`, `AwaitExpr`, `SpawnExpr` to AST
3. Parse `async fn`, `await expr`, `spawn [virtual] expr`
4. Add `Value.Task(future: CompletableFuture<Value>)` to Value enum
5. Add `ASYNC_CALL`, `AWAIT`, `SPAWN`, `SPAWN_VIRTUAL` opcodes to OpCode enum

### Phase 2: VM Integration
1. Add `CoroutineScope` to VM
2. Implement `ASYNC_CALL` — launch coroutine, return Task immediately
3. Implement `AWAIT` — suspend current coroutine until future completes
4. Implement `SPAWN` / `SPAWN_VIRTUAL` — run on thread pool, return Task
5. Handle Task exceptions in await

### Phase 3: Type System
1. Add Task to type checker — `await` requires Task
2. `async fn` return type is Task<T>
3. Validate `spawn` target is callable

### Phase 4: Standard Library
1. Implement `http.get`, `http.post` as native async functions
2. Integrate with existing TableRuntime, ConfigRuntime

### Phase 5: Testing & Polish
1. Exception propagation through Task
2. Fire-and-forget spawn error logging
3. Documentation and examples
