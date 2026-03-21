package org.inklang.bukkit

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.inklang.ContextVM
import org.inklang.InkScript
import org.inklang.InkCompiler
import java.io.File

class InkBukkit : JavaPlugin() {

    private val compiler = InkCompiler()
    private val scriptCache = mutableMapOf<String, InkScript>()

    override fun onEnable() {
        logger.info("Ink plugin enabled!")
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
            val scriptDir = File(dataFolder, "scripts")
            scriptDir.mkdirs()
            val dbFile = File(dataFolder, "data.db")
            dbFile.parentFile?.mkdirs()

            val ioDriver = BukkitIo(scriptDir)
            val jsonDriver = BukkitJson()
            val dbDriver = BukkitDb(dbFile.absolutePath)

            val context = BukkitContext(sender, this, ioDriver, jsonDriver, dbDriver)
            val vm = ContextVM(context)

            // Pre-load configs from YAML files before execution
            val preloadedConfigs = compiled.preloadConfigs(scriptDir.absolutePath)
            vm.setGlobals(preloadedConfigs)

            vm.execute(compiled.getChunk())
            sender.sendMessage("§aScript executed successfully")
        } catch (e: Exception) {
            sender.sendMessage("§cError: ${e.message}")
        }
    }
}
