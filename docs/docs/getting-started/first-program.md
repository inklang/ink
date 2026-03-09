---
sidebar_position: 2
---

# Your First Program

Let's write and understand your first Ain program.

## Hello World

Create a file named `hello.ain`:

```ain title="hello.ain"
print("Hello, World!")
```

Run it:

```bash
./gradlew run --args="hello.ain"
```

Output:

```
Hello, World!
```

## Program Structure

Let's look at a slightly more complex program:

```ain
// This is a comment
let name = "Ain"
let version = 1.0

// Define a function
fn greet(person) {
    return "Hello, " + person + "!"
}

// Call the function and print result
print(greet(name))
```

### Breaking it Down

1. **Comments** - Use `//` for single-line comments
2. **Variables** - Use `let` to declare variables
3. **Functions** - Use `fn` to define functions
4. **Print** - Use `print()` to output to console

## Variables and Types

Ain supports several data types:

```ain
let integer = 42          // Number
let float = 3.14          // Number (decimal)
let string = "Hello"      // String
let boolean = true        // Boolean
let array = [1, 2, 3]     // Array
let nothing = null        // Null
```

## Next Steps

Learn how to [Run Code](/docs/getting-started/running-code) more efficiently, or dive into [Variables](/docs/basics/variables).
