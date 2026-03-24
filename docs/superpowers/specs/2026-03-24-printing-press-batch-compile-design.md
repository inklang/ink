# printing_press Batch Compilation Design

## Context

The Quill build tool (`ink-build.ts`) uses `ink.jar` with a batch compilation interface:
```bash
java -jar ink.jar compile --grammar <ir.json> --sources <dir> --out <dir>
```

`printing_press`, the Rust-based Ink compiler, currently only supports single-file compilation:
```bash
printing_press compile <input.ink> -o <output.json>
```

To make Quill work with `printing_press`, the Rust compiler needs to support the same batch interface.

## Design

### CLI Interface

printing_press gains two modes:

| Mode | Invocation |
|------|------------|
| Single file (existing) | `printing_press compile <input.ink> -o <output.json>` |
| Batch (new) | `printing_press compile --sources <dir> --out <dir> [--grammar <ir.json>...]` |

#### Arguments

| Argument | Mode | Description |
|----------|------|-------------|
| `<input.ink>` | Single-file | Source file to compile |
| `-o, --output <file>` | Single-file | Output file path |
| `--sources <dir>` | Batch | Directory containing `.ink` source files |
| `--out <dir>` | Batch | Output directory for compiled `.inkc` files |
| `--grammar <file>` | Both | Grammar IR file (can be repeated for multiple) |
| `--debug` | Both | Pretty-print JSON output |

Single-file mode and batch mode are mutually exclusive. When `--sources` is provided, `--out` is required and single-file args are ignored.

### Batch Mode Behavior

1. Scan `--sources` for all `*.ink` files (non-recursive, top-level only)
2. For each `*.ink` file, compile using all provided grammar IRs merged in order
3. Write output to `--out/<name>.inkc` (preserving filename, only extension changes)
4. Report errors per-file but continue processing others
5. Exit with error code if any file fails

### Quill Integration

**`ink-build.ts`** (`src/commands/ink-build.ts`):
- `resolveCompiler()` (lines 174-199) updated to also find `printing_press` or `printing_press.exe` in the bundled compiler path
- Lines 152-156 (`execSync` call) can remain unchanged — the CLI interface is compatible

**`resolveCompiler()` search order:**
1. Bundled `compiler/ink.jar` (existing)
2. Bundled `compiler/printing_press` or `compiler/printing_press.exe` (new)
3. `INK_COMPILER` env var (existing)

### README Update

The `printing_press` README gets a CLI reference section documenting both modes with examples.

## Verification

1. `printing_press compile --help` shows both modes
2. Single-file mode produces identical output to before
3. Batch mode correctly compiles a directory of `.ink` files
4. Quill's `ink-build` works with `printing_press` as the compiler
5. Grammar IR files are correctly loaded and applied

## Out of Scope

- Recursive directory scanning
- Watching mode
- Custom file extensions (only `*.ink`)

## Spec Review

Status: **approved**