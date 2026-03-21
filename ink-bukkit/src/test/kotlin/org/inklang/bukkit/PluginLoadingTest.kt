package org.inklang.bukkit

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
}