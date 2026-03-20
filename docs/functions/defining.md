---
sidebar_position: 1
---

# Defining Functions

Functions group code into reusable blocks. They help you organize your program and avoid repeating yourself.

## Basic Functions

Use `fn` to define a function:

```ink
fn greet() {
    print("Hello!")
}

greet()  // Calls the function - prints "Hello!"
```

## Functions with Parameters

Pass data into functions using parameters:

```ink
fn greet(name) {
    print("Hello, ${name}!")
}

greet("Alice")  // Hello, Alice!
greet("Bob")    // Hello, Bob!
```

## Multiple Parameters

Functions can take multiple parameters:

```ink
fn add(a, b) {
    return a + b
}

let sum = add(5, 3)
print(sum)  // 8
```

## Return Values

Use `return` to send a value back to the caller:

```ink
fn square(x) {
    return x * x
}

let result = square(7)
print(result)  // 49
```

## Functions Without Return

If a function doesn't return a value, it returns `null` by default:

```ink
fn sayHello(name) {
    print("Hello, ${name}!")
}

let result = sayHello("World")
print(result)  // null
```

## Functions Are Values

In ink, functions are first-class values—they can be stored in variables and passed around:

```ink
fn double(x) {
    return x * 2
}

let myFunc = double
print(myFunc(5))  // 10
```

## Next Steps

Learn about [Default Parameters](/docs/functions/parameters) to make functions more flexible.
