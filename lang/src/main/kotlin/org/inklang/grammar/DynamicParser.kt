package org.inklang.grammar

class TokenStream(private val tokens: List<DynToken>) {
    var pos: Int = 0

    fun peek(): DynToken = tokens[pos]
    fun advance(): DynToken = tokens[pos++]
    fun isAtEnd(): Boolean = peek().type == DynTokenType.EOF

    fun expect(type: DynTokenType, message: String = "Expected $type"): DynToken {
        val tok = peek()
        if (tok.type != type) {
            throw ParseException("$message but found ${tok.type}('${tok.text}') at line ${tok.line}, col ${tok.col}")
        }
        return advance()
    }

    fun expectKeyword(value: String): DynToken {
        val tok = peek()
        if (tok.type != DynTokenType.KEYWORD || tok.text != value) {
            throw ParseException(
                "Expected keyword '$value' but found ${tok.type}('${tok.text}') at line ${tok.line}, col ${tok.col}"
            )
        }
        return advance()
    }

    fun check(type: DynTokenType): Boolean = peek().type == type
    fun checkKeyword(value: String): Boolean = peek().type == DynTokenType.KEYWORD && peek().text == value

    fun save(): Int = pos
    fun restore(saved: Int) { pos = saved }
}

class ParseException(message: String) : RuntimeException(message)

class DynamicParser(private val grammar: GrammarPackage) {

    private val declByKeyword: Map<String, DeclarationDef> =
        grammar.declarations.associateBy { it.keyword }

    private val rulesByName: Map<String, RuleEntry> = grammar.rules

    fun parse(source: String): List<CstNode> {
        val lexer = DynamicLexer(grammar.keywords.toSet())
        val tokens = TokenStream(lexer.tokenize(source))
        val results = mutableListOf<CstNode>()

        while (!tokens.isAtEnd()) {
            results.add(parseDeclaration(tokens))
        }

        return results
    }

    private fun parseDeclaration(tokens: TokenStream): CstNode {
        val tok = tokens.peek()
        val decl = declByKeyword[tok.text]
            ?: throw ParseException(
                "Expected declaration keyword (one of: ${declByKeyword.keys}) " +
                "but found '${tok.text}' at line ${tok.line}, col ${tok.col}"
            )

        tokens.expectKeyword(decl.keyword)
        val name = parseRuleToString(decl.nameRule, tokens)
        tokens.expect(DynTokenType.LBRACE, "Expected '{' after declaration name")

        val body = mutableListOf<CstNode>()
        while (!tokens.check(DynTokenType.RBRACE) && !tokens.isAtEnd()) {
            body.add(parseScopeRule(decl.scopeRules, tokens))
        }

        tokens.expect(DynTokenType.RBRACE, "Expected '}' to close declaration")
        return CstNode.Declaration(decl.keyword, name, body)
    }

    private fun parseScopeRule(scopeRuleNames: List<String>, tokens: TokenStream): CstNode {
        for (ruleName in scopeRuleNames) {
            val entry = rulesByName[ruleName]
                ?: throw ParseException("Unknown rule reference: $ruleName")

            val saved = tokens.save()
            try {
                val children = mutableListOf<CstNode>()
                parseRuleInto(entry.rule, tokens, children)
                return CstNode.RuleMatch(ruleName, children)
            } catch (e: ParseException) {
                tokens.restore(saved)
            }
        }

        val tok = tokens.peek()
        throw ParseException(
            "No scope rule matched at line ${tok.line}, col ${tok.col} " +
            "(token: '${tok.text}'). Expected one of: $scopeRuleNames"
        )
    }

