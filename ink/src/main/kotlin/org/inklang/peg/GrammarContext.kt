package org.inklang.peg

/**
 * Mutable rule registry for grammar definitions.
 * Allows rules to be registered and retrieved by name.
 */
class GrammarContext {
    private val rules = mutableMapOf<String, Parser<*>>()

    /**
     * Registers a named rule in this grammar context.
     */
    fun <T> rule(name: String, parser: Parser<T>) {
        rules[name] = parser
    }

    /**
     * Retrieves a registered rule by name.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getRule(name: String): Parser<T>? {
        return rules[name] as? Parser<T>
    }

    /**
     * Checks if a rule with the given name exists.
     */
    fun hasRule(name: String): Boolean = rules.containsKey(name)

    /**
     * Returns all registered rule names.
     */
    fun ruleNames(): Set<String> = rules.keys.toSet()
}
