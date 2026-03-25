# Ink Language Design Specification

**Version:** 0.1.0
**Date:** 2026-03-21
**Status:** Approved for implementation

---

## 1. Overview

Ink is a composable scripting language for Minecraft server administrators and plugin developers. It provides a base language with an extensible grammar, allowing package authors to add custom syntax and language features that script authors consume without needing to understand the underlying implementation.

The design philosophy is **layers, not hacks**: Ink does not try to be all things to all people. A clean separation between Paper API (Kotlin), Ink packages (Kotlin, the FFI layer), and Ink scripts (.ink files) means version compatibility, performance, and expressiveness live at the right layer.

---

## 2. Architecture Layers

```
┌─────────────────────────────────────────────┐
│  Ink Scripts (.ink files)                   │  ← Server admins write these
├─────────────────────────────────────────────┤
│  Ink Packages (Kotlin)                     │  ← Plugin devs write these
│  - Grammar extensions                       │
│  - pub script functions (FFI to Paper)      │
│  - Declarative shells                       │
├─────────────────────────────────────────────┤
│  Ink VM (bytecode executor)                 │  ← Fixed instruction set
├─────────────────────────────────────────────┤
│  Paper API / NMS                             │  ← Version-specific, patched
│                                             │    by package authors, not
│                                             │    script authors
└─────────────────────────────────────────────┘
```

**Key principle:** Scripts never call Paper directly. All Paper interop goes through `pub script` functions provided by packages. This insulates script authors from Minecraft version churn.

---

## 3. Grammar Extension System

Ink supports a tiered grammar extension model. Package authors pick the tier that matches their skill level and ambition.

### Tier 1 — Declarative Shells

Package authors describe domain entities using a structured DSL. No grammar knowledge required.

```kotlin
ink.registerDeclaration("mob") {
    fields {
        field("health", IntType)
        field("name", StringType)
    }
    blocks {
        block("on spawn")
        block("on death")
        block("on damage")
    }
}
```

Result: Script authors write:
```
mob Zombie {
    name = "§cFast Zombie"
    health = 40

    on spawn { ... }
    on death { ... }
}
```

The compiler auto-generates event bindings from the declared blocks.

### Tier 2 — Statement Extensions

For custom control flow and domain-specific statements.

```kotlin
ink.registerStatement("when") {
    pattern = keyword("when") + expression + block
    lower { expr, body ->
        CallRuntime("event_bind", expr, body)
    }
}
```

Script authors write:
```
when entity is type("ZOMBIE") {
    despawn()
}
```

### Tier 3 — Raw PEG Rules

Full grammar extension for advanced users. Package authors write PEG-like rules directly.

This is an escape hatch, not the default API. Most package authors never need this.

### Conflict Resolution

Two packages may register the same keyword. Resolution order:
1. Explicit namespace: `quests::when` disambiguates
2. Conflict is a build-time error; package manifest declares which keywords it owns
3. Future: explicit conflict resolution in manifest

---

## 4. Type System

### VM-Level Types (First-Class)

| Type       | Description                                      |
|------------|--------------------------------------------------|
| `Int`      | 64-bit signed integer                            |
| `Float`    | 64-bit IEEE 754 double                           |
| `Bool`     | True / false                                     |
| `String`   | UTF-8 immutable string                           |
| `Object`   | Dynamic key-value map with typed fields          |
| `List`     | Homogeneous list of any first-class type         |
| `Vector3`  | Minecraft 3D coordinate (x, y, z as Floats)      |
| `EntityRef`| Reference to a live Minecraft entity            |

### Why Vector3 and EntityRef First-Class

Minecraft is a 3D game. Any script dealing with locations, directions, or entity interactions would constantly pack/unpack coordinates as three separate floats. First-class Vector3 avoids this ergonomics cliff.

EntityRef is the natural handle for event targets — `on damage` handlers receive the damaged entity as an EntityRef, not a raw UUID or integer ID.

### User-Defined Types

Types beyond the first-class set are represented as `Object` with a type tag. Package authors using Tier 1 declarative shells get typed fields with validation.

---

## 5. VM Architecture

### Register-Based

The VM uses a **register-based instruction set** (not stack-based). This matches the design already proven in Quill — register allocation, SSA, and spill code are well-understood techniques that produce efficient bytecode.

- 16 physical registers per call frame (R0–R15)
- 32-bit packed bytecode instructions
- SPILL/UNSPILL opcodes for register overflow

This work from Quill transfers directly. Only the frontend (lexer/parser/AST) needs redesign for extensibility.

### Closed Instruction Set

