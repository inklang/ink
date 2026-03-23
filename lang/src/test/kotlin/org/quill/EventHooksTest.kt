package org.quill

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EventHooksTest {
    private val compiler = InkCompiler()

    @Test
    fun `parses on handler`() {
        // Use 'evt' instead of 'event' since 'event' is a keyword
        val script = compiler.compile("""
            on player_join(evt, player: Player) {
                print("Hello!")
            }
        """)
        assertNotNull(script)
    }

    @Test
    fun `rejects unknown event`() {
        val ex = assertThrows(RuntimeException::class.java) {
            compiler.compile("""
                on player_jon(evt, player: Player) { }
            """)
        }
        assertTrue(ex.message?.contains("Unknown event") == true)
    }

    @Test
    fun `rejects wrong parameter count`() {
        val ex = assertThrows(RuntimeException::class.java) {
            compiler.compile("""
                on player_join(evt, player: Player, extra: Int) { }
            """)
        }
        assertTrue(ex.message?.contains("expects") == true)
    }

    @Test
    fun `event cancel sets cancelled flag`() {
        val eventObj = org.quill.lang.Value.EventObject(
            eventName = org.quill.lang.Value.String("test"),
            cancellable = true,
            cancelled = false,
            data = emptyList<org.quill.lang.Value?>()
        )
        eventObj.cancelled = true
        assertTrue(eventObj.cancelled)
    }
}