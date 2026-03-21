# Null Safety Operators Design

## Overview

Add two null safety operators inspired by Kotlin:
- **`?.` Safe call**: Returns `null` if the receiver is `null`, otherwise performs the field/method access
- **`??` Elvis**: Returns the left side if not null, otherwise returns the right side

## Approach

### 1. Lexer changes (`lang/src/main/kotlin/org/inklang/lang/Lexer.kt`)

- Add recognition for `??` token (elvis operator)
- `?.` is already lexable as separate tokens (QUESTION + DOT) - handled in parser

### 2. Token types (`lang/src/main/kotlin/org/inklang/lang/Token.kt`)

- Add `QUESTION_QUESTION` token type for elvis operator `??`

### 3. Parser changes (`lang/src/main/kotlin/org/inklang/lang/Parser.kt`)

- Handle `?.` in `parsePostfix()` - after matching DOT, check if next token is `?`
  - If `?` follows DOT, consume it and return `SafeCallExpr` instead of `GetExpr`
- Add elvis operator `??` with precedence weight 15 (between `or` at 20 and assignment at 10)
- Parse elvis in `parseExpression()` similar to ternary

### 4. AST changes (`lang/src/main/kotlin/org/inklang/lang/AST.kt`)

- Add `SafeCallExpr(val obj: Expr, val name: Token)` - null-safe property access
- Add `ElvisExpr(val left: Expr, val right: Expr)` - null-coalescing

### 5. IR changes (`lang/src/main/kotlin/org/inklang/lang/IR.kt`)

- No new IR instructions needed - operators are desugared in AST lowering

### 6. AST Lowerer changes (`lang/src/main/kotlin/org/inklang/ast/AstLowerer.kt`)

- `SafeCallExpr` desugaring:
  1. Evaluate obj into temp register
  2. Jump to null-label if temp is null
  3. Get field from temp
  4. Jump to end-label
  5. null-label: load null constant
  6. end-label

- `ElvisExpr` desugaring:
  1. Evaluate left into temp register
  2. Jump to end-label if temp is not null
  3. Evaluate right
  4. end-label

### 7. VM changes

- No VM changes needed - IR lowering handles the logic

## Example Transformations

### Safe Call
```ink
user?.name
```
Desugars to (conceptually):
```
temp = user
if (temp == null) goto null_label
result = temp.name
goto end_label
null_label:
result = null
end_label:
```

### Elvis Operator
```ink
value ?? "default"
```
Desugars to (conceptually):
```
temp = value
if (temp != null) goto end_label
temp = "default"
end_label:
result = temp
```

### Chained Safe Calls
```ink
user?.address?.city
```
Chained safe calls work correctly - each `?.` introduces its own null check.

## Testing

Add tests for:
- `user?.name` where user is null → returns null
- `user?.name` where user is not null → returns name
- `value ?? "default"` where value is null → returns "default"
- `value ?? "default"` where value is not null → returns value
- `user?.address?.city` chained safe calls
- `user?.name ?? "anonymous"` combining both operators
