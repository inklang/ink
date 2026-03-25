package org.inklang.bukkit.handlers

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import org.inklang.lang.Value

/**
 * Built-in skill functions for mob event handlers.
 * Skills operate on Ink Value instances representing entities.
 */
object MobSkills {

    /**
     * particle_effect(entity, "EXPLOSION_NORMAL", 0.5, 0.5, 0.5, 0.01, 20)
     */
    fun particleEffect(entity: Value.Instance, effect: String, offsetX: Double, offsetY: Double, offsetZ: Double, speed: Double, count: Int): Value.Null {
        // Skills need server access to spawn particles - return Null for now
        // In a full implementation, this would be wired through the plugin context
        return Value.Null
    }

    /**
     * sound(entity, "ENTITY_CREEPER_HISS", 1.0, 1.0)
     */
    fun sound(entity: Value.Instance, sound: String, volume: Double, pitch: Double): Value.Null {
        return Value.Null
    }

    /**
     * explosion(entity, 2.0, false, true)
     */
    fun explosion(entity: Value.Instance, radius: Float, setFire: Boolean, breakBlocks: Boolean): Value.Null {
        return Value.Null
    }

    /**
     * damage(entity, 5.0)
     */
    fun damage(entity: Value.Instance, amount: Double): Value.Null {
        return Value.Null
    }

    /**
     * heal(entity, 10.0)
     */
    fun heal(entity: Value.Instance, amount: Double): Value.Null {
        val health = entity.fields["health"] as? Value.Double
        if (health != null) {
            val currentHealth = health.value
            val maxHealth = (entity.fields["maxHealth"] as? Value.Double)?.value ?: 20.0
            val newHealth = minOf(currentHealth + amount, maxHealth)
            entity.fields["health"] = Value.Double(newHealth)
        }
        return Value.Null
    }

    /**
     * speed_boost(entity, 2.0, 60)
     */
    fun speedBoost(entity: Value.Instance, amplifier: Double, durationTicks: Int): Value.Null {
        return Value.Null
    }

    /**
     * jump_boost(entity, 2.0, 60)
     */
    fun jumpBoost(entity: Value.Instance, amplifier: Double, durationTicks: Int): Value.Null {
        return Value.Null
    }

    /**
     * teleport(entity, x, y, z)
     */
    fun teleport(entity: Value.Instance, x: Double, y: Double, z: Double): Value.Null {
        val world = (entity.fields["world"] as? Value.String)?.value ?: return Value.Null
        entity.fields["x"] = Value.Double(x)
        entity.fields["y"] = Value.Double(y)
        entity.fields["z"] = Value.Double(z)
        return Value.Null
    }

    /**
     * summon(entity, "ZOMBIE", x, y, z) - returns new entity instance
     */
    fun summon(entity: Value.Instance, mobType: String, x: Double, y: Double, z: Double): Value.Instance {
        // Returns a new entity instance - actual spawning needs server access
        return Value.Instance(
            org.inklang.lang.ClassDescriptor("Entity", null, emptyMap()),
            mutableMapOf(
                "type" to Value.String(mobType),
                "x" to Value.Double(x),
                "y" to Value.Double(y),
                "z" to Value.Double(z),
                "world" to (entity.fields["world"] ?: Value.String("")),
                "health" to Value.Double(20.0),
                "maxHealth" to Value.Double(20.0)
            )
        )
    }

    /**
     * remove_entity(entity)
     */
    fun removeEntity(entity: Value.Instance): Value.Null {
        entity.fields["removed"] = Value.Boolean.TRUE
        return Value.Null
    }

    /**
     * set_velocity(entity, x, y, z)
     */
    fun setVelocity(entity: Value.Instance, x: Double, y: Double, z: Double): Value.Null {
        entity.fields["velocity_x"] = Value.Double(x)
        entity.fields["velocity_y"] = Value.Double(y)
        entity.fields["velocity_z"] = Value.Double(z)
        return Value.Null
    }

