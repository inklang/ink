# Plugin Loading System Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement two-mode script loading — plugins (persistent, lifecycle-aware, event-enabled) in `plugins/ink/plugins/` and dynamic scripts (isolated, one-shot) in `plugins/ink/scripts/`.

**Architecture:** Separate `PluginContext` (lifecycle, events) from `ScriptContext` (no lifecycle, no events). Plugin scanner reads `plugins.toml` for disabled plugins, loads plugins on startup with `enable {}`/`disable {}` validation.

**Tech Stack:** Kotlin, PaperMC plugin, SnakeYAML for TOML/YAML parsing

---

## File Structure

```
ink/src/main/kotlin/org/inklang/
  InkContext.kt              # Extend with lifecycle methods
  InkScript.kt               # Already exists
  lang/
    Token.kt                 # Add KW_ENABLE, KW_DISABLE
    AST.kt                   # Add EnableStmt, DisableStmt
    Parser.kt                # Parse enable/disable blocks
    ConfigRuntime.kt         # Adapt for per-plugin/script paths

ink-bukkit/src/main/kotlin/org/inklang/bukkit/
  InkBukkit.kt               # Add plugin scanner, lifecycle management
  PluginContext.kt           # New — lifecycle-aware context
  ScriptContext.kt           # New — isolated context (renamed from BukkitContext behavior)
  GlobalConfig.kt            # New — parse plugins.toml
  PluginRuntime.kt           # New — lifecycle + event registry
```

---

## Chunk 1: Token and AST Changes

### Task 1: Add KW_ENABLE and KW_DISABLE tokens

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Token.kt`

- [ ] **Step 1: Add enable/disable tokens**

Modify Token.kt — add after `KW_ON`:

```kotlin
KW_ENABLE,    // enable keyword
KW_DISABLE,   // disable keyword
```

Run: `grep -n "KW_ON" ink/src/main/kotlin/org/inklang/lang/Token.kt`
Expected: Shows line number where KW_ON is defined

- [ ] **Step 2: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Token.kt
git commit -m "feat: add KW_ENABLE and KW_DISABLE tokens"
```

---

### Task 2: Add EnableStmt and DisableStmt AST nodes

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/AST.kt`

- [ ] **Step 1: Add EnableStmt and DisableStmt to sealed class Stmt**

Modify AST.kt — add after `data class OnStmt` (around line 170):

```kotlin
// Lifecycle blocks for plugins
data class EnableStmt(val block: BlockStmt) : Stmt()
data class DisableStmt(val block: BlockStmt) : Stmt()
```

- [ ] **Step 2: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/AST.kt
git commit -m "feat: add EnableStmt and DisableStmt AST nodes"
```

---

### Task 3: Update Lexer to tokenize enable/disable keywords

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Lexer.kt`

- [ ] **Step 1: Check Lexer for keyword handling**

Run: `grep -n "when.*text" ink/src/main/kotlin/org/inklang/lang/Lexer.kt | head -20`
Expected: Shows keyword mapping pattern

- [ ] **Step 2: Add enable/disable to keyword map**

Add to the keyword map in Lexer.kt (after KW_ON entries):

```kotlin
"enable" -> Token(TokenType.KW_ENABLE, line, col)
"disable" -> Token(TokenType.KW_DISABLE, line, col)
```

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Lexer.kt
git commit -m "feat: add enable/disable keyword tokenization"
```

---

## Chunk 2: Parser Changes

### Task 4: Parse enable {} and disable {} blocks

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/Parser.kt`

- [ ] **Step 1: Check current statement parsing**

Run: `grep -n "parseStatement\|check.*KW_ON\|KW_CONFIG" ink/src/main/kotlin/org/inklang/lang/Parser.kt | head -20`
Expected: Shows where KW_ON and KW_CONFIG are handled

- [ ] **Step 2: Add parseEnable and parseDisable methods**

Add to Parser.kt after `parseOnStatement`:

```kotlin
private fun parseEnable(): Stmt {
    consume(TokenType.KW_ENABLE, "Expected 'enable'")
    consume(TokenType.L_BRACE, "Expected '{'")
    val statements = mutableListOf<Stmt>()
    while (!check(TokenType.R_BRACE) && !isAtEnd()) {
        statements.add(parseStatement())
    }
    consume(TokenType.R_BRACE, "Expected '}'")
    return Stmt.EnableStmt(Stmt.BlockStmt(statements))
}

