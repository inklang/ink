---
sidebar_position: 3
---

# Operators

Learn the operators available in quill.

## Arithmetic Operators

```quill
let a = 10
let b = 3

print(a + b)   // 13 (addition)
print(a - b)   // 7  (subtraction)
print(a * b)   // 30 (multiplication)
print(a / b)   // 3.33... (division)
print(a % b)   // 1  (modulo/remquillder)
```

## Comparison Operators

```quill
let x = 5
let y = 10

print(x == y)  // false (equal)
print(x != y)  // true  (not equal)
print(x < y)   // true  (less than)
print(x > y)   // false (greater than)
print(x <= y)  // true  (less or equal)
print(x >= y)  // false (greater or equal)
```

## Logical Operators

```quill
let a = true
let b = false

print(a && b)  // false (and)
print(a || b)  // true  (or)
print(!a)      // false (not)
```

## String Operators

```quill
let first = "Hello"
let last = "World"

// Concatenation
let full = first + " " + last
print(full)  // "Hello World"
```

## Assignment Operators

```quill
let count = 0

count = 5      // Simple assignment
count = count + 1  // Increment
count = count - 1  // Decrement
```

## Operator Precedence

Operators are evaluated in this order (highest to lowest):

1. `!` (not)
2. `*`, `/`, `%`
3. `+`, `-`
4. `<`, `>`, `<=`, `>=`
5. `==`, `!=`
6. `&&`
7. `||`

Use parentheses to control order:

```quill
let result = (2 + 3) * 4  // 20
let other = 2 + 3 * 4     // 14
```

## Next Steps

Learn about [Control Flow](/docs/basics/control-flow) to make decisions in your code.
