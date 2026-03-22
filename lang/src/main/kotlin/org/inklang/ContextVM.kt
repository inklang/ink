package org.inklang

import org.inklang.lang.Chunk
import org.inklang.lang.VM

/**
 * A VM wrapper that routes output through a QuillContext.
 * TODO: Wire context log/print into the VM's built-in functions.
 */
class ContextVM(
    private val context: QuillContext,
    private val maxInstructions: Int = 0
) {
    private val vm = VM()

    fun execute(chunk: Chunk) {
        vm.execute(chunk)
    }
}
