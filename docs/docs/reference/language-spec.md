---
sidebar_position: 1
title: Language Specification
---

# Ink Language Specification

> **Status:** Draft
> **Date:** 2026-03-22
> **Version:** 1.0

---

## 1. Overview

Ink is a compiled scripting language targeting a register-based bytecode VM. It is designed for embedding in JVM applications (primarily Minecraft/PaperMC) as a safe, expressive scripting layer.

- **Language name:** Ink
- **File extension:** `.ink`
- **Toolchain:** Quill (`quill` CLI for package management, building, and running)
- **Namespace:** `org.inklang`

### Compilation Pipeline

```
Source (.ink)
    -> Lexer        (tokenize)      -> Token stream
    -> Parser       (parse)         -> AST (Stmt/Expr nodes)
    -> ConstantFolder (fold)        -> Optimized AST
    -> AstLowerer   (lower)        -> IR instructions
    -> LivenessAnalyzer (analyze)  -> Live ranges
    -> RegisterAllocator (allocate) -> Physical register map
    -> IrCompiler   (compile)      -> Chunk (packed 32-bit bytecode)
    -> VM           (execute)      -> Program output
```

---

## 2. Lexical Structure

### 2.1 Character Set

Ink source files are UTF-16 encoded text.

### 2.2 Identifiers

Identifiers start with a letter (`a-z`, `A-Z`) or underscore (`_`), followed by zero or more letters, digits (`0-9`), or underscores. Identifiers are case-sensitive.

```
identifier = (letter | '_') (letter | digit | '_')*
```

### 2.3 Keywords

The following 32 identifiers are reserved as keywords and cannot be used as variable or function names:

```
bool    int     float   double  string  true    false   null
let     const   if      else    while   for     in      fn
return  and     or      not     break   next    enum    class
extends import  from    is      has     table   key     config
```

Additionally, the following words are **contextual keywords** — they have special meaning in specific syntactic positions but are not currently reserved by the lexer. User code should avoid shadowing them, as they may become fully reserved in a future version:

```
try     catch   finally throw   self
```

### 2.4 Comments

Single-line comments begin with `//` and extend to the end of the line.

```ink
// This is a comment
let x = 42  // Inline comment
```

Block comments are not supported.

### 2.5 Semicolons and Automatic Semicolon Insertion (ASI)

Statements may be terminated with an explicit `;`. When omitted, the lexer performs Automatic Semicolon Insertion: a semicolon is inserted at a newline when the previous token could end a statement.

Tokens that trigger ASI:
- `IDENTIFIER`
- Number literals (`int`, `float`, `double`)
- `string` literal
- `true`, `false`, `null`
- `)`, `]`
- `break`, `next`

The `}` token does NOT trigger ASI — it is a block terminator, not a statement terminator.

### 2.6 String Literals

String literals are enclosed in double quotes and support the following escape sequences:

| Escape | Character |
|--------|-----------|
| `\n` | Newline |
| `\t` | Tab |
| `\r` | Carriage return |
| `\"` | Double quote |
| `\\` | Backslash |
| `\$` | Literal dollar sign |

**String interpolation:** Expressions inside `${...}` within a string are evaluated and concatenated:

```ink
let name = "world"
let msg = "Hello, ${name}!"  // "Hello, world!"
```

Interpolation is desugared during parsing into string concatenation using the `+` operator. Interpolations can be nested and can contain any valid expression.

### 2.7 Number Literals

**Integer literals:** Sequences of digits, optionally prefixed with `-`.
```ink
42
-100
0
```

**Double literals:** Sequences of digits containing a decimal point (`.`), where at least one digit follows the point.
```ink
3.14
-0.5
```

There is no literal syntax for `float` — float values are produced through explicit coercion or API calls.

---

## 3. Types

### 3.1 Primitive Types

| Type | Size | Description | Literal examples |
|------|------|-------------|-----------------|
| `int` | 32-bit signed | Integer values | `42`, `-100`, `0` |
| `float` | 32-bit IEEE 754 | Single-precision floating point | *(no literal — via coercion or API)* |
| `double` | 64-bit IEEE 754 | Double-precision floating point | `3.14`, `-0.5` |
| `string` | UTF-16 | Text values | `"hello"`, `"hi ${name}"` |
| `bool` | Boolean | Logical true/false | `true`, `false` |
| `null` | Unit | Absence of value | `null` |

### 3.2 Composite Types

