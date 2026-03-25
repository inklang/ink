package org.inklang.bukkit.runtime

import org.bukkit.command.CommandSender
import org.bukkit.Server
import org.inklang.lang.ClassRegistry
import org.inklang.lang.EconomySkills
import org.inklang.lang.Value

object BukkitRuntimeRegistrar {

    fun register(sender: CommandSender, server: Server) {
        org.inklang.lang.ClassRegistry.registerGlobal(
            "player",
            PlayerClass.createDescriptor(sender, server)
        )
        org.inklang.lang.ClassRegistry.registerGlobal(
            "server",
            ServerClass.createDescriptor(server)
        )
        if (server.worlds.isNotEmpty()) {
            org.inklang.lang.ClassRegistry.registerGlobal(
                "world",
                WorldClass.createDescriptor(server.worlds[0], server)
            )
        }
    }

    /**
     * Register economy built-in functions as globals so they're callable as
     * eco_balance("Steve"), eco_give("Steve", 100), etc.
     */
    fun registerEconomyFunctions(ecoFunctions: Map<String, Value>) {
        ecoFunctions.forEach { (name, fn) ->
            ClassRegistry.registerGlobalFunction(name, fn)
        }
    }
}
