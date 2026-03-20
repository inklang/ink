---
sidebar_position: 1
slug: /
---

# Welcome to ink

ink is a modern, expressive scripting language built for embedding in Minecraft servers. It combines clean syntax with powerful features—classes, functions, string interpolation, and more—running on a fast register-based bytecode VM.

## Why ink?

- **Clean syntax** — Expressive code that's easy to read and write
- **First-class functions** — Functions as values, with closures and higher-order functions
- **Object-oriented** — Classes with inheritance and methods
- **Fast VM** — Register-based bytecode with SSA optimizations
- **Built for Minecraft** — Designed to script PaperMC servers

## Quick Example

```ink
// Define a function with string interpolation
fn greet(name) {
    return "Hello, ${name}!"
}

print(greet("Player"))  // Hello, Player!

// Classes with inheritance
class Animal {
    init(name) {
        this.name = name
    }

    speak() {
        return "${this.name} makes a sound"
    }
}

class Dog extends Animal {
    speak() {
        return "${this.name} barks!"
    }
}

let dog = Dog("Buddy")
print(dog.speak())  // Buddy barks!
```

## Getting Started

Ready to write your first ink program? Head over to the [Installation Guide](/docs/getting-started/installation) to set up your environment.

## Resources

- [GitHub Repository](https://github.com/inklang/ink) — Source code and issue tracker
- [Inklang Discord](https://discord.gg/inklang) — Community chat
