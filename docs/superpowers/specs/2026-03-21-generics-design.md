# Generics Design for Inklang

## Status

Draft - awaiting implementation

## Overview

Add parametric generics to Inklang, allowing functions and classes to work with type parameters that are specified at call site. Inspired by Java/Kotlin generics with Inklang's existing `:` syntax for type bounds.

## Type Parameters

Type parameters are declared in angle brackets after the name:

```inklang
fn identity<T>(x: T) -> T { x }

class Box<T> {
    var item: T
    fn new(item: T) { Box(item) }
}
```

Multiple type parameters:
```inklang
fn map<A, B>(list: List<A>, transform: fn(A) -> B) -> List<B> {
    // implementation
}
```

## Constraints (Bounds)

Constraints use colon `:` after the type parameter — consistent with Inklang's existing type annotation syntax:

```inklang
// T must be a reference type (non-null)
fn print<T : Any>(value: T) {
    io.println(value.toString())
}

// T must be printable
fn debug<T : Printable>(value: T) -> String {
    return value.toString()
}
```

Multiple constraints on a single type parameter are separated by comma:
```inklang
fn create<T : Printable, U : Any>(value: T) -> U {
    // requires T is Printable and U is Any
}
```

### Predefined Constraint Types

| Constraint | Meaning |
|------------|---------|
| `Any` | Any non-null reference type |
| `Printable` | Has `toString() -> String` method |
| `Comparable` | Has comparison operators |

### Constraints Are Optional

Type parameters without bounds have no compile-time checking. This keeps things simple — bounds are only needed when the function actually requires certain capabilities:

```inklang
// No bounds - works with any type
fn first<T>(list: List<T>) -> T {
    return list[0]
}
```

## Generic Functions

Type arguments are inferred at call sites:
```inklang
let result = identity(42)        // infers T = Int
let boxed = Box::new("hello")   // infers T = String
```

Explicit type arguments when inference isn't possible:
```inklang
let result = identity<Int>(42)
```

## Generic Classes

```inklang
class Pair<K, V> {
    var key: K
    var value: V

    fn new(key: K, value: V) {
        this.key = key
        this.value = value
    }
}

let pair = Pair<String, Int>::new("age", 25)
let pair2 = Pair::new("name", "Alice")  // inferred
```

### Instantiation

Type arguments are inferred from constructor arguments, or specified explicitly:
```inklang
let box = Box::new(42)           // inferred Box<Int>
let box = Box<Int>::new(42)      // explicit
```

## What Is Out of Scope

- Type aliases: `type IntBox = Box<Int>`
- Variance annotations: `out`/`in` keywords
- Return-type-only constraints
- Higher-kinded types (generics of generics of generics)

## Grammar Changes

```
GenericTypeParamList ::= '<' TypeParam (',' TypeParam)* '>'
TypeParam            ::= IDENTIFIER (':' TypeConstraint)?
TypeConstraint       ::= IDENTIFIER (',' IDENTIFIER)*

FunctionDecl    ::= 'fn' IDENTIFIER GenericTypeParamList? '(' Params? ')' ('->' Type)? Block
ClassDecl       ::= 'class' IDENTIFIER GenericTypeParamList? ('extends' Type)? ClassBody

ClassBody       ::= '{' (FuncDecl | VarDecl)* '}'
ConstructorDecl ::= 'fn' 'new' '(' Params? ')' Block
```

Nested generics are parsed left-to-right: `List<List<T>>` is parsed as type `List` with arg `List<T>`, then wrapped again with outer `>`.

Constructor is declared with `fn new` — no `static` keyword needed. The class name is used to call constructors: `Box::new(42)` or `Box<Int>::new(42)`.

## Implementation Phases

1. Add `GenericTypeParam` to lexer/parser — parse `<T>` syntax
2. Add type parameter storage to `FuncStmt` and `ClassStmt`
3. Add constraint checking during type resolution
4. Implement type argument inference at call sites
5. Add erasure or reification strategy for runtime (see below)

## Runtime Representation

**Erasure (JVM style):** Type parameters are removed at runtime. `Box<Int>` and `Box<String>` are the same class at runtime. This matches the JVM model Inklang already targets.

Type arguments are stored in metadata for reflection and AI features, but bytecode operates on the erased type.

### Primitive Types

Primitives (Int, Bool, Float) are **auto-boxed** when used in generic contexts. `List<Int>` internally stores a `List<IntBox>` where `IntBox` wraps the primitive. This is handled automatically by the compiler — users write `List<Int>` and it works.

All built-in types (Int, Bool, Float, String) are considered `Printable` and `Comparable`.

## Examples

### Basic Generic Function
```inklang
fn first<T>(list: List<T>) -> T {
    return list[0]
}

let numbers = [1, 2, 3]
let firstNum = first(numbers)  // T inferred as Int
```

### Generic Class
```inklang
class Container<T> {
    var items: List<T>

    fn new() { Container(List<T>()) }
    fn add(item: T) { items.push(item) }
    fn get(index: Int) -> T { items[index] }
}

let stringContainer = Container<String>::new()
stringContainer.add("hello")
```

### Constrained Generic
```inklang
fn toDebug<T : Printable>(value: T) -> String {
    return value.toString()
}

toDebug(42)        // works - Int is Printable
toDebug("hello")   // works - String is Printable
```

### Default Bounds

Type parameters without explicit bounds default to `Any`. So `fn identity<T>` is equivalent to `fn identity<T : Any>`.

## Error Handling

| Scenario | Error |
|----------|-------|
| Type argument violates bound | "Type `X` does not satisfy constraint `Y`" |
| Explicit type args mismatch count | "Expected N type arguments, got M" |
| Inference conflict with explicit | "Cannot infer type argument — specify explicitly" |
| Primitive used where ref type needed | Compiler inserts auto-boxing |

Type argument inference uses a single algorithm for both function calls and constructors. The compiler examines argument types and return type expectations to deduce `T`.
