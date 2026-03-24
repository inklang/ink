# ink-bukkit Runtime Enhancements Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement thread safety (VM lock), /ink load/unload commands, command grammar, and player event grammar.

**Architecture:** Thread safety via `ReentrantLock` in `ContextVM` wrapping `setGlobals+execute`. New command and player grammars follow the existing `MobHandler`/`GrammarKeywordHandler` pattern. `/ink load/unload` are thin command wrappers.

**Tech Stack:** Kotlin, PaperMC, `java.util.concurrent.locks.ReentrantLock`

---

## Chunk 1: Thread Safety — ContextVM Lock

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ContextVM.kt:1-30` (class declaration + imports)

### Task 1: Add ReentrantLock to ContextVM

- [ ] **Step 1: Add import and lock field**

Modify `ink/src/main/kotlin/org/inklang/ContextVM.kt` — add import at line 5:
```kotlin
import java.util.concurrent.locks.ReentrantLock
```

After line 31 (`private var instructionCount: Long = 0`), add:
```kotlin
private val lock = ReentrantLock()
```

- [ ] **Step 2: Add executeWithLock method**

After the `setGlobals` method (after line 119), add:
```kotlin
inline fun <T> executeWithLock(fn: () -> T): T {
    lock.lock()
    try {
        return fn()
    } finally {
        lock.unlock()
    }
}
```

- [ ] **Step 3: Run build to verify compilation**

Run: `./gradlew :ink:compileKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ContextVM.kt
git commit -m "feat: add ReentrantLock and executeWithLock to ContextVM"
```

---

## Chunk 2: Thread Safety — MobListener Lock Usage

**Files:**
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobListener.kt:79-88`

### Task 2: Update MobListener to use executeWithLock

- [ ] **Step 1: Replace safeCall with locked version**

Modify `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobListener.kt` — replace the `safeCall` method (lines 79-88):

Old:
```kotlin
private fun safeCall(funcIdx: Int, eventName: String, eventGlobals: Map<String, Value>) {
    try {
        if (funcIdx < chunk.functions.size) {
            vm.setGlobals(eventGlobals)
            vm.execute(chunk.functions[funcIdx])
        }
    } catch (e: Exception) {
        System.err.println("[Ink] Error in mob '$mobName' $eventName handler: ${e.message}")
    }
}
```

New:
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

- [ ] **Step 2: Run build to verify compilation**

Run: `./gradlew :ink-bukkit:compileKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobListener.kt
git commit -m "fix: use executeWithLock in MobListener for thread safety"
```

---

## Chunk 3: /ink load and /ink unload Commands

**Files:**
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt` (the `onCommand` method, around lines 76-123)

### Task 3: Add /ink load command

- [ ] **Step 1: Add "load" branch to onCommand**

Modify `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt` — in the `onCommand` `when` statement (around line 89), add after the `"run"` branch:

```kotlin
"load" -> {
    if (args.size < 2) {
        sender.sendMessage("§cUsage: /ink load <plugin>")
        true
    } else {
        val pluginName = args[1]
        val pluginFile = File(File(dataFolder, "plugins"), "$pluginName.ink")
        if (!pluginFile.exists()) {
            sender.sendMessage("§cPlugin not found: $pluginName.ink")
        } else {
            val result = pluginRuntime.loadPlugin(pluginFile)
            if (result.isSuccess) {
                sender.sendMessage("§aPlugin loaded: $pluginName")
            } else {
                sender.sendMessage("§cFailed to load $pluginName: ${result.exceptionOrNull()?.message}")
            }
        }
        true
    }
}
```

- [ ] **Step 2: Run build to verify compilation**

Run: `./gradlew :ink-bukkit:compileKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt
git commit -m "feat: add /ink load command for runtime plugin loading"
```

### Task 4: Add /ink unload command

- [ ] **Step 1: Add "unload" branch to onCommand**

In the same `when` statement in `InkBukkit.kt`, add after the `"load"` branch:

```kotlin
"unload" -> {
    if (args.size < 2) {
        sender.sendMessage("§cUsage: /ink unload <plugin>")
        true
    } else {
        val pluginName = args[1]
        pluginRuntime.unloadPlugin(pluginName)
        sender.sendMessage("§aPlugin unloaded: $pluginName")
        true
    }
}
```

- [ ] **Step 2: Update usage message**

Modify the usage string at line 85 (or wherever the usage message is) to include load/unload:

Old:
```kotlin
sender.sendMessage("§cUsage: /ink <run|list|reload> [args]")
```

New:
```kotlin
sender.sendMessage("§cUsage: /ink <run|list|load|unload|reload> [args]")
```

- [ ] **Step 3: Run build to verify compilation**

Run: `./gradlew :ink-bukkit:compileKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt
git commit -m "feat: add /ink unload command for runtime plugin unloading"
```

---

## Chunk 4: `command` Grammar Handler

**Files:**
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/CommandHandler.kt`
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt:29-31`

### Task 5: Create CommandHandler

- [ ] **Step 1: Create CommandHandler.kt**

Create `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/CommandHandler.kt`:

```kotlin
package org.inklang.bukkit.handlers

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.inklang.ContextVM
import org.inklang.bukkit.InkBukkit
import org.inklang.grammar.CstNode
import org.inklang.lang.Chunk
import org.inklang.lang.Value
import org.inklang.bukkit.runtime.BukkitRuntimeRegistrar

