package org.inklang.grammar

enum class DynTokenType {
    KEYWORD, IDENTIFIER, INT, FLOAT, STRING,
    LBRACE, RBRACE, EOF
}

data class DynToken(
    val type: DynTokenType,
    val text: String,
    val line: Int,
    val col: Int
)

class DynamicLexer(private val keywords: Set<String>) {

    fun tokenize(source: String): List<DynToken> {
        val tokens = mutableListOf<DynToken>()
        var pos = 0
        var line = 1
        var col = 1

        while (pos < source.length) {
            val ch = source[pos]

            // Skip whitespace
            if (ch.isWhitespace()) {
                if (ch == '\n') { line++; col = 1 } else col++
                pos++
                continue
            }

            // Skip line comments
            if (ch == '/' && pos + 1 < source.length && source[pos + 1] == '/') {
                while (pos < source.length && source[pos] != '\n') pos++
                continue
            }

            // Braces
            if (ch == '{') {
                tokens.add(DynToken(DynTokenType.LBRACE, "{", line, col))
                pos++; col++; continue
            }
            if (ch == '}') {
                tokens.add(DynToken(DynTokenType.RBRACE, "}", line, col))
                pos++; col++; continue
            }

            // String literal
            if (ch == '"') {
                val start = pos
                val startCol = col
                pos++; col++
                while (pos < source.length && source[pos] != '"') {
                    if (source[pos] == '\\') { pos++; col++ }
                    pos++; col++
                }
                if (pos < source.length) { pos++; col++ } // closing quote
                tokens.add(DynToken(DynTokenType.STRING, source.substring(start, pos), line, startCol))
                continue
            }

            // Number (int or float)
            if (ch.isDigit()) {
                val start = pos
                val startCol = col
                while (pos < source.length && source[pos].isDigit()) { pos++; col++ }
                if (pos < source.length && source[pos] == '.') {
                    pos++; col++
                    while (pos < source.length && source[pos].isDigit()) { pos++; col++ }
                    tokens.add(DynToken(DynTokenType.FLOAT, source.substring(start, pos), line, startCol))
                } else {
                    tokens.add(DynToken(DynTokenType.INT, source.substring(start, pos), line, startCol))
                }
                continue
            }

            // Identifier or keyword
            if (ch.isLetter() || ch == '_') {
                val start = pos
                val startCol = col
                while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) {
                    pos++; col++
                }
                val text = source.substring(start, pos)
                val type = if (text in keywords) DynTokenType.KEYWORD else DynTokenType.IDENTIFIER
                tokens.add(DynToken(type, text, line, startCol))
                continue
            }

            throw IllegalArgumentException("Unexpected character '$ch' at line $line, col $col")
        }

        tokens.add(DynToken(DynTokenType.EOF, "", line, col))
        return tokens
    }
}
