package org.inklang.bukkit.runtime

import org.inklang.lang.ClassRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BukkitRuntimeRegistrarTest {

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
    fun `register creates player server and world globals`() {
        ClassRegistry.clear()
        val player = server.addPlayer("Player1")
        val world = server.worlds[0]

        BukkitRuntimeRegistrar.register(player, server)

        assertTrue(ClassRegistry.hasGlobal("player"))
        assertTrue(ClassRegistry.hasGlobal("server"))
        assertTrue(ClassRegistry.hasGlobal("world"))

        val globals = ClassRegistry.getAllGlobals()
        assertEquals(3, globals.size)
    }

    @Test
    fun `register is idempotent`() {
        ClassRegistry.clear()
        val player = server.addPlayer("Player1")
        val world = server.worlds[0]

        BukkitRuntimeRegistrar.register(player, server)
        BukkitRuntimeRegistrar.register(player, server)

        // Idempotent - same globals
        assertEquals(3, ClassRegistry.getAllGlobals().size)
    }
}
