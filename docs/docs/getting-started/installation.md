---
sidebar_position: 1
---

# Installation

Learn how to install and set up Ain on your system.

## Prerequisites

- **Java Runtime Environment (JRE) 11+** - Ain runs on the JVM
- **Gradle** (optional) - For building from source

## Building from Source

### 1. Clone the Repository

```bash
git clone https://github.com/your-github-username/ainscript.git
cd ainscript
```

### 2. Build with Gradle

```bash
./gradlew build
```

### 3. Run Ain

```bash
./gradlew run --args="your-file.ain"
```

## Development Setup

For development, you can use:

- **IntelliJ IDEA** - Recommended for Kotlin development
- **VS Code** - With Kotlin extensions

## Verifying Installation

Create a simple test file:

```ain title="hello.ain"
print("Hello, Ain!")
```

Run it:

```bash
./gradlew run --args="hello.ain"
```

You should see:

```
Hello, Ain!
```

## Next Steps

Now that you have Ain installed, let's write your [First Program](/docs/getting-started/first-program)!
