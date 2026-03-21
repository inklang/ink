# Inklang Architecture

> Detailed architecture reference. For quick context, see CLAUDE.md.

## Language Design

Ink is a compiled, statically-typed scripting language targeting a register-based bytecode VM. It supports classes, first-class functions, closures, string interpolation, default parameters, iterator-based for loops, and null safety operators.

### Source File Extensions
- `.ink` - Primary source files
- `.ain` - Not sure, possibly alternate extension

### Built-in Types
- `int`, `float`, `double`, `string`, `bool`, `null`
- `list`, `set`, `tuple`, `map` collection literals
- `class` with single inheritance
- `enum`
- `table` (stub - parsed but not fully implemented)
- `config` (stub - parsed but not fully implemented)

### Operators

| Operator | Type | Description |
|----------|------|-------------|
| `?.` | Safe call | Returns null if receiver is null |
| `??` | Elvis | Returns right side if left is null |
| `has` | Existence | Checks if object has a field |
| `is` | Type check | Runtime type checking |
| `and`, `or` | Logical | Short-circuit evaluation |
| `not` | Unary | Boolean negation |
| `++`, `--` | Prefix | Increment/decrement |
| `**` | Power | Exponentiation |

## Compilation Pipeline

### 1. Lexing (`Lexer.kt`)
- `tokenize(source: String): List<Token>`
- Handles string interpolation: `"Hello ${name}!"` → tokens with `INTERPOLATION_START`, `INTERPOLATION_END`
- Automatic Semicolon Insertion (ASI) at newlines when previous token can end a statement
- Recognizes `?.` and `??` as single tokens (`QUESTION_DOT`, `QUESTION_QUESTION`)

### 2. Parsing (`Parser.kt`)
- Pratt parser with precedence-based expression parsing
- Weights map controls operator precedence
- Parses string interpolation into synthetic binary `+` operations
- Constructs AST with `Expr` sealed class hierarchy

### 3. Constant Folding (`ConstantFolder.kt`)
- Pre-lowering constant expression evaluation
- Evaluates literal expressions at compile time when possible

### 4. AST Lowering (`AstLowerer.kt`)
- `lower(statements: List<Stmt>): LoweredResult`
- Transforms AST into IR (Intermediate Representation)
- Allocates virtual registers
- Desugars:
  - For loops → while loops with iterator protocol
  - Default parameters → separate IR chunks stored in `functionDefaults`
  - String interpolation → synthetic `+` chain
  - `has` operator → `HAS` IR instruction
  - `SafeCallExpr` → conditional null check with jumps
  - `ElvisExpr` → conditional null check with jumps

### 5. SSA Round-trip (`IrCompiler.optimizedSsaRoundTrip`)
- IR → SSA conversion using Cytron algorithm
- SSA optimization passes (constant propagation, GVN, DCE)
- SSA → IR deconstruction
- **Note**: This is a round-trip optimization, not the primary IR form

### 6. Register Allocation (`RegisterAllocator.kt`)
- Linear scan allocation
- Maps unlimited virtual registers to 16 physical registers (R0-R15)
- `LivenessAnalyzer` computes live ranges first

### 7. Spill Insertion (`SpillInserter.kt`)
- Inserts `SPILL`/`UNSPILL` instructions for register pressure overflow
- Uses spill slots in chunk metadata

### 8. Bytecode Compilation (`IrCompiler.kt`)
- Two-pass: first resolves all label offsets, then emits bytecode
- Packs instructions into 32-bit words
- Creates `Chunk` with code, constants, functions, classes

## Bytecode Format

### Instruction Layout (32-bit)
```
| bits 0-7  | bits 8-11  | bits 12-15 | bits 16-19 | bits 20-31 |
| opcode    | dst (4-bit)| src1(4-bit)| src2(4-bit)| immediate  |
```

