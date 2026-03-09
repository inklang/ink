---
sidebar_position: 2
---

# Data Types

Explore the data types available in Ain.

## Primitive Types

### Numbers

Ain uses a single number type for integers and decimals:

```ain
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

```ain
let greeting = "Hello, World!"
let name = "Ain"

// String concatenation
let message = greeting + " Welcome to " + name
print(message)  // Hello, World! Welcome to Ain

// String length (if supported)
let len = len(greeting)
```

### Booleans

True or false values:

```ain
let isActive = true
let isComplete = false

// Boolean operations
print(!isActive)       // false
print(isActive && isComplete)  // false
print(isActive || isComplete)  // true
```

### Null

Represents the absence of a value:

```ain
let empty = null
print(empty)  // null
```

## Composite Types

### Arrays

Ordered collections of values:

```ain
let numbers = [1, 2, 3, 4, 5]
let mixed = [1, "two", true]

// Access elements
print(numbers[0])  // 1

// Modify elements
numbers[0] = 10
```

### Maps (Dictionaries)

Key-value pairs:

```ain
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

```ain
print(type(42))       // "number"
print(type("hello"))  // "string"
print(type(true))     // "boolean"
print(type(null))     // "null"
```

## Next Steps

Learn about [Operators](/docs/basics/operators) to work with these data types.
