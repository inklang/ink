# Event Hooks Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the `event` declaration and `on` handler registration system for Ink scripts, with Paper runtime integration.

**Architecture:**
- `event player_join(player: Player)` declares an event in the stdlib (type-only, no runtime behavior)
- `on player_join(event, player) { ... }` registers a handler with the runtime
- Handlers are stored in a global event registry in the VM globals
- Paper runtime bridges Paper events → Ink handlers via event bus integration
- `event.cancel()` sets a flag on the event object that Paper's listener checks to cancel propagation

**Tech Stack:** Kotlin, Ink compiler/VM, Paper API

---

## File Structure

### Modify (existing files)
- `lang/src/main/kotlin/org/inklang/lang/Token.kt` — Add `KW_ON`, `KW_EVENT` tokens
- `lang/src/main/kotlin/org/inklang/lang/Lexer.kt` — Lex `on` and `event` keywords
- `lang/src/main/kotlin/org/inklang/lang/AST.kt` — Add `EventDeclStmt`, `OnHandlerStmt`, `EventParam`, `InkEvent` type
- `lang/src/main/kotlin/org/inklang/lang/Parser.kt` — Parse `event` declarations and `on` handlers
- `lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt` — Lower new AST nodes to IR
- `lang/src/main/kotlin/org/inklang/lang/IR.kt` — Add `RegisterEvent`, `InvokeEvent` IR instructions
- `lang/src/main/kotlin/org/inklang/lang/OpCode.kt` — Add opcodes if needed
- `lang/src/main/kotlin/org/inklang/lang/Value.kt` — Add `Value.Event` type, `Value.EventInfo` for handler metadata
- `lang/src/main/kotlin/org/inklang/ContextVM.kt` — Add event registry to globals, support `event.cancel()`
- `lang/src/main/kotlin/org/inklang/InkContext.kt` — Extend interface with event registration methods

### Create (new files)
- `lang/src/main/kotlin/org/inklang/stdlib/Events.kt` — Event declarations (Player, Block, Entity types), `events.list/exists/info` builtins
- `lang/src/main/kotlin/org/inklang/stdlib/EventTypes.kt` — Type definitions for Player, Block, Entity, Position
- `bukkit/src/main/kotlin/org/inklang/bukkit/PaperEvents.kt` — Paper event mappings and listener registration
- `bukkit/src/main/kotlin/org/inklang/bukkit/PaperEventMappings.kt` — Maps Ink event names → Paper event classes

---

## Chunk 1: Lexer and Token Updates

### Task 1: Add tokens for `on` and `event`

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/Token.kt:58`

- [ ] **Step 1: Add token types**

```kotlin
// In TokenType enum, after KW_FINALLY:
KW_ON,
KW_EVENT,
```

- [ ] **Step 2: Run build to verify**

Run: `cd /c/Users/justi/dev/ink && ./gradlew :lang:compileKotlin 2>&1 | head -30`
Expected: FAIL if token type not yet handled in Lexer

- [ ] **Step 3: Add keywords to Lexer**

In `Lexer.kt` keywords map (line ~53):
```kotlin
"on" to TokenType.KW_ON,
"event" to TokenType.KW_EVENT,
```

- [ ] **Step 4: Run build to verify**

Run: `./gradlew :lang:compileKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/lang/Token.kt lang/src/main/kotlin/org/inklang/lang/Lexer.kt
git commit -m "feat: add KW_ON and KW_EVENT token types"
```

---

## Chunk 2: AST Nodes

### Task 2: Add event-related AST nodes

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/AST.kt:128`

- [ ] **Step 1: Add new statement and type classes**

Add before the closing `}` of `Stmt`:

```kotlin
// Event declaration: event player_join(player: Player)
data class EventDeclStmt(
    val name: Token,
    val params: List<EventParam>
) : Stmt()

// Parameter in an event declaration: player: Player
data class EventParam(
    val name: Token,
    val type: Token
)

// On-handler registration: on player_join(event, player) { ... }
data class OnHandlerStmt(
    val eventName: Token,
    val handlerName: Token?,  // null if not named
    val eventParam: Token,    // user-named first param (the event object)
    val dataParams: List<EventParam>,
    val body: Stmt.BlockStmt
) : Stmt()
```