All grammar extensions, regardless of tier, lower to a **fixed set of VM primitives**:

| Opcode            | Description                                      |
|-------------------|--------------------------------------------------|
| `LOAD`            | Load local or argument into register             |
| `STORE`           | Store register value to local                    |
| `CALL`            | Call function (may be package runtime or user)   |
| `JUMP`            | Unconditional jump to label                      |
| `JUMP_IF_FALSE`   | Conditional jump on false/null                  |
| `RETURN`          | Return from function with optional value         |
| `SPAWN_COROUTINE` | Create new coroutine                          |
| `YIELD`           | Suspend current coroutine                        |
| `RESUME`          | Resume a suspended coroutine                     |
| `DISPATCH_EVENT`  | Register/bind event handler                     |
| `GET_FIELD`       | Access object field by name                      |
| `SET_FIELD`       | Mutate object field                              |
| `LIST_NEW`        | Allocate new list                                |
| `LIST_APPEND`     | Append to list                                   |
| `VEC3_NEW`        | Allocate new Vector3                             |
| `VEC3_GET_X/Y/Z`  | Extract coordinate component                     |

> **Note:** Arithmetic opcodes (ADD, SUB, MUL, DIV, MOD, NEG, CMP) and comparison opcodes are required and omitted from this table for brevity. They are obvious in scope and will be enumerated fully during implementation.

**No extensible opcodes.** Packages never introduce new VM ops. Tier 3 grammar extensions lower to calls and jumps like everything else. This avoids the versioning and compatibility nightmares of open bytecode systems.

### Coroutine Lifecycle

Ink supports two coroutine models:

**Entity-bound (default):** Coroutines attached to a Minecraft entity lifecycle. Entity-bound coroutines are rooted in the entity's context. When the entity dies, its context is removed from the GC root set — the next GC cycle reclaims all associated stack frames and reachable objects. No manual cleanup, no reference counting cycles, no finalizers.

```ink
on spawn {
    every 5 seconds {
        // this coroutine is bound to the entity
        // auto-killed when entity dies
    }
}
```

**Independent (explicit):** User spawns a background coroutine with `spawn {}`. The author is explicitly responsible for its lifecycle and must use `cancel` to clean it up. Independent coroutines outlive the trigger.

```ink
spawn {
    loop {
        broadcast("timer tick")
        wait 60
    }
}
```

This distinction is intentional. Leaking background coroutines on a Minecraft server is a real performance problem. The dangerous operation is the verbose one.

### Event System

Two-level event handling:

**Auto-generated bindings (Tier 1):** When a script uses `on spawn { }` inside a `mob { }` block, the compiler auto-emits the DISPATCH_EVENT binding. Script authors don't call DISPATCH_EVENT directly.

**Manual wiring (Tier 2/3):** Package authors writing custom statements wire DISPATCH_EVENT manually in their lowering logic.

---

## 6. Module and Package System

### Namespace Hierarchy

Packages expose a `package::function` naming hierarchy:

```
mobs::spawn
mobs::despawn
quests::complete
ink-core::event_bind
```

Packages declare dependencies explicitly in the manifest. A package can only call another package's `pub` or `pub script` functions if it declares a dependency.

### Visibility Model

Three explicit visibility levels. Default is **private** — safe by default.

| Level          | From other packages | From .ink scripts | Use case                          |
|----------------|--------------------|-------------------|-----------------------------------|
| `pub script`   | Yes                | Yes               | Script-safe FFI functions        |
| `pub`          | Yes                | No                | Package infrastructure, registration APIs |
| `fun` (none)   | No                 | No                | Internal helpers, implementation details |

```kotlin
// mobs package
pub script fun spawn(type: String, loc: Vector3) { ... }  // scripts AND packages
pub fun register_mob(def: MobDefinition) { ... }           // packages only, not scripts
fun selectSpawnPoint(loc: Vector3) { ... }                  // internal only
```

**Why separate `pub` from `pub script`?** `mobs::register_mob` is a registration API callable only at startup — if a script calls it mid-execution, you get corrupted state. The type system can't catch this (it's a timing/context problem, not a type problem). `pub script` forces package authors to consciously declare which functions are safe for script context.

---

## 7. Error Handling

### At Package Boundaries: Result Types

All `pub` and `pub script` functions use `Result<T>` as their return type. Explicit success/failure contracts at package boundaries.

```kotlin
pub script fun spawn(type: String, loc: Vector3): Result<EntityRef>
```

Script authors handle errors explicitly:
```
result = mobs::spawn("ZOMBIE", player.location)
if result.is_err {
    print("failed to spawn: " + result.error)  // error is a String
}
```

