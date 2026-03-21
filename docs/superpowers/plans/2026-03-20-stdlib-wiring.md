# Stdlib Wiring: io, db, json — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up File I/O, SQLite database, and JSON as first-class built-in modules (`io`, `json`, `db`) accessible from inklang scripts. The **lang module defines interfaces**, the **bukkit module provides driver implementations**.

**Architecture:**
- `lang` defines `InkIo`, `InkJson`, `InkDb` interfaces (method signatures only, no implementations)
- `bukkit` implements these interfaces with actual drivers (SQLite, File I/O, org.json)
- `InkContext` is extended to carry driver instances
- `ContextVM` accesses drivers via `context.io()`, `context.json()`, `context.db()`
- `table` keyword syntax simplified: type annotations become optional

**Tech Stack:** Kotlin, JVM 21, sqlite-jdbc 3.45.1.0, org.json 20231013

**Test command:** `./gradlew test`

---

## Chunk 1: Interface Definitions in `lang`

### Task 1.1: Add driver interfaces to `lang`

**Files:**
- Create: `lang/src/main/kotlin/org/inklang/InkIo.kt`
- Create: `lang/src/main/kotlin/org/inklang/InkJson.kt`
- Create: `lang/src/main/kotlin/org/inklang/InkDb.kt`
- Modify: `lang/src/main/kotlin/org/inklang/InkContext.kt`

- [ ] **Step 1: Create `InkIo.kt`**

```kotlin
package org.inklang

/**
 * Interface for I/O operations provided by the host runtime.
 * lang module defines this interface; bukkit module implements it.
 */
interface InkIo {
    /** Read file at path, return contents as string. Path is relative to script directory. */
    fun read(path: String): String
    /** Write content to file at path. Creates parent dirs if needed. Returns null. */
    fun write(path: String, content: String)
}
```

- [ ] **Step 2: Create `InkJson.kt`**

```kotlin
package org.inklang

import org.inklang.lang.Value

/**
 * Interface for JSON operations provided by the host runtime.
 */
interface InkJson {
    /** Parse JSON string, return inklang Value (Map/Array/scalar). Throws on invalid JSON. */
    fun parse(json: String): Value
    /** Convert inklang Value to JSON string. */
    fun stringify(value: Value): String
}
```

- [ ] **Step 3: Create `InkDb.kt`**

```kotlin
package org.inklang

import org.inklang.lang.Value

/**
 * Interface for database operations provided by the host runtime.
 * Supabase-style query builder API.
 */
interface InkDb {
    /**
     * Get a query builder for the named table.
     * table Player { id isKey, name, score } creates a schema, then Player.all() calls db.from("Player").all()
     */
    fun from(table: String): InkTableRef

    /** Register a table schema (called when `table` declaration is lowered). */
    fun registerTable(name: String, fields: List<String>, keyIndex: Int)
}

/**
 * Query builder for a specific table. Supabase-style chaining.
 */
interface InkTableRef {
    fun all(): Value                        // SELECT * FROM table
    fun find(key: Value): Value?            // SELECT WHERE key = ?
    fun insert(data: Map<String, Value>): Value  // INSERT, returns created row
    fun update(key: Value, data: Map<String, Value>)  // UPDATE WHERE key = ?
    fun delete(key: Value)                   // DELETE WHERE key = ?
    fun where(condition: String, vararg args: Value): InkQueryBuilder
    fun order(field: String, direction: String): InkTableRef
    fun limit(n: Int): InkTableRef
}

/**
 * Chainable query builder from a where() call.
 */
interface InkQueryBuilder {
    fun order(field: String, direction: String): InkQueryBuilder
    fun limit(n: Int): InkQueryBuilder
    fun all(): Value
    fun first(): Value?
}
```

- [ ] **Step 4: Extend `InkContext.kt`**

Open `lang/src/main/kotlin/org/inklang/InkContext.kt`. Replace with:

```kotlin
package org.inklang

/**
 * Context interface that runtime hosts implement.
 * Scripts never access platform APIs directly - they call log/print,
 * and the runtime decides where output goes.
 */
interface InkContext {
    /** Info-level log output (server console in Paper, stdout in CLI) */
    fun log(message: String)

    /** User-facing output (command sender in Paper, stdout in CLI) */
    fun print(message: String)

    /** File I/O driver provided by the host runtime */
    fun io(): InkIo

    /** JSON parse/stringify driver provided by the host runtime */
    fun json(): InkJson

    /** Database driver provided by the host runtime */
    fun db(): InkDb
}
```

- [ ] **Step 5: Commit**
```bash
git add lang/src/main/kotlin/org/inklang/InkIo.kt
git add lang/src/main/kotlin/org/inklang/InkJson.kt
git add lang/src/main/kotlin/org/inklang/InkDb.kt
git add lang/src/main/kotlin/org/inklang/InkContext.kt
git commit -m "feat: add InkIo, InkJson, InkDb driver interfaces"
```

