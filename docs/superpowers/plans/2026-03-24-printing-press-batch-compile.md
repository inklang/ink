# printing_press Batch Compilation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `--sources <dir> --out <dir> [--grammar <file>...]` batch compilation mode to printing_press, matching ink.jar's CLI interface. Update quill's ink-build to use printing_press.

**Architecture:** printing_press gains a GrammarPackage deserializer and merged grammar registry. The parser accepts an optional grammar registry for resolving grammar rule references. The SerialScript's `config_definitions` is populated from grammar handlers.

**Tech Stack:** Rust (serde, clap), TypeScript (quill build tool)

---

## Chunk 1: Add Grammar IR Types and Loading (printing_press)

**Files:**
- Create: `src/inklang/grammar.rs`
- Modify: `src/inklang/mod.rs` (add `pub mod grammar;` and re-export)
- Modify: `src/inklang/parser.rs` (accept grammar registry in Parser::new)
- Modify: `src/lib.rs` (add `compile_with_grammar` variant)

- [ ] **Step 1: Create `src/inklang/grammar.rs`** with:
  - `GrammarPackage` struct matching the TypeScript IR (version, package, keywords, rules, declarations)
  - `RuleEntry` struct (rule, optional handler)
  - `DeclarationDef` struct (keyword, nameRule, scopeRules, inheritsBase, optional handler)
  - `Rule` enum matching all TypeScript rule types (seq, choice, many, many1, optional, ref, keyword, literal, identifier, int, float, string, block)
  - `MergedGrammar` struct with `keywords: Vec<String>`, `rules: HashMap<String, RuleEntry>`, `declarations: Vec<DeclarationDef>`
  - `fn load_grammar(path: &str) -> Result<GrammarPackage, CompileError>` — deserialize JSON file
  - `fn merge_grammars(packages: Vec<GrammarPackage>) -> MergedGrammar` — combine keywords, rules, declarations

```rust
// src/inklang/grammar.rs
use std::collections::HashMap;
use serde::Deserialize;
use super::error::CompileError;

#[derive(Debug, Clone, Deserialize)]
pub struct GrammarPackage {
    pub version: u32,
    pub package: String,
    pub keywords: Vec<String>,
    pub rules: HashMap<String, RuleEntry>,
    pub declarations: Vec<DeclarationDef>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RuleEntry {
    pub rule: Rule,
    #[serde(default)]
    pub handler: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DeclarationDef {
    pub keyword: String,
    pub name_rule: Rule,
    pub scope_rules: Vec<String>,
    pub inherits_base: bool,
    #[serde(default)]
    pub handler: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "type")]
pub enum Rule {
    Seq { items: Vec<Rule> },
    Choice { items: Vec<Rule> },
    Many { item: Box<Rule> },
    Many1 { item: Box<Rule> },
    Optional { item: Box<Rule> },
    Ref { rule: String },
    Keyword { value: String },
    Literal { value: String },
    Identifier,
    Int,
    Float,
    String,
    Block { scope: Option<String> },
}

#[derive(Debug, Clone)]
pub struct MergedGrammar {
    pub keywords: Vec<String>,
    pub rules: HashMap<String, RuleEntry>,
    pub declarations: Vec<DeclarationDef>,
}

pub fn load_grammar(path: &str) -> Result<GrammarPackage, CompileError> {
    let content = std::fs::read_to_string(path)
        .map_err(|e| CompileError::Compilation(format!("Failed to read grammar file '{}': {}", path, e)))?;
    serde_json::from_str(&content)
        .map_err(|e| CompileError::Compilation(format!("Failed to parse grammar file '{}': {}", path, e)))
}

pub fn merge_grammars(packages: Vec<GrammarPackage>) -> MergedGrammar {
    let mut all_keywords = Vec::new();
    let mut all_rules = HashMap::new();
    let mut all_declarations = Vec::new();

    for pkg in packages {
        all_keywords.extend(pkg.keywords);
        all_rules.extend(pkg.rules);
        all_declarations.extend(pkg.declarations);
    }

    // Deduplicate keywords
    all_keywords.sort();
    all_keywords.dedup();

    MergedGrammar {
        keywords: all_keywords,
        rules: all_rules,
        declarations: all_declarations,
    }
}
```

