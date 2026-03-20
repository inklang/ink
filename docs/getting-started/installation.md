---
sidebar_position: 1
---

# Installation

Learn how to install and set up quill on your system.

## Prerequisites

- **Java Runtime Environment (JRE) 11+** - quill runs on the JVM
- **Gradle** (optional) - For building from source

## Building from Source

### 1. Clone the Repository

```bash
git clone https://github.com/your-github-username/quill.git
cd quill
```

### 2. Build with Gradle

```bash
./gradlew build
```

### 3. Run quill

```bash
./gradlew run --args="your-file.quill"
```

## Development Setup

For development, you can use:

- **IntelliJ IDEA** - Recommended for Kotlin development
- **VS Code** - With Kotlin extensions

## Verifying Installation

Create a simple test file:

```lec title="hello.quill"
print("Hello, quill!")
```

Run it:

```bash
./gradlew run --args="hello.quill"
```

You should see:

```
Hello, quill!
```

## Next Steps

Now that you have quill installed, let's write your [First Program](/docs/getting-started/first-program)!
