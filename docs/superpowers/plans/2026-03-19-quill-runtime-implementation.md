# Quill Runtime v1 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the quill-runtime v1: a `quill-core` module (JVM lib) and a `quill-paper` module (Paper plugin) that can load and execute Quill scripts via `/quill load` command, with `log` and `print` functions backed by a `QuillContext`.

**Architecture:** Module split via Gradle multi-project. `quill-core` exposes `QuillCompiler`, `QuillContext`, and `CompiledScript`. `quill-paper` depends on `quill-core`, provides `QuillContextImpl`, and bootstraps the Paper plugin. Instruction counter timeout in VM prevents runaway scripts.

**Tech Stack:** Kotlin 2.2.21, JVM 21, Gradle, Paper API 1.21.4, MockBukkit

---

## Chunk 1: Gradle Multi-Module Setup

### Task 1: Configure Gradle multi-module project

**Files:**
- Modify: `settings.gradle.kts`
- Create: `quill-core/build.gradle.kts`
- Create: `quill-paper/build.gradle.kts`
- Create: `quill-paper/src/main/resources/plugin.yml`

- [ ] **Step 1: Update `settings.gradle.kts`**

Replace the current content with:

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "quill"

include("quill-core")
include("quill-paper")
include("quill-intellij")
include("quill-vscode")
```

- [ ] **Step 2: Create `quill-core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
}

group = "org.inklang"
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Create `quill-paper/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
}

group = "org.inklang"
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":quill-core"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("com.github.seeseemelk:MockBukkit-Paper:1.103.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Create `quill-paper/src/main/resources/plugin.yml`**

```yaml
name: Quill
version: 1.0-SNAPSHOT
main: org.inklang.paper.QuillPlugin
api-version: '1.21'
```

- [ ] **Step 5: Move existing source files into `quill-core/`**

First, delete files that depend on external dependencies not available in quill-core:

```bash
# ConfigRuntime and TableRuntime depend on snakeyaml/sqlite — remove them before the move
rm -f src/main/kotlin/org/quill/lang/ConfigRuntime.kt
rm -f src/main/kotlin/org/quill/lang/TableRuntime.kt
```

Now move the source files:

```bash
# macOS/Linux
mv src/main/kotlin/org/quill quill-core/src/main/kotlin/org/quill/
# Only move resources/test if they exist
[ -d src/main/resources ] && mv src/main/resources quill-core/src/main/resources/
[ -d src/test ] && mv src/test quill-core/src/test/
```

Windows equivalent:
```bash
move src\main\kotlin\org\quill quill-core\src\main\kotlin\org\quill\
if exist src\main\resources move src\main\resources quill-core\src\main\resources\
if exist src\test move src\test quill-core\src\test\
```

- [ ] **Step 6: Create stub `quill-paper/src/main/kotlin/org/quill/paper/` directory**

```bash
mkdir -p quill-paper/src/main/kotlin/org/quill/paper/commands
mkdir -p quill-paper/src/main/resources
mkdir -p quill-paper/src/test/kotlin/org/quill/paper
mkdir -p quill-paper/src/test/resources
```

- [ ] **Step 7: Create `quill-paper/src/main/kotlin/org/quill/paper/.gitkeep`**

Touch the directory to ensure it exists in git before building.

- [ ] **Step 8: Update `quill-core/src/main/kotlin/org/quill/lang/VM.kt` to remove print builtin**

In `VM.kt`, the `print` builtin should be removed from `globals`. The Paper runtime will provide it via `QuillContext`. For now, remove it from `VM.globals`:

```kotlin
val globals = mutableMapOf<String, Value>(
    "Array" to Value.Class(Builtins.ArrayClass),
    "Map" to Value.Class(Builtins.MapClass),
    "EnumValue" to Value.Class(Builtins.EnumValueClass),
    "EnumNamespace" to Value.Class(Builtins.EnumNamespaceClass),
    // print and log are provided by QuillContext at runtime, not here
)
```

- [ ] **Step 9: Run build to verify module setup**

```bash
./gradlew :quill-core:build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts quill-core/ quill-paper/
git add -u  # stage removed source files
git commit -m "feat: split into quill-core and quill-paper Gradle modules"
```

---

## Chunk 2: QuillCompiler, QuillContext, CompiledScript

### Task 2: Create core API classes

**Files:**
- Create: `quill-core/src/main/kotlin/org/quill/QuillContext.kt`
- Create: `quill-core/src/main/kotlin/org/quill/CompiledScript.kt`
- Create: `quill-core/src/main/kotlin/org/quill/QuillCompiler.kt`

- [ ] **Step 1: Write the failing test**

Create `quill-core/src/test/kotlin/org/quill/QuillCompilerTest.kt`:

```kotlin
package org.inklang

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class QuillCompilerTest {
    private val compiler = QuillCompiler()

    @Test
    fun testCompileReturnsNonNull() {
        val result = compiler.compile("print(42)")
        assertNotNull(result)
    }

    @Test
    fun testCompileAndExecuteWithFakeContext() {
        val ctx = FakeQuillContext()
        val result = compiler.compile("print(42)")
        result.execute(ctx)
        assertEquals(listOf("42"), ctx.prints)
    }

    @Test
    fun testLogFunction() {
        val ctx = FakeQuillContext()
        val result = compiler.compile("log(\"hello\")")
        result.execute(ctx)
        assertEquals(listOf("hello"), ctx.logs)
    }

    @Test
    fun testCompilationErrorThrows() {
        assertThrows(Exception::class.java) {
            compiler.compile("let x =")
        }
    }
}

