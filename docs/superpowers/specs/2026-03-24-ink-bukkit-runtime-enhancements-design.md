# ink-bukkit Runtime Enhancements Design

**Date:** 2026-03-24
**Status:** Approved
**Branch:** `feat/has-operator-v2`

## Overview

Four ink-bukkit runtime improvements:

1. Thread safety for concurrent event handlers (VM-level locking)
2. `/ink load` and `/ink unload` commands for runtime plugin management
3. `command mycmd { ... }` grammar for registering server commands
4. `player on_join { ... }` / `on_leave` / `on_chat` grammars for player lifecycle events

---

## 1. Thread Safety — VM-level Lock

### Problem

In `MobListener.safeCall()`, the sequence `vm.setGlobals(eventGlobals)` followed by `vm.execute(chunk.functions[funcIdx])` is not atomic. When multiple mob events fire simultaneously on different threads, these calls can interleave, corrupting VM state.

### Solution

Add a `ReentrantLock` to `ContextVM`. Expose `executeWithLock(fn: () -> T)` that acquires the lock around the full `setGlobals + execute` sequence. Lock is per-VM (per-plugin), so events for the same plugin serialize naturally — the natural serialization boundary.

### Changes

**`ink/src/main/kotlin/org/inklang/ContextVM.kt`:**
```kotlin
private val lock = ReentrantLock()

inline fun <T> executeWithLock(fn: () -> T): T {
    lock.lock()
    try {
        return fn()
    } finally {
        lock.unlock()
    }
}
```

**`ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobListener.kt`:**
```kotlin
private fun safeCall(funcIdx: Int, eventName: String, eventGlobals: Map<String, Value>) {
    try {
        if (funcIdx < chunk.functions.size) {
            vm.executeWithLock {
                vm.setGlobals(eventGlobals)
                vm.execute(chunk.functions[funcIdx])
            }
        }
    } catch (e: Exception) {
        System.err.println("[Ink] Error in mob '$mobName' $eventName handler: ${e.message}")
    }
}
```

---

## 2. `/ink load` and `/ink unload` Commands

### Problem

Currently plugins are only loaded at server startup via `loadPlugins()`. There is no way to load or unload plugins at runtime without restarting the server.

### `/ink load <name>`

Loads the plugin from `plugins/<name>.ink` at runtime.

**Command handler branch:**
```kotlin
"load" -> {
    if (args.size < 2) {
        sender.sendMessage("§cUsage: /ink load <plugin>")
        true
    } else {
        loadPluginAtRuntime(sender, args[1])
        true
    }
}
```

**`loadPluginAtRuntime`:**
- Looks for `plugins/<name>.ink`
- If not found, sends error `§cPlugin not found: <name>.ink`
- Calls `pluginRuntime.loadPlugin(file)`
- On success: `§aPlugin loaded: <name>`
- On failure: `§cFailed to load <name>: <error>`

### `/ink unload <name>`

Unloads a plugin, removing event listeners and discarding its VM.

**Command handler branch:**
```kotlin
"unload" -> {
    if (args.size < 2) {
        sender.sendMessage("§cUsage: /ink unload <plugin>")
        true
    } else {
        pluginRuntime.unloadPlugin(args[1])
        sender.sendMessage("§aPlugin unloaded: ${args[1]}")
        true
    }
}
```

Note: `pluginRuntime.unloadPlugin` already exists and works correctly. The command is a thin wrapper around it.

### Updated usage message

`§cUsage: /ink <run|list|load|unload|reload> [args]`

---

## 3. `command mycmd { ... }` Grammar

### Syntax

```
command mycmd {
    // Ink code runs when player types /<pluginlabel> mycmd
    // `sender` is available as Player or ConsoleSender
    print("Hello " + sender.name)
}
```

### Architecture

Follows the same `GrammarKeywordHandler` + handler object pattern as `MobHandler`.

### Grammar declaration

```kotlin
// In PluginRuntime.keywordHandlers initialization:
private val keywordHandlers: Map<String, GrammarKeywordHandler> = mapOf(
    "mob"     to MobHandler::handle,
    "command" to CommandHandler::handle,   // NEW
    "player"  to PlayerHandler::handle     // NEW
)
```

### `CommandHandler`

```kotlin
object CommandHandler {
    fun handle(
        cst: CstNode.Declaration,
        chunk: Chunk,
        vm: ContextVM,
        plugin: InkBukkit
    ) {
        val commandName = cst.name
        val fnBlock = cst.body.filterIsInstance<CstNode.FunctionBlock>().firstOrNull()
            ?: return

        val cmd = object : Command(commandName) {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
                vm.executeWithLock {
                    vm.setGlobals(mapOf(
                        "sender" to BukkitRuntimeRegistrar.wrapSender(sender),
                        "args"   to Value.List(args.toList())
                    ))
                    vm.execute(chunk.functions[fnBlock.funcIdx])
                }
                return true
            }
        }

        plugin.server.commandMap.register(plugin.description.name.lowercase(), cmd)
        plugin.logger.info("[Ink/command] Registered /$commandName")
    }
}
```

### Grammar rules (added to merged grammar)

