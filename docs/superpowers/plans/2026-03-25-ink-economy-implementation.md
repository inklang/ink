# ink.economy — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `ink.economy` as a lectern-published economy package. Single currency ("coins"), SQLite-backed, with `eco_*` built-in functions callable from Ink scripts.

**Architecture:**
- `EconoDb` — SQLite wrapper for account CRUD
- `EconomySkills` — `eco_*` built-in functions registered via `BukkitRuntimeRegistrar`
- `EconomyHandler` — handles `economy { on_config }` grammar declarations
- `EconoPlayerListener` — PlayerJoin name sync
- Package structure at `examples/ink.economy/` for lectern publishing

**Tech Stack:** Kotlin (runtime), TypeScript/Node (grammar IR build), Rust (Printing Press — unchanged), PaperMC API, SQLite

---

## Chunk 1: Create Package Structure

**Files to create:**
- `examples/ink.economy/ink-package.toml`
- `examples/ink.economy/src/grammar.ts`
- `examples/ink.economy/scripts/main.ink`
- `examples/ink.economy/scripts/examples.ink`
- `examples/ink.economy/dist/ink-manifest.json`

### Task 1.1: Create package directory and manifest

- [ ] **Step 1: Create directory structure**
  ```bash
  mkdir -p examples/ink.economy/src
  mkdir -p examples/ink.economy/dist/scripts
  mkdir -p examples/ink.economy/scripts
  ```

- [ ] **Step 2: Write `ink-package.toml`**
  ```toml
  [package]
  name = "ink.economy"
  version = "0.1.0"

  [dependencies]

  [grammar]
  entry = "src/grammar.ts"
  output = "dist/grammar.ir.json"
  ```

- [ ] **Step 3: Write `src/grammar.ts`**
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

- [ ] **Step 4: Write `dist/ink-manifest.json`**
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
    "author": "inklang",
    "license": "MIT"
  }
  ```

- [ ] **Step 5: Commit**
  ```bash
  git add examples/ink.economy/
  git commit -m "feat(economy): scaffold ink.economy package structure"
  ```

---

## Chunk 2: Build Grammar IR

**File:** `examples/ink.economy/dist/grammar.ir.json`

- [ ] **Step 1: Find how to build grammar IR**

  The grammar IR is built by the Quill toolchain. Check how `examples/ink.mobs/dist/grammar.ir.json` was previously generated. The `ink-package.toml` points `grammar.entry = "src/grammar.ts"` and `grammar.output = "dist/grammar.ir.json"`. Find the Quill CLI command that reads this config and compiles `src/grammar.ts` → `dist/grammar.ir.json`. Run that command.

  Expected: `dist/grammar.ir.json` created with grammar rules

- [ ] **Step 2: Commit**
  ```bash
  git add examples/ink.economy/dist/grammar.ir.json
  git commit -m "feat(economy): build grammar IR"
  ```

---

## Chunk 3: Implement EconoDb (SQLite Wrapper)

**File:** `ink-bukkit/src/main/kotlin/org/inklang/bukkit/EconoDb.kt` (create)

### Task 3.1: Create EconoDb class

- [ ] **Step 1: Create `EconoDb.kt`**

```kotlin
package org.inklang.bukkit

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * SQLite-backed economy account store.
 * All operations are blocking (call from async context if needed).
 */
class EconoDb(dbPath: String) {

    private val connection: Connection

    init {
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.autoCommit = false
        createTable()
    }