class FakeQuillContext : QuillContext {
    val logs = mutableListOf<String>()
    val prints = mutableListOf<String>()
    override fun log(message: String) { logs.add(message) }
    override fun print(message: String) { prints.add(message) }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :quill-core:test --tests "org.inklang.QuillCompilerTest" 2>&1 | tail -20
```

Expected: FAIL — `QuillCompiler` class does not exist

- [ ] **Step 3: Create `QuillContext.kt`**

```kotlin
package org.inklang

/**
 * Interface provided by the runtime host to scripts.
 * Quill scripts call log() and print() which delegate to this context.
 */
interface QuillContext {
    /** Info-level log output — typically server console */
    fun log(message: String)

    /** Player/user-facing output — routed to command sender */
    fun print(message: String)
}
```

- [ ] **Step 4: Create `CompiledScript.kt`**

```kotlin
package org.inklang

import org.inklang.lang.Chunk

/**
 * A compiled Quill script, ready for execution.
 * Scripts are identified by name (typically the filename).
 */
class CompiledScript(
    val name: String,
    private val chunk: Chunk,
    private val constants: List<org.inklang.lang.Value>
) {
    /** Maximum instructions before timeout (default 10M). Set by ScriptManager. */
    var instructionLimit: Long = 10_000_000L

    /**
     * Execute this script with the given context.
     * Runtime errors (infinite loop timeout, undefined variable) are thrown as exceptions.
     * The host should wrap this in a try-catch and format errors for the user.
     */
    fun execute(context: QuillContext) {
        val vm = org.inklang.lang.VM(context)
        vm.instructionLimit = instructionLimit
        vm.execute(chunk)
    }
}
```

- [ ] **Step 5: Create `QuillCompiler.kt`**

```kotlin
package org.inklang

import org.inklang.ast.AstLowerer
import org.inklang.ast.ConstantFolder
import org.inklang.lang.IrCompiler
import org.inklang.lang.LivenessAnalyzer
import org.inklang.lang.Parser
import org.inklang.lang.RegisterAllocator
import org.inklang.lang.SpillInserter
import org.inklang.lang.Value
import org.inklang.lang.tokenize

/**
 * Compiler for Quill source code.
 * Provides a clean API for runtime hosts (Paper plugin, tests, CLI).
 */
class QuillCompiler {

    /**
     * Compile Quill source code into a CompiledScript.
     * @param source Quill source code
     * @param name Script name (used for error reporting, defaults to "main")
     * @return CompiledScript ready for execution
     * @throws Exception on syntax or type errors
     */
    fun compile(source: String, name: String = "main"): CompiledScript {
        val tokens = tokenize(source)
        val parser = Parser(tokens)
        val statements = parser.parse()
        val folder = ConstantFolder()
        val folded = statements.map { folder.foldStmt(it) }
        val result = AstLowerer().lower(folded)

        // SSA round-trip with optimizations
        val (ssaDeconstructed, ssaOptConstants) = IrCompiler.optimizedSsaRoundTrip(
            result.instrs, result.constants
        )
        val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, ssaOptConstants)

