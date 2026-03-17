# SSA Round-Trip Integration & Comprehensive Test Coverage

**Date:** 2026-03-16
**Status:** Approved

## Goal

Integrate the existing SSA infrastructure into the main compilation pipeline as a correctness-proving round-trip (IR -> SSA -> IR), and add comprehensive unit tests for every phase of the compiler.

## Context

The Lectern compiler has a complete SSA infrastructure (`ssa/` package) that is built and tested in isolation but never called from the main compilation pipeline in `Main.kt`. The goal is to wire it in and prove that the round-trip produces functionally equivalent IR — establishing the foundation for future SSA-based optimizations.

Additionally, the project has minimal test coverage (3 test files for CFG, SSA, and optimization passes). Every pipeline phase needs dedicated unit tests to guarantee compiler correctness.

## Pipeline Change

### Current Pipeline
```
Source -> Lexer -> Parser -> ConstantFolder -> AstLowerer (IR)
  -> LivenessAnalyzer -> RegisterAllocator -> rewrite -> IrCompiler -> VM
```

### New Pipeline
```
Source -> Lexer -> Parser -> ConstantFolder -> AstLowerer (IR)
  -> SSA Builder -> SSA Deconstructor
  -> LivenessAnalyzer -> RegisterAllocator -> rewrite -> IrCompiler -> VM
```

The SSA round-trip slots in after lowering and before liveness analysis.

## Design

### SSA Round-Trip in `Main.kt`

After `AstLowerer.lower()` returns a `LoweredResult(instrs, constants)`:

1. Call `SsaBuilder.build(instrs, constants)` — this builds the CFG internally and returns an `SsaFunction`
2. Call `SsaDeconstructor.deconstruct(ssaFunc)` — returns `List<IrInstr>`
3. Reconstruct a `LoweredResult(deconstructedInstrs, result.constants)` — constants are threaded through via `SsaFunction` but we use the original constants list since the round-trip does not modify them
4. Feed the new `LoweredResult` into the existing `LivenessAnalyzer` -> `RegisterAllocator` -> `rewrite` -> `IrCompiler` path

No optimization passes run during the SSA phase — this is purely a correctness-proving round-trip.

**Note on register numbering:** The output IR will have different virtual register numbers than the input, but this is fine — the downstream `LivenessAnalyzer` and `RegisterAllocator` work on whatever virtual register numbers they receive.

### Prerequisite: Fix SsaDeconstructor Register Assignment

**Critical:** The current `SsaDeconstructor.assignRegisters()` maps all SSA versions of a base register (e.g., `x_0`, `x_1`, `x_2`) to the **same** physical register. This defeats the purpose of SSA — phi-resolution moves become no-ops (`Move(r0, r0)`) and the program is only correct by coincidence.

**Fix required:** Each distinct SSA value (`baseReg + version` pair) must get its own unique virtual register number. Change `assignRegisters()` so that every unique `(baseReg, version)` pair maps to a fresh `nextReg++`, rather than grouping by base register. This ensures phi-resolution moves are meaningful and the downstream register allocator can properly handle the liveness of each version independently.

### Per-Function SSA Round-Trip in `IrCompiler.kt`

`IrCompiler.compile()` already recurses into function and method bodies to compile them as nested chunks. The SSA round-trip is inserted at two specific points:

**Functions** (`IrInstr.LoadFunc` handler, before `LivenessAnalyzer` call):
1. Take `instr.instrs` and `instr.constants`
2. Run `SsaBuilder.build(instrs, constants)` -> `SsaDeconstructor.deconstruct(ssaFunc)`
3. Use the deconstructed IR for `LivenessAnalyzer().analyze()` and `RegisterAllocator().allocate()`

**Class methods** (`IrInstr.LoadClass` handler, before liveness analysis per method):
1. For each `(methodName, methodInfo)` in `instr.methods`
2. Run `SsaBuilder.build(methodInfo.instrs, methodInfo.constants)` -> `SsaDeconstructor.deconstruct(ssaFunc)`
3. Use the deconstructed IR for liveness analysis and register allocation

This ensures every function body — including class methods with implicit `self` — goes through SSA conversion.

### Prerequisite: Fix SsaDeconstructor Phi Resolution

**Critical:** The current `SsaDeconstructor.convertPhis()` is unsound for blocks with multiple predecessors. When a block has 2+ predecessors (e.g., the join point after if/else), it picks "the first non-undefined operand" instead of properly inserting copies in predecessor blocks. This will produce incorrect code for any program with branching variable reassignment.

**Fix required before integration:**
1. For each phi function in a block with multiple predecessors, insert a `Move` instruction at the end of each predecessor block (before its terminal jump/branch) that copies the phi operand for that predecessor into the phi result register
2. Handle critical edges: if a predecessor has multiple successors and the target has multiple predecessors, the edge is critical and needs splitting (insert a new intermediate block for the move)
3. Sequentialize parallel copies to handle cases where phi moves have circular dependencies (e.g., `a = b, b = a` needs a temp register)

**Ordering constraint:** Phi resolution and move insertion must happen before `assignRegisters()`, since newly inserted moves introduce new SSA values that need register assignments.

