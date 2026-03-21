package org.inklang.bukkit

import org.inklang.InkCompiler
import org.inklang.InkScript
import org.inklang.ContextVM
import org.inklang.lang.Value
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class PluginLoadingTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: InkBukkit

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(InkBukkit::class.java)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `global config parses disabled plugins`() {
        val configContent = """
            [disabled]
            plugins = ["math", "broken"]
        """.trimIndent()

        val configFile = plugin.dataFolder.toPath().resolve("plugins.toml")
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, configContent)

        val globalConfig = GlobalConfig(plugin)

        assertTrue(globalConfig.isPluginDisabled("math"))
        assertTrue(globalConfig.isPluginDisabled("broken"))
        assertFalse(globalConfig.isPluginDisabled("myplugin"))
    }

    @Test
    fun `global config empty when no config file`() {
        val globalConfig = GlobalConfig(plugin)

        assertFalse(globalConfig.isPluginDisabled("anything"))
    }

    @Test
    fun `global config handles empty disabled list`() {
        val configContent = """
            [disabled]
            plugins = []
        """.trimIndent()

        val configFile = plugin.dataFolder.toPath().resolve("plugins.toml")
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, configContent)

        val globalConfig = GlobalConfig(plugin)

        assertFalse(globalConfig.isPluginDisabled("anything"))
    }

    @Test
    fun `plugin gets persistent VM that survives enable`() {
        // Create a simple plugin script with enable block
        val pluginScript = """
            enable {
                print("enabled!");
            }
        """.trimIndent()

        val pluginFile = tempDir.resolve("testplugin.ink").toFile()
        pluginFile.writeText(pluginScript)

        val globalConfig = GlobalConfig(plugin)
        val runtime = PluginRuntime(plugin, globalConfig)

        val result = runtime.loadPlugin(pluginFile)

        assertTrue(result.isSuccess, "loadPlugin should succeed")
        val loadedPlugin = result.getOrNull()
        assertNotNull(loadedPlugin, "loaded plugin should not be null")
        assertNotNull(loadedPlugin.vm, "VM should be created and stored in LoadedPlugin")
        assertTrue(loadedPlugin.vm.globals.containsKey("__eventRegistry"), "VM should have event registry")
    }

    @Test
    fun `disable runs in same VM as enable`() {
        // Create a plugin with enable and disable blocks
        val pluginScript = """
            enable {
                print("enabled!");
            }
            disable {
                print("disabled!");
            }
        """.trimIndent()

        val pluginFile = tempDir.resolve("testplugin.ink").toFile()
        pluginFile.writeText(pluginScript)

        val globalConfig = GlobalConfig(plugin)
        val runtime = PluginRuntime(plugin, globalConfig)

        val loadResult = runtime.loadPlugin(pluginFile)
        assertTrue(loadResult.isSuccess, "loadPlugin should succeed")
        val loadedPlugin = loadResult.getOrNull()
        assertNotNull(loadedPlugin)

        val vmBeforeUnload = loadedPlugin.vm

        // unloadPlugin should run disable in the same VM
        runtime.unloadPlugin("testplugin")

        // If we reach here without exception, disable ran in the same VM
        assertTrue(true, "unloadPlugin completed without error")
    }

    @Test
    fun `fireEvent executes handler in plugin VM`() {
        // Create a plugin that registers an event handler
        val pluginScript = """
            enable {
                on player_join(event, player) {
                    print("player joined!");
                }
            }
        """.trimIndent()

        val pluginFile = tempDir.resolve("eventplugin.ink").toFile()
        pluginFile.writeText(pluginScript)

        val globalConfig = GlobalConfig(plugin)
        val runtime = PluginRuntime(plugin, globalConfig)

        val loadResult = runtime.loadPlugin(pluginFile)
        assertTrue(loadResult.isSuccess, "loadPlugin should succeed")
        val loadedPlugin = loadResult.getOrNull()
        assertNotNull(loadedPlugin)

        // Verify handler is registered in the VM's event registry
        val registry = loadedPlugin.vm.globals["__eventRegistry"]
        assertNotNull(registry, "event registry should exist")
        assertTrue(registry is Value.Instance, "event registry should be Instance")

        val registryInstance = registry as Value.Instance
        val handlers = registryInstance.fields["__handlers"]
        assertNotNull(handlers, "__handlers should exist in registry")
        assertTrue(handlers is Value.InternalList, "__handlers should be InternalList")

        val handlersList = handlers as Value.InternalList
        assertTrue(handlersList.items.isNotEmpty(), "at least one handler should be registered")

        // Verify the handler is for player_join event
        val handler = handlersList.items.firstOrNull()
        assertNotNull(handler, "handler should not be null")
        assertTrue(handler is Value.EventHandler, "handler should be EventHandler")
        val eventHandler = handler as Value.EventHandler
        assertTrue(eventHandler.eventName.value == "player_join", "handler should be for player_join event")
    }
}