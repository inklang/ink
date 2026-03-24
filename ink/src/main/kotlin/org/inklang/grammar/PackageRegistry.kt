package org.inklang.grammar

import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL
import java.nio.file.Path

/**
 * The merged result of loading multiple grammar packages.
 */
data class MergedGrammar(
    val keywords: Set<String>,
    val rules: Map<String, RuleEntry>,
    val declarations: List<DeclarationDef>
)

private val grammarJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Loads and merges multiple Ink packages from a parent directory.
 * Each subdirectory that contains an ink-manifest.json is treated as a package dist.
 */
class PackageRegistry {

    private val packages = mutableListOf<LoadedPackage>()

    /**
     * Scan a parent directory for package dist subdirectories and load them all.
     */
    fun loadAll(parentDir: File): List<LoadedPackage> {
        val subdirs = parentDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (subdir in subdirs) {
            // Each package has its dist/ subdirectory
            val distDir = subdir.resolve("dist")
            val manifestInDist = distDir.resolve("ink-manifest.json")
            // Also check if manifest is directly in the subdirectory
            val manifestDirect = subdir.resolve("ink-manifest.json")

            val dir = when {
                manifestInDist.exists() -> distDir
                manifestDirect.exists() -> subdir
                else -> continue
            }
            packages.add(GrammarLoader.load(dir))
        }
        return packages.toList()
    }

    /**
     * Load grammars from the JVM classpath (e.g., from a plugin JAR).
     * Searches for classpath resources matching basePaths/ink-manifest.json.
     *
     * @param basePaths List of classpath root prefixes to search under, e.g. "ink/bukkit/dist"
     * @param classLoader The ClassLoader to search in. Defaults to the current thread's context classloader.
     */
    fun loadFromClasspath(basePaths: List<String>, classLoader: ClassLoader? = null): List<LoadedPackage> {
        val loader = classLoader ?: Thread.currentThread().contextClassLoader
        val result = mutableListOf<LoadedPackage>()

        for (basePath in basePaths) {
            val manifestPath = "$basePath/ink-manifest.json"
            val urls = loader.getResources(manifestPath).toList()
            for (url in urls) {
                try {
                    val loaded = loadFromManifestUrl(url)
                    if (loaded != null) result.add(loaded)
                } catch (e: Exception) {
                    // Skip invalid grammar packages on classpath
                    System.err.println("[Ink] Warning: failed to load grammar from $url: ${e.message}")
                }
            }
        }
        return result
    }

    /**
     * Load a grammar package from a classpath URL of ink-manifest.json.
     * Infers the grammar IR path from the manifest's "grammar" field relative to the manifest URL.
     */
    private fun loadFromManifestUrl(manifestUrl: URL): LoadedPackage? {
        val manifestText = manifestUrl.openConnection().inputStream.bufferedReader().readText()
        val manifest = grammarJson.decodeFromString<InkManifest>(manifestText)

        val grammar = manifest.grammar?.let { grammarPath ->
            // Resolve grammar path relative to the manifest's directory
            val grammarUrl = URL(manifestUrl, grammarPath)
            val grammarText = grammarUrl.openConnection().inputStream.bufferedReader().readText()
            grammarJson.decodeFromString<GrammarPackage>(grammarText)
        }

        return LoadedPackage(manifest, grammar)
    }

    /**
     * Scan a parent directory for package dist subdirectories and load them all.
     */
    fun loadAll(parentDir: Path): List<LoadedPackage> = loadAll(parentDir.toFile())

    /**
     * Get all loaded packages.
     */
    fun getPackages(): List<LoadedPackage> = packages.toList()

    /**
     * Merge all loaded grammars into a single combined grammar.
     * Throws [KeywordConflictException] if two packages define the same keyword.
     */
    fun merge(): MergedGrammar {
        val allKeywords = mutableSetOf<String>()
        val allRules = mutableMapOf<String, RuleEntry>()
        val allDeclarations = mutableListOf<DeclarationDef>()
        // Track which package owns each keyword for error messages
        val keywordOwners = mutableMapOf<String, String>()

        for (pkg in packages) {
            val grammar = pkg.grammar ?: continue
            val pkgName = grammar.packageName

            for (keyword in grammar.keywords) {
                val existingOwner = keywordOwners[keyword]
                if (existingOwner != null) {
                    throw KeywordConflictException(
                        "Keyword '$keyword' is defined by both '$existingOwner' and '$pkgName'"
                    )
                }
                keywordOwners[keyword] = pkgName
                allKeywords.add(keyword)
            }

            allRules.putAll(grammar.rules)
            allDeclarations.addAll(grammar.declarations)
        }

        return MergedGrammar(allKeywords, allRules, allDeclarations)
    }
}

/**
 * Thrown when two packages define the same keyword.
 */
class KeywordConflictException(message: String) : RuntimeException(message)
