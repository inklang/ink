package org.inklang.bukkit.handlers

import org.bukkit.entity.EntityType
import org.inklang.ContextVM
import org.inklang.bukkit.InkBukkit
import org.inklang.grammar.CstNode
import org.inklang.lang.Chunk

/**
 * Handles `mob <Name> { ... }` grammar declarations at runtime.
 *
 * Called by PluginRuntime whenever CALL_HANDLER fires with keyword="mob".
 * Registers a MobListener for the entity type named in the declaration,
 * wiring each event clause (on_spawn, on_death, etc.) to its compiled function.
 */
object MobHandler {

    fun handle(
        cst: CstNode.Declaration,
        chunk: Chunk,
        vm: ContextVM,
        plugin: InkBukkit
    ) {
        val entityTypeName = cst.name.uppercase()
        val entityType = runCatching { EntityType.valueOf(entityTypeName) }.getOrElse {
            plugin.logger.warning("[Ink/mob] Unknown entity type '${cst.name}' — skipping")
            return
        }

        val handlers = extractHandlers(cst)
        if (handlers.isEmpty()) {
            plugin.logger.fine("[Ink/mob] No event handlers for ${cst.name} — nothing to register")
            return
        }

        val listener = MobListener(entityType, cst.name, handlers, chunk, vm, plugin.server)
        plugin.server.pluginManager.registerEvents(listener, plugin)

        val eventList = handlers.keys.joinToString(", ")
        plugin.logger.info("[Ink/mob] Registered ${cst.name} ($eventList)")
    }

    /**
     * Walk the CST body and collect event-clause → funcIdx mappings.
     * Looks for RuleMatch nodes whose ruleName ends in a known clause suffix.
     */
    private fun extractHandlers(cst: CstNode.Declaration): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (node in cst.body) {
            if (node !is CstNode.RuleMatch) continue
            val clause = node.ruleName.substringAfterLast('/')
            val eventName = when (clause) {
                "on_spawn_clause"   -> "on_spawn"
                "on_death_clause"   -> "on_death"
                "on_damage_clause"  -> "on_damage"
                "on_tick_clause"    -> "on_tick"
                "on_target_clause"  -> "on_target"
                "on_interact_clause"-> "on_interact"
                else                -> continue
            }
            val fnBlock = node.children.filterIsInstance<CstNode.FunctionBlock>().firstOrNull()
                ?: continue
            result[eventName] = fnBlock.funcIdx
        }
        return result
    }
}
