# Default Parameters Design for Inklang

## Overview

Add Python-style default parameters to Inklang functions and constructors. Parameters with defaults are optional at call sites — if omitted, the default expression is evaluated at call site and used.

**Syntax:**
```ink
fn greet(name: String = "World") {
    io.println("Hello, " + name + "!")
}

fn add(a: Int, b: Int = 10) -> Int { a + b }

class Person {
    var name: String
    fn new(name: String = "Anonymous") {
        this.name = name
    }
}
```

**Call site behavior:**
```ink
greet()                    // name = "World"
greet("Alice")             // name = "Alice"
add(5)                     // b = 10, result 15
add(5, 3)                  // b = 3, result 8
Person::new()              // name = "Anonymous"
Person::new("Bob")         // name = "Bob"
```

**Named arguments:**
```ink
add(5, b = 20)            // named: b = 20, result 25
Person::new(name = "Carol")
```

**Rules:**
- Positional args first, then named
- Named args must come after all positional args
- Defaults evaluated at call site (not at definition)
- Defaults can reference earlier parameters: `fn foo(a: Int, b: Int = a * 2)`

## Grammar Changes

```
Param            ::= IDENTIFIER ':' Type ('=' Expr)?
                 |  IDENTIFIER ('=' Expr)?
Params           ::= Param (',' Param)*
FuncDecl         ::= 'fn' IDENTIFIER '(' Params? ')' ('->' Type)? Block
ConstructorDecl  ::= 'fn' 'new' '(' Params? ')' Block
```

**Parsing:**
- When `=` is seen after a param (either `param: Type = expr` or `param = expr`), parse the expression as the default value
- If type annotation is missing on inferred default, type is inferred from the default expression

**Precedence:** `=` has lower precedence than most expressions, so `a: Int = 1 + 2 * 3` parses as `a: Int = (1 + (2 * 3))`

## IR Representation

Add `defaultValue: IrExpr?` to track defaults:

```kotlin
data class IrParam(
    val name: String,
    val type: IrType?,
    val defaultValue: IrExpr?  // null if no default
)

data class IrFunc(
    val name: String,
    val params: List<IrParam>,
    val returnType: IrType?,
    val body: List<IrInstr>
)
```

### Call Site Lowering

When lowering a function call with arguments:

```kotlin
// For: add(5)  where add's params are [a: Int, b: Int = 10]

// Provided args (5) -> registers
r0 = 5

// Missing defaults -> evaluate and load into registers
r1 = 10        // default expression evaluated at call site

// CALL with all params now in registers
CALL r0, r1
```

### Named Argument Handling

Named arguments are resolved at parse/lower time:
```kotlin
// For: add(b = 20, a = 5)  — parsed left to right, names resolved

// Reorder to positional:
// r0 = 5 (a), r1 = 20 (b)
```

**Implementation:** At call site, build a map of `name -> IrExpr` for named args, then:
1. Fill positional args first
2. Fill named args by matching param names
3. Fill remaining params with default expressions

## VM Changes

### Call Frame & Defaults

When a function is called with fewer arguments than parameters:

```kotlin
// VM pseudocode for CALL with defaults
val providedArgs = argBuffer.size
val totalParams = func.arity
val missingDefaults = totalParams - providedArgs

// For each missing param with a default:
// 1. Evaluate default expression
// 2. Push result to arg buffer (or load into register)
```

### Register Allocation for Defaults

Default expressions are evaluated into **temporary registers** at call site, then passed to the function like normal arguments. The register allocator ensures these temps don't clash with existing registers.

### Named Arguments

Named argument resolution happens **at compile time** (IR lowering), not runtime. The VM sees only positional arguments after reordering.

### Constructor Calls

Same mechanism as functions — `fn new(...)` with defaults works identically.

## Error Handling

### Compile-Time Errors

| Scenario | Error |
|----------|-------|
| `fn foo(a = "hi", b)` | "Positional parameter `b` cannot follow parameter with default" |
| `fn foo(a: Int)` called with `foo(b = 5)` where `b` isn't a param | "Unknown parameter `b`" |
| `fn foo(a: Int = "hello")` | "Type `String` does not match parameter type `Int`" (if types are explicit and don't match) |

### Runtime Errors

| Scenario | Behavior |
|----------|----------|
| Too few args, no default | Existing behavior — error at call site |
| Too many args | Existing behavior — error at call site |
| Default expression throws | Propagates to caller |

## Test Cases

```ink
// Basic defaults
fn greet(name: String = "World") {
    io.println("Hello, " + name + "!")
}
greet()                   // Hello, World!
greet("Alice")           // Hello, Alice!

// Multiple defaults
fn add(a: Int, b: Int = 10, c: Int = 20) -> Int {
    a + b + c
}
add(1)                    // 31
add(1, 2)                 // 23
add(1, 2, 3)             // 6
add(1, c = 100)          // 111

// Default with type inference
fn foo(x = 42) { x }
foo()                     // 42
foo("hello")             // "hello" — inferred String

// Defaults referencing other params
fn scale(value: Int, multiplier: Int = value * 2) -> Int {
    value * multiplier
}
scale(5)                  // 5 * 10 = 50
scale(5, 3)              // 5 * 3 = 15

// Constructor with defaults
class Person {
    var name: String
    fn new(name: String = "Anonymous") {
        this.name = name
    }
}
Person::new()             // name = "Anonymous"
Person::new("Bob")        // name = "Bob"

// Named args with defaults
fn configure(host: String, port: Int = 8080, debug: Bool = false) {}
configure("localhost")                    // defaults for port, debug
configure("localhost", debug = true)     // port uses default
configure(host = "server", port = 9000) // all named
```

## Implementation Order

1. **Parser** — Parse `=` default syntax, add `defaultValue` to `Param`
2. **AST** — Add `defaultValue` field to `Param` and `FuncStmt`
3. **Type Checker** — Validate default types match declared types
4. **IR Lowering** — Add `IrParam.defaultValue`, handle named args reordering
5. **IR Compiler** — Emit default evaluation code at call sites
6. **VM** — No changes needed if defaults are resolved at compile time
7. **Tests** — Add test cases for all scenarios

## Out of Scope

- Default parameters on methods (only standalone functions and constructors)
- Type aliases for default expressions
- `const` defaults (compile-time constants)
