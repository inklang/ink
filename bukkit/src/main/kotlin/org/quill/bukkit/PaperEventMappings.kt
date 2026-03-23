package org.quill.bukkit

import org.bukkit.event.Event
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent

object PaperEventMappings {
    // Maps Ink event names to Paper event classes
    val inkToPaper = mapOf(
        "player_join" to PlayerJoinEvent::class.java,
        "player_quit" to PlayerQuitEvent::class.java,
        "block_break" to BlockBreakEvent::class.java,
        "block_place" to BlockPlaceEvent::class.java
    )

    // Which Ink events are supported by Paper runtime
    fun isAvailable(inkEvent: String): Boolean = inkToPaper.containsKey(inkEvent)

    // Get parameter types for an event
    fun getParamTypes(inkEvent: String): List<String> = when (inkEvent) {
        "player_join" -> listOf("Player")
        "player_quit" -> listOf("Player")
        "block_break" -> listOf("Block", "Player")
        "block_place" -> listOf("Block", "Player")
        else -> emptyList()
    }
}
