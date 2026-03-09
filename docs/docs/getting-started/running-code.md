---
sidebar_position: 3
---

# Running Ain Code

Learn different ways to run your Ain programs.

## Using Gradle

The standard way to run Ain programs:

```bash
./gradlew run --args="filename.ain"
```

## Running Multiple Files

You can pass multiple files:

```bash
./gradlew run --args="main.ain utils.ain"
```

## Common Run Options

### Debug Mode

For debugging output:

```bash
./gradlew run --args="--debug filename.ain"
```

### Help

View available options:

```bash
./gradlew run --args="--help"
```

## REPL (Coming Soon)

Ain will soon support an interactive REPL:

```bash
./gradlew run --args="--repl"
```

## Next Steps

Now that you can run code, learn about [Variables](/docs/basics/variables) and [Data Types](/docs/basics/data-types).
