package org.inklang.bukkit.runtime

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.Server
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value

object PlayerClass {
    /**
     * Creates a ClassDescriptor for a Player if the sender is a Player.
     * If sender is NOT a Player, returns a ClassDescriptor with no methods.
     */
    fun createDescriptor(sender: CommandSender, server: Server): ClassDescriptor {
        // If sender is not a Player, return empty methods
        if (sender !is Player) {
            return ClassDescriptor(
                name = "Player",
                superClass = null,
                methods = emptyMap()
            )
        }

        return ClassDescriptor(
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
                    Value.Instance(LocationClass.createDescriptor(sender.location, server))
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
                    Value.Instance(InventoryClass.createDescriptor(sender, server))
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