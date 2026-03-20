---
sidebar_position: 2
---

# Data Types

Explore the data types available in quill.

## Primitive Types

### Numbers

quill uses a single number type for integers and decimals:

```quill
let integer = 42
let decimal = 3.14159
let negative = -100

// Arithmetic
print(10 + 5)   // 15
print(10 - 5)   // 5
print(10 * 5)   // 50
print(10 / 5)   // 2
print(10 % 3)   // 1 (modulo)
```

### Strings

Text values enclosed in double quotes:

```quill
let greeting = "Hello, World!"
let name = "Quill"

// String concatenation
let message = greeting + " Welcome to " + name
print(message)  // Hello, World! Welcome to Quill

// String interpolation
let interpolated = "Hello ${name}!"
print(interpolated)  // Hello Quill!

// String length
let len = len(greeting)
```

### Booleans

True or false values:

```quill
let isActive = true
let isComplete = false

// Boolean operations
print(!isActive)       // false
print(isActive && isComplete)  // false
print(isActive || isComplete)  // true
```

### Null

Represents the absence of a value:

```quill
let empty = null
print(empty)  // null
```

## Composite Types

### Arrays

Ordered collections of values:

```quill
let numbers = [1, 2, 3, 4, 5]
let mixed = [1, "two", true]

// Access elements
print(numbers[0])  // 1

// Modify elements
numbers[0] = 10
```

### Maps (Dictionaries)

Key-value pairs:

```quill
let user = {
    "name": "John",
    "age": 30,
    "active": true
}

// Access values
print(user["name"])  // John
```

## Type Checking

Check the type of a value:

```quill
print(type(42))       // "int"
print(type("hello"))   // "string"
print(type(true))      // "bool"
print(type(null))      // "null"
```

## Type Checking with `is`

Check if a value is a specific type:

```quill
let x = "hello"
if x is string {
    print("x is a string")
}

let num = 42
if num is int {
    print("num is an integer")
}
```

## Range Expressions

Create ranges for iteration:

```quill
let r = 0..5          // 0, 1, 2, 3, 4 (excludes end)
for i in r {
    print(i)
}
```

## Next Steps

Learn about [Operators](/docs/basics/operators) to work with these data types.
