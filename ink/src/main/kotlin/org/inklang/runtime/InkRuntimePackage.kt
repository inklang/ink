package org.inklang.runtime

import org.inklang.lang.Value

/**
 * Interface for extending the Ink runtime with custom declaration and statement handlers.
 * This is how config, table, and future domain-specific keywords are implemented.
 *
 * Runtime packages are registered with the VM before script execution.
 * Multiple packages can be registered. If two packages claim the same keyword,
 * the VM raises a configuration error at registration time.
 */
interface InkRuntimePackage {
    /** Unique package identifier (e.g., "ink.paper", "ink.data") */
    fun packageName(): String

    /** List of declaration keywords this package handles (e.g., ["config", "table"]) */
    fun handledDeclarations(): List<String>

    /** List of statement keywords this package handles */
    fun handledStatements(): List<String>

    /**
     * Handle a declaration keyword (config, table, etc.)
     * Called when the VM encounters a declaration with a keyword from handledDeclarations().
     * Must return a Value to bind to the declaration name as a global.
     */
    fun handleDeclaration(keyword: String, node: DeclarationNode): Value

    /**
     * Handle a custom statement keyword.
     * Called when the VM encounters a statement with a keyword from handledStatements().
     */
    fun handleStatement(keyword: String, node: StatementNode)
}

/**
 * Parsed declaration data provided to runtime packages.
 */
data class DeclarationNode(
    val name: String,
    val fields: List<DeclarationField>,
    val metadata: Map<String, Any> = emptyMap()
)

data class DeclarationField(
    val name: String,
    val type: String,
    val isKey: Boolean,
    val defaultValue: Value?
)

/**
 * Parsed statement data provided to runtime packages.
 */
data class StatementNode(
    val keyword: String,
    val arguments: List<Value>,
    val metadata: Map<String, Any> = emptyMap()
)
