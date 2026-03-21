# Annotation System Design for Ink

## Overview

Ink will support a compile-time annotation system for attaching metadata to declarations. Annotations are processed by the compiler to enable warnings, optimizations, and validation. This is a compile-time-only system — annotations are not stored in bytecode.

## Syntax

```ink
// Annotation declaration
annotation Deprecated {
    reason: string
}

annotation Inline {
    level: int
}

// Usage — annotations before declarations
@deprecated(reason="Use calculate() instead")
fn oldFunc() { ... }

@inline(level=2)
fn hotPath() { ... }

// Class annotation
@marked
class Config { ... }

// Field annotation
@validate @notNull
name: string

// Parameter annotation
fn process(@notNull input: string) { ... }
```

## Annotatable Targets

- **Functions** — `@inline myFunc() {...}`
- **Classes** — `@marked class Foo {...}`
- **Fields/Properties** — `@validate @notNull name: string`
- **Parameters** — `fn foo(@notNull arg: int)`

Return values, enum variants, and local variables are **not** annotatable in this initial version.
Annotation declarations themselves may not be annotated ( `@hidden annotation Foo {...}` is not allowed).

Multiple annotations can be stacked on a single declaration.

## Annotation Arguments

Arguments are named, similar to Python keyword arguments:
- `@deprecated(reason="...")`
- `@inline(level=2)`
- `@annotation(field=value, another=123)`

Positional arguments not supported — all arguments must be named.

## Annotation Declaration

Users can define custom annotations via `annotation` declarations:

```ink
annotation Deprecated {
    reason: string
}

annotation Inline {
    level: int = 1
}
```

Annotation fields have types: `string`, `int`, `bool`, `float`, `double`.
Default values are specified with `= value` syntax. Fields without defaults are required.

## Built-in Annotations

| Annotation | Fields | Purpose |
|------------|--------|---------|
| `@deprecated` | `reason: string` | Warn when deprecated declaration is used |
| `@inline` | `level: int = 1` (valid range: 1–3) | Suggest inlining to optimizer |
| `@pure` | — | Function has no side effects |

**@inline levels:** 1 = suggest inline, 2 = prefer inline, 3 = always inline

## Implementation

### Lexer Changes

- Add `AT` token type
- Tokenize `@identifier` as a single token
- Handle `@identifier(args)` as AT + identifier + paren group

### Parser Changes

- Add `AnnotationDecl` statement type: `annotation Name { fields }`
- Add `AnnotationExpr` — holds annotation name + map of named args
- Parse annotations before declarations
- Attach annotation list to: `ClassStmt`, `FuncStmt`, `VarStmt`, `FuncParam`

### AST Changes

```kotlin
// New Expr type
sealed class Expr {
    data class Annotation(
        val name: String,
        val args: Map<String, Expr>
    ) : Expr()
}

// Attach to statements
data class ClassStmt(
    val annotations: List<Expr.Annotation>,
    val name: String,
    ...
)

// Attach to FuncParam
data class FuncParam(
    val annotations: List<Expr.Annotation>,
    val name: String,
    val type: Type?,
    val default: Expr?
)
```

### Compiler Pipeline

```
Source Code
    |
    v
Lexer (tokenize) --> Token stream
    |
    v
Parser (parse) --> AST (with annotation attachments)
    |
    v
[NEW] AnnotationChecker (validates annotation args, processes @deprecated, @inline, @pure)
    |
    v
ConstantFolder (existing — runs after annotation processing)
    |
    v
AstLowerer
    ...
```

**Pipeline note:** `AnnotationChecker` runs before `ConstantFolder` so annotation argument validation (e.g., `@inline(level="foo")` type errors) occurs before constant folding. After validation, annotation arguments are stored as literals and do not participate in constant folding.

### Annotation Processing (Compile-Time)

1. **DeprecatedChecker** — When resolving a call/reference to a `@deprecated` declaration, emit a compiler warning with the reason.
2. **InlineProcessor** — Attach inline hints to the IR for the optimizer.
3. **PureValidator** — For `@pure` functions, verify no side-effecting operations:
   - No reads or writes to globals
   - No calls to non-`@pure` functions
   - No I/O operations (print, read, file I/O, network, etc.) — including built-in functions
   - A `@pure` function calling another `@pure` function is allowed.
   - If a `@pure` function calls an unknown/unannotated function, emit an error.
   - Built-in functions are considered I/O and are not allowed in `@pure` functions.
4. **Unknown annotations** — Ignored by default. To enable warnings for unknown annotations, use the `--warn-unknown-annotations` compiler flag.

### Error Handling

| Error | Behavior |
|-------|----------|
| Unknown annotation | Ignored by default; warning if `--warn-unknown-annotations` flag is set |
| `@inline` on non-function | Compiler error |
| Missing required annotation field | Compiler error |
| Unknown annotation field | Compiler error |
| Annotation field type mismatch | Compiler error (e.g., `@inline(level="foo")`) |
| `@deprecated` usage | Compiler warning with reason |
| `@pure` calls unknown-purity function | Compiler error |

## Testing

```ink
// Test deprecation warning
@deprecated(reason="Old API")
fn oldApi() { true }

// Test inline annotation
@inline
fn smallFunc() { 1 + 1 }

// Test annotation on class
@marked
class Foo {}

// Test parameter annotations
fn check(@notNull value: string) { value }
```

## Files to Modify

1. `Token.kt` — Add `AT` token type
2. `Lexer.kt` — Tokenize `@identifier` and `@identifier(args)`
3. `Parser.kt` — Parse annotation declarations and usage
4. `AST.kt` — Add `AnnotationDecl` Stmt, `Annotation` Expr, attach to existing types
5. `InkCompiler.kt` — Add `AnnotationChecker` pass to pipeline
6. New file: `AnnotationChecker.kt` — Built-in annotation processors
7. Tests in `lang/src/test/kotlin/org/inklang/`