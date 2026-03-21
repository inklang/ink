package org.inklang

/**
 * Interface for I/O operations provided by the host runtime.
 * lang module defines this interface; bukkit module implements it.
 */
interface InkIo {
    /**
     * Read file at path, return contents as string.
     * Path is relative to script directory if relative.
     * Returns empty string if file doesn't exist.
     */
    fun read(path: String): String

    /**
     * Write content to file at path (overwrites).
     * Creates parent directories if they don't exist.
     */
    fun write(path: String, content: String)
}
