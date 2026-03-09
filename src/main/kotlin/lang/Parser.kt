package org.aincraft.lang

class Parser(private val tokens: List<Token>) {
    private var cursor = 0

    companion object {
        // Higher number = tighter binding
        val weights = mapOf(
            TokenType.ASSIGN to 10,
            TokenType.ADD_EQUALS to 10, TokenType.SUB_EQUALS to 10,
            TokenType.MUL_EQUALS to 10, TokenType.DIV_EQUALS to 10, TokenType.MOD_EQUALS to 10,
            TokenType.KW_OR to 20,
            TokenType.KW_AND to 30,
            TokenType.KW_IS to 35,  // type checking, lower than and/or
            TokenType.EQ_EQ to 40, TokenType.BANG_EQ to 40,
            TokenType.LT to 50, TokenType.GT to 50, TokenType.LTE to 50, TokenType.GTE to 50,
            TokenType.DOT_DOT to 55,
            TokenType.PLUS to 60, TokenType.MINUS to 60,
            TokenType.STAR to 70, TokenType.SLASH to 70, TokenType.PERCENT to 70,
            TokenType.POW to 80
        )

        val ASSIGN_OPS = setOf(
            TokenType.ASSIGN,
            TokenType.ADD_EQUALS, TokenType.SUB_EQUALS,
            TokenType.MUL_EQUALS, TokenType.DIV_EQUALS,
            TokenType.MOD_EQUALS
        )
    }

    fun parse(): List<Stmt> {
        val stmts = mutableListOf<Stmt>()
        while (!isAtEnd()) {
            stmts.add(parseStmt())
        }
        return stmts
    }

    private fun parseStmt(): Stmt {
        return when {
            check(TokenType.KW_IMPORT) -> parseImport()
            check(TokenType.KW_LET) || check(TokenType.KW_CONST) -> parseVar()
            check(TokenType.KW_IF) -> parseIf()
            check(TokenType.KW_CLASS) -> parseClass()
            check(TokenType.KW_FN) -> parseFunc()
            check(TokenType.KW_RETURN) -> parseReturn()
            check(TokenType.KW_BREAK) -> {
                advance();
                Stmt.BreakStmt
            }
            check(TokenType.KW_NEXT) -> {
                advance();
                Stmt.NextStmt
            }
            check(TokenType.KW_WHILE) -> parseWhile()
            check(TokenType.KW_FOR) -> parseFor()
            check(TokenType.KW_ENUM) -> parseEnum()
            else -> {
                val expr = parseExpression(0)
                if (check(TokenType.SEMICOLON)) advance()
                Stmt.ExprStmt(expr)
            }
        }
    }

    private fun parseReturn(): Stmt {
        consume(TokenType.KW_RETURN, "Expected 'return'")
        val value = if (!check(TokenType.SEMICOLON) && !check(TokenType.R_BRACE)) {
            parseExpression(0)
        } else null
        if (check(TokenType.SEMICOLON)) advance()
        return Stmt.ReturnStmt(value)
    }

