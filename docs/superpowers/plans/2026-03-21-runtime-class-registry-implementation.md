# Runtime Class Registry Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace monolithic `PaperGlobals.kt` with a game-agnostic class registry and structured Bukkit class definitions.

**Architecture:** `ClassRegistry` in `org.inklang.lang` provides a global map for class descriptors. `BukkitRuntimeRegistrar` populates it with Bukkit-specific `ClassDescriptor` implementations. Each class (Player, Server, World, Location, Inventory) gets its own file under `org.inklang.bukkit.runtime`.

**Tech Stack:** Kotlin 2.2.21, JVM 21, Gradle, Paper/Bukkit API

---

## File Structure

```
ink/lang/src/main/kotlin/org/inklang/lang/
├── ClassRegistry.kt          # NEW - game-agnostic registry

ink-bukkit/src/main/kotlin/org/inklang/bukkit/
├── runtime/
│   ├── BukkitRuntimeRegistrar.kt   # NEW - composes all registrations
│   ├── PlayerClass.kt               # NEW - Player class definition
│   ├── ServerClass.kt               # NEW - Server class definition
│   ├── WorldClass.kt                # NEW - World class definition
│   ├── LocationClass.kt             # NEW - Location class definition
│   └── InventoryClass.kt            # NEW - Inventory class definition
├── PaperGlobals.kt                  # MODIFY - delegate to registrar (backwards compat)
└── BukkitContext.kt                 # MODIFY - wire registrar
```

---

## Chunk 1: ClassRegistry Core

**Files:**
- Create: `ink/lang/src/main/kotlin/org/inklang/lang/ClassRegistry.kt`
- Create: `ink/lang/src/test/kotlin/org/inklang/lang/ClassRegistryTest.kt`

- [ ] **Step 1: Write failing test for ClassRegistry**

```kotlin
// ink/lang/src/test/kotlin/org/inklang/lang/ClassRegistryTest.kt
package org.inklang.lang

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ClassRegistryTest {

    @Test
    fun `registerGlobal stores descriptor and getGlobal retrieves it`() {
        ClassRegistry.clear()
        val desc = ClassDescriptor("Test", null, emptyMap())
        ClassRegistry.registerGlobal("test", desc)
        assertEquals(desc, ClassRegistry.getGlobal("test"))
    }

    @Test
    fun `getGlobal returns null for unregistered name`() {
        ClassRegistry.clear()
        assertNull(ClassRegistry.getGlobal("nonexistent"))
    }

    @Test
    fun `hasGlobal returns correct boolean`() {
        ClassRegistry.clear()
        val desc = ClassDescriptor("Test", null, emptyMap())
        ClassRegistry.registerGlobal("test", desc)
        assertTrue(ClassRegistry.hasGlobal("test"))
        assertFalse(ClassRegistry.hasGlobal("other"))
    }

    @Test
    fun `getAllGlobals returns all as Value Instances`() {
        ClassRegistry.clear()
        val desc1 = ClassDescriptor("A", null, emptyMap())
        val desc2 = ClassDescriptor("B", null, emptyMap())
        ClassRegistry.registerGlobal("a", desc1)
        ClassRegistry.registerGlobal("b", desc2)
        val globals = ClassRegistry.getAllGlobals()
        assertEquals(2, globals.size)
        assertTrue(globals["a"] is Value.Instance)
        assertTrue(globals["b"] is Value.Instance)
        assertEquals("A", (globals["a"] as Value.Instance).clazz.name)
    }

    @Test
    fun `registerGlobal overwrites previous registration`() {
        ClassRegistry.clear()
        val desc1 = ClassDescriptor("V1", null, emptyMap())
        val desc2 = ClassDescriptor("V2", null, emptyMap())
        ClassRegistry.registerGlobal("test", desc1)
        ClassRegistry.registerGlobal("test", desc2)
        assertEquals("V2", ClassRegistry.getGlobal("test")!!.name)
    }

    @Test
    fun `clear removes all registrations`() {
        ClassRegistry.clear()
        ClassRegistry.registerGlobal("test", ClassDescriptor("Test", null, emptyMap()))
        ClassRegistry.clear()
        assertFalse(ClassRegistry.hasGlobal("test"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ink:test --tests "org.inklang.lang.ClassRegistryTest" -v`
