# Stdlib Wiring: io, db, json

**Date:** 2026-03-20
**Status:** Draft — addressing review comments

## Overview

Wire up existing backend utilities (File I/O, SQLite, JSON parsing) as first-class built-in globals accessible from inklang scripts. The goal is to make these feel natural to new players — minimal syntax, strong defaults, Supabase-inspired API.

---

## 1. File I/O — `io` module

### Syntax
```ink
import io
io.read("data.txt")              // → string
io.write("out.txt", "content")   // → null
```

### Semantics
- `read(path)` — reads file at `path`, returns contents as `string`. Path relative to script directory.
- `write(path, content)` — writes `content` to file at `path` (overwrites). Returns `null`.
- UTF-8 encoding.
- If file doesn't exist on `read`, return empty string `""`.
- If parent directory doesn't exist on `write`, create it.

### Implementation
- In `lang/src/main/kotlin/org/inklang/lang/Value.kt`, add `IoModule` as a `ClassDescriptor` with `read` and `write` as `NativeFunction` methods.
- In `lang/src/main/kotlin/org/inklang/ContextVM.kt`, add `"io" to Value.Instance(Builtins.IoModule)` to globals.
- No new dependencies needed (uses `java.io.File`).

---

## 2. Database — `db` global

### Syntax

**Declaration:**
```ink
table Player { id isKey, name, score }
// or with optional type annotations for readability:
table Player { id isKey: int, name: string, score: int }
```
- Field type annotations are optional — driver (SQLite) infers types from inserted data.
- `isKey` marks the primary key field. If omitted, first field is the key (convention).
- Fields comma-separated, newline optional.
- Table name must start with uppercase.
- Subsequent `table` declarations with same name are safe no-ops.

**Query operations on the table:**
```ink
Player.all()                                    // → array of instances
Player.find(1)                                 // → instance or null (by key)
Player.where("score > 10")                     // → query builder
Player.where("score > ?", 10)                  // → parameterized (safe)
Player.order("score", "desc")                  // → query builder (asc|desc)
Player.limit(5)                                // → query builder

// Chaining
Player.where("score > 10").order("score", "desc").limit(5).all()

// Mutation
Player.insert({ name: "Steve", score: 100 })  // → instance
Player.update(1, { score: 200 })               // → null
Player.delete(1)                               // → null
```

**Return values:**
- `all()` → `Value.Instance` (array wrapper via `InternalList`)
- `find(key)` → `Value.Instance` or `Value.Null`
- `insert(...)` → `Value.Instance` (the created row)
- `update(...)`, `delete(...)` → `Value.Null`
- Errors throw runtime exceptions.

**How `Player.all()` works:**
- `table Player { id, name, score }` is lowered to a global `Player` variable bound to a `TableRef`.
- `Player.all()` is syntactic sugar for `db("Player").all()` — both work, but `Player.all()` is preferred.
- `db("players")` is available for dynamic table names at runtime.

**Auto-schema:**
- First `insert` creates the SQLite table. Column types inferred from Kotlin Value types (Int → INTEGER, String → TEXT, Double → REAL, Bool → INTEGER).
- Subsequent `insert` validates against inferred schema.

### Implementation

**AstLowerer change — `AstLowerer.kt`:**

```kotlin
is Stmt.TableStmt -> {
    val tableName = stmt.name.lexeme
    val fieldNames = stmt.fields.map { it.name.lexeme }
    val keyFieldIdx = stmt.fields.indexOfFirst { it.isKey }.takeIf { it >= 0 } ?: 0
    // Emit: Player = db("Player") as a global
    val tableRefReg = freshReg()
    emit(IrInstr.Call(tableRefReg, dbFuncReg, listOf(registerStringConst(tableName))))
    emit(IrInstr.StoreGlobal(tableName, tableRefReg))
    // Register schema info for runtime validation
    tableRuntime.registerTable(tableName, fieldNames, keyFieldIdx)
}
```

The `table` declaration creates a global variable named `Player` that holds a `TableRef`. Subsequent access to `Player` uses `GET_GLOBAL` to retrieve the `TableRef` and then calls methods on it.

**Parser change — `Parser.kt`:**

The current `parseTable()` requires type annotations. Update to make them optional:

```kotlin
private fun parseTable(): Stmt {
    consume(TokenType.KW_TABLE, "Expected 'table'")
    val name = consume(TokenType.IDENTIFIER, "Expected table name")
    consume(TokenType.L_BRACE, "Expected '{'")
    val fields = mutableListOf<Stmt.TableField>()
    while (!check(TokenType.R_BRACE) && !isAtEnd()) {
        // isKey is optional — if present, consume it; otherwise first field is key by convention
        val isKey = match(TokenType.KW_KEY)
        val fieldName = consume(TokenType.IDENTIFIER, "Expected field name")
        // Type annotation is optional
        val fieldType = if (match(TokenType.COLON)) {
            consume(TokenType.IDENTIFIER, "Expected type").lexeme
        } else {
            null  // inferred from data
        }
        // Default value is optional
        val defaultValue = if (match(TokenType.ASSIGN)) {
            parseExpression(0)
        } else {
            null
        }
        fields.add(Stmt.TableField(fieldName, fieldType, isKey, defaultValue))
        if (!check(TokenType.R_BRACE)) {
            consume(TokenType.COMMA, "Expected ',' between fields")
        }
    }
    consume(TokenType.R_BRACE, "Expected '}'")
    return Stmt.TableStmt(name, fields)
}
```

