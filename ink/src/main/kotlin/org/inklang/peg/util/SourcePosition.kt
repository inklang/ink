package org.inklang.peg.util

/**
 * Represents a position in source code with line and column information.
 */
data class SourcePosition(
    val offset: Int,
    val line: Int,
    val column: Int
) {
    companion object {
        /**
         * Calculate SourcePosition from an offset into the input string.
         * Handles offset beyond input length with bounds check.
         */
        fun fromOffset(input: String, offset: Int): SourcePosition {
            val safeOffset = offset.coerceAtMost(input.length.coerceAtLeast(0))
            var line = 1
            var column = 1

            for (i in 0 until safeOffset) {
                if (input[i] == '\n') {
                    line++
                    column = 1
                } else {
                    column++
                }
            }

            return SourcePosition(offset, line, column)
        }
    }
}
