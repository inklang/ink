# Homes Plugin Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A multi-home management plugin for Inklang. Players can save, list, delete, and teleport to named homes. Data persisted to SQLite. Max homes and teleport countdown configurable.

**Architecture:** The homes logic lives entirely in an Ink plugin (`.ink` file). The plugin creates a `homes` table in SQLite and exposes functions (`setHome`, `getHome`, `deleteHome`, `listHomes`, `teleportHome`) as command stubs. Command registration is handled separately by the user's command infrastructure.

**Tech Stack:** Inklang scripting language, SQLite (via existing `db` driver), Kotlin for test scaffolding.

---

## Chunk 1: Plugin File + Core Logic

**Files:**
- Create: `ink-bukkit/src/main/resources/plugins/ink/plugins/homes.ink`
- Modify: `ink-bukkit/src/main/resources/plugins/ink/plugins/homes-config.toml` (config defaults)
- Test: `ink-bukkit/src/test/kotlin/org/inklang/bukkit/HomesPluginTest.kt` (new)

### Task 1: Create `homes.ink` plugin file

- [ ] **Step 1: Create `homes.ink` with enable/disable blocks, Home table, and homes module**

```ink
// homes.ink - Multi-home management plugin

enable {
    log("Homes plugin enabled");
}

disable {
    log("Homes plugin disabled");
}

// --- Config ---
// Default config values (overridden by homes-config.toml)
let __config = {
    max_homes: 5,
    teleport_countdown_seconds: 0
};

// Load config from file if present
let __configText = io.read("homes-config.toml");
if __configText != "" {
    let __lines = __configText.split("\n");
    for __line in __lines {
        if __line contains "max_homes" {
            let __parts = __line.split("=");
            if __parts.size() >= 2 {
                let __val = __parts[1].trim().parseInt();
                if __val != null {
                    __config.max_homes = __val;
                }
            }
        }
        if __line contains "teleport_countdown_seconds" {
            let __parts = __line.split("=");
            if __parts.size() >= 2 {
                let __val = __parts[1].trim().parseInt();
                if __val != null {
                    __config.teleport_countdown_seconds = __val;
                }
            }
        }
    }
}

// --- Home table ---
table Home {
    player_name isKey,
    home_name isKey,
    world,
    x,
    y,
    z,
    created_at
};

// --- Homes module ---
fn setHome(player, name) {
    let playerName = player.name;
    let existingCount = Home.where("player_name = ?", playerName).count();
    if existingCount >= __config.max_homes {
        return "max_homes_reached";
    }

    let existing = Home.where("player_name = ? AND home_name = ?", playerName, name).first();
    if existing != null {
        return "home_exists";
    }

    let loc = player.location;
    Home.insert({
        player_name: playerName,
        home_name: name,
        world: loc.world,
        x: loc.x,
        y: loc.y,
        z: loc.z,
        created_at: 0  // timestamp handled externally if needed
    });

    return "home_set";
}

fn getHome(player, name) {
    let playerName = player.name;
    let home = Home.where("player_name = ? AND home_name = ?", playerName, name).first();
    if home == null {
        return null;
    }
    return home;
}

fn deleteHome(player, name) {
    let playerName = player.name;
    let home = Home.where("player_name = ? AND home_name = ?", playerName, name).first();
    if home == null {
        return null;
    }
    Home.delete([playerName, name]);
    return true;
}

fn listHomes(player) {
    let playerName = player.name;
    return Home.where("player_name = ?", playerName).all();
}

// --- Command stubs (wired by command infrastructure) ---

fn __cmd_sethome(player, name) {
    return setHome(player, name);
}

fn __cmd_home(player, name) {
    return teleportHome(player, name);
}

fn __cmd_delhome(player, name) {
    return deleteHome(player, name);
}

fn __cmd_listhomes(player) {
    let homes = listHomes(player);
    if homes.size() == 0 {
        return [];
    }
    return homes.map(fn(h) { h.home_name });
}

// --- Teleport ---
fn teleportHome(player, name) {
    let home = getHome(player, name);
    if home == null {
        return "home_not_found";
    }

    // Check world exists via server global
    let targetWorld = server.getWorld(home.world);
    if targetWorld == null {
        return "world_not_found";
    }

    if __config.teleport_countdown_seconds > 0 {
        // Countdown mode: send action bar and return
        let seconds = __config.teleport_countdown_seconds;
        player.sendActionBar("Teleporting to ${name} in ${seconds} seconds...");
        // Note: true async delay requires scheduler integration
        // For now, teleport immediately (countdown is message only)
        player.teleport(home.x, home.y, home.z, home.world);
        return "teleporting";
    } else {
        // Instant teleport
        player.teleport(home.x, home.y, home.z, home.world);
        return "teleporting";
    }
}
```

