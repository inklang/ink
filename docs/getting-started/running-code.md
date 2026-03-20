---
sidebar_position: 3
---

# Running ink Code

Learn different ways to run your ink programs.

## Using Gradle

The standard way to run ink programs:

```bash
./gradlew run --args="filename.ink"
```

## Running Multiple Files

You can pass multiple files (they execute in order):

```bash
./gradlew run --args="main.ink utils.ink"
```

## File Naming

ink source files use the `.ink` extension:

```
hello.ink
myprogram.ink
game.ink
```

## Common Issues

### "Could not find or load main class"

Run `./gradlew build` first to compile the project.

### "File not found"

Make sure you're in the project root directory and the file path is correct.

## Building a JAR

To build a deployable JAR for Paper servers:

```bash
./gradlew shadowJar
```

The output will be in `build/libs/inklang-paper-1.0-SNAPSHOT.jar`.

## Next Steps

Now that you can run code, learn about [Variables](/docs/basics/variables) and [Data Types](/docs/basics/data-types).