#### Array

Ordered, mutable, mixed-type collection. Array literals require at least one element. To create an empty array, use the `Array()` constructor.

```ink
let numbers = [1, 2, 3]
let mixed = [1, "two", true]
let empty = Array()
numbers[0]         // 1
numbers.push(4)
numbers.size()     // 4
```

**Methods:** `size()`, `push(val)`, `get(idx)`, `set(idx, val)`, `iter()`

#### Map

Mutable key-value collection. Keys can be any value type; equality is exact (no coercion).

```ink
let user = {"name": "Alice", "age": 30}
user.get("name")     // "Alice"
user.set("active", true)
user.keys()          // ["name", "age", "active"]
```

**Literal syntax:** `{key: value, key: value}` — disambiguated from Set by the presence of `:` after the first expression.

**Methods:** `get(key)`, `set(key, val)`, `size()`, `keys()`, `values()`, `delete(key)`

To create an empty map, use the `Map()` constructor.

#### Set

Mutable, unordered collection of unique values.

```ink
let s = {1, 2, 3}
let empty = Set()
s.add(4)
s.has(2)    // true
s.size()    // 4
```

**Literal syntax:** `{expr, expr, ...}` — disambiguated from Map by the absence of `:` after the first expression. Empty `{}` is not valid in expression position. Use `Set()` to create an empty set.

**Methods:** `add(val)`, `has(val)`, `remove(val)`, `size()`, `clear()`, `delete(val)`, `iter()`

#### Tuple

Immutable, ordered sequence. Supports empty tuples and single-element tuples with trailing comma.

```ink
let point = (10, 20)
let empty = ()
let single = (42,)     // trailing comma distinguishes from grouping
point.get(0)           // 10
point.size()           // 2
```

**Methods:** `size()`, `get(idx)`, `has(val)`, `iter()`

#### Range

Immutable integer range, created with the `..` operator. Inclusive on both ends. Iterable.

```ink
let r = 0..10
for i in r {
    print(i)  // 0, 1, 2, ..., 10
}
```

### 3.3 Internal Types

These types exist at the runtime level but are not directly constructible by user code:

| Type | Description |
|------|-------------|
| `Function` | Compiled function value (chunk + arity + defaults) |
| `NativeFunction` | Host-provided function |
| `BoundMethod` | Method bound to a specific instance |
| `Class` | Class descriptor — holds methods and static fields |
| `Instance` | Instantiated object with fields |

### 3.4 Type Checking

The `is` operator checks the runtime type of a value:

```ink
42 is int           // true
"hello" is string   // true
dog is Animal       // true (checks class descriptor)
```

Valid type names for `is`: `int`, `float`, `double`, `string`, `bool`, and any class/enum name. To check for null, use `expr == null`.

---

## 4. Operators

### 4.1 Precedence Table

From highest to lowest precedence:

| Precedence | Operator | Associativity | Description |
|-----------|----------|---------------|-------------|
| — (postfix) | `.`, `[]`, `()` | Left | Field access, index, call (binds tightest) |
| — (prefix) | `-`, `!`, `not`, `++`, `--` | Right | Unary negate, not, increment, decrement |
| 80 | `**` | Left | Exponentiation (`a**b**c` is `(a**b)**c`) |
| 70 | `*`, `/`, `%` | Left | Multiply, divide, modulo |
| 60 | `+`, `-` | Left | Add / string concatenation, subtract |
| 55 | `..` | Left | Range construction |
| 50 | `<`, `>`, `<=`, `>=` | Left | Relational comparison |
| 45 | `has` | Left | Field/key existence check |
| 40 | `==`, `!=` | Left | Equality / inequality |
| 35 | `is` | Left | Type check |
| 30 | `and` | Left | Logical AND (short-circuit) |
| 20 | `or` | Left | Logical OR (short-circuit) |
| 15 | `? :` | Right | Ternary conditional |
| 10 | `=`, `+=`, `-=`, `*=`, `/=`, `%=` | Right | Assignment |

### 4.2 Arithmetic Operators

| Operator | Operation | Operands |
|----------|-----------|----------|
| `+` | Addition / string concatenation | Numeric types or strings |
| `-` | Subtraction | Numeric types |
| `*` | Multiplication | Numeric types |
| `/` | Division | Numeric types |
| `%` | Modulo | Numeric types |
| `**` | Exponentiation | Numeric types |

