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
GenericTypeParam ::= '<' IDENTIFIER ('<' IDENTIFIER (',' IDENTIFIER)* '>')?
TypeBound        ::= ':' TYPE
FunctionDecl    ::= 'fn' IDENTIFIER GenericTypeParam? '(' Params? ')' ('->' Type)? Block
ClassDecl        ::= 'class' IDENTIFIER GenericTypeParam? ('extends' Type)? Block
```

## Implementation Phases

1. Add `GenericTypeParam` to lexer/parser — parse `<T>` syntax
2. Add type parameter storage to `FuncStmt` and `ClassStmt`
3. Add constraint checking during type resolution
4. Implement type argument inference at call sites
5. Add erasure or reification strategy for runtime (see below)

## Runtime Representation

Two options:
- **Erasure (JVM style):** Type parameters are removed at runtime. `Box<Int>` and `Box<String>` are the same class at runtime. Simpler, matches JVM.
- **Reification:** Type parameters are preserved. `Box<Int>` and `Box<String>` are distinct. More powerful but complex.

**Recommendation:** Start with erasure (JVM style). Store type arguments in metadata when needed for reflection/AI features.

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
toDebug(42)        // compile error if Int not Printable
```

## Open Questions

1. Should type parameters have default bounds? (e.g., `T` defaults to `Any`)
2. How do primitive types (Int, Bool, Float) interact with generics?
3. Should we support method-chained syntax like `list.map<Int>()`?