/**
 * Handles `command <Name> { ... }` grammar declarations at runtime.
 *
 * Called by PluginRuntime whenever CALL_HANDLER fires with keyword="command".
 * Registers a Bukkit Command that executes the Ink function when invoked.
 */
object CommandHandler {

    fun handle(
        cst: CstNode.Declaration,
        chunk: Chunk,
        vm: ContextVM,
        plugin: InkBukkit
    ) {
        val commandName = cst.name
        // command_clause can produce either:
        // A) RuleMatch.children containing FunctionBlock (if grammar uses Rule.Seq with Keyword + Block)
        // B) RuleMatch.children containing FunctionBlock directly (if grammar wraps block in a named rule)
        // C) FunctionBlock directly in body (if grammar is simplified)
        // Handle all cases for robustness.
        val fnBlock = cst.body.filterIsInstance<CstNode.RuleMatch>()
            .firstOrNull()
            ?.children
            ?.filterIsInstance<CstNode.FunctionBlock>()
            ?.firstOrNull()
            ?: cst.body.filterIsInstance<CstNode.FunctionBlock>().firstOrNull()
            ?: run {
                plugin.logger.fine("[Ink/command] No body block for '$commandName' — nothing to register")
                return
            }

        if (fnBlock.funcIdx >= chunk.functions.size) {
            plugin.logger.warning("[Ink/command] Invalid function index for '$commandName'")
            return
        }

        val cmd = object : Command(commandName) {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
                try {
                    vm.executeWithLock {
                        vm.setGlobals(mapOf(
                            "sender" to BukkitRuntimeRegistrar.wrapSender(sender),
                            "args"   to Value.List(args.toList())
                        ))
                        vm.execute(chunk.functions[fnBlock.funcIdx])
                    }
                } catch (e: Exception) {
                    plugin.logger.severe("[Ink/command] Error executing /$commandName: ${e.message}")
                }
                return true
            }
        }

        plugin.server.commandMap.register(
            plugin.description.name.lowercase(),
            cmd
        )
        plugin.logger.info("[Ink/command] Registered /$commandName")
    }
}
```

- [ ] **Step 2: Wire CommandHandler into PluginRuntime.keywordHandlers**

Modify `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt` — update the `keywordHandlers` map (lines 29-31):

Old:
```kotlin
private val keywordHandlers: Map<String, GrammarKeywordHandler> = mapOf(
    "mob" to MobHandler::handle
)
```

New:
```kotlin
private val keywordHandlers: Map<String, GrammarKeywordHandler> = mapOf(
    "mob"     to MobHandler::handle,
    "command" to CommandHandler::handle
)
```

- [ ] **Step 3: Add import for CommandHandler at top of PluginRuntime.kt**

Check that `org.inklang.bukkit.handlers.CommandHandler` is imported (or add it if not).

- [ ] **Step 4: Run build to verify compilation**

Run: `./gradlew :ink-bukkit:compileKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/CommandHandler.kt
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt
git commit -m "feat: add command grammar handler for /commands registration"
```

---

## Chunk 5: `player` Grammar Handler

**Files:**
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/PlayerHandler.kt`
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt:29-31`

### Task 6: Create PlayerHandler and PlayerListener

- [ ] **Step 1: Create PlayerHandler.kt**

Create `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/PlayerHandler.kt`:

```kotlin
package org.inklang.bukkit.handlers

