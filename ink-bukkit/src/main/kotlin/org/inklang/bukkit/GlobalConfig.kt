package org.inklang.bukkit

import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Parses and holds global ink configuration from plugins.toml.
 */
class GlobalConfig(private val plugin: JavaPlugin) {

    data class Config(
        val disabledPlugins: Set<String> = emptySet()
    )

    private var config: Config = Config()

    init {
        reload()
    }

    fun reload() {
        val configFile = File(plugin.dataFolder, "plugins.toml")
        config = if (configFile.exists()) {
            parsePluginsToml(configFile.readText())
        } else {
            Config()
        }
    }

    private fun parsePluginsToml(content: String): Config {
        val disabled = mutableSetOf<String>()
        var inDisabledSection = false

        for (line in content.lines()) {
            val trimmed = line.trim()

            if (trimmed == "[disabled]" || trimmed.startsWith("[disabled]")) {
                inDisabledSection = true
                continue
            }

            if (trimmed.startsWith("[") && trimmed != "[disabled]") {
                inDisabledSection = false
            }

            if (inDisabledSection && trimmed.startsWith("plugins")) {
                // Parse: plugins = ["a", "b", "c"]
                val match = Regex("""plugins\s*=\s*\[(.*)]""").find(trimmed)
                if (match != null) {
                    val listContent = match.groupValues[1]
                    val items = listContent.split(",")
                        .map { it.trim().trim('"').trim('\'') }
                        .filter { it.isNotEmpty() }
                    disabled.addAll(items)
                }
            }
        }

        return Config(disabledPlugins = disabled)
    }

    fun isPluginDisabled(name: String): Boolean = config.disabledPlugins.contains(name)
}
