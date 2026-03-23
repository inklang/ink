# Grammar Reconstructor — Hand-Rolled Dynamic Parser

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a hand-rolled recursive descent parser that's dynamically constructed from grammar IR at runtime, producing a CST.

**Architecture:** A simple lexer tokenizes source using keywords from `GrammarPackage.keywords`. A `DynamicParser` walks the IR `Rule` tree recursively to match tokens, producing `CstNode` trees. Blocks use an explicit scope stack to resolve which rules are valid inside each block.

**Tech Stack:** Kotlin, JUnit 5, kotlinx.serialization (already present for IR loading)

---

## File Structure

| File | Responsibility |
|------|---------------|
| `grammar/CstNode.kt` | CST node sealed class hierarchy |
| `grammar/DynamicLexer.kt` | Hand-rolled tokenizer — keywords + standard tokens |
| `grammar/DynamicParser.kt` | Recursive descent parser driven by IR rules |
| `grammar/GrammarIRTest.kt` (modify) | Add parser acceptance tests |

All under `lang/src/main/kotlin/org/inklang/grammar/` and `lang/src/test/kotlin/org/inklang/grammar/`.

---

## Chunk 1: Setup + CST + Lexer

### Task 1: Remove better-parse from build.gradle.kts

**Files:**
- Modify: `lang/build.gradle.kts`

- [ ] **Step 1: Remove better-parse dependency** (it was never actually added, but verify clean state)
- [ ] **Step 2: Run `./gradlew :lang:dependencies`** to confirm no better-parse

### Task 2: CstNode sealed class

**Files:**
- Create: `lang/src/main/kotlin/org/inklang/grammar/CstNode.kt`

- [ ] **Step 1: Create CstNode.kt**

```kotlin
package org.inklang.grammar

sealed class CstNode {
    data class Declaration(
        val keyword: String,
        val name: String,
        val body: List<CstNode>
    ) : CstNode()

    data class RuleMatch(
        val ruleName: String,
        val children: List<CstNode>
    ) : CstNode()

    data class KeywordNode(val value: String) : CstNode()
    data class IdentifierNode(val value: String) : CstNode()
    data class IntLiteral(val value: String) : CstNode()
    data class FloatLiteral(val value: String) : CstNode()
    data class StringLiteral(val value: String) : CstNode()
    data class Block(val scope: String?, val children: List<CstNode>) : CstNode()
    data class Sequence(val children: List<CstNode>) : CstNode()
}
```

### Task 3: DynamicLexer

**Files:**
- Create: `lang/src/main/kotlin/org/inklang/grammar/DynamicLexer.kt`

- [ ] **Step 1: Write failing test for lexer**

In `GrammarIRTest.kt`, add:

```kotlin
@Test
fun `DynamicLexer tokenizes mob declaration`() {
    val grammar = loadMobsGrammar()
    val lexer = DynamicLexer(grammar.keywords.toSet())
    val tokens = lexer.tokenize("mob Dragon { on_spawn { } }")

    assertEquals(DynTokenType.KEYWORD, tokens[0].type)
    assertEquals("mob", tokens[0].text)
    assertEquals(DynTokenType.IDENTIFIER, tokens[1].type)
    assertEquals("Dragon", tokens[1].text)
    assertEquals(DynTokenType.LBRACE, tokens[2].type)
    assertEquals(DynTokenType.KEYWORD, tokens[3].type)
    assertEquals("on_spawn", tokens[3].text)
    assertEquals(DynTokenType.LBRACE, tokens[4].type)
    assertEquals(DynTokenType.RBRACE, tokens[5].type)
    assertEquals(DynTokenType.RBRACE, tokens[6].type)
    assertEquals(DynTokenType.EOF, tokens[7].type)
}

private fun loadMobsGrammar(): GrammarPackage {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val url = javaClass.classLoader.getResource("fixtures/ink.mobs/dist/grammar.ir.json")!!
    return json.decodeFromString(java.io.File(url.toURI()).readText())
}
```

