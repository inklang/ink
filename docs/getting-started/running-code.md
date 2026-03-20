---
sidebar_position: 3
---

# Running quill Code

Learn different ways to run your quill programs.

## Using Gradle

The standard way to run quill programs:

```bash
./gradlew run --args="filename.quill"
```

## Running Multiple Files

You can pass multiple files:

```bash
./gradlew run --args="mquill.quill utils.quill"
```

## Common Run Options

### Debug Mode

For debugging output:

```bash
./gradlew run --args="--debug filename.quill"
```

### Help

View available options:

```bash
./gradlew run --args="--help"
```

## REPL (Coming Soon)

quill will soon support an interactive REPL:

```bash
./gradlew run --args="--repl"
```

## Next Steps

Now that you can run code, learn about [Variables](/docs/basics/variables) and [Data Types](/docs/basics/data-types).