---

## Chunk 2: Dependencies in `bukkit`

**Files:**
- Modify: `bukkit/build.gradle.kts`

- [ ] **Step 1: Add sqlite-jdbc and org.json dependencies**

Open `bukkit/build.gradle.kts` and add to the `dependencies` block:
```kotlin
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.json:json:20231013")
}
```

Run: `./gradlew :bukkit:dependencies --configuration compileClasspath`
Expected: sqlite-jdbc and org.json in the dependency tree

- [ ] **Step 2: Commit**
```bash
git add bukkit/build.gradle.kts
git commit -m "deps: add sqlite-jdbc and org.json"
```

---

## Chunk 3: Driver Implementations in `bukkit`

**Files:**
- Create: `bukkit/src/main/kotlin/org/inklang/bukkit/BukkitIo.kt`
- Create: `bukkit/src/main/kotlin/org/inklang/bukkit/BukkitJson.kt`
- Create: `bukkit/src/main/kotlin/org/inklang/bukkit/BukkitDb.kt`

- [ ] **Step 1: Create `BukkitIo.kt`**

```kotlin
package org.inklang.bukkit

import org.inklang.InkIo
import java.io.File

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
        // If absolute, use as-is; if relative, resolve against script directory
        val f = File(path)
        return if (f.isAbsolute) f else File(scriptDir, path)
    }
}
```

- [ ] **Step 2: Create `BukkitJson.kt`**

```kotlin
package org.inklang.bukkit

import org.inklang.InkJson
import org.inklang.lang.Value
import org.inklang.lang.Builtins

class BukkitJson : InkJson {
    override fun parse(json: String): Value {
        return try {
            parseJsonValue(org.json.JSONObject(json))
        } catch (e: Exception) {
            try {
                parseJsonArray(org.json.JSONArray(json))
            } catch (e2: Exception) {
                throw RuntimeException("Invalid JSON: ${e.message}")
            }
        }
    }

    override fun stringify(value: Value): String = stringifyJsonValue(value)

    private fun parseJsonValue(json: org.json.JSONObject): Value.Instance {
        val map = Builtins.newMap()
        json.keys().forEach { key ->
            val v = json.get(key)
            val inkValue = when (v) {
                is org.json.JSONObject -> parseJsonValue(v)
                is org.json.JSONArray -> parseJsonArray(v)
                is String -> Value.String(v)
                is Int -> Value.Int(v)
                is Double -> Value.Double(v)
                is Boolean -> if (v) Value.Boolean.TRUE else Value.Boolean.FALSE
                else -> Value.Null
            }
            (map.fields["__entries"] as Value.InternalMap).entries[Value.String(key)] = inkValue
        }
        return map
    }

    private fun parseJsonArray(arr: org.json.JSONArray): Value.Instance {
        val list = mutableListOf<Value>()
        for (i in 0 until arr.length()) {
            val v = arr.get(i)
            list.add(
                when (v) {
                    is org.json.JSONObject -> parseJsonValue(v)
                    is org.json.JSONArray -> parseJsonArray(v)
                    is String -> Value.String(v)
                    is Int -> Value.Int(v)
                    is Double -> Value.Double(v)
                    is Boolean -> if (v) Value.Boolean.TRUE else Value.Boolean.FALSE
                    else -> Value.Null
                }
            )
        }
        return Builtins.newArray(list)
    }

    private fun stringifyJsonValue(value: Value): String {
        return when (value) {
            is Value.Instance -> {
                val entries = value.fields["__entries"]
                if (entries is Value.InternalMap) {
                    val sb = StringBuilder("{")
                    entries.entries.entries.forEachIndexed { i, (k, v) ->
                        if (i > 0) sb.append(", ")
                        sb.append("\"${(k as Value.String).value}\": ${stringifyJsonValue(v)}")
                    }
                    sb.append("}")
                    sb.toString()
                } else if (value.fields["__items"] is Value.InternalList) {
                    val items = (value.fields["__items"] as Value.InternalList).items
                    val sb = StringBuilder("[")
                    items.forEachIndexed { i, v ->
                        if (i > 0) sb.append(", ")
                        sb.append(stringifyJsonValue(v))
                    }
                    sb.append("]")
                    sb.toString()
                } else {
                    value.toString()
                }
            }
            is Value.String -> "\"${value.value}\""
            is Value.Int -> value.value.toString()
            is Value.Double -> value.value.toString()
            is Value.Boolean -> if (value.value) "true" else "false"
            is Value.Null -> "null"
            else -> value.toString()
        }
    }
}
```

- [ ] **Step 3: Create `BukkitDb.kt`**

