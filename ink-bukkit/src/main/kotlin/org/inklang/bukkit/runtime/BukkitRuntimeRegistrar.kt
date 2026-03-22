package org.inklang.bukkit.runtime

import org.bukkit.command.CommandSender
import org.bukkit.Server
import org.inklang.lang.ClassRegistry

object BukkitRuntimeRegistrar {

    fun register(sender: CommandSender, server: Server) {
        ClassRegistry.registerGlobal("player", PlayerClass.createDescriptor(sender, server))
        ClassRegistry.registerGlobal("server", ServerClass.createDescriptor(server))
        if (server.worlds.isNotEmpty()) {
            ClassRegistry.registerGlobal("world", WorldClass.createDescriptor(server.worlds[0], server))
        }
    }
}