**AstLowerer change — `AstLowerer.kt`:**

Currently `TableStmt` lowers to a marker string. Instead, register the table in a table registry:

```kotlin
is Stmt.TableStmt -> {
    val tableName = stmt.name.lexeme
    val fieldNames = stmt.fields.map { it.name.lexeme }
    val keyField = stmt.fields.indexOfFirst { it.isKey }.takeIf { it >= 0 } ?: 0
    val tableInfo = TableInfo(tableName, fieldNames, keyField)
    tableRegistry[tableName] = tableInfo
    // Emit no IR for the declaration itself — tables are discovered at runtime
}
```

**Extend `TableRuntime.kt` — `lang/src/main/kotlin/org/inklang/lang/TableRuntime.kt`:**

Build on existing `TableRuntime`, extend with query builder:

```kotlin
data class TableInfo(
    val name: String,
    val fields: List<String>,
    val keyIndex: Int  // index of primary key field
)

class TableRuntime {
    private var connection: Connection? = null
    private val tableInfoCache = mutableMapOf<String, TableInfo>()

    fun registerTable(tableName: String, fields: List<String>, keyIndex: Int) {
        tableInfoCache[tableName] = TableInfo(tableName, fields, keyIndex)
    }

    fun getTable(tableName: String): TableRef {
        val info = tableInfoCache[tableName]
            ?: error("Table '$tableName' not declared. Add 'table $tableName { ... }' at the top of your script.")
        return TableRef(getConnection(), info)
    }

    // ... existing createTable, getConnection, buildTableClass ...

    class TableRef(
        private val conn: Connection,
        private val info: TableInfo
    ) {
        fun all(): Value.Instance { ... }
        fun find(key: Value): Value.Instance? { ... }
        fun where(condition: String, vararg args: Value): QueryBuilder { ... }
        fun insert(data: Map<String, Value>): Value.Instance { ... }
        fun update(key: Value, data: Map<String, Value>) { ... }
        fun delete(key: Value) { ... }
    }

    class QueryBuilder(...) {
        fun order(field: String, direction: String): QueryBuilder
        fun limit(n: Int): QueryBuilder
        fun all(): Value.Instance
        fun first(): Value.Instance?
    }
}
```

**Wiring in `ContextVM.kt` and `InkBukkit.kt`:**

- `TableRuntime` is created once per plugin instance in `InkBukkit.kt`.
- `db` exposed as `Value.NativeFunction` in `ContextVM.globals` — calling `db("Player")` returns a `TableRef`.
- When a `table Player { ... }` declaration is lowered, `AstLowerer` emits `Player = db("Player")` — so the declared name is a global holding the `TableRef`.

**SQLite dependency — `bukkit/build.gradle.kts`:**
```kotlin
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
}
```

---

## 3. JSON — `json` module

### Syntax
```ink
import json
json.parse('{"name": "Steve"}')    // → inklang map/array/scalar
json.stringify({ name: "Steve" })  // → JSON string
json.stringify([1, 2, 3])          // → JSON array string
```

### Semantics
- `parse(str)` — parses JSON string. Returns inklang `Map` for objects, `Array` for arrays, `Int/Double/String/Boolean/Null` for primitives.
- `stringify(value)` — converts inklang value to JSON string.
- Invalid JSON on `parse` throws runtime error.

### Implementation
- Use `org.json` library (add to `bukkit/build.gradle.kts`).
- In `Value.kt`, add `JsonModule` as a `ClassDescriptor`.
- In `ContextVM.globals`, add `"json" to Value.Instance(Builtins.JsonModule)`.

**`bukkit/build.gradle.kts` dependencies:**
```kotlin
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.json:json:20231013")
}
```

---

## Files to Modify

| File | Change |
|------|--------|
| `lang/src/main/kotlin/org/inklang/lang/Parser.kt` | Make type annotations optional in `parseTable()` |
| `lang/src/main/kotlin/org/inklang/lang/AST.kt` | Update `TableField` docs — type is optional |
| `lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt` | Register table in `TableRuntime` table registry |
| `lang/src/main/kotlin/org/inklang/lang/TableRuntime.kt` | Add `TableInfo`, `TableRef`, `QueryBuilder` classes |
| `lang/src/main/kotlin/org/inklang/lang/Value.kt` | Add `IoModule`, `JsonModule` class descriptors |
| `lang/src/main/kotlin/org/inklang/ContextVM.kt` | Add `io`, `json`, `db` globals |
| `bukkit/src/main/kotlin/org/inklang/bukkit/InkBukkit.kt` | Create `TableRuntime`, pass to context, wire `db` |
| `bukkit/build.gradle.kts` | Add `org.xerial:sqlite-jdbc`, `org.json` dependencies |

---

## Non-Goals

- Async/await — virtual threads handle blocking naturally.
- Multiple database connections — single SQLite per plugin.
- SQL query logging — later iteration.
- Type annotations required — optional for readability.