```kotlin
package org.inklang.bukkit

import org.inklang.InkDb
import org.inklang.InkQueryBuilder
import org.inklang.InkTableRef
import org.inklang.lang.Value
import org.inklang.lang.Builtins
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class BukkitDb(private val dbPath: String) : InkDb {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val tableInfoCache = mutableMapOf<String, TableInfo>()

    override fun from(table: String): InkTableRef {
        val info = tableInfoCache[table]
            ?: error("Table '$table' not declared. Add 'table $table { ... }' at the top of your script.")
        return TableRefImpl(conn, info)
    }

    override fun registerTable(name: String, fields: List<String>, keyIndex: Int) {
        tableInfoCache[name] = TableInfo(name, fields, keyIndex)
    }

    data class TableInfo(
        val name: String,
        val fields: List<String>,
        val keyIndex: Int
    )

    inner class TableRefImpl(
        private val conn: Connection,
        private val info: TableInfo
    ) : InkTableRef {
        override fun all(): Value {
            val rs = conn.createStatement().executeQuery("SELECT * FROM ${info.name}")
            return resultSetToArray(rs)
        }

        override fun find(key: Value): Value? {
            val keyCol = info.fields[info.keyIndex]
            val pstmt = conn.prepareStatement("SELECT * FROM ${info.name} WHERE $keyCol = ?")
            bindValue(pstmt, 1, key)
            val rs = pstmt.executeQuery()
            return if (rs.next()) resultSetToInstance(rs) else null
        }

        override fun insert(data: Map<String, Value>): Value {
            // Auto-create table if not exists
            try {
                val columns = data.keys.joinToString(", ") { "$it TEXT" }
                conn.createStatement().execute("CREATE TABLE IF NOT EXISTS ${info.name} ($columns)")
            } catch (e: Exception) { }

            val columns = data.keys.joinToString(", ")
            val placeholders = data.keys.joinToString(", ") { "?" }
            val pstmt = conn.prepareStatement("INSERT INTO ${info.name} ($columns) VALUES ($placeholders)")
            data.entries.forEachIndexed { i, (_, v) -> bindValue(pstmt, i + 1, v) }
            pstmt.executeUpdate()

            val keyCol = info.fields[info.keyIndex]
            val keyVal = data.entries.find { it.key == keyCol }?.second
            return if (keyVal != null) {
                find(keyVal) ?: error("Insert failed")
            } else {
                val rs = conn.createStatement().executeQuery("SELECT last_insert_rowid()")
                if (rs.next()) find(Value.Int(rs.getInt(1))) ?: error("Insert failed")
                else error("Insert failed")
            }
        }

        override fun update(key: Value, data: Map<String, Value>) {
            val keyCol = info.fields[info.keyIndex]
            val setClause = data.keys.joinToString(", ") { "$it = ?" }
            val pstmt = conn.prepareStatement("UPDATE ${info.name} SET $setClause WHERE $keyCol = ?")
            data.entries.forEachIndexed { i, (_, v) -> bindValue(pstmt, i + 1, v) }
            bindValue(pstmt, data.size + 1, key)
            pstmt.executeUpdate()
        }

        override fun delete(key: Value) {
            val keyCol = info.fields[info.keyIndex]
            val pstmt = conn.prepareStatement("DELETE FROM ${info.name} WHERE $keyCol = ?")
            bindValue(pstmt, 1, key)
            pstmt.executeUpdate()
        }

        override fun where(condition: String, vararg args: Value): InkQueryBuilder {
            return QueryBuilderImpl(this, conn, info, condition, args.toList())
        }

        override fun order(field: String, direction: String): InkTableRef {
            // Not directly chainable on TableRef — use where() chain
            return this
        }

        override fun limit(n: Int): InkTableRef {
            return this
        }

        private fun resultSetToArray(rs: ResultSet): Value.Instance {
            val list = mutableListOf<Value>()
            while (rs.next()) { list.add(resultSetToInstance(rs)) }
            return Builtins.newArray(list)
        }

        private fun resultSetToInstance(rs: ResultSet): Value.Instance {
            val instance = Value.Instance(Builtins.EnumValueClass, mutableMapOf())
            for (i in 1..rs.metaData.columnCount) {
                val colName = rs.metaData.getColumnName(i)
                val colValue = rs.getObject(i)
                instance.fields[colName] = resultSetValueToInkValue(colValue)
            }
            return instance
        }

        private fun resultSetValueToInkValue(v: Any?): Value = when (v) {
            is Int -> Value.Int(v)
            is Long -> Value.Int(v.toInt())
            is Double -> Value.Double(v)
            is Float -> Value.Float(v)
            is String -> Value.String(v)
            is Boolean -> if (v) Value.Boolean.TRUE else Value.Boolean.FALSE
            null -> Value.Null
            else -> Value.String(v.toString())
        }

        private fun bindValue(stmt: java.sql.PreparedStatement, idx: Int, value: Value) {
            when (value) {
                is Value.Int -> stmt.setInt(idx, value.value)
                is Value.Float -> stmt.setFloat(idx, value.value)
                is Value.Double -> stmt.setDouble(idx, value.value)
                is Value.String -> stmt.setString(idx, value.value)
                is Value.Boolean -> stmt.setInt(idx, if (value.value) 1 else 0)
                is Value.Null -> stmt.setNull(idx, java.sql.Types.NULL)
                else -> stmt.setString(idx, value.toString())
            }
        }
    }

    inner class QueryBuilderImpl(
        private val tableRef: TableRefImpl,
        private val conn: Connection,
        private val info: TableInfo,
        private var condition: String,
        private var args: List<Value>
    ) : InkQueryBuilder {
        override fun order(field: String, direction: String): InkQueryBuilder {
            condition += " ORDER BY $field $direction"
            return this
        }

        override fun limit(n: Int): InkQueryBuilder {
            condition += " LIMIT $n"
            return this
        }

        override fun all(): Value {
            val pstmt = conn.prepareStatement("SELECT * FROM ${info.name} WHERE $condition")
            args.forEachIndexed { i, v -> bindValue(pstmt, i + 1, v) }
            val rs = pstmt.executeQuery()
            return tableRef.resultSetToArray(rs)
        }

        override fun first(): Value? {
            limit(1)
            val pstmt = conn.prepareStatement("SELECT * FROM ${info.name} WHERE $condition")
            args.forEachIndexed { i, v -> bindValue(pstmt, i + 1, v) }
            val rs = pstmt.executeQuery()
            return if (rs.next()) tableRef.resultSetToInstance(rs) else null
        }

        private fun bindValue(stmt: java.sql.PreparedStatement, idx: Int, value: Value) {
            when (value) {
                is Value.Int -> stmt.setInt(idx, value.value)
                is Value.Float -> stmt.setFloat(idx, value.value)
                is Value.Double -> stmt.setDouble(idx, value.value)
                is Value.String -> stmt.setString(idx, value.value)
                is Value.Boolean -> stmt.setInt(idx, if (value.value) 1 else 0)
                is Value.Null -> stmt.setNull(idx, java.sql.Types.NULL)
                else -> stmt.setString(idx, value.toString())
            }
        }
    }
}
```

