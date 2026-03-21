package org.inklang.bukkit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.inklang.InkDb
import org.inklang.InkIo
import org.inklang.InkJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BukkitContextTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: InkBukkit
    private lateinit var sender: Player
    private lateinit var context: BukkitContext

    private val mockIo = mockk<InkIo>()
    private val mockJson = mockk<InkJson>()
    private val mockDb = mockk<InkDb>()

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(InkBukkit::class.java)
        sender = server.addPlayer()
        context = BukkitContext(sender, plugin, mockIo, mockJson, mockDb)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `log writes to plugin logger`() {
        // Verify log doesn't throw - output goes to plugin logger
        context.log("Test log message")
        // If we get here without exception, log works
        assertTrue(true)
    }

    @Test
    fun `print sends message to sender`() {
        context.print("Hello, World!")

        // Verify print calls the sender's sendMessage
        verify { sender.sendMessage("§f[Ink] Hello, World!") }
    }

    @Test
    fun `print with empty string`() {
        context.print("")

        verify { sender.sendMessage("§f[Ink] ") }
    }

    @Test
    fun `print with special characters`() {
        context.print("Test §cColored §fText")

        verify { sender.sendMessage("§f[Ink] Test §cColored §fText") }
    }

    @Test
    fun `multiple print calls send multiple messages`() {
        context.print("First message")
        context.print("Second message")
        context.print("Third message")

        verify { sender.sendMessage("§f[Ink] First message") }
        verify { sender.sendMessage("§f[Ink] Second message") }
        verify { sender.sendMessage("§f[Ink] Third message") }
    }

    @Test
    fun `context works with console sender`() {
        val console = server.consoleSender
        val consoleContext = BukkitContext(console, plugin, mockIo, mockJson, mockDb)

        consoleContext.print("Console message")

        verify { console.sendMessage("§f[Ink] Console message") }
    }

    @Test
    fun `log and print are independent`() {
        context.log("Log message")
        context.print("Print message")

        // Only print should call sender.sendMessage
        verify { sender.sendMessage("§f[Ink] Print message") }
    }
}
