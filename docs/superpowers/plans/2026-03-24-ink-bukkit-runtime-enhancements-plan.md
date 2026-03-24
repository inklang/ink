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
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt:89-122`

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
        val fnBlock = cst.body.filterIsInstance<CstNode.FunctionBlock>().firstOrNull()
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

## Chunk 6: Grammar Rules for command and player

**Files:**
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginContext.kt` (add `dispatchPluginDecl` calls in grammar registration)
- Modify: grammar rules in `ink/src/main/kotlin/org/inklang/grammar/` or wherever the grammar is merged

### Task 7: Register command and player grammar declarations

The grammar rules need to be added to the merged grammar that `PluginParserRegistry` uses. This happens during grammar merging in `PackageRegistry`. The actual declaration additions depend on how the grammar package is constructed.

- [ ] **Step 1: Find grammar package construction**

Search for where `MergedGrammar` is created and where `DeclarationDef` entries for "mob" exist:

Run: `grep -rn "DeclarationDef\|mob.*MobHandler\|keywordHandlers" ink/src/main/kotlin/org/inklang/ --include="*.kt" | head -30`

Expected: Find where the grammar declarations are assembled (likely in a grammar merging phase).

- [ ] **Step 2: Add command and player declarations**

Add to the grammar package (wherever `mob` declaration is defined, add alongside it):

```kotlin
// command declaration
DeclarationDef(
    keyword = "command",
    nameRule = Rule.Identifier,
    scopeRules = listOf("command_clause")
)

// player declaration
DeclarationDef(
    keyword = "player",
    nameRule = Rule.Identifier,
    scopeRules = listOf("player_clause")
)
```

Add the scope rules:
```kotlin
RuleEntry(name = "command_clause", rule = Rule.Block(scope = null))

RuleEntry(
    name = "player_clause",
    rule = Rule.Choice(listOf(
        Rule.Seq(listOf(Rule.Keyword("on_join"),  Rule.Block(scope = null))),
        Rule.Seq(listOf(Rule.Keyword("on_leave"), Rule.Block(scope = null))),
        Rule.Seq(listOf(Rule.Keyword("on_chat"),  Rule.Block(scope = null)))
    ))
)
```

Note: If the grammar system is generated from a `.inkgrammar` file or similar DSL, follow that pattern instead.

- [ ] **Step 3: Run build to verify compilation**

Run: `./gradlew :ink:compileKotlin :ink-bukkit:compileKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add <grammar-files>
git commit -m "feat: add command and player grammar declarations"
```

---

## Chunk 7: Integration Test

### Task 8: Verify full integration

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
| `ink/src/main/kotlin/org/inklang/grammar/` (or similar) | **[Modified]** Add grammar declarations for `command` and `player` |

## Implementation Order

1. **Chunk 1**: ContextVM lock (foundation for everything)
2. **Chunk 2**: MobListener update (depends on Chunk 1)
3. **Chunk 3**: /ink load and /ink unload (independent, thin wrappers)
4. **Chunk 4**: CommandHandler (depends on Chunk 1)
5. **Chunk 5**: PlayerHandler (depends on Chunk 1)
6. **Chunk 6**: Grammar rules (enables parsing of new declarations)
7. **Chunk 7**: Integration test