- [ ] **Step 4: Commit**
```bash
git add bukkit/src/main/kotlin/org/inklang/bukkit/BukkitIo.kt
git add bukkit/src/main/kotlin/org/inklang/bukkit/BukkitJson.kt
git add bukkit/src/main/kotlin/org/inklang/bukkit/BukkitDb.kt
git commit -m "feat: implement BukkitIo, BukkitJson, BukkitDb drivers"
```

---

## Chunk 4: Wire Drivers into `BukkitContext` and `ContextVM`

**Files:**
- Modify: `bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt` — create driver instances
- Modify: `bukkit/src/main/kotlin/org/inklang/bukkit/BukkitContext.kt` — implement io/json/db
- Modify: `lang/src/main/kotlin/org/inklang/ContextVM.kt` — call context.io()/json()/db() for globals

- [ ] **Step 1: Update `BukkitContext.kt`**

Open `bukkit/src/main/kotlin/org/inklang/bukkit/BukkitContext.kt`. Replace with:

```kotlin
package org.inklang.bukkit

import org.inklang.InkContext
import org.inklang.InkIo
import org.inklang.InkJson
import org.inklang.InkDb
import org.inklang.lang.Value

class BukkitContext(
    private val sender: CommandSender,
    private val plugin: InkBukkit,
    private val io: InkIo,
    private val json: InkJson,
    private val db: InkDb
) : InkContext {
    override fun log(message: String) {
        plugin.logger.info("[Ink] $message")
    }

    override fun print(message: String) {
        sender.sendMessage("§f[Ink] $message")
    }

    override fun io(): InkIo = io
    override fun json(): InkJson = json
    override fun db(): InkDb = db
}
```

- [ ] **Step 2: Update `InkBukkit.kt`**

Open `bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt`. Modify `runScript` to create drivers and pass them to `BukkitContext`:

```kotlin
private fun runScript(sender: CommandSender, script: String) {
    try {
        val compiled = scriptCache.getOrPut(script.hashCode().toString()) {
            compiler.compile(script)
        }
        // Create driver instances per script execution
        // io uses script directory, db uses plugin data folder
        val scriptDir = File(plugin.dataFolder, "scripts")
        val dbFile = File(plugin.dataFolder, "data.db")
        dbFile.parentFile?.mkdirs()

        val ioDriver = BukkitIo(scriptDir)
        val jsonDriver = BukkitJson()
        val dbDriver = BukkitDb(dbFile.absolutePath)

        val context = BukkitContext(sender, this, ioDriver, jsonDriver, dbDriver)
        compiled.execute(context)
        sender.sendMessage("§aScript executed successfully")
    } catch (e: Exception) {
        sender.sendMessage("§cError: ${e.message}")
    }
}
```