- [ ] **Step 2: Update `src/inklang/mod.rs`** — add `pub mod grammar;` after `pub mod serialize;`

```rust
pub mod grammar;
```

- [ ] **Step 3: Update `src/inklang/parser.rs`** — add `grammar` parameter to `Parser::new`

Look at current `Parser::new` signature and add `grammar: Option<&MergedGrammar>` parameter. The grammar is used to resolve `Rule::Ref` variants during parsing by looking up rules in the registry. For now, if grammar is None, Ref errors should be handled gracefully (or we can skip ref resolution - the grammar ref mechanism may be used only for plugin-defined grammars).

- [ ] **Step 4: Update `src/lib.rs`** — add `compile_with_grammar(source, name, grammar: &MergedGrammar)` function

```rust
pub fn compile_with_grammar(source: &str, name: &str, grammar: &MergedGrammar) -> Result<SerialScript, CompileError> {
    let tokens = lexer::tokenize(source);
    let ast = Parser::new(tokens, Some(grammar))
        .parse()
        .map_err(|e| CompileError::Parsing(e.to_string()))?;
    // ... rest same as compile() ...
}
```

- [ ] **Step 5: Commit**

```bash
cd /c/Users/justi/dev/printing_press
git add src/inklang/grammar.rs src/inklang/mod.rs src/inklang/parser.rs src/lib.rs
git commit -m "feat(printing_press): add Grammar IR types and loading"
```

---

## Chunk 2: Add Batch CLI Mode to printing_press

**Files:**
- Modify: `src/main.rs`

- [ ] **Step 1: Rewrite CLI arguments** to support both single-file and batch mode

```rust
use clap::Parser;

#[derive(Parser, Debug)]
#[command(name = "printing_press", version = "0.1.0", about = "Inklang compiler")]
struct Args {
    #[command(subcommand)]
    command: Command,
}

#[derive(Parser, Debug)]
enum Command {
    Compile(CompileArgs),
}

#[derive(Parser, Debug)]
struct CompileArgs {
    /// Source file (single-file mode)
    #[arg(value_name = "INPUT")]
    input: Option<String>,

    /// Output file (single-file mode)
    #[arg(short, long, value_name = "OUTPUT")]
    output: Option<String>,

    /// Directory containing .ink source files (batch mode)
    #[arg(long, value_name = "DIR")]
    sources: Option<String>,

    /// Output directory (batch mode)
    #[arg(long, value_name = "DIR")]
    out: Option<String>,

    /// Grammar IR file (can be repeated)
    #[arg(long = "grammar")]
    grammar_files: Vec<String>,

    /// Pretty-print JSON output
    #[arg(short, long)]
    debug: bool,
}

fn main() {
    let args = Args::parse();
    match args.command {
        Command::Compile(c) => {
            // Determine mode: if --sources provided, batch mode
            if let Some(sources_dir) = c.sources {
                let out_dir = c.out.expect("--out is required in batch mode");
                let grammar = if !c.grammar_files.is_empty() {
                    let packages: Vec<_> = c.grammar_files.iter()
                        .map(|p| printing_press::inklang::grammar::load_grammar(p).unwrap())
                        .collect();
                    Some(printing_press::inklang::grammar::merge_grammars(packages))
                } else {
                    None
                };
                batch_compile(&sources_dir, &out_dir, grammar.as_ref(), c.debug);
            } else {
                // Single-file mode
                let input = c.input.expect("INPUT file or --sources required");
                let output = c.output.expect("-o/--output required in single-file mode");
                single_compile(&input, &output, c.debug);
            }
        }
    }
}

fn single_compile(input: &str, output: &str, debug: bool) {
    match std::fs::read_to_string(input) {
        Ok(source) => {
            match printing_press::compile(&source, "main") {
                Ok(script) => {
                    let json = if debug {
                        serde_json::to_string_pretty(&script).unwrap()
                    } else {
                        serde_json::to_string(&script).unwrap()
                    };
                    std::fs::write(output, json).unwrap();
                    println!("Compiled {} → {}", input, output);
                }
                Err(e) => {
                    eprintln!("error: compilation failed: {}", e);
                    std::process::exit(1);
                }
            }
        }
        Err(e) => {
            eprintln!("error: could not read file '{}': {}", input, e);
            std::process::exit(1);
        }
    }
}

fn batch_compile(sources_dir: &str, out_dir: &str, grammar: Option<&printing_press::inklang::grammar::MergedGrammar>, debug: bool) {
    let src_path = std::path::Path::new(sources_dir);
    let out_path = std::path::Path::new(out_dir);
    std::fs::create_dir_all(out_path).unwrap();

    let entries: Vec<_> = std::fs::read_dir(src_path)
        .unwrap()
        .filter_map(|e| e.ok())
        .filter(|e| e.path().extension().map_or(false, |ext| ext == "ink"))
        .collect();

    if entries.is_empty() {
        println!("No .ink files found in {}", sources_dir);
        return;
    }

    let mut errors = 0;
    for entry in entries {
        let input_path = entry.path();
        let file_name = input_path.file_stem().unwrap().to_str().unwrap();
        let output_path = out_path.join(format!("{}.inkc", file_name));

        let source = match std::fs::read_to_string(&input_path) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("error: could not read file '{}': {}", input_path.display(), e);
                errors += 1;
                continue;
            }
        };

        let result = if let Some(g) = grammar {
            printing_press::compile_with_grammar(&source, file_name, g)
        } else {
            printing_press::compile(&source, file_name).map_err(|e| e.into())
        };

        match result {
            Ok(script) => {
                let json = if debug {
                    serde_json::to_string_pretty(&script).unwrap()
                } else {
                    serde_json::to_string(&script).unwrap()
                };
                std::fs::write(&output_path, json).unwrap();
                println!("Compiled {} → {}", input_path.file_name().unwrap().to_str().unwrap(), output_path.file_name().unwrap().to_str().unwrap());
            }
            Err(e) => {
                eprintln!("error: compilation failed for '{}': {}", input_path.display(), e);
                errors += 1;
            }
        }
    }

    if errors > 0 {
        eprintln!("{} file(s) failed to compile", errors);
        std::process::exit(1);
    }
}
```

