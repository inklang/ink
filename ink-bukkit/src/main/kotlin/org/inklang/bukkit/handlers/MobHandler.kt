package org.inklang.bukkit.handlers

import org.inklang.lang.Value

/**
 * Handles mob registration and management for the ink.mobs library.
 */
class MobHandler {

    data class MobDefinition(
        val name: String,
        val equipment: Map<String, String>,
        val drops: List<DropEntry>,
        val experience: Int?,
        val eventHandlers: Map<String, Value.Function>  // eventName -> handler function
    )

    data class DropEntry(val item: String, val chance: Int?, val amount: Int?)

    private val registeredMobs = mutableMapOf<String, MobDefinition>()

    /**
     * Handle a mob registration from Ink code.
     * This is called when the VM executes REGISTER_MOB bytecode.
     */
    fun handle(mobName: String, equipment: Map<String, String>, drops: List<DropEntry>, experience: Int?, eventHandlers: Map<String, Value.Function>) {
        val mobDef = MobDefinition(
            name = mobName,
            equipment = equipment,
            drops = drops,
            experience = experience,
            eventHandlers = eventHandlers
        )
        registeredMobs[mobDef.name] = mobDef
    }

    /**
     * Get a registered mob by name.
     */
    fun getMob(name: String): MobDefinition? = registeredMobs[name]

    /**
     * Get all registered mobs.
     */
    fun getAllMobs(): Map<String, MobDefinition> = registeredMobs.toMap()

    /**
     * Check if a mob type is registered.
     */
    fun isRegistered(name: String): Boolean = registeredMobs.containsKey(name)
}