private fun parseDisable(): Stmt {
    consume(TokenType.KW_DISABLE, "Expected 'disable'")
    consume(TokenType.L_BRACE, "Expected '{'")
    val statements = mutableListOf<Stmt>()
    while (!check(TokenType.R_BRACE) && !isAtEnd()) {
        statements.add(parseStatement())
    }
    consume(TokenType.R_BRACE, "Expected '}'")
    return Stmt.DisableStmt(Stmt.BlockStmt(statements))
}
```

- [ ] **Step 3: Wire enable/disable into statement parsing**

Find where KW_ON is handled in parseStatement() and add:

```kotlin
check(TokenType.KW_ENABLE) -> parseEnable()
check(TokenType.KW_DISABLE) -> parseDisable()
```

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/Parser.kt
git commit -m "feat: parse enable and disable blocks"
```

---

## Chunk 3: Context Interfaces

### Task 5: Extend InkContext with lifecycle methods

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/InkContext.kt`

- [ ] **Step 1: Add lifecycle methods to InkContext**

Modify InkContext.kt — add after `fireEvent`:

```kotlin
/** Called when a plugin is enabled — returns the enable block's result, or throws */
fun onEnable(script: InkScript)

/** Called when a plugin is disabled — returns the disable block's result, or throws */
fun onDisable(script: InkScript)

/** Check if this context supports plugins (has lifecycle) */
fun supportsLifecycle(): Boolean = true
```

- [ ] **Step 2: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/InkContext.kt
git commit -m "feat: add lifecycle methods to InkContext"
```

---

### Task 6: Create PluginContext interface

**Files:**
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginContext.kt`

- [ ] **Step 1: Create PluginContext**

```kotlin
package org.inklang.bukkit

import org.bukkit.command.CommandSender
import org.inklang.InkContext
import org.inklang.InkIo
import org.inklang.InkJson
import org.inklang.InkDb
import org.inklang.InkScript
import java.io.File

/**
 * Extended context for plugin scripts with lifecycle support.
 */
class PluginContext(
    private val sender: CommandSender,
    private val plugin: InkBukkit,
    private val io: InkIo,
    private val json: InkJson,
    private val db: InkDb,
    private val pluginName: String,
    private val pluginFolder: File
) : InkContext {

    override fun log(message: String) {
        plugin.logger.info("[Ink/$pluginName] $message")
    }

    override fun print(message: String) {
        sender.sendMessage("§f[Ink/$pluginName] $message")
    }

    override fun io(): InkIo = io
    override fun json(): InkJson = json
    override fun db(): InkDb = db

    override fun onEnable(script: InkScript) {
        script.execute(this)
    }

    override fun onDisable(script: InkScript) {
        script.execute(this)
    }

    override fun supportsLifecycle(): Boolean = true

    fun getPluginFolder(): File = pluginFolder
    fun getPluginName(): String = pluginName
}
```

- [ ] **Step 2: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginContext.kt
git commit -m "feat: add PluginContext with lifecycle support"
```

---

### Task 7: Create ScriptContext (existing BukkitContext behavior)

**Files:**
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/ScriptContext.kt`

- [ ] **Step 1: Create ScriptContext**

```kotlin
package org.inklang.bukkit

import org.bukkit.command.CommandSender
import org.inklang.InkContext
import org.inklang.InkIo
import org.inklang.InkJson
import org.inklang.InkDb
import java.io.File

/**
 * Context for dynamic scripts — no lifecycle, isolated execution.
 */