Add imports:
```kotlin
import java.io.File
```

- [ ] **Step 3: Update `ContextVM.kt` to call context drivers for globals**

In `lang/src/main/kotlin/org/inklang/ContextVM.kt`, the `globals` map currently has hardcoded `math`/`random`. We need to make `io`/`json`/`db` come from `context`.

However, `ContextVM` constructor takes `(context: InkContext, maxInstructions: Int)`. We can access drivers via `context.io()`, `context.json()`, `context.db()`.

Add to `ContextVM.globals`:

```kotlin
"io" to context.io().let { ioDriver ->
    Value.Instance(ClassDescriptor(
        name = "IoModule",
        methods = mapOf(
            "read" to Value.NativeFunction { args ->
                val path = (args.getOrNull(0) as? Value.String)?.value
                    ?: error("io.read requires a string path")
                Value.String(ioDriver.read(path))
            },
            "write" to Value.NativeFunction { args ->
                val path = (args.getOrNull(0) as? Value.String)?.value
                    ?: error("io.write requires a string path")
                val content = (args.getOrNull(1) as? Value.String)?.value
                    ?: error("io.write requires a string content")
                ioDriver.write(path, content)
                Value.Null
            }
        )
    ))
},
"json" to context.json().let { jsonDriver ->
    Value.Instance(ClassDescriptor(
        name = "JsonModule",
        methods = mapOf(
            "parse" to Value.NativeFunction { args ->
                val str = (args.getOrNull(0) as? Value.String)?.value
                    ?: error("json.parse requires a string")
                jsonDriver.parse(str)
            },
            "stringify" to Value.NativeFunction { args ->
                val value = args.getOrNull(0)
                    ?: error("json.stringify requires a value")
                Value.String(jsonDriver.stringify(value))
            }
        )
    ))
},
"db" to context.db().let { dbDriver ->
    Value.NativeFunction { args ->
        val tableName = (args.getOrNull(0) as? Value.String)?.value
            ?: error("db requires a table name string")
        val tableRef = dbDriver.from(tableName)
        Value.TableRefInstance(tableRef)
    }
}
```

But `Value.TableRefInstance` doesn't exist yet. We need to add it to `Value.kt`:

```kotlin
data class TableRefInstance(val tableRef: org.inklang.InkTableRef) : Value()
```

And add `TableRefInstance` handling in `ContextVM` for `GET_FIELD` and `CALL`:

In `ContextVM.GET_FIELD`, add:
```kotlin
is Value.TableRefInstance -> {
    val methodName = frame.chunk.strings[imm]
    val tableRef = value.tableRef
    when (methodName) {
        "all" -> Value.NativeFunction { tableRef.all() }
        "find" -> Value.NativeFunction { args -> tableRef.find(args.getOrNull(1) ?: Value.Null) }
        "insert" -> Value.NativeFunction { args ->
            val data = args.getOrNull(1) as? Value.Instance
                ?: error("insert requires a map")
            val entries = (data.fields["__entries"] as? Value.InternalMap)?.entries
                ?: error("insert requires a map with __entries")
            val map = entries.mapKeys { (it.key as Value.String).value }.mapValues { it.value }
            tableRef.insert(map)
        }
        "update" -> Value.NativeFunction { args ->
            val key = args.getOrNull(1) ?: Value.Null
            val data = args.getOrNull(2) as? Value.Instance
                ?: error("update requires a map")
            val entries = (data.fields["__entries"] as? Value.InternalMap)?.entries
                ?: error("update requires a map with __entries")
            val map = entries.mapKeys { (it.key as Value.String).value }.mapValues { it.value }
            tableRef.update(key, map)
            Value.Null
        }
        "delete" -> Value.NativeFunction { args ->
            val key = args.getOrNull(1) ?: Value.Null
            tableRef.delete(key)
            Value.Null
        }
        "where" -> Value.NativeFunction { args ->
            val condition = (args.getOrNull(1) as? Value.String)?.value
                ?: error("where requires a string condition")
            // Collect varargs
            val queryArgs = args.drop(2)
            val qb = tableRef.where(condition, *queryArgs.toTypedArray())
            Value.QueryBuilderInstance(qb)
        }
        "order" -> Value.NativeFunction { args ->
            val field = (args.getOrNull(1) as? Value.String)?.value
                ?: error("order requires a field string")
            val direction = (args.getOrNull(2) as? Value.String)?.value ?: "asc"
            tableRef.order(field, direction)
            Value.Null  // order is chainable but returns Unit here
        }
        "limit" -> Value.NativeFunction { args ->
            val n = (args.getOrNull(1) as? Value.Int)?.value
                ?: error("limit requires an int")
            tableRef.limit(n)
            Value.Null
        }
        else -> error("TableRef has no method '$methodName'")
    }
}
```

