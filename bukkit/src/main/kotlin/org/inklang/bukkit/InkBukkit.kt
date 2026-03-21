package org.inklang.bukkit

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.inklang.CompiledScript
import org.inklang.InkCompiler
import org.inklang.InkContext
import org.inklang.lang.Value

class InkBukkit : JavaPlugin() {

    private val compiler = InkCompiler()
    private val scriptCache: MutableMap<String, CompiledScript> = mutableMapOf()
    private val paperEvents = PaperEvents(this)

    override fun onEnable() {
        logger.info("Ink plugin enabled!")
        paperEvents.registerHandlers()
    }

    override fun onDisable() {
        logger.info("Ink plugin disabled!")
        scriptCache.clear()
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (command.name != "ink") return false

        if (args.isEmpty()) {
            sender.sendMessage("§cUsage: /ink run <script>")
            return true
        }

        return when (args[0]) {
            "run" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /ink run <script>")
                    true
                } else {
                    val script = args.drop(1).joinToString(" ")
                    runScript(sender, script)
                    true
                }
            }
            else -> {
                sender.sendMessage("§cUnknown subcommand. Use: /ink run <script>")
                true
            }
        }
    }

    private fun runScript(sender: CommandSender, script: String) {
        try {
            val compiled = scriptCache.getOrPut(script.hashCode().toString()) {
                compiler.compile(script)
            }
            val context = BukkitContext(sender, this)
            compiled.execute(context)
            sender.sendMessage("§aScript executed successfully")
        } catch (e: Exception) {
            sender.sendMessage("§cError: ${e.message}")
        }
    }
}

class BukkitContext(private val sender: CommandSender, private val plugin: InkBukkit) : InkContext {
    private val eventHandlers = mutableMapOf<String, MutableList<Value.Function>>()

    override fun log(message: String) {
        plugin.logger.info("[Ink] $message")
    }

    override fun print(message: String) {
        sender.sendMessage("§f[Ink] $message")
    }

    override fun registerEventHandler(
        eventName: String,
        handlerFunc: Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    ) {
        eventHandlers.getOrPut(eventName) { mutableListOf() }.add(handlerFunc)
    }

    override fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean {
        val handlers = eventHandlers[eventName] ?: return true
        for (handler in handlers) {
            if (event.cancelled) return false
            // TODO: Execute handler with event object + data args
        }
        return !event.cancelled
    }
}