Add to `Expr` sealed class (near end):
```kotlin
// Event object (returned when accessing event.cancel())
data class EventExpr(val name: String) : Expr()
```

- [ ] **Step 2: Add Value types**

In `lang/src/main/kotlin/org/inklang/lang/Value.kt`, add:
```kotlin
// Represents the event object accessible in handlers
data class EventObject(
    val eventName: String,
    val cancellable: Boolean,
    var cancelled: Boolean = false,
    val data: List<Value?>
) : Value()

// Event handler registered via `on`
data class EventHandler(
    val eventName: String,
    val handlerChunk: Chunk,
    val constants: List<Value>
) : Value()

// Event info for events.list() / events.info()
data class EventInfo(
    val name: String,
    val params: List<Pair<String, String>>  // name → type
) : Value()
```

- [ ] **Step 3: Run build to verify**

Run: `./gradlew :lang:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/lang/AST.kt lang/src/main/kotlin/org/inklang/lang/Value.kt
git commit -m "feat: add EventDeclStmt, OnHandlerStmt, and EventValue types"
```

---

## Chunk 3: Parser Updates

### Task 3: Parse `event` declarations and `on` handlers

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/Parser.kt:97`

- [ ] **Step 1: Add parsing for `event` keyword in parseStmt()**

In `parseStmt()` (around line 97), add:
```kotlin
check(TokenType.KW_EVENT) -> parseEventDecl()
```

- [ ] **Step 2: Add parsing for `on` keyword in parseStmt()**

In `parseStmt()`:
```kotlin
check(TokenType.KW_ON) -> parseOnHandler()
```

- [ ] **Step 3: Add parseEventDecl() method**

```kotlin
private fun parseEventDecl(): Stmt {
    consume(TokenType.KW_EVENT, "Expected 'event'")
    val name = consume(TokenType.IDENTIFIER, "Expected event name")
    consume(TokenType.L_PAREN, "Expected '('")
    val params = mutableListOf<EventParam>()
    if (!check(TokenType.R_PAREN)) {
        do {
            val paramName = consume(TokenType.IDENTIFIER, "Expected parameter name")
            consume(TokenType.COLON, "Expected ':' after parameter name")
            val paramType = consume(TokenType.IDENTIFIER, "Expected type")
            params.add(EventParam(paramName, paramType))
        } while (match(TokenType.COMMA))
    }
    consume(TokenType.R_PAREN, "Expected ')'")
    return EventDeclStmt(name, params)
}
```

- [ ] **Step 4: Add parseOnHandler() method**

```kotlin
private fun parseOnHandler(): Stmt {
    consume(TokenType.KW_ON, "Expected 'on'")
    val eventName = consume(TokenType.IDENTIFIER, "Expected event name")
    consume(TokenType.L_PAREN, "Expected '('")

    // First param is always the event object (user-named)
    val eventParam = consume(TokenType.IDENTIFIER, "Expected event parameter name")

    val dataParams = mutableListOf<EventParam>()
    if (!check(TokenType.R_PAREN)) {
        consume(TokenType.COMMA, "Expected ',' after event param")
        do {
            val paramName = consume(TokenType.IDENTIFIER, "Expected parameter name")
            consume(TokenType.COLON, "Expected ':' after parameter name")
            val paramType = consume(TokenType.IDENTIFIER, "Expected type")
            dataParams.add(EventParam(paramName, paramType))
        } while (match(TokenType.COMMA))
    }
    consume(TokenType.R_PAREN, "Expected ')'")

    val body = parseBlock()
    return OnHandlerStmt(eventName, null, eventParam, dataParams, body)
}
```

- [ ] **Step 5: Run build to verify**

Run: `./gradlew :lang:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/lang/Parser.kt
git commit -m "feat: parse event declarations and on handlers"
```

---

## Chunk 4: IR Instructions

### Task 4: Add IR instructions for event registration

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/IR.kt:38`

