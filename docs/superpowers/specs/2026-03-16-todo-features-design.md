# Lectern TODO Features Design Spec

**Date:** 2026-03-16
**Scope:** Implement 7 unfinished language features: Ternary, Map, Lambda, Enum, Table, Config, Import
**Approach:** Desugar into existing IR/VM constructs wherever possible

## Major Refactor: Unify Collections as Classes

Before implementing new features, unify `Value.List` and `Value.Map` into `Value.Instance` backed by built-in classes. `Value.Range` and `Value.Iterator` are already implemented as `Value.Instance` via `Builtins.RangeClass` and `Builtins.IteratorClass` — extend this pattern to Array and Map.

### Changes

- **Remove:** `Value.List`, `Value.Map`
- **Add built-in classes** registered as globals at VM startup (following the existing `Builtins.RangeClass` pattern):
  - `Builtins.ArrayClass` — `ClassDescriptor` with native methods. Instances store elements in a `__items` field (internally a `MutableList<Value>` wrapped in a `Value.Instance`). Methods: `get(index)`, `set(index, value)`, `size()`, `push(value)`, `iter()`, `hasNext()`, `next()`
  - `Builtins.MapClass` — `ClassDescriptor` with native methods. Instances store entries in a `__entries` field (internally a `MutableMap<Value, Value>`). Methods: `get(key)`, `set(key, value)`, `size()`, `keys()`, `values()`, `delete(key)`
- **Remove opcodes:** `NEW_ARRAY`, `RANGE`
- **Remove IR instructions:** `NewArray`
- Array creation: `NewInstance(ArrayClass, elements)` — constructor takes variadic elements
- Map creation: `NewInstance(MapClass, [])` followed by `set()` calls
- Range creation already uses `Builtins.newRange()` — keep as-is
- `GET_INDEX` / `SET_INDEX` dispatch: instead of pattern-matching on `Value.List`, check for `Value.Instance` and call its `get`/`set` methods
- `ListExpr` lowering: currently emits `NewArray(dst, elementRegs)`. After refactor, emits `LoadGlobal(arrayClassReg, "Array")` + `NewInstance(dst, arrayClassReg, elementRegs)`

### VM migration scope
The VM currently special-cases `Value.List` in:
- `NEW_ARRAY` (line 167) — creates `Value.List`
- `GET_INDEX` (line 257) — casts to `Value.List`
- `SET_INDEX` (line 264) — casts to `Value.List`
- `IS_TYPE` (line 229) — checks `typeName == "list"`
- `ADD` — string concat checks

All of these need to be updated to use `Value.Instance` with method dispatch or field access. `GET_FIELD` already handles `Value.Instance` correctly (line 172).

---

## Feature 1: Ternary Expressions

**Syntax:** `condition ? thenExpr : elseExpr`

### Lexer
- Add `QUESTION` token type for `?`

### Parser
- After parsing a complete expression, check for `?`
- If found: parse then-branch expression, consume `:` (COLON), parse else-branch expression
- Precedence: lower than all other operators except assignment
- Produces `Expr.TernaryExpr(condition, thenBranch, elseBranch)`

### AstLowerer — Desugar to if/jump
```
condReg = <condition>
JumpIfFalse condReg -> elseLabel
dst = <thenExpr>
Jump -> endLabel
elseLabel:
dst = <elseExpr>
endLabel:
```

### New IR/Opcodes: None

---

## Feature 2: Map Literals

**Syntax:** `{ key: value, key2: value2 }`

### Lexer
- No changes — `{`, `}`, `:`, `,` all exist

### Parser
- Disambiguate from block statement: in expression position, if `{` is followed by `expr :`, parse as map
- Parse comma-separated `key : value` pairs
- Produces `Expr.MapExpr(entries: List<Pair<Expr, Expr>>)`

### AstLowerer — Desugar to NewInstance + method calls
```
// { "a": 1, "b": 2 }
// becomes:
mapReg = NewInstance(Map, [])
Call(_, map.set, ["a", 1])
Call(_, map.set, ["b", 2])
```

### New IR/Opcodes: None — uses NewInstance and Call

---

## Feature 3: Lambda Expressions

**Syntax:** `(params) -> { body }`

### Lexer
- No changes — `ARROW` (`->`) already exists

### Parser
- When seeing `(`, attempt to parse as param list
- If followed by `->`, parse as lambda; otherwise backtrack to grouped/call expression
- Requires `{` body `}` (braces always required)
- Produces `Expr.LambdaExpr(params: List<Param>, body: Stmt.BlockStmt)`

