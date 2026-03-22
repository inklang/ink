package org.inklang

import org.inklang.lang.Chunk
import org.inklang.lang.VM

/**
 * A compiled Ink script that can be executed with a context.
 */
class CompiledScript(private val chunk: Chunk) {
    /**
     * Execute this script with the given context.
     */
    fun execute(context: InkContext) {
        val vm = VM()
        // TODO: Wire up context (log, print, event handlers) to VM globals
        vm.execute(chunk)
    }

    /**
     * Returns the compiled chunk for direct VM execution.
     */
    fun getChunk(): Chunk = chunk
}
