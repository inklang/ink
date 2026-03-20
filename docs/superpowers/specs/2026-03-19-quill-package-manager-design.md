# Quill Package Manager — Design Specification

> **Project:** Quill (language) + lectern (toolchain, CLI `lt`)
> **Date:** 2026-03-19
> **Status:** Draft

## Renames (Full)

The entire project is being renamed from `lectern` to `quill`:

| Before | After |
|--------|-------|
| `org.lectern` (Kotlin package) | `org.inklang` |
| `lectern-intellij/` | `quill-intellij/` |
| `lectern-vscode/` | `quill-vscode/` |
| `.lec` / `.ain` (source files) | `.quill` |
| `rootProject.name = "lectern"` | `rootProject.name = "quill"` |

All source files at `src/main/kotlin/org/lectern/` → `src/main/kotlin/org/quill/`.
All test files at `src/test/kotlin/org/lectern/` → `src/test/kotlin/org/quill/`.
All `.lec` / `.ain` files renamed to `.quill`.

---

## Overview

`lectern` is the toolchain CLI (with `lt` shorthand) that manages Quill packages. It handles scaffolding, dependency resolution, installation, building, and publishing. The registry is configurable via `quill.toml`.

**Key distinction:**
- **Quill** — the language (`.quill` source files)
- **lectern** — the toolchain (compiler, VM, package manager)
- **`lt`** — shorthand CLI for the package manager (`lt add`, `lt install`, etc.)

---

## Package Manifest (`quill.toml`)

Each Quill package has a `quill.toml` at its root:

```toml
[package]
name = "runtime/paper"
version = "1.2.0"
main = "mod"
description = "Paper platform bindings"

[dependencies]
quill-core = "^1.0.0"
ui-console = "2.1.0"

[dev-dependencies]
quill-test = "^0.1.0"
```

**Fields:**
- `name` — hierarchical namespace (`scope/package` style, e.g. `runtime/paper`)
- `version` — semver string
- `main` — entry point filename without `.quill` extension (e.g. `mod` → `mod.quill`)
- `dependencies` — packages required at runtime (and compile-time)
- `dev-dependencies` — packages only needed during development (testing, build)

**Note:** All dependencies currently serve as both runtime and compile-time. The `dev-dependencies` distinction exists in the schema but is not yet enforced by the compiler.

**Semver ranges:**
- `^1.0.0` — compatible (>= 1.0.0, < 2.0.0)
- `~1.0.0` — patch-compatible (>= 1.0.0, < 1.1.0)
- `>=1.0.0` — greater than or equal
- `1.2.0` — exact version
- Pre-release versions (`1.0.0-alpha`) are excluded unless explicitly requested

---

## Registry Index

Registry URL is configurable in `quill.toml` or via `QUILL_REGISTRY` environment variable. Default: `https://packages.quill-lang.org`

**Index format** (`/index.json`):

```json
{
  "packages": {
    "runtime/paper": {
      "1.2.0": {
        "url": "https://packages.quill-lang.org/runtime/paper-1.2.0.tar.gz",
        "dependencies": { "quill-core": "^1.0.0" }
      },
      "1.1.0": {
        "url": "https://packages.quill-lang.org/runtime/paper-1.1.0.tar.gz",
        "dependencies": {}
      }
    }
  }
}
```

**Tarball URL construction:**
```
{registry}/{name}/{name}-{version}.tar.gz
```
where `name` has `/` replaced with `-` (e.g., `runtime/paper` → `runtime-paper`).

---

## Lockfile (`quill.lock`)

Generated automatically by `lt install`:

```json
{
  "version": 1,
  "registry": "https://packages.quill-lang.org",
  "packages": {
    "runtime/paper@1.2.0": {
      "url": "https://packages.quill-lang.org/runtime/paper-1.2.0.tar.gz",
      "resolved": "runtime/paper@1.2.0",
      "dependencies": {
        "quill-core@1.5.0": {}
      }
    },
    "quill-core@1.5.0": {
      "url": "https://packages.quill-lang.org/quill-core-1.5.0.tar.gz",
      "resolved": "quill-core@1.5.0",
      "dependencies": {}
    }
  }
}
```

- `lt install` (no args) reads `quill.toml`, resolves semver, writes `quill.lock`
- `lt add <pkg>` also updates `quill.lock`
- `lt update` re-resolves all dependencies and updates `quill.lock`
- `quill.lock` is committed to version control

**Atomicity:** `lt add` and `lt install` update `quill.lock` only after all downloads and extractions succeed. If any step fails, `quill.lock` is not modified.

---

## Local Package Structure

```
my-project/
├── quill.toml       ← this package's manifest
├── quill.lock       ← resolved dependencies (committed)
├── main.quill       ← entry point
├── packages/        ← installed packages
│   ├── runtime-paper/
│   │   ├── quill.toml
│   │   └── mod.quill
│   └── quill-core/
│       ├── quill.toml
│       └── mod.quill
└── .quill-cache/    ← compiled bytecode (gitignored)
```

---

## Import Resolution Algorithm

**Rules:**

1. For `import X` (no slash):
   - First: `packages/X/quill.toml` → load `main` file from that package
   - Second: `packages/X.quill` — single-file package
   - Fail if neither exists

2. For `import X/Y` (one slash):
   - First: `packages/X/Y.quill` — submodule as single file
   - Second: `packages/X/Y/quill.toml` → load `main` file from that package
   - Fail if neither exists

