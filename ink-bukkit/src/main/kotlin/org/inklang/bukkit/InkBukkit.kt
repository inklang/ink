package org.inklang.bukkit

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.inklang.ContextVM
import org.inklang.InkScript
import org.inklang.InkCompiler
import org.inklang.inkScriptFromJson
import org.inklang.bukkit.runtime.BukkitRuntimeRegistrar
import org.inklang.lang.ClassRegistry
import java.io.File

class InkBukkit : JavaPlugin() {

    private val compiler = InkCompiler()
    private val scriptCache = mutableMapOf<String, InkScript>()
    private lateinit var globalConfig: GlobalConfig
    private lateinit var pluginRuntime: PluginRuntime

    override fun onEnable() {
        logger.info("Ink plugin enabled!")

        // Initialize global config
        globalConfig = GlobalConfig(this)

        // Initialize plugin runtime
        pluginRuntime = PluginRuntime(this, globalConfig)

        // Ensure directories exist
        dataFolder.mkdirs()
        File(dataFolder, "plugins").mkdirs()
        File(dataFolder, "scripts").mkdirs()

        // Load plugins from plugins/ink/plugins/
        loadPlugins()
    }

    override fun onDisable() {
        logger.info("Ink plugin disabling...")
        pluginRuntime.unloadAll()
        scriptCache.clear()
    }

    private fun loadPlugins() {
        val pluginsDir = File(dataFolder, "plugins")
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
            return
        }

        val inkFiles  = pluginsDir.listFiles { f -> f.extension == "ink"  }?.toList()  ?: emptyList()
        val inkcFiles = pluginsDir.listFiles { f -> f.extension == "inkc" }?.toList() ?: emptyList()

        // Build set of names that have a precompiled .inkc — these take priority
        val compiledNames = inkcFiles.map { it.nameWithoutExtension }.toSet()

        // Load precompiled plugins first
        for (inkcFile in inkcFiles) {
            val result = pluginRuntime.loadCompiledPlugin(inkcFile)
            if (result.isFailure) {
                logger.severe("Failed to load ${inkcFile.name}: ${result.exceptionOrNull()?.message}")
            }
        }

        // Load source plugins that don't have a precompiled counterpart
        for (inkFile in inkFiles) {
            if (inkFile.nameWithoutExtension in compiledNames) continue
            val result = pluginRuntime.loadPlugin(inkFile)
            if (result.isFailure) {
                logger.severe("Failed to load ${inkFile.name}: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (command.name != "ink") return false

        if (args.isEmpty()) {
            sender.sendMessage("§cUsage: /ink <run|list|load|unload|reload> [args]")
            return true
        }

        return when (args[0]) {
            "run" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /ink run <script>")
                    true
                } else {
                    val scriptName = args.drop(1).joinToString(" ")
                    runScript(sender, scriptName)
                    true
                }
            }
            "load" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /ink load <plugin>")
                    true
                } else {
                    val pluginName = args[1]
                    val pluginFile = File(File(dataFolder, "plugins"), "$pluginName.ink")
                    if (!pluginFile.exists()) {
                        sender.sendMessage("§cPlugin not found: $pluginName.ink")
                    } else {
                        val result = pluginRuntime.loadPlugin(pluginFile)
                        if (result.isSuccess) {
                            sender.sendMessage("§aPlugin loaded: $pluginName")
                        } else {
                            sender.sendMessage("§cFailed to load $pluginName: ${result.exceptionOrNull()?.message}")
                        }
                    }
                    true
                }
            }
            "unload" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /ink unload <plugin>")
                    true
                } else {
                    val pluginName = args[1]
                    pluginRuntime.unloadPlugin(pluginName)
                    sender.sendMessage("§aPlugin unloaded: $pluginName")
                    true
                }
            }
            "list" -> {
                listPlugins(sender)
                true
            }
            "reload" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /ink reload <plugin>")
                    true
                } else {
                    reloadPlugin(sender, args[1])
                    true
                }
            }
            "reload-config" -> {
                globalConfig.reload()
                sender.sendMessage("§aGlobal config reloaded")
                true
            }
            else -> {
                sender.sendMessage("§cUnknown subcommand. Use: /ink <run|list|load|unload|reload>")
                true
            }
        }
    }

    private fun runScript(sender: CommandSender, scriptName: String) {
        try {
            val scriptsDir = File(dataFolder, "scripts")
            val inkcFile = File(scriptsDir, "$scriptName.inkc")
            val inkFile  = File(scriptsDir, "$scriptName.ink")

            val compiled = when {
                inkcFile.exists() -> scriptCache.getOrPut(inkcFile.absolutePath) {
                    inkScriptFromJson(inkcFile.readText())
                }
                inkFile.exists() -> scriptCache.getOrPut(inkFile.absolutePath) {
                    compiler.compile(inkFile.readText(), scriptName)
                }
                else -> {
                    sender.sendMessage("§cScript not found: $scriptName (.ink or .inkc)")
                    return
                }
            }

            val scriptDir = File(File(dataFolder, "scripts"), scriptName)
            scriptDir.mkdirs()
            val dbFile = File(scriptDir, "data.db")
            dbFile.parentFile?.mkdirs()

            val ioDriver = BukkitIo(scriptDir)
            val jsonDriver = BukkitJson()
            val dbDriver = BukkitDb(dbFile.absolutePath)

            val context = ScriptContext(sender, this, ioDriver, jsonDriver, dbDriver)
            val vm = ContextVM(context)

            // Pre-load configs from YAML files before execution
            val preloadedConfigs = compiled.preloadConfigs(scriptDir.absolutePath)
            vm.setGlobals(preloadedConfigs)

            // Register Bukkit globals
            BukkitRuntimeRegistrar.register(sender, server)
            vm.setGlobals(ClassRegistry.getAllGlobals())

            vm.execute(compiled.getChunk())
            sender.sendMessage("§aScript executed successfully")
        } catch (e: UnsupportedOperationException) {
            sender.sendMessage("§c${e.message}")
        } catch (e: Exception) {
            sender.sendMessage("§cError: ${e.message}")
        }
    }

    private fun listPlugins(sender: CommandSender) {
        val plugins = pluginRuntime.getLoadedPlugins()
        if (plugins.isEmpty()) {
            sender.sendMessage("§7No Ink plugins loaded")
            return
        }
        sender.sendMessage("§6Loaded Ink plugins:")
        plugins.forEach { (name, _) ->
            sender.sendMessage("§a- $name")
        }
    }

    private fun reloadPlugin(sender: CommandSender, pluginName: String) {
        try {
            pluginRuntime.unloadPlugin(pluginName)
            val pluginFile = File(File(dataFolder, "plugins"), "$pluginName.ink")
            if (!pluginFile.exists()) {
                sender.sendMessage("§cPlugin not found: $pluginName.ink")
                return
            }
            val result = pluginRuntime.loadPlugin(pluginFile)
            if (result.isSuccess) {
                sender.sendMessage("§aPlugin reloaded: $pluginName")
            } else {
                sender.sendMessage("§cFailed to reload $pluginName: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            sender.sendMessage("§cError reloading $pluginName: ${e.message}")
        }
    }
}
