# Ink Language Spec Finalization Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finalize the Ink language spec as the canonical reference, save project memory, and create the InkRuntimePackage Kotlin interface.

**Architecture:** The spec is already written and reviewed at `docs/superpowers/specs/2026-03-22-ink-language-spec.md`. This plan copies it to a permanent docs location, updates Claude memory with key project context, and creates a single Kotlin interface file extracted from spec section 14.

**Tech Stack:** Markdown, Kotlin (interface only)

---

## Chunk 1: Finalize Spec and Memory

### Task 1: Copy spec to permanent docs location

**Files:**
- Create: `docs/docs/reference/language-spec.md`

- [ ] **Step 1: Create the reference directory and copy the spec**

Copy `docs/superpowers/specs/2026-03-22-ink-language-spec.md` to `docs/docs/reference/language-spec.md`. Add a Docusaurus frontmatter header at the top:

```markdown
---
sidebar_position: 1
title: Language Specification
---
```

The rest of the file is identical to the spec.

- [ ] **Step 2: Commit**

```bash
git add docs/docs/reference/language-spec.md
git commit -m "docs: add Ink language spec to reference docs"
```

### Task 2: Update Claude memory

**Files:**
- Create: `C:\Users\justi\.claude\projects\C--Users-justi-chev\memory\MEMORY.md`

- [ ] **Step 1: Write MEMORY.md with key project context**

```markdown
# Ink Language Project

## Naming
- **Language:** Ink (file extension `.ink`)
- **Toolchain/package manager:** Quill (`quill` CLI)
- **Domain namespace:** `org.inklang`
- **History:** lectern -> quill -> ink (codebase still has quill references in docs/specs)

## Project Structure
- `lang/` — Kotlin source for compiler + VM
  - `src/main/kotlin/org/inklang/lang/` — Token, Lexer, Parser, AST, IR, OpCode, Value, Chunk
  - `src/main/kotlin/org/inklang/ast/` — AstLowerer, IrCompiler, VM, RegisterAllocator
  - `src/main/kotlin/org/inklang/ssa/` — SSA infrastructure
  - `src/main/kotlin/org/inklang/opt/` — Optimization passes
- `docs/` — Docusaurus documentation site
- `quill/` — Node.js project (possibly toolchain frontend)
- `.claude/worktrees/error-handling/` — Active worktree with latest code

## Key Architecture
- Register-based bytecode VM (16 physical registers, 32-bit packed instructions)
- Pipeline: Lexer -> Parser -> AST -> ConstantFolder -> AstLowerer -> IR -> RegisterAllocator -> IrCompiler -> Chunk -> VM
- SSA infrastructure built but not wired as primary pipeline
- Config/table are core syntax with pluggable runtime backends (InkRuntimePackage)

## Language Spec
- Canonical spec: `docs/superpowers/specs/2026-03-22-ink-language-spec.md`
- Reference copy: `docs/docs/reference/language-spec.md`

## Implementation Status (key gaps)
- Lambdas/closures: parsed, designed, not lowered
- try/catch/finally + throw: parsed, not lowered
- Imports: parsed, stub-lowered only
- Config/table: parsed, stub runtime (YAML/SQLite)

## Design Decisions
- Error handling: throw any value (not just Error instances)
- Imports: bare identifier namespaces resolved by host
- Config/table: core syntax, pluggable runtime via InkRuntimePackage
- `has` operator: checks own instance fields only, no inheritance walk
- Type annotations: informational only, no compile-time enforcement
```

- [ ] **Step 2: Verify memory file exists**

Read back `C:\Users\justi\.claude\projects\C--Users-justi-chev\memory\MEMORY.md` to confirm it was saved correctly.

### Task 3: Create InkRuntimePackage Kotlin interface

**Files:**
- Create: `lang/src/main/kotlin/org/inklang/runtime/InkRuntimePackage.kt`

- [ ] **Step 1: Create the runtime directory and interface file**

Create `lang/src/main/kotlin/org/inklang/runtime/InkRuntimePackage.kt` with the interface from spec section 14:

```kotlin
package org.inklang.runtime

import org.inklang.lang.Value

/**
 * Interface for extending the Ink runtime with custom declaration and statement handlers.
 * This is how config, table, and future domain-specific keywords are implemented.
 *
 * Runtime packages are registered with the VM before script execution.
 * Multiple packages can be registered. If two packages claim the same keyword,
 * the VM raises a configuration error at registration time.
 */
interface InkRuntimePackage {
    /** Unique package identifier (e.g., "ink.paper", "ink.data") */
    fun packageName(): String

    /** List of declaration keywords this package handles (e.g., ["config", "table"]) */
    fun handledDeclarations(): List<String>

    /** List of statement keywords this package handles */
    fun handledStatements(): List<String>

    /**
     * Handle a declaration keyword (config, table, etc.)
     * Called when the VM encounters a declaration with a keyword from handledDeclarations().
     * Must return a Value to bind to the declaration name as a global.
     */
    fun handleDeclaration(keyword: String, node: DeclarationNode): Value

    /**
     * Handle a custom statement keyword.
     * Called when the VM encounters a statement with a keyword from handledStatements().
     */
    fun handleStatement(keyword: String, node: StatementNode)
}

/**
 * Parsed declaration data provided to runtime packages.
 */
data class DeclarationNode(
    val name: String,
    val fields: List<DeclarationField>,
    val metadata: Map<String, Any> = emptyMap()
)

data class DeclarationField(
    val name: String,
    val type: String,
    val isKey: Boolean,
    val defaultValue: Value?
)

/**
 * Parsed statement data provided to runtime packages.
 */
data class StatementNode(
    val keyword: String,
    val arguments: List<Value>,
    val metadata: Map<String, Any> = emptyMap()
)
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (the interface has no dependencies beyond Value.kt which already exists)

- [ ] **Step 3: Commit**

```bash
git add lang/src/main/kotlin/org/inklang/runtime/InkRuntimePackage.kt
git commit -m "feat: add InkRuntimePackage interface from language spec section 14"
```
