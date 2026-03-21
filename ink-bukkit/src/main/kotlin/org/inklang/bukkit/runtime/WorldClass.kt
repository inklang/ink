package org.inklang.bukkit.runtime

import org.bukkit.Server
import org.bukkit.World
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value
import org.inklang.lang.Builtins

object WorldClass {
    /**
     * Creates a ClassDescriptor for a World.
     */
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