When `+` is applied to a string and any other value, the other value is coerced to a string and the result is concatenation.

### 4.3 Comparison Operators

| Operator | Description |
|----------|-------------|
| `==` | Equal (exact value equality, no coercion) |
| `!=` | Not equal |
| `<` | Less than |
| `>` | Greater than |
| `<=` | Less than or equal |
| `>=` | Greater than or equal |

### 4.4 Logical Operators

| Operator | Description |
|----------|-------------|
| `and` | Logical AND with short-circuit evaluation |
| `or` | Logical OR with short-circuit evaluation |
| `not` / `!` | Logical negation (both forms are equivalent) |

### 4.5 Assignment Operators

| Operator | Equivalent |
|----------|-----------|
| `=` | Simple assignment |
| `+=` | `x = x + value` |
| `-=` | `x = x - value` |
| `*=` | `x = x * value` |
| `/=` | `x = x / value` |
| `%=` | `x = x % value` |

Assignment targets must be a variable name, field access (`obj.field`), or index access (`obj[idx]`).

### 4.6 Unary Operators

| Operator | Description |
|----------|-------------|
| `-expr` | Numeric negation |
| `!expr` / `not expr` | Logical negation |
| `++expr` | Prefix increment |
| `--expr` | Prefix decrement |

### 4.7 Special Operators

**Range:** `a..b` constructs a `Range` from `a` to `b` (both inclusive, integers only).

**Ternary:** `condition ? thenExpr : elseExpr` — right-associative. `a ? b : c ? d : e` parses as `a ? b : (c ? d : e)`.

**Type check:** `expr is TypeName` — returns `bool`. See section 3.4.

**Field check:** `expr has expr` — returns `bool`. The right-hand side is any expression; it is evaluated at runtime and must resolve to a string (runtime error if not). Checks own instance fields only (no inheritance walk). Works on class instances and maps. Returns `false` for all other types (integers, strings, arrays, etc.).

---

## 5. Variables and Declarations

### 5.1 Mutable Variables

```ink
let x = 42
let name = "ink"
x = 100  // reassignment allowed
```

`let` declares a mutable variable. The initializer is optional (`let x` declares `x` as `null`).

### 5.2 Constants

```ink
const PI = 3.14159
PI = 3.0  // compile-time error: cannot reassign const
```

`const` declares an immutable binding. Reassignment is a compile-time error.

### 5.3 Scoping

Variables are block-scoped. A variable is visible within the `{}` block where it is declared and all nested blocks. There is no hoisting — variables must be declared before use.

```ink
let global = "visible everywhere"

fn example() {
    let local = "visible in this function"
    if true {
        let inner = "visible in this block"
        print(global)  // OK
        print(local)   // OK
        print(inner)   // OK
    }
    // inner is not accessible here
}
// local is not accessible here
```

---

## 6. Control Flow

### 6.1 If / Else If / Else

```ink
if condition {
    // then branch
} else if otherCondition {
    // else-if branch
} else {
    // else branch
}
```

- No parentheses around the condition
- Braces `{}` are required
- `else if` and `else` branches are optional
- `else if` chains are unlimited

### 6.2 While Loop

```ink
while condition {
    // body
}
```

### 6.3 For-In Loop

```ink
for variable in iterable {
    // body
}
```

Desugared internally to:

```ink
let __iter = iterable.iter()
while __iter.hasNext() {
    let variable = __iter.next()
    // body
}
```

Any value with an `iter()` method returning an object with `hasNext() -> bool` and `next() -> value` methods is a valid iterable. Built-in iterables: `Range`, `Array`, `Set`, `Tuple`. Note that `Map` is **not** directly iterable — use `map.keys()` or `map.values()` to obtain an iterable `Array`.

### 6.4 Loop Control

| Statement | Description |
|-----------|-------------|
| `break` | Exits the innermost enclosing loop immediately |
| `next` | Skips to the next iteration of the innermost enclosing loop |

---

## 7. Functions

### 7.1 Named Functions

```ink
fn name(param1: Type, param2: Type) -> ReturnType {
    // body
}
```

- Declared with the `fn` keyword
- Parameter types are optional annotations (`: Type` after param name)
- Return type is optional (`-> Type` after parameter list)
- Type annotations are currently informational — no compile-time type checking is enforced
- Functions without an explicit `return` statement return `null`

### 7.2 Default Parameters

