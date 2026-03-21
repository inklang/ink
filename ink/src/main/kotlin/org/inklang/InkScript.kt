package org.inklang

import org.inklang.lang.Chunk
import org.inklang.lang.ConfigFieldDef
import org.inklang.lang.ConfigRuntime
import org.inklang.lang.Value

/**
 * A compiled Quill script that can be executed with a context.
 */
class InkScript(
    val name: String,
    private val chunk: Chunk,
    private val configDefinitions: Map<String, List<ConfigFieldDef>> = emptyMap()
) {
    /**
     * Pre-load all configs declared in this script from YAML files.
     * @param scriptDir The directory to look for config YAML files (e.g. "plugins/ink/scripts/")
     * @return Map of config name → loaded Value.Instance
     */
    fun preloadConfigs(scriptDir: String): Map<String, Value.Instance> {
        return configDefinitions.mapValues { (name, fields) ->
            ConfigRuntime.loadConfig(name, fields, scriptDir)
        }
    }

    /**
     * Execute this script with the given context.
     * @param context The context providing log/print implementations
     * @param maxInstructions Maximum instructions to execute before timeout (0 = no limit)
     * @throws ScriptException if a runtime error occurs
     * @throws ScriptTimeoutException if maxInstructions is exceeded
     */
    fun execute(context: InkContext, maxInstructions: Int = 0) {
        val vm = ContextVM(context, maxInstructions)
        vm.execute(chunk)
    }
}
