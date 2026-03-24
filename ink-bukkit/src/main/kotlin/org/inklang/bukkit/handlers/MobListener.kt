package org.inklang.bukkit.handlers

import org.bukkit.Server
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.inklang.ContextVM
import org.inklang.bukkit.runtime.EntityClass
import org.inklang.lang.Chunk
import org.inklang.lang.Value

/**
 * Bukkit listener wired up for a single grammar `mob` declaration.
 * Each mob declaration registers one of these.
 *
 * Before executing each handler chunk, injects event-specific globals into the VM:
 *   - `entity`  — always present, wraps the Bukkit entity
 *   - `damage`  — on_damage only, the damage amount as a Double
 *   - `cancel`  — on_damage only, a function that cancels the event
 *   - `target`  — on_target only, the entity being targeted (or null)
 */
class MobListener(
    private val entityType: EntityType,
    private val mobName: String,
    private val handlers: Map<String, Int>,   // eventName -> chunk.functions index
    private val chunk: Chunk,
    private val vm: ContextVM,
    private val server: Server
) : Listener {

    @EventHandler
    fun onEntitySpawn(evt: EntitySpawnEvent) {
        if (evt.entity.type != entityType) return
        handlers["on_spawn"]?.let { funcIdx ->
            safeCall(funcIdx, "on_spawn", mapOf(
                "entity" to EntityClass.wrap(evt.entity, server)
            ))
        }
    }

    @EventHandler
    fun onEntityDeath(evt: EntityDeathEvent) {
        if (evt.entity.type != entityType) return
        handlers["on_death"]?.let { funcIdx ->
            safeCall(funcIdx, "on_death", mapOf(
                "entity" to EntityClass.wrap(evt.entity, server)
            ))
        }
    }

    @EventHandler
    fun onEntityDamage(evt: EntityDamageEvent) {
        if (evt.entity.type != entityType) return
        handlers["on_damage"]?.let { funcIdx ->
            safeCall(funcIdx, "on_damage", mapOf(
                "entity" to EntityClass.wrap(evt.entity, server),
                "damage" to Value.Double(evt.damage),
                "cancel" to Value.NativeFunction { evt.isCancelled = true; Value.Null }
            ))
        }
    }

    @EventHandler
    fun onEntityTarget(evt: EntityTargetEvent) {
        if (evt.entity.type != entityType) return
        handlers["on_target"]?.let { funcIdx ->
            val targetValue: Value = evt.target?.let { EntityClass.wrap(it, server) } ?: Value.Null
            safeCall(funcIdx, "on_target", mapOf(
                "entity" to EntityClass.wrap(evt.entity, server),
                "target" to targetValue
            ))
        }
    }

    private fun safeCall(funcIdx: Int, eventName: String, eventGlobals: Map<String, Value>) {
        try {
            if (funcIdx < chunk.functions.size) {
                vm.executeWithLock {
                    vm.setGlobals(eventGlobals)
                    vm.execute(chunk.functions[funcIdx])
                }
            }
        } catch (e: Exception) {
            System.err.println("[Ink] Error in mob '$mobName' $eventName handler: ${e.message}")
        }
    }
}
