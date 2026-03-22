package org.inklang.peg

import org.inklang.lang.Expr
import org.inklang.lang.Stmt
import org.inklang.lang.Token
import org.inklang.lang.TokenType
import org.inklang.peg.combinators.map
import org.inklang.peg.util.SourcePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrammarExtensionTest {

    // Helper to create a simple token
    private fun makeToken(type: TokenType, lexeme: String): Token {
        return Token(type, lexeme, 1, 1)
    }

    // Helper to create a simple statement for testing
    private fun createSimpleStmt(name: String): Stmt {
        return Stmt.ExprStmt(Expr.VariableExpr(makeToken(TokenType.IDENTIFIER, name)))
    }

    // ============== InkExtensionContext Tests ==============

    @Test
    fun `registerStatement adds statement to grammar`() {
        val context = InkExtensionContext()

        // Register a simple statement extension
        val pattern = literal("test").map { "test" }
        context.registerStatement("test") {
            this.pattern = pattern
            this.lower = { createSimpleStmt("test") }
        }

        val grammar = context.build()

        assertTrue(grammar.statements.containsKey("test"))
        assertEquals("test", grammar.statements["test"]?.name)
    }

    @Test
    fun `registerDeclaration adds declaration to grammar`() {
        val context = InkExtensionContext()

        val pattern = literal("decl").map { "decl" }
        context.registerDeclaration("decl") {
            this.pattern = pattern
            this.lower = { createSimpleStmt("decl") }
            this.fields(FieldDef("name", "String"))
            this.blocks("body")
        }

        val grammar = context.build()

        assertTrue(grammar.declarations.containsKey("decl"))
        assertEquals("decl", grammar.declarations["decl"]?.name)
        assertEquals(1, grammar.declarations["decl"]?.fields?.size)
        assertEquals("name", grammar.declarations["decl"]?.fields?.get(0)?.name)
        assertEquals(1, grammar.declarations["decl"]?.blocks?.size)
    }

    @Test
    fun `registerRule adds rule to grammar`() {
        val context = InkExtensionContext()
        val ruleParser = literal("rule").map { "rule" }

        context.registerRule("myRule", ruleParser)

        val grammar = context.build()

        assertTrue(grammar.rules.containsKey("myRule"))
    }

    @Test
    fun `build returns PackageGrammar with all registered extensions`() {
        val context = InkExtensionContext()

        val stmtPattern = literal("stmt").map { "stmt" }
        context.registerStatement("stmt") {
            this.pattern = stmtPattern
            this.lower = { createSimpleStmt("stmt") }
        }

        val declPattern = literal("decl").map { "decl" }
        context.registerDeclaration("decl") {
            this.pattern = declPattern
            this.lower = { createSimpleStmt("decl") }
        }

        val ruleParser = literal("rule").map { "rule" }
        context.registerRule("myRule", ruleParser)

        val grammar = context.build()

        assertEquals(1, grammar.statements.size)
        assertEquals(1, grammar.declarations.size)
        assertEquals(1, grammar.rules.size)
    }

    @Test
    fun `DeclarationExtensionBuilder fields and blocks methods work`() {
        val context = InkExtensionContext()

        context.registerDeclaration("config") {
            this.pattern = literal("config")
            this.fields(
                FieldDef("name", "String"),
                FieldDef("value", "Int")
            )
            this.blocks("init", "cleanup")
            this.lower = { createSimpleStmt("config") }
        }

        val grammar = context.build()
        val decl = grammar.declarations["config"]!!

        assertEquals(2, decl.fields.size)
        assertEquals("name", decl.fields[0].name)
        assertEquals("String", decl.fields[0].type)
        assertEquals("value", decl.fields[1].name)
        assertEquals("Int", decl.fields[1].type)

        assertEquals(2, decl.blocks.size)
        assertEquals("init", decl.blocks[0])
        assertEquals("cleanup", decl.blocks[1])
    }

    // ============== CombinedGrammarBuilder Tests ==============

    @Test
    fun `merge combines multiple extensions`() {
        // Create first package grammar
        val context1 = InkExtensionContext()
        context1.registerStatement("stmt1") {
            this.pattern = literal("stmt1")
            this.lower = { createSimpleStmt("stmt1") }
        }
        val pkg1 = context1.build()

        // Create second package grammar
        val context2 = InkExtensionContext()
        context2.registerStatement("stmt2") {
            this.pattern = literal("stmt2")
            this.lower = { createSimpleStmt("stmt2") }
        }
        val pkg2 = context2.build()

        // Merge
        val builder = CombinedGrammarBuilder()
        val combined = builder.merge(listOf(pkg1, pkg2))

        val parser = combined.buildParser()
        val result = parser.parse("stmt1 stmt2")

        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test
    fun `tryMerge returns MergeError on keyword conflict`() {
        // Create first package grammar with "test" statement
        val context1 = InkExtensionContext()
        context1.registerStatement("test") {
            this.pattern = literal("test")
            this.lower = { createSimpleStmt("test1") }
        }
        val pkg1 = context1.build()

        // Create second package grammar also with "test" statement (conflict!)
        val context2 = InkExtensionContext()
        context2.registerStatement("test") {
            this.pattern = literal("test")
            this.lower = { createSimpleStmt("test2") }
        }
        val pkg2 = context2.build()

        // Try merge
        val builder = CombinedGrammarBuilder()
        val result = builder.tryMerge(listOf(pkg1, pkg2))

        assertIs<MergeResult.MergeError>(result)
        assertEquals("test", result.conflictingKeyword)
    }

    @Test
    fun `tryMerge returns Ok when no conflicts`() {
        val context1 = InkExtensionContext()
        context1.registerStatement("stmt1") {
            this.pattern = literal("stmt1")
            this.lower = { createSimpleStmt("stmt1") }
        }
        val pkg1 = context1.build()

        val context2 = InkExtensionContext()
        context2.registerStatement("stmt2") {
            this.pattern = literal("stmt2")
            this.lower = { createSimpleStmt("stmt2") }
        }
        val pkg2 = context2.build()

        val builder = CombinedGrammarBuilder()
        val result = builder.tryMerge(listOf(pkg1, pkg2))

        assertIs<MergeResult.Ok>(result)
        assertIs<CombinedGrammar>(result.grammar)
    }

    @Test
    fun `merge throws on conflict`() {
        val context1 = InkExtensionContext()
        context1.registerDeclaration("decl") {
            this.pattern = literal("decl")
            this.lower = { createSimpleStmt("decl1") }
        }
        val pkg1 = context1.build()

        val context2 = InkExtensionContext()
        context2.registerDeclaration("decl") {
            this.pattern = literal("decl")
            this.lower = { createSimpleStmt("decl2") }
        }
        val pkg2 = context2.build()

        val builder = CombinedGrammarBuilder()

        var exceptionThrown = false
        try {
            builder.merge(listOf(pkg1, pkg2))
        } catch (e: IllegalStateException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("decl") == true)
        }
        assertTrue(exceptionThrown)
    }

    @Test
    fun `CombinedGrammar from merges rules from multiple packages`() {
        val context1 = InkExtensionContext()
        context1.registerRule("rule1", literal("rule1"))
        val pkg1 = context1.build()

        val context2 = InkExtensionContext()
        context2.registerRule("rule2", literal("rule2"))
        val pkg2 = context2.build()

        val builder = CombinedGrammarBuilder()
        val result = builder.tryMerge(listOf(pkg1, pkg2))

        assertIs<MergeResult.Ok>(result)
        val grammar = result.grammar

        assertIs<ParseResult.Success<List<Stmt>>>(grammar.buildParser().parse("rule1"))
        assertIs<ParseResult.Success<List<Stmt>>>(grammar.buildParser().parse("rule2"))
    }

    // ============== ExtensionRegistry Tests ==============

    @Test
    fun `ExtensionRegistry registers and retrieves packages`() {
        ExtensionRegistry.clear()

        val context = InkExtensionContext()
        context.registerStatement("registry_test") {
            this.pattern = literal("registry_test")
            this.lower = { createSimpleStmt("registry_test") }
        }
        val pkg = context.build()

        ExtensionRegistry.register(pkg)

        val registered = ExtensionRegistry.getRegistered()
        assertEquals(1, registered.size)
        assertEquals(1, registered[0].statements.size)
        assertTrue(registered[0].statements.containsKey("registry_test"))

        ExtensionRegistry.clear()
    }

    @Test
    fun `ExtensionRegistry clear removes all packages`() {
        ExtensionRegistry.clear()

        val context1 = InkExtensionContext()
        context1.registerStatement("test1") {
            this.pattern = literal("test1")
            this.lower = { createSimpleStmt("test1") }
        }
        ExtensionRegistry.register(context1.build())

        val context2 = InkExtensionContext()
        context2.registerStatement("test2") {
            this.pattern = literal("test2")
            this.lower = { createSimpleStmt("test2") }
        }
        ExtensionRegistry.register(context2.build())

        assertEquals(2, ExtensionRegistry.getRegistered().size)

        ExtensionRegistry.clear()

        assertEquals(0, ExtensionRegistry.getRegistered().size)
    }

    // ============== CombinedGrammar buildParser Tests ==============

    @Test
    fun `buildParser parses registered statements`() {
        val context = InkExtensionContext()
        context.registerStatement("hello") {
            this.pattern = literal("hello")
            this.lower = { createSimpleStmt("hello") }
        }
        val pkg = context.build()

        val combined = CombinedGrammar.from(listOf(pkg))
        val parser = combined.buildParser()

        val result = parser.parse("hello")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(1, result.value.size)
    }

    @Test
    fun `buildParser parses multiple statements`() {
        val context = InkExtensionContext()
        context.registerStatement("print") {
            this.pattern = literal("print")
            this.lower = { createSimpleStmt("print") }
        }
        val pkg = context.build()

        val combined = CombinedGrammar.from(listOf(pkg))
        val parser = combined.buildParser()

        val result = parser.parse("print print print")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(3, result.value.size)
    }

    @Test
    fun `buildParser stops at unrecognized input`() {
        val context = InkExtensionContext()
        context.registerStatement("known") {
            this.pattern = literal("known")
            this.lower = { createSimpleStmt("known") }
        }
        val pkg = context.build()

        val combined = CombinedGrammar.from(listOf(pkg))
        val parser = combined.buildParser()

        val result = parser.parse("known unknown")
        assertIs<ParseResult.Success<List<Stmt>>>(result)
        assertEquals(1, result.value.size)
    }
}
