package org.inklang.bukkit

import org.inklang.lang.Value
import org.inklang.lang.Builtins
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for BukkitJson - tests the JSON driver directly without MockBukkit.
 */
class BukkitJsonTest {

    private val json = BukkitJson()

    @Test
    fun `parse simple object`() {
        val result = json.parse("""{"name": "Steve", "age": 42}""")

        assertTrue(result is Value.Instance)
        val entries = (result.fields["__entries"] as Value.InternalMap).entries
        assertEquals("Steve", (entries[Value.String("name")] as Value.String).value)
        assertEquals(42, (entries[Value.String("age")] as Value.Int).value)
    }

    @Test
    fun `parse simple array`() {
        val result = json.parse("""["a", "b", "c"]""")

        assertTrue(result is Value.Instance)
        val items = (result.fields["__items"] as Value.InternalList).items
        assertEquals(3, items.size)
        assertEquals("a", (items[0] as Value.String).value)
        assertEquals("b", (items[1] as Value.String).value)
        assertEquals("c", (items[2] as Value.String).value)
    }

    @Test
    fun `parse nested object`() {
        val result = json.parse("""{"player": {"name": "Herobrine", "level": 99}}""")

        assertTrue(result is Value.Instance)
        val entries = (result.fields["__entries"] as Value.InternalMap).entries
        val player = entries[Value.String("player")] as Value.Instance
        val playerEntries = (player.fields["__entries"] as Value.InternalMap).entries
        assertEquals("Herobrine", (playerEntries[Value.String("name")] as Value.String).value)
    }

    @Test
    fun `parse array of objects`() {
        val result = json.parse("""[{"name": "Alice"}, {"name": "Bob"}]""")

        assertTrue(result is Value.Instance)
        val items = (result.fields["__items"] as Value.InternalList).items
        assertEquals(2, items.size)
    }

    @Test
    fun `stringify map`() {
        val map = Builtins.newMap()
        (map.fields["__entries"] as Value.InternalMap).entries[Value.String("name")] = Value.String("Alex")
        (map.fields["__entries"] as Value.InternalMap).entries[Value.String("score")] = Value.Int(100)

        val result = json.stringify(map)

        assertTrue(result.contains("\"name\": \"Alex\""))
        assertTrue(result.contains("\"score\": 100"))
    }

    @Test
    fun `stringify array`() {
        val arr = Builtins.newArray(mutableListOf(Value.String("x"), Value.String("y")))

        val result = json.stringify(arr)

        assertEquals("[\"x\", \"y\"]", result)
    }

    @Test
    fun `stringify primitive string`() {
        val result = json.stringify(Value.String("hello"))
        assertEquals("\"hello\"", result)
    }

    @Test
    fun `stringify primitive int`() {
        val result = json.stringify(Value.Int(42))
        assertEquals("42", result)
    }

    @Test
    fun `stringify primitive boolean true`() {
        val result = json.stringify(Value.Boolean.TRUE)
        assertEquals("true", result)
    }

    @Test
    fun `stringify primitive boolean false`() {
        val result = json.stringify(Value.Boolean.FALSE)
        assertEquals("false", result)
    }

    @Test
    fun `stringify null`() {
        val result = json.stringify(Value.Null)
        assertEquals("null", result)
    }
}
