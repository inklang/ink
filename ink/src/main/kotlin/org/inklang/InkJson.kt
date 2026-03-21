package org.inklang

import org.inklang.lang.Value

/**
 * Interface for JSON operations provided by the host runtime.
 * lang module defines this interface; bukkit module implements it.
 */
interface InkJson {
    /**
     * Parse JSON string, return inklang Value.
     * Objects → Value.Instance (Map), Arrays → Value.Instance (Array), primitives → scalar Value.
     * Throws RuntimeException on invalid JSON.
     */
    fun parse(json: String): Value

    /**
     * Convert inklang Value to JSON string.
     */
    fun stringify(value: Value): String
}