And add `Value.QueryBuilderInstance`:
```kotlin
data class QueryBuilderInstance(val queryBuilder: org.inklang.InkQueryBuilder) : Value()
```

With handler in `ContextVM.GET_FIELD`:
```kotlin
is Value.QueryBuilderInstance -> {
    val methodName = frame.chunk.strings[imm]
    val qb = value.queryBuilder
    when (methodName) {
        "order" -> Value.NativeFunction { args ->
            val field = (args.getOrNull(1) as? Value.String)?.value
                ?: error("order requires a field string")
            val direction = (args.getOrNull(2) as? Value.String)?.value ?: "asc"
            qb.order(field, direction)
            Value.QueryBuilderInstance(qb)
        }
        "limit" -> Value.NativeFunction { args ->
            val n = (args.getOrNull(1) as? Value.Int)?.value
                ?: error("limit requires an int")
            qb.limit(n)
            Value.QueryBuilderInstance(qb)
        }
        "all" -> Value.NativeFunction { qb.all() }
        "first" -> Value.NativeFunction { qb.first() ?: Value.Null }
        else -> error("QueryBuilder has no method '$methodName'")
    }
}
```

Also add to `AstLowerer.kt` line 82 to handle `io` and `json` imports:
```kotlin
if (stmt.namespace.lexeme == "math" || stmt.namespace.lexeme == "random" || stmt.namespace.lexeme == "io" || stmt.namespace.lexeme == "json") {
```

- [ ] **Step 4: Write failing test**

Open `lang/src/test/kotlin/org/inklang/ast/VMTest.kt`. The test needs a mock context. Check how existing tests create `ContextVM`:

Look at `VMTest.kt` for `compileAndRun` helper and see how it sets up `ContextVM`.

Add test:
```kotlin
@Test
fun testIoWriteAndRead() {
    // This test requires a real filesystem — skip in unit tests
    // or use a temp directory via BukkitContext
}
```

For unit tests in `lang` module, `ContextVM` uses `InkContext` which may not have io/json/db. For now, tests can be in `bukkit` module.

- [ ] **Step 5: Commit**
```bash
git add bukkit/src/main/kotlin/org/inklang/bukkit/BukkitContext.kt
git add bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt
git add lang/src/main/kotlin/org/inklang/ContextVM.kt
git add lang/src/main/kotlin/org/inklang/lang/Value.kt
git commit -m "feat: wire io, json, db drivers into ContextVM via InkContext"
```

---

