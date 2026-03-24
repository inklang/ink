package org.inklang.bukkit.runtime

import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value

object EntityClass {

    fun wrap(entity: Entity, server: Server): Value.Instance {
        val methods = mutableMapOf<String, Value>(
            "name" to Value.NativeFunction { Value.String(entity.type.name) },
            "uuid" to Value.NativeFunction { Value.String(entity.uniqueId.toString()) },
            "type" to Value.NativeFunction { Value.String(entity.type.name) },
            "location" to Value.NativeFunction {
                Value.Instance(LocationClass.createDescriptor(entity.location, server))
            },
            "world" to Value.NativeFunction {
                Value.Instance(WorldClass.createDescriptor(entity.world, server))
            },
            "remove" to Value.NativeFunction {
                entity.remove()
                Value.Null
            },
            "teleport" to Value.NativeFunction { args ->
                val x = toDouble(args.getOrNull(1)) ?: error("teleport requires x")
                val y = toDouble(args.getOrNull(2)) ?: error("teleport requires y")
                val z = toDouble(args.getOrNull(3)) ?: error("teleport requires z")
                entity.teleport(org.bukkit.Location(entity.world, x, y, z))
                Value.Null
            }
        )

        if (entity is LivingEntity) {
            methods["health"] = Value.NativeFunction { Value.Double(entity.health) }
            methods["max_health"] = Value.NativeFunction {
                Value.Double(entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0)
            }
            methods["kill"] = Value.NativeFunction {
                entity.health = 0.0
                Value.Null
            }
            methods["set_health"] = Value.NativeFunction { args ->
                val h = toDouble(args.getOrNull(1)) ?: error("set_health requires a number")
                val max = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                entity.health = h.coerceIn(0.0, max)
                Value.Null
            }
        }

        return Value.Instance(ClassDescriptor("Entity", null, methods))
    }

    private fun toDouble(v: Value?): Double? = when (v) {
        is Value.Double -> v.value
        is Value.Float -> v.value.toDouble()
        is Value.Int -> v.value.toDouble()
        else -> null
    }
}
