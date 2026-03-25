# Inklang - Claude Code Context

## Project Overview

**Inklang** is a compiled scripting language targeting a register-based bytecode VM. The VM is written in Kotlin (PaperMC/Bukkit plugin) and the compiler is a standalone Rust CLI tool.

- **Language**: Kotlin 2.2.21, JVM 21 (VM), Rust (compiler)
- **Build**: Gradle (Kotlin), Cargo (Rust)
- **Package**: `org.inklang`

## Repository Structure

```
inklang/
├── CLAUDE.md                    # This file
├── README.md
├── build.gradle.kts            # Root build config
├── settings.gradle.kts
├── ink/                        # VM + runtime (no compiler)
│   ├── build.gradle.kts
│   └── src/main/kotlin/org/inklang/
│       ├── Main.kt             # CLI entry (for running .ink scripts locally)
│       ├── InkContext.kt       # Runtime context interface
│       ├── InkIo.kt            # IO builtins
│       ├── InkJson.kt          # JSON builtins
│       ├── InkDb.kt           # Database builtins
│       ├── InkCompiler.kt      # DEPRECATED: JIT compiler stub, only for --help/dev
│       ├── InkScript.kt        # Compiled script representation
│       ├── CompiledScript.kt   # PEG pipeline result (also deprecated)
│       ├── ChunkSerializer.kt   # JSON <-> Chunk serialization (for .inkc loading)
│       ├── lang/               # VM instruction set (shared with compiler)
│       │   ├── OpCode.kt       # Bytecode opcodes
│       │   ├── Value.kt        # Runtime values
│       │   ├── Chunk.kt        # Bytecode container
│       │   ├── ClassRegistry.kt # Built-in classes
│       │   ├── ConfigRuntime.kt # Config loading
│       │   └── TableRuntime.kt  # Table/DB runtime
│       ├── ast/                # VM execution engine
│       │   ├── VM.kt           # Register-based VM
│       │   ├── ContextVM.kt    # VM wrapper with context + globals
│       │   └── ...
│       ├── peg/                 # PEG parser (used for grammar extensions only)
│       ├── grammar/            # Grammar package system
│       └── ssa/                 # SSA infrastructure (for VM's IR, not compiler)
├── ink-bukkit/                  # PaperMC/Bukkit plugin hosting the VM
│   ├── build.gradle.kts
│   └── src/main/kotlin/org/inklang/bukkit/
│       ├── InkPlugin.kt        # PaperMC BootstrapProvider (empty bootstrap)
│       ├── InkBukkit.kt        # Main JavaPlugin — loads .ink/.inkc, manages lifecycle
│       ├── PluginRuntime.kt    # Per-plugin VM lifecycle, event dispatch
│       ├── PluginContext.kt    # Per-plugin context (io, db, json, events)
│       ├── BukkitContext.kt    # Bukkit-specific context interface
│       ├── ScriptContext.kt    # Ad-hoc script execution context
│       ├── GlobalConfig.kt     # Global plugins.toml config
│       ├── BukkitIo.kt         # Bukkit file IO
│       ├── BukkitJson.kt       # Bukkit JSON
│       ├── BukkitDb.kt         # Bukkit SQLite
│       ├── PaperGlobals.kt     # Paper API globals (server, players, worlds)
│       ├── runtime/             # Bukkit runtime classes (Player, World, Location, etc.)
│       │   ├── BukkitRuntimeRegistrar.kt
│       │   ├── PlayerClass.kt
│       │   ├── WorldClass.kt
│       │   ├── LocationClass.kt
│       │   ├── InventoryClass.kt
│       │   ├── EntityClass.kt
│       │   └── ServerClass.kt
│       └── handlers/           # Grammar keyword handlers
│           ├── MobHandler.kt
│           ├── MobListener.kt
│           ├── PlayerHandler.kt
│           └── CommandHandler.kt
├── docs/                       # Documentation
└── gradlew
```

## Architecture: Compiler vs Runtime Separation

**The Kotlin compiler is deprecated.** All compilation happens in Printing Press (Rust).

```
┌─────────────────────────────┐     ┌─────────────────────────────┐
│   Printing Press (Rust)     │     │   ink (Kotlin) + ink-bukkit │
│   ~/dev/printing_press      │     │                             │
│                             │     │   ink/       = VM + runtime│
│   ./printing_press compile  │     │   ink-bukkit/ = plugin host │
│       script.ink             │     │                             │
│       -o script.inkc        │     │   vm.execute(chunk)         │
└──────────────┬──────────────┘     └──────────────┬──────────────┘
               │                                     │
               │  .inkc (JSON)                        │
               └─────────────────────────────────────►│
                                                     │
```

### Compilation Flow
```
Source (.ink)
    │
    ▼  printing_press (Rust CLI)
.inkc file (JSON, SerialScript format)
    │
    ▼  InkBukkit / ink VM
vm.execute(chunk)
```

### .inkc Format
`.inkc` files are JSON-serialized `InkScript` objects. The format matches `ChunkSerializer.kt`'s `SerialScript` schema exactly. See `ink/src/main/kotlin/org/inklang/ChunkSerializer.kt`.

