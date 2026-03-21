# Plugin Loading System Design for Ink

**Date:** 2026-03-21
**Status:** Draft

## Overview

Ink supports two distinct script execution modes: **plugins** (persistent, lifecycle-aware, event-enabled) and **dynamic scripts** (isolated, one-shot execution). This design specifies the loading system, lifecycle hooks, config model, and runtime contexts for both modes.

---

## Script Types

| | **Plugin** | **Dynamic Script** |
|---|---|---|
| **Location** | `plugins/ink/plugins/` | `plugins/ink/scripts/` |
| **Lifecycle** | `enable {}` / `disable {}` required | None |
| **Events** | Yes — persist across calls | No |
| **State** | Persists in memory | Isolated per execution |
| **Config** | `config {}` block → YAML in plugin folder | `config {}` block → YAML in script folder |

---

## File System Layout

```
plugins/ink/
  plugins.toml          # Global config (disabled plugins list)
  plugins/              # Plugin scripts (auto-scanned on startup)
    math/
      config.yml       # Generated from config {} block
      data.db          # Plugin data
    myplugin.ink
  scripts/              # Dynamic scripts (on-demand via /ink run)
    myscript.ink
    myscript/
      config.yml       # Generated from config {} block
```

---

## Script Structure

### Plugin

Plugin scripts **must** have `enable {}` and `disable {}` blocks.

```ink
config myplugin {
    prefix: string = "&6[MyPlugin]&r"
    debug: bool = false
}

enable {
    print("Plugin enabled!")
}

on player_join(event, player) {
    print("Welcome, ${player.name}!")
}

disable {
    print("Plugin disabled!")
}
```

### Dynamic Script

Dynamic scripts **may** have `config {}` blocks but **cannot** have `enable {}`, `disable {}`, or `on` handlers.

```ink
config myscript {
    message: string = "Hello!"
}

print(config.message)
```

---

## Global Config (`plugins.toml`)

TOML format for global runtime settings:

```toml
# plugins/ink/plugins.toml

[disabled]
plugins = ["math", "broken_plugin"]
```

Future extensions (out of scope for initial implementation):
- `load_order` or `priority` for plugin initialization order
- Global runtime settings (debug mode, logging level)

---

## Per-Plugin Config

The `config {}` block generates a YAML file named after the config:

```ink
config Math {
    precision: int = 10
    debug: bool = false
}
```

Generates: `plugins/ink/plugins/math/config.yml`

```yaml
precision: 10
debug: false
```

Config values can be:
- Overridden by editing the YAML file
- Loaded at `enable {}` execution time
- Missing values fall back to defaults from the `config {}` block

---

## Runtime Contexts

### PluginContext

Passed to plugin scripts. Provides:

- `enable()` — called when plugin loads
- `disable()` — called when plugin unloads
- Event registration — `on event_name {}` handlers persist for server lifetime
- Config access — reads from `plugins/ink/plugins/<name>/config.yml`
- Data persistence — read/write files in `plugins/ink/plugins/<name>/`

### ScriptContext

Passed to dynamic scripts. Provides:

- No lifecycle hooks
- No event registration
- Config access — reads from `plugins/ink/scripts/<name>/config.yml`
- Isolated execution — each call is independent

---

## Loading Process

### Server Startup

1. Scan `plugins/ink/plugins/` for `.ink` files
2. Read `plugins/ink/plugins.toml` → build disabled plugins list
3. For each plugin (in filesystem order):
   - Validate `enable {}` and `disable {}` blocks exist → error if missing
   - Create plugin folder if it doesn't exist
   - Load config from `plugins/ink/plugins/<name>/config.yml` (or use defaults)
   - Execute `enable {}` block with `PluginContext`
   - Register event handlers
4. If `enable {}` throws:
   - Plugin marked as failed
   - Server continues
   - `disable {}` never runs
   - Error logged

### Dynamic Script Execution

1. Resolve script path from `plugins/ink/scripts/`
2. Validate no `enable {}`, `disable {}`, or `on` handlers → error if present
3. Create script folder if `config {}` exists
4. Execute script with `ScriptContext`
5. Return result or error to caller

### Server Shutdown

1. For each loaded plugin:
   - Execute `disable {}` block
2. Clear all event registrations

---

## Error Handling

| Scenario | Behavior |
|---|---|
| Plugin missing `enable {}` | Load error — plugin skipped |
| Plugin missing `disable {}` | Load error — plugin skipped |
| `enable {}` throws | Plugin marked failed, server continues |
| Dynamic script has `on` handler | Error returned to caller |
| Plugin config missing values | Fall back to defaults |
| Config file parse error | Error logged, defaults used |

---

## Out of Scope

- Hot reload (edit files while server runs)
- Plugin dependencies/interop
- `reload {}` block — `enable {}` re-runs on reload
- Per-plugin TOML configs — use `config {}` blocks instead

---

## Files to Modify

1. `InkPlugin.kt` / `InkBukkit.kt` — add plugin scanner
2. `InkContext.kt` — add `PluginContext` interface
3. New: `PluginRuntime.kt` — lifecycle management, event registry
4. New: `GlobalConfig.kt` — parse `plugins.toml`
5. `ConfigRuntime.kt` — adapt for per-plugin/script config paths
6. Error messages for missing lifecycle blocks

---

## Related Designs

- [2026-03-20-event-hooks-design.md](./2026-03-20-event-hooks-design.md) — event system
- [2026-03-20-annotation-design.md](./2026-03-20-annotation-design.md) — annotation system (config uses similar declaration syntax)