import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.inklang.ContextVM
import org.inklang.bukkit.InkBukkit
import org.inklang.grammar.CstNode
import org.inklang.lang.Chunk
import org.inklang.lang.Value
import org.inklang.bukkit.runtime.PlayerClass

/**
 * Handles `player <event> { ... }` grammar declarations at runtime.
 *
 * Called by PluginRuntime whenever CALL_HANDLER fires with keyword="player".
 * Registers PlayerJoinEvent, PlayerQuitEvent, and AsyncPlayerChatEvent listeners.
 */
object PlayerHandler {

    fun handle(
        cst: CstNode.Declaration,
        chunk: Chunk,
        vm: ContextVM,
        plugin: InkBukkit
    ) {
        val handlers = extractHandlers(cst)
        if (handlers.isEmpty()) {
            plugin.logger.fine("[Ink/player] No event handlers for '${cst.name}' — nothing to register")
            return
        }

        val listener = PlayerListener(handlers, chunk, vm, plugin.server)
        plugin.server.pluginManager.registerEvents(listener, plugin)

        val eventList = handlers.keys.joinToString(", ")
        plugin.logger.info("[Ink/player] Registered ${cst.name} ($eventList)")
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
            System.err.println("[Ink] Error in player '$eventName' handler: ${e.message}")
        }
    }
}
```

- [ ] **Step 2: Wire PlayerHandler into PluginRuntime.keywordHandlers**

Modify `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt` — update the `keywordHandlers` map:

Old:
```kotlin
private val keywordHandlers: Map<String, GrammarKeywordHandler> = mapOf(
    "mob"     to MobHandler::handle,
    "command" to CommandHandler::handle
)
```

New:
```kotlin
private val keywordHandlers: Map<String, GrammarKeywordHandler> = mapOf(
    "mob"     to MobHandler::handle,
    "command" to CommandHandler::handle,
    "player"  to PlayerHandler::handle
)
```

- [ ] **Step 3: Add import for PlayerHandler at top of PluginRuntime.kt**

Add `org.inklang.bukkit.handlers.PlayerHandler` to imports.

- [ ] **Step 4: Run build to verify compilation**

Run: `./gradlew :ink-bukkit:compileKotlin 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/PlayerHandler.kt
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt
git commit -m "feat: add player grammar handler for join/leave/chat events"
```

---

## Chunk 6: Grammar Files and PluginParserRegistry Setup

**Files:**
- Create: `ink-bukkit/src/main/resources/ink/bukkit/dist/grammar.ir.json`
- Create: `ink-bukkit/src/main/resources/ink/bukkit/dist/ink-manifest.json`
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt`

The grammar system works as follows:
1. Grammar packages are `.json` files (`grammar.ir.json` + `ink-manifest.json`) in a package directory
2. `PackageRegistry.loadAll(parentDir)` discovers and loads packages from subdirectories
3. `PluginParserRegistry` wraps the merged grammar and is passed to `Parser` at compile time
4. When `Parser` encounters a plugin keyword (like `mob`), it calls `pluginRegistry.parseDeclaration()` which uses `DynamicParser` to parse the grammar declaration
5. The resulting `CstNode.Declaration` is embedded in the compiled chunk's `cstTable`
6. At runtime, `PluginRuntime.dispatchKeyword()` routes `CstNode.Declaration.keyword` to the appropriate `GrammarKeywordHandler`

The bukkit plugin needs its own grammar package that includes `mob` (for existing compatibility), `command`, and `player`.

