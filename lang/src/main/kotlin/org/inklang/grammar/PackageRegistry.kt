package org.inklang.grammar

import java.io.File
import java.nio.file.Path

/**
 * The merged result of loading multiple grammar packages.
 */
data class MergedGrammar(
    val keywords: Set<String>,
    val rules: Map<String, RuleEntry>,
    val declarations: List<DeclarationDef>
)

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
