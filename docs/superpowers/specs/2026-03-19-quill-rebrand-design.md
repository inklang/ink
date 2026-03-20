# Rebrand: Lectern → Quill

**Date:** 2026-03-19
**Status:** Approved
**Branch:** `feat/language-fixes` (in-progress)

---

## Overview

Rename the Lectern programming language to Quill. The change is purely cosmetic — no architectural or behavioral changes. A quill is a writing instrument, fitting for a programming language targeting a register-based bytecode VM.

---

## Scope

**1,107 occurrences across 103 files.**

### Package Names
- `org.lectern` → `org.inklang`
- All Kotlin source and test files in `src/main/kotlin/org/lectern/` → `src/main/kotlin/org/quill/`
- All Kotlin test files in `src/test/kotlin/org/lectern/` → `src/test/kotlin/org/quill/`

### Project Name
- Root project name: `lectern` → `quill` (in `settings.gradle.kts` and `build.gradle.kts`)
- Group ID: `org.lectern` → `org.inklang`

### File Extension
- `.lec` → `.quill`
- All test fixture files renamed accordingly

### IDE Plugin Directories
- `lectern-intellij/` → `quill-intellij/`
- `lectern-vscode/` → `quill-vscode/`
- All internal references to "Lectern" within plugins updated

### All References
- `lectern` → `quill` (lowercase)
- `Lectern` → `Quill` (PascalCase)
- `LECTERN` → `QUILL` (uppercase, if any)

---

## Implementation Steps

### Phase 1: Rename directory structure
1. Move `src/main/kotlin/org/lectern/` → `src/main/kotlin/org/quill/`
2. Move `src/test/kotlin/org/lectern/` → `src/test/kotlin/org/quill/`
3. Move `lectern-intellij/` → `quill-intellij/`
4. Move `lectern-vscode/` → `quill-vscode/`

### Phase 2: Update Gradle configuration
1. Update `settings.gradle.kts` — root project name and include statements
2. Update `build.gradle.kts` — group ID and project name references
3. Update `lectern-intellij/build.gradle.kts`
4. Update `lectern-intellij/settings.gradle.kts` → `quill-intellij/settings.gradle.kts`

### Phase 3: Global replacement in all files
1. Replace `org.lectern` → `org.inklang` in all Kotlin files
2. Replace `lectern` → `quill` in all remaining files (docs, configs, etc.)
3. Replace `Lectern` → `Quill` in documentation and comments
4. Replace `LECTERN` → `QUILL` in any constant names

### Phase 4: Rename file extension
1. Find all `*.lec` files → rename to `*.quill`
2. Update any references to `.lec` in documentation

### Phase 5: Update documentation
1. Update `ARCHITECTURE.md` references
2. Update all docs under `docs/docs/`
3. Update `docs/superpowers/plans/` files referencing lectern
4. Update `README.md` files in plugin directories

---

## Risks & Notes

### IntelliJ/VS Code Marketplace
Plugin IDs, UUIDs, and marketplace registrations are tied to the old name. After the rebrand:
- IntelliJ plugin will need a new plugin ID and re-upload to JetBrains Marketplace
- VS Code extension will need a new extension ID and re-publish to VS Code Marketplace

### Git History
Due to the package path change (`org/lectern/` → `org/quill/`), git will show all files as deleted/new rather than renamed. History for individual files will be lost in the rename. If history preservation is critical, a separate strategy (e.g., git-filter-repo) could be used.

### Test Files
All `.lec` test fixture files in the root directory will be renamed to `.quill`. Any hardcoded expectations of `.lec` in tests will be updated.

---

## Files to Modify

### Kotlin Source (38 files)
```
src/main/kotlin/org/lectern/ → src/main/kotlin/org/quill/
```
Including: `Main.kt`, `Lexer.kt`, `Parser.kt`, `AstLowerer.kt`, `IrCompiler.kt`, `VM.kt`, `OpCode.kt`, `AST.kt`, `IR.kt`, `Token.kt`, `Value.kt`, `Chunk.kt`, `Register.kt`, `ConstantFolder.kt`, `LivenessAnalyzer.kt`, `RegisterAllocator.kt`, `SpillInserter.kt`, `BasicBlock.kt`, `ControlFlowGraph.kt`, `TableRuntime.kt`, `ConfigRuntime.kt`, `Script.kt`, all SSA and optimization files

### Kotlin Tests (18 files)
```
src/test/kotlin/org/lectern/ → src/test/kotlin/org/quill/
```
Including: `VMTest.kt`, `LexerTest.kt`, `ParserTest.kt`, `IrCompilerTest.kt`, `AstLowererTest.kt`, `ConstantFolderTest.kt`, `LivenessAnalyzerTest.kt`, `RegisterAllocatorTest.kt`, `RegisterSpillTest.kt`, `SpillInserterTest.kt`, `ControlFlowGraphTest.kt`, `OptimizationPassesTest.kt`, `SsaTest.kt`, `SsaRoundTripTest.kt`

### IDE Plugins
- `lectern-intellij/` → `quill-intellij/` (all contents)
- `lectern-vscode/` → `quill-vscode/` (all contents)

### Gradle Files
- `settings.gradle.kts`
- `build.gradle.kts`
- `lectern-intellij/build.gradle.kts` → `quill-intellij/build.gradle.kts`
- `lectern-intellij/settings.gradle.kts` → `quill-intellij/settings.gradle.kts`

### Documentation
- `ARCHITECTURE.md`
- `docs/docs/intro.md`
- `docs/docs/getting-started/*.md`
- `docs/docs/basics/*.md`
- All `docs/superpowers/plans/*.md`
- All `docs/superpowers/specs/*.md`
- `lectern-intellij/README.md`
- `lectern-vscode/README.md`

### Test Fixtures
- `test.lec` → `test.quill`
- `test_simple.lec` → `test_simple.quill`
- `test_features.lec` → `test_features.quill`
- `test_bubble.lec` → `test_bubble.quill`
- `test_compound.lec` → `test_compound.quill`
- `test_comprehensive.lec` → `test_comprehensive.quill`
- `test_for.lec` → `test_for.quill`
- `test_for_array.lec` → `test_for_array.quill`
- `test_index.lec` → `test_index.quill`
- `test_interpolation.lec` → `test_interpolation.quill`
- `test_prefix_edge.lec` → `test_prefix_edge.quill`
- `examples.lec` → `examples.quill`

---

## Verification

After rebrand:
1. `./gradlew build` — compiles successfully
2. `./gradlew test` — all tests pass
3. `./gradlew run --args="test.quill"` — sample program runs
4. IDE plugins load without errors (manual verification)
