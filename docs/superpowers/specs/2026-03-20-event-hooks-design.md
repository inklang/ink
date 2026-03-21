# Event Hooks Design — Ink Runtime

**Date:** 2026-03-20
**Status:** Draft

## Overview

Ink scripts can register handlers for platform events (player join, block break, etc.) using the `on` keyword. The Ink stdlib defines a platform-agnostic event taxonomy; each runtime (Paper, Hytale) maps Ink events to their platform-specific counterparts.

This document covers the event declaration syntax, handler registration, data types, cancellation, persistence, and runtime implementation.

---

## 1. Event Taxonomy (Stdlib)

Events are declared flat at module level in the Ink stdlib. No class nesting.

```ink
// Player events
event player_join(player: Player)
event player_quit(player: Player)
event chat_message(player: Player, message: String)

// Block events
event block_break(block: Block, player: Player)
event block_place(block: Block, player: Player)

// Entity events
event entity_death(entity: Entity, killer: Player)
event entity_damage(entity: Entity, amount: Number)

// World events
event server_start()
event server_stop()
```

### 1.1 Stdlib Structure

```
ink-stdlib/
  events.ink          # Event declarations (platform-agnostic)
  types.ink           # Type definitions (Player, Block, Entity, etc.)
  builtins.ink        # print(), log(), events.list(), etc.
```

---

## 2. Runtime Mappings

Each runtime maps Ink event names to platform events.

### 2.1 Paper Runtime

| Ink Event         | Paper Event                  |
|-------------------|------------------------------|
| `player_join`     | `PlayerJoinEvent`            |
| `player_quit`     | `PlayerQuitEvent`            |
| `chat_message`    | `AsyncPlayerChatEvent`       |
| `block_break`     | `BlockBreakEvent`            |
| `block_place`      | `BlockPlaceEvent`            |
| `entity_death`    | `EntityDeathEvent`           |
| `entity_damage`    | `EntityDamageByEntityEvent` |
| `server_start`     | Lifecycle (on enable)        |
| `server_stop`     | Lifecycle (on disable)       |

### 2.2 Hytale Runtime

| Ink Event         | Hytale Event                 |
|-------------------|------------------------------|
| `player_join`     | `HytaleJoinEvent`            |
| `player_quit`     | `HytaleQuitEvent`            |
| `chat_message`    | `HytaleChatEvent`            |
| `block_break`     | `HytaleBlockBreakEvent`      |
| ...               | ...                          |

If a script uses an event Hytale doesn't support → runtime error: `Event 'server_start' is not supported on Hytale runtime`.

---

## 3. Handler Syntax

### 3.1 Basic Registration

```ink
on player_join(event, player) {
    print("Welcome, " + player.name)
}

on block_break(event, block, player) {
    if block.material == "DIAMOND_ORE" {
        player.sendMessage("Nice diamond!")
    }
}
```

- `event` is always the **first parameter** — the user names it (any identifier)
- Remaining parameters are **data objects** from the platform
- `event.cancel()` stops propagation
- Handler runs to completion or until cancelled

### 3.2 Registration Placement

`on` handlers can be registered at:

- **Module level** — registers immediately when the script loads
- **Inside `if` blocks** — conditional registration, evaluated at module load time

```ink
// Module level — always registered
on player_join(event, player) {
    print("Welcome!")
}

// Conditional — registered only if true at load time
if config.enable_greeting {
    on player_join(event, player) {
        player.sendMessage("Hello!")
    }
}
```

**Nested `on` inside another `on` handler is not supported.** Dynamic registration (registering handlers from within other handlers) is out of scope for now.

### 3.3 Cancellation

```ink
on player_join(event, player) {
    if player.name == "BannedPlayer" {
        event.cancel()
    }
}
```

- `event.cancel()` is the only way to cancel
- Return values do not control cancellation
- Cancelling skips remaining handlers for that event

### 3.4 Execution Order

- Handlers fire in **registration order**
- If a handler calls `cancel()`, remaining handlers for that event are skipped
- Handler execution is **synchronous**

### 3.5 Exception Handling

If a handler throws an exception:
1. That handler stops executing
2. Remaining handlers for the same event are still attempted (unless cancelled)
3. The exception is propagated to the runtime's exception handler (logged on Paper)

The runtime is responsible for bridging synchronous Ink handler execution to async platform events (e.g., Paper's `AsyncPlayerChatEvent`). Thread-safety and event-queue semantics are runtime-defined.

---

## 4. Data Types

Ink-native types passed to handlers:

| Type       | Properties / Methods                                      |
|------------|------------------------------------------------------------|
| `Player`   | `name: String`, `world: String`, `sendMessage(msg: String)` |
| `Block`    | `material: String`, `position: Position`                  |
| `Entity`   | `type: String`, `position: Position`                      |
| `Position` | `x: Number`, `y: Number`, `z: Number`                     |
| `String`   | built-in                                                   |
| `Number`   | built-in                                                   |

Runtime is responsible for converting platform objects → Ink types.

---

## 5. Event Discovery

Runtime provides an `events` registry:

```ink
events.list()                    // returns [player_join, player_quit, ...]
events.exists("player_join")    // true/false
events.info("player_join")      // returns { name: String, parameters: [{ name: String, type: String }] }
```

Example `events.info("player_join")`:
```ink
{
    name: "player_join",
    parameters: [
        { name: "event", type: "Event" },
        { name: "player", type: "Player" }
    ]
}
```

---

## 6. Error Handling

| Error                              | Message Example                                                    |
|------------------------------------|---------------------------------------------------------------------|
| Unknown event                      | `RuntimeError: Unknown event 'player_jon'. Available: player_join, ...` |
| Wrong parameter count              | `RuntimeError: Event 'player_join' expects 3 parameters (including event), got 2` |
| Wrong parameter types              | `RuntimeError: Parameter 2 of 'player_join' must be Player, got Block` |
| Event not supported on platform   | `RuntimeError: Event 'server_start' is not supported on Hytale runtime` |
| Cancel on non-cancellable event   | `RuntimeError: Event 'server_start' cannot be cancelled`         |
| Handler throws                    | Exception propagated to runtime handler; other handlers still attempted |

---

## 7. Persistence Model

Handlers are **persistent by default** — they live in the runtime and fire for as long as the runtime is running:

- **Paper**: Handlers persist until server shutdown / plugin reload
- **Hytale**: Handlers persist until game client closes

No manual unregistration (`off`). Handlers are tied to the runtime lifecycle and cleared when the runtime shuts down.

---

## 8. Runtime Implementation Requirements

Runtimes must implement:

1. **Type mappings** — Ink types (Player, Block, Entity) → platform objects
2. **Event mappings** — Ink event names → platform event classes
3. **Event bus integration** — register listeners with the platform's event system
4. **Cancellation bridge** — translate `event.cancel()` → platform cancellation mechanism
5. **`events` registry** — implement `events.list()`, `events.exists()`, `events.info()`

---

---

## 10. Related Designs

- [2026-03-19-inklang-website-design.md](./2026-03-19-inklang-website-design.md) — Ink stdlib structure
- [2026-03-20-nullsafety-operators-design.md](./2026-03-20-nullsafety-operators-design.md) — null safety in types