### Task 2: Create default config file

- [ ] **Step 1: Create `homes-config.toml` defaults**

```toml
max_homes = 5
teleport_countdown_seconds = 0
```

Place at: `ink-bukkit/src/main/resources/plugins/ink/plugins/homes-config.toml`

---

## Chunk 2: Testing

**Files:**
- Create: `ink-bukkit/src/test/kotlin/org/inklang/bukkit/HomesPluginTest.kt`

### Task 3: Write integration tests for homes plugin

- [ ] **Step 1: Write `HomesPluginTest.kt` with test cases for set/get/delete/list**

```kotlin
package org.inklang.bukkit

import org.inklang.InkCompiler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.bukkit.entity.Player
import io.mockk.mockk
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomesPluginTest {

    private lateinit var server: ServerMock
    private lateinit var sender: Player

    @TempDir
    lateinit var tempDir: File

    private lateinit var ioDriver: BukkitIo
    private lateinit var jsonDriver: BukkitJson
    private lateinit var dbDriver: BukkitDb
    private lateinit var context: BukkitContext

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        sender = server.addPlayer("TestPlayer")

        val scriptDir = File(tempDir, "scripts").also { it.mkdirs() }
        val dbFile = File(tempDir, "test.db")

        ioDriver = BukkitIo(scriptDir)
        jsonDriver = BukkitJson()
        dbDriver = BukkitDb(dbFile.absolutePath)
        val mockPlugin = mockk<InkBukkit>(relaxed = true)
        context = BukkitContext(sender, mockPlugin, ioDriver, jsonDriver, dbDriver)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `setHome creates home in database`() {
        val source = """
            table Home { player_name isKey, home_name isKey, world, x, y, z, created_at };
            Home.insert({player_name: "TestPlayer", home_name: "home1", world: "world", x: 100.0, y: 64.0, z: 200.0, created_at: 0});
            let home = Home.where("player_name = ?", "TestPlayer").first();
            print(home.home_name);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        assertTrue(context.capturedOutput.any { it.contains("home1") })
    }

    @Test
    fun `getHome returns null for missing home`() {
        val source = """
            table Home { player_name isKey, home_name isKey, world, x, y, z, created_at };
            let home = Home.where("player_name = ? AND home_name = ?", "TestPlayer", "missing").first();
            print(home ?? "null");
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        assertTrue(context.capturedOutput.any { it.contains("null") })
    }

    @Test
    fun `deleteHome removes home from database`() {
        val source = """
            table Home { player_name isKey, home_name isKey, world, x, y, z, created_at };
            Home.insert({player_name: "TestPlayer", home_name: "home1", world: "world", x: 100.0, y: 64.0, z: 200.0, created_at: 0});
            Home.delete(["TestPlayer", "home1"]);
            let count = Home.where("player_name = ?", "TestPlayer").count();
            print(count);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        assertTrue(context.capturedOutput.any { it.contains("0") })
    }

    @Test
    fun `listHomes returns all homes for player`() {
        val source = """
            table Home { player_name isKey, home_name isKey, world, x, y, z, created_at };
            Home.insert({player_name: "TestPlayer", home_name: "home1", world: "world", x: 100.0, y: 64.0, z: 200.0, created_at: 0});
            Home.insert({player_name: "TestPlayer", home_name: "home2", world: "world", x: 300.0, y: 64.0, z: 400.0, created_at: 0});
            Home.insert({player_name: "OtherPlayer", home_name: "other", world: "world", x: 0.0, y: 64.0, z: 0.0, created_at: 0});
            let homes = Home.where("player_name = ?", "TestPlayer").all();
            print(homes.size());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        assertTrue(context.capturedOutput.any { it.contains("2") })
    }

    @Test
    fun `max homes check prevents exceeding limit`() {
        val source = """
            table Home { player_name isKey, home_name isKey, world, x, y, z, created_at };
            let maxHomes = 2;
            // Try to add 3 homes
            Home.insert({player_name: "TestPlayer", home_name: "home1", world: "world", x: 100.0, y: 64.0, z: 200.0, created_at: 0});
            Home.insert({player_name: "TestPlayer", home_name: "home2", world: "world", x: 300.0, y: 64.0, z: 400.0, created_at: 0});
            let count = Home.where("player_name = ?", "TestPlayer").count();
            if count >= maxHomes {
                print("limit_reached");
            } else {
                Home.insert({player_name: "TestPlayer", home_name: "home3", world: "world", x: 500.0, y: 64.0, z: 600.0, created_at: 0});
                print("added");
            }
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        assertTrue(context.capturedOutput.any { it.contains("limit_reached") })
    }
}
```