## InkPlugin vs InkBukkit

- **`InkPlugin.kt`** — PaperMC `PluginBootstrap`. Does almost nothing on bootstrap. PaperMC requires this as the entry point for the plugin to be discovered.
- **`InkBukkit.kt`** — The actual `JavaPlugin`. Handles `/ink` commands, calls `PluginRuntime` to load plugins, and manages the server-side lifecycle.

The `InkPlugin.bootstrap()` is intentionally minimal because PaperMC needs a `BootstrapProvider` to register the plugin, but all real logic lives in `InkBukkit` which is loaded after bootstrap.

## Execution Model

Each Ink plugin gets a **persistent `ContextVM`** that lives for the server's lifetime. The VM:
1. Loads the plugin's compiled chunk
2. Executes the `enable {}` block
3. Registers event handlers from grammar declarations (`mob`, `command`, `player`)
4. Stays resident to handle events until `disable {}` is called or the server stops

## Key Design Decisions

### Register-Based VM
- 16 physical registers (R0-R15) per call frame
- 32-bit packed bytecode instructions
- SPILL/UNSPILL opcodes for register overflow

### Bytecode Format
Each 32-bit word:
```
| bits 0-7  | bits 8-11  | bits 12-15 | bits 16-19 | bits 20-31 |
| opcode    | dst (4-bit)| src1(4-bit)| src2(4-bit)| immediate  |
```

### OpCode Subset (runtime-relevant)
- `GET_FIELD`, `SET_FIELD` — object field access
- `INVOKE` — method calls
- `JUMP`, `JUMP_IF_FALSE` — branching
- `AWAIT`, `SPAWN` — async/fiber concurrency
- `HAS` — field existence check
- Plus standard arithmetic, comparison, logic opcodes

### Null Safety Operators
- `?.` (SafeCallExpr) — null-safe field access, desugared in lowerer
- `??` (ElvisExpr) — null-coalesce, desugared in lowerer

### `has` Operator
`expr has "field"` checks if an object/map has a named field. Lowered to `HasCheck` IR instruction → `HAS` opcode. VM checks own-instance fields or `__entries` map.

### Runtime Classes (Bukkit)
Built-in classes registered via `BukkitRuntimeRegistrar`:
- `Player`, `World`, `Location`, `Inventory`, `Entity`, `Server`
- All wrapped from PaperMC API types

### Grammar Package System
Plugins can declare grammar extensions via `ink/bukkit/dist/` in the plugin JAR. Handled by `PluginParserRegistry`. Built-in keywords: `mob`, `command`, `player`.

## Current Branch: feat/has-operator-v2

Active development on:
- `has` operator (implemented, SSA round-trip incomplete — blocks tests)
- Null safety operators (`?.` and `??`) (implemented, tests failing)

## Development Workflow

### Build & Test
```bash
# Build everything
./gradlew build

# Run ink module tests
./gradlew :ink:test

# Run ink-bukkit tests
./gradlew :ink-bukkit:test

# Run Paper server locally
./gradlew :ink-bukkit:runServer
```

### Printing Press (Compiler)
Located at `~/dev/printing_press`. It's a Rust CLI tool:
```bash
cd ~/dev/printing_press
cargo build --release
./target/release/printing_press compile script.ink -o script.inkc
```

### Loading Plugins
Ink plugins are loaded from `plugins/ink/plugins/*.ink` (source) or `*.inkc` (precompiled). Precompiled `.inkc` takes priority when both exist.

## Known Issues

1. **`has` operator SSA**: `SsaBuilder.kt` has `error("HasCheck not yet implemented")` at line 230. This causes all tests using `optimizedSsaRoundTrip()` to fail. The `has` operator itself is fully implemented in parser/VM/IrCompiler.
2. **58 tests failing** in `:ink:test` — cascading from the `has` SSA issue plus async/closure issues.
3. **`SafeCallExpr` lowering**: Some safe call tests failing — likely edge case in the desugaring logic.
4. **Closures**: Parsed and partially lowered, but not fully wired in SSA.
5. **Parallel method compilation**: Tests failing.

## Important Files

| File | Purpose |
|------|---------|
| `ink/src/main/kotlin/org/inklang/ast/VM.kt` | Execution engine |
| `ink/src/main/kotlin/org/inklang/ContextVM.kt` | VM wrapper with context + globals |
| `ink/src/main/kotlin/org/inklang/ChunkSerializer.kt` | JSON <-> Chunk (for .inkc loading) |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt` | Main plugin, loads/runs plugins |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt` | Per-plugin VM lifecycle |
| `ink/src/main/kotlin/org/inklang/lang/OpCode.kt` | All bytecode opcodes |
| `docs/superpowers/plans/*.md` | Implementation plans |
| `docs/superpowers/specs/2026-03-24-printing-press-compiler-design.md` | Compiler design |
| `~/dev/printing_press/` | Rust compiler (external repo) |

## Context Documents

Detailed context maintained in `.context/codebase/`:
- `ARCHITECTURE.md` — Language design and pipeline (outdated — compiler info is wrong)
- `COMPILER.md` — Compiler internals (outdated — references old Kotlin compiler)
- `VM.md` — Virtual machine details