    /**
     * ignite(entity, seconds)
     */
    fun ignite(entity: Value.Instance, seconds: Int): Value.Null {
        entity.fields["fire_ticks"] = Value.Int(seconds * 20)
        return Value.Null
    }

    /**
     * extinguish(entity)
     */
    fun extinguish(entity: Value.Instance): Value.Null {
        entity.fields["fire_ticks"] = Value.Int(0)
        return Value.Null
    }

    /**
     * set_health(entity, health)
     */
    fun setHealth(entity: Value.Instance, health: Double): Value.Null {
        val maxHealth = (entity.fields["maxHealth"] as? Value.Double)?.value ?: 20.0
        entity.fields["health"] = Value.Double(health.coerceIn(0.0, maxHealth))
        return Value.Null
    }

    /**
     * set_max_health(entity, maxHealth)
     */
    fun setMaxHealth(entity: Value.Instance, maxHealth: Double): Value.Null {
        val clamped = maxHealth.coerceAtLeast(1.0)
        entity.fields["maxHealth"] = Value.Double(clamped)
        // Also cap current health if needed
        val current = (entity.fields["health"] as? Value.Double)?.value ?: clamped
        if (current > clamped) {
            entity.fields["health"] = Value.Double(clamped)
        }
        return Value.Null
    }

    /**
     * add_tag(entity, "tag")
     */
    fun addTag(entity: Value.Instance, tag: String): Value.Null {
        val tags = (entity.fields["tags"] as? Value.String)?.value?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        if (!tags.contains(tag)) {
            tags.add(tag)
            entity.fields["tags"] = Value.String(tags.joinToString(","))
        }
        return Value.Null
    }

    /**
     * has_tag(entity, "tag") -> bool
     */
    fun hasTag(entity: Value.Instance, tag: String): Value.Boolean {
        val tags = (entity.fields["tags"] as? Value.String)?.value?.split(",")?.filter { it.isNotBlank() } ?: return Value.Boolean.FALSE
        return if (tags.contains(tag)) Value.Boolean.TRUE else Value.Boolean.FALSE
    }

    /**
     * remove_tag(entity, "tag")
     */
    fun removeTag(entity: Value.Instance, tag: String): Value.Null {
        val tags = (entity.fields["tags"] as? Value.String)?.value?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        tags.remove(tag)
        entity.fields["tags"] = Value.String(tags.joinToString(","))
        return Value.Null
    }

    /**
     * play_effect(entity, "FIRE")
     */
    fun playEffect(entity: Value.Instance, effectName: String): Value.Null {
        return Value.Null
    }