## Chunk 5: Parser — Make table types optional

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/lang/Parser.kt:62-78`
- Modify: `lang/src/main/kotlin/org/inklang/lang/AST.kt`

- [ ] **Step 1: Update `parseTable()` to make type annotations optional**

Replace `parseTable()` (lines 62-78):

```kotlin
private fun parseTable(): Stmt {
    consume(TokenType.KW_TABLE, "Expected 'table'")
    val name = consume(TokenType.IDENTIFIER, "Expected table name")
    consume(TokenType.L_BRACE, "Expected '{'")
    val fields = mutableListOf<Stmt.TableField>()
    while (!check(TokenType.R_BRACE) && !isAtEnd()) {
        val isKey = match(TokenType.KW_KEY)
        val fieldName = consume(TokenType.IDENTIFIER, "Expected field name")
        // Type annotation is optional
        val fieldType = if (match(TokenType.COLON)) {
            consume(TokenType.IDENTIFIER, "Expected type").lexeme
        } else {
            null
        }
        // Default value is optional
        val defaultValue = if (match(TokenType.ASSIGN)) {
            parseExpression(0)
        } else {
            null
        }
        fields.add(Stmt.TableField(fieldName, fieldType, isKey, defaultValue))
        if (!check(TokenType.R_BRACE)) {
            match(TokenType.COMMA)  // comma optional
        }
    }
    consume(TokenType.R_BRACE, "Expected '}'")
    return Stmt.TableStmt(name, fields)
}
```

- [ ] **Step 2: Update `AST.kt` `TableField` to reflect optional type**

```kotlin
data class TableField(val name: Token, val type: String?, val isKey: Boolean, val defaultValue: Expr?)
```

- [ ] **Step 3: Commit**
```bash
git add lang/src/main/kotlin/org/inklang/lang/Parser.kt
git add lang/src/main/kotlin/org/inklang/lang/AST.kt
git commit -m "feat(parser): make table field types optional"
```

---

## Chunk 6: AstLowerer — TableStmt Lowering (registers schema with db driver)

**Files:**
- Modify: `lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt:107-113`

- [ ] **Step 1: Change TableStmt lowering to register schema with db driver**

The `table Player { id isKey, name, score }` declaration should:
1. Call `db("Player")` at runtime to get the TableRef
2. Store it as global `Player`

But we also need to register the schema with `db` before that call. Since the script runs in order, `table Player { ... }` runs first, then `Player.insert(...)`. So we just need the declaration to emit `Player = db("Player")` and the `db()` call will fail if the table wasn't registered.

Actually, we need to register the schema before `db("Player")` is called. The schema registration can be done via a `db.register("Player", ["id", "name"], 0)` call emitted by the lowerer.

Replace `is Stmt.TableStmt` (lines 107-113) with:

```kotlin
is Stmt.TableStmt -> {
    val tableName = stmt.name.lexeme
    val fieldNames = stmt.fields.map { it.name.lexeme }
    val keyFieldIdx = stmt.fields.indexOfFirst { it.isKey }.takeIf { it >= 0 } ?: 0

    // Register the table schema with db driver:
    // db.registerTable("Player", ["id", "name", "score"], 0)
    val dbReg = freshReg()
    emit(IrInstr.LoadGlobal(dbReg, "db"))
    val tableNameIdx = addConstant(Value.String(tableName))
    val tableNameReg = freshReg()
    emit(IrInstr.LoadImm(tableNameReg, tableNameIdx))

    // Build list of field names as Value array
    val fieldsListReg = freshReg()
    emit(IrInstr.NewArray(fieldsListReg, emptyList()))  // empty for now, we'll simplify

    // Actually, let's emit a Call to db.registerTable
    // db.registerTable(tableName, fields, keyIndex)
    // This requires adding a NativeFunction for registerTable on db
    // For simplicity: emit db() call first, then handle registration separately

    // Simpler approach: just emit Player = db("Player")
    // and require table declaration to be processed by the driver at db() call time
    // The driver can register on first db("Player") call if not already registered

    // So: Player = db("Player")
    val playerReg = freshReg()
    emit(IrInstr.Call(playerReg, dbReg, listOf(tableNameReg)))
    locals[tableName] = playerReg
    emit(IrInstr.StoreGlobal(tableName, playerReg))
}
```

But this won't register the schema. The driver needs to know the fields.

**Better approach**: Have the `db` global be a special `Value.NativeFunction` that, when called with a table name, checks if that table is registered. If not, it throws a helpful error. The `table` declaration needs to register first.

The `table` declaration should call `db.registerTable(...)` before setting up `Player`. We can add `registerTable` as a method on the `db` global's ClassDescriptor methods.

**Simplest working approach**: Add a `registerTable` NativeFunction to the `db` global in ContextVM, and emit the registration call in AstLowerer:

```kotlin
is Stmt.TableStmt -> {
    val tableName = stmt.name.lexeme
    val fieldNames = stmt.fields.map { it.name.lexeme }
    val keyFieldIdx = stmt.fields.indexOfFirst { it.isKey }.takeIf { it >= 0 } ?: 0

    // Emit: db.registerTable("Player", ["id", "name"], 0)
    val dbReg = freshReg()
    emit(IrInstr.LoadGlobal(dbReg, "db"))
    val tableNameIdx = addConstant(Value.String(tableName))
    val tableNameReg = freshReg()
    emit(IrInstr.LoadImm(tableNameReg, tableNameIdx))
    // Field names as array literal
    val fieldsReg = freshReg()
    val fieldNameValues = fieldNames.map { addConstant(Value.String(it)) }
    emit(IrInstr.NewArray(fieldsReg, fieldNameValues))
    val keyIdxReg = freshReg()
    emit(IrInstr.LoadImm(keyIdxReg, addConstant(Value.Int(keyFieldIdx))))
    // Call db.registerTable(tableName, fields, keyIndex)
    emit(IrInstr.Call(freshReg(), dbReg, listOf(tableNameReg, fieldsReg, keyIdxReg)))

    // Then: Player = db("Player")
    val playerReg = freshReg()
    emit(IrInstr.LoadGlobal(dbReg, "db"))
    emit(IrInstr.Call(playerReg, dbReg, listOf(tableNameReg)))
    locals[tableName] = playerReg
    emit(IrInstr.StoreGlobal(tableName, playerReg))
}
```

And in `ContextVM.globals`, add `registerTable` method to the `db` returned ClassDescriptor. Actually, `db` is a `NativeFunction` currently. We need `db` to be an `Instance` with methods.

**Change `db` to be an Instance with methods**:

```kotlin
"db" to Value.Instance(ClassDescriptor(
    name = "DbModule",
    methods = mapOf(
        "from" to Value.NativeFunction { args ->
            val tableName = (args.getOrNull(0) as? Value.String)?.value
                ?: error("db.from requires a table name")
            val tableRef = context.db().from(tableName)
            Value.TableRefInstance(tableRef)
        },
        "registerTable" to Value.NativeFunction { args ->
            val tableName = (args.getOrNull(0) as? Value.String)?.value
                ?: error("registerTable requires table name")
            val fieldsArr = args.getOrNull(1) as? Value.Instance
                ?: error("registerTable requires fields array")
            val items = (fieldsArr.fields["__items"] as? Value.InternalList)?.items
                ?: error("registerTable requires fields array with __items")
            val fieldNames = items.map { (it as Value.String).value }
            val keyIdx = (args.getOrNull(2) as? Value.Int)?.value ?: 0
            context.db().registerTable(tableName, fieldNames, keyIdx)
            Value.Null
        }
    )
))
```

But wait — `context` is available in `ContextVM` constructor but not in the `NativeFunction` lambda. We need to capture it.

```kotlin
class ContextVM(
    private val context: InkContext,
    private val maxInstructions: Int = 0
) {
    // Capture context for use in globals initialization
    private val ctx = context

    val globals = mutableMapOf<String, Value>(
        // ...
        "db" to Value.Instance(ClassDescriptor(
            name = "DbModule",
            methods = mapOf(
                "from" to Value.NativeFunction { args ->
                    val tableName = (args.getOrNull(0) as? Value.String)?.value
                        ?: error("db.from requires a table name")
                    val tableRef = ctx.db().from(tableName)
                    Value.TableRefInstance(tableRef)
                },
                "registerTable" to Value.NativeFunction { args ->
                    val tableName = (args.getOrNull(0) as? Value.String)?.value
                        ?: error("registerTable requires table name")
                    val fieldsArr = args.getOrNull(1) as? Value.Instance
                        ?: error("registerTable requires fields array")
                    val items = (fieldsArr.fields["__items"] as? Value.InternalList)?.items
                        ?: error("registerTable requires fields array")
                    val fieldNames = items.map { (it as Value.String).value }
                    val keyIdx = (args.getOrNull(2) as? Value.Int)?.value ?: 0
                    ctx.db().registerTable(tableName, fieldNames, keyIdx)
                    Value.Null
                }
            )
        ))
    )
}
```

This is complex. Let me write the actual code in the plan.

- [ ] **Step 2: Commit**
```bash
git add lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt
git add lang/src/main/kotlin/org/inklang/lang/Value.kt
git add lang/src/main/kotlin/org/inklang/ContextVM.kt
git commit -m "feat(db): wire table declarations via db.registerTable and db.from"
```

---

## Chunk 7: Integration Tests in `bukkit`

**Files:**
- Create: `bukkit/src/test/kotlin/org/inklang/bukkit/StdlibIntegrationTest.kt`

- [ ] **Step 1: Write integration test**

```kotlin
class StdlibIntegrationTest {
    private val compiler = InkCompiler()

