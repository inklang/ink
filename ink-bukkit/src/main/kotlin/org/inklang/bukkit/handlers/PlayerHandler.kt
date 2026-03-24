package org.inklang.bukkit.handlers

import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.inklang.ContextVM
import org.inklang.bukkit.InkBukkit
import org.inklang.bukkit.runtime.PlayerClass
import org.inklang.grammar.CstNode
import org.inklang.lang.Chunk
import org.inklang.lang.Value

/**
 * Bukkit listener wired up for a single grammar `player` declaration.
 * Each player declaration registers one of these.
 *
 * Before executing each handler chunk, injects event-specific globals into the VM:
 *   - `player`  — always present, wraps the Bukkit player
 *   - `message` — on_chat only, the chat message
 *   - `cancel`  — on_chat only, a function that cancels the event
 */
class PlayerListener(
    private val handlers: Map<String, Int>,
    private val chunk: Chunk,
    private val vm: ContextVM,
    private val server: Server
) : Listener {

    @EventHandler
    fun onPlayerJoin(evt: PlayerJoinEvent) {
        handlers["on_join"]?.let { funcIdx ->
            safeCall(funcIdx, "on_join", mapOf(
                "player" to Value.Instance(PlayerClass.createDescriptor(evt.player, server))
            ))
        }
    }

    @EventHandler
    fun onPlayerQuit(evt: PlayerQuitEvent) {
        handlers["on_leave"]?.let { funcIdx ->
            safeCall(funcIdx, "on_leave", mapOf(
                "player" to Value.Instance(PlayerClass.createDescriptor(evt.player, server))
            ))
        }
    }

    @EventHandler
    fun onPlayerChat(evt: AsyncPlayerChatEvent) {
        handlers["on_chat"]?.let { funcIdx ->
            safeCall(funcIdx, "on_chat", mapOf(
                "player" to Value.Instance(PlayerClass.createDescriptor(evt.player, server)),
                "message" to Value.String(evt.message),
                "cancel" to Value.NativeFunction {
                    evt.isCancelled = true
                    Value.Null
                }
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
            System.err.println("[Ink] Error in player $eventName handler: ${e.message}")
        }
    }
}

/**
 * Handles `player <event_clause> { ... }` grammar declarations at runtime.
 *
 * Called by PluginRuntime whenever dispatchKeyword fires with keyword="player".
 * Registers a PlayerListener for the player events.
 */
object PlayerHandler {

    fun handle(
        cst: CstNode.Declaration,
        chunk: Chunk,
        vm: ContextVM,
        plugin: InkBukkit
    ) {
        val handlers = extractHandlers(cst)
        if (handlers.isEmpty()) {
            plugin.logger.fine("[Ink/player] No event handlers for ${cst.name} — nothing to register")
            return
        }

        val listener = PlayerListener(handlers, chunk, vm, plugin.server)
        plugin.server.pluginManager.registerEvents(listener, plugin)

        val eventList = handlers.keys.joinToString(", ")
        plugin.logger.info("[Ink/player] Registered player ${cst.name} ($eventList)")
    }

    /**
     * Walk the CST body and collect event-clause to funcIdx mappings.
     * Looks for RuleMatch nodes whose ruleName ends in a known clause suffix.
     */
    private fun extractHandlers(cst: CstNode.Declaration): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (node in cst.body) {
            if (node !is CstNode.RuleMatch) continue
            val clause = node.ruleName.substringAfterLast('/')
            val eventName = when (clause) {
                "on_join_clause"  -> "on_join"
                "on_leave_clause" -> "on_leave"
                "on_chat_clause"  -> "on_chat"
                else              -> continue
            }
            val fnBlock = node.children.filterIsInstance<CstNode.FunctionBlock>().firstOrNull()
                ?: continue
            result[eventName] = fnBlock.funcIdx
        }
        return result
    }
}
