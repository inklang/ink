# Quill Runtime Architecture — v1 Design

> **Status:** Draft

## Overview

Quill is a compiled scripting language targeting a register-based bytecode VM. This spec covers the v1 architecture: splitting the language implementation into a platform-independent core module and a Paper runtime module, establishing the `QuillContext` interface, and building a minimal Paper plugin that can load and execute Quill scripts via commands.

## Goals

- Split lang-independent code from platform-specific code at the module boundary
- Enable the Paper plugin to host the Quill VM and provide a safe scripting API
- Load `.quill` source files at runtime via `/quill load` command
- Keep v1 surface area minimal: only `log` and `print` functions exposed to scripts
- Provide a `/quill` command suite for dynamic script loading/reloading
- Execution timeout protection via instruction counter (configurable limit)

## Non-Goals

- Precompiled bytecode format (`.qbc`) and precompilation CLI tool (v2)
- JVM plugin or WASM/JS targets (future)
- Full Paper API exposure (v1)
- Event system or scheduled tasks (v1)
- Script security sandboxing beyond what's provided by the VM's register model (future)

---

## Module Structure

```
quill/                           # Root Gradle project
├── quill-core/                  # JVM lib, no platform deps
│   └── src/main/kotlin/org/quill/
│       ├── lang/               # Token, Lexer, Parser, AST, IR, OpCode, Value, Chunk
│       ├── ast/                # AstLowerer, VM, IrCompiler, LivenessAnalyzer, RegisterAllocator
│       ├── opt/                # Optimization passes
│       ├── ssa/               # SSA infrastructure
│       ├── QuillCompiler.kt    # compile(source) API
│       └── QuillContext.kt    # Context interface (in core for testability)
├── quill-paper/                 # Paper plugin
│   └── src/main/kotlin/org/quill/paper/
│       ├── QuillPlugin.kt     # Paper plugin entry point, lifecycle
│       ├── QuillContextImpl.kt # Paper implementation of QuillContext
│       ├── ScriptManager.kt   # Loads, stores, and hot-reloads scripts
│       └── commands/          # /quill load, /quill reload, /quill list
```

### quill-core

Lang-independent JVM library. Depends only on the Kotlin standard library. Exposes:

- `QuillCompiler` — main API class with `compile()` method
- `QuillContext` — interface that runtime hosts implement
- All compiler and VM internals

### quill-paper

Paper plugin that depends on `quill-core` and Paper API. Provides `QuillContextImpl` backed by Paper's `Server` logger and `BroadcastRecipient`. No Quill logic lives here — only the platform binding.

---

## QuillContext Interface

```kotlin
package org.inklang

interface QuillContext {
    /** Info-level log output to server console */
    fun log(message: String)

    /** Player-facing output (falls back to console if no player context) */
    fun print(message: String)
}
```

**Design rationale:** Scripts never access the Paper API directly. They call `log("hi")` and `print("hi")` — the runtime decides where that output goes. In Paper, `log` writes to the server log. `print` sends to the command sender (player or console) — useful for command-driven scripts.

**Why not a single `print`?** Separating log (server ops, always visible) from print (sender-targeted, contextual) gives the runtime host control. Ops can redirect logs to files; player-facing messages can be suppressed per-context.

**Per-command context:** When a script is executed via `/quill load` or `/quill reload`, the `QuillContextImpl` is instantiated with the `CommandSender` that issued the command. This allows `print` to route output back to the correct sender.

---

## Script Model

```kotlin
class CompiledScript(
    val name: String,
    private val chunk: Chunk,
    private val constants: List<Value>
) {
    fun execute(context: QuillContext)
}
```

Scripts are identified by a `name` (derived from filename). Multiple named scripts can be loaded simultaneously. Re-loading a script with the same name replaces it.

---

## QuillCompiler API

```kotlin
package org.inklang

class QuillCompiler {
    /** Compile Quill source code to a CompiledScript */
    fun compile(source: String, name: String = "main"): CompiledScript
}
```

**Error handling:** Compilation errors (syntax errors, type errors) are raised as exceptions from `compile()`. The Paper plugin catches these and reports them to the command sender formatted as user-friendly error messages.

