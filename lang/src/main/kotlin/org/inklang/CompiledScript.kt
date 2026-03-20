package org.inklang

import org.inklang.lang.Chunk

/**
 * A compiled Quill script that can be executed with a context.
 */
class CompiledScript(
    val name: String,
    private val chunk: Chunk
) {
    /**
     * Execute this script with the given context.
     * @param context The context providing log/print implementations
     * @param maxInstructions Maximum instructions to execute before timeout (0 = no limit)
     * @throws ScriptException if a runtime error occurs
     * @throws ScriptTimeoutException if maxInstructions is exceeded
     */
    fun execute(context: QuillContext, maxInstructions: Int = 0) {
        val vm = ContextVM(context, maxInstructions)
        vm.execute(chunk)
    }
}