- [ ] **Step 1: Add event IR instructions**

In `IrInstr` sealed class:
```kotlin
// Register an event handler with the runtime event bus
data class RegisterEventHandler(
    val eventName: String,
    val handlerFuncIndex: Int,
    val eventParamName: String,
    val dataParamNames: List<String>
) : IrInstr()

// Invoke a registered handler (used at runtime when events fire)
data class InvokeEventHandler(
    val eventName: String,
    val handlerIndex: Int,
    val eventObject: Int,
    val dataArgs: List<Int>
) : IrInstr()
```

- [ ] **Step 2: Run build to verify**

Run: `./gradlew :lang:compileKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/lang/IR.kt
git commit -m "feat: add RegisterEventHandler and InvokeEventHandler IR instructions"
```

---

## Chunk 5: AST Lowerer

### Task 5: Lower `EventDeclStmt` and `OnHandlerStmt` to IR

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt`

- [ ] **Step 1: Add lowering for EventDeclStmt**

In `lowerStmt()`, add:
```kotlin
is Stmt.EventDeclStmt -> {
    // Event declarations are type-only at this stage
    // Store event metadata in the constants table
    val eventInfo = EventInfo(
        stmt.name.lexeme,
        stmt.params.map { it.name.lexeme to it.type.lexeme }
    )
    // Nothing to emit - event info is registered via stdlib
    emptyList()
}
```

- [ ] **Step 2: Add lowering for OnHandlerStmt**

In `lowerStmt()`, add:
```kotlin
is Stmt.OnHandlerStmt -> {
    // Compile the handler body to a function chunk
    val handlerResult = lowerToIr(listOf(stmt.body))
    val handlerFuncIndex = result.functions.size
    result.functions.add(handlerResult.instrs)
    result.constants.addAll(handlerResult.constants)

    // Emit RegisterEventHandler instruction
    val instr = IrInstr.RegisterEventHandler(
        eventName = stmt.eventName.lexeme,
        handlerFuncIndex = handlerFuncIndex,
        eventParamName = stmt.eventParam.lexeme,
        dataParamNames = stmt.dataParams.map { it.name.lexeme }
    )
    listOf(instr)
}
```

- [ ] **Step 3: Run build to verify**

Run: `./gradlew :lang:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt
git commit -m "feat: lower EventDeclStmt and OnHandlerStmt to IR"
```

---

## Chunk 6: ContextVM Event Registry

### Task 6: Add event registry to ContextVM globals

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/ContextVM.kt:46`

- [ ] **Step 1: Add event registry to globals**

Add to `globals` mutableMap initialization (around line 46):
```kotlin
// Event registry for on-handler storage
"__eventRegistry" to Value.Instance(ClassDescriptor("EventRegistry", null, mapOf(
    "handlers" to Value.NativeFunction { args ->
        // Returns list of registered event names
        Value.Null
    }
))),
```

- [ ] **Step 2: Add `event.cancel()` support**

Add a native function in globals:
```kotlin
"cancel" to Value.NativeFunction { args ->
    // cancel(eventObject) — sets cancelled flag
    if (args.isNotEmpty() && args[0] is Value.EventObject) {
        (args[0] as Value.EventObject).cancelled = true
    }
    Value.Null
}
```

- [ ] **Step 3: Add `events` builtin object**

```kotlin
"events" to Value.Instance(ClassDescriptor("Events", null, mapOf(
    "list" to Value.NativeFunction { args ->
        // Returns list of available event names from stdlib
        Value.Null  // TODO: return event names
    },
    "exists" to Value.NativeFunction { args ->
        val name = (args.getOrNull(0) as? Value.String)?.value ?: ""
        // TODO: check if event exists
        Value.Boolean.FALSE
    },
    "info" to Value.NativeFunction { args ->
        val name = (args.getOrNull(0) as? Value.String)?.value ?: ""
        // TODO: return event info
        Value.Null
    }
))),
```

