package org.inklang.bukkit

import org.inklang.InkCompiler
import org.inklang.InkScript
import org.inklang.lang.Value
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages loaded plugins — lifecycle, event registration, state.
 */
class PluginRuntime(
    private val plugin: InkBukkit,
    private val globalConfig: GlobalConfig
) {
    private val compiler = InkCompiler()
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()

    data class LoadedPlugin(
        val name: String,
        val script: InkScript,
        val enableScript: InkScript,
        val disableScript: InkScript?,
        val context: PluginContext,
        val folder: File
    )

    /**
     * Load and enable a plugin from its .ink file.
     */
    fun loadPlugin(pluginFile: File): Result<LoadedPlugin> {
        val pluginName = pluginFile.nameWithoutExtension

        if (globalConfig.isPluginDisabled(pluginName)) {
            return Result.failure(PluginDisabledException("$pluginName is disabled in plugins.toml"))
        }

        return try {
            val source = pluginFile.readText()
            val script = compiler.compile(source, pluginName)

            // Extract enable and disable blocks
            // TODO: Extract enable/disable blocks from compiled script
            // For now, we'll execute the full script for enable
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

            // Execute enable block
            context.onEnable(enableScript)

            val loaded = LoadedPlugin(
                name = pluginName,
                script = script,
                enableScript = enableScript,
                disableScript = disableScript,
                context = context,
                folder = pluginFolder
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
     * Unload a plugin (execute disable block, clear events).
     */
    fun unloadPlugin(pluginName: String) {
        val loaded = loadedPlugins.remove(pluginName) ?: return
        try {
            loaded.disableScript?.let { loaded.context.onDisable(it) }
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

    fun getLoadedPlugins(): Map<String, LoadedPlugin> = loadedPlugins.toMap()
}

class PluginDisabledException(message: String) : RuntimeException(message)