### Task 7: Create grammar.ir.json for bukkit plugin

- [ ] **Step 1: Create the grammar package directory**

Create: `ink-bukkit/src/main/resources/ink/bukkit/dist/`

- [ ] **Step 2: Create ink-manifest.json**

Create: `ink-bukkit/src/main/resources/ink/bukkit/dist/ink-manifest.json`
```json
{
  "name": "ink.bukkit",
  "version": "0.1.0",
  "grammar": "grammar.ir.json",
  "scripts": []
}
```

- [ ] **Step 3: Create grammar.ir.json with mob, command, and player declarations**

Create: `ink-bukkit/src/main/resources/ink/bukkit/dist/grammar.ir.json`

```json
{
  "version": 1,
  "package": "ink.bukkit",
  "keywords": [
    "mob",
    "on_spawn", "on_death", "on_damage", "on_tick", "on_target", "on_interact",
    "command",
    "player",
    "on_join", "on_leave", "on_chat"
  ],
  "rules": {
    "ink.bukkit/on_spawn_clause": {
      "rule": { "type": "seq", "items": [{ "type": "keyword", "value": "on_spawn" }, { "type": "block", "scope": null }] }
    },
    "ink.bukkit/on_death_clause": {
      "rule": { "type": "seq", "items": [{ "type": "keyword", "value": "on_death" }, { "type": "block", "scope": null }] }
    },
    "ink.bukkit/on_damage_clause": {
      "rule": { "type": "seq", "items": [{ "type": "keyword", "value": "on_damage" }, { "type": "block", "scope": null }] }
    },
    "ink.bukkit/on_tick_clause": {
      "rule": { "type": "seq", "items": [{ "type": "keyword", "value": "on_tick" }, { "type": "block", "scope": null }] }
    },
    "ink.bukkit/on_target_clause": {
      "rule": { "type": "seq", "items": [{ "type": "keyword", "value": "on_target" }, { "type": "block", "scope": null }] }
    },
    "ink.bukkit/on_interact_clause": {
      "rule": { "type": "seq", "items": [{ "type": "keyword", "value": "on_interact" }, { "type": "block", "scope": null }] }
    },
    "ink.bukkit/command_clause": {
      "rule": { "type": "block", "scope": null }
    },
    "ink.bukkit/player_clause": {
      "rule": {
        "type": "choice",
        "items": [
          { "type": "seq", "items": [{ "type": "keyword", "value": "on_join" }, { "type": "block", "scope": null }] },
          { "type": "seq", "items": [{ "type": "keyword", "value": "on_leave" }, { "type": "block", "scope": null }] },
          { "type": "seq", "items": [{ "type": "keyword", "value": "on_chat" }, { "type": "block", "scope": null }] }
        ]
      }
    }
  },
  "declarations": [
    {
      "keyword": "mob",
      "nameRule": { "type": "identifier" },
      "scopeRules": [
        "ink.bukkit/on_spawn_clause",
        "ink.bukkit/on_death_clause",
        "ink.bukkit/on_damage_clause",
        "ink.bukkit/on_tick_clause",
        "ink.bukkit/on_target_clause",
        "ink.bukkit/on_interact_clause"
      ],
      "inheritsBase": true
    },
    {
      "keyword": "command",
      "nameRule": { "type": "identifier" },
      "scopeRules": ["ink.bukkit/command_clause"],
      "inheritsBase": true
    },
    {
      "keyword": "player",
      "nameRule": { "type": "identifier" },
      "scopeRules": ["ink.bukkit/player_clause"],
      "inheritsBase": true
    }
  ]
}
```

### Task 8: Wire PluginParserRegistry into PluginRuntime

- [ ] **Step 1: Add PluginParserRegistry initialization to PluginRuntime**

Modify `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt` — add import and initialize the registry:

Add imports:
```kotlin
import org.inklang.grammar.PackageRegistry
import org.inklang.grammar.PluginParserRegistry
```