- [ ] **Step 4: Add REGISTER_EVENT opcode**

In `lang/src/main/kotlin/org/inklang/lang/OpCode.kt`:
```kotlin
REGISTER_EVENT(0x2B),  // Register an event handler
```

Add to `ContextVM.execute()` switch:
```kotlin
OpCode.REGISTER_EVENT -> {
    // args[0] = event name string index
    // args[1] = handler function index
    val eventName = (frame.regs[src1] as? Value.String)?.value ?: ""
    val handlerFunc = frame.regs[src2]
    // Store in event registry
    val registry = globals["__eventRegistry"] as? Value.Instance
        ?: throw ScriptException("No event registry")
}
```

- [ ] **Step 5: Run build to verify**

Run: `./gradlew :lang:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (may have errors - fix iteratively)

- [ ] **Step 6: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/ContextVM.kt lang/src/main/kotlin/org/inklang/lang/OpCode.kt
git commit -m "feat: add event registry and cancel to ContextVM"
```

---

## Chunk 7: InkContext Extension

### Task 7: Extend InkContext with event registration

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/InkContext.kt`

- [ ] **Step 1: Add event registration interface**

```kotlin
package org.inklang

import org.inklang.lang.Value

/**
 * Context interface that runtime hosts implement.
 * Scripts never access platform APIs directly - they call log/print,
 * and the runtime decides where output goes.
 */
interface InkContext {
    /** Info-level log output (server console in Paper, stdout in CLI) */
    fun log(message: String)

    /** User-facing output (command sender in Paper, stdout in CLI) */
    fun print(message: String)

    /**
     * Register an event handler.
     * @param eventName The Ink event name (e.g., "player_join")
     * @param handlerFunc The compiled handler function to call when the event fires
     * @param eventParamName The name of the first parameter (the event object)
     * @param dataParamNames Names of the data parameter types
     */
    fun registerEventHandler(
        eventName: String,
        handlerFunc: Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    )

    /**
     * Fire an event, invoking all registered handlers.
     * @param eventName The Ink event name
     * @param event The event object (can be cancelled)
     * @param data The data arguments to pass to handlers
     * @return true if event was not cancelled, false if cancelled
     */
    fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean
}
```

- [ ] **Step 2: Update BukkitContext to implement new methods**

In `bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt`, update `BukkitContext`:
```kotlin
class BukkitContext(private val sender: CommandSender, private val plugin: InkBukkit) : InkContext {
    private val eventHandlers = mutableMapOf<String, MutableList<Value.Function>>()

    override fun log(message: String) {
        plugin.logger.info("[Ink] $message")
    }

    override fun print(message: String) {
        sender.sendMessage("§f[Ink] $message")
    }

    override fun registerEventHandler(
        eventName: String,
        handlerFunc: Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    ) {
        eventHandlers.getOrPut(eventName) { mutableListOf() }.add(handlerFunc)
    }

    override fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean {
        val handlers = eventHandlers[eventName] ?: return true
        for (handler in handlers) {
            // Execute handler with event object + data args
            // If handler throws, log and continue
            // If event.cancelled, stop
            if (event.cancelled) return false
        }
        return !event.cancelled
    }
}
```

- [ ] **Step 3: Run build to verify**

Run: `./gradlew :lang:compileKotlin :bukkit:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/InkContext.kt bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt
git commit -m "feat: extend InkContext with event registration API"
```

---

## Chunk 8: Paper Event Mappings

### Task 8: Create Paper event mapping and listener integration

**Files:**
- Create: `bukkit/src/main/kotlin/org/inklang/bukkit/PaperEvents.kt`
- Create: `bukkit/src/main/kotlin/org/inklang/bukkit/PaperEventMappings.kt`

- [ ] **Step 1: Create PaperEventMappings.kt**

```kotlin
package org.inklang.bukkit

import org.bukkit.event.Event
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent

