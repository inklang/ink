package org.inklang.bukkit.handlers

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.inklang.ContextVM
import org.inklang.bukkit.BukkitRuntimeRegistrar
import org.inklang.bukkit.InkBukkit
import org.inklang.grammar.CstNode
import org.inklang.lang.Chunk
import org.inklang.lang.Value

/**
 * Handles `command <Name> { ... }` grammar declarations at runtime.
 *
 * Called by PluginRuntime whenever dispatchKeyword fires with keyword="command".
 * Registers a Bukkit Command that wires the execute function to the compiled function.
 */
object CommandHandler {

    fun handle(
        cst: CstNode.Declaration,
        chunk: Chunk,
        vm: ContextVM,
        plugin: InkBukkit
    ) {
        val commandName = cst.name
        // The function block is in cst.body as either:
        // - A CstNode.RuleMatch whose children contain a CstNode.FunctionBlock
        // - Directly a CstNode.FunctionBlock
        val fnBlock = cst.body.filterIsInstance<CstNode.FunctionBlock>().firstOrNull()
            ?: cst.body.flatMap { if (it is CstNode.RuleMatch) it.children else listOf(it) }
                .filterIsInstance<CstNode.FunctionBlock>().firstOrNull()
            ?: return

        val cmd = object : Command(commandName) {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
                vm.executeWithLock {
                    vm.setGlobals(mapOf(
                        "sender" to BukkitRuntimeRegistrar.wrapSender(sender),
                        "args"   to Value.List(args.toList())
                    ))
                    vm.execute(chunk.functions[fnBlock.funcIdx])
                }
                return true
            }
        }

        plugin.server.commandMap.register(plugin.description.name.lowercase(), cmd)
        plugin.logger.info("[Ink/command] Registered /$commandName")
    }
}
