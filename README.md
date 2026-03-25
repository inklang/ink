# inklang

ink is a compiled scripting language targeting a register-based bytecode VM for PaperMC servers.

## Features

- Clean, expressive syntax
- First-class functions and closures
- String interpolation
- Classes and inheritance
- Register-based bytecode VM with SSA optimizations
- Extensible via the [quill](https://github.com/inklang/quill) package manager

## Packages

- [ink.economy](https://github.com/inklang/economy) — single-currency economy system with SQLite-backed accounts and `eco_*` built-in functions

## Building

```bash
./gradlew build
```

## Running

```bash
./gradlew run --args="path/to/script.ink"
```

## Documentation

Full docs at [inklang.github.io/web](https://inklang.github.io/web).