object PaperEventMappings {
    // Maps Ink event names to Paper event classes
    val inkToPaper = mapOf(
        "player_join" to PlayerJoinEvent::class.java,
        "player_quit" to PlayerQuitEvent::class.java,
        "block_break" to BlockBreakEvent::class.java,
        "block_place" to BlockPlaceEvent::class.java
    )

    // Which Ink events are supported by Paper runtime
    fun isAvailable(inkEvent: String): Boolean = inkToPaper.containsKey(inkEvent)

    // Get parameter types for an event
    fun getParamTypes(inkEvent: String): List<String> = when (inkEvent) {
        "player_join" -> listOf("Player")
        "player_quit" -> listOf("Player")
        "block_break" -> listOf("Block", "Player")
        "block_place" -> listOf("Block", "Player")
        else -> emptyList()
    }
}
```

- [ ] **Step 2: Create PaperEvents.kt with listener registration**

```kotlin
package org.inklang.bukkit

import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.plugin.java.JavaPlugin
import org.inklang.InkContext
import org.inklang.lang.Value
import org.inklang.lang.EventObject

class PaperEvents(private val context: InkContext, private val plugin: JavaPlugin) {

    fun registerHandlers() {
        // Register PlayerJoinEvent listener
        plugin.server.pluginManager.registerEvent(
            PlayerJoinEvent::class.java,
            object : org.bukkit.event.Listener {
                @org.bukkit.event.EventHandler
                fun onPlayerJoin(event: PlayerJoinEvent) {
                    val eventObj = Value.EventObject(
                        eventName = "player_join",
                        cancellable = true,
                        cancelled = false,
                        data = listOf(
                            wrapPlayer(event.player)
                        )
                    )
                    context.fireEvent("player_join", eventObj, eventObj.data)
                    if (eventObj.cancelled) {
                        event.joinMessage(null)
                    }
                }
            },
            EventPriority.NORMAL,
            { _, _ -> },  // Ignore cancelled events by default
            plugin
        )
    }

    private fun wrapPlayer(player: org.bukkit.entity.Player): Value.Instance {
        // Create an Ink Player wrapper with name, world, sendMessage
        return Value.Instance(ClassDescriptor("Player", null, mapOf(
            "name" to Value.String(player.name),
            "world" to Value.String(player.world.name),
            "sendMessage" to Value.NativeFunction { args ->
                val msg = (args.getOrNull(1) as? Value.String)?.value ?: ""
                player.sendMessage(msg)
                Value.Null
            }
        )))
    }
}
```

- [ ] **Step 3: Update InkPlugin to call registerHandlers()**

In `InkPlugin.kt`:
```kotlin
class InkPlugin : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        // Register Paper event listeners
        PaperEvents(context, this).registerHandlers()
    }
}
```

- [ ] **Step 4: Run build to verify**

Run: `./gradlew :bukkit:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add bukkit/src/main/kotlin/org/inklang/bukkit/PaperEvents.kt bukkit/src/main/kotlin/org/inklang/bukkit/PaperEventMappings.kt
git add bukkit/src/main/kotlin/org/inklang/bukkit/InkPlugin.kt
git commit -m "feat: add Paper event mappings and listener registration"
```

---

## Chunk 9: Stdlib Event Types

### Task 9: Create stdlib for events

**Files:**
- Create: `lang/src/main/kotlin/org/inklang/stdlib/EventTypes.kt`
- Create: `lang/src/main/kotlin/org/inklang/stdlib/Events.kt`

- [ ] **Step 1: Create EventTypes.kt**

```kotlin
package org.inklang.stdlib

import org.inklang.lang.Value
import org.inklang.lang.ClassDescriptor

/**
 * Ink stdlib types for events.
 * These are the types passed as parameters to event handlers.
 */
object EventTypes {
    // Player type: name, world, sendMessage
    val PlayerClass = ClassDescriptor("Player", null, mapOf(
        "name" to Value.NativeFunction("get") { args ->
            (args.getOrNull(0) as? Value.Instance)?.fields?.get("name") ?: Value.Null
        },
        "world" to Value.NativeFunction("get") { args ->
            (args.getOrNull(0) as? Value.Instance)?.fields?.get("world") ?: Value.Null
        },
        "sendMessage" to Value.NativeFunction("call") { args ->
            // Handled by Paper runtime wrapper
            Value.Null
        }
    ), readOnly = true)

