package org.inklang.bukkit.runtime

import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerClassTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `createDescriptor returns empty methods when sender is not Player`() {
        val sender = server.consoleSender

        val descriptor = PlayerClass.createDescriptor(sender, server)

        assertEquals("Player", descriptor.name)
        assertTrue(descriptor.methods.isEmpty())
    }

    @Test
    fun `createDescriptor returns methods when sender is Player`() {
        val player = server.addPlayer()

        val descriptor = PlayerClass.createDescriptor(player, server)

        assertEquals("Player", descriptor.name)
        assertTrue(descriptor.methods.isNotEmpty())
    }

    @Test
    fun `createDescriptor includes name method`() {
        val player = server.addPlayer("TestPlayer")

        val descriptor = PlayerClass.createDescriptor(player, server)

        assertTrue(descriptor.methods.containsKey("name"))
    }

    @Test
    fun `createDescriptor includes health method`() {
        val player = server.addPlayer()

        val descriptor = PlayerClass.createDescriptor(player, server)

        assertTrue(descriptor.methods.containsKey("health"))
    }

    @Test
    fun `createDescriptor includes world method`() {
        val player = server.addPlayer()

        val descriptor = PlayerClass.createDescriptor(player, server)

        assertTrue(descriptor.methods.containsKey("world"))
    }
}