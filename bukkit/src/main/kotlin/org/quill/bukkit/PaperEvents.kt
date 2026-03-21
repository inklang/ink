package org.quill.bukkit

import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin

class PaperEvents(private val plugin: JavaPlugin) {

    fun registerHandlers() {
        plugin.server.pluginManager.registerEvent(
            PlayerJoinEvent::class.java,
            object : org.bukkit.event.Listener {
                @org.bukkit.event.EventHandler
                fun onPlayerJoin(event: PlayerJoinEvent) {
                    // TODO: Fire Ink event handlers
                }
            },
            EventPriority.NORMAL,
            { _, _ -> },
            plugin
        )
    }
}
