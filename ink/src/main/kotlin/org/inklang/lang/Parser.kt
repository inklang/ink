package org.inklang.lang

private fun unescape(s: String): String = buildString {
    var i = 0
    while (i < s.length) {
        if (s[i] == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n'  -> { append('\n'); i += 2 }
                't'  -> { append('\t'); i += 2 }
                'r'  -> { append('\r'); i += 2 }
                '"'  -> { append('"');  i += 2 }
                '\\' -> { append('\\'); i += 2 }
                '$'  -> { append('$');  i += 2 }
                'u'  -> {
                    if (i + 6 <= s.length) {
                        val hex = s.substring(i + 2, i + 6)
                        if (hex.all { it in "0123456789abcdefABCDEF" }) {
                            append(hex.toInt(16).toChar())
                            i += 6
                        } else {
                            append(s[i]); i++
                        }
                    } else {
                        append(s[i]); i++
                    }
                }
                else -> { append(s[i]); i++ }
            }
        } else {
            append(s[i])
            i++
        }
    }
}

class Parser(private val tokens: List<Token>) {
    private var cursor = 0

    companion object {
        // Higher number = tighter binding
        val weights = mapOf(
            TokenType.ASSIGN to 10,
            TokenType.ADD_EQUALS to 10, TokenType.SUB_EQUALS to 10,
            TokenType.MUL_EQUALS to 10, TokenType.DIV_EQUALS to 10, TokenType.MOD_EQUALS to 10,
            TokenType.QUESTION to 15,
            TokenType.QUESTION_QUESTION to 15,  // elvis operator, same precedence as ternary
            TokenType.KW_OR to 20,
            TokenType.KW_AND to 30,
            TokenType.KW_IS to 35,  // type checking, lower than and/or
            TokenType.EQ_EQ to 40, TokenType.BANG_EQ to 40,
            TokenType.KW_HAS to 45,   // new — tighter than < > <= >=, looser than . (90)
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

    private fun parseTable(): Stmt {
        consume(TokenType.KW_TABLE, "Expected 'table'")
        val name = consume(TokenType.IDENTIFIER, "Expected table name")
        consume(TokenType.L_BRACE, "Expected '{'")
        val fields = mutableListOf<Stmt.TableField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            val isKey = match(TokenType.KW_KEY)
            val fieldName = consume(TokenType.IDENTIFIER, "Expected field name")
            // Type annotation is optional: `name` or `name: type`
            val fieldType = if (match(TokenType.COLON)) {
                val typeToken = parseType()
                typeToken.lexeme
            } else {
                null
            }
            val defaultValue = if (match(TokenType.ASSIGN)) parseExpression(0) else null
            if (check(TokenType.SEMICOLON)) advance()
            if (check(TokenType.COMMA)) advance()
            fields.add(Stmt.TableField(fieldName, fieldType, isKey, defaultValue))
        }
        consume(TokenType.R_BRACE, "Expected '}'")
        return Stmt.TableStmt(name, fields)
    }

    private fun parseConfig(): Stmt {
        consume(TokenType.KW_CONFIG, "Expected 'config'")
        val name = consume(TokenType.IDENTIFIER, "Expected config name")
        consume(TokenType.L_BRACE, "Expected '{'")
        val fields = mutableListOf<Stmt.ConfigField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            val fieldName = consume(TokenType.IDENTIFIER, "Expected field name")
            consume(TokenType.COLON, "Expected ':' after field name")
            val fieldType = parseType()
            val defaultValue = if (match(TokenType.ASSIGN)) parseExpression(0) else null
            if (check(TokenType.SEMICOLON)) advance()
            fields.add(Stmt.ConfigField(fieldName, fieldType, defaultValue))
        }
        consume(TokenType.R_BRACE, "Expected '}'")
        return Stmt.ConfigStmt(name, fields)
    }

    private fun parseAnnotationDecl(): Stmt {
        consume(TokenType.KW_ANNOTATION, "Expected 'annotation'")
        val name = consume(TokenType.IDENTIFIER, "Expected annotation name")
        consume(TokenType.L_BRACE, "Expected '{'")
        val fields = mutableListOf<Stmt.AnnotationField>()
        while (!check(TokenType.R_BRACE) && !isAtEnd()) {
            val fieldName = consume(TokenType.IDENTIFIER, "Expected field name")
            consume(TokenType.COLON, "Expected ':' after field name")
            val fieldType = parseType()
            val defaultValue = if (match(TokenType.ASSIGN)) parseExpression(0) else null
            if (check(TokenType.SEMICOLON)) advance()
            match(TokenType.COMMA) // consume optional comma between fields
            fields.add(Stmt.AnnotationField(fieldName, fieldType, defaultValue))
        }
        consume(TokenType.R_BRACE, "Expected '}'")
        return Stmt.AnnotationDeclStmt(name, fields)
    }