    /**
     * Returns a map of MobSkills functions wrapped as Ink NativeFunctions.
     * Use this to register mob skills in the VM's globals.
     */
    fun asNativeFunctionMap(): Map<String, Value> {
        return mapOf(
            "particle_effect" to Value.NativeFunction { args ->
                if (args.size < 7) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val effect = (args[1] as? Value.String)?.value ?: return@NativeFunction Value.Null
                val ox = (args[2] as? Value.Double)?.value ?: 0.0
                val oy = (args[3] as? Value.Double)?.value ?: 0.0
                val oz = (args[4] as? Value.Double)?.value ?: 0.0
                val speed = (args[5] as? Value.Double)?.value ?: 0.0
                val count = (args[6] as? Value.Int)?.value ?: 1
                particleEffect(entity, effect, ox, oy, oz, speed, count)
            },
            "sound" to Value.NativeFunction { args ->
                if (args.size < 3) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val sound = (args[1] as? Value.String)?.value ?: return@NativeFunction Value.Null
                val volume = (args[2] as? Value.Double)?.value ?: 1.0
                val pitch = (args.getOrNull(3) as? Value.Double)?.value ?: 1.0
                sound(entity, sound, volume, pitch)
            },
            "explosion" to Value.NativeFunction { args ->
                if (args.size < 4) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val radius = (args[1] as? Value.Double)?.value?.toFloat() ?: 2.0f
                val setFire = (args[2] as? Value.Boolean)?.value ?: false
                val breakBlocks = (args[3] as? Value.Boolean)?.value ?: true
                explosion(entity, radius, setFire, breakBlocks)
            },
            "damage" to Value.NativeFunction { args ->
                if (args.size < 2) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val amount = (args[1] as? Value.Double)?.value ?: 0.0
                damage(entity, amount)
            },
            "heal" to Value.NativeFunction { args ->
                if (args.size < 2) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val amount = (args[1] as? Value.Double)?.value ?: 0.0
                heal(entity, amount)
            },
            "speed_boost" to Value.NativeFunction { args ->
                if (args.size < 3) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val amplifier = (args[1] as? Value.Double)?.value ?: 1.0
                val ticks = (args[2] as? Value.Int)?.value ?: 60
                speedBoost(entity, amplifier, ticks)
            },
            "jump_boost" to Value.NativeFunction { args ->
                if (args.size < 3) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val amplifier = (args[1] as? Value.Double)?.value ?: 1.0
                val ticks = (args[2] as? Value.Int)?.value ?: 60
                jumpBoost(entity, amplifier, ticks)
            },
            "teleport" to Value.NativeFunction { args ->
                if (args.size < 4) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val x = (args[1] as? Value.Double)?.value ?: 0.0
                val y = (args[2] as? Value.Double)?.value ?: 0.0
                val z = (args[3] as? Value.Double)?.value ?: 0.0
                teleport(entity, x, y, z)
            },
            "summon" to Value.NativeFunction { args ->
                if (args.size < 5) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val mobType = (args[1] as? Value.String)?.value ?: return@NativeFunction Value.Null
                val x = (args[2] as? Value.Double)?.value ?: 0.0
                val y = (args[3] as? Value.Double)?.value ?: 0.0
                val z = (args[4] as? Value.Double)?.value ?: 0.0
                summon(entity, mobType, x, y, z)
            },
            "remove_entity" to Value.NativeFunction { args ->
                if (args.isEmpty()) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                removeEntity(entity)
            },
            "set_velocity" to Value.NativeFunction { args ->
                if (args.size < 4) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val x = (args[1] as? Value.Double)?.value ?: 0.0
                val y = (args[2] as? Value.Double)?.value ?: 0.0
                val z = (args[3] as? Value.Double)?.value ?: 0.0
                setVelocity(entity, x, y, z)
            },
            "ignite" to Value.NativeFunction { args ->
                if (args.size < 2) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val seconds = (args[1] as? Value.Int)?.value ?: 0
                ignite(entity, seconds)
            },
            "extinguish" to Value.NativeFunction { args ->
                if (args.isEmpty()) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                extinguish(entity)
            },
            "set_health" to Value.NativeFunction { args ->
                if (args.size < 2) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val health = (args[1] as? Value.Double)?.value ?: 20.0
                setHealth(entity, health)
            },
            "set_max_health" to Value.NativeFunction { args ->
                if (args.size < 2) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val maxHealth = (args[1] as? Value.Double)?.value ?: 20.0
                setMaxHealth(entity, maxHealth)
            },
            "add_tag" to Value.NativeFunction { args ->
                if (args.size < 2) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val tag = (args[1] as? Value.String)?.value ?: return@NativeFunction Value.Null
                addTag(entity, tag)
            },
            "has_tag" to Value.NativeFunction { args ->
                if (args.size < 2) return@NativeFunction Value.Boolean.FALSE
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Boolean.FALSE
                val tag = (args[1] as? Value.String)?.value ?: return@NativeFunction Value.Boolean.FALSE
                hasTag(entity, tag)
            },
            "remove_tag" to Value.NativeFunction { args ->
                if (args.size < 2) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val tag = (args[1] as? Value.String)?.value ?: return@NativeFunction Value.Null
                removeTag(entity, tag)
            },
            "play_effect" to Value.NativeFunction { args ->
                if (args.size < 2) return@NativeFunction Value.Null
                val entity = args[0] as? Value.Instance ?: return@NativeFunction Value.Null
                val effect = (args[1] as? Value.String)?.value ?: return@NativeFunction Value.Null
                playEffect(entity, effect)
            }
        )
    }
}