Expected: FAIL - ClassRegistry not found

- [ ] **Step 3: Write minimal ClassRegistry implementation**

```kotlin
// ink/lang/src/main/kotlin/org/inklang/lang/ClassRegistry.kt
package org.inklang.lang

object ClassRegistry {
    private val globals = mutableMapOf<String, ClassDescriptor>()

    fun registerGlobal(name: String, descriptor: ClassDescriptor) {
        globals[name] = descriptor
    }

    fun getGlobal(name: String): ClassDescriptor? = globals[name]

    fun getAllGlobals(): Map<String, Value> = globals.mapValues { (_, desc) ->
        Value.Instance(desc)
    }

    fun clear() = globals.clear()

    fun hasGlobal(name: String): Boolean = globals.containsKey(name)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ink:test --tests "org.inklang.lang.ClassRegistryTest" -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ink/lang/src/main/kotlin/org/inklang/lang/ClassRegistry.kt
git add ink/lang/src/test/kotlin/org/inklang/lang/ClassRegistryTest.kt
git commit -m "feat: add game-agnostic ClassRegistry for runtime global registration"
```

---

## Chunk 2: Bukkit PlayerClass

**Files:**
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/PlayerClass.kt`
- Create: `ink-bukkit/src/test/kotlin/org/inklang/bukkit/runtime/PlayerClassTest.kt`

- [ ] **Step 1: Write failing test for PlayerClass**

```kotlin
// ink-bukkit/src/test/kotlin/org/inklang/bukkit/runtime/PlayerClassTest.kt
package org.inklang.bukkit.runtime

import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*

class PlayerClassTest {

    @Test
    fun `createPlayerDescriptor returns null-typed player when sender is not Player`() {
        val sender = mock(CommandSender::class.java)
        val server = mock(Server::class.java)
        val desc = PlayerClass.createDescriptor(sender, server)
        assertEquals("Player", desc.name)
        assertTrue(desc.methods.isEmpty())
    }

