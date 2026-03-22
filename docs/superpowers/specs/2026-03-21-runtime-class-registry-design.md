# Runtime Class Registry Design

## Overview

Replace the monolithic `PaperGlobals.kt` with a game-agnostic class registry that allows platform-specific runtimes (Bukkit, Sponge, Vanilla, etc.) to register class definitions at runtime. Inklang core provides the registry mechanism; the platform provides the class definitions.

## Architecture

```
org.inklang.common
├── lang/
│   ├── ClassRegistry.kt       # Game-agnostic registry (shared)
│   └── Value.kt                # Already exists - ClassDescriptor lives here

org.inklang.bukkit
└── bukkit/
    └── BukkitRuntimeRegistrar.kt  # Bukkit-specific class registration
```

## Core Registry (`org.inklang.common.lang.ClassRegistry`)

```kotlin
object ClassRegistry {
    // Map of global name -> ClassDescriptor
    private val globals = mutableMapOf<String, ClassDescriptor>()

    /**
     * Register a global class definition.
     * @param name The global variable name (e.g., "player", "server")
     * @param descriptor The class descriptor with methods
     */
    fun registerGlobal(name: String, descriptor: ClassDescriptor) {
        globals[name] = descriptor
    }

    /**
     * Get a registered global class descriptor.
     */
    fun getGlobal(name: String): ClassDescriptor? = globals[name]

    /**
     * Get all registered globals as a map suitable for VM injection.
     */
    fun getAllGlobals(): Map<String, Value> = globals.mapValues { (_, desc) -> Value.Instance(desc) }

    /**
     * Clear all registrations (useful for testing or plugin reload).
     */
    fun clear() = globals.clear()

    /**
     * Check if a global is registered.
     */
    fun hasGlobal(name: String): Boolean = globals.containsKey(name)
}
```

## Bukkit Registrar (`org.inklang.bukkit.BukkitRuntimeRegistrar`)

```kotlin
object BukkitRuntimeRegistrar {
    fun register(sender: CommandSender, server: Server) {
        ClassRegistry.registerGlobal("player", createPlayerDescriptor(sender, server))
        ClassRegistry.registerGlobal("server", createServerDescriptor(server))
        ClassRegistry.registerGlobal("world", createDefaultWorldDescriptor(server))
    }

    private fun createPlayerDescriptor(sender: CommandSender, server: Server): ClassDescriptor {
        // If sender is not a Player, return a null-typed player
        if (sender !is Player) {
            return ClassDescriptor(name = "Player", superClass = null, methods = emptyMap(), readOnly = true)
        }
        return ClassDescriptor(
            name = "Player",
            superClass = null,
            methods = mapOf(
                "name" to Value.NativeFunction { Value.String(sender.name) },
                "health" to Value.NativeFunction { Value.Double(sender.health.toDouble()) },
                // ... other methods
            )
        )
    }

    private fun createServerDescriptor(server: Server): ClassDescriptor { /* ... */ }
    private fun createDefaultWorldDescriptor(server: Server): ClassDescriptor { /* ... */ }
}
```

## Injection Point

In `InkBukkit.kt` or `PluginRuntime.kt`:

```kotlin
// At plugin enable time
BukkitRuntimeRegistrar.register(sender, server)

// At script execution time, globals are pulled from the registry
fun executeScript(script: CompiledScript, sender: CommandSender, server: Server) {
    // Register globals first (idempotent - can be called multiple times)
    BukkitRuntimeRegistrar.register(sender, server)

    // Get globals and inject into VM
    val globals = ClassRegistry.getAllGlobals()
    vm.setGlobals(globals)
    vm.execute(script)
}
```

## Key Design Decisions

1. **`ClassRegistry` is a simple map** - No events, no lifecycle. Platform calls `registerGlobal()` when ready.

2. **`Value.Instance` wrapping is automatic** - `getAllGlobals()` returns instances, not descriptors. The VM expects `Map<String, Value>`.

3. **Null sender handling** - `createPlayerDescriptor` returns an empty `ClassDescriptor` with no methods when sender isn't a Player. Ink scripts that call `player.send_message()` will fail at runtime with a clear error.

4. **Idempotent registration** - Calling `register()` multiple times overwrites previous registrations. This supports reload scenarios.

5. **Clear method for testing** - `ClassRegistry.clear()` allows tests to start fresh.

## File Split Plan

1. **Phase 1**: Create `lang/src/main/kotlin/org/inklang/lang/ClassRegistry.kt`
2. **Phase 2**: Create `BukkitRuntimeRegistrar.kt` with stub implementations
3. **Phase 3**: Move methods from `PaperGlobals.kt` into `BukkitRuntimeRegistrar.kt`
4. **Phase 4**: Delete `PaperGlobals.kt` or deprecate it
5. **Phase 5**: Add tests for `ClassRegistry`

## Testing Strategy

- Unit tests for `ClassRegistry` registration/lookup behavior
- Integration tests for `BukkitRuntimeRegistrar` with mock Bukkit objects
- VM integration tests verify globals are accessible from scripts

## Backwards Compatibility

- `PaperGlobals.getGlobals()` remains functional during transition
- Deprecate `PaperGlobals` with a warning, pointing to new approach
- No changes to compiled script format or VM opcode