    // Block type: material, position
    val BlockClass = ClassDescriptor("Block", null, mapOf(
        "material" to Value.NativeFunction("get") { args ->
            (args.getOrNull(0) as? Value.Instance)?.fields?.get("material") ?: Value.Null
        },
        "position" to Value.NativeFunction("get") { args ->
            (args.getOrNull(0) as? Value.Instance)?.fields?.get("position") ?: Value.Null
        }
    ), readOnly = true)

    // Position type: x, y, z
    val PositionClass = ClassDescriptor("Position", null, mapOf(
        "x" to Value.NativeFunction("get") { args ->
            (args.getOrNull(0) as? Value.Instance)?.fields?.get("x") ?: Value.Null
        },
        "y" to Value.NativeFunction("get") { args ->
            (args.getOrNull(0) as? Value.Instance)?.fields?.get("y") ?: Value.Null
        },
        "z" to Value.NativeFunction("get") { args ->
            (args.getOrNull(0) as? Value.Instance)?.fields?.get("z") ?: Value.Null
        }
    ), readOnly = true)

    // Entity type: type, position
    val EntityClass = ClassDescriptor("Entity", null, mapOf(
        "type" to Value.NativeFunction("get") { args ->
            (args.getOrNull(0) as? Value.Instance)?.fields?.get("type") ?: Value.Null
        }
    ), readOnly = true)
}
```

- [ ] **Step 2: Create Events.kt**

```kotlin
package org.inklang.stdlib

import org.inklang.lang.Value
import org.inklang.lang.ClassDescriptor

/**
 * Ink stdlib event declarations.
 * These define the event taxonomy available to scripts.
 */
object Events {
    // Event registry — available as `events` builtin
    val EventsClass = ClassDescriptor("Events", null, mapOf(
        "list" to Value.NativeFunction("call") { args ->
            // Returns list of available event names
            val names = listOf(
                Value.String("player_join"),
                Value.String("player_quit"),
                Value.String("chat_message"),
                Value.String("block_break"),
                Value.String("block_place"),
                Value.String("entity_death"),
                Value.String("entity_damage"),
                Value.String("server_start"),
                Value.String("server_stop")
            )
            Value.Instance(ClassDescriptor("List", null, mapOf("__items" to
                Value.InternalList(names.toMutableList())
            )))
        },
        "exists" to Value.NativeFunction("call") { args ->
            val name = (args.getOrNull(1) as? Value.String)?.value ?: ""
            val available = setOf("player_join", "player_quit", "chat_message",
                "block_break", "block_place", "entity_death", "entity_damage",
                "server_start", "server_stop")
            Value.Boolean(if (name in available) Value.Boolean.TRUE else Value.Boolean.FALSE)
        },
        "info" to Value.NativeFunction("call") { args ->
            val name = (args.getOrNull(1) as? Value.String)?.value ?: ""
            val info = when (name) {
                "player_join" -> listOf("event" to "Event", "player" to "Player")
                "player_quit" -> listOf("event" to "Event", "player" to "Player")
                "block_break" -> listOf("event" to "Event", "block" to "Block", "player" to "Player")
                else -> emptyList()
            }
            // Return as an Instance with name and parameters fields
            Value.Instance(ClassDescriptor("EventInfo", null, mapOf(
                "name" to Value.String(name),
                "parameters" to Value.Instance(ClassDescriptor("List", null, mapOf()))
            )))
        }
    ), readOnly = true)