### Opcodes (`OpCode.kt`)
```
LOAD_IMM(0x00)     - Load immediate value
POP(0x01)          - Pop stack
LOAD_GLOBAL(0x05)   - Load global variable
STORE_GLOBAL(0x06)  - Store global variable
MOVE(0x07)         - Move register
ADD/SUB/MUL/DIV(0x08-0x0B)
NEG(0x0C)          - Negate
NOT(0x0D)          - Boolean not
EQ/NEQ/LT/LTE/GT/GTE(0x0E-0x13)
JUMP/JUMP_IF_FALSE(0x14-0x15)
LOAD_FUNC(0x16)    - Load function
CALL(0x17)         - Function call
RETURN(0x18)       - Return
BREAK/NEXT(0x19-0x1A) - Loop control
MOD(0x1B)          - Modulo
PUSH_ARG(0x1C)     - Argument for call
GET_FIELD(0x1D)    - Get object field
SET_FIELD(0x1E)    - Set object field
NEW_INSTANCE(0x1F) - Create class instance
IS_TYPE(0x20)      - Type check
NEW_ARRAY(0x21)    - Create array
GET_INDEX(0x22)    - Array/index access
SET_INDEX(0x23)    - Set index
RANGE(0x24)        - Create range
BUILD_CLASS(0x25)  - Build class
SPILL(0x26)        - Spill register to slot
UNSPILL(0x27)      - Restore register from slot
POW(0x28)          - Power
HAS(0x29)          - Field existence check
THROW(0x2A)        - Throw exception
```

## VM Execution (`VM.kt`)

### Register Model
- 16 physical registers per call frame
- Separate stack for return addresses, spilled values
- Call frames track: function entry label, register state, spill slots

### Class System
- Single inheritance via `extends`
- Methods receive implicit `self` at index 0
- `init` methods are constructors called by `NEW_INSTANCE`
- `BoundMethod` returned for method access

### For Loop Desugaring
```
for x in collection { body }
→ let __iter = collection.iter()
→ while (__iter.hasNext()) {
→     let x = __iter.next()
→     body
→ }
```

## SSA Infrastructure

### SSA Form
- Versioned registers: `r0.0`, `r0.1`, etc.
- Phi functions for merge points
- Dominance frontier for phi placement

### SSA Passes
- `SsaConstantPropagationPass` - Propagate constant values
- `SsaGlobalValueNumberingPass` - GVN within single block
- `SsaCrossBlockGvnPass` - GVN across blocks using domination
- `SsaDeadCodeEliminationPass` - Remove unreachable code

## IR Optimization Pipeline

### IR Passes (`opt/passes/`)
- `ConstantFoldingPass` - IR-level constant folding
- `InductionVariablePass` - Range iterator normalization
- `StrengthReductionPass` - Algebraic simplification (x*2 → x+x)
- `DeadCodeEliminationPass` - Unreachable block removal
- `CopyPropagationPass` - Redundant MOVE elimination
- `LoopInvariantCodeMotionPass` - Hoist invariant code
- `BranchOptimizationPass` - Conditional branch optimization

## Null Safety Implementation

### Safe Call (`?.`)
Parsed as `SafeCallExpr` in AST, lowered to IR:
```
obj?.name
→ temp = obj
→ if temp == null goto null_label
→ result = temp.name
→ goto end
null_label:
→ result = null
end:
```

### Elvis (`??`)
Parsed as `ElvisExpr` in AST, lowered to IR:
```
left ?? right
→ temp = left
→ if temp != null goto end
→ temp = right
end:
→ result = temp
```

## Error Handling

- Exceptions thrown with `THROW` opcode
- Try/catch/finally parsing implemented
- `CompilationException` wraps all compilation errors
- Parser stops at first error (no error recovery)

## Known Limitations

1. **Error recovery** - Parser stops at first error
2. **SSA not integrated as primary IR** - Used for round-trip optimization only
3. **16 register limit** - Spilling handles most overflow
4. **Import/Config/Table stubs** - Parsed but no runtime implementation
