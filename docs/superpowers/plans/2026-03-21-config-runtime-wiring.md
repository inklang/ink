# Config Runtime Wiring Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `ConfigRuntime.loadConfig()` into the execution pipeline so `config Settings { port: int = 8080 }` in a script loads from `settings.yml` and merges with defaults at runtime.

**Architecture:** Extract config field definitions at compile time (from `Stmt.ConfigStmt`), store in `InkScript`, call `ConfigRuntime.loadConfig()` in `InkBukkit.runScript()` before `execute()`, and pre-populate `ContextVM` globals via `setGlobals()`.

**Tech Stack:** Kotlin, Gradle, PaperMC plugin, SnakeYAML

---

## Chunk 1: Add config definitions to InkScript

### Files
- Modify: `ink/src/main/kotlin/org/inklang/InkScript.kt`
- Modify: `ink/src/main/kotlin/org/inklang/InkCompiler.kt`

- [ ] **Step 1: Modify InkScript.kt to store configDefinitions and add preloadConfigs()**

Read `ink/src/main/kotlin/org/inklang/InkScript.kt` first.

Replace the entire file contents with:

```kotlin
package org.inklang

import org.inklang.lang.Chunk
import org.inklang.lang.ConfigFieldDef
import org.inklang.lang.ConfigRuntime
import org.inklang.lang.Value

/**
 * A compiled Quill script that can be executed with a context.
 */
class InkScript(
    val name: String,
    private val chunk: Chunk,
    private val configDefinitions: Map<String, List<ConfigFieldDef>> = emptyMap()
) {
    /**
     * Pre-load all configs declared in this script from YAML files.
     * @param scriptDir The directory to look for config YAML files (e.g. "plugins/ink/scripts/")
     * @return Map of config name → loaded Value.Instance
     */
    fun preloadConfigs(scriptDir: String): Map<String, Value.Instance> {
        return configDefinitions.mapValues { (name, fields) ->
            ConfigRuntime.loadConfig(name, fields, scriptDir)
        }
    }

    /**
     * Execute this script with the given context.
     * @param context The context providing log/print implementations
     * @param maxInstructions Maximum instructions to execute before timeout (0 = no limit)
     * @throws ScriptException if a runtime error occurs
     * @throws ScriptTimeoutException if maxInstructions is exceeded
     */
    fun execute(context: InkContext, maxInstructions: Int = 0) {
        val vm = ContextVM(context, maxInstructions)
        vm.execute(chunk)
    }
}
```

- [ ] **Step 2: Modify InkCompiler.kt to extract ConfigStmt fields**

Read `ink/src/main/kotlin/org/inklang/InkCompiler.kt` first.

Add the import:
```kotlin
import org.inklang.lang.ConfigFieldDef
```

Replace the `compile()` method's return statement with:

```kotlin
            // Extract config field definitions for runtime loading
            val configDefinitions = mutableMapOf<String, List<ConfigFieldDef>>()
            for (stmt in statements) {
                if (stmt is Stmt.ConfigStmt) {
                    val fields = stmt.fields.map { field ->
                        ConfigFieldDef(
                            name = field.name.lexeme,
                            type = field.type.lexeme,
                            defaultValue = field.defaultValue?.let { expr ->
                                // Evaluate constant expression to Value
                                val folded = folder.fold(expr)
                                (folded as? Expr.LiteralExpr)?.literal
                            }
                        )
                    }
                    configDefinitions[stmt.name.lexeme] = fields
                }
            }

            return InkScript(name, chunk, configDefinitions)
```

The `folder` variable is already available from the constant-fold step above. The `Stmt` and `Expr` types are already imported via `org.inklang.ast.*`.

Note: `Stmt.ConfigStmt` and `Expr.LiteralExpr` are in the `org.inklang.lang` package (see `AST.kt`), so they are accessible from `InkCompiler.kt`.

- [ ] **Step 3: Run the tests to verify nothing is broken**

Run: `./gradlew :ink:test --tests "org.inklang.InkCompilerTest" -v`
Expected: All tests pass (no behavioral changes yet)

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/InkScript.kt ink/src/main/kotlin/org/inklang/InkCompiler.kt
git commit -m "feat: extract ConfigStmt definitions into InkScript for runtime loading"
```

---

## Chunk 2: Add setGlobals to ContextVM

### Files
- Modify: `ink/src/main/kotlin/org/inklang/lang/ContextVM.kt`

- [ ] **Step 1: Read ContextVM.kt globals section**

Read `ink/src/main/kotlin/org/inklang/lang/ContextVM.kt` lines 25-45 to find the `globals` declaration.

- [ ] **Step 2: Add setGlobals method to ContextVM**

Add this method inside the `ContextVM` class (after the `globals` declaration):

```kotlin
    /**
     * Merge pre-loaded global values (e.g. from config files) into the VM's globals.
     * Must be called before execute().
     */
    fun setGlobals(overrides: Map<String, Value>) {
        globals.putAll(overrides)
    }