        val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
        val allocResult = RegisterAllocator().allocate(ranges)
        val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)
        val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaOptConstants))
        chunk.spillSlotCount = allocResult.spillSlotCount

        return CompiledScript(name, chunk, ssaOptConstants)
    }
}
```

- [ ] **Step 6: Update `VM` constructor to accept `QuillContext`**

Modify `VM` constructor to accept a `QuillContext` and wire `log`/`print` globals:

```kotlin
class VM(val context: QuillContext? = null) {
    val globals = mutableMapOf<String, Value>(
        "Array" to Value.Class(Builtins.ArrayClass),
        "Map" to Value.Class(Builtins.MapClass),
        "EnumValue" to Value.Class(Builtins.EnumValueClass),
        "EnumNamespace" to Value.Class(Builtins.EnumNamespaceClass),
    )

    init {
        // Wire log and print from QuillContext if provided
        context?.let { ctx ->
            globals["log"] = Value.NativeFunction { args ->
                ctx.log(args.joinToString(" ") { it.toString() })
                Value.Null
            }
            globals["print"] = Value.NativeFunction { args ->
                ctx.print(args.joinToString(" ") { it.toString() })
                Value.Null
            }
        }
    }
```

Also update `Main.kt` to pass `null` context (legacy behavior — CLI mode):

```kotlin
val vm = VM(null)  // was VM()
```

- [ ] **Step 7: Run tests to verify they pass**

```bash
./gradlew :quill-core:test --tests "org.inklang.QuillCompilerTest"
```

Expected: all 4 PASS

- [ ] **Step 8: Commit**

```bash
git add quill-core/src/main/kotlin/org/quill/QuillContext.kt
git add quill-core/src/main/kotlin/org/quill/CompiledScript.kt
git add quill-core/src/main/kotlin/org/quill/QuillCompiler.kt
git add quill-core/src/test/kotlin/org/quill/QuillCompilerTest.kt
git add quill-core/src/main/kotlin/org/quill/lang/VM.kt
git add quill-core/src/main/kotlin/org/quill/Main.kt
git commit -m "feat: add QuillCompiler, QuillContext, and CompiledScript API classes"
```

---

## Chunk 3: Instruction Counter Timeout

### Task 3: Add instruction counter to VM

**Files:**
- Modify: `quill-core/src/main/kotlin/org/quill/lang/VM.kt`

- [ ] **Step 1: Write a failing test for infinite loop detection**

Add to `QuillCompilerTest.kt`:

```kotlin
@Test
fun testInfiniteLoopTimesOut() {
    val compiler = QuillCompiler()
    val ctx = FakeQuillContext()
    // Infinite loop that would run forever
    val result = compiler.compile("while (true) {}")
    // Default timeout is 10M instructions; this should throw
    assertThrows(Exception::class.java) {
        result.execute(ctx)
    }
}
```

- [ ] **Step 2: Run test to verify it fails (loop runs until process kill or timeout)**

```bash
timeout 10 ./gradlew :quill-core:test --tests "org.inklang.QuillCompilerTest.testInfiniteLoopTimesOut" 2>&1 | tail -10
```

Expected: test times out or runs indefinitely (this is expected failure — no timeout yet)

- [ ] **Step 3: Add instruction counter to `VM.execute()`**

Modify `VM.execute()` to track instruction count:

```kotlin
class VM(val context: QuillContext? = null) {
    // ... existing code ...

    /**
     * Maximum instructions per script execution.
     * Configured via QuillContext or defaults to 10M.
     */
    var instructionLimit: Long = 10_000_000L