- [ ] **Step 2: Build and verify**

```bash
cd /c/Users/justi/dev/printing_press
cargo build --release
./target/release/printing_press compile --help
```

Expected output shows both modes.

- [ ] **Step 3: Test single-file mode still works**

```bash
echo 'print("hello")' > /tmp/test.ink
./target/release/printing_press compile /tmp/test.ink -o /tmp/test.inkc --debug
cat /tmp/test.inkc
```

- [ ] **Step 4: Test batch mode**

```bash
mkdir -p /tmp/ink_sources
echo 'print("file1")' > /tmp/ink_sources/script1.ink
echo 'print("file2")' > /tmp/ink_sources/script2.ink
./target/release/printing_press compile --sources /tmp/ink_sources --out /tmp/ink_out --debug
ls /tmp/ink_out/
```

Expected: `script1.inkc` and `script2.inkc` created.

- [ ] **Step 5: Commit**

```bash
git add src/main.rs
git commit -m "feat(printing_press): add --sources/--out batch compilation mode"
```

---

## Chunk 3: Update Quill ink-build.ts to Use printing_press

**Files:**
- Modify: `src/commands/ink-build.ts` (lines 174-199)

- [ ] **Step 1: Update `resolveCompiler()`** to also find `printing_press` or `printing_press.exe`

Current search order: `compiler/ink.jar` → `INK_COMPILER` env var

New search order: `compiler/ink.jar` → `compiler/printing_press.exe` → `compiler/printing_press` → `INK_COMPILER` env var

