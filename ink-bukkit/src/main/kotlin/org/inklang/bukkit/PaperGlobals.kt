package org.inklang.bukkit

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.Server
import org.inklang.lang.Value
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Builtins

/**
 * Creates Paper/Bukkit global objects exposed to Ink scripts.
 * These are passed to the VM at execution time via setGlobals().
 */
@Deprecated("Use BukkitRuntimeRegistrar and ClassRegistry.getAllGlobals() instead", ReplaceWith("BukkitRuntimeRegistrar.register(sender, server); ClassRegistry.getAllGlobals()"))
object PaperGlobals {

    /**
     * Get the globals map for a given command sender and server.
     */
    fun getGlobals(sender: CommandSender, server: Server): Map<String, Value> {
        return mapOf(
            "player" to createPlayerInstance(sender, server),
            "server" to createServerInstance(server)
        )
    }

    private fun createPlayerInstance(sender: CommandSender, server: Server): Value {
        // If sender is not a Player, player is null
        if (sender !is Player) {
            return Value.Null
        }

        return Value.Instance(
            ClassDescriptor(
                name = "Player",
                superClass = null,
                methods = mapOf(
                    // Basic info
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
                    // Location
                    "world" to Value.NativeFunction { Value.String(sender.world.name) },
                    "location" to Value.NativeFunction { args ->
                        Value.Instance(
                            ClassDescriptor(
                                name = "Location",
                                superClass = null,
                                methods = mapOf(
                                    "x" to Value.NativeFunction { Value.Double(sender.location.x) },
                                    "y" to Value.NativeFunction { Value.Double(sender.location.y) },
                                    "z" to Value.NativeFunction { Value.Double(sender.location.z) },
                                    "yaw" to Value.NativeFunction { Value.Double(sender.location.yaw.toDouble()) },
                                    "pitch" to Value.NativeFunction { Value.Double(sender.location.pitch.toDouble()) },
                                    "block_x" to Value.NativeFunction { Value.Int(sender.location.blockX) },
                                    "block_y" to Value.NativeFunction { Value.Int(sender.location.blockY) },
                                    "block_z" to Value.NativeFunction { Value.Int(sender.location.blockZ) }
                                )
                            )
                        )
                    },
                    // Actions
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
                    // Permissions
                    "has_permission" to Value.NativeFunction { args ->
                        val perm = args.getOrNull(1)?.let { valueToString(it) } ?: ""
                        Value.Boolean(sender.hasPermission(perm))
                    },
                    "is_permission_set" to Value.NativeFunction { args ->
                        val perm = args.getOrNull(1)?.let { valueToString(it) } ?: ""
                        Value.Boolean(sender.isPermissionSet(perm))
                    },
                    // Inventory
                    "inventory" to Value.NativeFunction { args ->
                        Value.Instance(
                            ClassDescriptor(
                                name = "Inventory",
                                superClass = null,
                                methods = mapOf(
                                    "title" to Value.NativeFunction { Value.String(sender.openInventory.title) },
                                    "close" to Value.NativeFunction { args ->
                                        sender.closeInventory()
                                        Value.Null
                                    }
                                )
                            )
                        )
                    },
                    // Teleport
                    "teleport" to Value.NativeFunction { args ->
                        val x = (args.getOrNull(1) as? Value.Double)?.value ?: (args.getOrNull(1) as? Value.Int)?.value?.toDouble()
                            ?: error("teleport requires x coordinate")
                        val y = (args.getOrNull(2) as? Value.Double)?.value ?: (args.getOrNull(2) as? Value.Int)?.value?.toDouble()
                            ?: error("teleport requires y coordinate")
                        val z = (args.getOrNull(3) as? Value.Double)?.value ?: (args.getOrNull(3) as? Value.Int)?.value?.toDouble()
                            ?: error("teleport requires z coordinate")
                        val worldName = args.getOrNull(4)?.let { valueToString(it) } ?: sender.world.name
                        val targetWorld = server.getWorld(worldName) ?: error("World not found: $worldName")
                        sender.teleport(org.bukkit.Location(targetWorld, x, y, z))
                        Value.Null
                    },
                    // Set player state
                    "set_health" to Value.NativeFunction { args ->
                        val health = (args.getOrNull(1) as? Value.Double)?.value ?: (args.getOrNull(1) as? Value.Int)?.value?.toDouble()
                            ?: error("set_health requires a number")
                        sender.health = health.coerceIn(0.0, sender.maxHealth.toDouble())
                        Value.Null
                    },
                    "set_food_level" to Value.NativeFunction { args ->
                        val food = (args.getOrNull(1) as? Value.Int)?.value ?: error("set_food_level requires an int")
                        sender.foodLevel = food.coerceIn(0, 20)
                        Value.Null
                    },
                    "set_saturation" to Value.NativeFunction { args ->
                        val sat = (args.getOrNull(1) as? Value.Double)?.value ?: error("set_saturation requires a number")
                        sender.saturation = sat.toFloat().coerceIn(0f, sender.foodLevel.toFloat())
                        Value.Null
                    },
                    "set_level" to Value.NativeFunction { args ->
                        val level = (args.getOrNull(1) as? Value.Int)?.value ?: error("set_level requires an int")
                        sender.level = level.coerceAtLeast(0)
                        Value.Null
                    },
                    "set_exp" to Value.NativeFunction { args ->
                        val exp = (args.getOrNull(1) as? Value.Double)?.value ?: error("set_exp requires a number")
                        sender.exp = exp.toFloat().coerceIn(0f, 1f)
                        Value.Null
                    },
                    "set_game_mode" to Value.NativeFunction { args ->
                        val gmName = args.getOrNull(1)?.let { valueToString(it) } ?: error("set_game_mode requires a string")
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
        )
    }

    private fun createServerInstance(server: Server): Value {
        return Value.Instance(
            ClassDescriptor(
                name = "Server",
                superClass = null,
                methods = mapOf(
                    "name" to Value.NativeFunction { Value.String(server.name) },
                    "minecraft_version" to Value.NativeFunction { Value.String(server.minecraftVersion) },
                    "world_type" to Value.NativeFunction { Value.String(server.worldType) },
                    "default_world_name" to Value.NativeFunction { Value.String(server.worlds[0].name) },

                    // Online players
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
                                        val name = args.getOrNull(1)?.let { valueToString(it) }
                                            ?: error("get requires a player name")
                                        val player = server.getPlayer(name)
                                        if (player != null) createPlayerInstance(player, server) else Value.Null
                                    },
                                    "find" to Value.NativeFunction { args ->
                                        val pattern = args.getOrNull(1)?.let { valueToString(it) } ?: ""
                                        val matches = server.onlinePlayers.filter {
                                            it.name.contains(pattern, ignoreCase = true)
                                        }
                                        Builtins.newArray(matches.map { createPlayerInstance(it, server) }.toMutableList())
                                    }
                                )
                            )
                        )
                    },
                    "player_count" to Value.NativeFunction { Value.Int(server.onlinePlayers.size) },
                    "max_players" to Value.NativeFunction { Value.Int(server.maxPlayers) },

                    // Worlds
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
                                        val name = args.getOrNull(1)?.let { valueToString(it) }
                                            ?: error("get requires a world name")
                                        val world = server.getWorld(name)
                                        if (world != null) createWorldInstance(world, server) else Value.Null
                                    }
                                )
                            )
                        )
                    },
                    "get_world" to Value.NativeFunction { args ->
                        val name = args.getOrNull(1)?.let { valueToString(it) }
                            ?: error("get_world requires a world name")
                        val world = server.getWorld(name)
                        if (world != null) createWorldInstance(world, server) else Value.Null
                    },

                    // Broadcast
                    "broadcast" to Value.NativeFunction { args ->
                        val message = args.drop(1).joinToString(" ") { valueToString(it) }
                        server.onlinePlayers.forEach { it.sendMessage(message) }
                        Value.Int(server.onlinePlayers.size)
                    },

                    // Server operations
                    "reload" to Value.NativeFunction { args ->
                        server.reload()
                        Value.Null
                    },
                    "shutdown" to Value.NativeFunction { args ->
                        server.shutdown()
                        Value.Null
                    },

                    // Plugin manager
                    "plugin_manager" to Value.NativeFunction { args ->
                        Value.Instance(
                            ClassDescriptor(
                                name = "PluginManager",
                                superClass = null,
                                methods = mapOf(
                                    "get_plugin" to Value.NativeFunction { args ->
                                        val name = args.getOrNull(1)?.let { valueToString(it) }
                                            ?: error("get_plugin requires a plugin name")
                                        val plugin = server.pluginManager.getPlugin(name)
                                        if (plugin != null) Value.String(plugin.name) else Value.Null
                                    },
                                    "is_plugin_enabled" to Value.NativeFunction { args ->
                                        val name = args.getOrNull(1)?.let { valueToString(it) }
                                            ?: error("is_plugin_enabled requires a plugin name")
                                        val plugin = server.pluginManager.getPlugin(name)
                                        Value.Boolean(plugin != null && plugin.isEnabled)
                                    }
                                )
                            )
                        )
                    },

                    // Scheduler - simplified, just returns a task ID placeholder
                    "scheduler" to Value.NativeFunction { args ->
                        Value.Instance(
                            ClassDescriptor(
                                name = "Scheduler",
                                superClass = null,
                                methods = mapOf(
                                    "run_task" to Value.NativeFunction { args ->
                                        // For now, scheduler just returns a placeholder ID
                                        // Full async task support requires closure integration
                                        Value.Int(-1)
                                    },
                                    "run_task_async" to Value.NativeFunction { args ->
                                        Value.Int(-1)
                                    },
                                    "cancel_task" to Value.NativeFunction { args ->
                                        Value.Null
                                    },
                                    "cancel_all_tasks" to Value.NativeFunction { args ->
                                        Value.Null
                                    }
                                )
                            )
                        )
                    }
                )
            )
        )
    }

    private fun createWorldInstance(world: org.bukkit.World, server: Server): Value {
        return Value.Instance(
            ClassDescriptor(
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
                        Builtins.newArray(world.players.map { createPlayerInstance(it, server) }.toMutableList())
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
        )
    }

    private fun valueToString(value: Value?): String = when (value) {
        is Value.String -> value.value
        is Value.Int -> value.value.toString()
        is Value.Double -> value.value.toString()
        is Value.Float -> value.value.toString()
        is Value.Boolean -> value.value.toString()
        is Value.Null -> "null"
        else -> value.toString()
    }
}