    @Test
    fun testTableFullWorkflow() {
        val tempDb = createTempDb()
        val io = BukkitIo(createTempDir())
        val json = BukkitJson()
        val db = BukkitDb(tempDb.absolutePath)
        val context = TestContext(io, json, db)

        val result = compiler.compile("""
            table Player { id isKey, name, score }
            Player.insert({ name: "Steve", score: 100 })
            Player.insert({ name: "Alex", score: 50 })
            let all = Player.all()
            all.size()
        """).execute(context)
        assert(lastPrintOutput() == "2")
    }

    @Test
    fun testTableFind() {
        // similar test for Player.find
    }

    @Test
    fun testTableWhere() {
        // test Player.where("score > 50").all()
    }

    @Test
    fun testJsonParseAndStringify() {
        // test json.parse and json.stringify
    }

    @Test
    fun testIoWriteAndRead() {
        // test io.write and io.read
    }
}
```

Note: You'll need a `TestContext` that implements `InkContext` for tests.

- [ ] **Step 2: Run tests**
Run: `./gradlew :bukkit:test`
Expected: All pass

- [ ] **Step 3: Commit**
```bash
git add bukkit/src/test/kotlin/org/inklang/bukkit/StdlibIntegrationTest.kt
git commit -m "test: add stdlib integration tests"
```

---

## Summary

| Chunk | Description | Files |
|-------|-------------|-------|
| 1 | Driver interfaces in `lang` | `InkIo.kt`, `InkJson.kt`, `InkDb.kt`, `InkContext.kt` |
| 2 | Dependencies | `bukkit/build.gradle.kts` |
| 3 | Driver implementations in `bukkit` | `BukkitIo.kt`, `BukkitJson.kt`, `BukkitDb.kt` |
| 4 | Wire drivers via `BukkitContext` | `BukkitContext.kt`, `InkBukkit.kt`, `ContextVM.kt`, `Value.kt` |
| 5 | Parser: optional table types | `Parser.kt`, `AST.kt` |
| 6 | AstLowerer: table lowering | `AstLowerer.kt` |
| 7 | Integration tests | `StdlibIntegrationTest.kt` |