    private fun parseEnum(): Stmt {
        consume(TokenType.KW_ENUM, "Expected enum")
        val name = consume(TokenType.IDENTIFIER, "Expected enum name")
        consume(TokenType.L_BRACE, "Expected {")
        val values = mutableListOf<Token>()
        if (!check(TokenType.R_BRACE)) {
            do {
                values.add(consume(TokenType.IDENTIFIER, "Expected enum value"))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_BRACE, "Expected }")
        return Stmt.EnumStmt(name, values)
    }

    private fun parseWhile(): Stmt {
        consume(TokenType.KW_WHILE, "Expected while")
        val condition = parseExpression(0)
        val body = parseBlock()
        return Stmt.WhileStmt(condition, body)
    }

    private fun parseFor(): Stmt {
        consume(TokenType.KW_FOR, "Expected for")
        val variable = consume(TokenType.IDENTIFIER, "Expected loop variable")
        consume(TokenType.KW_IN, "Expected 'in' after loop variable")
        val iterable = parseExpression(0)
        val body = parseBlock()
        return Stmt.ForRangeStmt(variable, iterable, body)
    }

    private fun parseFunc(): Stmt {
        consume(TokenType.KW_FN, "Expected 'fn'")
        val name = consume(TokenType.IDENTIFIER, "Expected function name")
        consume(TokenType.L_PAREN, "Expected '('")
        val params = mutableListOf<Param>()
        var hasSeenDefaultParam = false

        if (!check(TokenType.R_PAREN)) {
            do {
                val paramName = consume(TokenType.IDENTIFIER, "Expected parameter name")
                val paramType = if (match(TokenType.COLON)) consume(
                    TokenType.IDENTIFIER,
                    "Expected type"
                ) else null

                // Check for default value
                val defaultValue = if (match(TokenType.ASSIGN)) {
                    hasSeenDefaultParam = true
                    parseExpression(0)
                } else {
                    // No default value - validate that we haven't seen a default param yet
                    if (hasSeenDefaultParam) {
                        throw error(
                            previous(),
                            "Non-default parameter '${paramName.lexeme}' cannot follow default parameter"
                        )
                    }
                    null
                }

                params.add(Param(paramName, paramType, defaultValue))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')'")
        val returnType = if (match(TokenType.ARROW)) {
            parseType()
        } else null
        val body = parseBlock()
        return Stmt.FuncStmt(name, params, returnType, body)
    }

    private fun parseType(): Token {
        return when {
            check(TokenType.KW_BOOL) -> advance()
            check(TokenType.KW_INT) -> advance()
            check(TokenType.KW_FLOAT) -> advance()
            check(TokenType.KW_DOUBLE) -> advance()
            check(TokenType.KW_STRING) -> advance()
            check(TokenType.IDENTIFIER) -> advance()
            else -> throw error(peek(), "Expected type!")
        }
    }

    private fun parseClass(): Stmt {
        consume(TokenType.KW_CLASS, "Expected class")
        val name = consume(TokenType.IDENTIFIER, "Expected identifier")
        val superClass = if (match(TokenType.KW_EXTENDS)) {
            consume(TokenType.IDENTIFIER, "Expected identifier")
        } else null
        val body = parseBlock()
        return Stmt.ClassStmt(name, superClass, body)
    }

    private fun parseIf(): Stmt {
        consume(TokenType.KW_IF, "Expected if")
        val condition = parseExpression(0)
        val thenBranch = parseBlock()
        val elseBranch = if (match(TokenType.KW_ELSE)) {
            if (check(TokenType.KW_IF)) Stmt.ElseBranch.ElseIf(parseIf() as Stmt.IfStmt)
            else Stmt.ElseBranch.Else(parseBlock())
        } else null
        return Stmt.IfStmt(condition, thenBranch, elseBranch)
    }

    private fun parseBlock(): Stmt.BlockStmt {
        consume(TokenType.L_BRACE, "Expected lbrace")
        val stmts = mutableListOf<Stmt>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) stmts.add(parseStmt())
        consume(TokenType.R_BRACE, "Expected brace")
        return Stmt.BlockStmt(stmts)
    }

    private fun parseVar(): Stmt {
        val keyword = advance()
        val name = consume(TokenType.IDENTIFIER, "Expected name")
        val value = if (match(TokenType.ASSIGN)) parseExpression(0) else null
        if (check(TokenType.SEMICOLON)) advance()
        return Stmt.VarStmt(keyword, name, value)
    }

    private fun parseImport(): Stmt {
        consume(TokenType.KW_IMPORT, "Expected import")
        if (check(TokenType.IDENTIFIER) && (checkAhead(1, TokenType.KW_FROM) || checkAhead(
                1,
                TokenType.COMMA
            ))
        ) {
            val tokens = mutableListOf<Token>()
            tokens.add(consume(TokenType.IDENTIFIER, "Expected identifier"))
            while (check(TokenType.COMMA)) {
                advance()
                tokens.add(consume(TokenType.IDENTIFIER, "Expected identifier"))
            }
            consume(TokenType.KW_FROM, "Expected from")
            val namespace = consume(TokenType.IDENTIFIER, "Expected namespace")
            if (check(TokenType.SEMICOLON)) advance()
            return Stmt.ImportFromStmt(namespace, tokens)
        }
        val namespace = consume(TokenType.IDENTIFIER, "Expected namespace")
        if (check(TokenType.SEMICOLON)) advance()
        return Stmt.ImportStmt(namespace)
    }

    private fun parseExpression(minPrecedence: Int): Expr {
        var left = parsePrefix()

        while (true) {
            left = parsePostfix(left)

            val token = peek()
            val precedence = weights[token.type] ?: break
            if (precedence < minPrecedence) break

            // assignment ops are right-associative and need target validation
            if (token.type in ASSIGN_OPS) {
                val target = left
                if (target !is Expr.VariableExpr && target !is Expr.GetExpr && target !is Expr.IndexExpr)
                    throw error(token, "Invalid assignment target")
                advance()
                val value = parseExpression(precedence) // right-associative
                return Expr.AssignExpr(target, token, value)
            }

            // 'is' takes a type name (identifier), not an expression
            if (token.type == TokenType.KW_IS) {
                advance()
                val typeName = consume(TokenType.IDENTIFIER, "Expected type name after 'is'")
                left = Expr.IsExpr(left, typeName)
                continue
            }

            advance()
            val right = parseExpression(precedence + 1)
            left = Expr.BinaryExpr(left, token, right)
        }

        return left
    }

    private fun parsePostfix(left: Expr): Expr {
        var expr = left
        while (true) {
            expr = when {
                match(TokenType.L_PAREN) -> {
                    val args = mutableListOf<Expr>()
                    if (!check(TokenType.R_PAREN)) {
                        do { args.add(parseExpression(0)) } while (match(TokenType.COMMA))
                    }
                    val paren = consume(TokenType.R_PAREN, "Expected ')' after arguments")
                    Expr.CallExpr(expr, paren, args)
                }
                match(TokenType.DOT) -> {
                    val name = consume(TokenType.IDENTIFIER, "Expected field name after '.'")
                    Expr.GetExpr(expr, name)
                }
                match(TokenType.L_SQUARE) -> {
                    val index = parseExpression(0)
                    consume(TokenType.R_SQUARE, "Expected ']'")
                    Expr.IndexExpr(expr, index)
                }
                else -> break
            }
        }
        return expr
    }
    private fun parsePrefix(): Expr {
        val token = advance()
        return when (token.type) {
            TokenType.IDENTIFIER -> Expr.VariableExpr(token)
            TokenType.KW_TRUE -> Expr.LiteralExpr(Value.Boolean.TRUE)
            TokenType.KW_FALSE -> Expr.LiteralExpr(Value.Boolean.FALSE)
            TokenType.KW_NULL -> Expr.LiteralExpr(Value.Null)
            TokenType.KW_INT -> {
                // Parse integer from lexeme
                val value = token.lexeme.toIntOrNull()
                    ?: throw error(token, "Invalid integer literal: ${token.lexeme}")
                Expr.LiteralExpr(Value.Int(value))
            }
            TokenType.KW_DOUBLE -> {
                // Parse double from lexeme
                val value = token.lexeme.toDoubleOrNull()
                    ?: throw error(token, "Invalid double literal: ${token.lexeme}")
                Expr.LiteralExpr(Value.Double(value))
            }
            TokenType.KW_STRING -> {
                // String literal (lexeme includes quotes)
                val value = token.lexeme.substring(1, token.lexeme.length - 1)
                // Check if this is the start of an interpolated string
                if (check(TokenType.INTERPOLATION_START)) {
                    advance()  // consume INTERPOLATION_START
                    parseInterpolatedString(Expr.LiteralExpr(Value.String(value)))
                } else {
                    Expr.LiteralExpr(Value.String(value))
                }
            }
            TokenType.INTERPOLATION_START -> {
                // Interpolation starting at beginning of string (empty string prefix)
                // The INTERPOLATION_START token has already been consumed by advance() in parsePrefix
                parseInterpolatedString(Expr.LiteralExpr(Value.String("")))
            }
            TokenType.MINUS -> Expr.UnaryExpr(token, parseExpression(90))
            TokenType.BANG -> Expr.UnaryExpr(token, parseExpression(90))
            TokenType.KW_NOT -> Expr.UnaryExpr(token, parseExpression(90))
            TokenType.L_PAREN -> {
                val expr = parseExpression(0)
                consume(TokenType.R_PAREN, "Expect ')' after expression.")
                Expr.GroupExpr(expr)
            }
            TokenType.L_SQUARE -> {
                val elements = mutableListOf<Expr>()
                if (!check(TokenType.R_SQUARE)) {
                    do { elements.add(parseExpression(0)) } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_SQUARE, "Expected ']'")
                Expr.ListExpr(elements)
            }
            else -> throw error(
                token,
                "Expected expression but found ${token.type} ('${token.lexeme}')"
            )
        }
    }

    /**
     * Parse an interpolated string and desugar it to concatenation operations.
     * Example: "Hi ${name}!" desugars to "Hi " + name + "!"
     *
     * IMPORTANT: This method assumes the caller has already consumed the INTERPOLATION_START token.
     *
     * @param firstPart The initial string literal (or empty string if interpolation starts at beginning)
     * @return A BinaryExpr chain representing the concatenation
     */
    private fun parseInterpolatedString(firstPart: Expr): Expr {
        var result = firstPart
        val plusToken = Token(TokenType.PLUS, "+", 0, 0)  // Synthetic token for concatenation

        // We're already past the INTERPOLATION_START - parse the expression
        while (true) {
            // Parse the expression inside the interpolation
            val expr = parseExpression(0)

            // Expect INTERPOLATION_END
            consume(TokenType.INTERPOLATION_END, "Expected '}' after interpolated expression")

            // Concatenate: result + expr
            result = Expr.BinaryExpr(result, plusToken, expr)

            // Check if there's more string content or another interpolation
            if (check(TokenType.KW_STRING)) {
                // There's string content after the interpolation
                val stringToken = advance()
                val stringValue = stringToken.lexeme.substring(1, stringToken.lexeme.length - 1)
                result = Expr.BinaryExpr(result, plusToken, Expr.LiteralExpr(Value.String(stringValue)))

                // Check if there's another interpolation
                if (check(TokenType.INTERPOLATION_START)) {
                    advance()  // consume INTERPOLATION_START
                    // Continue to parse the next interpolation
                } else {
                    break
                }
            } else if (check(TokenType.INTERPOLATION_START)) {
                // Another interpolation immediately after this one
                advance()  // consume INTERPOLATION_START
                continue
            } else {
                // End of interpolated string
                break
            }
        }

        return result
    }

    // --- Helpers ---

    private fun checkAhead(offset: Int, type: TokenType): Boolean {
        val idx = cursor + offset
        if (idx >= tokens.size) return false
        return tokens[idx].type == type
    }

    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw error(peek(), message)
    }

    private fun error(token: Token, message: String): RuntimeException {
        println("Error at line ${token.line}: $message")
        return RuntimeException(message)
    }

    fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    fun check(type: TokenType) = if (isAtEnd()) false else peek().type == type

    fun peek(): Token = tokens[cursor]

    fun previous(): Token = tokens[cursor - 1]

    fun advance(): Token {
        if (!isAtEnd()) cursor++
        return previous()
    }

    fun isAtEnd(): Boolean = peek().type == TokenType.EOF
}