```typescript
private resolveCompiler(): string | null {
  const tryPath = (p: string): string | null => {
    if (existsSync(p)) return p
    const msys = p.match(/^\/([a-zA-Z])\/(.*)$/)
    if (msys) {
      const win = `${msys[1].toUpperCase()}:/${msys[2]}`
      if (existsSync(win)) return win
    }
    return null
  }

  const quillRoot = fileURLToPath(new URL('../..', import.meta.url))

  // 1. Bundled ink.jar (existing)
  const bundledJar = join(quillRoot, 'compiler', 'ink.jar')
  const r1 = tryPath(bundledJar)
  if (r1) return r1

  // 2. Bundled printing_press (new)
  const bundledPressExe = join(quillRoot, 'compiler', 'printing_press.exe')
  const r2 = tryPath(bundledPressExe)
  if (r2) return r2

  const bundledPress = join(quillRoot, 'compiler', 'printing_press')
  const r3 = tryPath(bundledPress)
  if (r3) return r3

  // 3. INK_COMPILER env var
  const envCompiler = process.env['INK_COMPILER']
  if (envCompiler) {
    const r = tryPath(envCompiler)
    if (r) return r
  }

  return null
}
```

- [ ] **Step 2: Detect which compiler is being used and adjust CLI invocation**

After `resolveCompiler()`, check if the path ends with `printing_press` or `printing_press.exe`. If so, use the new batch CLI format. If ink.jar, use the existing Java invocation.

```typescript
const compiler = this.resolveCompiler()
const isPrintingPress = compiler?.endsWith('printing_press') || compiler?.endsWith('printing_press.exe')
```

Then build the appropriate command:

```typescript
if (isPrintingPress) {
  // printing_press batch mode
  const compilerPath = compiler!.replace(/\\/g, '/')
  const sourcesFwd = scriptsDir.replace(/\\/g, '/')
  const outFwd = outDir.replace(/\\/g, '/')
  const grammarFlags = grammarArgs
    .map(p => `--grammar "${p.replace(/\\/g, '/')}"`)
    .join(' ')

  try {
    execSync(
      `"${compilerPath}" compile --sources "${sourcesFwd}" --out "${outFwd}" ${grammarFlags}`,
      { cwd: this.projectDir, stdio: 'pipe' } as any
    )
  } catch (e: any) {
    const output = (e.stdout?.toString() ?? '') + (e.stderr?.toString() ?? '')
    console.error('Ink compilation failed:\n' + output)
    process.exit(1)
  }
} else {
  // ink.jar mode (existing)
  execSync(
    `"${javaCmd}" -jar "${compilerPath}" compile ${grammarFlags} --sources "${scriptsDirFwd}" --out "${outDirFwd}"`,
    { cwd: this.projectDir, stdio: 'pipe' } as any
  )
}
```

- [ ] **Step 3: Commit**

```bash
cd /c/Users/justi/dev/quill
git add src/commands/ink-build.ts
git commit -m "feat(quill): support printing_press as compiler with batch mode"
```

---

## Chunk 4: Update printing_press README

**Files:**
- Modify: `README.md` in printing_press repo

- [ ] **Step 1: Add CLI reference section**

Document both modes with examples:

```markdown
## CLI Reference

### Single-File Compilation

```bash
printing_press compile <input.ink> -o <output.json> [--debug]
```

### Batch Compilation

```bash
printing_press compile --sources <dir> --out <dir> [--grammar <ir.json>...] [--debug]
```

Scan a directory of `.ink` files and compile each to `.inkc`.

**Arguments:**
- `--sources <dir>` — Directory containing `.ink` source files
- `--out <dir>` — Output directory for compiled `.inkc` files
- `--grammar <file>` — Grammar IR file (can be repeated for multiple grammars)
- `--debug` — Pretty-print JSON output
- `-o <file>` — Output file (single-file mode only)
```

- [ ] **Step 2: Commit**

```bash
cd /c/Users/justi/dev/printing_press
git add README.md
git commit -m "docs(printing_press): add CLI reference with batch mode"
```

---

## Summary

| Chunk | Files | What's Done |
|-------|-------|-------------|
| 1 | `grammar.rs`, `mod.rs`, `parser.rs`, `lib.rs` | Grammar IR types, loading, merging, parser integration |
| 2 | `main.rs` | Batch CLI (`--sources/--out`) |
| 3 | `ink-build.ts` | Quill compiler resolution for printing_press |
| 4 | `README.md` | Documentation |
