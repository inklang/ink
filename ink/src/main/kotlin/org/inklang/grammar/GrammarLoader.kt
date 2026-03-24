package org.inklang.grammar

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path

/**
 * Result of loading a package distribution directory.
 */
data class LoadedPackage(
    val manifest: InkManifest,
    val grammar: GrammarPackage?
)

/**
 * Loads Ink package artifacts (manifest + grammar IR) from a dist directory.
 */
object GrammarLoader {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * Load a package from the given dist directory.
     * The directory must contain an ink-manifest.json file.
     */
    fun load(distDir: File): LoadedPackage {
        val manifestFile = distDir.resolve("ink-manifest.json")
        require(manifestFile.exists()) {
            "No ink-manifest.json found in ${distDir.absolutePath}"
        }

        val manifest = json.decodeFromString<InkManifest>(manifestFile.readText())

        val grammar = manifest.grammar?.let { grammarPath ->
            val grammarFile = distDir.resolve(grammarPath)
            require(grammarFile.exists()) {
                "Grammar file '$grammarPath' referenced in manifest not found in ${distDir.absolutePath}"
            }
            json.decodeFromString<GrammarPackage>(grammarFile.readText())
        }

        return LoadedPackage(manifest, grammar)
    }

    /**
     * Load a package from the given dist directory path.
     */
    fun load(distDir: Path): LoadedPackage = load(distDir.toFile())
}
