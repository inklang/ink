---
sidebar_position: 1
---

# Installation

This guide walks you through setting up ink on your machine.

## Prerequisites

- **Java Development Kit (JDK) 21** — ink runs on the JVM
- **Git** — For cloning the repository

## Clone the Repository

```bash
git clone https://github.com/inklang/ink.git
cd ink
```

## Build with Gradle

```bash
./gradlew build
```

This compiles the language and runs the test suite. You should see all tests pass.

## Run Your First Program

Create a file called `hello.ink`:

```ink title="hello.ink"
print("Hello, ink!")
```

Run it:

```bash
./gradlew run --args="hello.ink"
```

You should see:

```
Hello, ink!
```

## Development Setup

### IntelliJ IDEA

1. Open the project folder in IntelliJ
2. Select "Import Gradle project"
3. The Kotlin plugin is included via Gradle toolchains

### VS Code

Install the ink extension when available, or use the Kotlin language extension for basic syntax highlighting.

## Next Steps

Now that ink is installed, let's write [Your First Program](/docs/getting-started/first-program)!