```ink
fn connect(host: string, port: int = 8080, debug: bool = false) {
    // ...
}

connect("localhost")              // port=8080, debug=false
connect("localhost", 3000)        // port=3000, debug=false
connect("localhost", 3000, true)  // port=3000, debug=true
```

- Default parameters must come after all required parameters
- Default value expressions are evaluated at call time (not definition time)

### 7.3 Lambdas

```ink
let double = (x) -> { return x * 2 }
let add = (a: int, b: int) -> { return a + b }
let noop = () -> { }
```

- Parenthesized parameter list, `->` arrow, block body
- Parameter type annotations are optional
- Default parameters are **not** supported on lambdas
- Lambdas are first-class values — they can be assigned, passed as arguments, and returned

### 7.4 Closures

Lambdas capture variables from their enclosing scope by reference:

```ink
fn makeCounter() {
    let count = 0
    return () -> {
        count += 1
        return count
    }
}

let counter = makeCounter()
counter()  // 1
counter()  // 2
```

- Captured variables are shared — mutations in the closure are visible in the enclosing scope and vice versa
- Maximum 15 captured variables per closure (4-bit encoding constraint)

### 7.5 Calling Functions

```ink
greet("world")
let result = double(21)
obj.method(arg1, arg2)
```

- Arguments are positional
- Missing arguments for parameters with defaults use the default value
- Excess arguments are ignored

---

## 8. Classes

### 8.1 Declaration

```ink
class ClassName {
    fn init(params) {
        self.field = value
    }

    fn method(params) -> ReturnType {
        // body — self is available
    }
}
```

- Class bodies contain **only** method declarations (`fn` statements). Other statement types (e.g., `let`, `if`, `while`) inside a class body are not valid.
- `init` is the constructor — called automatically on instantiation
- `self` is an implicit first parameter available in all methods (not declared in the parameter list)
- Fields are created dynamically by assignment to `self.field` — there are no upfront field declarations

### 8.2 Inheritance

```ink
class Dog extends Animal {
    fn init(name: string, breed: string) {
        self.name = name
        self.breed = breed
    }
}
```

- Single inheritance via `extends`
- Methods can be overridden by declaring a method with the same name in the subclass
- No `super` keyword — subclass constructors must initialize all fields themselves
- `is` checks respect the inheritance chain — `Dog()` `is Animal` evaluates to `true`

### 8.3 Instantiation

```ink
let dog = Dog("Rex", "Labrador")
dog.name          // field access
dog.speak()       // method call
```

- Calling a class name as a function creates a new instance and calls `init` if defined
- Field access via `.` notation
- Method calls automatically bind `self` to the instance

### 8.4 Field Existence

```ink
dog has "name"    // true
dog has "age"     // false
```

- Checks own instance fields only (no inheritance walk)
- Right-hand side can be any expression evaluating to a string
- Also works on `Map` instances (checks key existence)

---

## 9. Enums

```ink
enum Direction {
    North, South, East, West
}

let d = Direction.North
print(d is Direction)  // true
```

- Declared with the `enum` keyword
- Variants are comma-separated identifiers
- Accessed via `EnumName.Variant` (dot notation on the enum namespace)
- Type-checkable with `is`
- Simple value enums — no associated data, no methods

---

## 10. Error Handling

### 10.1 Try / Catch / Finally

```ink
try {
    let result = riskyOperation()
} catch e {
    print("Error: ${e}")
} finally {
    cleanup()
}
```

- `try` block: code that may throw
- `catch` block: handles thrown values. The variable (`e`) binds to the thrown value. The catch variable is optional — `catch { }` is valid.
- `finally` block: always executes, whether or not an error occurred. Optional.
- At least one of `catch` or `finally` must be present after `try`

### 10.2 Throw

```ink
throw "something went wrong"
throw 404
throw ErrorInfo("not found", 404)
```

- `throw` is an expression — it can appear anywhere an expression is expected
- **Any value** can be thrown: strings, numbers, class instances, etc.
- `throw` unwinds the call stack until a matching `try/catch` is found
- Uncaught throws are runtime errors reported to the host

---

## 11. Imports

### 11.1 Namespace Import

```ink
import math

math.sin(3.14)
math.PI
```

Imports the entire module as a namespace. Members are accessed via dot notation.

### 11.2 Selective Import

```ink
import sin, cos from math

sin(3.14)
cos(0)
```

Imports specific names from a module directly into the current scope.

### 11.3 Resolution