```kotlin
DeclarationDef(
    keyword = "command",
    nameRule = Rule.Identifier,
    scopeRules = listOf("command_clause")
)

RuleEntry(
    name = "command_clause",
    rule = Rule.Seq(listOf(
        Rule.Block(scope = null)   // code block
    ))
)
```

---

## 4. `player on_join { ... }` Grammar

### Syntax

```
player on_join {
    print(player.name + " joined!")
}

player on_leave {
    print(player.name + " left")
}

player on_chat {
    broadcast("[" + player.name + "] " + message)
    cancel()  // cancels the chat event
}
```

### Supported event clauses

| Clause | Bukkit Event | Event Globals |
|--------|-------------|---------------|
| `on_join` | `PlayerJoinEvent` | `player: Player` |
| `on_leave` | `PlayerQuitEvent` | `player: Player` |
| `on_chat` | `AsyncPlayerChatEvent` | `player: Player`, `message: String`, `cancel(): Null` |

### `PlayerHandler`

```kotlin
object PlayerHandler {
    fun handle(
        cst: CstNode.Declaration,
        chunk: Chunk,
        vm: ContextVM,
        plugin: InkBukkit
    ) {
        val handlers = extractHandlers(cst)  // same pattern as MobHandler
        if (handlers.isEmpty()) return

        val listener = PlayerListener(handlers, chunk, vm, plugin.server)
        plugin.server.pluginManager.registerEvents(listener, plugin)
    }

    private fun extractHandlers(cst: CstNode.Declaration): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (node in cst.body) {
            if (node !is CstNode.RuleMatch) continue
            val clause = node.ruleName.substringAfterLast('/')
            val eventName = when (clause) {
                "on_join_clause"  -> "on_join"
                "on_leave_clause" -> "on_leave"
                "on_chat_clause"  -> "on_chat"
                else              -> continue
            }
            val fnBlock = node.children.filterIsInstance<CstNode.FunctionBlock>().firstOrNull() ?: continue
            result[eventName] = fnBlock.funcIdx
        }
        return result
    }
}
```

### `PlayerListener`

```kotlin
class PlayerListener(
    private val handlers: Map<String, Int>,
    private val chunk: Chunk,
    private val vm: ContextVM,
    private val server: Server
) : Listener {

    @EventHandler
    fun onPlayerJoin(evt: PlayerJoinEvent) {
        handlers["on_join"]?.let { funcIdx ->
            safeCall(funcIdx, "on_join", mapOf(
                "player" to PlayerClass.wrap(evt.player, server)
            ))
        }
    }

    @EventHandler
    fun onPlayerQuit(evt: PlayerQuitEvent) {
        handlers["on_leave"]?.let { funcIdx ->
            safeCall(funcIdx, "on_leave", mapOf(
                "player" to PlayerClass.wrap(evt.player, server)
            ))
        }
    }

    @EventHandler
    fun onPlayerChat(evt: AsyncPlayerChatEvent) {
        handlers["on_chat"]?.let { funcIdx ->
            safeCall(funcIdx, "on_chat", mapOf(
                "player" to PlayerClass.wrap(evt.player, server),
                "message" to Value.String(evt.message),
                "cancel" to Value.NativeFunction {
                    evt.isCancelled = true
                    Value.Null
                }
            ))
        }
    }

    private fun safeCall(funcIdx: Int, eventName: String, eventGlobals: Map<String, Value>) {
        try {
            if (funcIdx < chunk.functions.size) {
                vm.executeWithLock {
                    vm.setGlobals(eventGlobals)
                    vm.execute(chunk.functions[funcIdx])
                }
            }
        } catch (e: Exception) {
            System.err.println("[Ink] Error in player $eventName handler: ${e.message}")
        }
    }
}
```

### Grammar rules

```kotlin
DeclarationDef(
    keyword = "player",
    nameRule = Rule.Identifier,  // "on_join", "on_leave", "on_chat"
    scopeRules = listOf("player_clause")
)

RuleEntry(
    name = "player_clause",
    rule = Rule.Choice(listOf(
        Rule.Seq(listOf(Rule.Keyword("on_join"),  Rule.Block(scope = null))),
        Rule.Seq(listOf(Rule.Keyword("on_leave"), Rule.Block(scope = null))),
        Rule.Seq(listOf(Rule.Keyword("on_chat"),  Rule.Block(scope = null)))
    ))
)
```

---

## File Changes

| File | Change |
|------|--------|
| `ink/src/main/kotlin/org/inklang/ContextVM.kt` | Add `ReentrantLock`, `executeWithLock()` |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobListener.kt` | Use `vm.executeWithLock { ... }` |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt` | Add `/ink load` and `/ink unload` commands |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/CommandHandler.kt` | **New** — command registration + execution |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/PlayerHandler.kt` | **New** — player event registration + `PlayerListener` |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt` | Wire CommandHandler and PlayerHandler into `keywordHandlers` |

---

## Implementation Order

1. **Thread safety** — `ContextVM` lock + `MobListener` update (lowest risk, foundational)
2. **`/ink load` and `/ink unload`** — thin command wrappers around existing `PluginRuntime` methods
3. **`command` grammar** — follows existing `MobHandler` pattern exactly
4. **`player` grammar** — follows existing `MobHandler` pattern exactly

Thread safety (item 1) should be implemented first since everything else depends on the VM being thread-safe.