    fun execute(chunk: Chunk) {
        var instructionCount = 0L
        val limit = instructionLimit
        val frames = ArrayDeque<CallFrame>()
        frames.addLast(CallFrame(chunk))

        while (frames.isNotEmpty()) {
            // Check instruction limit before each instruction
            if (instructionCount++ >= limit) {
                error("Script execution timed out (> $limit instructions)")
            }

            val frame = frames.last()
            if (frame.ip >= frame.chunk.code.size) {
                frames.removeLast()
                continue
            }
            // ... rest unchanged ...
        }
    }
}
```

- [ ] **Step 4: Wire instruction limit from `CompiledScript.execute()`**

Update `CompiledScript.execute()` to accept an optional `instructionLimit` parameter:

```kotlin
fun execute(context: QuillContext, instructionLimit: Long = 10_000_000L) {
    val vm = org.inklang.lang.VM(context)
    vm.instructionLimit = instructionLimit
    vm.execute(chunk)
}
```

- [ ] **Step 5: Run the timeout test**

```bash
./gradlew :quill-core:test --tests "org.inklang.QuillCompilerTest.testInfiniteLoopTimesOut"
```

Expected: PASS (test should throw within ~1 second due to instruction limit)

- [ ] **Step 6: Run full quill-core test suite**

```bash
./gradlew :quill-core:test
```

Expected: all PASS

- [ ] **Step 7: Commit**

```bash
git add quill-core/src/main/kotlin/org/quill/ast/VM.kt
git add quill-core/src/test/kotlin/org/quill/QuillCompilerTest.kt
git commit -m "feat: add instruction counter timeout to VM"
```

---

## Chunk 4: QuillPaper Plugin

### Task 4: Create Paper plugin bootstrap

**Files:**
- Create: `quill-paper/src/main/kotlin/org/quill/paper/QuillPlugin.kt`
- Create: `quill-paper/src/main/kotlin/org/quill/paper/QuillContextImpl.kt`
- Create: `quill-paper/src/main/kotlin/org/quill/paper/ScriptManager.kt`
- Create: `quill-paper/src/main/kotlin/org/quill/paper/commands/QuillCommand.kt`

- [ ] **Step 1: Create `QuillPlugin.kt`**

```kotlin
package org.inklang.paper

import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class QuillPlugin : JavaPlugin() {

    lateinit var scriptManager: ScriptManager
        private set

    override fun onEnable() {
        // Save default config if not exists
        saveDefaultConfig()

        val scriptDirName = config.getString("quill.scripts.directory", "quill/scripts")
        val scriptDir = File(dataFolder, scriptDirName).also { it.mkdirs() }
        val timeout = config.getLong("quill.execution.timeout", 10_000_000L)
        val autoLoad = config.getBoolean("quill.scripts.auto-load", false)

        scriptManager = ScriptManager(scriptDir, timeout)

        // Auto-load scripts if configured
        if (autoLoad) {
            scriptDir.listFiles { _, name -> name.endsWith(".quill") }
                ?.forEach { file ->
                    val name = file.nameWithoutExtension
                    val ctx = getQuillContext(server.consoleSender)
                    scriptManager.load(name, ctx)
                }
        }

        // Register commands
        val executor = QuillCommandExecutor(this)
        server.pluginManager.registerEvents(ScriptEventListener(this), this)
        getCommand("quill")?.setExecutor(executor)
        getCommand("quill")?.setTabCompleter(QuillTabCompleter())

        logger.info("Quill enabled — script directory: ${scriptDir.absolutePath}")
    }

    override fun onDisable() {
        scriptManager.unloadAll()
        logger.info("Quill disabled")
    }

    fun getQuillContext(sender: CommandSender): QuillContext {
        return QuillContextImpl(sender, server)
    }
}
```

- [ ] **Step 2: Create `QuillContextImpl.kt`**

```kotlin
package org.inklang.paper

import org.bukkit.command.CommandSender
import org.bukkit.Server
import org.inklang.QuillContext

class QuillContextImpl(
    private val sender: CommandSender,
    private val server: Server
) : QuillContext {
    override fun log(message: String) {
        server.logger.info("[Quill] $message")
    }

    override fun print(message: String) {
        if (sender == server.consoleSender) {
            server.logger.info("[Quill] $message")
        } else {
            sender.sendMessage(message)
        }
    }
}
```

- [ ] **Step 3: Create `ScriptManager.kt`**

```kotlin
package org.inklang.paper

import org.inklang.QuillCompiler
import org.inklang.QuillContext
import org.inklang.CompiledScript
import java.io.File

class ScriptManager(
    private val scriptDir: File,
    private val defaultTimeout: Long
) {
    private val scripts = mutableMapOf<String, CompiledScript>()
    private val compiler = QuillCompiler()

    /**
     * Load or reload a script from disk.
     * @param name Script name (without .quill extension)
     * @param sender Command sender for error reporting
     * @return success message or error
     */
    fun load(name: String, sender: CommandSender): String {
        val file = File(scriptDir, "$name.quill")
        if (!file.exists()) {
            return "§cScript not found: $name.quill"
        }
        return try {
            val source = file.readText()
            val compiled = compiler.compile(source, name)
            compiled.instructionLimit = defaultTimeout
            scripts[name] = compiled
            "§aScript loaded: $name"
        } catch (e: Exception) {
            "§cCompilation failed: ${e.message}"
        }
    }

    /**
     * Unload a script by name.
     */
    fun unload(name: String): String {
        return if (scripts.remove(name) != null) {
            "§aScript unloaded: $name"
        } else {
            "§cScript not loaded: $name"
        }
    }

    /**
     * Unload all scripts. Called on plugin disable.
     */
    fun unloadAll() {
        scripts.clear()
    }

    /**
     * List all loaded scripts.
     */
    fun list(): List<Pair<String, Int>> {
        return scripts.map { (name, script) ->
            name to script.bytecodeSize
        }
    }

    /**
     * Execute a loaded script.
     * @param name Script name
     * @param context QuillContext (includes sender info for routing output)
     * @return success message or error formatted for the sender
     */
    fun execute(name: String, context: QuillContext): String {
        val script = scripts[name] ?: return "§cScript not loaded: $name"
        return try {
            script.execute(context)
            "§aScript executed: $name"
        } catch (e: Exception) {
            "§cExecution error: ${e.message}"
        }
    }

    private val CompiledScript.bytecodeSize: Int
        get() = 0  // Placeholder — Chunk doesn't expose size yet
}
```

- [ ] **Step 4: Create `QuillCommandExecutor.kt`**

```kotlin
package org.inklang.paper

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class QuillCommandExecutor(private val plugin: QuillPlugin) : CommandExecutor {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("§6Quill Script Engine §fv${plugin.description.version}")
            sender.sendMessage("§e/quill load <script> §7— load or reload a script")
            sender.sendMessage("§e/quill unload <script> §7— unload a script")
            sender.sendMessage("§e/quill list §7— list loaded scripts")
            sender.sendMessage("§e/quill run <script> §7— execute a loaded script")
            return true
        }

