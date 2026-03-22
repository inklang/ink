package org.inklang.peg

/**
 * Registry for all registered package grammars.
 * This object is synchronized for thread-safe access.
 */
object ExtensionRegistry {
    private val registeredPackages = mutableListOf<PackageGrammar>()

    /**
     * Registers a package grammar with the global registry.
     * @param pkg The package grammar to register
     */
    @Synchronized
    fun register(pkg: PackageGrammar) {
        registeredPackages.add(pkg)
    }

    /**
     * Returns all registered package grammars.
     * @return List of all registered package grammars
     */
    @Synchronized
    fun getRegistered(): List<PackageGrammar> {
        return registeredPackages.toList()
    }

    /**
     * Clears all registered package grammars.
     * Primarily used for testing.
     */
    @Synchronized
    fun clear() {
        registeredPackages.clear()
    }
}