    @Test
    fun `createDescriptor returns player methods when sender is Player`() {
        val sender = mock(Player::class.java)
        `when`(sender.name).thenReturn("TestPlayer")
        `when`(sender.health).thenReturn(20.0)
        `when`(sender.maxHealth).thenReturn(20.0)
        `when`(sender.foodLevel).thenReturn(20)
        `when`(sender.world).thenReturn(mock(org.bukkit.World::class.java).apply {
            `when`(name).thenReturn("world")
        })
        val server = mock(Server::class.java)
        val desc = PlayerClass.createDescriptor(sender, server)
        assertEquals("Player", desc.name)
        assertTrue(desc.methods.containsKey("name"))
        assertTrue(desc.methods.containsKey("health"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :ink-bukkit:test --tests "org.inklang.bukkit.runtime.PlayerClassTest" -v`
Expected: FAIL - PlayerClass not found

- [ ] **Step 3: Extract createDescriptor to PlayerClass.kt**

```kotlin
// ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/PlayerClass.kt
package org.inklang.bukkit.runtime

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.Server
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value

object PlayerClass {

    fun createDescriptor(sender: CommandSender, server: Server): ClassDescriptor {
        if (sender !is Player) {
            return ClassDescriptor(name = "Player", superClass = null, methods = emptyMap(), readOnly = true)
        }
        return ClassDescriptor(
            name = "Player",
            superClass = null,
            methods = mapOf(
                "name" to Value.NativeFunction { Value.String(sender.name) },
                "display_name" to Value.NativeFunction { Value.String(sender.displayName) },
                "health" to Value.NativeFunction { Value.Double(sender.health.toDouble()) },
                "max_health" to Value.NativeFunction { Value.Double(sender.maxHealth.toDouble()) },
                "food_level" to Value.NativeFunction { Value.Int(sender.foodLevel) },
                "saturation" to Value.NativeFunction { Value.Double(sender.saturation.toDouble()) },
                "exhaustion" to Value.NativeFunction { Value.Double(sender.exhaustion.toDouble()) },
                "level" to Value.NativeFunction { Value.Int(sender.level) },
                "exp" to Value.NativeFunction { Value.Double(sender.exp.toDouble()) },
                "game_mode" to Value.NativeFunction { Value.String(sender.gameMode.name) },
                "is_online" to Value.NativeFunction { Value.Boolean(sender.isOnline) },
                "is_op" to Value.NativeFunction { Value.Boolean(sender.isOp) },
                "is_flying" to Value.NativeFunction { Value.Boolean(sender.isFlying) },
                "is_on_ground" to Value.NativeFunction { Value.Boolean(sender.isOnGround) },
                "is_sneaking" to Value.NativeFunction { Value.Boolean(sender.isSneaking) },
                "is_sprinting" to Value.NativeFunction { Value.Boolean(sender.isSprinting) },
                "world" to Value.NativeFunction { Value.String(sender.world.name) },
                "location" to Value.NativeFunction { LocationClass.createDescriptor(sender.location, server) },
                "send_message" to Value.NativeFunction { args ->
                    val message = args.drop(1).joinToString(" ") { valueToString(it) }
                    sender.sendMessage(message)
                    Value.Null
                },
                "send_action_bar" to Value.NativeFunction { args ->
                    val message = args.getOrNull(1)?.let { valueToString(it) } ?: ""
                    sender.sendActionBar(message)
                    Value.Null
                },
                "kick" to Value.NativeFunction { args ->
                    val reason = args.getOrNull(1)?.let { valueToString(it) } ?: ""
                    sender.kickPlayer(reason)
                    Value.Null
                },
                "has_permission" to Value.NativeFunction { args ->
                    val perm = args.getOrNull(1)?.let { valueToString(it) } ?: ""
                    Value.Boolean(sender.hasPermission(perm))
                },
                "is_permission_set" to Value.NativeFunction { args ->
                    val perm = args.getOrNull(1)?.let { valueToString(it) } ?: ""
                    Value.Boolean(sender.isPermissionSet(perm))
                },
                "inventory" to Value.NativeFunction { InventoryClass.createDescriptor(sender, server) },
                "teleport" to Value.NativeFunction { args ->
                    val x = (args.getOrNull(1) as? Value.Double)?.value
                        ?: (args.getOrNull(1) as? Value.Int)?.value?.toDouble()
                        ?: error("teleport requires x coordinate")
                    val y = (args.getOrNull(2) as? Value.Double)?.value
                        ?: (args.getOrNull(2) as? Value.Int)?.value?.toDouble()
                        ?: error("teleport requires y coordinate")
                    val z = (args.getOrNull(3) as? Value.Double)?.value
                        ?: (args.getOrNull(3) as? Value.Int)?.value?.toDouble()
                        ?: error("teleport requires z coordinate")
                    val worldName = args.getOrNull(4)?.let { valueToString(it) } ?: sender.world.name
                    val targetWorld = server.getWorld(worldName) ?: error("World not found: $worldName")
                    sender.teleport(org.bukkit.Location(targetWorld, x, y, z))
                    Value.Null
                },
                "set_health" to Value.NativeFunction { args ->
                    val health = (args.getOrNull(1) as? Value.Double)?.value
                        ?: (args.getOrNull(1) as? Value.Int)?.value?.toDouble()
                        ?: error("set_health requires a number")
                    sender.health = health.coerceIn(0.0, sender.maxHealth.toDouble())
                    Value.Null
                },
                "set_food_level" to Value.NativeFunction { args ->
                    val food = (args.getOrNull(1) as? Value.Int)?.value
                        ?: error("set_food_level requires an int")
                    sender.foodLevel = food.coerceIn(0, 20)
                    Value.Null
                },
                "set_saturation" to Value.NativeFunction { args ->
                    val sat = (args.getOrNull(1) as? Value.Double)?.value
                        ?: error("set_saturation requires a number")
                    sender.saturation = sat.toFloat().coerceIn(0f, sender.foodLevel.toFloat())
                    Value.Null
                },
                "set_level" to Value.NativeFunction { args ->
                    val level = (args.getOrNull(1) as? Value.Int)?.value
                        ?: error("set_level requires an int")
                    sender.level = level.coerceAtLeast(0)
                    Value.Null
                },
                "set_exp" to Value.NativeFunction { args ->
                    val exp = (args.getOrNull(1) as? Value.Double)?.value
                        ?: error("set_exp requires a number")
                    sender.exp = exp.toFloat().coerceIn(0f, 1f)
                    Value.Null
                },
                "set_game_mode" to Value.NativeFunction { args ->
                    val gmName = args.getOrNull(1)?.let { valueToString(it) }
                        ?: error("set_game_mode requires a string")
                    val gm = org.bukkit.GameMode.valueOf(gmName.uppercase())
                    sender.gameMode = gm
                    Value.Null
                },
                "set_flying" to Value.NativeFunction { args ->
                    val flying = args.getOrNull(1)?.let { it != Value.Boolean.FALSE } ?: true
                    sender.isFlying = flying
                    Value.Null
                },
                "set_allow_flight" to Value.NativeFunction { args ->
                    val allowed = args.getOrNull(1)?.let { it != Value.Boolean.FALSE } ?: true
                    sender.allowFlight = allowed
                    Value.Null
                }
            )
        )
    }

    internal fun valueToString(value: Value?): String = when (value) {
        is Value.String -> value.value
        is Value.Int -> value.value.toString()
        is Value.Double -> value.value.toString()
        is Value.Float -> value.value.toString()
        is Value.Boolean -> value.value.toString()
        is Value.Null -> "null"
        else -> value.toString()
    }
}
```

- [ ] **Step 3b: Create stub files for dependencies (LocationClass, InventoryClass) - compile will fail without these**

```kotlin
// ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/LocationClass.kt
package org.inklang.bukkit.runtime

import org.bukkit.Location
import org.bukkit.Server
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value

object LocationClass {
    fun createDescriptor(location: Location, server: Server): ClassDescriptor {
        return ClassDescriptor(
            name = "Location",
            superClass = null,
            methods = mapOf(
                "x" to Value.NativeFunction { Value.Double(location.x) },
                "y" to Value.NativeFunction { Value.Double(location.y) },
                "z" to Value.NativeFunction { Value.Double(location.z) },
                "yaw" to Value.NativeFunction { Value.Double(location.yaw.toDouble()) },
                "pitch" to Value.NativeFunction { Value.Double(location.pitch.toDouble()) },
                "block_x" to Value.NativeFunction { Value.Int(location.blockX) },
                "block_y" to Value.NativeFunction { Value.Int(location.blockY) },
                "block_z" to Value.NativeFunction { Value.Int(location.blockZ) }
            )
        )
    }
}
```

```kotlin
// ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/InventoryClass.kt
package org.inklang.bukkit.runtime

import org.bukkit.entity.Player
import org.bukkit.Server
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value
import org.inklang.lang.Builtins

object InventoryClass {
    fun createDescriptor(player: Player, server: Server): ClassDescriptor {
        return ClassDescriptor(
            name = "Inventory",
            superClass = null,
            methods = mapOf(
                "title" to Value.NativeFunction { Value.String(player.openInventory.title) },
                "close" to Value.NativeFunction { args ->
                    player.closeInventory()
                    Value.Null
                }
            )
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ink-bukkit:test --tests "org.inklang.bukkit.runtime.PlayerClassTest" -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/PlayerClass.kt
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/LocationClass.kt
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/InventoryClass.kt
git add ink-bukkit/src/test/kotlin/org/inklang/bukkit/runtime/PlayerClassTest.kt
git commit -m "feat: extract PlayerClass and dependencies from PaperGlobals"
```

---

## Chunk 3: ServerClass and WorldClass

**Files:**
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/ServerClass.kt`
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/WorldClass.kt`

- [ ] **Step 1: Create ServerClass.kt**

```kotlin
// ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/ServerClass.kt
package org.inklang.bukkit.runtime

import org.bukkit.Server
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value
import org.inklang.lang.Builtins

object ServerClass {

    fun createDescriptor(server: Server): ClassDescriptor {
        return ClassDescriptor(
            name = "Server",
            superClass = null,
            methods = mapOf(
                "name" to Value.NativeFunction { Value.String(server.name) },
                "minecraft_version" to Value.NativeFunction { Value.String(server.minecraftVersion) },
                "world_type" to Value.NativeFunction { Value.String(server.worldType) },
                "default_world_name" to Value.NativeFunction { Value.String(server.worlds[0].name) },
                "online_players" to Value.NativeFunction { args ->
                    Value.Instance(
                        ClassDescriptor(
                            name = "PlayerList",
                            superClass = null,
                            methods = mapOf(
                                "size" to Value.NativeFunction { Value.Int(server.onlinePlayers.size) },
                                "names" to Value.NativeFunction {
                                    Builtins.newArray(server.onlinePlayers.map { Value.String(it.name) }.toMutableList())
                                },
                                "get" to Value.NativeFunction { args ->
                                    val name = args.getOrNull(1)?.let { PlayerClass.valueToString(it) }
                                        ?: error("get requires a player name")
                                    val player = server.getPlayer(name)
                                    if (player != null) Value.Instance(PlayerClass.createDescriptor(player, server))
                                    else Value.Null
                                },
                                "find" to Value.NativeFunction { args ->
                                    val pattern = args.getOrNull(1)?.let { PlayerClass.valueToString(it) } ?: ""
                                    val matches = server.onlinePlayers.filter {
                                        it.name.contains(pattern, ignoreCase = true)
                                    }
                                    Builtins.newArray(matches.map { Value.Instance(PlayerClass.createDescriptor(it, server)) }.toMutableList())
                                }
                            )
                        )
                    )
                },
                "player_count" to Value.NativeFunction { Value.Int(server.onlinePlayers.size) },
                "max_players" to Value.NativeFunction { Value.Int(server.maxPlayers) },
                "worlds" to Value.NativeFunction { args ->
                    Value.Instance(
                        ClassDescriptor(
                            name = "WorldList",
                            superClass = null,
                            methods = mapOf(
                                "size" to Value.NativeFunction { Value.Int(server.worlds.size) },
                                "names" to Value.NativeFunction {
                                    Builtins.newArray(server.worlds.map { Value.String(it.name) }.toMutableList())
                                },
                                "get" to Value.NativeFunction { args ->
                                    val name = args.getOrNull(1)?.let { PlayerClass.valueToString(it) }
                                        ?: error("get requires a world name")
                                    val world = server.getWorld(name)
                                    if (world != null) Value.Instance(WorldClass.createDescriptor(world, server))
                                    else Value.Null
                                }
                            )
                        )
                    )
                },
                "get_world" to Value.NativeFunction { args ->
                    val name = args.getOrNull(1)?.let { PlayerClass.valueToString(it) }
                        ?: error("get_world requires a world name")
                    val world = server.getWorld(name)
                    if (world != null) Value.Instance(WorldClass.createDescriptor(world, server))
                    else Value.Null
                },
                "broadcast" to Value.NativeFunction { args ->
                    val message = args.drop(1).joinToString(" ") { PlayerClass.valueToString(it) }
                    server.onlinePlayers.forEach { it.sendMessage(message) }
                    Value.Int(server.onlinePlayers.size)
                },
                "reload" to Value.NativeFunction { args ->
                    server.reload()
                    Value.Null
                },
                "shutdown" to Value.NativeFunction { args ->
                    server.shutdown()
                    Value.Null
                },
                "plugin_manager" to Value.NativeFunction { args ->
                    Value.Instance(
                        ClassDescriptor(
                            name = "PluginManager",
                            superClass = null,
                            methods = mapOf(
                                "get_plugin" to Value.NativeFunction { args ->
                                    val name = args.getOrNull(1)?.let { PlayerClass.valueToString(it) }
                                        ?: error("get_plugin requires a plugin name")
                                    val plugin = server.pluginManager.getPlugin(name)
                                    if (plugin != null) Value.String(plugin.name) else Value.Null
                                },
                                "is_plugin_enabled" to Value.NativeFunction { args ->
                                    val name = args.getOrNull(1)?.let { PlayerClass.valueToString(it) }
                                        ?: error("is_plugin_enabled requires a plugin name")
                                    val plugin = server.pluginManager.getPlugin(name)
                                    Value.Boolean(plugin != null && plugin.isEnabled)
                                }
                            )
                        )
                    )
                },
                "scheduler" to Value.NativeFunction { args ->
                    Value.Instance(
                        ClassDescriptor(
                            name = "Scheduler",
                            superClass = null,
                            methods = mapOf(
                                "run_task" to Value.NativeFunction { Value.Int(-1) },
                                "run_task_async" to Value.NativeFunction { Value.Int(-1) },
                                "cancel_task" to Value.NativeFunction { Value.Null },
                                "cancel_all_tasks" to Value.NativeFunction { Value.Null }
                            )
                        )
                    )
                }
            )
        )
    }
}
```

- [ ] **Step 2: Create WorldClass.kt**

```kotlin
// ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/WorldClass.kt
package org.inklang.bukkit.runtime

import org.bukkit.World
import org.bukkit.Server
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value
import org.inklang.lang.Builtins

object WorldClass {

    fun createDescriptor(world: World, server: Server): ClassDescriptor {
        return ClassDescriptor(
            name = "World",
            superClass = null,
            methods = mapOf(
                "name" to Value.NativeFunction { Value.String(world.name) },
                "environment" to Value.NativeFunction { Value.String(world.environment.name) },
                "seed" to Value.NativeFunction { Value.Int(world.seed.toInt()) },
                "full_time" to Value.NativeFunction { Value.Int(world.fullTime.toInt()) },
                "time" to Value.NativeFunction { Value.Int(world.time.toInt()) },
                "weather_status" to Value.NativeFunction { Value.String(if (world.hasStorm()) "storm" else "clear") },
                "players" to Value.NativeFunction {
                    Builtins.newArray(world.players.map { Value.Instance(PlayerClass.createDescriptor(it, server)) }.toMutableList())
                },
                "player_count" to Value.NativeFunction { Value.Int(world.players.size) },
                "entity_count" to Value.NativeFunction { Value.Int(world.entityCount) },
                "set_time" to Value.NativeFunction { args ->
                    val time = (args.getOrNull(1) as? Value.Int)?.value ?: error("set_time requires an int")
                    world.time = time.toLong()
                    Value.Null
                },
                "set_storm" to Value.NativeFunction { args ->
                    val storm = args.getOrNull(1)?.let { it != Value.Boolean.FALSE } ?: true
                    world.setStorm(storm)
                    Value.Null
                },
                "set_thundering" to Value.NativeFunction { args ->
                    val thunder = args.getOrNull(1)?.let { it != Value.Boolean.FALSE } ?: true
                    world.setThundering(thunder)
                    Value.Null
                },
                "get_block" to Value.NativeFunction { args ->
                    val x = (args.getOrNull(1) as? Value.Int)?.value ?: error("get_block requires x")
                    val y = (args.getOrNull(2) as? Value.Int)?.value ?: error("get_block requires y")
                    val z = (args.getOrNull(3) as? Value.Int)?.value ?: error("get_block requires z")
                    val block = world.getBlockAt(x, y, z)
                    Value.Instance(
                        ClassDescriptor(
                            name = "Block",
                            superClass = null,
                            methods = mapOf(
                                "type" to Value.NativeFunction { Value.String(block.type.name) },
                                "type_id" to Value.NativeFunction { Value.Int(block.type.id) },
                                "data" to Value.NativeFunction { Value.Int(block.data.toInt() and 0xFF) },
                                "x" to Value.NativeFunction { Value.Int(block.x) },
                                "y" to Value.NativeFunction { Value.Int(block.y) },
                                "z" to Value.NativeFunction { Value.Int(block.z) }
                            )
                        )
                    )
                }
            )
        )
    }
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :ink-bukkit:build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/ServerClass.kt
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/WorldClass.kt
git commit -m "feat: add ServerClass and WorldClass from PaperGlobals extraction"
```

---

## Chunk 4: BukkitRuntimeRegistrar and Wiring

**Files:**
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/BukkitRuntimeRegistrar.kt`
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/BukkitContext.kt`
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PaperGlobals.kt` (deprecate, delegate)

- [ ] **Step 1: Create BukkitRuntimeRegistrar**

```kotlin
// ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/BukkitRuntimeRegistrar.kt
package org.inklang.bukkit.runtime

import org.bukkit.command.CommandSender
import org.bukkit.Server
import org.inklang.lang.ClassRegistry

object BukkitRuntimeRegistrar {

    fun register(sender: CommandSender, server: Server) {
        ClassRegistry.registerGlobal("player", PlayerClass.createDescriptor(sender, server))
        ClassRegistry.registerGlobal("server", ServerClass.createDescriptor(server))
        if (server.worlds.isNotEmpty()) {
            ClassRegistry.registerGlobal("world", WorldClass.createDescriptor(server.worlds[0], server))
        }
    }

    fun registerPlayer(sender: CommandSender, server: Server) {
        ClassRegistry.registerGlobal("player", PlayerClass.createDescriptor(sender, server))
    }

    fun registerServer(server: Server) {
        ClassRegistry.registerGlobal("server", ServerClass.createDescriptor(server))
    }

    fun registerDefaultWorld(server: Server) {
        if (server.worlds.isNotEmpty()) {
            ClassRegistry.registerGlobal("world", WorldClass.createDescriptor(server.worlds[0], server))
        }
    }
}
```

- [ ] **Step 2: Modify BukkitContext to wire registrar**

```kotlin
// ink-bukkit/src/main/kotlin/org/inklang/bukkit/BukkitContext.kt
// Add import and update setupGlobals:
// import org.inklang.bukkit.runtime.BukkitRuntimeRegistrar

// In the function that sets up globals (look for PaperGlobals.getGlobals call):
val globals = ClassRegistry.getAllGlobals()
// or if using registrar:
BukkitRuntimeRegistrar.register(sender, server)
val globals = ClassRegistry.getAllGlobals()
```

- [ ] **Step 3: Deprecate PaperGlobals**

```kotlin
// ink-bukkit/src/main/kotlin/org/inklang/bukkit/PaperGlobals.kt

@Deprecated("Use BukkitRuntimeRegistrar and ClassRegistry.getAllGlobals() instead", ReplaceWith("BukkitRuntimeRegistrar.register(sender, server); ClassRegistry.getAllGlobals()"))
object PaperGlobals {
    // ... keep existing code unchanged for backwards compatibility during transition
}
```

- [ ] **Step 4: Build to verify wiring**

Run: `./gradlew :ink-bukkit:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/runtime/BukkitRuntimeRegistrar.kt
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/BukkitContext.kt
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/PaperGlobals.kt
git commit -m "feat: add BukkitRuntimeRegistrar and wire ClassRegistry"
```

---

## Chunk 5: Integration Test

**Files:**
- Create: `ink-bukkit/src/test/kotlin/org/inklang/bukkit/runtime/BukkitRuntimeRegistrarTest.kt`

- [ ] **Step 1: Write integration test**

```kotlin
// ink-bukkit/src/test/kotlin/org/inklang/bukkit/runtime/BukkitRuntimeRegistrarTest.kt
package org.inklang.bukkit.runtime

import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*
import org.inklang.lang.ClassRegistry

class BukkitRuntimeRegistrarTest {

    @Test
    fun `register creates player server and world globals`() {
        ClassRegistry.clear()
        val sender = mock(Player::class.java)
        `when`(sender.name).thenReturn("Player1")
        `when`(sender.health).thenReturn(20.0)
        `when`(sender.maxHealth).thenReturn(20.0)
        `when`(sender.foodLevel).thenReturn(20)
        val world = mock(org.bukkit.World::class.java)
        `when`(world.name).thenReturn("world")
        `when`(sender.world).thenReturn(world)
        val server = mock(Server::class.java)
        `when`(server.worlds).thenReturn(listOf(world))
        `when`(server.onlinePlayers).thenReturn(emptyList())
        `when`(server.name).thenReturn("TestServer")
        `when`(server.minecraftVersion).thenReturn("1.21.4")
        `when`(server.worldType).thenReturn("DEFAULT")
        `when`(server.maxPlayers).thenReturn(20)

        BukkitRuntimeRegistrar.register(sender, server)

        assertTrue(ClassRegistry.hasGlobal("player"))
        assertTrue(ClassRegistry.hasGlobal("server"))
        assertTrue(ClassRegistry.hasGlobal("world"))

        val globals = ClassRegistry.getAllGlobals()
        assertEquals(3, globals.size)
    }

    @Test
    fun `register is idempotent`() {
        ClassRegistry.clear()
        val sender = mock(Player::class.java)
        `when`(sender.name).thenReturn("Player1")
        `when`(sender.health).thenReturn(20.0)
        `when`(sender.maxHealth).thenReturn(20.0)
        `when`(sender.foodLevel).thenReturn(20)
        val world = mock(org.bukkit.World::class.java)
        `when`(world.name).thenReturn("world")
        `when`(sender.world).thenReturn(world)
        val server = mock(Server::class.java)
        `when`(server.worlds).thenReturn(listOf(world))
        `when`(server.onlinePlayers).thenReturn(emptyList())
        `when`(server.name).thenReturn("TestServer")
        `when`(server.minecraftVersion).thenReturn("1.21.4")
        `when`(server.worldType).thenReturn("DEFAULT")
        `when`(server.maxPlayers).thenReturn(20)

        BukkitRuntimeRegistrar.register(sender, server)
        BukkitRuntimeRegistrar.register(sender, server)

        assertEquals(3, ClassRegistry.getAllGlobals().size)
    }
}
```

- [ ] **Step 2: Run integration test**

Run: `./gradlew :ink-bukkit:test --tests "org.inklang.bukkit.runtime.BukkitRuntimeRegistrarTest" -v`
Expected: PASS

- [ ] **Step 3: Run full test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 4: Commit**

```bash
git add ink-bukkit/src/test/kotlin/org/inklang/bukkit/runtime/BukkitRuntimeRegistrarTest.kt
git commit -m "test: add integration test for BukkitRuntimeRegistrar"
```

---

## Verification

Run all tests to confirm:
```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

Confirm PaperGlobals deprecation warning is visible during compilation of ink-bukkit.
