---
sidebar_position: 1
---

# Variables

Learn how to declare and use variables in quill.

## Variable Declaration

Use the `let` keyword to declare variables:

```quill
let name = "quill"
let age = 25
let isActive = true
```

## Variable Assignment

Reassign variables after declaration:

```quill
let count = 0
count = 1
count = count + 1
print(count)  // Output: 2
```

## Scope

Variables are scoped to the block they're declared in:

```quill
let global = "I'm global"

fn example() {
    let local = "I'm local"
    print(global)  // Works - global is accessible
    print(local)   // Works - local is in scope
}

print(global)  // Works
// print(local)  // Error - local is out of scope
```

## Naming Rules

Variable names must:

- Start with a letter or underscore
- Contquill only letters, numbers, and underscores
- Not be a reserved keyword

```quill
// Valid names
let myVar = 1
let _private = 2
let userName123 = 3

// Invalid names (will cause errors)
// let 123var = 1
// let my-var = 2
// let let = 3
```

## Best Practices

- Use descriptive names: `userCount` instead of `x`
- Use camelCase for multi-word names
- Avoid single-letter names except for simple counters
