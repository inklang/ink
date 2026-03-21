package org.inklang.bukkit

import io.mockk.mockk
import io.mockk.verify
import org.inklang.InkCompiler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.bukkit.entity.Player
import java.io.File

/**
 * Integration tests for the io, json, and db stdlib drivers.
 * These tests exercise the full pipeline: compile ink script → execute with driver implementations.
 */
class StdlibIntegrationTest {

    private lateinit var server: ServerMock
    private lateinit var sender: Player

    @TempDir
    lateinit var tempDir: File

    private lateinit var ioDriver: BukkitIo
    private lateinit var jsonDriver: BukkitJson
    private lateinit var dbDriver: BukkitDb
    private lateinit var context: BukkitContext

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        sender = server.addPlayer()

        // Create temp directories and files for drivers
        val scriptDir = File(tempDir, "scripts").also { it.mkdirs() }
        val dbFile = File(tempDir, "test.db")

        ioDriver = BukkitIo(scriptDir)
        jsonDriver = BukkitJson()
        dbDriver = BukkitDb(dbFile.absolutePath)
        val mockPlugin = mockk<InkBukkit>(relaxed = true)
        context = BukkitContext(sender, mockPlugin, ioDriver, jsonDriver, dbDriver)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    // === io driver tests ===

    @Test
    fun `io write and read round-trip`() {
        val source = """
            io.write("test.txt", "hello world");
            let content = io.read("test.txt");
            print(content);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] hello world") }
    }

    @Test
    fun `io read returns empty string for missing file`() {
        val source = """
            let content = io.read("nonexistent.txt");
            print(content);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] ") }
    }

    @Test
    fun `io write creates parent directories`() {
        val source = """
            io.write("subdir/nested/file.txt", "nested content");
            let content = io.read("subdir/nested/file.txt");
            print(content);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] nested content") }
    }

    // === json driver tests ===

    @Test
    fun `json parse object`() {
        val source = """
            let obj = json.parse('{"name": "Steve", "age": 42}');
            print(obj.name);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] Steve") }
    }

    @Test
    fun `json parse array`() {
        val source = """
            let arr = json.parse('["a", "b", "c"]');
            print(arr.size());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] 3") }
    }

    @Test
    fun `json stringify map`() {
        val source = """
            let m = {"name": "Alex", "score": 100};
            let s = json.stringify(m);
            print(s);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage(match<String> { it!!.contains("\"name\": \"Alex\"") && it.contains("\"score\": 100") }) }
    }

    @Test
    fun `json stringify array`() {
        val source = """
            let arr = ["x", "y"];
            let s = json.stringify(arr);
            print(s);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] [\"x\", \"y\"]") }
    }

    @Test
    fun `json parse and access nested object`() {
        val source = """
            let obj = json.parse('{"player": {"name": "Herobrine", "level": 99}}');
            print(obj.player.name);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] Herobrine") }
    }

    // === db driver tests ===

    @Test
    fun `table insert and all`() {
        val source = """
            table Player { id isKey, name, score };
            Player.insert({id: 1, name: "Alice", score: 150});
            Player.insert({id: 2, name: "Bob", score: 200});
            let all = Player.all();
            print(all.size());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] 2") }
    }

    @Test
    fun `table find by key`() {
        val source = """
            table Player { id isKey, name, score };
            Player.insert({id: 1, name: "Alice", score: 150});
            Player.insert({id: 2, name: "Bob", score: 200});
            let p = Player.find(1);
            print(p.name);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] Alice") }
    }

    @Test
    fun `table find returns null for missing key`() {
        val source = """
            table Player { id isKey, name };
            Player.insert({id: 1, name: "Alice"});
            let p = Player.find(999);
            print(p ?? "not found");
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] not found") }
    }

    @Test
    fun `table where with condition and args`() {
        val source = """
            table Player { id isKey, name, score };
            Player.insert({id: 1, name: "Alice", score: 50});
            Player.insert({id: 2, name: "Bob", score: 150});
            Player.insert({id: 3, name: "Carol", score: 250});
            let high = Player.where("score > ?", 100).all();
            print(high.size());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] 2") }
    }

    @Test
    fun `table where with order and limit`() {
        val source = """
            table Player { id isKey, name, score };
            Player.insert({id: 1, name: "Alice", score: 50});
            Player.insert({id: 2, name: "Bob", score: 150});
            Player.insert({id: 3, name: "Carol", score: 250});
            let top = Player.where("score > ?", 25).order("score", "desc").limit(2).all();
            print(top.size());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] 2") }
    }

    @Test
    fun `table where first returns single result`() {
        val source = """
            table Player { id isKey, name, score };
            Player.insert({id: 1, name: "Alice", score: 50});
            Player.insert({id: 2, name: "Bob", score: 150});
            let p = Player.where("score > ?", 25).first();
            print(p.name);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] Alice") }
    }

    @Test
    fun `table update`() {
        val source = """
            table Player { id isKey, name, score };
            Player.insert({id: 1, name: "Alice", score: 50});
            Player.update(1, {score: 99});
            let p = Player.find(1);
            print(p.score);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] 99") }
    }

    @Test
    fun `table delete`() {
        val source = """
            table Player { id isKey, name };
            Player.insert({id: 1, name: "Alice"});
            Player.insert({id: 2, name: "Bob"});
            Player.delete(1);
            let all = Player.all();
            print(all.size());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] 1") }
    }

    // === combined tests ===

    @Test
    fun `json and io combined - persist data to file`() {
        // Using $$ to escape $ in Kotlin string interpolation
        val source = """
            let data = {"players": [
                {"name": "Alice", "score": 100},
                {"name": "Bob", "score": 200}
            ]};
            io.write("game_data.json", json.stringify(data));
            let loaded = json.parse(io.read("game_data.json"));
            print(loaded.players.size());
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        verify { sender.sendMessage("§f[Ink] 2") }
    }

    @Test
    fun `db and json combined - export table to json`() {
        val source = """
            table Player { id isKey, name, score };
            Player.insert({id: 1, name: "Alice", score: 100});
            Player.insert({id: 2, name: "Bob", score: 200});
            let all = Player.all();
            let jsonStr = json.stringify(all);
            print(jsonStr);
        """.trimIndent()

        val compiler = InkCompiler()
        val script = compiler.compile(source)
        script.execute(context)

        // Verify that json stringify was called and contains player data
        verify { sender.sendMessage(match<String> { it!!.contains("Alice") && it.contains("Bob") }) }
    }
}
