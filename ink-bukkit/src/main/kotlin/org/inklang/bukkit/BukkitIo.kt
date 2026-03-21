package org.inklang.bukkit

import org.inklang.InkIo
import java.io.File

/**
 * Bukkit implementation of InkIo using java.io.File.
 * Paths are resolved relative to the script directory.
 */
class BukkitIo(private val scriptDir: File) : InkIo {
    override fun read(path: String): String {
        val file = resolvePath(path)
        return if (file.exists()) file.readText() else ""
    }

    override fun write(path: String, content: String) {
        val file = resolvePath(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun resolvePath(path: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(scriptDir, path)
    }
}