- Namespace identifiers are bare identifiers (not string paths)
- Module resolution is host-defined — the Quill toolchain resolves via the `packages/` directory structure
- Circular imports are detected and rejected at runtime
- Each module is compiled and executed once; subsequent imports return the cached module

---

## 12. Config and Table Declarations

Config and table are **core syntax** — part of the Ink grammar — but their runtime behavior is provided by the host via the `InkRuntimePackage` interface. The core VM does not implement config/table directly; it dispatches to the registered runtime package.

### 12.1 Config

```ink
config ServerSettings {
    host: string = "localhost"
    port: int = 8080
    debug: bool = false
}

print(ServerSettings.host)
```

- Declares a named configuration block
- Fields have a name, a type annotation, and an optional default value
- The runtime package determines how values are loaded (YAML, environment variables, etc.)
- Config instances are **read-only** — field assignment is a runtime error
- Fields are accessed via dot notation on the config name

### 12.2 Table

```ink
table Players {
    key name: string
    score: int = 0
    level: int = 1
}

Players.set("Alice", {"score": 100, "level": 5})
let player = Players.get("Alice")
Players.delete("Alice")
```

- Declares a persistent data structure with typed fields
- Exactly one field must be marked with the `key` modifier — this serves as the primary identifier
- Non-key fields may have default values
- The runtime package determines the storage backend (SQLite, in-memory, etc.)
- Operations: `get(key)`, `set(key, data)`, `delete(key)`

### 12.3 Runtime Dispatch

When the VM encounters a `config` or `table` declaration, it invokes `InkRuntimePackage.handleDeclaration(keyword, node)` on the registered runtime package. The runtime package is responsible for:

1. Processing the declaration (creating backing storage, loading config files, etc.)
2. Returning a runtime `Value` (typically a `Value.Instance`) that is bound to the declaration name as a global variable

If no runtime package is registered that handles the keyword, the VM raises a runtime error.

---

## 13. Standard Library

### 13.1 Built-in Functions

| Function | Description |
|----------|-------------|
| `print(value)` | Output to user-facing channel (host-defined) |
| `log(value)` | Output to system log (host-defined) |

Output routing is determined by the `InkContext` interface implementation provided by the host.

### 13.2 String Methods

String methods are called on string values via dot notation:

| Method | Signature | Description |
|--------|-----------|-------------|
| `split` | `split(delim: string) -> Array` | Split by delimiter. Empty/null delimiter splits into characters. |
| `trim` | `trim() -> string` | Remove leading/trailing whitespace |
| `contains` | `contains(str: string) -> bool` | Check if substring exists |
| `replace` | `replace(old: string, new: string) -> string` | Replace all literal occurrences (case-sensitive) |
| `replaceAll` | `replaceAll(old: string, new: string) -> string` | Replace all occurrences (case-insensitive) |
| `toUpperCase` | `toUpperCase() -> string` | Convert to uppercase |
| `toLowerCase` | `toLowerCase() -> string` | Convert to lowercase |
| `startsWith` | `startsWith(str: string) -> bool` | Check prefix |
| `endsWith` | `endsWith(str: string) -> bool` | Check suffix |
| `indexOf` | `indexOf(str: string) -> int` | Index of first occurrence, -1 if not found |
| `length` | `length() -> int` | Number of characters |
| `isEmpty` | `isEmpty() -> bool` | True if length is 0 |
| `isBlank` | `isBlank() -> bool` | True if empty or only whitespace |
| `chars` | `chars() -> Array` | Split into array of single-character strings |
| `get` | `get(idx: int) -> string` | Character at index, `null` if out of bounds |

### 13.3 Math Object

Accessed as a global `Math` object:

| Method | Description |
|--------|-------------|
| `Math.abs(n)` | Absolute value |
| `Math.min(a, b)` | Minimum of two numbers |
| `Math.max(a, b)` | Maximum of two numbers |
| `Math.pow(base, exp)` | Exponentiation |
| `Math.floor(n)` | Floor (round down) |
| `Math.ceil(n)` | Ceiling (round up) |
| `Math.round(n)` | Round to nearest integer |

All Math methods accept `int`, `float`, or `double` and return `double`.

### 13.4 Random Object

| Method | Description |
|--------|-------------|
| `Random.random()` | Returns a random `double` between 0.0 (inclusive) and 1.0 (exclusive) |

### 13.5 Iterator Protocol

