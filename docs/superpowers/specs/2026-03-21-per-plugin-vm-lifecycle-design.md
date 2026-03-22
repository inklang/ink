# Per-Plugin Persistent VM Lifecycle — Design Spec

## Status
Draft

## Overview

Each Ink plugin (`enable {}` / `disable {}`) runs on a persistent `ContextVM` that lives for the server lifetime. Dynamic scripts (`/ink run`) continue to get a fresh `ContextVM` per execution (no change needed there).

This enables:
- Event handler closures that capture persistent plugin state
- Plugins that hold mutable state across event calls
- Proper `enable {}` → event loop → `disable {}` lifecycle

## Problem

Currently `PluginRuntime` creates a fresh `ContextVM` for every execution:
- `context.onEnable(enableScript)` → `InkScript.execute()` → `new ContextVM(context)` → discarded
- Event handlers register to `VM.globals["__eventRegistry"]` which dies with the VM
- On disable, a NEW `ContextVM` is created — it has no event handlers because they were in the old VM

Result: Event handlers don't actually persist. `disable {}` can't fire events because it runs in a different VM.

## Design

### Plugin Side: Persistent VM per plugin

**`PluginRuntime`** creates and owns one `ContextVM` per plugin:

```kotlin
class PluginRuntime(
    private val plugin: InkBukkit,
    private val globalConfig: GlobalConfig
) {
    private val compiler = InkCompiler()
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()

    data class LoadedPlugin(
        val name: String,
        val script: InkScript,
        val enableScript: InkScript,
        val disableScript: InkScript?,
        val context: PluginContext,
        val folder: File,
        val vm: ContextVM  // NEW: persistent per-plugin VM
    )

    fun loadPlugin(pluginFile: File): Result<LoadedPlugin> {
        // ... compile script ...
        val vm = ContextVM(context)  // Create VM once
        context.setVM(vm)             // Give context access

        // Execute enable - registers events to vm.globals
        vm.execute(enableScript.getChunk())

        // Store plugin with its VM
        loadedPlugins[pluginName] = LoadedPlugin(...)
    }

    fun unloadPlugin(pluginName: String) {
        val loaded = loadedPlugins.remove(pluginName)
        loaded?.vm?.execute(loaded.disableScript.getChunk())  // disable in same VM
    }
}
```

**`PluginContext`** holds a reference to its VM:

```kotlin
class PluginContext(
    private val sender: CommandSender,
    private val plugin: InkBukkit,
    private val io: InkIo,
    private val json: InkJson,
    private val db: InkDb,
    private val pluginName: String,
    private val pluginFolder: File
) : InkContext {
    private var vm: ContextVM? = null  // Set by PluginRuntime

    fun setVM(vm: ContextVM) { this.vm = vm }

    override fun onEnable(script: InkScript) {
        // VM already executing enable {} during loadPlugin()
        // This is called during loadPlugin() itself - no-op since VM is already running
    }

    override fun onDisable(script: InkScript) {
        // Called by PluginRuntime.unloadPlugin() - execute in same VM
        vm?.execute(script.getChunk())
    }

    override fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean {
        val vm = this.vm ?: return false
        // Look up handlers in vm.globals["__eventRegistry"]
        // Execute each handler in the plugin's persistent VM
    }
}
```

**`ContextVM`** needs to expose `execute(chunk)` publicly so `PluginRuntime` can call it:

```kotlin
class ContextVM(
    private val context: InkContext,
    private val maxInstructions: Int = 0
) {
    val globals = mutableMapOf<String, Value>(...)

    fun execute(chunk: Chunk) {  // Made public - called by PluginRuntime
        val frames = ArrayDeque<CallFrame>()
        frames.addLast(CallFrame(chunk))
        // ... execution loop ...
    }

    fun setGlobals(overrides: Map<String, Value>) {
        globals.putAll(overrides)
    }
}
```

### Dynamic Script Side: Unchanged

`InkBukkit.runScript()` already creates a fresh `ContextVM` per execution:

```kotlin
private fun runScript(sender: CommandSender, scriptName: String) {
    val compiled = scriptCache.getOrPut(...) { compiler.compile(...) }
    val context = ScriptContext(sender, this, ioDriver, jsonDriver, dbDriver)
    val vm = ContextVM(context)  // Fresh per execution
    val preloadedConfigs = compiled.preloadConfigs(scriptDir.absolutePath)
    vm.setGlobals(preloadedConfigs)
    vm.execute(compiled.getChunk())  // One-shot
}
```

No changes needed here.

## Event Firing

When a Bukkit event fires (player join, block break, etc.):

```
Bukkit event listener (InkBukkit)
  → PluginRuntime.fireEvent(eventName, event, data)
    → For each loaded plugin:
        → plugin.vm.execute(handler.getChunk())
```

Each handler runs in the plugin's own VM with its own globals — closures capture plugin state.

## Data Flow

```
Server start
  └─> PluginRuntime.loadPlugin("myplugin")
        ├─> compile script → InkScript
        ├─> create ContextVM(context)  ← persistent VM born
        ├─> vm.setGlobals(preloadedConfigs)
        ├─> vm.execute(enableScript.getChunk())
        │     └─> registers event handlers to vm.globals["__eventRegistry"]
        │     └─> captures state in vm.globals
        └─> stored: LoadedPlugin(name, context, vm, ...)
              └─> plugin.context.vm = vm  (context gets reference)

Player joins
  └─> InkBukkit.onPlayerJoin(event)
        └─> PluginRuntime.fireEvent("player_join", event, ...)
              └─> for each LoadedPlugin:
                    └─> plugin.vm.execute(handler.getChunk())  ← same VM as enable

Server stops
  └─> PluginRuntime.unloadAll()
        └─> for each plugin:
              └─> plugin.vm.execute(disableScript.getChunk())  ← same VM as enable
```

## Files Changed

### `ink/src/main/kotlin/org/inklang/ContextVM.kt`
- Make `execute(chunk)` public (currently `private`)

### `ink/src/main/kotlin/org/inklang/InkContext.kt`
- Add `setVM(vm: ContextVM)` method to `PluginContext` interface
- `fireEvent()` currently returns `false` — needs real implementation

### `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt`
- Add `vm: ContextVM` to `LoadedPlugin` data class
- `loadPlugin()` creates VM, passes to context, executes enable, stores
- `unloadPlugin()` executes disable in stored VM
- Add `fireEvent(eventName, event, data)` method

### `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginContext.kt`
- Add `private var vm: ContextVM? = null`
- Add `setVM(vm: ContextVM)` method
- `onEnable()` becomes no-op (called during load, VM already running)
- `fireEvent()` looks up handlers in `vm?.globals?.get("__eventRegistry")` and executes

### `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt`
- `fireEvent()` called by Bukkit listeners → delegates to `pluginRuntime.fireEvent()`

## Out of Scope

- Inter-plugin event communication (plugin A fires event that plugin B listens to) — handled by shared event registry
- Hot reload (edit plugin files while server runs)
- Plugin dependencies

## Testing

1. `PluginLoadingTest` — verify `enable {}` runs, VM is stored, event handlers persist
2. `PluginEventTest` — fire an event, verify handler runs in same VM (state preserved)
3. `PluginDisableTest` — unload plugin, verify `disable {}` runs in same VM
