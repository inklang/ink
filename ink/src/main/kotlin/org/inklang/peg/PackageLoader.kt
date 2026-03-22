package org.inklang.peg

import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Discovers and loads .jar packages from a directory, parsing their manifest.toml files.
 */
class PackageLoader(private val packagesDir: File) {

    data class LoadedPackage(
        val manifest: PackageManifest,
        val grammar: PackageGrammar,
        val classLoader: ClassLoader
    )

    /**
     * Load all packages from the packages directory.
     * Each package is a .jar file containing a manifest.toml at its root.
     */
    fun loadAll(): List<LoadedPackage> {
        if (!packagesDir.exists() || !packagesDir.isDirectory) {
            return emptyList()
        }

        return packagesDir.listFiles { file ->
            file.isFile && file.name.endsWith(".jar")
        }?.mapNotNull { jarFile ->
            loadPackage(jarFile)
        } ?: emptyList()
    }

    private fun loadPackage(jarFile: File): LoadedPackage? {
        return try {
            val manifestContent = readManifestFromJar(jarFile) ?: return null
            val manifest = PackageManifest.parse(manifestContent)

            // Create a ClassLoader for the jar
            val classLoader = StubClassLoader(jarFile)

            // Build an empty PackageGrammar from the manifest
            // In a full implementation, we would load the package's grammar extensions
            val grammar = PackageGrammar(
                name = manifest.name,
                statements = emptyMap(),
                declarations = emptyMap(),
                rules = emptyMap()
            )

            LoadedPackage(
                manifest = manifest,
                grammar = grammar,
                classLoader = classLoader
            )
        } catch (e: Exception) {
            System.err.println("Failed to load package from ${jarFile.name}: ${e.message}")
            null
        }
    }

    private fun readManifestFromJar(jarFile: File): String? {
        return try {
            JarFile(jarFile).use { jar ->
                val entry = jar.getEntry("manifest.toml")
                if (entry != null) {
                    jar.getInputStream(entry).bufferedReader().readText()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            System.err.println("Error reading manifest from ${jarFile.name}: ${e.message}")
            null
        }
    }

    /**
     * Sort packages in topological order based on dependencies.
     * Packages with no dependencies come first, then packages that depend on them.
     *
     * @throws IllegalArgumentException if a circular dependency is detected
     */
    fun topologicalSort(packages: List<LoadedPackage>): List<LoadedPackage> {
        if (packages.isEmpty()) return emptyList()

        val packageByName = packages.associateBy { it.manifest.name }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val result = mutableListOf<LoadedPackage>()

        fun visit(pkg: LoadedPackage) {
            if (pkg.manifest.name in visited) return

            if (pkg.manifest.name in visiting) {
                throw IllegalArgumentException("Circular dependency detected involving package: ${pkg.manifest.name}")
            }

            visiting.add(pkg.manifest.name)

            // Visit all dependencies first
            for ((depName, _) in pkg.manifest.dependencies) {
                val dep = packageByName[depName]
                if (dep != null) {
                    visit(dep)
                }
                // If dependency is not found in our set, we skip it (external dependency)
            }

            visiting.remove(pkg.manifest.name)
            visited.add(pkg.manifest.name)
            result.add(pkg)
        }

        for (pkg in packages) {
            visit(pkg)
        }

        return result
    }

    /**
     * A stub classloader that simply returns the package class if it's on the classpath.
     * In a full implementation, this would actually load classes from the jar file.
     */
    private class StubClassLoader(private val jarFile: File) : ClassLoader() {
        override fun findClass(name: String): Class<*> {
            // For the initial implementation, we delegate to the system classloader
            // A full implementation would extract and load classes from the jar
            throw ClassNotFoundException("Class $name not found in package jar: ${jarFile.name}")
        }
    }
}
