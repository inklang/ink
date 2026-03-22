package org.inklang.grammar

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrammarIRTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixtureDir(name: String): File {
        val url = javaClass.classLoader.getResource("fixtures/$name/dist")
            ?: error("Fixture not found: $name")
        return File(url.toURI())
    }

    private fun fixturesParent(): File {
        val url = javaClass.classLoader.getResource("fixtures/ink.test/dist")
            ?: error("Fixtures not found")
        // Go up from fixtures/ink.test/dist -> fixtures/
        return File(url.toURI()).parentFile.parentFile
    }

    // --- Deserialization tests ---

    @Test
    fun `deserialize grammar ir json round-trip`() {
        val grammarJson = fixtureDir("ink.test").resolve("grammar.ir.json").readText()
        val grammar = json.decodeFromString<GrammarPackage>(grammarJson)

        assertEquals(1, grammar.version)
        assertEquals("ink.test", grammar.packageName)
        assertEquals(listOf("spawn", "entity"), grammar.keywords)
        assertEquals(1, grammar.rules.size)
        assertTrue(grammar.rules.containsKey("ink.test/spawn_clause"))

        val spawnRule = grammar.rules["ink.test/spawn_clause"]!!
        assertNull(spawnRule.handler)
        val seq = spawnRule.rule as Rule.Seq
        assertEquals(3, seq.items.size)
        assertEquals(Rule.Keyword("spawn"), seq.items[0])
        assertEquals(Rule.Identifier, seq.items[1])
        assertEquals(Rule.Block(null), seq.items[2])

        assertEquals(1, grammar.declarations.size)
        val decl = grammar.declarations[0]
        assertEquals("entity", decl.keyword)
        assertEquals(Rule.Identifier, decl.nameRule)
        assertEquals(listOf("ink.test/spawn_clause"), decl.scopeRules)
        assertTrue(decl.inheritsBase)
        assertNull(decl.handler)

        // Round-trip: serialize back and deserialize again
        val reserialized = json.encodeToString(GrammarPackage.serializer(), grammar)
        val reparsed = json.decodeFromString<GrammarPackage>(reserialized)
        assertEquals(grammar, reparsed)
    }

    @Test
    fun `deserialize mobs grammar ir json`() {
        val grammarJson = fixtureDir("ink.mobs").resolve("grammar.ir.json").readText()
        val grammar = json.decodeFromString<GrammarPackage>(grammarJson)

        assertEquals("ink.mobs", grammar.packageName)
        assertEquals(listOf("mob", "on_spawn"), grammar.keywords)
        assertEquals(1, grammar.rules.size)

        val onSpawnRule = grammar.rules["ink.mobs/on_spawn_clause"]!!
        val seq = onSpawnRule.rule as Rule.Seq
        assertEquals(2, seq.items.size)
        assertEquals(Rule.Keyword("on_spawn"), seq.items[0])
        assertEquals(Rule.Block(null), seq.items[1])
    }

    @Test
    fun `deserialize manifest with runtime`() {
        val manifestJson = fixtureDir("ink.mobs").resolve("ink-manifest.json").readText()
        val manifest = json.decodeFromString<InkManifest>(manifestJson)

        assertEquals("ink.mobs", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertEquals("grammar.ir.json", manifest.grammar)
        assertNotNull(manifest.runtime)
        assertEquals("runtime.jar", manifest.runtime!!.jar)
        assertEquals("org.ink.mobs.MobsRuntime", manifest.runtime!!.entry)
    }

    @Test
    fun `deserialize manifest without runtime`() {
        val manifestJson = fixtureDir("ink.test").resolve("ink-manifest.json").readText()
        val manifest = json.decodeFromString<InkManifest>(manifestJson)

        assertEquals("ink.test", manifest.name)
        assertNull(manifest.runtime)
    }

    // --- GrammarLoader tests ---

    @Test
    fun `GrammarLoader loads package from fixture directory`() {
        val loaded = GrammarLoader.load(fixtureDir("ink.test"))

        assertEquals("ink.test", loaded.manifest.name)
        assertNotNull(loaded.grammar)
        assertEquals("ink.test", loaded.grammar!!.packageName)
        assertEquals(listOf("spawn", "entity"), loaded.grammar!!.keywords)
    }

    @Test
    fun `GrammarLoader loads package with runtime descriptor`() {
        val loaded = GrammarLoader.load(fixtureDir("ink.mobs"))

        assertEquals("ink.mobs", loaded.manifest.name)
        assertNotNull(loaded.grammar)
        assertNotNull(loaded.manifest.runtime)
        assertEquals("org.ink.mobs.MobsRuntime", loaded.manifest.runtime!!.entry)
    }

    @Test
    fun `GrammarLoader fails on missing manifest`() {
        val exception = assertThrows<IllegalArgumentException> {
            GrammarLoader.load(File("/nonexistent/path"))
        }
        assertTrue(exception.message!!.contains("ink-manifest.json"))
    }

    // --- PackageRegistry tests ---

    @Test
    fun `PackageRegistry loads multiple packages`() {
        val registry = PackageRegistry()
        val packages = registry.loadAll(fixturesParent())

        assertEquals(2, packages.size)
        val names = packages.map { it.manifest.name }.toSet()
        assertTrue(names.contains("ink.test"))
        assertTrue(names.contains("ink.mobs"))
    }

    @Test
    fun `PackageRegistry merges grammars from multiple packages`() {
        val registry = PackageRegistry()
        registry.loadAll(fixturesParent())
        val merged = registry.merge()

        assertEquals(setOf("spawn", "entity", "mob", "on_spawn"), merged.keywords)
        assertEquals(2, merged.rules.size)
        assertTrue(merged.rules.containsKey("ink.test/spawn_clause"))
        assertTrue(merged.rules.containsKey("ink.mobs/on_spawn_clause"))
        assertEquals(2, merged.declarations.size)
    }

    // --- DynamicLexer tests ---

    private fun loadMobsGrammar(): GrammarPackage {
        val grammarJson = fixtureDir("ink.mobs").resolve("grammar.ir.json").readText()
        return json.decodeFromString(grammarJson)
    }

    private fun loadTestGrammar(): GrammarPackage {
        val grammarJson = fixtureDir("ink.test").resolve("grammar.ir.json").readText()
        return json.decodeFromString(grammarJson)
    }

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

    // --- DynamicParser tests ---

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
        assertEquals(2, scopeMatch.children.size)
        assertEquals(CstNode.KeywordNode("on_spawn"), scopeMatch.children[0])
        val block = scopeMatch.children[1] as CstNode.Block
        assertEquals(null, block.scope)
        assertEquals(0, block.children.size)
    }

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

    @Test
    fun `DynamicParser rejects unknown keyword`() {
        val grammar = loadMobsGrammar()
        val parser = DynamicParser(grammar)

        assertThrows<ParseException> {
            parser.parse("item Sword { }")
        }
    }

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

    @Test
    fun `PackageRegistry detects keyword conflicts`() {
        // Create a temporary fixture that conflicts with ink.test
        val tempDir = createTempDirectory("ink-conflict-test").toFile()
        try {
            // Package A
            val pkgADir = File(tempDir, "pkg.a/dist").also { it.mkdirs() }
            pkgADir.resolve("ink-manifest.json").writeText("""
                {"name": "pkg.a", "version": "1.0.0", "grammar": "grammar.ir.json"}
            """.trimIndent())
            pkgADir.resolve("grammar.ir.json").writeText("""
                {"version": 1, "package": "pkg.a", "keywords": ["spawn"], "rules": {}, "declarations": []}
            """.trimIndent())

            // Package B — also defines "spawn"
            val pkgBDir = File(tempDir, "pkg.b/dist").also { it.mkdirs() }
            pkgBDir.resolve("ink-manifest.json").writeText("""
                {"name": "pkg.b", "version": "1.0.0", "grammar": "grammar.ir.json"}
            """.trimIndent())
            pkgBDir.resolve("grammar.ir.json").writeText("""
                {"version": 1, "package": "pkg.b", "keywords": ["spawn"], "rules": {}, "declarations": []}
            """.trimIndent())

            val registry = PackageRegistry()
            registry.loadAll(tempDir)

            val exception = assertThrows<KeywordConflictException> {
                registry.merge()
            }
            assertTrue(exception.message!!.contains("spawn"))
            assertTrue(exception.message!!.contains("pkg.a") || exception.message!!.contains("pkg.b"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