    private fun createTable() {
        connection.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS economy_accounts (
                    player_uuid TEXT PRIMARY KEY,
                    player_name TEXT NOT NULL,
                    balance INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
        connection.commit()
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    /**
     * Get player's balance. Returns 0 if account doesn't exist.
     */
    fun getBalance(uuid: String): Long {
        connection.prepareStatement("SELECT balance FROM economy_accounts WHERE player_uuid = ?").use { ps ->
            ps.setString(1, uuid)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getLong("balance") else 0L
            }
        }
    }

    /**
     * Ensure an account exists (creates with 0 balance if not).
     */
    fun ensureAccount(uuid: String, name: String) {
        connection.prepareStatement("""
            INSERT OR IGNORE INTO economy_accounts (player_uuid, player_name, balance, updated_at)
            VALUES (?, ?, 0, ?)
        """.trimIndent()).use { ps ->
            ps.setString(1, uuid)
            ps.setString(2, name)
            ps.setLong(3, now())
            ps.executeUpdate()
        }
        connection.commit()
    }

    /**
     * Update player's display name.
     */
    fun updateName(uuid: String, name: String) {
        connection.prepareStatement("UPDATE economy_accounts SET player_name = ?, updated_at = ? WHERE player_uuid = ?").use { ps ->
            ps.setString(1, name)
            ps.setLong(2, now())
            ps.setString(3, uuid)
            ps.executeUpdate()
        }
        connection.commit()
    }

    /**
     * Set player's balance to exactly `amount`. Clamps to 0 if negative.
     * Returns the new balance.
     */
    fun setBalance(uuid: String, name: String, amount: Long): Long {
        val clamped = amount.coerceAtLeast(0)
        ensureAccount(uuid, name)
        connection.prepareStatement("UPDATE economy_accounts SET balance = ?, updated_at = ? WHERE player_uuid = ?").use { ps ->
            ps.setLong(1, clamped)
            ps.setLong(2, now())
            ps.setString(3, uuid)
            ps.executeUpdate()
        }
        connection.commit()
        return clamped
    }

    /**
     * Add `amount` coins to player. Returns new balance.
     * Errors if result would exceed Long.MAX_VALUE.
     */
    fun giveCoins(uuid: String, name: String, amount: Long): Long {
        if (amount <= 0) return getBalance(uuid)
        ensureAccount(uuid, name)
        val current = getBalance(uuid)
        val newBalance = current + amount
        if (newBalance < 0) error("eco_give: balance overflow")
        setBalance(uuid, name, newBalance)
        return newBalance
    }

    /**
     * Remove up to `amount` coins from player. Returns new balance (clamped to 0).
     */
    fun takeCoins(uuid: String, name: String, amount: Long): Long {
        if (amount <= 0) return getBalance(uuid)
        ensureAccount(uuid, name)
        val current = getBalance(uuid)
        val newBalance = (current - amount).coerceAtLeast(0)
        setBalance(uuid, name, newBalance)
        return newBalance
    }

    /**
     * Transfer coins atomically from one player to another.
     * Returns true if successful, false if from player has insufficient funds.
     */
    fun transferCoins(fromUuid: String, fromName: String, toUuid: String, toName: String, amount: Long): Boolean {
        if (amount <= 0) return false
        ensureAccount(fromUuid, fromName)
        ensureAccount(toUuid, toName)
        val fromBalance = getBalance(fromUuid)
        if (fromBalance < amount) return false

        try {
            connection.autoCommit = false
            connection.prepareStatement("UPDATE economy_accounts SET balance = balance - ?, updated_at = ? WHERE player_uuid = ?").use { ps ->
                ps.setLong(1, amount)
                ps.setLong(2, now())
                ps.setString(3, fromUuid)
                ps.executeUpdate()
            }
            connection.prepareStatement("UPDATE economy_accounts SET balance = balance + ?, updated_at = ? WHERE player_uuid = ?").use { ps ->
                ps.setLong(1, amount)
                ps.setLong(2, now())
                ps.setString(3, toUuid)
                ps.executeUpdate()
            }
            connection.commit()
            return true
        } catch (e: SQLException) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Get top N richest players as list of [name, balance] pairs.
     */
    fun getTopN(n: Int): List<Pair<String, Long>> {
        if (n <= 0) return emptyList()
        connection.prepareStatement("SELECT player_name, balance FROM economy_accounts ORDER BY balance DESC LIMIT ?").use { ps ->
            ps.setInt(1, n)
            ps.executeQuery().use { rs ->
                val results = mutableListOf<Pair<String, Long>>()
                while (rs.next()) {
                    results.add(rs.getString("player_name") to rs.getLong("balance"))
                }
                return results
            }
        }
    }

    fun close() {
        connection.close()
    }
}
```

- [ ] **Step 2: Commit**
  ```bash
  git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/EconoDb.kt
  git commit -m "feat(economy): add EconoDb SQLite wrapper"
  ```

---

## Chunk 4: Implement EconomySkills (Built-in Functions)

**File:** `ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/EconomySkills.kt` (create)

### Task 4.1: Create EconomySkills

- [ ] **Step 1: Create `EconomySkills.kt`**

```kotlin
package org.inklang.bukkit.runtime

import org.inklang.lang.Value
import org.inklang.lang.ClassRegistry
import org.inklang.bukkit.EconoDb
import org.bukkit.Server
import java.util.UUID

/**
 * Singleton that holds the shared EconoDb instance across the plugin lifetime.
 * Initialized by EconoPluginRuntime when the economy plugin loads.
 */
object EconomyRegistry {
    lateinit var db: EconoDb
        private set

    fun init(dbPath: String) {
        if (!::db.isInitialized) {
            db = EconoDb(dbPath)
        }
    }

    fun close() {
        if (::db.isInitialized) {
            db.close()
        }
    }
}

object EconomySkills {

    private fun expectString(arg: Value?, name: String): String = when (arg) {
        is Value.String -> arg.value
        else -> error("$name: expected string argument")
    }

    private fun expectInt(arg: Value?, name: String): Long = when (arg) {
        is Value.Int -> arg.value.toLong()
        is Value.Double -> arg.value.toLong()
        else -> error("$name: expected integer argument")
    }

    private fun playerNameToUuid(name: String, server: Server): String {
        val player = server.getPlayer(name)
        return player?.uniqueId?.toString() ?: UUID.nameUUIDFromBytes("Offline:$name".toByteArray()).toString()
    }

    private fun ensurePlayer(uuid: String, name: String) {
        try {
            EconomyRegistry.db.ensureAccount(uuid, name)
        } catch (e: Exception) {
            // DB not initialized yet — will be created on first actual operation
        }
    }

    // eco_balance(player: String) -> Int
    val BALANCE = Value.NativeFunction { args ->
        val name = expectString(args.getOrNull(1), "eco_balance")
        val uuid = playerNameToUuid(name, ClassRegistry.getGlobal("server")?.let {
            // Get server from globals
        } ?: throw error("eco_balance: server not available"))
        try {
            Value.Int(EconomyRegistry.db.getBalance(uuid).toInt())
        } catch (e: Exception) {
            Value.Int(0)
        }
    }

    // eco_give(player: String, amount: Int) -> Int
    val GIVE = Value.NativeFunction { args ->
        val name = expectString(args.getOrNull(1), "eco_give")
        val amount = expectInt(args.getOrNull(2), "eco_give")
        val uuid = playerNameToUuid(name, /* server from context */)
        val newBalance = EconomyRegistry.db.giveCoins(uuid, name, amount)
        Value.Int(newBalance.toInt())
    }

    // eco_take(player: String, amount: Int) -> Int
    val TAKE = Value.NativeFunction { args ->
        val name = expectString(args.getOrNull(1), "eco_take")
        val amount = expectInt(args.getOrNull(2), "eco_take")
        val uuid = playerNameToUuid(name, /* server */)
        val newBalance = EconomyRegistry.db.takeCoins(uuid, name, amount)
        Value.Int(newBalance.toInt())
    }

    // eco_set(player: String, amount: Int) -> Int
    val SET = Value.NativeFunction { args ->
        val name = expectString(args.getOrNull(1), "eco_set")
        val amount = expectInt(args.getOrNull(2), "eco_set")
        val uuid = playerNameToUuid(name, /* server */)
        val newBalance = EconomyRegistry.db.setBalance(uuid, name, amount)
        Value.Int(newBalance.toInt())
    }

    // eco_transfer(from: String, to: String, amount: Int) -> Boolean
    val TRANSFER = Value.NativeFunction { args ->
        val fromName = expectString(args.getOrNull(1), "eco_transfer")
        val toName = expectString(args.getOrNull(2), "eco_transfer")
        val amount = expectInt(args.getOrNull(3), "eco_transfer")
        val fromUuid = playerNameToUuid(fromName, /* server */)
        val toUuid = playerNameToUuid(toName, /* server */)
        val success = EconomyRegistry.db.transferCoins(fromUuid, fromName, toUuid, toName, amount)
        Value.Boolean(success)
    }

    // eco_top(n: Int) -> Array
    val TOP = Value.NativeFunction { args ->
        val n = (args.getOrNull(1) as? Value.Int)?.value?.toInt() ?: 5
        val top = EconomyRegistry.db.getTopN(n)
        val arr = top.map { (name, balance) ->
            Value.Array(mutableListOf(Value.String(name), Value.Int(balance.toInt())))
        }
        Value.Array(arr.toMutableList())
    }

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

**Note:** The server reference in `EconomySkills` needs a server handle. Since `ClassRegistry` doesn't hold a server reference, pass server through a thread-local or add a server accessor. For simplicity, use `org.bukkit.Bukkit.getServer()` as a static accessor — it's safe in a Bukkit plugin context.

- [ ] **Step 2: Fix server reference in EconomySkills**

Replace `playerNameToUuid` with a static Bukkit accessor:

```kotlin
private fun playerNameToUuid(name: String): String {
    val server = org.bukkit.Bukkit.getServer()
    val player = server.getPlayer(name)
    return player?.uniqueId?.toString() ?: UUID.nameUUIDFromBytes("Offline:$name".toByteArray()).toString()
}
```

Remove the server parameter throughout.

- [ ] **Step 3: Register in `BukkitRuntimeRegistrar`**

Add to `BukkitRuntimeRegistrar.register()`:
```kotlin
EconomySkills.ALL.forEach { (name, fn) ->
    ClassRegistry.registerGlobal(name, ClassDescriptor(
        name = name,
        superClass = null,
        methods = mapOf()
    ))
}
```

But `ClassRegistry.registerGlobal` takes a `ClassDescriptor`, not a `Value`. The current pattern registers classes/descriptors. `eco_*` functions should be registered as globals too.

Looking at how `PaperGlobals` registers globals — check `PaperGlobals.kt`:

- [ ] **Step 4: Check how globals are registered**
  Read `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PaperGlobals.kt` to understand how globals map to Values.

- [ ] **Step 5: Fix EconomySkills ALL registration**

Based on how PaperGlobals works, update `ALL` to be registered correctly.

- [ ] **Step 6: Commit**
  ```bash
  git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/EconomySkills.kt
  git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/BukkitRuntimeRegistrar.kt
  git commit -m "feat(economy): add EconomySkills eco_* built-in functions"
  ```

---

## Chunk 5: Player Name Sync Listener

**File:** `ink-bukkit/src/main/kotlin/org/inklang/bukkit/EconoPlayerListener.kt` (create)

- [ ] **Step 1: Create `EconoPlayerListener.kt`**

```kotlin
package org.inklang.bukkit

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Updates player name in economy DB when they join (in case it changed).
 */
class EconoPlayerListener : Listener {

    @EventHandler
    fun onPlayerJoin(evt: PlayerJoinEvent) {
        try {
            val uuid = evt.player.uniqueId.toString()
            val name = evt.player.name
            EconomyRegistry.db.updateName(uuid, name)
        } catch (e: Exception) {
            // DB may not be initialized yet — ignore
        }
    }
}
```

- [ ] **Step 2: Register listener in `InkBukkit.onEnable()`**

After `pluginRuntime = PluginRuntime(...)`, add:
```kotlin
// Initialize economy DB
val ecoDbPath = File(dataFolder, "economy.db").absolutePath
EconomyRegistry.init(ecoDbPath)

// Register economy player listener
server.pluginManager.registerEvents(EconoPlayerListener(), this)
```

- [ ] **Step 3: Close DB in `InkBukkit.onDisable()`**

```kotlin
override fun onDisable() {
    // ... existing code ...
    EconomyRegistry.close()
}
```

- [ ] **Step 4: Commit**
  ```bash
  git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/EconoPlayerListener.kt
  git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt
  git commit -m "feat(economy): add player name sync listener and DB lifecycle"
  ```

---

## Chunk 6: Write Example Ink Scripts

**Files:**
- `examples/ink.economy/scripts/main.ink`
- `examples/ink.economy/scripts/examples.ink`

### Task 6.1: Write main.ink

```ink
// ink.economy — loaded on plugin enable
economy {
    on_config {
        print("ink.economy v0.1.0 loaded")
    }
}
```

### Task 6.2: Write examples.ink

```ink
// ink.economy usage examples

// Check balance
let balance = eco_balance("Steve")
print("Steve's balance: ${balance}")

// Give coins
let newBal = eco_give("Steve", 100)
print("Gave Steve 100 coins. New balance: ${newBal}")

// Take coins (clamped to 0)
let after = eco_take("Steve", 1000)
print("Took up to 1000 from Steve. Final balance: ${after}")

// Set balance
eco_set("Steve", 500)
print("Set Steve's balance to 500")

// Transfer
let ok = eco_transfer("Steve", "Treasury", 50)
if !ok {
    print("Transfer failed — insufficient funds!")
}

// Top 5 richest
let top = eco_top(5)
print("Top 5 richest players:")
print(top)
```

### Task 6.3: Compile scripts

Run Printing Press to compile `main.ink` and `examples.ink` to `.inkc` files in `dist/scripts/`.

- [ ] **Step 1: Compile scripts**
  ```bash
  cd ~/dev/printing_press
  cargo build --release  # if needed
  ./target/release/printing_press compile examples/ink.economy/scripts/main.ink -o examples/ink.economy/dist/scripts/main.inkc
  ./target/release/printing_press compile examples/ink.economy/scripts/examples.ink -o examples/ink.economy/dist/scripts/examples.inkc
  ```

- [ ] **Step 2: Commit**
  ```bash
  git add examples/ink.economy/scripts/
  git add examples/ink.economy/dist/scripts/
  git commit -m "feat(economy): write example Ink scripts"
  ```

---

## Chunk 7: Build and Verify

- [ ] **Step 1: Compile Kotlin**
  ```bash
  ./gradlew :ink-bukkit:compileKotlin
  ```
  Expected: Compiles without errors

- [ ] **Step 2: Run tests**
  ```bash
  ./gradlew :ink-bukkit:test
  ```
  Expected: Tests pass (may have pre-existing failures unrelated to economy)

- [ ] **Step 3: Build JAR**
  ```bash
  ./gradlew :ink-bukkit:build
  ```
  Expected: JAR built successfully

- [ ] **Step 4: Commit build artifacts**
  ```bash
  git add ink-bukkit/build/libs/*.jar
  git commit -m "feat(economy): build ink-bukkit with economy support"
  ```

---

## Chunk 8: Publish to Lectern

**Prerequisite:** lectern CLI tool (`lt`) must be configured with registry credentials.

- [ ] **Step 1: Verify package structure**
  ```
  examples/ink.economy/
  ├── ink-package.toml
  ├── dist/
  │   ├── grammar.ir.json
  │   ├── ink-manifest.json
  │   └── scripts/
  │       ├── main.inkc
  │       └── examples.inkc
  ```

- [ ] **Step 2: Run `lt publish`**
  From `examples/ink.economy/`:
  ```bash
  lt publish
  ```

- [ ] **Step 3: Verify published**
  Check the lectern registry index for `ink.economy` appearing.

---

## File Summary

| File | Action |
|---|---|
| `examples/ink.economy/ink-package.toml` | Create |
| `examples/ink.economy/src/grammar.ts` | Create |
| `examples/ink.economy/dist/grammar.ir.json` | Build |
| `examples/ink.economy/scripts/main.ink` | Create |
| `examples/ink.economy/scripts/examples.ink` | Create |
| `examples/ink.economy/dist/scripts/main.inkc` | Compile |
| `examples/ink.economy/dist/scripts/examples.inkc` | Compile |
| `examples/ink.economy/dist/ink-manifest.json` | Create |
| `ink-bukkit/.../EconoDb.kt` | Create |
| `ink-bukkit/.../EconoPlayerListener.kt` | Create |
| `ink-bukkit/.../runtime/EconomySkills.kt` | Create |
| `ink-bukkit/.../runtime/BukkitRuntimeRegistrar.kt` | Modify |
| `ink-bukkit/.../InkBukkit.kt` | Modify |
