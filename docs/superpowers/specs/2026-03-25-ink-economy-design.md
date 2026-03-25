# ink.economy — Design Specification

> **Project:** inklang ecosystem
> **Date:** 2026-03-25
> **Status:** Draft

## Overview

`ink.economy` is a lectern-published economy package for inklang. It provides a single-currency virtual economy system for Minecraft servers with account management, balance tracking, transfers, and通货膨胀 controls.

**Core concepts:**
- Each player has one economy account keyed by UUID
- Currency is a single "coins" unit (integer, no decimals)
- All operations are atomic and race-safe
- Economy data persists in the plugin's SQLite database
- Built-in functions registered via `BukkitRuntimeRegistrar` — callable directly from Ink scripts

---

## Data Model

### Database Schema

```sql
CREATE TABLE IF NOT EXISTS economy_accounts (
    player_uuid TEXT PRIMARY KEY,  -- UUID string
    player_name TEXT NOT NULL,     -- current or last known name
    balance INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL    -- Unix epoch seconds
);
```

### Balance Rules
- Balance is `INTEGER` (long) — no decimals
- Minimum balance: `0` (no debt)
- Maximum balance: `9,223,372,036,854,775,807` (Long.MAX_VALUE)
- Negative balance impossible — operations clamp to 0

---

## Grammar Extension

Extends Ink with a new top-level declaration keyword `economy`.

### File: `examples/ink.economy/src/grammar.ts`

```typescript
import { defineGrammar, declaration, rule } from '@inklang/quill/grammar'

export default defineGrammar({
  package: 'ink.economy',
  declarations: [
    declaration({
      keyword: 'economy',
      inheritsBase: true,
      rules: [
        rule('on_config_clause', r => r.seq(r.keyword('on_config'), r.block())),
      ]
    })
  ]
})
```

**Note:** The `on_config` clause lets admins configure economy settings via Ink code. If empty, defaults apply.

---

## Runtime Built-ins

### Account Management Functions

| Function | Returns | Description |
|---|---|---|
| `eco_balance(player)` | `Int` | Get player's current balance |
| `eco_give(player, amount)` | `Int` | Add coins to player, returns new balance |
| `eco_take(player, amount)` | `Int` | Remove coins from player, returns new balance (clamped to 0) |
| `eco_set(player, amount)` | `Int` | Set player's balance, returns new balance |
| `eco_transfer(from, to, amount)` | `Boolean` | Transfer coins between players. Returns true if successful, false if insufficient funds |
| `eco_top(n)` | `Array` | Get top N richest players as `[[name, balance], ...]` |

### Player Lifecycle Hooks

- `on_config` — fires during plugin enable, for setting economic parameters

### Amount Parameter Rules
- All amounts are integers (no decimals)
- `eco_take` clamps to 0 if amount exceeds balance
- `eco_give` errors if result would exceed Long.MAX_VALUE
- `eco_transfer` does nothing and returns `false` if `from` has insufficient balance

---

## Ink Scripting API

Example usage in Ink scripts:

```ink
// Check a player's balance
let balance = eco_balance("Steve")
print("Steve has ${balance} coins")

// Give a reward
eco_give("Steve", 100)

// Take a fee (e.g., entry fee)
let success = eco_transfer("Steve", "Treasury", 50)
if !success {
    print("Insufficient funds!")
}

// Set a starting balance for new players
eco_set("NewPlayer", 500)

// List top 5 richest players
let top = eco_top(5)
print(top)
```

---

## Bukkit Event Hooks

`ink.economy` hooks into PaperMC player events to maintain name sync:

| Event | Action |
|---|---|
| `PlayerJoinEvent` | Updates `player_name` in DB if changed |
| `PlayerQuitEvent` | No action (data persists) |

---

## Package Structure