- [ ] **Step 2: Run test, verify it fails** (DynamicLexer doesn't exist yet)

- [ ] **Step 3: Implement DynamicLexer**

```kotlin
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
            if (ch.isDigit() || (ch == '-' && pos + 1 < source.length && source[pos + 1].isDigit())) {
                val start = pos
                val startCol = col
                if (ch == '-') { pos++; col++ }
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
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :lang:test --tests "org.inklang.grammar.GrammarIRTest.DynamicLexer tokenizes mob declaration"`

- [ ] **Step 5: Commit**

```
feat(grammar): add CstNode types and DynamicLexer
```

---

## Chunk 2: DynamicParser + Acceptance Test

### Task 4: TokenStream helper

This is a small cursor wrapper over the token list, used by DynamicParser. Include it in DynamicParser.kt.

### Task 5: DynamicParser

**Files:**
- Create: `lang/src/main/kotlin/org/inklang/grammar/DynamicParser.kt`
- Modify: `lang/src/test/kotlin/org/inklang/grammar/GrammarIRTest.kt`

- [ ] **Step 1: Write the acceptance test**

```kotlin
@Test
fun `DynamicParser parses mob Dragon with on_spawn block`() {
    val grammar = loadMobsGrammar()
    val parser = DynamicParser(grammar)
    val result = parser.parse("mob Dragon {\n    on_spawn {\n    }\n}")

    assertEquals(1, result.size)
    val decl = result[0] as CstNode.Declaration
    assertEquals("mob", decl.keyword)
    assertEquals("Dragon", decl.name)
    assertEquals(1, decl.body.size)

    val scopeMatch = decl.body[0] as CstNode.RuleMatch
    assertEquals("ink.mobs/on_spawn_clause", scopeMatch.ruleName)
    // on_spawn_clause is seq(keyword("on_spawn"), block(null))
    // children: [KeywordNode("on_spawn"), Block(null, [])]
    assertEquals(2, scopeMatch.children.size)
    assertEquals(CstNode.KeywordNode("on_spawn"), scopeMatch.children[0])
    val block = scopeMatch.children[1] as CstNode.Block
    assertEquals(null, block.scope)
    assertEquals(0, block.children.size)
}
```

- [ ] **Step 2: Run test, verify it fails** (DynamicParser doesn't exist yet)

- [ ] **Step 3: Implement DynamicParser**

```kotlin
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
            throw ParseException("Expected keyword '$value' but found ${tok.type}('${tok.text}') at line ${tok.line}, col ${tok.col}")
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

    // Index declarations by keyword for fast lookup
    private val declByKeyword: Map<String, DeclarationDef> =
        grammar.declarations.associateBy { it.keyword }

    // Index rules by name
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
                parseRuleInto(rule.item, tokens, out) // must match at least once
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
            // Scoped block: parse contents using the referenced scope rules
            val scopeDecl = declByKeyword.values.find { decl ->
                decl.scopeRules.any { it == scope }
            }
            val scopeRules = scopeDecl?.scopeRules ?: listOf(scope)
            while (!tokens.check(DynTokenType.RBRACE) && !tokens.isAtEnd()) {
                children.add(parseScopeRule(scopeRules, tokens))
            }
        }
        // scope == null: empty block, just match braces

        tokens.expect(DynTokenType.RBRACE, "Expected '}' to close block")
        return CstNode.Block(scope, children)
    }

    private fun parseRuleToString(rule: Rule, tokens: TokenStream): String {
        return when (rule) {
            is Rule.Identifier -> {
                tokens.expect(DynTokenType.IDENTIFIER, "Expected identifier").text
            }
            is Rule.StringRule -> {
                tokens.expect(DynTokenType.STRING, "Expected string").text
            }
            else -> throw ParseException("Unsupported name rule type: ${rule::class.simpleName}")
        }
    }
}
```

Key design points:
- `parseRuleInto` collects children into a mutable list — this naturally handles Seq flattening
- `Rule.Ref` and `Rule.Block` are lazy by construction: they look up rules/declarations at parse time, not at build time. No eager construction = no infinite recursion.
- `Choice`, `Optional`, `Many` use save/restore for backtracking
- `parseScopeRule` tries each scope rule with backtracking
- `parseBlock` with `scope == null` just matches braces (empty block content allowed)

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :lang:test --tests "org.inklang.grammar.GrammarIRTest.DynamicParser parses mob Dragon with on_spawn block"`

- [ ] **Step 5: Commit**

```
feat(grammar): add hand-rolled DynamicParser driven by grammar IR
```

---

## Chunk 3: Additional Tests

### Task 6: More parser tests

**Files:**
- Modify: `lang/src/test/kotlin/org/inklang/grammar/GrammarIRTest.kt`

- [ ] **Step 1: Test multiple declarations in one source**

```kotlin
@Test
fun `DynamicParser parses multiple mob declarations`() {
    val grammar = loadMobsGrammar()
    val parser = DynamicParser(grammar)
    val result = parser.parse("""
        mob Dragon {
            on_spawn { }
        }
        mob Zombie {
            on_spawn { }
        }
    """.trimIndent())

    assertEquals(2, result.size)
    assertEquals("Dragon", (result[0] as CstNode.Declaration).name)
    assertEquals("Zombie", (result[1] as CstNode.Declaration).name)
}
```

- [ ] **Step 2: Test error on unknown keyword**

```kotlin
@Test
fun `DynamicParser rejects unknown keyword`() {
    val grammar = loadMobsGrammar()
    val parser = DynamicParser(grammar)

    assertThrows<ParseException> {
        parser.parse("item Sword { }")
    }
}
```

- [ ] **Step 3: Test entity grammar from ink.test fixture**

```kotlin
@Test
fun `DynamicParser parses entity with spawn clause`() {
    val grammar = loadTestGrammar()
    val parser = DynamicParser(grammar)
    val result = parser.parse("""
        entity Creeper {
            spawn CreeperSpawner { }
        }
    """.trimIndent())

    assertEquals(1, result.size)
    val decl = result[0] as CstNode.Declaration
    assertEquals("entity", decl.keyword)
    assertEquals("Creeper", decl.name)
    assertEquals(1, decl.body.size)

    val spawnMatch = decl.body[0] as CstNode.RuleMatch
    assertEquals("ink.test/spawn_clause", spawnMatch.ruleName)
}

private fun loadTestGrammar(): GrammarPackage {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val url = javaClass.classLoader.getResource("fixtures/ink.test/dist/grammar.ir.json")!!
    return json.decodeFromString(java.io.File(url.toURI()).readText())
}
```

- [ ] **Step 4: Run all tests**

Run: `./gradlew :lang:test`

- [ ] **Step 5: Commit**

```
test(grammar): add parser tests for multiple decls, errors, and entity grammar
```