This fix is scoped as part of this work, not deferred.

### What Does NOT Change

- No optimization passes wired in
- Lexer, Parser, AST, Value, OpCode, Chunk are untouched
- The `opt/OptimizationPipeline` infrastructure stays unused (for future work)
- `domain/Script.kt` stays as-is

### Files Modified

- `Main.kt` — add SSA round-trip call between lowering and liveness analysis
- `IrCompiler.kt` — add SSA round-trip before each function/method compilation
- `SsaDeconstructor.kt` — fix `assignRegisters()` to give each SSA version a unique register; fix `convertPhis()` to properly insert copies in predecessor blocks with critical edge splitting

## Test Coverage

### New Test Files

#### 1. `LexerTest.kt`
- Tokenizes simple expressions (literals, operators, keywords)
- Tokenizes string interpolation (`"hello ${name}"` -> correct token sequence with INTERPOLATION_START/END)
- ASI insertion (newlines produce semicolons after identifiers, literals, `)`, `]`, `break`, `next`, `return`)
- Escape sequences in strings
- Keywords vs identifiers (e.g., `let` is KEYWORD, `letter` is IDENTIFIER)
- Multi-line input with correct line/column tracking

#### 2. `ParserTest.kt`
- Parses variable declarations (`let x = 1`, `const y = 2`)
- Parses function declarations with and without default parameters
- Parses class declarations with methods and inheritance (`extends`)
- Parses control flow (if/else chains, while, for-in)
- Parses expressions with correct operator precedence (e.g., `1 + 2 * 3` -> `1 + (2 * 3)`)
- Parses compound assignment desugaring (`x += 1` -> `x = x + 1`)
- Parses string interpolation desugaring to concatenation
- Error on invalid syntax (missing expected token)

#### 3. `ConstantFolderTest.kt`
- Folds `2 + 3` -> `IntLiteral(5)`
- Folds nested expressions `(2 + 3) * 4` -> `IntLiteral(20)`
- Leaves non-constant expressions untouched (e.g., `x + 1` stays as BinaryExpr)
- Folds across statement types (VarStmt initializer, ReturnStmt value, IfStmt condition)

#### 4. `AstLowererTest.kt`
- Lowers variable declarations to `LoadImm` + register tracking
- Lowers binary expressions to `BinaryOp` IR instructions
- Lowers function declarations to `LoadFunc` with correct arity and parameter count
- Lowers class declarations with methods, verifying `self` parameter injection
- Lowers for-in loops to iterator desugaring pattern (iter/hasNext/next calls)
- Lowers default parameters as separate `DefaultValueInfo` entries
- Lowers if/else to correct `JumpIfFalse` + `Label` jump structure

#### 5. `SsaRoundTripTest.kt` (key new tests)
- **Identity round-trip**: IR -> SSA -> deconstruct -> IR produces functionally equivalent output for straight-line code
- **Simple reassignment**: `let x = 1; x = 2; print(x)` — SSA versions correctly, deconstructs back to working IR
- **Branching**: if/else with variable reassignment — phi functions placed at join point and resolved during deconstruction
- **Loops**: while loop with counter — phi at loop header, back-edge handled correctly
- **Nested functions**: each function body gets independent SSA conversion and deconstruction
- **Class methods**: method bodies round-trip correctly with `self` parameter preserved at register 0
- **End-to-end validation**: compile and execute programs before and after SSA round-trip, verify identical output

#### 6. `LivenessAnalyzerTest.kt`
- Computes correct live range for a single variable (defined at instruction N, last used at instruction M)
- Variables used across branches have extended ranges covering both paths
- Dead variables (assigned but never read) have minimal ranges
- Loop variables stay live across back-edges

#### 7. `RegisterAllocatorTest.kt`
- Allocates non-overlapping variables to the same physical register (register reuse)
- Overlapping live ranges get different physical registers
- Function parameters pre-allocated to R0..Rn in order
- Handles up to 16 simultaneous live variables without spilling

#### 8. `IrCompilerTest.kt`
- Compiles simple IR instructions to correct 32-bit bytecode words
- Label resolution produces correct jump offsets (forward and backward jumps)
- Function bodies compiled as nested chunks with correct indices
- Default value expressions compiled as separate chunks in `functionDefaults`

#### 9. `VMTest.kt` (end-to-end execution)
- Arithmetic expressions produce correct results (int, float, double)
- String concatenation and interpolation
- Function calls with all args, with defaults, and mixed
- Class instantiation, field access, method calls
- Inheritance and method override
- For-in loop over ranges
- Break and next (continue) in loops
- Array creation, indexing, and mutation
- Nested function calls and recursion

### Existing Tests (kept as-is)
- `ControlFlowGraphTest.kt` — CFG construction
- `SsaTest.kt` — SSA building and passes
- `OptimizationPassesTest.kt` — IR optimization passes

## Success Criteria

1. All existing `.lec` test files produce identical output with and without the SSA round-trip
2. All new unit tests pass
3. `./gradlew test` passes with zero failures
4. No regressions in any existing functionality