### AstLowerer — Desugar to anonymous LoadFunc
```
// (x, y) -> { return x + y }
// becomes:
// fn __lambda_0(x, y) { return x + y }
```
- Create fresh AstLowerer, set up params, lower body
- Emit `LoadFunc(dst, "__lambda_N", arity, instrs, constants, defaultValues)`
- No `StoreGlobal` — lambda stays in register only

### Closures
Lambdas do NOT support closures in v1. A lambda can only reference:
- Its own parameters
- Global variables (via `LoadGlobal`)

Referencing local variables from an enclosing scope is not supported and will result in a runtime error (variable not found). This is consistent with how `lowerFunc` already works — it creates a fresh `AstLowerer` with no access to the parent's `locals` map. Closure/upvalue support can be added later as a separate feature.

### New IR/Opcodes: None

---

## Feature 4: Enums

**Syntax:** `enum Color { RED, GREEN, BLUE }` (already parsed)

### Lexer/Parser
- No changes — already fully parsed as `Stmt.EnumStmt(name, values)`

### AstLowerer — Desugar to namespace instance

`Value.Class` wraps a `ClassDescriptor` which has no mutable fields — you cannot `SetField` on a class. Instead, enums desugar to an instance used as a namespace:

```
// enum Color { RED, GREEN, BLUE }
// becomes:
// 1. Create enum value instances
redReg = NewInstance(enumValueClass, [])
SetField(redReg, "name", "RED")
SetField(redReg, "ordinal", 0)

greenReg = NewInstance(enumValueClass, [])
SetField(greenReg, "name", "GREEN")
SetField(greenReg, "ordinal", 1)

blueReg = NewInstance(enumValueClass, [])
SetField(blueReg, "name", "BLUE")
SetField(blueReg, "ordinal", 2)

// 2. Create namespace instance to hold enum values
namespaceReg = NewInstance(enumNamespaceClass, [])
SetField(namespaceReg, "RED", redReg)
SetField(namespaceReg, "GREEN", greenReg)
SetField(namespaceReg, "BLUE", blueReg)
StoreGlobal("Color", namespaceReg)
```

A `Builtins.EnumValueClass` and `Builtins.EnumNamespaceClass` (both empty `ClassDescriptor`s) are registered at VM startup.

### Runtime behavior
- `Color.RED` → `GetField` on namespace instance → enum value instance
- `Color.RED.name` → `"RED"`
- `Color.RED.ordinal` → `0`
- `Color.RED == Color.GREEN` → `false` (instance identity)
- `print(Color.RED)` → `"RED"` (toString via name field)

### New IR/Opcodes: None

---

## Feature 5: Table (replaces Record)

**Syntax:**
```
table Users {
    key id: int
    name: string
    email: string
    age: int = 0
}
```

### Lexer
- Add `KW_TABLE` keyword
- Add `KW_KEY` keyword

### Parser
- New `parseTable()` function
- Parses field declarations: optional `key` modifier, name `:` type, optional `= defaultValue`
- **New AST node:** `Stmt.TableStmt(name: Token, fields: List<TableField>)`
- **New data class:** `TableField(name: Token, type: Token, isKey: Boolean, defaultValue: Expr?)`
- Remove `Stmt.RecordStmt` from AST

### AstLowerer
- Emits a class with SQLite-backed storage
- Creates built-in methods: `delete(key)`
- Bracket access via `GET_INDEX` / `SET_INDEX` for reads and writes

### VM/Runtime
- On first use, creates SQLite table with the defined schema
- `Users[1]` → `GET_INDEX` dispatches to table's `get` method → SELECT WHERE key = ? → returns instance with fields
- `Users[1] = { name: "Bob", ... }` → `SET_INDEX` dispatches to table's `set` method → INSERT OR REPLACE. The RHS `{ ... }` is parsed as a `MapExpr` (map literal), which the table's set method unpacks into columns, applying defaults for omitted fields
- `Users.delete(1)` → DELETE WHERE key = ?
- SQLite file: auto-named relative to script (e.g., `<script_name>.db`)

### Dependencies
- `org.xerial:sqlite-jdbc` added to Gradle

### New IR/Opcodes: None — uses class/instance infrastructure

---

## Feature 6: Config

**Syntax:**
```
config Settings {
    name: string = "default"
    port: int = 8080
}
```

### Lexer
- Add `KW_CONFIG` keyword