Any value with the following method contract can be used in `for-in` loops:

```
iterable.iter() -> iterator
iterator.hasNext() -> bool
iterator.next() -> value
```

Built-in iterables: `Range`, `Array`, `Set`, `Tuple`. `Map` is not directly iterable — use `map.keys()` or `map.values()`.

---

## 14. InkRuntimePackage Interface

The `InkRuntimePackage` interface allows JVM code to extend the Ink runtime with custom declaration handlers and statement handlers. This is how `config`, `table`, and future domain-specific keywords are implemented.

```kotlin
interface InkRuntimePackage {
    /** Unique package identifier (e.g., "ink.paper", "ink.data") */
    fun packageName(): String

    /** List of declaration keywords this package handles (e.g., ["config", "table"]) */
    fun handledDeclarations(): List<String>

    /** List of statement keywords this package handles */
    fun handledStatements(): List<String>

    /**
     * Handle a declaration keyword (config, table, etc.)
     * Called when the VM encounters a declaration with a keyword from handledDeclarations().
     * Must return a Value to bind to the declaration name as a global.
     */
    fun handleDeclaration(keyword: String, node: DeclarationNode): Value

    /**
     * Handle a custom statement keyword.
     * Called when the VM encounters a statement with a keyword from handledStatements().
     */
    fun handleStatement(keyword: String, node: StatementNode)
}
```

**DeclarationNode** provides the parsed declaration data to the runtime package:

```kotlin
data class DeclarationNode(
    val name: String,                        // declaration name (e.g., "ServerSettings")
    val fields: List<DeclarationField>,      // declared fields
    val metadata: Map<String, Any> = emptyMap()  // keyword-specific data
)

data class DeclarationField(
    val name: String,           // field name
    val type: String,           // type annotation as string
    val isKey: Boolean,         // true if marked with 'key' modifier (tables)
    val defaultValue: Value?    // evaluated default value, or null
)
```

**StatementNode** provides parsed statement data:

```kotlin
data class StatementNode(
    val keyword: String,
    val arguments: List<Value>,
    val metadata: Map<String, Any> = emptyMap()
)
```

**Registration:** Runtime packages are registered with the VM before script execution. Multiple packages can be registered. If two packages claim the same keyword, the VM raises a configuration error at registration time.

---

## 15. Grammar Summary (EBNF)

**Terminal symbols:** `INTEGER` is a sequence of digits (lexer token `KW_INT`). `DOUBLE` is a digit sequence containing a decimal point (lexer token `KW_DOUBLE`). `STRING` is a double-quoted string (lexer token `KW_STRING`). `IDENT` is an identifier (lexer token `IDENTIFIER`). These names differ from their lexer token names for historical reasons.

**Disambiguation rules:**
- `() ->` is always a lambda (zero params). Bare `()` without `->` is an empty tuple.
- `(params) ->` is a lambda. The parser uses lookahead past the matching `)` to check for `->`.
- `{expr: expr, ...}` is a map. `{expr, expr, ...}` is a set. Disambiguated by presence of `:` after the first expression.
- Class bodies (`classDecl`) accept only `fnDecl` statements. This constraint is enforced semantically, not in the grammar.
- Lambda parameters reuse the `paramList` production but **do not support default values** — this is enforced semantically (parse error if `= expr` appears in a lambda param).