```
examples/ink.economy/
├── ink-package.toml              # Package manifest
├── src/
│   └── grammar.ts                # Grammar extension
├── dist/
│   ├── grammar.ir.json           # Compiled grammar IR
│   └── scripts/
│       └── main.inkc             # Compiled entry script
├── scripts/
│   ├── main.ink                  # Entry — sets up built-in hooks
│   └── examples.ink              # Usage examples
└── dist/
    └── ink-manifest.json         # Lectern manifest
```

### `ink-package.toml`

```toml
[package]
name = "ink.economy"
version = "0.1.0"

[dependencies]

[grammar]
entry = "src/grammar.ts"
output = "dist/grammar.ir.json"
```

### `ink-manifest.json`

```json
{
  "name": "ink.economy",
  "version": "0.1.0",
  "description": "Single-currency economy system for inklang servers. Provides balance tracking, transfers, and built-in eco_* functions.",
  "grammar": "grammar.ir.json",
  "scripts": [
    "main.inkc",
    "examples.inkc"
  ],
  "keywords": ["economy", "coins", "paper", "minecraft", "server"],
  "author": "<your-handle>",
  "license": "MIT"
}
```

---

## Implementation Architecture

### Kotlin Components

| File | Responsibility |
|---|---|
| `ink-bukkit/.../runtime/EconomySkills.kt` | All `eco_*` built-in functions as `Value.NativeFunction` |
| `ink-bukkit/.../runtime/BukkitRuntimeRegistrar.kt` | Registers `EcoSkills` globals on VM init |
| `ink-bukkit/.../handlers/EconomyHandler.kt` | Handles `economy { on_config { ... } }` declarations |
| `ink-bukkit/.../EconoDb.kt` | SQLite wrapper for account CRUD |
| `ink-bukkit/.../EconoPlayerListener.kt` | PlayerJoin name sync hook |

### Class: `EconoDb`

```kotlin
class EconoDb(dbPath: String) {
    fun getBalance(uuid: String): Long
    fun setBalance(uuid: String, name: String, amount: Long)
    fun giveCoins(uuid: String, name: String, amount: Long): Long   // returns new balance
    fun takeCoins(uuid: String, name: String, amount: Long): Long  // returns new balance (clamped)
    fun transferCoins(fromUuid: String, fromName: String, toUuid: String, toName: String, amount: Long): Boolean
    fun getTopN(n: Int): List<Pair<String, Long>>
    fun ensureAccount(uuid: String, name: String)  // creates with 0 balance if not exists
    fun updateName(uuid: String, name: String)
    fun close()
}
```

### Class: `EconomySkills`

```kotlin
object EconomySkills {
    val BALANCE = Value.NativeFunction { args -> ... }
    val GIVE = Value.NativeFunction { args -> ... }
    val TAKE = Value.NativeFunction { args -> ... }
    val SET = Value.NativeFunction { args -> ... }
    val TRANSFER = Value.NativeFunction { args -> ... }
    val TOP = Value.NativeFunction { args -> ... }

    val ALL = mapOf(
        "eco_balance" to BALANCE,
        "eco_give" to GIVE,
        "eco_take" to TAKE,
        "eco_set" to SET,
        "eco_transfer" to TRANSFER,
        "eco_top" to TOP
    )
}
```

---

## Security Considerations

- All amounts are validated as non-negative integers before DB write
- `eco_transfer` is atomic — uses a transaction
- `eco_take` cannot go below 0
- Player name is stored for display only; all internal ops use UUID

---

## Testing Strategy

1. **Unit tests** for `EconoDb` — CRUD operations, edge cases (negative amounts, overflow, transfer atomicity)
2. **Integration test** — load `ink.economy` in test server, call each function via ad-hoc script execution
3. **Transfer atomicity test** — concurrent transfers between same accounts, verify no money lost or created

---

## Deferred

- Multi-currency support (named currencies)
- Currency exchange rates
- Economy reset / inflation controls
- Transaction history log
- BAML/console commands (`/eco give <player> <amount>`)