3. For `import X/Y/Z` (multiple slashes):
   - Try `packages/X/Y/Z.quill`
   - Try `packages/X/Y/Z/quill.toml` → load `main`
   - **If both fail:** backtrack and try `packages/X/Y.quill`
   - Try `packages/X/Y/quill.toml` → load `main`
   - **If both fail:** try `packages/X.quill`
   - Try `packages/X/quill.toml` → load `main`
   - Fail if no match found

4. Local-first: If a local `packages/X/` exists, it always wins over any remote package of the same name.

**Conflict rule:** If two installed packages both have a dependency on the same package (e.g., `pkg-a` and `pkg-b` both depend on `quill-core` but at compatible versions), a single copy is installed and shared. If they require **incompatible** versions (e.g., `^1.0.0` vs `^2.0.0`), `lt install` fails with an error listing both requirements.

**Self-dependency:** A package listing itself as a dependency is an error — `lt install` fails with "package cannot depend on itself".

**Circular dependencies:** Detected and rejected. If `pkg-a → pkg-b → pkg-a` is detected, fail with a cycle error.

---

## CLI Commands (`lt`)

```
lt new <name>          Scaffold new package (quill.toml + main.quill)
lt init                  Initialize quill.toml in existing project directory
lt add <pkg>[@ver]     Install package, update quill.toml and quill.lock
lt remove <pkg>        Uninstall package, update quill.toml and quill.lock
lt install              Resolve deps from quill.toml, write quill.lock, populate packages/
lt update [pkg]        Re-resolve dependencies, update quill.lock (all or single pkg)
lt ls                   List installed packages and their versions
lt build                Compile all .quill → .quill-cache/
lt run [file]          Run main.quill or specified file (from .quill-cache/ or compile first)
lt publish             Publish to registry (auth deferred)
lt clean                Remove .quill-cache/
```

**Build behavior:**
- `lt build` compiles `.quill` → bytecode in `.quill-cache/`
- Import resolution happens at compile time via `packages/`
- If `packages/` changes, `.quill-cache/` for that package is invalidated
- `lt build` always recompiles (no incremental build in v1)

**Run behavior:**
- `lt run` uses `.quill-cache/` if it exists, otherwise compiles then runs
- `lt run main.quill` compiles and runs a specific file

**Offline mode:** `lt install --offline` uses only packages already in `packages/`. Fails if a needed package is not present.

---

## Semver Resolution Algorithm

1. Fetch registry index from configured registry URL
2. For each direct dependency in `quill.toml`, find all available versions
3. Filter to versions satisfying the semver range
4. Select newest compatible version per package
5. Recurse on transitive dependencies
6. Flatten into a flat map of `name@version`
7. Detect version conflicts (same package, incompatible major versions)
8. Detect circular dependencies
9. Write `quill.lock`

**Pre-release handling:** Ranges like `^1.0.0` do NOT match pre-release versions (`1.0.0-alpha`) unless the pre-release is explicitly listed or `>=` is used with a pre-release lower bound.

---

## File Structure

```
quill/                              ← project root (was lectern/)
├── src/main/kotlin/org/quill/      ← compiler, VM, stdlib (was org/lectern/)
├── src/test/kotlin/org/quill/     ← tests (was org/lectern/)
├── quill-intellij/                 ← IntelliJ plugin (was lectern-intellij/)
├── quill-vscode/                   ← VS Code extension (was lectern-vscode/)
├── lt/                             ← NEW: package manager as subproject
│   ├── src/main/kotlin/org/quill/lt/
│   └── build.gradle.kts
├── docs/                           ← Docusaurus site
├── ARCHITECTURE.md
└── quill.toml                      ← quill project itself is a package (self-hosting)
```

---

## Implementation Phases

### Phase 1: Full Rename + Project Scaffolding
- Rename `org.lectern` → `org.inklang` in all Kotlin sources
- Rename `lectern-intellij/` → `quill-intellij/`
- Rename `lectern-vscode/` → `quill-vscode/`
- Rename all `.lec` / `.ain` files → `.quill`
- Update `rootProject.name = "quill"` in `settings.gradle.kts`
- Add `lt/` as a Gradle subproject with basic stub
- Create self-hosting `quill.toml` at project root (name=`quill`, version=`0.1.0`, main=`main`)
- Verify all tests pass

### Phase 2: Package Manager Core (`lt`)
- `lt new` — scaffold package (quill.toml + main.quill)
- `lt init` — create quill.toml in existing project
- `lt add` — fetch and extract package tarballs to `packages/`
- `lt remove` — remove from `packages/`, update manifests
- `lt install` — read lockfile, populate `packages/`
- `lt ls` — list installed packages
- `lt clean` — remove .quill-cache/
- Configurable registry URL (env var + quill.toml)
- Basic mock registry for testing

### Phase 3: Dependency Resolution
- Semver range matching
- `quill.lock` generation with full transitive dependency graph
- Conflict detection (incompatible major versions)
- Circular dependency detection
- Self-dependency detection
- `lt update` (all or single package)

### Phase 4: Build & Run Integration
- `lt build` — compile with import resolution via `packages/`
- `lt run` — execute compiled output
- Compiler wired to resolve imports via `packages/`
- `.quill-cache/` management

### Phase 5: Publishing
- `lt publish` — create tarball, push to registry
- Auth mechanism (deferred — configurable registry initially)

---

## Open Questions / Deferred

1. **Auth for publishing** — API key, OAuth, or other?
2. **Registry hosting** — who runs `packages.quill-lang.org`?
3. **Private packages** — support for private registries?
4. **Package search** — `lt search <term>` command?
5. **Incremental build** — rebuild only changed packages in `.quill-cache/`?
6. **Dev dependencies enforcement** — compiler to distinguish dev vs runtime deps?
7. **Lockfile migration** — how to handle schema upgrades?