**Runtime errors:** When a script is executed via `CompiledScript.execute(context)`, any runtime errors (e.g., undefined variable, infinite loop timeout) are thrown as exceptions. The Paper plugin wraps `execute()` in a try-catch and formats exceptions for the command sender.

---

## Paper Plugin Bootstrap

### config.yml

```yaml
quill:
  scripts:
    directory: "quill/scripts"   # root-relative path to script files
    auto-load: false              # whether to load all scripts on server enable
  execution:
    timeout: 10000000             # max VM instructions before killing script (10M default)
```

### Startup Sequence

1. **On enable:**
   - Load `config.yml`
   - Create `QuillCompiler`
   - Create `ScriptManager`
   - Register `/quill load`, `/quill reload`, `/quill unload`, `/quill list` commands
   - If `auto-load: true`, scan script directory and load all `.quill` files

2. **On disable:**
   - Unload all scripts (no-op in v1 — no cleanup hooks)
   - Shut down cleanly

### QuillContextImpl

```kotlin
class QuillContextImpl(
    private val sender: CommandSender,
    private val server: Server
) : QuillContext {
    override fun log(message: String) {
        server.logger.info(message)
    }
    override fun print(message: String) {
        if (sender == server.consoleSender) {
            server.logger.info(message)
        } else {
            sender.sendMessage(message)
        }
    }
}
```

`QuillContextImpl` is instantiated per-command with the `CommandSender` of the command that triggered execution. This allows `print` to route output correctly whether the command came from a player or the console.

---

## Command Interface

### `/quill load <script>`

- Resolves `scripts/<script>.quill`
- Compiles the script (throws on syntax/type error)
- Stores in `ScriptManager` by name
- Prints success/failure to command sender

### `/quill reload <script>`

- Looks up existing script by name
- Waits for any in-flight execution of that script to complete (blocks the reload)
- Re-compiles from disk
- Replaces in `ScriptManager`

### `/quill unload <script>`

- Removes script from `ScriptManager`
- No cleanup hook in v1

### `/quill list`

- Lists all loaded scripts with their status
- Shows script name and bytecode size

**Thread safety:** All `/quill` commands execute on the Paper server main thread. `ScriptManager` uses a plain `Map<String, CompiledScript>` with no explicit synchronization — sequential command execution is guaranteed by Paper's command handler design.

---

## Testing Strategy

### quill-core Unit Tests

`QuillCompiler`, VM, and parser are tested using a `FakeQuillContext`:

```kotlin
class FakeQuillContext : QuillContext {
    val logs = mutableListOf<String>()
    val prints = mutableListOf<String>()
    override fun log(message: String) { logs += message }
    override fun print(message: String) { prints += message }
}
```

Tests compile a script, execute it with `FakeQuillContext`, and assert on `logs` and `prints`.

### quill-paper Integration Tests

MockBukkit tests that:
1. Load `QuillPlugin` on a mock Paper server
2. Issue `/quill load` commands
3. Verify scripts compile and execute
4. Verify `QuillContextImpl` routes `log`/`print` correctly

---

## Migration from lectern

| From | To |
|------|-----|
| `lectern/` root | `quill/` root |
| `lectern-core/` | `quill-core/` |
| `org.lectern` package | `org.inklang` package |
| `.lec` file extension | `.quill` file extension (both supported during transition) |
| `lectern-paper/` (new) | `quill-paper/` (new) |

The rebranding from `lectern` to `quill` is a separate task handled in parallel. The module structure above is the final target.

**File extension note:** Both `.lec` (existing files) and `.quill` (new files) are supported. The lexer operates on raw text — file extension is a deployment concern, not a language concern.

---

## Open Questions for v2+

- Precompiled bytecode format (`.qbc`) — define binary format and add CLI precompilation tool
- Script lifecycle hooks (`onEnable`, `onDisable`) — needed for resource cleanup, event registration
- Event system — `context.event.on('player.join', handler)` for Paper event-driven scripts
- Scheduled tasks — `context.schedule(delay, callback)` for delayed/periodic script execution
- Script security sandboxing — file/network APIs not yet scoped
- Other runtime targets (HTTP, game-agnostic)
- Script auto-load ordering and dependencies between scripts