    private fun parseRuleInto(rule: Rule, tokens: TokenStream, out: MutableList<CstNode>) {
        when (rule) {
            is Rule.Seq -> {
                for (item in rule.items) {
                    parseRuleInto(item, tokens, out)
                }
            }
            is Rule.Choice -> {
                for (option in rule.items) {
                    val saved = tokens.save()
                    try {
                        parseRuleInto(option, tokens, out)
                        return
                    } catch (e: ParseException) {
                        tokens.restore(saved)
                    }
                }
                val tok = tokens.peek()
                throw ParseException("No choice matched at line ${tok.line}, col ${tok.col}")
            }
            is Rule.Many -> {
                while (true) {
                    val saved = tokens.save()
                    try {
                        parseRuleInto(rule.item, tokens, out)
                    } catch (e: ParseException) {
                        tokens.restore(saved)
                        break
                    }
                }
            }
            is Rule.Many1 -> {
                parseRuleInto(rule.item, tokens, out)
                while (true) {
                    val saved = tokens.save()
                    try {
                        parseRuleInto(rule.item, tokens, out)
                    } catch (e: ParseException) {
                        tokens.restore(saved)
                        break
                    }
                }
            }
            is Rule.Optional -> {
                val saved = tokens.save()
                try {
                    parseRuleInto(rule.item, tokens, out)
                } catch (e: ParseException) {
                    tokens.restore(saved)
                }
            }
            is Rule.Ref -> {
                val entry = rulesByName[rule.rule]
                    ?: throw ParseException("Unknown rule reference: ${rule.rule}")
                val children = mutableListOf<CstNode>()
                parseRuleInto(entry.rule, tokens, children)
                out.add(CstNode.RuleMatch(rule.rule, children))
            }
            is Rule.Keyword -> {
                tokens.expectKeyword(rule.value)
                out.add(CstNode.KeywordNode(rule.value))
            }
            is Rule.Literal -> {
                val tok = tokens.peek()
                if (tok.text != rule.value) {
                    throw ParseException("Expected literal '${rule.value}' but found '${tok.text}'")
                }
                tokens.advance()
                out.add(CstNode.KeywordNode(rule.value))
            }
            is Rule.Block -> {
                out.add(parseBlock(rule.scope, tokens))
            }
            is Rule.Identifier -> {
                val tok = tokens.expect(DynTokenType.IDENTIFIER, "Expected identifier")
                out.add(CstNode.IdentifierNode(tok.text))
            }
            is Rule.Int -> {
                val tok = tokens.expect(DynTokenType.INT, "Expected integer")
                out.add(CstNode.IntLiteral(tok.text))
            }
            is Rule.Float -> {
                val tok = tokens.expect(DynTokenType.FLOAT, "Expected float")
                out.add(CstNode.FloatLiteral(tok.text))
            }
            is Rule.StringRule -> {
                val tok = tokens.expect(DynTokenType.STRING, "Expected string")
                out.add(CstNode.StringLiteral(tok.text))
            }
        }
    }

    private fun parseBlock(scope: String?, tokens: TokenStream): CstNode.Block {
        tokens.expect(DynTokenType.LBRACE, "Expected '{' to open block")
        val children = mutableListOf<CstNode>()

        if (scope != null) {
            // Scoped block: find the referenced rule and use it to parse block contents
            val scopeEntry = rulesByName[scope]
            if (scopeEntry != null) {
                // The scope references a named rule — parse contents using it
                while (!tokens.check(DynTokenType.RBRACE) && !tokens.isAtEnd()) {
                    val child = mutableListOf<CstNode>()
                    parseRuleInto(scopeEntry.rule, tokens, child)
                    children.addAll(child)
                }
            } else {
                // The scope might reference a declaration keyword — try declarations
                val scopeDecl = declByKeyword[scope]
                if (scopeDecl != null) {
                    while (!tokens.check(DynTokenType.RBRACE) && !tokens.isAtEnd()) {
                        children.add(parseDeclaration(tokens))
                    }
                }
            }
        }
        // scope == null: just match braces, block body is empty (or we could
        // allow arbitrary tokens, but for now empty is correct per the IR spec)

        tokens.expect(DynTokenType.RBRACE, "Expected '}' to close block")
        return CstNode.Block(scope, children)
    }

    private fun parseRuleToString(rule: Rule, tokens: TokenStream): String {
        return when (rule) {
            is Rule.Identifier -> {
                tokens.expect(DynTokenType.IDENTIFIER, "Expected identifier").text
            }
            is Rule.StringRule -> {
                val tok = tokens.expect(DynTokenType.STRING, "Expected string")
                // Strip quotes if present
                tok.text.removeSurrounding("\"")
            }
            else -> throw ParseException("Unsupported name rule type: ${rule::class.simpleName}")
        }
    }
}
