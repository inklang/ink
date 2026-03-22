package org.inklang.peg

import org.inklang.peg.util.SourcePosition

/**
 * Lightweight token for position tracking during PEG parsing.
 * Contains the token type, the actual text (lexeme), and the source position.
 */
data class PegToken(
    val type: String,
    val lexeme: String,
    val position: SourcePosition
) {
    /**
     * Returns the source position of this token.
     * Note: The spec mentioned this should reference 'position' not 'offset'.
     */
    val sourcePosition: SourcePosition
        get() = position
}
