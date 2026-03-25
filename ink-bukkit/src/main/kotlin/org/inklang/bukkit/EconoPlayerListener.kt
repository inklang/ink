package org.inklang.bukkit

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.inklang.lang.EconomySkills

/**
 * Updates player name in economy DB when they join (in case it changed).
 */
class EconoPlayerListener : Listener {

    @EventHandler
    fun onPlayerJoin(evt: PlayerJoinEvent) {
        try {
            val uuid = evt.player.uniqueId.toString()
            val name = evt.player.name
            EconomySkills.db.updateName(uuid, name)
        } catch (e: Exception) {
            // DB may not be initialized yet -- ignore
        }
    }
}