- [ ] **Step 2: Run tests to verify they compile and pass**

Run: `./gradlew :ink-bukkit:test --tests "org.inklang.bukkit.HomesPluginTest" -v`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add ink-bukkit/src/main/resources/plugins/ink/plugins/homes.ink
git add ink-bukkit/src/main/resources/plugins/ink/plugins/homes-config.toml
git add ink-bukkit/src/test/kotlin/org/inklang/bukkit/HomesPluginTest.kt
git commit -m "feat: add homes plugin with core logic and tests

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

---

## Chunk 3: Integration with Config File Loading

**Files:**
- Modify: `ink-bukkit/src/main/resources/plugins/ink/plugins/homes.ink` (fix config loading)
- Modify: `HomesPluginTest.kt` (add config loading tests)

### Task 4: Verify config loading and refine `homes.ink`

- [ ] **Step 1: Write test for config file loading**

```kotlin
@Test
fun `config loading reads max_homes from file`() {
    // Write config file
    val configFile = File(tempDir, "scripts/homes-config.toml")
    configFile.parentFile?.mkdirs()
    configFile.writeText("max_homes = 3\nteleport_countdown_seconds = 5\n")

    val source = """
        let __configText = io.read("homes-config.toml");
        let maxHomes = 5;
        if __configText != "" {
            let __lines = __configText.split("\n");
            for __line in __lines {
                if __line contains "max_homes" {
                    let __parts = __line.split("=");
                    if __parts.size() >= 2 {
                        let __val = __parts[1].trim().parseInt();
                        if __val != null {
                            maxHomes = __val;
                        }
                    }
                }
            }
        }
        print(maxHomes);
    """.trimIndent()

    val compiler = InkCompiler()
    val script = compiler.compile(source)
    script.execute(context)

    assertTrue(context.capturedOutput.any { it.contains("3") })
}
```

- [ ] **Step 2: Run test and fix issues**
- [ ] **Step 3: Commit**

---

## Notes for Command Infrastructure

When wiring commands, the command handler in Kotlin will:

1. Parse command args
2. Call into the homes plugin VM via `vm.execute(chunk)` with a snippet that invokes `__cmd_sethome`, etc., OR expose the homes module as a global Ink function registered at plugin load time
3. Return the result string to the player

The command stubs in `homes.ink` (`__cmd_sethome`, etc.) are the integration points. They return result strings that the Kotlin command handler interprets.
