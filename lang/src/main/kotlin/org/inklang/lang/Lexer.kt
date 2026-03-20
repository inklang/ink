package org.inklang.lang

private class Lexer(val source: String) {

    private var tokens: MutableList<Token> = mutableListOf()
    private var start = 0
    private var cursor = 0;
    private var line = 1;
    private var column = 0;
    private var interpolationDepth = 0;  // Track nested interpolations

    companion object {
        // Tokens that can end a statement (for ASI)
        // Note: R_BRACE is NOT included because it's a block terminator, not a statement terminator
        private val STATEMENT_ENDERS = setOf(
            TokenType.IDENTIFIER,
            TokenType.KW_INT, TokenType.KW_FLOAT, TokenType.KW_DOUBLE,
            TokenType.KW_STRING, TokenType.KW_TRUE, TokenType.KW_FALSE, TokenType.KW_NULL,
            TokenType.R_PAREN, TokenType.R_SQUARE,
            TokenType.KW_BREAK, TokenType.KW_NEXT
        )

        private val keywords = mapOf(
            "bool" to TokenType.KW_BOOL,
            "int" to TokenType.KW_INT,
            "float" to TokenType.KW_FLOAT,
            "double" to TokenType.KW_DOUBLE,
            "string" to TokenType.KW_STRING,
            "true" to TokenType.KW_TRUE,
            "false" to TokenType.KW_FALSE,
            "let" to TokenType.KW_LET,
            "const" to TokenType.KW_CONST,
            "if" to TokenType.KW_IF,
            "else" to TokenType.KW_ELSE,
            "while" to TokenType.KW_WHILE,
            "for" to TokenType.KW_FOR,
            "in" to TokenType.KW_IN,
            "fn" to TokenType.KW_FN,
            "return" to TokenType.KW_RETURN,
            "and" to TokenType.KW_AND,
            "or" to TokenType.KW_OR,
            "not" to TokenType.KW_NOT,
            "null" to TokenType.KW_NULL,
            "break" to TokenType.KW_BREAK,
            "next" to TokenType.KW_NEXT,
            "enum" to TokenType.KW_ENUM,
            "class" to TokenType.KW_CLASS,
            "import" to TokenType.KW_IMPORT,
            "from" to TokenType.KW_FROM,
            "is" to TokenType.KW_IS,
            "has" to TokenType.KW_HAS,
            "table" to TokenType.KW_TABLE,
            "key" to TokenType.KW_KEY,
            "config" to TokenType.KW_CONFIG
        )
    }

    fun tokenize(): List<Token> {
        if (tokens.isNotEmpty()) return tokens

        while (!isAtEnd()) {
            start = cursor
            val c = advance()

            when (c) {
                // Grouping & Punctuation
                '(' -> addToken(TokenType.L_PAREN)
                ')' -> addToken(TokenType.R_PAREN)
                '{' -> addToken(TokenType.L_BRACE)
                '}' -> {
                    // Check if this closes an interpolation
                    if (interpolationDepth > 0) {
                        handleInterpolationEnd()
                    } else {
                        addToken(TokenType.R_BRACE)
                    }
                }
                '[' -> addToken(TokenType.L_SQUARE)
                ']' -> addToken(TokenType.R_SQUARE)
                ',' -> addToken(TokenType.COMMA)
                '.' -> if (match('.')) addToken(TokenType.DOT_DOT) else addToken(TokenType.DOT)
                ';' -> addToken(TokenType.SEMICOLON)
                ':' -> addToken(TokenType.COLON)

                // Math & Operators
                '+' -> when {
                    match('+') -> addToken(TokenType.INCREMENT)
                    match('=') -> addToken(TokenType.ADD_EQUALS)
                    else -> addToken(TokenType.PLUS)
                }

                '-' -> when {
                    match('-') -> addToken(TokenType.DECREMENT)
                    match('=') -> addToken(TokenType.SUB_EQUALS)
                    match('>') -> addToken(TokenType.ARROW)
                    else -> addToken(TokenType.MINUS)
                }

                '*' -> when {
                    match('*') -> addToken(TokenType.POW)
                    match('=') -> addToken(TokenType.MUL_EQUALS)
                    else -> addToken(TokenType.STAR)
                }

                '/' -> when {
                    match('=') -> addToken(TokenType.DIV_EQUALS)
                    match('/') -> while (peek() != '\n' && !isAtEnd()) advance()
                    else -> addToken(TokenType.SLASH)
                }

                '%' -> if (match('=')) addToken(TokenType.MOD_EQUALS) else addToken(TokenType.PERCENT)

                '!' -> if (match('=')) addToken(TokenType.BANG_EQ) else addToken(TokenType.BANG)
                '=' -> if (match('=')) addToken(TokenType.EQ_EQ) else addToken(TokenType.ASSIGN)
                '<' -> if (match('=')) addToken(TokenType.LTE) else addToken(TokenType.LT)
                '>' -> if (match('=')) addToken(TokenType.GTE) else addToken(TokenType.GT)
                '?' -> addToken(TokenType.QUESTION)

                ' ', '\r', '\t' -> {}
                '\n' -> {
                    // Automatic Semicolon Insertion (ASI)
                    // Insert semicolon if previous token could end a statement
                    if (tokens.isNotEmpty() && tokens.last().type in STATEMENT_ENDERS) {
                        addToken(TokenType.SEMICOLON)
                    }
                    line++
                    column = 0
                }

                '"' -> string() // Custom method for string literals

                else -> {
                    when {
                        c.isDigit() -> number()
                        c.isLetter() || c == '_' -> identifier()
                    }
                }
            }
        }

        addToken(TokenType.EOF)
        return tokens
    }

