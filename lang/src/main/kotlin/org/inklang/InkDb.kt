package org.inklang

import org.inklang.lang.Value

/**
 * Interface for database operations provided by the host runtime.
 * Supabase-style query builder API.
 * lang module defines this interface; bukkit module implements it.
 */
interface InkDb {
    /**
     * Get a reference for the named table.
     * Called when `Player.all()`, `Player.find()` etc. is executed.
     * @throws if table schema hasn't been registered via registerTable()
     */
    fun from(table: String): InkTableRef

    /**
     * Register a table schema. Called when `table Player { ... }` declaration is lowered.
     */
    fun registerTable(name: String, fields: List<String>, keyIndex: Int)
}

/**
 * Reference to a database table with Supabase-style query builder API.
 */
interface InkTableRef {
    /** SELECT * FROM table — returns array of row instances */
    fun all(): Value

    /** SELECT WHERE key = ? — returns single row instance or null */
    fun find(key: Value): Value?

    /** INSERT row, returns created row instance */
    fun insert(data: Map<String, Value>): Value

    /** UPDATE row WHERE key = ? */
    fun update(key: Value, data: Map<String, Value>)

    /** DELETE row WHERE key = ? */
    fun delete(key: Value)

    /**
     * Start a WHERE clause — returns a query builder.
     * @param condition SQL condition string (e.g. "score > ?")
     * @param args values to bind to ? placeholders
     */
    fun where(condition: String, vararg args: Value): InkQueryBuilder

    /** Apply ORDER BY clause (chainable on TableRef directly) */
    fun order(field: String, direction: String): InkTableRef

    /** Apply LIMIT clause (chainable on TableRef directly) */
    fun limit(n: Int): InkTableRef
}

/**
 * Chainable query builder from a where() call.
 * Supports .order().limit().all() and .first()
 */
interface InkQueryBuilder {
    fun order(field: String, direction: String): InkQueryBuilder
    fun limit(n: Int): InkQueryBuilder
    /** Execute query, return array of row instances */
    fun all(): Value
    /** Execute query, return first row instance or null */
    fun first(): Value?
}
