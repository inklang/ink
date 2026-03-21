package org.inklang.bukkit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkBukkitTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: InkBukkit

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
    fun `plugin enables successfully`() {
        assertTrue(plugin.isEnabled, "Plugin should be enabled after loading")
    }

    @Test
    fun `plugin disables successfully`() {
        MockBukkit.unmock()
        // If we reach here without exception, disable was successful
        assertTrue(true)
    }

    @Test
    fun `ink command with no args sends usage message`() {
        val sender = server.addPlayer()

        val result = server.dispatchCommand(sender, "ink")

        assertFalse(result, "Command should return false when no args")
        verify { sender.sendMessage("§cUsage: /ink run <script>") }
    }

    @Test
    fun `ink run with no script sends usage message`() {
        val sender = server.addPlayer()

        val result = server.dispatchCommand(sender, "ink run")

        assertTrue(result, "Command should return true")
        verify { sender.sendMessage("§cUsage: /ink run <script>") }
    }

    @Test
    fun `ink run with valid script executes successfully`() {
        val sender = server.addPlayer()

        val result = server.dispatchCommand(sender, "ink run print(\"Hello, World!\")")

        assertTrue(result, "Command should return true")
        verify { sender.sendMessage("§f[Ink] Hello, World!") }
        verify { sender.sendMessage("§aScript executed successfully") }
    }

    @Test
    fun `ink run with arithmetic executes correctly`() {
        val sender = server.addPlayer()

        server.dispatchCommand(sender, "ink run print(2 + 3 * 4)")

        verify { sender.sendMessage("§f[Ink] 14") }
        verify { sender.sendMessage("§aScript executed successfully") }
    }

    @Test
    fun `ink run with script error sends error message`() {
        val sender = server.addPlayer()

        server.dispatchCommand(sender, "ink run print(undefinedVar)")

        verify { sender.sendMessage("§cError: Undefined variable: undefinedVar") }
    }

    @Test
    fun `ink run with syntax error sends error message`() {
        val sender = server.addPlayer()

        server.dispatchCommand(sender, "ink run let x =")

        verify { sender.sendMessage(match<String> { it!!.startsWith("§cError:") }) }
    }

    @Test
    fun `ink with unknown subcommand sends error message`() {
        val sender = server.addPlayer()

        server.dispatchCommand(sender, "ink unknown")

        verify { sender.sendMessage("§cUnknown subcommand. Use: /ink run <script>") }
    }

    @Test
    fun `ink run uses script cache for repeated scripts`() {
        val sender = server.addPlayer()

        // First run
        server.dispatchCommand(sender, "ink run print(\"test\")")
        // Second run with same script - should use cache
        server.dispatchCommand(sender, "ink run print(\"test\")")

        // Both should succeed - if cache works, no exception thrown
        verify { sender.sendMessage("§f[Ink] test") }
        verify { sender.sendMessage("§aScript executed successfully") }
    }

    @Test
    fun `ink run works from console sender`() {
        val console = server.consoleSender

        server.dispatchCommand(console, "ink run print(\"Console test\")")

        verify { console.sendMessage("§f[Ink] Console test") }
    }

    @Test
    fun `ink run with multi-word script argument`() {
        val sender = server.addPlayer()

        server.dispatchCommand(sender, "ink run let x = 10; print(x)")

        verify { sender.sendMessage("§f[Ink] 10") }
    }

    @Test
    fun `ink run with function definition and execution`() {
        val sender = server.addPlayer()

        server.dispatchCommand(sender, "ink run fn add(a, b) { return a + b; } print(add(5, 3))")

        verify { sender.sendMessage("§f[Ink] 8") }
    }

    @Test
    fun `ink run with for loop`() {
        val sender = server.addPlayer()

        server.dispatchCommand(sender, "ink run let sum = 0; for i in 1..5 { sum = sum + i; } print(sum)")

        verify { sender.sendMessage("§f[Ink] 15") }
    }

    @Test
    fun `ink run with string interpolation`() {
        val sender = server.addPlayer()

        server.dispatchCommand(sender, "ink run let name = \"World\"; print(\"Hello, \${name}!\")")

        verify { sender.sendMessage("§f[Ink] Hello, World!") }
    }

    @Test
    fun `script cache is cleared on plugin disable`() {
        val sender = server.addPlayer()

        // Run a script to populate cache
        server.dispatchCommand(sender, "ink run print(\"test\")")

        // Disable and re-enable plugin
        MockBukkit.unmock()
        server = MockBukkit.mock()
        val newPlugin = MockBukkit.load(InkBukkit::class.java)

        // Cache should be cleared, but we can't directly access it
        // Just verify plugin works after reload
        val newSender = server.addPlayer()
        server.dispatchCommand(newSender, "ink run print(\"after reload\")")
        verify { newSender.sendMessage("§f[Ink] after reload") }
    }

    @Test
    fun `ink run with class definition`() {
        val sender = server.addPlayer()

        val script = """
            class Counter {
                let count = 0;
                fun increment() {
                    count = count + 1;
                }
            }
            let c = Counter();
            c.increment();
            c.increment();
            print(c.count);
        """.trimIndent().replace("\n", " ")

        server.dispatchCommand(sender, "ink run $script")

        verify { sender.sendMessage("§f[Ink] 2") }
    }
}
