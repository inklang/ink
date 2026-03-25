package org.inklang.bukkit

import org.inklang.InkCompiler
import org.inklang.InkScript
import org.inklang.ContextVM
import org.inklang.lang.Value
import org.inklang.bukkit.handlers.MobHandler
import org.inklang.bukkit.handlers.MobListener
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages loaded plugins — lifecycle, event registration, state.
 * Each plugin gets a persistent ContextVM that lives for the server lifetime.
 */
class PluginRuntime(
    private val plugin: InkBukkit,
    private val globalConfig: GlobalConfig
) {
    private val compiler = InkCompiler()
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()
    private val mobListeners = ConcurrentHashMap<String, MobListener>()
    private val mobHandlers = ConcurrentHashMap<String, MobHandler>()

    data class LoadedPlugin(
        val name: String,
        val script: InkScript,
        val enableScript: InkScript,
        val disableScript: InkScript?,
        val context: PluginContext,
        val folder: File,
        val vm: ContextVM  // Persistent per-plugin VM
    )

    /**
     * Load and enable a plugin from its .ink file.
     * Creates a persistent VM for the plugin.
     */
    fun loadPlugin(pluginFile: File): Result<LoadedPlugin> {
        val pluginName = pluginFile.nameWithoutExtension

        if (globalConfig.isPluginDisabled(pluginName)) {
            return Result.failure(PluginDisabledException("$pluginName is disabled in plugins.toml"))
        }

        return try {
            val source = pluginFile.readText()

            // Validate plugin has required enable/disable blocks
            val parsedStatements = compiler.parse(source)
            val validationResult = compiler.validatePluginScript(parsedStatements)
            if (!validationResult.isValid()) {
                return Result.failure(
                    IllegalStateException(
                        "Invalid plugin ${pluginFile.name}: ${validationResult.errors.joinToString("; ")}"
                    )
                )
            }

            val script = compiler.compile(source, pluginName)

            // Extract enable and disable blocks
            // TODO: Extract enable/disable blocks from compiled script
            // For now, we execute the full script for enable
            val enableScript = script
            val disableScript = script // TODO: Extract disable block

            val pluginFolder = File(plugin.dataFolder, "plugins/$pluginName")
            pluginFolder.mkdirs()

            val ioDriver = BukkitIo(pluginFolder)
            val jsonDriver = BukkitJson()
            val dbDriver = BukkitDb(File(pluginFolder, "data.db").absolutePath)

            val context = PluginContext(
                plugin.server.consoleSender,
                plugin,
                ioDriver,
                jsonDriver,
                dbDriver,
                pluginName,
                pluginFolder
            )

            // Create the persistent VM for this plugin
            val vm = ContextVM(context)

            // Pre-load configs from YAML files
            val preloadedConfigs = script.preloadConfigs(pluginFolder.absolutePath)
            vm.setGlobals(preloadedConfigs)

            // Add Paper/Bukkit globals (player, server, etc.)
            val paperGlobals = PaperGlobals.getGlobals(plugin.server.consoleSender, plugin.server)
            vm.setGlobals(paperGlobals)

            // Give the context a reference to its VM
            context.setVM(vm)

            // Execute enable block in the persistent VM
            vm.execute(enableScript.getChunk())

            // Extract mob registrations from VM globals and create MobHandler
            val mobHandler = extractMobHandler(vm, pluginName)
            mobHandlers[pluginName] = mobHandler

            // Create and register MobListener for this plugin
            val mobListener = MobListener(this, mobHandler, pluginName)
            mobListeners[pluginName] = mobListener
            plugin.server.pluginManager.registerEvents(mobListener, plugin)

            val loaded = LoadedPlugin(
                name = pluginName,
                script = script,
                enableScript = enableScript,
                disableScript = disableScript,
                context = context,
                folder = pluginFolder,
                vm = vm
            )

            loadedPlugins[pluginName] = loaded
            plugin.logger.info("Ink plugin loaded: $pluginName")
            Result.success(loaded)
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load Ink plugin $pluginName: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Unload a plugin (execute disable block in same VM, then discard VM).
     */
    fun unloadPlugin(pluginName: String) {
        val loaded = loadedPlugins.remove(pluginName) ?: return
        try {
            loaded.disableScript?.let { disableScript ->
                loaded.vm.execute(disableScript.getChunk())
            }
        } catch (e: Exception) {
            plugin.logger.severe("Error during disable for $pluginName: ${e.message}")
        }
        plugin.logger.info("Ink plugin unloaded: $pluginName")
    }

    /**
     * Unload all plugins on server shutdown.
     */
    fun unloadAll() {
        loadedPlugins.keys.toList().forEach { unloadPlugin(it) }
    }

    /**
     * Fire an event to all loaded plugins.
     * Each plugin's handler runs in its own persistent VM.
     */
    fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean {
        var cancelled = false
        for (loaded in loadedPlugins.values) {
            try {
                val wasCancelled = loaded.context.fireEvent(eventName, event, data)
                if (wasCancelled) cancelled = true
            } catch (e: Exception) {
                plugin.logger.severe("Error firing event $eventName to ${loaded.name}: ${e.message}")
            }
        }
        return cancelled
    }

    fun getLoadedPlugins(): Map<String, LoadedPlugin> = loadedPlugins.toMap()

    /**
     * Get the MobHandler for a plugin by name.
     */
    fun getMobHandler(pluginName: String): MobHandler? = mobHandlers[pluginName]

    /**
     * Get the ContextVM for a plugin by name.
     */
    fun getVM(pluginName: String): ContextVM? = loadedPlugins[pluginName]?.vm

    /**
     * Get the context for a plugin.
     */
    fun getContext(pluginName: String): org.inklang.InkContext? = loadedPlugins[pluginName]?.context

    /**
     * Execute a mob handler function with given globals.
     * Creates a new temporary VM for handler execution.
     */
    fun executeMobHandler(pluginName: String, handlerFunc: Value.Function, extraGlobals: Map<String, Value>): Value? {
        val context = object : org.inklang.InkContext {
            private var _vm: org.inklang.ContextVM? = null
            override fun setVM(vm: org.inklang.ContextVM) { _vm = vm }
            override fun log(message: String) { plugin.logger.info("[Ink/$pluginName] $message") }
            override fun print(message: String) { /* handlers don't print */ }
            override fun io(): org.inklang.InkIo = loadedPlugins[pluginName]?.context?.io()
                ?: throw IllegalStateException("No context for plugin $pluginName")
            override fun json(): org.inklang.InkJson = loadedPlugins[pluginName]?.context?.json()
                ?: throw IllegalStateException("No context for plugin $pluginName")
            override fun db(): org.inklang.InkDb = loadedPlugins[pluginName]?.context?.db()
                ?: throw IllegalStateException("No context for plugin $pluginName")
            override fun registerEventHandler(eventName: String, handlerFunc: Value.Function, eventParamName: String, dataParamNames: List<String>) {}
            override fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean = false
            override fun onEnable(script: InkScript) { /* not used for handlers */ }
            override fun onDisable(script: InkScript) { /* not used for handlers */ }
            override fun supportsLifecycle(): Boolean = true
        }

        // Create a temporary VM for handler execution
        val handlerVm = org.inklang.ContextVM(context)

        // Get the plugin's VM globals and merge with extra globals
        val pluginVm = loadedPlugins[pluginName]?.vm
        if (pluginVm != null) {
            handlerVm.setGlobals(pluginVm.getGlobalsSnapshot())
        }
        handlerVm.setGlobals(extraGlobals)

        // Execute the handler function
        try {
            handlerVm.execute(handlerFunc.chunk)
            return null  // Handlers don't return values
        } catch (e: Exception) {
            plugin.logger.severe("Error executing mob handler: ${e.message}")
            return Value.Null
        }
    }

    /**
     * Extract MobHandler from VM's __mobRegistry global.
     */
    private fun extractMobHandler(vm: ContextVM, pluginName: String): MobHandler {
        val handler = MobHandler()
        val globals = vm.getGlobalsSnapshot()
        val mobRegistry = globals["__mobRegistry"] as? Value.Instance
            ?: return handler

        for ((mobName, mobValue) in mobRegistry.fields) {
            if (mobName.startsWith("__")) continue  // Skip internal fields

            val mobDef = mobValue as? Value.MobDefinition ?: continue

            // Convert Value.MobDefinition to MobHandler.MobDefinition
            val drops = mobDef.drops.map {
                MobHandler.DropEntry(it.item, it.chance, it.amount)
            }
            val equipment = mobDef.equipment

            handler.handle(mobDef.name, equipment, drops, mobDef.experience, mobDef.eventHandlers)
        }

        return handler
    }
}

class PluginDisabledException(message: String) : RuntimeException(message)