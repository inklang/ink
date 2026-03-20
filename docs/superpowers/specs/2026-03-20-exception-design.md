# Exception Design Specification

## Overview

Add exception handling support to the Inklang language: `throw`, `try`, and `catch`.

## Syntax

```quill
// Throwing an exception
throw Exception("error message")

// Try-catch
try {
    riskyOperation()
} catch e {
    print(e.message)
}
```

## Semantics

### Exception Class

The built-in `Exception` class has:
- `message` field (string) — the error message

```quill
class Exception {
    init(message: string) {
        this.message = message
    }
}
```

### Throw

- `throw` takes a single expression (must evaluate to an Exception instance)
- Throws exception, unwinds call stack, jumps to nearest catch handler

### Try-Catch

- `try` block is executed normally
- If no exception thrown, `catch` is skipped
- If exception thrown anywhere in `try` block (including nested calls), execution jumps to `catch`
- The catch variable `e` is bound to the thrown exception instance
- Only one catch block per try (no exception type filtering)

## Implementation

### AST Changes (AST.kt)

```kotlin
// New statement types
object ThrowStmt(val expr: Expr) : Stmt()
data class TryCatchStmt(
    val tryBlock: BlockStmt,
    val catchBlock: BlockStmt,
    val catchVar: Token  // "e" in catch e { }
) : Stmt()
```

### Parser Changes (Parser.kt)

```
tryStmt      → "try" blockStmt "catch" IDENTIFIER blockStmt
throwStmt    → "throw" expression ";"
```

### IR / Lowering (AstLowerer.kt)

Lower `TryCatchStmt` to IR instructions:

```
TRY_START(label)       ; marks start of try region
... (try body) ...
TRY_END                 ; end of try body, normal flow
JUMP(endLabel)          ; skip catch block on normal completion
CATCH_LABEL:           ; catch label
... (catch body) ...
END_LABEL:              ; after try-catch
```

Lower `ThrowStmt`:
```
... (evaluate expression to a register) ...
THROW                   ; opcode that unwinds and jumps to nearest catch
```

### Opcodes (OpCode.kt)

- `TRY_START` — marks beginning of protected region, operand is catch label index
- `TRY_END` — marks end of protected region
- `THROW` — throws exception, src register contains the exception instance

### VM Changes (VM.kt)

1. Add `CallFrame` fields:
   - `pendingException: Value?` — holds exception to be caught
   - `tryStartStack: ArrayDeque<Int>` — stack of protected region start IPs

2. `THROW` opcode:
   - Pop exception from src register
   - Unwind call frames
   - Look for nearest `TRY_START` in the unwound frames
   - Store exception in `pendingException` of that frame
   - Jump to the catch label

3. Exception unwinding:
   - Walk the frame stack backwards
   - For each frame with an active try, transfer control to its catch
   - If no try found, propagate up or terminate program

### Builtins (Value.kt)

Add `ExceptionClass` to Builtins:
```kotlin
val ExceptionClass = ClassDescriptor(
    name = "Exception",
    superClass = null,
    methods = mapOf(
        "init" to NativeFunction { args ->
            val self = args[0] as Instance
            self.fields["message"] = args[1]
            Null
        }
    )
)
```

### Lowering for Throw

```kotlin
is Stmt.ThrowStmt -> {
    val exnReg = freshReg()
    lowerExpr(stmt.expr, exnReg)
    emit(IrInstr.Throw(exnReg))
}
```

### Lowering for TryCatch

```kotlin
is Stmt.TryCatchStmt -> {
    val catchLabel = freshLabel()
    val endLabel = freshLabel()

    emit(IrInstr.TryStart(catchLabel))
    lowerBlock(stmt.tryBlock)
    emit(IrInstr.TryEnd)
    emit(IrInstr.Jump(endLabel))
    emit(IrInstr.Label(catchLabel))

    // Set up catch variable
    val catchReg = freshReg()
    // Exception is stored in a special frame field
    locals[stmt.catchVar.lexeme] = catchReg
    emit(IrInstr.LoadPendingException(catchReg))

    lowerBlock(stmt.catchBlock)
    emit(IrInstr.Label(endLabel))
}
```

Note: `LoadPendingException` is a new IR instruction to load from frame field.

## Test Cases

```quill
// Basic throw and catch
try {
    throw Exception("oops")
} catch e {
    print(e.message)  // prints "oops"
}

// Exception in nested call
func risky() {
    throw Exception("fail")
}

try {
    risky()
} catch e {
    print(e.message)  // prints "fail"
}

// Try without catch (let exception propagate)
func outer() {
    try {
        throw Exception("inner")
    } catch e {
        print("caught inner")
    }
    throw Exception("outer")
}

// Multiple statements in try
try {
    let x = 1
    let y = 2
    if x < y {
        throw Exception("x is less than y")
    }
    print("after throw")
} catch e {
    print(e.message)
}
print("after try-catch")  // still executes
```

## Out of Scope

- Exception type filtering (`catch e: TypeError`)
- Finally blocks
- Exception chaining
- Custom exception subclasses
- Built-in exception types (TypeError, ValueError, etc.)
- Stack traces

## Files to Modify

1. `src/main/kotlin/org/inklang/lang/AST.kt` — Add ThrowStmt, TryCatchStmt
2. `src/main/kotlin/org/inklang/lang/Parser.kt` — Parse try/catch/throw
3. `src/main/kotlin/org/inklang/lang/OpCode.kt` — Add TRY_START, TRY_END, THROW
4. `src/main/kotlin/org/inklang/lang/IR.kt` — Add IrInstr.TryStart, IrInstr.Throw
5. `src/main/kotlin/org/inklang/lang/Value.kt` — Add ExceptionClass to Builtins
6. `src/main/kotlin/org/inklang/ast/AstLowerer.kt` — Lower try/catch/throw to IR
7. `src/main/kotlin/org/inklang/ast/IrCompiler.kt` — Compile IR to bytecode
8. `src/main/kotlin/org/inklang/ast/VM.kt` — Implement THROW opcode execution
9. `src/test/kotlin/org/inklang/ast/VMTest.kt` — Add tests
