package org.inklang.peg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.File

class PackageLoaderTest {

    @Test
    fun `parse valid manifest`() {
        val toml = """
            [package]
            name = "mobs"
            version = "1.0.0"
            ink_version = ">=0.3.0"
            description = "First-class mob definitions for Ink"

            [dependencies]
            ink-core = "0.3.0"
            ink-events = ">=0.2.0"

            [visibility]
            pub_script = ["mobs::spawn", "mobs::despawn"]
            pub = ["mobs::register_mob"]
        """.trimIndent()

        val manifest = PackageManifest.parse(toml)

        assertEquals("mobs", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertEquals(">=0.3.0", manifest.inkVersion)
        assertEquals("First-class mob definitions for Ink", manifest.description)

        assertEquals(2, manifest.dependencies.size)
        assertEquals("ink-core" to "0.3.0", manifest.dependencies[0])
        assertEquals("ink-events" to ">=0.2.0", manifest.dependencies[1])

        assertEquals(listOf("mobs::spawn", "mobs::despawn"), manifest.visibility.pubScript)
        assertEquals(listOf("mobs::register_mob"), manifest.visibility.pub)
    }

    @Test
    fun `parse minimal manifest`() {
        val toml = """
            [package]
            name = "test"
            version = "0.1.0"
            ink_version = "0.3.0"
        """.trimIndent()

        val manifest = PackageManifest.parse(toml)

        assertEquals("test", manifest.name)
        assertEquals("0.1.0", manifest.version)
        assertEquals("0.3.0", manifest.inkVersion)
        assertEquals("", manifest.description)
        assertTrue(manifest.dependencies.isEmpty())
        assertTrue(manifest.visibility.pubScript.isEmpty())
        assertTrue(manifest.visibility.pub.isEmpty())
    }

    @Test
    fun `parse manifest with single dependency`() {
        val toml = """
            [package]
            name = "events"
            version = "0.2.0"
            ink_version = ">=0.2.0"

            [dependencies]
            ink-core = "0.3.0"
        """.trimIndent()

        val manifest = PackageManifest.parse(toml)

        assertEquals(1, manifest.dependencies.size)
        assertEquals("ink-core" to "0.3.0", manifest.dependencies[0])
    }

    @Test
    fun `parse manifest with empty arrays`() {
        val toml = """
            [package]
            name = "empty"
            version = "1.0.0"
            ink_version = "1.0.0"

            [visibility]
            pub_script = []
            pub = []
        """.trimIndent()

        val manifest = PackageManifest.parse(toml)

        assertTrue(manifest.visibility.pubScript.isEmpty())
        assertTrue(manifest.visibility.pub.isEmpty())
    }

    private fun createTestPackage(name: String, dependencies: List<Pair<String, String>> = emptyList()): PackageLoader.LoadedPackage {
        val manifest = PackageManifest(
            name = name,
            version = "1.0.0",
            inkVersion = "1.0.0",
            dependencies = dependencies
        )
        val grammar = PackageGrammar(
            name = name,
            statements = emptyMap(),
            declarations = emptyMap(),
            rules = emptyMap()
        )
        // Use a stub ClassLoader that doesn't load from any jar
        val stubClassLoader = object : ClassLoader() {}
        return PackageLoader.LoadedPackage(
            manifest = manifest,
            grammar = grammar,
            classLoader = stubClassLoader
        )
    }

    @Test
    fun `topologicalSort orders by dependencies`() {
        // Create packages in random order
        val pkgC = createTestPackage("c", listOf("b" to "1.0.0"))
        val pkgB = createTestPackage("b", listOf("a" to "1.0.0"))
        val pkgA = createTestPackage("a")

        val loader = PackageLoader(File("."))
        val sorted = loader.topologicalSort(listOf(pkgC, pkgA, pkgB))

        assertEquals("a", sorted[0].manifest.name)
        assertEquals("b", sorted[1].manifest.name)
        assertEquals("c", sorted[2].manifest.name)
    }

    @Test
    fun `topologicalSort throws on circular dependency`() {
        val pkgA = createTestPackage("a", listOf("b" to "1.0.0"))
        val pkgB = createTestPackage("b", listOf("a" to "1.0.0"))

        val loader = PackageLoader(File("."))

        assertFailsWith<IllegalArgumentException> {
            loader.topologicalSort(listOf(pkgA, pkgB))
        }
    }

    @Test
    fun `topologicalSort handles independent packages`() {
        val pkgX = createTestPackage("x")
        val pkgY = createTestPackage("y")

        val loader = PackageLoader(File("."))
        val sorted = loader.topologicalSort(listOf(pkgX, pkgY))

        // Both should be present, order doesn't matter for independent packages
        assertEquals(2, sorted.size)
        assertTrue(sorted.any { it.manifest.name == "x" })
        assertTrue(sorted.any { it.manifest.name == "y" })
    }

    @Test
    fun `loadAll returns empty list for non-existent directory`() {
        val loader = PackageLoader(File("/non/existent/directory"))
        val packages = loader.loadAll()
        assertTrue(packages.isEmpty())
    }
}