```ebnf
program        = stmt* EOF

stmt           = importStmt
               | varDecl
               | constDecl
               | ifStmt
               | whileStmt
               | forStmt
               | fnDecl
               | classDecl
               | enumDecl
               | configDecl
               | tableDecl
               | tryCatchStmt
               | returnStmt
               | breakStmt
               | nextStmt
               | exprStmt

importStmt     = "import" IDENT ("," IDENT)* "from" IDENT ";"?
               | "import" IDENT ";"?

varDecl        = "let" IDENT ("=" expr)? ";"?
constDecl      = "const" IDENT "=" expr ";"?

ifStmt         = "if" expr block ("else" "if" expr block)* ("else" block)?
whileStmt      = "while" expr block
forStmt        = "for" IDENT "in" expr block

fnDecl         = "fn" IDENT "(" paramList? ")" ("->" type)? block
paramList      = param ("," param)*
param          = IDENT (":" type)? ("=" expr)?

classDecl      = "class" IDENT ("extends" IDENT)? block
enumDecl       = "enum" IDENT "{" IDENT ("," IDENT)* "}"

configDecl     = "config" IDENT "{" configField* "}"
configField    = IDENT ":" type ("=" expr)? ";"?

tableDecl      = "table" IDENT "{" tableField* "}"
tableField     = "key"? IDENT ":" type ("=" expr)? ";"?

tryCatchStmt   = "try" block ( "catch" IDENT? block ("finally" block)?
                             | "finally" block )

returnStmt     = "return" expr? ";"?
breakStmt      = "break" ";"?
nextStmt       = "next" ";"?

exprStmt       = expr ";"?

block          = "{" stmt* "}"

type           = "bool" | "int" | "float" | "double" | "string" | IDENT

expr           = assignment
assignment     = ternary (("=" | "+=" | "-=" | "*=" | "/=" | "%=") assignment)?
ternary        = logicOr ("?" expr ":" expr)?
logicOr        = logicAnd ("or" logicAnd)*
logicAnd       = typeCheck ("and" typeCheck)*
typeCheck      = equality ("is" type)?
equality       = hasCheck (("==" | "!=") hasCheck)*
hasCheck       = comparison ("has" comparison)?
comparison     = range (("<" | ">" | "<=" | ">=") range)*
range          = addition (".." addition)?
addition       = multiplication (("+" | "-") multiplication)*
multiplication = power (("*" | "/" | "%") power)*
power          = unary ("**" unary)*
unary          = ("-" | "!" | "not" | "++" | "--") unary | postfix
postfix        = primary (call | index | field)*
call           = "(" argList? ")"
index          = "[" expr "]"
field          = "." IDENT
argList        = expr ("," expr)*

primary        = INTEGER | DOUBLE | STRING
               | "true" | "false" | "null"
               | IDENT
               | "(" expr ")"                    // grouping
               | "(" ")"                          // empty tuple
               | "(" expr "," exprList? ")"       // tuple
               | "[" exprList "]"                  // array
               | "{" mapEntries "}"                // map
               | "{" exprList "}"                  // set
               | "(" paramList? ")" "->" block     // lambda
               | "throw" expr

exprList       = expr ("," expr)*
mapEntries     = mapEntry ("," mapEntry)*
mapEntry       = expr ":" expr
```

---

## Appendix A: Bytecode Format

Instructions are packed into 32-bit words:

```
| bits 0-7  | bits 8-11  | bits 12-15 | bits 16-19 | bits 20-31 |
| opcode    | dst (4-bit)| src1(4-bit)| src2(4-bit)| immediate  |
```

The VM uses 16 physical registers (R0-R15) per call frame. Virtual registers from compilation are mapped to physical registers via linear scan allocation, with spill/unspill for register pressure exceeding 16.

---

## Appendix B: Implementation Status

| Feature | Parsed | Lowered | VM Support |
|---------|--------|---------|------------|
| Primitives (int, float, double, string, bool, null) | Yes | Yes | Yes |
| let / const | Yes | Yes | Yes |
| Arithmetic / comparison / logical operators | Yes | Yes | Yes |
| Compound assignment (+=, -=, etc.) | Yes | Yes | Yes |
| Prefix operators (-, !, not, ++, --) | Yes | Yes | Yes |
| String interpolation | Yes | Yes | Yes |
| if / else if / else | Yes | Yes | Yes |
| while | Yes | Yes | Yes |
| for-in | Yes | Yes | Yes |
| break / next | Yes | Yes | Yes |
| Functions with defaults | Yes | Yes | Yes |
| Classes with inheritance | Yes | Yes | Yes |
| Array literals and indexing | Yes | Yes | Yes |
| Map literals | Yes | Yes | Yes |
| Set literals | Yes | Yes | Yes |
| Tuple literals | Yes | Yes | Yes |
| Range (a..b) | Yes | Yes | Yes |
| Enums | Yes | Yes | Yes |
| is (type check) | Yes | Yes | Yes |
| has (field check) | Yes | Yes | Yes |
| Ternary (? :) | Yes | Yes | Yes |
| print / log | Yes | Yes | Yes |
| Math / Random | — | — | Yes (native) |
| String methods | — | — | Yes (native) |
| Lambdas / closures | Yes | Designed | No |
| try / catch / finally | Yes | No | No |
| throw | Yes | No | No |
| Imports | Yes | Stub | No |
| Config declarations | Yes | Stub | Stub |
| Table declarations | Yes | Stub | Stub |
