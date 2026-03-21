# Inklang - Claude Code Context

## Project Overview

**Inklang** is a compiled scripting language targeting a register-based bytecode VM. Written in Kotlin with PaperMC/Bukkit integration for Minecraft server scripting.

- **Language**: Kotlin 2.2.21, JVM 21
- **Build**: Gradle
- **Package**: `org.inklang`

## Repository Structure

```
inklang/
├── CLAUDE.md                    # This file
├── ARCHITECTURE.md             # Language architecture (outdated, see .context/)
├── README.md
├── build.gradle.kts            # Root build config
├── settings.gradle.kts
├── lang/                       # Core language implementation
│   ├── build.gradle.kts
│   └── src/main/kotlin/org/inklang/
│       ├── Main.kt             # Entry point
│       ├── InkCompiler.kt      # Main compiler API
│       ├── InkScript.kt        # Compiled script representation
│       ├── InkContext.kt       # Runtime context interface
│       ├── InkIo.kt            # IO builtins
│       ├── InkJson.kt          # JSON builtins
│       ├── InkDb.kt           # Database builtins
│       ├── lang/               # Lexer, Parser, AST, IR, Token types
│       │   ├── Token.kt        # TokenType enum
│       │   ├── Lexer.kt        # Tokenizer
│       │   ├── Parser.kt        # Pratt parser
│       │   ├── AST.kt           # Expr/Stmt sealed classes
│       │   ├── IR.kt            # Intermediate representation
│       │   ├── OpCode.kt        # Bytecode opcodes
│       │   ├── Value.kt         # Runtime values
│       │   ├── Chunk.kt         # Bytecode container
│       │   └── ...
│       ├── ast/                 # AST lowering, VM, optimizations
│       │   ├── AstLowerer.kt    # AST → IR lowering
│       │   ├── VM.kt            # Register-based VM
│       │   ├── ConstantFolder.kt
│       │   ├── LivenessAnalyzer.kt
│       │   ├── RegisterAllocator.kt
│       │   ├── SpillInserter.kt
│       │   ├── IrCompiler.kt    # IR → bytecode
│       │   └── ControlFlowGraph.kt
│       ├── ssa/                 # SSA optimization infrastructure
│       │   ├── SsaBuilder.kt
│       │   ├── SsaDeconstructor.kt
│       │   ├── SsaValue.kt
│       │   ├── SsaInstr.kt
│       │   ├── SsaBlock.kt
│       │   ├── SsaFunction.kt
│       │   ├── DominanceFrontier.kt
│       │   ├── SsaRenamer.kt
│       │   └── passes/          # SSA optimization passes
│       └── opt/                 # IR optimization passes
│           ├── OptimizationPipeline.kt
│           └── passes/
├── bukkit/                      # PaperMC/Bukkit plugin
│   └── src/main/kotlin/org/inklang/bukkit/
│       ├── InkPlugin.kt
│       ├── BukkitContext.kt
│       ├── BukkitIo.kt
│       ├── BukkitJson.kt
│       └── InkBukkit.kt
├── docs/                       # Documentation (Docusaurus)
│   └── superpowers/
│       ├── plans/              # Implementation plans
│       └── specs/              # Design specifications
├── test.ink                    # Test scripts
└── gradlew
```

## Compilation Pipeline

```
Source (.ink)
    │
    ▼
Lexer (tokenize) → Token stream
    │
    ▼
Parser (parse) → AST (Expr/Stmt)
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
VM (execute) → Program output
```

## Key Design Decisions

### Register-Based VM
- 16 physical registers (R0-R15) per call frame
- 32-bit packed bytecode instructions
- SPILL/UNSPILL opcodes for register overflow

### Token Types
Key operators in `Token.kt`:
- `QUESTION_DOT` (?. ) - Safe call
- `QUESTION_QUESTION` (??) - Elvis operator
- `KW_HAS` - "has" operator for field existence

### AST Nodes
Notable expression types in `AST.kt`:
- `SafeCallExpr(obj, name)` - obj?.name
- `ElvisExpr(left, right)` - left ?? right
- `HasExpr(target, field)` - target has field

### SSA Infrastructure
Full SSA construction/deconstruction with optimization passes:
- Constant propagation
- Global Value Numbering (GVN)
- Dead code elimination
- More in `lang/src/main/kotlin/org/inklang/ssa/passes/`

## Current Branch: feat/has-operator-v2

Active development implementing:
- `has` operator for field existence checking
- Null safety operators (?. and ??)
- Related test coverage

## Development Workflow

This project uses **superpowers** methodology:

1. **Plans** (`docs/superpowers/plans/`) - Step-by-step implementation tasks
2. **Specs** (`docs/superpowers/specs/`) - Design documents before plans
3. **Subagent-driven development** - Use `superpowers:subagent-driven-development` skill for multi-step implementations
4. **Verification before completion** - Use `superpowers:verification-before-completion` before claiming done

### Common Commands

```bash
# Build
./gradlew build

# Run tests
./gradlew :lang:test

# Run specific test class
./gradlew :lang:test --tests "org.inklang.InkCompilerTest"

# Run script
./gradlew run --args="test.ink"
```

## Important Files

| File | Purpose |
|------|---------|
| `lang/src/main/kotlin/org/inklang/InkCompiler.kt` | Main compiler entry point |
| `lang/src/main/kotlin/org/inklang/lang/Parser.kt` | Pratt parser for the language |
| `lang/src/main/kotlin/org/inklang/lang/AST.kt` | All expression/statement types |
| `lang/src/main/kotlin/org/inklang/ast/VM.kt` | Execution engine |
| `docs/superpowers/plans/*.md` | Current implementation plans |

## Context Documents

Detailed context is maintained in `.context/`:

- `.context/codebase/ARCHITECTURE.md` - Detailed language architecture
- `.context/codebase/COMPILER.md` - Compiler internals
- `.context/codebase/VM.md` - Virtual machine details
