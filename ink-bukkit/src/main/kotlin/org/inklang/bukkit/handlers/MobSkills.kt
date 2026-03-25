package org.inklang.bukkit.handlers

import org.bukkit.EntityEffect
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector

/**
 * Built-in skill functions for mob event handlers.
 */
object MobSkills {

    /**
     * particle_effect(entity, "EXPLOSION_NORMAL", 0.5, 0.5, 0.5, 0.01, 20)
     */
    fun particleEffect(entity: Entity, effect: String, offsetX: Double, offsetY: Double, offsetZ: Double, speed: Double, count: Int) {
        try {
            val particle = org.bukkit.Particle.valueOf(effect)
            entity.world.spawnParticle(particle, entity.location, count, offsetX, offsetY, offsetZ, speed)
        } catch (e: IllegalArgumentException) {
            // Unknown particle effect - ignore
        }
    }

    /**
     * sound(entity, "ENTITY_CREEPER_HISS", 1.0, 1.0)
     */
    fun sound(entity: Entity, sound: String, volume: Double, pitch: Double) {
        try {
            val soundType = Sound.valueOf(sound)
            entity.world.playSound(entity.location, soundType, volume.toFloat(), pitch.toFloat())
        } catch (e: IllegalArgumentException) {
            // Unknown sound - ignore
        }
    }

    /**
     * explosion(entity, 2.0, false, true)
     */
    fun explosion(entity: Entity, radius: Float, setFire: Boolean, breakBlocks: Boolean) {
        entity.location.world.createExplosion(entity.location, radius, setFire, breakBlocks)
    }

    /**
     * damage(entity, 5.0)
     */
    fun damage(entity: Entity, amount: Double) {
        if (entity is LivingEntity) {
            entity.damage(amount)
        }
    }

    /**
     * heal(entity, 10.0)
     */
    fun heal(entity: Entity, amount: Double) {
        if (entity is LivingEntity) {
            val newHealth = minOf(entity.health + amount, entity.maxHealth)
            entity.health = newHealth
        }
    }

    /**
     * speed_boost(entity, 2.0, 60)
     */
    fun speedBoost(entity: Entity, amplifier: Double, durationTicks: Int) {
        if (entity is LivingEntity) {
            entity.addPotionEffect(PotionEffect(PotionEffectType.SPEED, durationTicks, amplifier.toInt()))
        }
    }

    /**
     * jump_boost(entity, 2.0, 60)
     */
    fun jumpBoost(entity: Entity, amplifier: Double, durationTicks: Int) {
        if (entity is LivingEntity) {
            entity.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, durationTicks, amplifier.toInt()))
        }
    }

    /**
     * teleport(entity, x, y, z)
     */
    fun teleport(entity: Entity, x: Double, y: Double, z: Double) {
        entity.teleport(org.bukkit.Location(entity.world, x, y, z))
    }

    /**
     * summon(entity, "ZOMBIE", x, y, z)
     * Returns the spawned entity
     */
    fun summon(entity: Entity, mobType: String, x: Double, y: Double, z: Double): org.bukkit.entity.Entity? {
        return try {
            val entityType = org.bukkit.entity.EntityType.valueOf(mobType)
            entity.location.world.spawnEntity(org.bukkit.Location(entity.world, x, y, z), entityType)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * remove_entity(entity)
     */
    fun removeEntity(entity: Entity) {
        entity.remove()
    }

    /**
     * set_velocity(entity, x, y, z)
     */
    fun setVelocity(entity: Entity, x: Double, y: Double, z: Double) {
        entity.velocity = Vector(x, y, z)
    }

    /**
     * play_effect(entity, "FIRE")
     */
    fun playEffect(entity: Entity, effectName: String) {
        try {
            val effect = EntityEffect.valueOf(effectName)
            entity.playEffect(effect)
        } catch (e: IllegalArgumentException) {
            // Unknown effect - ignore
        }
    }

    /**
     * ignite(entity, seconds)
     */
    fun ignite(entity: Entity, seconds: Int) {
        entity.fireTicks = seconds * 20
    }

    /**
     * extinguish(entity)
     */
    fun extinguish(entity: Entity) {
        entity.fireTicks = 0
    }

    /**
     * set_health(entity, health)
     */
    fun setHealth(entity: Entity, health: Double) {
        if (entity is LivingEntity) {
            entity.health = health.coerceIn(0.0, entity.maxHealth)
        }
    }

    /**
     * set_max_health(entity, maxHealth)
     */
    fun setMaxHealth(entity: Entity, maxHealth: Double) {
        if (entity is LivingEntity) {
            entity.maxHealth = maxHealth.coerceAtLeast(1.0)
        }
    }

    /**
     * add_tag(entity, "tag")
     */
    fun addTag(entity: Entity, tag: String) {
        entity.addScoreboardTag(tag)
    }

    /**
     * has_tag(entity, "tag") -> bool
     */
    fun hasTag(entity: Entity, tag: String): Boolean {
        return entity.scoreboardTags.contains(tag)
    }

    /**
     * remove_tag(entity, "tag")
     */
    fun removeTag(entity: Entity, tag: String) {
        entity.removeScoreboardTag(tag)
    }
}