**Error type:** `Result<T>` has `is_err: Bool`, `value: T` (valid when `is_err` is false), and `error: String` (valid when `is_err` is true). Error messages are human-readable strings intended for display and logging, not programmatic error categorization.

### At Tier 1: Graceful Degradation

Declarative shells swallow errors with logging rather than crashing. A malformed `mob { }` definition logs a warning and skips the entity rather than propagating an exception.

This is intentional: a bad mob definition from a server admin should not take down the entire server.

### Inside the VM

The VM uses exceptions internally but they do not leak to scripts. All VM exceptions are caught at package boundary functions and converted to `Result<T>`.

---

## 8. Package Distribution

### v1: Local JAR Drop

Packages are `.jar` files dropped into a `/packages` directory. At startup, Ink loads all packages, collects grammar extensions, builds the combined grammar, then runs user scripts.

### Future: Package Registry

A registry can be added in v2 without breaking existing packages, because the manifest format is self-describing.

**Package manifest must include from day one:**
- Package name
- Version (semver)
- Ink version compatibility constraint (`ink_version`)
- Dependency list

This allows a future registry to resolve versions, check compatibility, and index packages without requiring a special build step.

---

## 9. Package Manifest Format

TOML (not Kotlin DSL, not JSON).

Kotlin DSL feels natural but is a trap — the manifest becomes executable code, which makes static analysis impossible without a JVM. A registry that wants to index package metadata, check dependencies, or verify compatibility cannot parse a Kotlin DSL manifest without running it.

TOML is parseable without execution, has comment support, and is the standard in the Rust/Cargo ecosystem which is the closest analogous ecosystem.

```toml
[package]
name = "mobs"
version = "1.0.0"
ink_version = ">=0.3.0"
description = "First-class mob definitions for Ink"

[dependencies]
ink-core = "0.3.0"
ink-events = ">=0.2.0"

[visibility]
pub_script = ["mobs::spawn", "mobs::despawn"]
pub = ["mobs::register_mob", "mobs::register_blueprint"]
```

The `[visibility]` block is a machine-readable API surface declaration. A registry or IDE tooling can consume it without parsing Kotlin source.

---

## 10. Grammar Extension API (Package Author Perspective)

### Tier 1 — Declarative Shell Registration

```kotlin
ink.registerDeclaration("mob") {
    fields {
        field("health", IntType)
        field("name", StringType)
    }
    blocks {
        block("on spawn")
        block("on damage")
        block("on death")
    }
}
```

### Tier 2 — Statement Extension Registration

```kotlin
ink.registerStatement("when") {
    pattern = keyword("when") + expression + block
    lower { ctx ->
        val condition = ctx.expr(0)
        val body = ctx.block(1)
        CallRuntime("event_bind", condition, body)
    }
}
```

### Tier 3 — PEG Rule Registration (escape hatch)

```kotlin
ink.registerRule("my_operator") {
    PEGSequence(
        PEGKeyword("given"),
        PEGExpression,
        PEGBlock
    )
}
```

---

## 11. Memory Management

### Garbage Collection Strategy

Ink uses a **tracing mark-and-sweep garbage collector** for heap-allocated objects (Object, List, String, Vector3, EntityRef). This choice is deliberate: reference counting would break on cycles, and entity-bound coroutines holding references to other entities is exactly the kind of cycle that occurs in practice (a mob referencing its spawner, which references the mob).

**Object header design is generational-friendly.** Each heap object includes a header with:
- Object kind tag (String, Object, List, Vector3, EntityRef, Closure, Coroutine)
- Mark bit for the current GC cycle
- Size field

The header format is designed to support **generational GC in a future iteration** — most Ink objects are short-lived (event handler locals, temporary Vector3s, intermediate values). Starting with mark-and-sweep does not preclude adding generational collection later.

**Coroutine cleanup is a GC root question.** Entity-bound coroutines are rooted in the entity's lifecycle context. When an entity dies, its coroutine context is removed from the root set. The next GC collection cycle naturally reclaims all objects reachable only from that context. No special finalization mechanism is needed.

**Roots:**
- Global variables
- Call frame locals
- Entity-bound coroutine stacks
- Independent coroutine stacks (until cancelled)

### Garbage Collection Trigger

GC runs:
- When allocation exceeds a threshold (simple headroom check, not generational promotion)
- When a coroutine yields (opportunistic)
- On explicit `collect()` call (available in debug packages only)

---

## 12. Standard Library (Built-ins)

These functions are available in all Ink scripts without importing anything. They are provided by the VM runtime, not by any package.