```

Place it right after the `globals` declaration closing brace (line 105 in the original).

- [ ] **Step 3: Run tests to verify ContextVM still works**

Run: `./gradlew :ink:test --tests "org.inklang.ast.VMTest" -v`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/lang/ContextVM.kt
git commit -m "feat: add setGlobals() to ContextVM for pre-loading configs"
```

---

## Chunk 3: Wire config preloading in InkBukkit

### Files
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt`

- [ ] **Step 1: Read InkBukkit.kt**

Read `ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt` lines 54-74 to see the `runScript` method.

- [ ] **Step 2: Modify runScript() to preload configs**

Replace the `runScript` method body (lines 55-74) with:

```kotlin
    private fun runScript(sender: CommandSender, script: String) {
        try {
            val compiled = scriptCache.getOrPut(script.hashCode().toString()) {
                compiler.compile(script)
            }
            val scriptDir = File(dataFolder, "scripts")
            scriptDir.mkdirs()
            val dbFile = File(dataFolder, "data.db")
            dbFile.parentFile?.mkdirs()

            val ioDriver = BukkitIo(scriptDir)
            val jsonDriver = BukkitJson()
            val dbDriver = BukkitDb(dbFile.absolutePath)

            val context = BukkitContext(sender, this, ioDriver, jsonDriver, dbDriver)
            val vm = ContextVM(context)

            // Pre-load configs from YAML files before execution
            val preloadedConfigs = compiled.preloadConfigs(scriptDir.absolutePath)
            vm.setGlobals(preloadedConfigs)

            vm.execute(compiled.chunk)
            sender.sendMessage("§aScript executed successfully")
        } catch (e: Exception) {
            sender.sendMessage("§cError: ${e.message}")
        }
    }
```

The only changes from the original are:
- `scriptDir.mkdirs()` added to ensure the scripts directory exists
- `val vm = ContextVM(context)` instead of inline `compiled.execute(context)`
- Two new lines: `preloadedConfigs = ...` and `vm.setGlobals(...)`

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :ink-bukkit:compileKotlin -v`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add ink-bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt
git commit -m "feat: wire config preloading into InkBukkit.runScript()"
```

---

## Chunk 4: Remove dead __config__ marker code from AstLowerer

### Files
- Modify: `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt`

- [ ] **Step 1: Read AstLowerer.kt lines 105-111**

```kotlin
        is Stmt.ConfigStmt -> {
            val dst = freshReg()
            locals[stmt.name.lexeme] = dst
            val configMarkerIdx = addConstant(Value.String("__config__${stmt.name.lexeme}"))
            emit(IrInstr.LoadImm(dst, configMarkerIdx))
            emit(IrInstr.StoreGlobal(stmt.name.lexeme, dst))
        }
```

This is dead code — the real value now comes from `preloadConfigs()`. The `STORE_GLOBAL` of the marker string would overwrite the correct config instance that was pre-loaded into `globals` with a useless string.

- [ ] **Step 2: Replace ConfigStmt case with Nop**

Replace the `Stmt.ConfigStmt` branch with:

```kotlin
        is Stmt.ConfigStmt -> {
            // Config values are loaded at runtime via InkScript.preloadConfigs()
            // No IR emitted here — globals["${stmt.name.lexeme}"] is pre-populated
            locals[stmt.name.lexeme] = freshReg()
        }
```

The `locals` entry is needed because `lowerExpr(Expr.VariableExpr)` checks `locals` first for variable resolution. The config name must be registered as a local so that uses of `Settings.port` resolve correctly.

- [ ] **Step 3: Run tests**

Run: `./gradlew :ink:test --tests "org.inklang.ast.VMTest" -v`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt
git commit -m "fix: remove dead __config__ marker code from AstLowerer"
```

---

## Chunk 5: Add config loading tests

### Files
- Modify: `ink/src/test/kotlin/org/inklang/ast/VMTest.kt`

- [ ] **Step 1: Read existing config parsing test**

Read `ink/src/test/kotlin/org/inklang/ast/VMTest.kt` around lines 282-294 to see the existing `testConfigParsing` test.

- [ ] **Step 2: Add testConfigLoading test after testConfigParsing**

Insert this test after `testConfigParsing()`:

```kotlin
    @Test
    fun testConfigLoading() {
        // Create a temp dir with a settings.yml file
        val tempDir = createTempDir()
        val settingsFile = File(tempDir, "settings.yml")
        settingsFile.writeText("""
            name: "loaded-from-yaml"
            port: 9000
        """.trimIndent())

        // Compile a script that declares the config
        val compiler = InkCompiler()
        val script = """
            config Settings {
                name: string = "default"
                port: int = 8080
            }
            print(Settings.name)
            print(Settings.port)
        """.trimIndent()
        val compiled = compiler.compile(script, "test-config")

        // preloadConfigs should load from YAML
        val preloaded = compiled.preloadConfigs(tempDir.absolutePath)
        assertEquals(1, preloaded.size)
        assertTrue(preloaded.containsKey("Settings"))

        val settings = preloaded["Settings"]!!
        assertEquals("loaded-from-yaml", (settings.fields["name"] as Value.String).value)
        assertEquals(9000, (settings.fields["port"] as Value.Int).value)

        // Cleanup
        tempDir.deleteRecursively()
    }
```