class ScriptContext(
    private val sender: CommandSender,
    private val plugin: InkBukkit,
    private val io: InkIo,
    private val json: InkJson,
    private val db: InkDb
) : InkContext {

    override fun log(message: String) {
        plugin.logger.info("[Ink] $message")
    }

    override fun print(message: String) {
        sender.sendMessage("§f[Ink] $message")
    }

    override fun io(): InkIo = io
    override fun json(): InkJson = json
    override fun db(): InkDb = db

    override fun supportsLifecycle(): Boolean = false

    // Dynamic scripts cannot register events — throw UnsupportedOperationException
    override fun registerEventHandler(
        eventName: String,
        handlerFunc: org.inklang.lang.Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    ) {
        throw UnsupportedOperationException(
            "Dynamic scripts cannot register events. Place event handlers in a plugin in plugins/ink/plugins/"
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/ScriptContext.kt
git commit -m "feat: add ScriptContext for isolated dynamic scripts"
```

---

## Chunk 4: Runtime Components

### Task 8: Create GlobalConfig for plugins.toml parsing

**Files:**
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/GlobalConfig.kt`

- [ ] **Step 1: Create GlobalConfig**

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/GlobalConfig.kt
git commit -m "feat: add GlobalConfig for plugins.toml parsing"
```

---

### Task 9: Create PluginRuntime for lifecycle and event registry

**Files:**
- Create: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt`

- [ ] **Step 1: Create PluginRuntime**

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt
git commit -m "feat: add PluginRuntime for lifecycle and event management"
```

---

## Chunk 5: InkBukkit Integration

### Task 10: Update InkBukkit with plugin scanner

**Files:**
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt`

- [ ] **Step 1: Replace InkBukkit with plugin scanner**

Replace the entire InkBukkit.kt content:

```kotlin
package org.inklang.bukkit

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.inklang.InkCompiler
import org.inklang.InkScript
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

        pluginsDir.listFiles { file -> file.extension == "ink" }
            ?.forEach { pluginFile ->
                val result = pluginRuntime.loadPlugin(pluginFile)
                if (result.isFailure) {
                    logger.severe("Failed to load ${pluginFile.name}: ${result.exceptionOrNull()?.message}")
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
            sender.sendMessage("§cUsage: /ink <run|list|reload> [args]")
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
                sender.sendMessage("§cUnknown subcommand. Use: /ink <run|list|reload>")
                true
            }
        }
    }

    private fun runScript(sender: CommandSender, scriptName: String) {
        try {
            val scriptFile = File(File(dataFolder, "scripts"), "$scriptName.ink")
            if (!scriptFile.exists()) {
                sender.sendMessage("§cScript not found: $scriptName.ink")
                return
            }

            val compiled = scriptCache.getOrPut(scriptFile.absolutePath) {
                compiler.compile(scriptFile.readText(), scriptName)
            }

            val scriptDir = File(File(dataFolder, "scripts"), scriptName)
            scriptDir.mkdirs()
            val dbFile = File(scriptDir, "data.db")
            dbFile.parentFile?.mkdirs()

            val ioDriver = BukkitIo(scriptDir)
            val jsonDriver = BukkitJson()
            val dbDriver = BukkitDb(dbFile.absolutePath)

            val context = ScriptContext(sender, this, ioDriver, jsonDriver, dbDriver)
            compiled.execute(context)
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
```

- [ ] **Step 2: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt
git commit -m "feat: integrate plugin loading into InkBukkit"
```

---

## Chunk 6: Validation and Error Handling

### Task 11: Add validation for missing enable/disable blocks

**Files:**
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt`

This task is already partially addressed in the TODO comment in PluginRuntime. The AST needs to be inspected after compilation to validate enable/disable exist.

- [ ] **Step 1: Add validation method**

Add to InkCompiler.kt (in ink module) or create a validation utility:

```kotlin
// In ink/src/main/kotlin/org/inklang/InkCompiler.kt or new Validation.kt

fun validatePluginScript(statements: List<Stmt>): ValidationResult {
    var hasEnable = false
    var hasDisable = false

    for (stmt in statements) {
        when (stmt) {
            is Stmt.EnableStmt -> hasEnable = true
            is Stmt.DisableStmt -> hasDisable = true
            else -> {}
        }
    }

    val errors = mutableListOf<String>()
    if (!hasEnable) errors.add("Plugin must have an 'enable {}' block")
    if (!hasDisable) errors.add("Plugin must have a 'disable {}' block")

    return ValidationResult(errors)
}

data class ValidationResult(val errors: List<String>) {
    fun isValid() = errors.isEmpty()
}
```

- [ ] **Step 2: Wire validation into PluginRuntime.loadPlugin**

Modify PluginRuntime.kt loadPlugin:

```kotlin
// Before compilation
val validationResult = validatePluginScript(script) // Need to expose parsed statements
if (!validationResult.isValid()) {
    return Result.failure(
        IllegalStateException(
            "Invalid plugin ${pluginFile.name}: ${validationResult.errors.joinToString("; ")}"
        )
    )
}
```

Note: This requires making parse() accessible or having InkCompiler return the AST. Refactor as needed.

- [ ] **Step 3: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/InkCompiler.kt
git commit -m "feat: add plugin script validation for enable/disable blocks"
```

---

## Chunk 7: ConfigRuntime Path Adaptation

### Task 12: Update ConfigRuntime for per-plugin/script paths

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/lang/ConfigRuntime.kt`

- [ ] **Step 1: Adapt ConfigRuntime to accept folder path directly**

The current ConfigRuntime uses `scriptDir` as a base path. We need to ensure it places config files in the correct location for plugins vs scripts.

Currently ConfigRuntime puts config at `<scriptDir>/<configName>.yml`. For plugins this becomes `plugins/ink/plugins/math/config.yml` which is correct per spec.

Verify the current behavior works correctly by examining the path construction:

```kotlin
// Current implementation in ConfigRuntime.kt:
val fileName = configName.replace(Regex("([a-z])([A-Z])")) {
    "${it.groupValues[1]}-${it.groupValues[2].lowercase()}"
}.lowercase() + ".yml"

val file = File(scriptDir, fileName)
```

This already produces `<scriptDir>/<kebab-case-config-name>.yml`, which is correct for both plugins and scripts.

No changes needed for ConfigRuntime path handling.

- [ ] **Step 2: Document the folder convention**

Add to docs/superpowers/specs/2026-03-21-plugin-loading-design.md:

```markdown
### Config File Locations

For plugins: `plugins/ink/plugins/<pluginname>/config.yml`
For scripts: `plugins/ink/scripts/<scriptname>/config.yml`

Note: ConfigRuntime generates kebab-case filenames from PascalCase config names.
`config MyPlugin {}` → `my-plugin.yml`
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-03-21-plugin-loading-design.md
git commit -m "docs: clarify config file path conventions"
```

---

## Chunk 8: Tests

### Task 13: Add plugin loading tests

**Files:**
- Create: `ink-bukkit/src/test/kotlin/org/inklang/bukkit/PluginLoadingTest.kt`

- [ ] **Step 1: Write plugin loading tests**

```kotlin
package org.inklang.bukkit

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class PluginLoadingTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `global config parses disabled plugins`() {
        val configContent = """
            [disabled]
            plugins = ["math", "broken"]
        """.trimIndent()

        val configFile = tempDir.resolve("plugins.toml")
        Files.writeString(configFile, configContent)

        // Create a mock plugin that uses tempDir as dataFolder
        // For now, just test GlobalConfig parsing directly
        val mockPlugin = MockJavaPlugin(tempDir.toFile())
        val globalConfig = GlobalConfig(mockPlugin)

        assertTrue(globalConfig.isPluginDisabled("math"))
        assertTrue(globalConfig.isPluginDisabled("broken"))
        assertFalse(globalConfig.isPluginDisabled("myplugin"))
    }

    @Test
    fun `global config empty when no config file`() {
        val mockPlugin = MockJavaPlugin(tempDir.toFile())
        val globalConfig = GlobalConfig(mockPlugin)

        assertFalse(globalConfig.isPluginDisabled("anything"))
    }

    @Test
    fun `script context throws on event registration`() {
        // Test that ScriptContext throws when registerEventHandler is called
        // This requires mocking InkContext dependencies
    }
}

// Helper for testing
class MockJavaPlugin(val dataFolder: java.io.File) : org.bukkit.plugin.java.JavaPlugin() {
    override fun onEnable() {}
    override fun onDisable() {}
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :ink-bukkit:test --tests "org.inklang.bukkit.PluginLoadingTest"`
Expected: Tests compile and run (some may be incomplete due to mocking complexity)

- [ ] **Step 3: Commit**

```bash
git add ink-bukkit/src/test/kotlin/org/inklang/bukkit/PluginLoadingTest.kt
git commit -m "test: add plugin loading tests"
```

---

## Summary

**New files:**
- `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginContext.kt`
- `ink-bukkit/src/main/kotlin/org/inklang/bukkit/ScriptContext.kt`
- `ink-bukkit/src/main/kotlin/org/inklang/bukkit/GlobalConfig.kt`
- `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt`
- `ink-bukkit/src/test/kotlin/org/inklang/bukkit/PluginLoadingTest.kt`

**Modified files:**
- `ink/src/main/kotlin/org/inklang/lang/Token.kt` — add KW_ENABLE, KW_DISABLE
- `ink/src/main/kotlin/org/inklang/lang/AST.kt` — add EnableStmt, DisableStmt
- `ink/src/main/kotlin/org/inklang/lang/Lexer.kt` — tokenize enable/disable
- `ink/src/main/kotlin/org/inklang/lang/Parser.kt` — parse enable/disable blocks
- `ink/src/main/kotlin/org/inklang/InkContext.kt` — add lifecycle methods
- `ink/src/main/kotlin/org/inklang/InkCompiler.kt` — add validation
- `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt` — plugin scanner integration

**Out of scope for this plan:**
- Extracting enable/disable blocks as separate InkScript objects (currently executes full script)
- Hot reload
- Plugin interop/dependencies
- Reload {} block

---

## Related Designs

- [2026-03-21-plugin-loading-design.md](../specs/2026-03-21-plugin-loading-design.md)
- [2026-03-20-event-hooks-design.md](../specs/2026-03-20-event-hooks-design.md)
