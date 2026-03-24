# Printing Press - Rust Inklang Compiler

## Overview

Printing Press is a standalone Rust CLI compiler that compiles Inklang source files (`.ink`) to bytecode. It replaces the existing Kotlin compiler (`ink/src/main/kotlin/org/inklang/`). The output format is JSON compatible with the existing Kotlin `InkScript.serialize()` format, so the Kotlin VM in ink-bukkit loads it without modification.

**Location**: `~/dev/printing_press`

---

## Project Structure

```
printing_press/
├── Cargo.toml
├── src/
│   ├── main.rs              # CLI entry (clap)
│   ├── inklang/
│   │   ├── lexer.rs         # Tokenizer
│   │   ├── token.rs         # TokenType enum
│   │   ├── parser.rs        # Pratt parser → AST
│   │   ├── ast.rs           # Expr/Stmt sealed classes
│   │   ├── value.rs         # Value types (compile-time constants)
│   │   ├── lowerer.rs       # AST → IR
│   │   ├── ir.rs            # IR instruction types
│   │   ├── constant_fold.rs # Constant folding
│   │   ├── ssa/
│   │   │   ├── builder.rs
│   │   │   ├── deconstructor.rs
│   │   │   ├── passes/
│   │   │   │   ├── constant_propagation.rs
│   │   │   │   ├── gvn.rs
│   │   │   │   └── dce.rs
│   │   │   └── mod.rs
│   │   ├── liveness.rs      # Liveness analysis
│   │   ├── register_alloc.rs
│   │   ├── spill_insert.rs
│   │   ├── codegen.rs       # IR → Chunk
│   │   ├── chunk.rs         # Chunk bytecode container
│   │   └── serialize.rs    # JSON serialization (matches SerialScript schema)
│   └── error.rs             # CompilationError
└── tests/
```

---

## Compilation Pipeline

```
Source (.ink)
    │
    ▼
Lexer (tokenize) → Token stream
    │
    ▼
Parser (Pratt) → AST (Expr/Stmt)
    │
    ▼
ConstantFolder (fold) → Optimized AST
    │
    ▼
AstLowerer (lower) → IR instructions + constants
    │
    ▼
SSA Round-trip (optimizedSsaRoundTrip)
    │
    ▼
LivenessAnalyzer → Live ranges
    │
    ▼
RegisterAllocator → Virtual → Physical register map
    │
    ▼
SpillInserter → Insert SPILL/UNSPILL for overflow
    │
    ▼
IrCompiler (compile) → Chunk (packed bytecode)
    │
    ▼
Serialize (to JSON) → .ink.json file
```

---

## Serialization Contract

The Rust compiler outputs JSON matching the Kotlin `SerialScript` schema exactly. This ensures the Kotlin VM can load it via `inkScriptFromJson()` without changes.

### JSON Schema

```json
{
  "name": "<script name>",
  "chunk": {
    "code": [123456, ...],      // List of 32-bit packed bytecode words (decimal)
    "constants": [
      {"t": "null"},
      {"t": "bool", "v": true},
      {"t": "int", "v": 123},
      {"t": "float", "v": 1.5},
      {"t": "double", "v": 1.5},
      {"t": "string", "v": "hello"},
      {"t": "event", "name": "eventname", "params": [["param", "type"]]}
    ],
    "strings": ["string0", "string1", ...],
    "functions": [<SerialChunk>, ...],
    "classes": [{"name": "ClassName", "superClass": "Parent", "methods": {"method": 0}}],
    "functionDefaults": [[null, 0, null], ...],  // null = no default, int = chunk index
    "functionUpvalues": {"0": {"count": 1, "regs": [5]}},  // key = funcIdx string
    "spillSlotCount": 0,
    "cstTable": []
  },
  "configDefinitions": {
    "ConfigName": [{"name": "field", "type": "int", "defaultValue": {"t": "int", "v": 0}}]
  }
}
```

### Value Type Discriminator (`t` field)

| Type | JSON |
|------|------|
| Null | `{"t":"null"}` |
| Boolean | `{"t":"bool","v":true}` or `{"t":"bool","v":false}` |
| Int | `{"t":"int","v":123}` |
| Float | `{"t":"float","v":1.5}` (f32) |
| Double | `{"t":"double","v":1.5}` (f64) |
| String | `{"t":"string","v":"text"}` |
| EventInfo | `{"t":"event","name":"name","params":[["p","t"],...]}` |

### Bytecode Word Format (32-bit)

Each word is packed as:
```
| bits 0-7  | bits 8-11  | bits 12-15 | bits 16-19 | bits 20-31 |
| opcode    | dst (4-bit)| src1(4-bit)| src2(4-bit)| immediate  |
```

This matches the Kotlin `Chunk.write()` format.

---

## CLI Interface

```bash
# Compile a script
printing_press compile <input.ink> -o <output.json>

# Example
printing_press compile scripts/hello.ink -o build/hello.ink.json

# Help
printing_press --help
printing_press compile --help
```

### Exit Codes
- `0` - Success
- `1` - Compilation error

---

## What's NOT Included

The following are bukkit/Kotlin VM-specific and are NOT ported to Printing Press:

- **VM execution** (`ast/VM.kt`) - stays in ink-bukkit Kotlin
- **CST table** (`cstTable` in Chunk) - bukkit grammar dispatch, set to `[]`
- **PluginParserRegistry** - bukkit grammar extensions
- **Runtime builtins** (math, random, print, events) - VM provides these
- **ForkJoinPool / async** - VM handles runtime async
- **ConfigRuntime** - loads configs at runtime
- **ContextVM** - VM execution wrapper

---

## Porting Phases

### Phase 1: Core Pipeline
1. Project scaffold (Cargo, basic structure)
2. Lexer (tokenize source → Token stream)
3. Parser (Token stream → AST)
4. AST types (Expr/Stmt - matching Kotlin sealed classes)
5. Constant folder
6. AST lowerer (AST → IR)
7. IR types

### Phase 2: Optimization
8. SSA builder
9. SSA deconstructor
10. SSA optimization passes (constant propagation, GVN, DCE)
11. SSA round-trip integration

### Phase 3: Backend
12. Liveness analyzer
13. Register allocator
14. Spill inserter
15. IR compiler (IR → Chunk)

### Phase 4: Output
16. JSON serializer (matching SerialScript schema exactly)
17. CLI integration (clap)

### Phase 5: Validation
18. Compare output with Kotlin compiler on existing test scripts
19. Ensure bytecode produces identical VM behavior

---

## Testing Strategy

1. **Round-trip tests**: Compile a script with Kotlin compiler, compile with Rust compiler, compare JSON output
2. **VM execution tests**: Compile with Rust compiler, load in Kotlin VM via `inkScriptFromJson()`, execute, compare output
3. **Property tests**: Random Inklang programs compile without panic

---

## Dependencies

- `clap` - CLI argument parsing
- `serde` + `serde_json` - JSON serialization (must match Kotlin format exactly)
- `rusty_v8` - NOT needed (no JS engine, pure compiler)
- `thiserror` - error handling

No external dependencies needed for the compiler itself.