- [ ] **Step 3: Add testConfigDefaults test**

Insert this test after `testConfigLoading()`:

```kotlin
    @Test
    fun testConfigDefaults() {
        // Empty temp dir — no YAML file
        val tempDir = createTempDir()

        val compiler = InkCompiler()
        val script = """
            config Settings {
                name: string = "default-name"
                port: int = 1234
            }
        """.trimIndent()
        val compiled = compiler.compile(script, "test-config-defaults")

        // preloadConfigs should use defaults since no YAML exists
        val preloaded = compiled.preloadConfigs(tempDir.absolutePath)
        val settings = preloaded["Settings"]!!
        assertEquals("default-name", (settings.fields["name"] as Value.String).value)
        assertEquals(1234, (settings.fields["port"] as Value.Int).value)

        tempDir.deleteRecursively()
    }
```

- [ ] **Step 4: Add testConfigMissingRequiredField test**

Insert this test after `testConfigDefaults()`:

```kotlin
    @Test(expected = RuntimeException::class)
    fun testConfigMissingRequiredField() {
        val tempDir = createTempDir()

        val compiler = InkCompiler()
        // "required" field has no default — YAML has no value for it
        val script = """
            config Settings {
                required: string
            }
        """.trimIndent()
        val compiled = compiler.compile(script, "test-config-required")

        // Should throw because 'required' has no value in YAML and no default
        compiled.preloadConfigs(tempDir.absolutePath)

        tempDir.deleteRecursively()
    }
```

- [ ] **Step 5: Run all three new tests**

Run: `./gradlew :ink:test --tests "org.inklang.ast.VMTest.testConfigLoading" --tests "org.inklang.ast.VMTest.testConfigDefaults" --tests "org.inklang.ast.VMTest.testConfigMissingRequiredField" -v`
Expected: All three pass

- [ ] **Step 6: Commit**

```bash
git add ink/src/test/kotlin/org/inklang/ast/VMTest.kt
git commit -m "test: add config loading, defaults, and missing-required tests"
```

---

## Chunk 6: Integration test in ink-bukkit

### Files
- Modify: `ink-bukkit/src/test/kotlin/org/inklang/bukkit/BukkitContextTest.kt`

- [ ] **Step 1: Read BukkitContextTest.kt**

```kotlin
// to understand existing test patterns
```

- [ ] **Step 2: Add integration test for config wiring**

Insert a new test:

```kotlin
    @Test
    fun testConfigPreloadingInBukkit() {
        val tempDir = createTempDir()
        val settingsFile = File(tempDir, "server-config.yml")
        settingsFile.writeText("""
            host: "mc.example.com"
            maxPlayers: 100
        """.trimIndent())

        val compiler = InkCompiler()
        val script = """
            config ServerConfig {
                host: string = "localhost"
                maxPlayers: int = 20
            }
            print(ServerConfig.host)
            print(ServerConfig.maxPlayers)
        """.trimIndent()
        val compiled = compiler.compile(script, "server-config")

        // Verify preloadConfigs works at this level
        val preloaded = compiled.preloadConfigs(tempDir.absolutePath)
        assertEquals(1, preloaded.size)

        val config = preloaded["ServerConfig"]!!
        assertEquals("mc.example.com", (config.fields["host"] as Value.String).value)
        assertEquals(100, (config.fields["maxPlayers"] as Value.Int).value)

        tempDir.deleteRecursively()
    }
```

- [ ] **Step 3: Run the integration test**

Run: `./gradlew :ink-bukkit:test --tests "org.inklang.bukkit.BukkitContextTest.testConfigPreloadingInBukkit" -v`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add ink-bukkit/src/test/kotlin/org/inklang/bukkit/BukkitContextTest.kt
git commit -m "test: add bukkit config preloading integration test"
```

---

## Chunk 7: Full build verification

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew build -v`
Expected: BUILD SUCCESSFUL — all tests pass across all modules

- [ ] **Step 2: Verify spec is referenced**

Confirm `docs/superpowers/specs/2026-03-21-config-runtime-wiring-design.md` exists and is committed.

- [ ] **Step 3: Final commit if needed**

If any issues were found and fixed, commit them now.

---

## Dependencies

- Chunk 1 must complete before Chunk 2 (InkScript must have `preloadConfigs` before `setGlobals` can be tested)
- Chunk 2 must complete before Chunk 3 (ContextVM must have `setGlobals` before InkBukkit can call it)
- Chunk 4 (AstLowerer fix) is safe to run in parallel with Chunks 1-3 but should land before integration testing
- Chunk 5 (tests) requires Chunks 1-4 to be complete
- Chunk 6 (bukkit integration test) requires Chunks 1-3 to be complete
- Chunk 7 is the final verification gate