    private fun string() {
        // Scan until closing quote or interpolation start
        while (peek() != '"' && !isAtEnd()) {
            // Check for interpolation start ${
            if (peek() == '$' && peekNext() == '{') {
                // Emit the string part we've accumulated so far
                if (cursor > start + 1) {
                    // We have content before the ${
                    val value = source.substring(start + 1, cursor)
                    // Create a synthetic string token (without quotes in lexeme, but we store the actual content)
                    tokens.add(Token(TokenType.KW_STRING, "\"$value\"", line, column - value.length))
                }

                // Emit INTERPOLATION_START
                advance() // consume $
                advance() // consume {
                tokens.add(Token(TokenType.INTERPOLATION_START, "\${", line, column - 1))
                interpolationDepth++
                return  // Let normal tokenization handle the expression inside
            }

            if (peek() == '\n') {
                line++
                column = 0
            }
            // Handle escape sequences
            if (peek() == '\\') {
                advance() // consume backslash
                if (!isAtEnd()) advance() // consume escaped char
            } else {
                advance()
            }
        }

        if (isAtEnd()) {
            // Unterminated string - we'll report this as an error
            // For now, just return
            return
        }

        // Closing quote
        advance()

        // Trim the surrounding quotes
        val value = source.substring(start + 1, cursor - 1)
        addToken(TokenType.KW_STRING)
    }

    private fun handleInterpolationEnd() {
        // This is called when we see } and interpolationDepth > 0
        // Note: The } has already been consumed by advance() in the main loop
        tokens.add(Token(TokenType.INTERPOLATION_END, "}", line, column - 1))
        interpolationDepth--

        // After closing interpolation, we might have more string content
        // Check if the next character is a quote (end of string) or more content
        if (peek() == '"') {
            // End of the interpolated string
            advance() // consume closing quote
            // The string is complete - no more parts
        } else {
            // Continue scanning string content after the interpolation
            // We need to scan until the next ${ or closing "
            scanStringTail()
        }
    }

    private fun scanStringTail() {
        // Continue scanning string content after an interpolation
        // This is like string() but we're already inside the string
        start = cursor  // Start at current position (after the closing })

        while (peek() != '"' && !isAtEnd()) {
            // Check for another interpolation
            if (peek() == '$' && peekNext() == '{') {
                // Emit the string part we've accumulated
                if (cursor > start) {
                    val value = source.substring(start, cursor)
                    tokens.add(Token(TokenType.KW_STRING, "\"$value\"", line, column - value.length))
                }

                // Emit INTERPOLATION_START
                advance() // consume $
                advance() // consume {
                tokens.add(Token(TokenType.INTERPOLATION_START, "\${", line, column - 1))
                interpolationDepth++
                return
            }

            if (peek() == '\n') {
                line++
                column = 0
            }
            // Handle escape sequences
            if (peek() == '\\') {
                advance() // consume backslash
                if (!isAtEnd()) advance() // consume escaped char
            } else {
                advance()
            }
        }

        if (isAtEnd()) {
            // Unterminated string
            return
        }

        // Closing quote
        advance()

        // Emit the final string part (may be empty)
        if (cursor > start + 1) {
            val value = source.substring(start, cursor - 1)  // cursor - 1 to exclude closing quote
            tokens.add(Token(TokenType.KW_STRING, "\"$value\"", line, column - value.length))
        }
    }

    private fun identifier() {
        while (peek().isLetterOrDigit() || peek() == '_') advance()
        val text = source.substring(start, cursor)
        addToken(keywords[text] ?: TokenType.IDENTIFIER)
    }

    private fun number() {
        while (peek().isDigit()) advance()
        if (peek() == '.' && peekNext().isDigit()) {
            advance()
            while (peek().isDigit()) advance()
            addToken(TokenType.KW_DOUBLE)
        } else {
            addToken(TokenType.KW_INT)
        }
    }

    private fun advance(): Char {
        val c = source[cursor++]
        column++
        return c
    }

    private fun match(expected: Char): Boolean {
        if (isAtEnd() || source[cursor] != expected) return false
        cursor++
        column++
        return true
    }

    private fun peek() = if (isAtEnd()) '\u0000' else source[cursor]
    private fun peekNext() = if (cursor + 1 >= source.length) '\u0000' else source[cursor + 1]
    private fun isAtEnd() = cursor >= source.length

    private fun addToken(type: TokenType) {
        val text = source.substring(start, cursor)
        tokens.add(Token(type, text, line, column - text.length))
    }
}

fun tokenize(source: String): List<Token> {
    return Lexer(source).tokenize()
}