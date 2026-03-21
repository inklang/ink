package org.inklang.bukkit.runtime

import org.bukkit.Server
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value
import org.inklang.lang.Builtins

object ServerClass {
    /**
     * Creates a ClassDescriptor for the Server.
     */
    fun createDescriptor(server: Server): ClassDescriptor {
        return ClassDescriptor(
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
                                    if (player != null) Value.Instance(PlayerClass.createDescriptor(player, server)) else Value.Null
                                },
                                "find" to Value.NativeFunction { args ->
                                    val pattern = args.getOrNull(1)?.let { valueToString(it) } ?: ""
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
                                    if (world != null) Value.Instance(WorldClass.createDescriptor(world, server)) else Value.Null
                                }
                            )
                        )
                    )
                },
                "get_world" to Value.NativeFunction { args ->
                    val name = args.getOrNull(1)?.let { valueToString(it) }
                        ?: error("get_world requires a world name")
                    val world = server.getWorld(name)
                    if (world != null) Value.Instance(WorldClass.createDescriptor(world, server)) else Value.Null
                },

                // Broadcast
                "broadcast" to Value.NativeFunction { args ->
                    val message = args.drop(1).joinToString(" ") { valueToString(it) }
                    server.onlinePlayers.forEach { it.sendMessage(message) }
                    Value.Int(server.onlinePlayers.size)
                },

                // Server operations
                "reload" to Value.NativeFunction { _ ->
                    server.reload()
                    Value.Null
                },
                "shutdown" to Value.NativeFunction { _ ->
                    server.shutdown()
                    Value.Null
                },

                // Plugin manager
                "plugin_manager" to Value.NativeFunction { _ ->
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
                "scheduler" to Value.NativeFunction { _ ->
                    Value.Instance(
                        ClassDescriptor(
                            name = "Scheduler",
                            superClass = null,
                            methods = mapOf(
                                "run_task" to Value.NativeFunction { _ ->
                                    // For now, scheduler just returns a placeholder ID
                                    // Full async task support requires closure integration
                                    Value.Int(-1)
                                },
                                "run_task_async" to Value.NativeFunction { _ ->
                                    Value.Int(-1)
                                },
                                "cancel_task" to Value.NativeFunction { _ ->
                                    Value.Null
                                },
                                "cancel_all_tasks" to Value.NativeFunction { _ ->
                                    Value.Null
                                }
                            )
                        )
                    )
                }
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
