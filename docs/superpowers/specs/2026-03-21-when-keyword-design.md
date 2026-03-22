# when Keyword Design

**Date:** 2026-03-21
**Status:** Design approved

## Overview

Implement the `when` keyword as a reactive condition watcher — an expression that observes a boolean condition and fires a block when the condition transitions from false to true, then re-arms when it becomes false again.

**Mental model:** "when X happens, do Y"

## Syntax

```
when <condition> {
    <block>
}
```

**Examples:**
```
when player.health < 20 {
    sendMessage(player, "Low health!")
}

when boss.health < 100 && player.distanceTo(boss) < 10 {
    announce("Boss is nearby and damaged!")
}
```

## Semantics

### State Machine

`when` operates as a two-state watcher:

| State | Description |
|-------|-------------|
| **Armed** | Initial state. Watching for false→true transition. |
| **Fired** | Condition is true. Block is executing or has executed. Ignores all transitions until re-armed. |

### Transition Rules

```
Initial state: Armed (wasFalse = true, wasTrue = false)

On each check:
  if armed && condition becomes true:
      fire block
      armed = false, fired = true

  if fired && condition becomes false:
      armed = true, fired = false
      (next true will fire again)
```

### Firing Behavior

- **Fires once** on the false→true transition
- **Stays fired** while condition remains true (no repeated firing)
- **Re-arms** on true→false transition
- **Fires again** on next false→true

### Condition Expression

- Any boolean expression
- Supports `&&` (AND) and `||` (OR) with proper short-circuit evaluation
- No custom operators, guards, or pattern matching (keep it simple for v1)

## Implementation

### AST

```kotlin
data class WhenStmt(
    val condition: Expr,
    val body: List<Stmt>
) : Stmt()
```

### IR

```kotlin
IrInstr.WhenStart(condReg: Int)
    // Records current condition value and arms the watcher

IrInstr.WhenCheck()
    // Called each tick — evaluates condition, fires or re-arms as needed
```

### VM Opcodes

| Opcode | Encoding | Behavior |
|--------|----------|----------|
| `WHEN_START` | dst=dst, imm=schemaIdx | Initialize watcher, arm it |
| `WHEN_CHECK` | imm=schemaIdx | Evaluate, compare to previous, fire/re-arm |

### VM State

Per-watcher runtime state:
```kotlin
data class WhenWatcher(
    val conditionReg: Int,      // register holding current condition value
    val wasTrue: Boolean,       // previous evaluation result
    val isArmed: Boolean,       // true = armed, false = fired
    val blockChunk: Chunk       // compiled block to execute on fire
)
```

### Execution Flow

1. **Compilation**: `when` expression is lowered to `WhenStart` + compiled block
2. **First evaluation**: Condition evaluated, result stored, watcher armed
3. **Per-tick check**: `WHEN_CHECK` re-evaluates condition, compares to previous, fires if transition detected

### Integration Points

- `WHEN_CHECK` is called automatically on each VM tick for all active watchers
- Watchers are stored in `VM.watcherScopes: List<MutableMap<String, WhenWatcher>>`
- Scopes are pushed/popped as function frames change (same as upvalue capture semantics)

## Files to Modify

| File | Changes |
|------|---------|
| `Token.kt` | Add `KW_WHEN` |
| `Lexer.kt` | Add `"when" -> KW_WHEN` |
| `Parser.kt` | `KW_WHEN` branch in `parseStatement()` |
| `AST.kt` | `WhenStmt` data class |
| `IR.kt` | `WhenStart`, `WhenCheck` IR nodes |
| `OpCode.kt` | `WHEN_START`, `WHEN_CHECK` opcodes |
| `AstLowerer.kt` | Lower `WhenStmt` to IR |
| `IrCompiler.kt` | Compile IR nodes to bytecode |
| `VM.kt` | Execute opcodes, maintain watcher state |

## Non-Goals

- Pattern matching / destructuring
- Guards (conditions inside branches)
- Multiple independent branches (`when { ... }` with no leading expression)
- Debouncing or coalescing rapid transitions
- Custom operators in conditions

## Open Questions

None — design is complete.