### Parser
- New `parseConfig()` function
- Parses: `config <name> { field declarations }`
- Field: name `:` type, optional `= defaultValue`
- **Revised AST node:** `Stmt.ConfigStmt(name: Token, fields: List<ConfigField>)`
- **New data class:** `ConfigField(name: Token, type: Token, defaultValue: Expr?)`

### File resolution
- Name converted to kebab-case + `.yml`
- `Settings` → `settings.yml`
- `DatabaseConfig` → `database-config.yml`
- Resolved relative to the script file

### AstLowerer
- Desugars to class instance with fields populated from YAML at initialization
- Missing field + no default = runtime error
- Read-only at runtime: enforced via a `Builtins.ConfigClass` whose descriptor has a `readOnly` flag. The VM's `SET_FIELD` handler checks this flag and throws "Cannot modify config field" error.

### Dependencies
- SnakeYAML (or similar) added to Gradle

### New IR/Opcodes: None

---

## Feature 7: Imports

**Syntax:**
```
import utils              // namespaced: utils.doSomething()
import spawn from arena   // direct: spawn()
```

### Lexer/Parser
- No changes — already fully parsed as `ImportStmt` and `ImportFromStmt`

### AstLowerer + VM/Runtime

**`import utils`:**
1. Resolve `utils.lec` relative to current script
2. Lex, parse, lower, compile the module into its own chunk
3. Execute the chunk
4. Capture module's globals into a namespace instance
5. `StoreGlobal("utils", namespaceInstance)`
6. `utils.doSomething()` works via `GetField` on the namespace

**`import spawn, reset from arena`:**
1. Same resolution and execution as above
2. Extract each requested symbol from module globals
3. `StoreGlobal("spawn", value)`, `StoreGlobal("reset", value)` — each injected directly into caller's scope
4. If a requested symbol doesn't exist in the module, throw a compile-time or runtime error

### Module caching
- Each file loaded and executed once
- Subsequent imports return cached globals
- Cache keyed by resolved absolute file path

### Circular imports
- Maintain a set of currently-loading file paths throughout the compilation pipeline
- If a file is encountered that's already in the set, throw a clear error: "Circular import detected: a.lec -> b.lec -> a.lec"

### New IR/Opcodes: None

---

## Build Order

1. **Collection refactor** — unify Value.List/Map into classes, extending existing Range/Iterator pattern (prerequisite for Map)
2. **Ternary** — standalone, smallest change
3. **Map** — depends on collection refactor
4. **Lambda** — standalone
5. **Enum** — standalone
6. **Table** — needs SQLite dependency, new AST
7. **Config** — needs YAML dependency, new AST
8. **Import** — needs module resolution, touches pipeline

## Empty Collections

- `ListExpr` currently requires `elements.isNotEmpty()` and `MapExpr` requires `entries.isNotEmpty()` in AST.kt
- After the collection refactor, empty construction is via constructor call: `Array()`, `Map()`
- Remove the `isNotEmpty()` constraints from `ListExpr` and `MapExpr`, or keep them and rely on constructor syntax for empty collections
- Recommended: keep the constraints (empty `[]` and `{}` are ambiguous syntactically) — use `Array()` and `Map()` for empty collections

## Files Affected

- `Token.kt` — new token types (QUESTION, KW_TABLE, KW_KEY, KW_CONFIG)
- `Lexer.kt` — lex `?`, new keywords
- `AST.kt` — revise ConfigStmt, replace RecordStmt with TableStmt, add TableField/ConfigField
- `Parser.kt` — parseTable(), parseConfig(), parseTernary(), parseMap(), parseLambda()
- `AstLowerer.kt` — implement all 7 TODO branches
- `Value.kt` — remove List, Map; add Builtins.ArrayClass, Builtins.MapClass, Builtins.EnumValueClass, Builtins.EnumNamespaceClass, Builtins.ConfigClass
- `ClassDescriptor` — add `readOnly: Boolean = false` flag for config enforcement
- `VM.kt` — register built-in classes, refactor GET_INDEX/SET_INDEX/NEW_ARRAY/IS_TYPE to use instances, add readOnly check in SET_FIELD
- `OpCode.kt` — remove NEW_ARRAY, RANGE opcodes
- `IrInstr.kt` (IR.kt) — remove NewArray instruction
- `IrCompiler.kt` — update for removed opcodes
- `Chunk.kt` — no changes expected
- `build.gradle.kts` — add sqlite-jdbc, snakeyaml dependencies
