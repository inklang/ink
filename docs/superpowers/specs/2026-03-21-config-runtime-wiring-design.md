# Config Runtime Wiring — Design Spec

## Status
Accepted

## Overview

Wire `ConfigRuntime.loadConfig()` into the execution pipeline so that `config Settings { port: int = 8080 }` declarations in scripts actually load values from `settings.yml` and merge with defaults at runtime.

## Current State

The parsing and AST layers are complete:
- `Lexer.kt` — `KW_CONFIG` token
- `Parser.kt:99-113` — `parseConfig()` parses `config Name { field: type = default }`
- `AST.kt:147-148` — `Stmt.ConfigField` and `Stmt.ConfigStmt`
- `AstLowerer.kt:105-111` — emits `__config__${name}` marker string to globals
- `ConfigRuntime.kt` — fully implemented YAML loader with type coercion

The gap: `ConfigRuntime.loadConfig()` is never called. The lowerer stores a useless marker string instead of the loaded config instance.

## Design

### Approach: Pre-populate globals before execution

In `InkBukkit.runScript()`, before calling `compiled.execute(context)`:
1. Extract all `ConfigStmt` field definitions at compile time (stored in `InkScript`)
2. Call `ConfigRuntime.loadConfig()` for each config with the script's `dataFolder/scripts/` directory
3. Pre-populate the VM's globals with the loaded config instances
4. Execute the chunk — globals now contain real values, not marker strings

This mirrors how `BukkitDb`, `BukkitIo`, and `BukkitJson` are already set up as runtime dependencies passed through `BukkitContext`.

## Files Changed

### `ink/src/main/kotlin/org/inklang/lang/ConfigRuntime.kt`

No changes needed — interface is already correct:
```kotlin
fun loadConfig(
    configName: String,
    fields: List<ConfigFieldDef>,
    scriptDir: String
): Value.Instance
```

### `ink/src/main/kotlin/org/inklang/InkCompiler.kt`

After `parser.parse()`, extract config field definitions:
```kotlin
// Collect config field definitions for runtime loading
val configDefinitions = mutableMapOf<String, List<ConfigFieldDef>>()
for (stmt in statements) {
    if (stmt is Stmt.ConfigStmt) {
        val fields = stmt.fields.map { field ->
            ConfigFieldDef(
                name = field.name.lexeme,
                type = field.type.lexeme,
                defaultValue = field.defaultValue?.let { evaluateConstant(it) }
            )
        }
        configDefinitions[stmt.name.lexeme] = fields
    }
}
return InkScript(name, chunk, configDefinitions)
```

`evaluateConstant()` folds literal expressions (e.g., `="default"`) to `Value` at compile time — same constant-folding logic used by `ConstantFolder`.

### `ink/src/main/kotlin/org/inklang/InkScript.kt`

Store and expose config definitions:
```kotlin
class InkScript(
    val name: String,
    private val chunk: Chunk,
    private val configDefinitions: Map<String, List<ConfigFieldDef>> = emptyMap()
) {
    fun preloadConfigs(scriptDir: String): Map<String, Value.Instance> {
        return configDefinitions.mapValues { (name, fields) ->
            ConfigRuntime.loadConfig(name, fields, scriptDir)
        }
    }

    fun execute(context: InkContext, maxInstructions: Int = 0) {
        val vm = ContextVM(context, maxInstructions)
        vm.execute(chunk)
    }
}
```

### `ink/src/main/kotlin/org/inklang/lang/ContextVM.kt`

Accept pre-populated globals from the caller:
```kotlin
class ContextVM(
    private val context: InkContext,
    private val maxInstructions: Int = 0
) {
    val globals = mutableMapOf<String, Value>(/* existing stdlib entries */)

    fun setGlobals(overrides: Map<String, Value>) {
        globals.putAll(overrides)
    }

    // ... existing execute() method unchanged
}
```

### `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt`

Wire it all together:
```kotlin
private fun runScript(sender: CommandSender, script: String) {
    try {
        val compiled = scriptCache.getOrPut(script.hashCode().toString()) {
            compiler.compile(script)
        }
        val scriptDir = File(dataFolder, "scripts")
        // ... existing setup ...

        val context = BukkitContext(sender, this, ioDriver, jsonDriver, dbDriver)
        val vm = ContextVM(context)

        // Pre-load configs into globals before execution
        val preloadedConfigs = compiled.preloadConfigs(scriptDir.absolutePath)
        vm.setGlobals(preloadedConfigs)

        vm.execute(compiled.chunk)
        sender.sendMessage("§aScript executed successfully")
    } catch (e: Exception) {
        sender.sendMessage("§cError: ${e.message}")
    }
}
```

## Data Flow

```
Source:   config Settings { port: int = 8080 }
              │
              ▼
Parser ──► ConfigStmt { name: "Settings", fields: [ConfigField("port", "int", Value.Int(8080))] }
              │
              ▼
InkCompiler ──► Extracts ConfigFieldDef list, passes to InkScript
              │
              ▼
InkScript.configDefinitions = { "Settings" → [ConfigFieldDef("port", "int", Value.Int(8080))] }
              │
              ▼
InkBukkit.runScript():
  preloadConfigs("plugins/ink/scripts/")  ──► ConfigRuntime.loadConfig()
              │                              ├── reads settings.yml
              │                              ├── coerces types
              │                              └── returns Value.Instance
              ▼
  vm.setGlobals({ "Settings" → Value.Instance(...) })
              │
              ▼
  vm.execute(chunk)  ──► globals["Settings"] = loaded instance
```

## Error Handling

- **Missing YAML file** — `ConfigRuntime.loadConfig()` uses `emptyMap()` for missing files. If a required field has no default and no YAML value, it throws `error("Config field '...' has no value in ...yml and no default")`.
- **Type mismatch in YAML** — `convertYamlValue()` casts to the expected type; wrong types throw `ClassCastException` which surfaces as a script error.

## Dead Code

The `__config__${name}` marker strings emitted by `AstLowerer.kt:108` become dead code — the actual config values come from `preloadConfigs()`. The `STORE_GLOBAL` of the marker string still runs but overwrites a value that's already in globals with a string, which would be a bug.

**Fix**: Change `AstLowerer.kt:105-111` to emit `IrInstr.Nop` instead of the marker-loading sequence, or remove the `__config__*` string from the constants table entirely.

Option: Keep the marker string as a compile-time assertion that the config exists in the YAML (e.g., if the marker is absent from YAML, warn). For now: just remove the dead code.

## Test Plan

1. Add `VMTest.testConfigLoading()` — compiles a script with `config TestConfig { name: string = "default", port: int = 8080 }`, calls `preloadConfigs()` with a temp dir containing a `test-config.yml`, and verifies the returned instance has the YAML values.
2. Add `VMTest.testConfigDefaults()` — same but with no YAML file, verifies defaults are used.
3. Add `VMTest.testConfigMissingRequired()` — no YAML, no default, expects error.

## Alternatives Considered

### A. LOAD_CONFIG opcode in VM
Add a `LOAD_CONFIG` opcode that triggers `ConfigRuntime.loadConfig()` at runtime when the `__config__*` marker string is encountered. This keeps config loading in the VM but requires passing `scriptDir` to the VM and adding opcode infrastructure. More complex — rejected.

### B. Config as annotation
Define config via `@config(...)` annotations on classes or functions. Already rejected in prior design — the `config` keyword syntax is what the user requested.