        val subcommand = args[0]
        val scriptName = args.getOrNull(1)
        val ctx = plugin.getQuillContext(sender)

        return when (subcommand) {
            "load" -> {
                if (scriptName == null) {
                    sender.sendMessage("§cUsage: /quill load <script>")
                    true
                }
                val result = plugin.scriptManager.load(scriptName, sender)
                sender.sendMessage(result)
                if (result.startsWith("§a")) {
                    // Auto-run on load
                    val execResult = plugin.scriptManager.execute(scriptName, ctx)
                    sender.sendMessage(execResult)
                }
                true
            }
            "unload" -> {
                if (scriptName == null) {
                    sender.sendMessage("§cUsage: /quill unload <script>")
                    true
                }
                val result = plugin.scriptManager.unload(scriptName)
                sender.sendMessage(result)
                true
            }
            "list" -> {
                val scripts = plugin.scriptManager.list()
                if (scripts.isEmpty()) {
                    sender.sendMessage("§7No scripts loaded")
                } else {
                    sender.sendMessage("§6Loaded scripts (§e${scripts.size}§6):")
                    for ((name, size) in scripts) {
                        sender.sendMessage("§e  $name §7($size bytes)")
                    }
                }
                true
            }
            "run" -> {
                if (scriptName == null) {
                    sender.sendMessage("§cUsage: /quill run <script>")
                    true
                }
                val result = plugin.scriptManager.execute(scriptName, ctx)
                sender.sendMessage(result)
                true
            }
            else -> {
                sender.sendMessage("§cUnknown subcommand: $subcommand")
                false
            }
        }
    }
}
```

- [ ] **Step 5: Create `QuillTabCompleter.kt`**

```kotlin
package org.inklang.paper

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class QuillTabCompleter : TabCompleter {
    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        return when (args.size) {
            1 -> listOf("load", "unload", "run", "list").filter { it.startsWith(args[0]) }
            2 -> {
                val sub = args[0]
                if (sub == "load" || sub == "unload" || sub == "run") {
                    // Return .quill files in script directory
                    val plugin = org.bukkit.Bukkit.getPluginManager.getPlugin("Quill") as? QuillPlugin
                    plugin?.scriptManager?.list()?.map { it.first }?.filter { it.startsWith(args[1]) }
                        ?: emptyList()
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }
}
```

- [ ] **Step 6: Create stub `ScriptEventListener.kt`**

```kotlin
package org.inklang.paper

import org.bukkit.event.Listener

class ScriptEventListener(private val plugin: QuillPlugin) : Listener {
    // Event system deferred to v2
    // This is a placeholder so the plugin compiles without event usage
}
```

- [ ] **Step 7: Create stub `quill-paper/src/main/resources/config.yml`**

```yaml
quill:
  scripts:
    directory: "quill/scripts"
    auto-load: false
  execution:
    timeout: 10000000
```

- [ ] **Step 8: Create default config in plugin JAR**

Add to `QuillPlugin.onEnable()` before `saveDefaultConfig()`:

```kotlin
// Ensure config.yml exists in the plugin directory
if (!config.contains("quill")) {
    config.set("quill.scripts.directory", "quill/scripts")
    config.set("quill.scripts.auto-load", false)
    config.set("quill.execution.timeout", 10_000_000L)
    saveConfig()
}
```

Actually, since we want the default config to be provided via `plugin.yml`/`config.yml` approach, instead create `quill-paper/src/main/resources/config.yml` with the content above and use `saveDefaultConfig()` which will copy it automatically.

- [ ] **Step 9: Commit**

```bash
git add quill-paper/src/main/kotlin/org/quill/paper/
git add quill-paper/src/main/resources/
git commit -m "feat: add QuillPlugin, QuillContextImpl, ScriptManager, and commands"
```

---

## Chunk 5: Integration Tests with MockBukkit

### Task 5: Add MockBukkit integration tests

**Files:**
- Create: `quill-paper/src/test/kotlin/org/quill/paper/QuillPluginTest.kt`
- Create: `quill-paper/src/test/resources/mockbukkit.yml` (MockBukkit config)

- [ ] **Step 1: Write a failing integration test**

Create `quill-paper/src/test/kotlin/org/quill/paper/QuillPluginTest.kt`:

```kotlin
package org.inklang.paper

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.ServerMock
import be.seeseemelk.mockbukkit.entity.PlayerMock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class QuillPluginTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: QuillPlugin

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin(QuillPlugin::class.java)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun testPluginEnables() {
        assertTrue(plugin.isEnabled)
    }

    @Test
    fun testQuillLoadCommandCompilesScript() {
        // Create a test script file
        val scriptFile = plugin.dataFolder.toPath().resolve("scripts/test.quill")
        scriptFile.parent.toFile().mkdirs()
        scriptFile.toFile().writeText("print(\"hello from quill\")")

        // Execute /quill load test
        val sender = server.consoleSender
        plugin.scriptManager.load("test", sender)

        // Verify script was loaded
        assertNotNull(plugin.scriptManager.list().find { it.first == "test" })
    }

    @Test
    fun testQuillListCommand() {
        val sender = server.consoleSender
        plugin.scriptManager.load("test", sender)
        val scripts = plugin.scriptManager.list()
        assertEquals(1, scripts.size)
        assertEquals("test", scripts[0].first)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :quill-paper:test --tests "org.inklang.paper.QuillPluginTest" 2>&1 | tail -20
```

Expected: FAIL — likely compilation errors or MockBukkit setup issues

- [ ] **Step 3: Fix compilation and test issues**

Common issues at this stage — fix as encountered:
- Missing Paper API stubs in test
- QuillPlugin constructor needs a no-arg constructor for JavaPlugin
- MockBukkit version mismatch with Paper API

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :quill-paper:test 2>&1 | tail -20
```

Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add quill-paper/src/test/
git commit -m "test: add MockBukkit integration tests for QuillPlugin"
```

---

## Chunk 6: End-to-End Verification

### Task 6: Verify full stack works

- [ ] **Step 1: Build both modules**

```bash
./gradlew :quill-core:build :quill-paper:build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run full test suite**

```bash
./gradlew test
```

Expected: all tests PASS

- [ ] **Step 3: Verify `test.lec` runs via quill-core CLI**

The existing test.lec should still work via the CLI (Main.kt passes null context):

```bash
./gradlew :quill-core:run --args="test.lec" 2>&1 | tail -20
```

Expected: executes without error

- [ ] **Step 4: Commit**

```bash
git add -u
git commit -m "chore: verify full build and test suite passes"
```

---

## Final Verification

- [ ] **Run complete test suite**

```bash
./gradlew test
```

Expected: all tests pass, zero failures

- [ ] **Build quill-paper JAR**

```bash
./gradlew :quill-paper:jar
ls quill-paper/build/libs/
```

Expected: `quill-paper-*.jar` exists
