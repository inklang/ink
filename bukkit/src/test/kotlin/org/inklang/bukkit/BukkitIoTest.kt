package org.inklang.bukkit

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Unit tests for BukkitIo - tests the file I/O driver directly without MockBukkit.
 */
class BukkitIoTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `write and read roundtrip`() {
        val scriptDir = File(tempDir, "scripts").also { it.mkdirs() }
        val io = BukkitIo(scriptDir)

        io.write("test.txt", "hello world")
        val content = io.read("test.txt")

        assertEquals("hello world", content)
    }

    @Test
    fun `read returns empty string for missing file`() {
        val scriptDir = File(tempDir, "scripts").also { it.mkdirs() }
        val io = BukkitIo(scriptDir)

        val content = io.read("nonexistent.txt")

        assertEquals("", content)
    }

    @Test
    fun `write creates parent directories`() {
        val scriptDir = File(tempDir, "scripts").also { it.mkdirs() }
        val io = BukkitIo(scriptDir)

        io.write("subdir/nested/file.txt", "nested content")
        val content = io.read("subdir/nested/file.txt")

        assertEquals("nested content", content)
    }

    @Test
    fun `relative paths resolved against script dir`() {
        val scriptDir = File(tempDir, "scripts").also { it.mkdirs() }
        val io = BukkitIo(scriptDir)

        io.write("relative.txt", "relative content")
        val content = io.read("relative.txt")

        assertEquals("relative content", content)
    }

    @Test
    fun `absolute paths bypass script dir`() {
        val scriptDir = File(tempDir, "scripts").also { it.mkdirs() }
        val io = BukkitIo(scriptDir)

        val absFile = File(tempDir, "absolute.txt")
        io.write(absFile.absolutePath, "absolute content")
        val content = io.read(absFile.absolutePath)

        assertEquals("absolute content", content)
    }

    @Test
    fun `overwrite existing file`() {
        val scriptDir = File(tempDir, "scripts").also { it.mkdirs() }
        val io = BukkitIo(scriptDir)

        io.write("test.txt", "first")
        io.write("test.txt", "second")

        val content = io.read("test.txt")
        assertEquals("second", content)
    }
}