    // Event declarations — type-only, used by stdlib
    val EVENT_DECLS = mapOf(
        "player_join" to listOf("player" to "Player"),
        "player_quit" to listOf("player" to "Player"),
        "chat_message" to listOf("player" to "Player", "message" to "String"),
        "block_break" to listOf("block" to "Block", "player" to "Player"),
        "block_place" to listOf("block" to "Block", "player" to "Player"),
        "entity_death" to listOf("entity" to "Entity", "killer" to "Player"),
        "entity_damage" to listOf("entity" to "Entity", "amount" to "Number"),
        "server_start" to emptyList(),
        "server_stop" to emptyList()
    )
}
```

- [ ] **Step 3: Run build to verify**

Run: `./gradlew :lang:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/stdlib/EventTypes.kt lang/src/main/kotlin/org/inklang/stdlib/Events.kt
git commit -m "feat: add stdlib event types and event declarations"
```

---

## Chunk 10: Compile-time Validation

### Task 10: Validate event names at compile time

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt`

- [ ] **Step 1: Add compile-time event validation**

When lowering `OnHandlerStmt`, validate the event name against known events:
```kotlin
is Stmt.OnHandlerStmt -> {
    val eventName = stmt.eventName.lexeme
    val knownEvents = setOf("player_join", "player_quit", "chat_message",
        "block_break", "block_place", "entity_death", "entity_damage",
        "server_start", "server_stop")
    if (eventName !in knownEvents) {
        throw CompilationException(
            "RuntimeError: Unknown event '$eventName'. Available: ${knownEvents.joinToString(", ")}"
        )
    }
    // ... rest of lowering
}
```

- [ ] **Step 2: Validate parameter count**

In `OnHandlerStmt` lowering:
```kotlin
val expectedParams = when (eventName) {
    "player_join", "player_quit" -> 2  // event + player
    "block_break", "block_place" -> 3  // event + block + player
    "chat_message" -> 3  // event + player + message
    else -> 2
}
val actualParams = 1 + stmt.dataParams.size  // event + data params
if (actualParams != expectedParams) {
    throw CompilationException(
        "RuntimeError: Event '$eventName' expects $expectedParams parameters (including event), got $actualParams"
    )
}
```

- [ ] **Step 3: Run build and test**

Run: `./gradlew :lang:compileKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

Run a test with an invalid event name to verify the error message.

- [ ] **Step 4: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt
git commit -m "feat: validate event names and parameter counts at compile time"
```

---

## Chunk 11: End-to-End Test

### Task 11: Write and run end-to-end test

**Files:**
- Create: `bukkit/src/test/kotlin/org/inklang/bukkit/EventHooksTest.kt`

- [ ] **Step 1: Write event hooks test**

```kotlin
package org.inklang.bukkit

import org.junit.jupiter.api.Test
import org.inklang.InkCompiler
import org.inklang.InkScript
import org.junit.jupiter.api.Assertions.*

class EventHooksTest {
    private val compiler = InkCompiler()

    @Test
    fun `parses event declaration`() {
        val script = compiler.compile("""
            event player_join(player: Player)
            on player_join(event, player) {
                print("Hello!")
            }
        """)
        assertNotNull(script)
    }

    @Test
    fun `rejects unknown event`() {
        assertThrows(CompilationException::class.java) {
            compiler.compile("""
                on player_jon(event, player) { }
            """)
        }
    }

    @Test
    fun `rejects wrong parameter count`() {
        assertThrows(CompilationException::class.java) {
            compiler.compile("""
                on player_join(event, player, extra) { }
            """)
        }
    }

    @Test
    fun `event_cancel sets cancelled flag`() {
        // This tests the Value.EventObject cancellation behavior
        val eventObj = org.inklang.lang.Value.EventObject(
            eventName = "test",
            cancellable = true,
            cancelled = false,
            data = emptyList()
        )
        eventObj.cancelled = true
        assertTrue(eventObj.cancelled)
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :lang:test :bukkit:test 2>&1 | tail -30`
Expected: Tests pass

- [ ] **Step 3: Commit**

```bash
git add bukkit/src/test/kotlin/org/inklang/bukkit/EventHooksTest.kt
git commit -m "test: add event hooks tests"
```

---

## Plan Complete

**Next step:** Use `superpowers:subagent-driven-development` to execute this plan with a fresh subagent per task.
