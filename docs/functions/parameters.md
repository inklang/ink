---
sidebar_position: 2
---

# Default Parameters

Functions can have default parameter values—values used when the caller doesn't provide one.

## Basic Default Parameters

```ink
fn greet(name, greeting = "Hello") {
    return "${greeting}, ${name}!"
}

print(greet("Alice"))           // Hello, Alice!
print(greet("Bob", "Hi"))       // Hi, Bob!
```

## Multiple Default Parameters

```ink
fn configure(host, port = 8080, timeout = 30) {
    return "Connecting to ${host}:${port} (timeout: ${timeout}s)"
}

print(configure("localhost"))
// Connecting to localhost:8080 (timeout: 30s)

print(configure("localhost", 3000))
// Connecting to localhost:3000 (timeout: 30s)

print(configure("localhost", 3000, 60))
// Connecting to localhost:3000 (timeout: 60s)
```

## Default Parameters with Expressions

Default values can be expressions:

```ink
fn createUser(name, level = 1, multiplier = level * 10) {
    return "User ${name} at level ${level} (max HP: ${multiplier})"
}

print(createUser("Alice"))
// User Alice at level 1 (max HP: 10)

print(createUser("Bob", 5))
// User Bob at level 5 (max HP: 50)
```

## When to Use Default Parameters

- Making parameters optional for convenience
- Providing sensible defaults
- Allowing flexible function calls

## Next Steps

Learn about [Classes](/docs/classes/defining) to create objects that bundle data and functions together.
