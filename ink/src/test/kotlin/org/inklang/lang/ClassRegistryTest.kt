package org.inklang.lang

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ClassRegistryTest {

    @Test
    fun `registerGlobal stores descriptor and getGlobal retrieves it`() {
        ClassRegistry.clear()
        val desc = ClassDescriptor("Test", null, emptyMap())
        ClassRegistry.registerGlobal("test", desc)
        assertEquals(desc, ClassRegistry.getGlobal("test"))
    }

    @Test
    fun `getGlobal returns null for unregistered name`() {
        ClassRegistry.clear()
        assertNull(ClassRegistry.getGlobal("nonexistent"))
    }

    @Test
    fun `hasGlobal returns correct boolean`() {
        ClassRegistry.clear()
        val desc = ClassDescriptor("Test", null, emptyMap())
        ClassRegistry.registerGlobal("test", desc)
        assertTrue(ClassRegistry.hasGlobal("test"))
        assertFalse(ClassRegistry.hasGlobal("other"))
    }

    @Test
    fun `getAllGlobals returns all as Value Instances`() {
        ClassRegistry.clear()
        val desc1 = ClassDescriptor("A", null, emptyMap())
        val desc2 = ClassDescriptor("B", null, emptyMap())
        ClassRegistry.registerGlobal("a", desc1)
        ClassRegistry.registerGlobal("b", desc2)
        val globals = ClassRegistry.getAllGlobals()
        assertEquals(2, globals.size)
        assertTrue(globals["a"] is Value.Instance)
        assertTrue(globals["b"] is Value.Instance)
        assertEquals("A", (globals["a"] as Value.Instance).clazz.name)
    }

    @Test
    fun `registerGlobal overwrites previous registration`() {
        ClassRegistry.clear()
        val desc1 = ClassDescriptor("V1", null, emptyMap())
        val desc2 = ClassDescriptor("V2", null, emptyMap())
        ClassRegistry.registerGlobal("test", desc1)
        ClassRegistry.registerGlobal("test", desc2)
        assertEquals("V2", ClassRegistry.getGlobal("test")!!.name)
    }

    @Test
    fun `clear removes all registrations`() {
        ClassRegistry.clear()
        ClassRegistry.registerGlobal("test", ClassDescriptor("Test", null, emptyMap()))
        ClassRegistry.clear()
        assertFalse(ClassRegistry.hasGlobal("test"))
    }
}