### Type Construction

```
Vector3(x, y, z)        → Vector3   // Construct a 3D coordinate
EntityRef(uuid)          → EntityRef // Construct an entity reference from UUID string
```

### Type Introspection

```
typeOf(value)           → String     // Returns the type name as a string
```

### String Operations

```
str(value)              → String     // Convert any value to string
format("{}", a, b, ...) → String    // Interpolate values into format string
len(s)                  → Int        // String length
substring(s, start, end) → String  // Extract substring
split(s, delim)         → List       // Split string into list
trim(s)                 → String     // Remove leading/trailing whitespace
```

### Math Operations

```
floor(f), ceil(f), abs(i|f)
min(a, b), max(a, b)
sqrt(f), pow(f, exp)
rand()                  → Float      // Random Float in [0, 1)
randInt(min, max)       → Int        // Random integer in [min, max]
```

### Collection Constructors

```
listOf(a, b, ...)      → List       // Construct a list from arguments
mapOf(k1, v1, k2, v2) → Object      // Construct a map from key-value pairs
get(m, key)             → Value      // Map/Object lookup
set(m, key, value)                  // Map/Object mutation
```

### Control Flow

```
wait(seconds)           → ()         // Suspend coroutine and resume after N seconds (not available in Tier 1 event handlers — use `every` instead)
cancel(coroutine)                   // Cancel an independent coroutine
```

**Note:** `wait` is not available in Tier 1 event handlers because those run synchronously in the entity lifecycle — yielding would desync the entity state. Tier 1 handlers should use `every` or `on` for timed behavior.

---

## 13. Package Loading and Initialization

### Startup Sequence

1. Discover all `.jar` packages in `/packages` directory
2. Parse each package's `manifest.toml` to collect grammar extensions and dependency declarations
3. **Topological sort** of packages by dependency graph to determine load order
4. Initialize each package in sorted order:
   - Load Kotlin classes
   - Register grammar extensions (Tier 1/2/3)
   - Wire public function table
   - Register `pub` and `pub script` functions
5. Build combined grammar from all registered extensions
6. Parse and execute user scripts against combined grammar

### Circular Dependencies

Circular dependencies between packages (A depends on B, B depends on A) are a **build-time error**. The topological sort detects cycles and reports which packages are involved. Package authors must resolve cycles before the package can load.

This is intentional. Circular runtime dependencies in a Minecraft server context lead to subtle initialization order bugs that are hard to debug. Better to fail fast at load time.

### Conflict Resolution (Permanent Policy)

When two packages register the same keyword without namespace disambiguation, the conflict is a **build-time error**, not a runtime override. The error message names both packages and the conflicting keyword.

`quests::when` is the correct way to use `when` from the quests package. Using `when` directly when two packages both register it is an error.

**Rationale:** Implicit conflict resolution (one wins, one loses) is a source of fragile scripts. When package A updates and changes its `when` behavior, scripts that relied on the implicit resolution silently break. Explicit namespace disambiguation is the only robust policy.

---

## 14. Open Questions (Deferred to Implementation)

These are deliberately left unspecified for now:

- **Full instruction set enumeration** — arithmetic opcodes (ADD, SUB, MUL, DIV, MOD, POW, NEG) are required and will be enumerated during implementation. The opcode table in Section 5 is incomplete but the missing opcodes are obvious.
- **Specific PEG library** — the frontend will use a parser combinator backbone, but the library choice is an implementation detail.
- **Coroutine scheduling for independent coroutines** — time-driven FIFO with wait resumption. Independent coroutines run on a scheduler: when a coroutine calls `wait t`, it yields and is re-queued to resume after `t` seconds. Multiple waiting coroutines are resumed in spawn order (FIFO). This is independent of entity-bound event handlers.
- **IDE support** — LSP protocol for .ink files, grammar-aware completion.

---

## 15. Design Principles

1. **Scripts are for admins, packages are for developers.** Different audiences, different skill levels, different APIs.
2. **Closed VM, open grammar.** All extensions lower to a fixed instruction set. No extensible opcodes.
3. **Timing problems need visibility markers.** `pub script` exists because the type system cannot catch "called at the wrong time" errors.
4. **Safe by default.** Private-by-default visibility. Graceful Tier 1 degradation. Entity-bound coroutines auto-cleanup.
5. **TOML manifests, not executable manifests.** Static analysis is necessary for registries, tooling, and compatibility checking.
6. **Vector3 and EntityRef are first-class.** Minecraft is a 3D world with entities. Ergonomics at the script layer matter.
