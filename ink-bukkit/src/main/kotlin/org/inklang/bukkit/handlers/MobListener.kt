package org.inklang.bukkit.handlers

import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.inklang.lang.Value
import org.inklang.bukkit.PluginRuntime

/**
 * Bukkit event listener for mob events.
 * Handles entity spawn, death, damage, and target events for registered mobs.
 */
class MobListener(
    private val runtime: PluginRuntime,
    private val mobHandler: MobHandler,
    private val pluginName: String
) : Listener {

    private val mobSkills = MobSkills

    /**
     * Get the mob handler for external access.
     */
    fun getMobHandler(): MobHandler = mobHandler

    @EventHandler
    fun onEntitySpawn(event: EntitySpawnEvent) {
        val entity = event.entity
        val mobName = entity.type.name
        val mob = mobHandler.getMob(mobName) ?: return

        // Apply equipment
        applyEquipment(entity, mob.equipment)

        // Execute on_spawn handler
        executeHandler(mob, "on_spawn", entity)
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val mobName = entity.type.name
        val mob = mobHandler.getMob(mobName) ?: return

        // Execute on_death handler
        executeHandler(mob, "on_death", entity)

        // Apply drops
        applyDrops(event, mob.drops)

        // Apply experience
        mob.experience?.let { event.droppedExp = it }
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity
        val mobName = entity.type.name
        val mob = mobHandler.getMob(mobName) ?: return

        // Execute on_damage handler with damage info
        val damageValue = Value.Double(event.finalDamage.toDouble())
        val result = executeHandlerWithResult(mob, "on_damage", entity, mapOf("damage" to damageValue))

        // If handler returns TRUE, cancel the damage
        if (result == Value.Boolean.TRUE) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val entity = event.entity
        val mobName = entity.type.name
        val mob = mobHandler.getMob(mobName) ?: return

        // Get the damager
        val damager = event.damager
        val damagerValue = when (damager) {
            is Player -> Value.Instance(
                org.inklang.lang.ClassDescriptor("Player", null, emptyMap()),
                mutableMapOf("name" to Value.String(damager.name))
            )
            else -> Value.Instance(
                org.inklang.lang.ClassDescriptor("Entity", null, emptyMap()),
                mutableMapOf("type" to Value.String(damager.type.name))
            )
        }

        // Execute on_damaged handler with damage info and damager
        val damageValue = Value.Double(event.finalDamage.toDouble())
        val extraGlobals = mapOf(
            "damage" to damageValue,
            "damager" to damagerValue
        )
        val result = executeHandlerWithResult(mob, "on_damaged", entity, extraGlobals)

        // If handler returns TRUE, cancel the damage
        if (result == Value.Boolean.TRUE) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onEntityTarget(event: EntityTargetEvent) {
        val entity = event.entity
        val mobName = entity.type.name
        val mob = mobHandler.getMob(mobName) ?: return

        executeHandler(mob, "on_target", entity)
    }

    private fun applyEquipment(entity: Entity, equipment: Map<String, String>) {
        if (entity is org.bukkit.entity.LivingEntity) {
            val equipmentSlot = entity.equipment ?: return

            equipment["head"]?.let { itemName ->
                getMaterial(itemName)?.let { material ->
                    equipmentSlot.helmet = org.bukkit.inventory.ItemStack(material)
                }
            }
            equipment["chest"]?.let { itemName ->
                getMaterial(itemName)?.let { material ->
                    equipmentSlot.chestplate = org.bukkit.inventory.ItemStack(material)
                }
            }
            equipment["legs"]?.let { itemName ->
                getMaterial(itemName)?.let { material ->
                    equipmentSlot.leggings = org.bukkit.inventory.ItemStack(material)
                }
            }
            equipment["boots"]?.let { itemName ->
                getMaterial(itemName)?.let { material ->
                    equipmentSlot.boots = org.bukkit.inventory.ItemStack(material)
                }
            }
            equipment["main"]?.let { itemName ->
                getMaterial(itemName)?.let { material ->
                    equipmentSlot.setItemInMainHand(org.bukkit.inventory.ItemStack(material))
                }
            }
            equipment["off"]?.let { itemName ->
                getMaterial(itemName)?.let { material ->
                    equipmentSlot.setItemInOffHand(org.bukkit.inventory.ItemStack(material))
                }
            }
        }
    }

    private fun applyDrops(event: EntityDeathEvent, drops: List<MobHandler.DropEntry>) {
        for (drop in drops) {
            val chance = drop.chance ?: 100
            if (Math.random() * 100 < chance) {
                val material = getMaterial(drop.item)
                if (material != null && material != Material.AIR) {
                    val amount = drop.amount ?: 1
                    event.drops.add(org.bukkit.inventory.ItemStack(material, amount))
                }
            }
        }
    }

    private fun getMaterial(name: String): Material? {
        return try {
            Material.valueOf(name.uppercase())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun executeHandler(mob: MobHandler.MobDefinition, eventName: String, entity: Entity) {
        val handlerFunc = mob.eventHandlers[eventName] ?: return

        // Create the entity as a global for the script
        val entityInstance = createEntityValue(entity)
        val extraGlobals = mapOf("entity" to entityInstance)

        // Execute the handler using PluginRuntime
        runtime.executeMobHandler(pluginName, handlerFunc, extraGlobals)
    }

    private fun executeHandlerWithResult(
        mob: MobHandler.MobDefinition,
        eventName: String,
        entity: Entity,
        extraGlobals: Map<String, Value>
    ): Value {
        val handlerFunc = mob.eventHandlers[eventName] ?: return Value.Null

        val entityInstance = createEntityValue(entity)
        val allGlobals = extraGlobals + mapOf("entity" to entityInstance)

        // Execute the handler using PluginRuntime
        return runtime.executeMobHandler(pluginName, handlerFunc, allGlobals) ?: Value.Null
    }

    private fun createEntityValue(entity: Entity): Value.Instance {
        val health = if (entity is org.bukkit.entity.LivingEntity) entity.health else 0.0
        return Value.Instance(
            org.inklang.lang.ClassDescriptor("Entity", null, emptyMap()),
            mutableMapOf(
                "type" to Value.String(entity.type.name),
                "location" to createLocationValue(entity.location),
                "health" to Value.Double(health),
                "world" to Value.String(entity.world.name)
            )
        )
    }

    private fun createLocationValue(location: org.bukkit.Location): Value.Instance {
        return Value.Instance(
            org.inklang.lang.ClassDescriptor("Location", null, emptyMap()),
            mutableMapOf(
                "x" to Value.Double(location.x),
                "y" to Value.Double(location.y),
                "z" to Value.Double(location.z),
                "world" to Value.String(location.world.name)
            )
        )
    }
}