    private fun parseEventDecl(): Stmt {
        consume(TokenType.KW_EVENT, "Expected 'event'")
        val name = consume(TokenType.IDENTIFIER, "Expected event name")
        consume(TokenType.L_PAREN, "Expected '('")
        val params = mutableListOf<Stmt.EventParam>()
        if (!check(TokenType.R_PAREN)) {
            do {
                val paramName = consume(TokenType.IDENTIFIER, "Expected parameter name")
                consume(TokenType.COLON, "Expected ':' after parameter name")
                val paramType = consume(TokenType.IDENTIFIER, "Expected type")
                params.add(Stmt.EventParam(paramName, paramType))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')'")
        return Stmt.EventDeclStmt(name, params)
    }

    private fun parseOnHandler(): Stmt {
        consume(TokenType.KW_ON, "Expected 'on'")
        val eventName = consume(TokenType.IDENTIFIER, "Expected event name")
        consume(TokenType.L_PAREN, "Expected '('")

        // First param is always the event object (user-named)
        val eventParam = consume(TokenType.IDENTIFIER, "Expected event parameter name")

        val dataParams = mutableListOf<Stmt.EventParam>()
        if (!check(TokenType.R_PAREN)) {
            consume(TokenType.COMMA, "Expected ',' after event param")
            do {
                val paramName = consume(TokenType.IDENTIFIER, "Expected parameter name")
                consume(TokenType.COLON, "Expected ':' after parameter name")
                val paramType = consume(TokenType.IDENTIFIER, "Expected type")
                dataParams.add(Stmt.EventParam(paramName, paramType))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')'")

        val body = parseBlock()
        return Stmt.OnHandlerStmt(eventName, null, eventParam, dataParams, body)
    }

    private fun parseStmt(): Stmt {
        // Check for annotations first - they may appear before any statement keyword
        val leadingAnnotations = if (check(TokenType.AT)) parseAnnotations() else emptyList()

        return when {
            check(TokenType.KW_IMPORT) -> parseImport()
            check(TokenType.KW_LET) || check(TokenType.KW_CONST) -> parseVar(leadingAnnotations)
            check(TokenType.KW_IF) -> parseIf()
            check(TokenType.KW_CLASS) -> parseClass(leadingAnnotations)
            check(TokenType.KW_FN) -> parseFunc(leadingAnnotations)
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
            check(TokenType.KW_TABLE) -> parseTable()
            check(TokenType.KW_CONFIG) -> parseConfig()
            check(TokenType.KW_ANNOTATION) -> parseAnnotationDecl()
            check(TokenType.KW_EVENT) -> parseEventDecl()
            check(TokenType.KW_ON) -> parseOnHandler()
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

    private fun parseFunc(leadingAnnotations: List<Expr.AnnotationExpr> = emptyList()): Stmt {
        // Use leading annotations if provided, otherwise parse them (for when called directly)
        val annotations = if (leadingAnnotations.isNotEmpty()) {
            leadingAnnotations
        } else {
            parseAnnotations()
        }
        consume(TokenType.KW_FN, "Expected 'fn'")
        val name = consume(TokenType.IDENTIFIER, "Expected function name")
        consume(TokenType.L_PAREN, "Expected '('")
        val params = mutableListOf<Param>()
        var hasSeenDefaultParam = false

        if (!check(TokenType.R_PAREN)) {
            do {
                // Parse parameter annotations
                val paramAnnotations = parseAnnotations()
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

                params.add(Param(paramAnnotations, paramName, paramType, defaultValue))
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.R_PAREN, "Expected ')'")
        val returnType = if (match(TokenType.ARROW)) {
            parseType()
        } else null
        val body = parseBlock()
        return Stmt.FuncStmt(annotations, name, params, returnType, body)
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

    private fun parseClass(leadingAnnotations: List<Expr.AnnotationExpr> = emptyList()): Stmt {
        // Use leading annotations if provided, otherwise parse them (for when called directly)
        val annotations = if (leadingAnnotations.isNotEmpty()) {
            leadingAnnotations
        } else {
            parseAnnotations()
        }
        consume(TokenType.KW_CLASS, "Expected class")
        val name = consume(TokenType.IDENTIFIER, "Expected identifier")
        val superClass = if (match(TokenType.KW_EXTENDS)) {
            consume(TokenType.IDENTIFIER, "Expected identifier")
        } else null
        val body = parseBlock()
        return Stmt.ClassStmt(annotations, name, superClass, body)
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

    private fun parseVar(leadingAnnotations: List<Expr.AnnotationExpr> = emptyList()): Stmt {
        // Use leading annotations if provided, otherwise parse them (for when called directly)
        val annotations = if (leadingAnnotations.isNotEmpty()) {
            leadingAnnotations
        } else {
            parseAnnotations()
        }
        val keyword = advance()
        val name = consume(TokenType.IDENTIFIER, "Expected name")
        val value = if (match(TokenType.ASSIGN)) parseExpression(0) else null
        if (check(TokenType.SEMICOLON)) advance()
        return Stmt.VarStmt(annotations, keyword, name, value)
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
                val typeName = parseType()
                left = Expr.IsExpr(left, typeName)
                continue
            }

            // 'has' takes a field name expression (right-hand side)
            if (token.type == TokenType.KW_HAS) {   // new
                advance()
                val field = parseExpression(45)
                left = Expr.HasExpr(left, field)
                continue
            }

            // Ternary: condition ? then : else
            if (token.type == TokenType.QUESTION) {
                advance()  // consume ?
                val thenBranch = parseExpression(0)
                consume(TokenType.COLON, "Expected ':' in ternary expression")
                val elseBranch = parseExpression(0)
                left = Expr.TernaryExpr(left, thenBranch, elseBranch)
                continue
            }

            // Elvis: left ?? right
            if (token.type == TokenType.QUESTION_QUESTION) {
                advance()  // consume ??
                val right = parseExpression(15)  // same precedence as ternary
                left = Expr.ElvisExpr(left, right)
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
                match(TokenType.QUESTION_DOT) -> {
                    // Safe call: obj?.name
                    val name = when {
                        match(TokenType.IDENTIFIER) -> previous()
                        match(TokenType.KW_HAS) -> previous()  // has can be used as method name
                        match(TokenType.KW_IS) -> previous()    // is can be used as method name
                        else -> throw error(peek(), "Expected field name after '?.'")
                    }
                    Expr.SafeCallExpr(expr, name)
                }
                match(TokenType.DOT) -> {
                    // Regular property access: obj.name
                    val name = when {
                        match(TokenType.IDENTIFIER) -> previous()
                        match(TokenType.KW_HAS) -> previous()  // has can be used as method name
                        match(TokenType.KW_IS) -> previous()    // is can be used as method name
                        else -> throw error(peek(), "Expected field name after '.'")
                    }
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
                    parseInterpolatedString(Expr.LiteralExpr(Value.String(unescape(value))))
                } else {
                    Expr.LiteralExpr(Value.String(unescape(value)))
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
            TokenType.INCREMENT -> Expr.UnaryExpr(token, parseExpression(90))
            TokenType.DECREMENT -> Expr.UnaryExpr(token, parseExpression(90))
            TokenType.L_BRACE -> {
                // Map literal or set literal in expression position
                // { key: value, ... } → Map
                // { expr, expr, ... } → Set
                // Empty {} is an error in expression position (parsed as block at statement level)
                if (check(TokenType.R_BRACE)) {
                    // Empty {} - error since we can't have empty set/map in expression position
                    throw error(token, "Empty '{}' is not valid in expression position. Use '()' for empty tuple or '{key: value}' for single-element map.")
                }
                // Parse first element to determine if this is a map or set
                val firstExpr = parseExpression(0)
                if (match(TokenType.COLON)) {
                    // This is a map: { key: value, ... }
                    val entries = mutableListOf<Pair<Expr, Expr>>()
                    entries.add(firstExpr to parseExpression(0))
                    while (match(TokenType.COMMA)) {
                        val key = parseExpression(0)
                        consume(TokenType.COLON, "Expected ':' after map key")
                        val value = parseExpression(0)
                        entries.add(key to value)
                    }
                    consume(TokenType.R_BRACE, "Expected '}' after map literal")
                    Expr.MapExpr(entries)
                } else {
                    // This is a set: { expr, expr, ... }
                    val elements = mutableListOf<Expr>()
                    elements.add(firstExpr)
                    while (match(TokenType.COMMA)) {
                        elements.add(parseExpression(0))
                    }
                    consume(TokenType.R_BRACE, "Expected '}' after set literal")
                    Expr.SetExpr(elements)
                }
            }
            TokenType.L_PAREN -> {
                // Could be: grouped expression, lambda, or tuple
                // Lambda: (params) -> { body }
                if (isLambdaAhead()) {
                    val params = mutableListOf<Param>()
                    if (!check(TokenType.R_PAREN)) {
                        do {
                            // Lambda parameters don't support annotations (yet)
                            val paramAnnotations = emptyList<Expr.AnnotationExpr>()
                            val paramName = consume(TokenType.IDENTIFIER, "Expected parameter name")
                            val paramType = if (match(TokenType.COLON)) {
                                consume(TokenType.IDENTIFIER, "Expected type")
                            } else null
                            val defaultValue = if (match(TokenType.ASSIGN)) {
                                parseExpression(0)
                            } else null
                            params.add(Param(paramAnnotations, paramName, paramType, defaultValue))
                        } while (match(TokenType.COMMA))
                    }
                    consume(TokenType.R_PAREN, "Expected ')'")
                    consume(TokenType.ARROW, "Expected '->' after lambda params")
                    val body = parseBlock()
                    Expr.LambdaExpr(params, body)
                } else {
                    // Check if this is a tuple: (expr, expr, ...) or ()
                    if (check(TokenType.R_PAREN)) {
                        // Empty tuple: ()
                        advance()
                        Expr.TupleExpr(emptyList())
                    } else {
                        val firstExpr = parseExpression(0)
                        if (check(TokenType.COMMA)) {
                            // This is a tuple: (expr, expr, ...) or (expr,) - single element with trailing comma
                            val elements = mutableListOf<Expr>()
                            elements.add(firstExpr)
                            while (match(TokenType.COMMA)) {
                                // Check for trailing comma: (expr,) case - next token should be R_PAREN
                                if (check(TokenType.R_PAREN)) {
                                    break
                                }
                                elements.add(parseExpression(0))
                            }
                            consume(TokenType.R_PAREN, "Expected ')' after tuple")
                            Expr.TupleExpr(elements)
                        } else {
                            // Single expression in parentheses - grouping
                            consume(TokenType.R_PAREN, "Expect ')' after expression.")
                            Expr.GroupExpr(firstExpr)
                        }
                    }
                }
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
                result = Expr.BinaryExpr(result, plusToken, Expr.LiteralExpr(Value.String(unescape(stringValue))))

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

    /**
     * Parse zero or more annotations before a declaration.
     * Returns a list of AnnotationExpr.
     */
    private fun parseAnnotations(): List<Expr.AnnotationExpr> {
        val annotations = mutableListOf<Expr.AnnotationExpr>()

        while (true) {
            // Consume any ASI-inserted semicolons before checking for next annotation
            // This handles cases like: @inline(level: 2) \n @pure \n fn foo()
            // where ASI inserts ; after the first )
            match(TokenType.SEMICOLON)

            if (!match(TokenType.AT)) {
                // No more annotations
                break
            }

            val nameToken = consume(TokenType.IDENTIFIER, "Expected annotation name after @")
            val name = nameToken.lexeme
            val args = mutableMapOf<String, Expr>()

            // Parse optional (arg1, arg2, ...) — named arguments only
            if (match(TokenType.L_PAREN)) {
                if (!check(TokenType.R_PAREN)) {
                    do {
                        val argName = consume(TokenType.IDENTIFIER, "Expected named argument name")
                        val argNameStr = argName.lexeme
                        consume(TokenType.COLON, "Expected ':' after argument name")
                        val argValue = parseExpression(0)
                        args[argNameStr] = argValue
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.R_PAREN, "Expected ')' after annotation arguments")
            }

            annotations.add(Expr.AnnotationExpr(name, args))
        }
        return annotations
    }

    /** Lookahead to check if current L_PAREN starts a lambda: (params) -> { ... } */
    private fun isLambdaAhead(): Boolean {
        // We're just past L_PAREN. Scan for matching R_PAREN, then check for ARROW.
        var depth = 1
        var i = cursor
        while (i < tokens.size && depth > 0) {
            when (tokens[i].type) {
                TokenType.L_PAREN -> depth++
                TokenType.R_PAREN -> depth--
                TokenType.EOF -> return false
                else -> {}
            }
            i++
        }
        // i is now past R_PAREN. Check if next token is ARROW.
        return i < tokens.size && tokens[i].type == TokenType.ARROW
    }

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