In `PluginRuntime` class body, after `private val loadedPlugins` line, add:
```kotlin
private val pluginRegistry: PluginParserRegistry by lazy {
    // Load grammar packages from this JAR's resources
    val registry = PackageRegistry()
    // ink.bukkit grammar is bundled at resources/ink/bukkit/dist/
    val grammarDir = plugin::class.java.getResource("/ink/bukkit/dist")
        ?: error("ink.bukkit grammar not found in resources")
    registry.loadAll(File(grammarDir.toURI()))
    PluginParserRegistry(registry.merge())
}
```

Note: The `/ink/bukkit/dist` path uses `getResource("/ink/bukkit/dist")` to find the grammar directory packaged in the plugin JAR. Adjust the path if the resources directory structure differs.

- [ ] **Step 2: Pass pluginRegistry to compiler in loadPlugin and loadCompiledPlugin**

Modify `PluginRuntime.loadPlugin()`:

Old:
```kotlin
val parsedStatements = compiler.parse(source)
val validationResult = compiler.validatePluginScript(parsedStatements)
...
val script = compiler.compile(source, pluginName)
```

New:
```kotlin
val parsedStatements = compiler.parse(source)
val validationResult = compiler.validatePluginScript(parsedStatements)
...
val script = compiler.compile(source, pluginName, pluginRegistry)
```

Also update `loadCompiledPlugin()` similarly if it uses the compiler.

- [ ] **Step 3: Verify resource path**

Confirm the resource path `/ink/bukkit/dist/ink-manifest.json` is correct for Gradle resources. By default, files in `src/main/resources/` are placed at the root of the classpath, so `ink-bukkit/src/main/resources/ink/bukkit/dist/ink-manifest.json` → classpath resource at `/ink/bukkit/dist/ink-manifest.json`.

Run: `./gradlew :ink-bukkit:processResources 2>&1 | grep -E "dist|ink-manifest"`
Expected: Shows the resource being copied to build output

- [ ] **Step 4: Run build to verify compilation**

Run: `./gradlew :ink-bukkit:compileKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add ink-bukkit/src/main/resources/ink/bukkit/dist/
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt
git commit -m "feat: bundle ink.bukkit grammar package and wire PluginParserRegistry"
```

---

## Chunk 7: Integration Test

### Task 9: Verify full integration

- [ ] **Step 1: Run all tests**

Run: `./gradlew :ink:test :ink-bukkit:test 2>&1 | tail -30`
Expected: All tests pass

- [ ] **Step 2: Commit with all changes**

```bash
git add -A
git commit -m "feat: ink-bukkit runtime enhancements - thread safety, load/unload commands, command and player grammars"
```

---

## File Changes Summary

| File | Change |
|------|--------|
| `ink/src/main/kotlin/org/inklang/ContextVM.kt` | **[Modified]** Add `ReentrantLock`, `executeWithLock()` |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/MobListener.kt` | **[Modified]** Use `vm.executeWithLock { ... }` |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt` | **[Modified]** Add `/ink load` and `/ink unload` commands |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/CommandHandler.kt` | **[New]** — command registration + execution |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/handlers/PlayerHandler.kt` | **[New]** — player event registration + `PlayerListener` |
| `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt` | **[Modified]** Wire CommandHandler and PlayerHandler into `keywordHandlers` |
| `ink-bukkit/src/main/resources/ink/bukkit/dist/grammar.ir.json` | **[New]** — grammar declarations for mob, command, player |
| `ink-bukkit/src/main/resources/ink/bukkit/dist/ink-manifest.json` | **[New]** — package manifest for ink.bukkit grammar |

## Implementation Order

1. **Chunk 1**: ContextVM lock (foundation for everything)
2. **Chunk 2**: MobListener update (depends on Chunk 1)
3. **Chunk 3**: /ink load and /ink unload (independent, thin wrappers)
4. **Chunk 4**: CommandHandler (depends on Chunk 1)
5. **Chunk 5**: PlayerHandler (depends on Chunk 1)
6. **Chunk 6**: Grammar files (Task 7) then PluginParserRegistry wiring (Task 8) — enables parsing of new declarations, must be done before testing grammar declarations
7. **Chunk 7**: Integration test
