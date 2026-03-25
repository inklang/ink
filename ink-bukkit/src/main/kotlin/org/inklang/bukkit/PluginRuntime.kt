package org.inklang.bukkit

import org.inklang.InkScript
import org.inklang.ContextVM
import org.inklang.inkScriptFromJson
import org.inklang.bukkit.handlers.CommandHandler
import org.inklang.bukkit.handlers.MobHandler
import org.inklang.bukkit.handlers.PlayerHandler
import org.inklang.grammar.CstNode
import org.inklang.lang.Chunk
import org.inklang.lang.Value
import org.inklang.lang.ClassRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Signature for a built-in grammar keyword handler. */
typealias GrammarKeywordHandler = (cst: CstNode.Declaration, chunk: Chunk, vm: ContextVM, plugin: InkBukkit) -> Unit

/**
 * Manages loaded plugins — lifecycle, event registration, state.
 * Each plugin gets a persistent ContextVM that lives for the server lifetime.
 */
class PluginRuntime(
    private val plugin: InkBukkit,
    private val globalConfig: GlobalConfig
) {
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()

    /** Built-in grammar keyword handlers, keyed by grammar declaration keyword. */
    private val keywordHandlers: Map<String, GrammarKeywordHandler> = mapOf(
        "mob" to MobHandler::handle,
        "command" to CommandHandler::handle,
        "player" to PlayerHandler::handle
    )

    data class LoadedPlugin(
        val name: String,
        val script: InkScript,
        val enableScript: InkScript,
        val disableScript: InkScript?,
        val context: PluginContext,
        val folder: File,
        val vm: ContextVM
    )

    /**
     * Load and enable a plugin from a precompiled .inkc file.
     */
    fun loadCompiledPlugin(inkcFile: File): Result<LoadedPlugin> {
        val pluginName = inkcFile.nameWithoutExtension

        if (globalConfig.isPluginDisabled(pluginName)) {
            return Result.failure(PluginDisabledException("$pluginName is disabled in plugins.toml"))
        }

        return try {
            val script = inkScriptFromJson(inkcFile.readText())
            plugin.logger.info("Loading precompiled plugin: $pluginName")
            enableScript(pluginName, script)
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load compiled Ink plugin $pluginName: ${e.message}")
            Result.failure(e)
        }
    }

    private fun enableScript(pluginName: String, script: InkScript): Result<LoadedPlugin> {
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
            pluginFolder,
            onPluginDecl = { cst, chunk, vm -> dispatchKeyword(cst, chunk, vm) }
        )

        val vm = ContextVM(context)

        val preloadedConfigs = script.preloadConfigs(pluginFolder.absolutePath)
        vm.setGlobals(preloadedConfigs)

        val paperGlobals = PaperGlobals.getGlobals(plugin.server.consoleSender, plugin.server)
        vm.setGlobals(paperGlobals)

        // Include ClassRegistry globals (including economy eco_* functions)
        vm.setGlobals(ClassRegistry.getAllGlobals())

        context.setVM(vm)

        vm.execute(script.getChunk())

        val loaded = LoadedPlugin(
            name = pluginName,
            script = script,
            enableScript = script,
            disableScript = script,
            context = context,
            folder = pluginFolder,
            vm = vm
        )

        loadedPlugins[pluginName] = loaded
        plugin.logger.info("Ink plugin loaded: $pluginName")
        return Result.success(loaded)
    }

    /**
     * Dispatch a grammar declaration to the appropriate built-in keyword handler.
     */
    private fun dispatchKeyword(cst: CstNode.Declaration, chunk: Chunk, vm: ContextVM) {
        val handler = keywordHandlers[cst.keyword]
        if (handler != null) {
            handler(cst, chunk, vm, plugin)
        } else {
            plugin.logger.fine("[Ink] No handler for grammar keyword '${cst.keyword}' (${cst.name})")
        }
    }

    /**
     * Unload a plugin (execute disable block, discard VM).
     */
    fun unloadPlugin(pluginName: String) {
        val loaded = loadedPlugins.remove(pluginName) ?: return
        try {
            loaded.disableScript?.let { loaded.vm.execute(it.getChunk()) }
        } catch (e: Exception) {
            plugin.logger.severe("Error during disable for $pluginName: ${e.message}")
        } finally {
            loaded.vm.close()
        }
        plugin.logger.info("Ink plugin unloaded: $pluginName")
    }

    fun unloadAll() {
        loadedPlugins.keys.toList().forEach { unloadPlugin(it) }
    }

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
}

class PluginDisabledException(message: String) : RuntimeException(message)
