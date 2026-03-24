# Printing Press Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Inklang compiler from Kotlin to Rust, outputting JSON bytecode compatible with the existing Kotlin VM.

**Architecture:** Standalone Rust CLI tool that takes `.ink` source files and outputs JSON bytecode matching the Kotlin `SerialScript` schema. Project lives in `~/dev/printing_press`.

**Tech Stack:** Rust (stable), clap (CLI), serde + serde_json (JSON serialization), thiserror (errors)

---

## File Structure

```
printing_press/
├── Cargo.toml
├── src/
│   ├── main.rs              # CLI entry (clap)
│   ├── inklang/
│   │   ├── mod.rs           # Module root
│   │   ├── error.rs         # CompilationError
│   │   ├── token.rs         # TokenType enum, Token struct
│   │   ├── lexer.rs         # Lexer
│   │   ├── ast.rs           # Expr/Stmt enums
│   │   ├── value.rs         # Value types (compile-time constants)
│   │   ├── parser.rs        # Pratt parser
│   │   ├── constant_fold.rs # Constant folding
│   │   ├── lowerer.rs       # AST → IR
│   │   ├── ir.rs            # IrInstr, IrLabel
│   │   ├── ssa/
│   │   │   ├── mod.rs
│   │   │   ├── builder.rs   # SSA builder
│   │   │   ├── deconstructor.rs
│   │   │   ├── value.rs     # SsaValue
│   │   │   ├── block.rs     # SsaBlock
│   │   │   ├── function.rs  # SsaFunction
│   │   │   └── passes/
│   │   │       ├── mod.rs
│   │   │       ├── constant_propagation.rs
│   │   │       ├── gvn.rs
│   │   │       └── dce.rs
│   │   ├── liveness.rs      # Liveness analysis
│   │   ├── register_alloc.rs
│   │   ├── spill_insert.rs
│   │   ├── codegen.rs       # IR → Chunk
│   │   ├── chunk.rs         # Chunk struct (matches Kotlin)
│   │   └── serialize.rs     # JSON serialization
│   └── cli.rs               # CLI argument parsing
└── tests/
    └── round_trip.rs        # Compare Kotlin vs Rust output
```

---

## Chunk 1: Project Scaffold

**Goal:** Create `printing_press` Rust project with Cargo.toml and basic structure.

- [ ] **Step 1: Create project**

```bash
mkdir -p /c/Users/justi/dev/printing_press
cd /c/Users/justi/dev/printing_press
cargo init --name printing_press
```

- [ ] **Step 2: Write Cargo.toml**

```toml
[package]
name = "printing_press"
version = "0.1.0"
edition = "2021"

[dependencies]
clap = { version = "4", features = ["derive"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
thiserror = "1"
```

- [ ] **Step 3: Create module structure**

Create all `src/inklang/*.rs` files as empty modules (stub `mod.rs` files that compile).

- [ ] **Step 4: Write stub main.rs with clap**

```rust
use clap::Parser;

#[derive(Parser, Debug)]
#[command(name = "printing_press")]
struct Args {
    #[command(subcommand)]
    command: Command,
}

#[derive(Parser, Debug)]
enum Command {
    Compile {
        #[arg(value_name = "INPUT")]
        input: String,
        #[arg(short, long, value_name = "OUTPUT")]
        output: String,
    },
}

fn main() {
    let args = Args::parse();
    match args.command {
        Command::Compile { input, output } => {
            let source = std::fs::read_to_string(&input).unwrap();
            let script = printing_press::compile(&source, "main");
            let json = serde_json::to_string_pretty(&script).unwrap();
            std::fs::write(&output, json).unwrap();
            println!("Compiled {} → {}", input, output);
        }
    }
}
```

- [ ] **Step 5: Write stub inklang/mod.rs**

```rust
pub mod error;
pub mod token;
pub mod lexer;
pub mod ast;
pub mod value;
pub mod parser;
pub mod constant_fold;
pub mod lowerer;
pub mod ir;
pub mod ssa;
pub mod liveness;
pub mod register_alloc;
pub mod spill_insert;
pub mod codegen;
pub mod chunk;
pub mod serialize;

pub fn compile(source: &str, name: &str) -> SerialScript {
    todo!()
}
```

- [ ] **Step 6: Verify it builds**

Run: `cargo build`
Expected: Builds successfully (with main.rs errors about missing functions - that's fine for now)

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: scaffold printing_press Rust project"
```

---

## Chunk 2: Lexer

**Files:**
- Create: `src/inklang/token.rs`
- Create: `src/inklang/lexer.rs`

**Reference:** `ink/src/main/kotlin/org/inklang/lang/Lexer.kt`, `Token.kt`

- [ ] **Step 1: Write token.rs (TokenType enum + Token struct)**

Match Kotlin `TokenType` enum exactly. All token types from the Kotlin file.

```rust
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenType {
    // Literals
    KwBool,
    KwInt,
    KwFloat,
    KwDouble,
    KwString,
    KwTrue,
    KwFalse,
    KwNull,
    // Keywords
    KwLet,
    KwConst,
    KwIf,
    KwElse,
    KwWhile,
    KwFor,
    KwIn,
    KwFn,
    KwReturn,
    KwAnd,
    KwOr,
    KwNot,
    KwBreak,
    KwNext,
    KwEnum,
    KwClass,
    KwExtends,
    KwImport,
    KwFrom,
    KwIs,
    KwHas,
    KwTable,
    KwKey,
    KwConfig,
    KwTry,
    KwCatch,
    KwFinally,
    KwThrow,
    KwOn,
    KwEnable,
    KwDisable,
    KwEvent,
    KwAsync,
    KwAwait,
    KwSpawn,
    KwVirtual,
    // Operators
    Identifier,
    Plus,
    Minus,
    Star,
    Slash,
    Percent,
    Increment,
    Decrement,
    Pow,
    EqEq,
    BangEq,
    Lt,
   Gt,
    Lte,
    Gte,
    Assign,
    Arrow,
    AddEquals,
    SubEquals,
    MulEquals,
    DivEquals,
    ModEquals,
    LBrace,
    RBrace,
    LParen,
    RParen,
    LSquare,
    RSquare,
    Bang,
    Comma,
    Dot,
    DotDot,
    Colon,
    Semicolon,
    Question,
    QuestionDot,
    QuestionQuestion,
    InterpolationStart,
    InterpolationEnd,
    Dollar,
    At,
    KwAnnotation,
    Eof,
}

#[derive(Debug, Clone)]
pub struct Token {
    pub typ: TokenType,
    pub lexeme: String,
    pub line: usize,
    pub column: usize,
}
```

- [ ] **Step 2: Write lexer.rs**

Port the Kotlin Lexer class. Key behaviors:
- Character-by-character scanning
- Keywords lookup (matching Kotlin keywords map)
- String scanning with interpolation support (`${...}`)
- Number scanning (int vs double based on `.` presence)
- ASI (automatic semicolon insertion) on newlines after statement-enders
- Comment scanning (`//`)

Reference the Kotlin Lexer for:
- STATEMENT_ENDERS set
- keyword map
- interpolation handling (interpolationDepth tracking)

- [ ] **Step 3: Write lexer tests**

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tokenize_int() {
        let tokens = tokenize("42");
        assert_eq!(tokens[0].typ, TokenType::KwInt);
        assert_eq!(tokens[0].lexeme, "42");
    }

    #[test]
    fn test_tokenize_string() {
        let tokens = tokenize("\"hello\"");
        assert_eq!(tokens[0].typ, TokenType::KwString);
        assert_eq!(tokens[0].lexeme, "\"hello\"");
    }

    #[test]
    fn test_tokenize_keywords() {
        let tokens = tokenize("let x = 5");
        assert_eq!(tokens[0].typ, TokenType::KwLet);
        assert_eq!(tokens[1].typ, TokenType::Identifier);
        assert_eq!(tokens[2].typ, TokenType::Assign);
        assert_eq!(tokens[3].typ, TokenType::KwInt);
    }

    #[test]
    fn test_tokenize_operators() {
        let tokens = tokenize("a + b == c");
        assert_eq!(tokens[1].typ, TokenType::Plus);
        assert_eq!(tokens[2].typ, TokenType::EqEq);
    }

    #[test]
    fn test_tokenize_interpolation() {
        let tokens = tokenize("\"hello ${name} world\"");
        // Should produce: STRING, INTERPOLATION_START, IDENTIFIER, INTERPOLATION_END, STRING
        assert!(tokens.iter().any(|t| t.typ == TokenType::InterpolationStart));
        assert!(tokens.iter().any(|t| t.typ == TokenType::InterpolationEnd));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cargo test --lib lexer`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: implement lexer"
```

---

## Chunk 3: AST

**Files:**
- Create: `src/inklang/value.rs`
- Create: `src/inklang/ast.rs`

**Reference:** `ink/src/main/kotlin/org/inklang/lang/AST.kt`

- [ ] **Step 1: Write value.rs (compile-time Value types)**

Match `Value` sealed class from Kotlin. These are compile-time constant values only.

```rust
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Boolean(bool),
    Int(i64),
    Float(f32),
    Double(f64),
    String(String),
    EventInfo {
        name: String,
        params: Vec<(String, String)>,
    },
}
```

- [ ] **Step 2: Write ast.rs (Expr + Stmt enums)**

Port all Expr and Stmt variants from Kotlin AST.kt. Key variants:

```rust
#[derive(Debug, Clone)]
pub enum Expr {
    Literal(Value),
    List(Vec<Expr>),
    Set(Vec<Expr>),
    Tuple(Vec<Expr>),
    Map(Vec<(Expr, Expr)>),
    Variable(String),
    Assign(Box<Expr>, Token, Box<Expr>),
    Binary(Box<Expr>, Token, Box<Expr>),
    Unary(Token, Box<Expr>),
    Ternary(Box<Expr>, Box<Expr>, Box<Expr>),
    Group(Box<Expr>),
    Call(Box<Expr>, Vec<Expr>),
    Lambda(Vec<Param>, Box<Stmt>),
    Get(Box<Expr>, Token),
    Index(Box<Expr>, Box<Expr>),
    Is(Box<Expr>, Token),
    Has(Box<Expr>, Box<Expr>),
    SafeCall(Box<Expr>, Token),
    Elvis(Box<Expr>, Box<Expr>),
    NamedArg(Token, Box<Expr>),
    Await(Box<Expr>),
    Spawn(Box<Expr>, bool),
    Throw(Box<Expr>),
    Annotation { name: String, args: HashMap<String, Expr> },
}

#[derive(Debug, Clone)]
pub enum Stmt {
    Expr(Expr),
    Let { name: Token, type_annot: Option<Token>, value: Expr },
    Const { name: Token, type_annot: Option<Token>, value: Expr },
    Block(Vec<Stmt>),
    If { condition: Expr, then_branch: Box<Stmt>, else_branch: Option<Box<Stmt>> },
    While { condition: Expr, body: Box<Stmt> },
    For { variable: Token, iterable: Expr, body: Box<Stmt> },
    Return(Option<Expr>),
    Break,
    Next,
    Fn { name: Token, params: Vec<Param>, body: Box<Stmt> },
    Class { name: Token, superclass: Option<Token>, methods: Vec<Stmt> },
    Enum { name: Token, variants: Vec<EnumVariant> },
    Config { name: Token, fields: Vec<ConfigField> },
    Try { body: Box<Stmt>, catch_var: Option<Token>, catch_body: Option<Box<Stmt>>, finally_body: Option<Box<Stmt>> },
    Throw(Expr),
    EventHandler { name: Token, params: Vec<Param>, body: Box<Stmt> },
    Enable(Box<Stmt>),
    Disable(Box<Stmt>),
    Import { path: Vec<String>, items: Option<Vec<String>>, from: bool },
    On { event: Token, handler: Box<Stmt> },
    AnnotationDef { name: Token, args: Vec<Param> },
}

#[derive(Debug, Clone)]
pub struct Param {
    pub name: Token,
    pub type_annot: Option<Token>,
    pub default: Option<Expr>,
}
```

- [ ] **Step 3: Write basic AST tests**

```rust
#[cfg(test)]
mod tests {
    #[test]
    fn test_expr_clone() {
        let expr = Expr::Literal(Value::Int(42));
        let cloned = expr.clone();
        assert!(matches!(cloned, Expr::Literal(Value::Int(42))));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cargo test --lib ast`
Expected: Tests pass

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: implement AST types"
```

---

## Chunk 4: Parser (Pratt Parser)

**Files:**
- Modify: `src/inklang/parser.rs` (create)

**Reference:** `ink/src/main/kotlin/org/inklang/lang/Parser.kt`

This is the most complex part. The Kotlin parser is a Pratt parser with operator precedence.

- [ ] **Step 1: Write parser.rs**

Key elements to port:
- Token stream consumption
- Pratt parser with precedence levels
- Expression parsing (handle all Expr types)
- Statement parsing (handle all Stmt types)
- Error recovery (skip to resynchronization point)
- Handle `INTERPOLATION_START/END` for string expressions

Precedence order (lowest to highest):
1. Assignment (`=`, `+=`, etc.)
2. Ternary (`?:`)
3. Elvis (`??`)
4. Or (`or`)
5. And (`and`)
6. Is (`is`)
7. Has (`has`)
8. Comparison (`==`, `!=`, `<`, `>`, `<=`, `>=`)
9. Range (`..`)
10. Term (`+`, `-`)
11. Factor (`*`, `/`, `%`)
12. Unary (`-`, `!`, `not`, `++`, `--`)
13. Call (`.`, `?.`, `[]`)
14. Primary (literals, identifiers, grouping)

- [ ] **Step 2: Write parser tests**

Start with simple expressions and build up.

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_literal() {
        let tokens = tokenize("42");
        let parser = Parser::new(tokens);
        let ast = parser.parse();
        assert!(matches!(ast[0], Stmt::Expr(Expr::Literal(Value::Int(42)))));
    }

    #[test]
    fn test_parse_binary_expr() {
        let tokens = tokenize("1 + 2");
        let parser = Parser::new(tokens);
        let ast = parser.parse();
        assert!(matches!(&ast[0], Stmt::Expr(Expr::Binary(..))));
    }

    #[test]
    fn test_parse_let_statement() {
        let tokens = tokenize("let x: int = 5");
        let parser = Parser::new(tokens);
        let ast = parser.parse();
        assert!(matches!(&ast[0], Stmt::Let { .. }));
    }

    #[test]
    fn test_parse_function() {
        let source = "fn add(a: int, b: int) -> int { a + b }";
        let tokens = tokenize(source);
        let parser = Parser::new(tokens);
        let ast = parser.parse();
        assert!(matches!(&ast[0], Stmt::Fn { .. }));
    }
}
```

- [ ] **Step 3: Run tests**

Run: `cargo test --lib parser`
Expected: Tests pass (may need iteration)

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: implement Pratt parser"
```

---

## Chunk 5: Constant Folding + Lowerer + IR

**Files:**
- Create: `src/inklang/constant_fold.rs`
- Create: `src/inklang/ir.rs`
- Create: `src/inklang/lowerer.rs`

**Reference:** `ink/src/main/kotlin/org/inklang/ast/ConstantFolder.kt`, `ink/src/main/kotlin/org/inklang/ast/AstLowerer.kt`, `ink/src/main/kotlin/org/inklang/lang/IR.kt`

- [ ] **Step 1: Write ir.rs**

```rust
#[derive(Debug, Clone)]
pub enum IrInstr {
    LoadImm { dst: usize, index: usize },
    LoadGlobal { dst: usize, name: String },
    StoreGlobal { name: String, src: usize },
    BinaryOp { dst: usize, op: TokenType, src1: usize, src2: usize },
    UnaryOp { dst: usize, op: TokenType, src: usize },
    Jump { target: IrLabel },
    JumpIfFalse { src: usize, target: IrLabel },
    Label { label: IrLabel },
    LoadFunc { dst: usize, name: String, arity: usize, instrs: Vec<IrInstr>, constants: Vec<Value>, default_values: Vec<Option<DefaultValueInfo>>, captured_vars: Vec<String>, upvalue_regs: Vec<usize>> },
    Call { dst: usize, func: usize, args: Vec<usize> },
    Return { src: usize },
    Move { dst: usize, src: usize },
    GetIndex { dst: usize, obj: usize, index: usize },
    SetIndex { obj: usize, index: usize, src: usize },
    NewArray { dst: usize, elements: Vec<usize> },
    GetField { dst: usize, obj: usize, name: String },
    SetField { obj: usize, name: String, src: usize },
    NewInstance { dst: usize, class_reg: usize, args: Vec<usize> },
    IsType { dst: usize, src: usize, type_name: String },
    HasCheck { dst: usize, obj: usize, field_name: String },
    Throw { src: usize },
    LoadClass { dst: usize, name: String, super_class: Option<String>, methods: HashMap<String, MethodInfo>> },
    Break,
    Next,
    Spill { slot: usize, src: usize },
    Unspill { dst: usize, slot: usize },
    GetUpvalue { dst: usize, upvalue_index: usize },
    RegisterEventHandler { event_name: String, handler_func_index: usize, event_param_name: String, data_param_names: Vec<String> },
    InvokeEventHandler { event_name: String, handler_index: usize, event_object_reg: usize, data_arg_regs: Vec<usize> },
    AwaitInstr { dst: usize, task: usize },
    SpawnInstr { dst: usize, func: usize, args: Vec<usize>, virtual_: bool },
    AsyncCallInstr { dst: usize, func: usize, args: Vec<usize> },
}

#[derive(Debug, Clone)]
pub struct MethodInfo {
    pub arity: usize,
    pub instrs: Vec<IrInstr>,
    pub constants: Vec<Value>,
    pub default_values: Vec<Option<DefaultValueInfo>>,
}

#[derive(Debug, Clone)]
pub struct DefaultValueInfo {
    pub instrs: Vec<IrInstr>,
    pub constants: Vec<Value>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct IrLabel(pub usize);
```

- [ ] **Step 2: Write constant_fold.rs**

Port `ConstantFolder.kt`. Fold literal expressions at compile time:
- `1 + 2` → `3`
- `!true` → `false`
- `"hello" + "world"` → `"helloworld"`
- Leave non-constant expressions unchanged

- [ ] **Step 3: Write lowerer.rs**

Port `AstLowerer.kt`. Transform AST into IR:
- Statement lowering (let, const, if, while, for, return, etc.)
- Expression lowering (binary ops, function calls, etc.)
- Register allocation for temporaries
- Handle control flow with labels and jumps
- Closure lowering (capture variables)

- [ ] **Step 4: Write tests for lowerer**

```rust
#[cfg(test)]
mod tests {
    #[test]
    fn test_lower_literal() {
        let tokens = tokenize("5");
        let ast = Parser::new(tokens).parse();
        let (instrs, constants) = lowerer::lower(&ast);
        // Should produce a LoadImm instruction
    }
}
```

- [ ] **Step 5: Run tests**

Run: `cargo test --lib lowerer`
Expected: Tests pass

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: implement IR, constant folding, and AST lowerer"
```

---

## Chunk 6: SSA Infrastructure

**Files:**
- Create: `src/inklang/ssa/mod.rs`
- Create: `src/inklang/ssa/builder.rs`
- Create: `src/inklang/ssa/deconstructor.rs`
- Create: `src/inklang/ssa/value.rs`
- Create: `src/inklang/ssa/block.rs`
- Create: `src/inklang/ssa/function.rs`
- Create: `src/inklang/ssa/passes/mod.rs`
- Create: `src/inklang/ssa/passes/constant_propagation.rs`
- Create: `src/inklang/ssa/passes/gvn.rs`
- Create: `src/inklang/ssa/passes/dce.rs`

**Reference:** `ink/src/main/kotlin/org/inklang/ssa/SsaBuilder.kt`, `SsaDeconstructor.kt`, and passes

- [ ] **Step 1: Write ssa/value.rs, block.rs, function.rs**

Define `SsaValue`, `SsaBlock`, `SsaFunction` types that represent SSA form.

- [ ] **Step 2: Write ssa/builder.rs**

Port `SsaBuilder.kt`. Convert linear IR into SSA form:
- Identify basic blocks
- Insert φ-functions at join points
- Rename registers (SSA renaming)

- [ ] **Step 3: Write ssa/deconstructor.rs**

Port `SsaDeconstructor.kt`. Convert SSA back to linear IR:
- Replace φ-nodes with moves from predecessor registers
- Remove SSA-specific instructions

- [ ] **Step 4: Write SSA passes**

Port the optimization passes from Kotlin:
- `constant_propagation.rs` - replace uses of constants with their values
- `gvn.rs` - Global Value Numbering (detect redundant computations)
- `dce.rs` - Dead Code Elimination (remove unreachable code)

- [ ] **Step 5: Write tests**

```rust
#[cfg(test)]
mod tests {
    #[test]
    fn test_ssa_round_trip() {
        // Lower AST to IR
        // Build SSA
        // Deconstruct SSA
        // Should produce equivalent IR
    }
}
```

- [ ] **Step 6: Run tests**

Run: `cargo test --lib ssa`
Expected: Tests pass

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: implement SSA builder, deconstructor, and optimization passes"
```

---

## Chunk 7: Register Allocation + Spill Insertion

**Files:**
- Create: `src/inklang/liveness.rs`
- Create: `src/inklang/register_alloc.rs`
- Create: `src/inklang/spill_insert.rs`

**Reference:** `ink/src/main/kotlin/org/inklang/ast/LivenessAnalyzer.kt`, `RegisterAllocator.kt`, `SpillInserter.kt`

- [ ] **Step 1: Write liveness.rs**

Port `LivenessAnalyzer.kt`:
- Compute live ranges for each virtual register
- Build interference graph
- Return vector of live ranges per instruction

```rust
pub struct LiveRange {
    pub start: usize,
    pub end: usize,
    pub reg: usize,
}
```

- [ ] **Step 2: Write register_alloc.rs**

Port `RegisterAllocator.kt`:
- Graph-coloring allocator (16 physical registers: R0-R15)
- Allocate virtual registers to physical registers
- Track spill slot requirements

```rust
pub struct AllocResult {
    pub mapping: Vec<usize>,       // virtual reg → physical reg
    pub spill_slot_count: usize,
}
```

- [ ] **Step 3: Write spill_insert.rs**

Port `SpillInserter.kt`:
- Insert `Spill` and `Unspill` instructions where registers overflow

- [ ] **Step 4: Write tests**

```rust
#[cfg(test)]
mod tests {
    #[test]
    fn test_liveness_basic_block() {
        // Compute liveness for a simple sequence
    }
}
```

- [ ] **Step 5: Run tests**

Run: `cargo test --lib register_alloc`
Expected: Tests pass

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: implement liveness analysis and register allocation"
```

---

## Chunk 8: Code Generation + Serialization

**Files:**
- Create: `src/inklang/codegen.rs`
- Create: `src/inklang/chunk.rs`
- Create: `src/inklang/serialize.rs`

**Reference:** `ink/src/main/kotlin/org/inklang/lang/Chunk.kt`, `ink/src/main/kotlin/org/inklang/ChunkSerializer.kt`, second half of `IrCompiler.kt`

- [ ] **Step 1: Write chunk.rs**

Match Kotlin `Chunk` class exactly:

```rust
#[derive(Debug, Clone)]
pub struct Chunk {
    pub code: Vec<i32>,                        // 32-bit packed bytecode words
    pub constants: Vec<SerialValue>,           // compile-time constants
    pub strings: Vec<String>,                  // string pool
    pub functions: Vec<Box<Chunk>>,             // nested function chunks
    pub classes: Vec<ClassInfo>,                // class definitions
    pub function_defaults: Vec<FunctionDefaults>,
    pub function_upvalues: HashMap<usize, (usize, Vec<usize>)>, // funcIdx → (count, regs)
    pub spill_slot_count: usize,
    pub cst_table: Vec<CstNode>,               // always empty in Rust compiler
}

#[derive(Debug, Clone)]
pub struct ClassInfo {
    pub name: String,
    pub super_class: Option<String>,
    pub methods: HashMap<String, usize>,
}

#[derive(Debug, Clone)]
pub struct FunctionDefaults {
    pub default_chunks: Vec<Option<usize>>,
}

// SerialValue - matches Kotlin SerialValue exactly
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "t")]
pub enum SerialValue {
    #[serde(rename = "null")]
    Null,
    #[serde(rename = "bool")]
    Bool { v: bool },
    #[serde(rename = "int")]
    Int { v: i64 },
    #[serde(rename = "float")]
    Float { v: f32 },
    #[serde(rename = "double")]
    Double { v: f64 },
    #[serde(rename = "string")]
    String { v: String },
    #[serde(rename = "event")]
    Event { name: String, params: Vec<Vec<String>> },
}
```

Key: `#[serde(tag = "t")]` produces `{"t":"int","v":42}` format matching Kotlin exactly.

- [ ] **Step 2: Write codegen.rs**

Port the `compile()` method from `IrCompiler.kt`:
- Resolve label positions (first pass)
- Emit bytecode words (second pass)
- Map IrInstr → OpCode
- Handle opcode packing: `opcode | (dst << 8) | (src1 << 12) | (src2 << 16) | (imm << 20)`

OpCode enum values must match Kotlin exactly:
```rust
enum OpCode(u8) {
    LoadImm = 0x00,
    Pop = 0x01,
    LoadGlobal = 0x05,
    StoreGlobal = 0x06,
    Move = 0x07,
    Add = 0x08,
    // ... etc (match Kotlin OpCode.kt exactly)
}
```

- [ ] **Step 3: Write serialize.rs**

Port `ChunkSerializer.kt` serialization logic:

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SerialConfigField {
    pub name: String,
    #[serde(rename = "type")]
    pub type_: String,
    pub default_value: Option<SerialValue>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SerialScript {
    pub name: String,
    pub chunk: Chunk,
    pub config_definitions: HashMap<String, Vec<SerialConfigField>>,
}
```

- [ ] **Step 4: Wire into main compile function**

```rust
pub fn compile(source: &str, name: &str) -> SerialScript {
    let tokens = lexer::tokenize(source);
    let ast = parser::Parser::new(tokens).parse();
    let folded = constant_fold::fold(&ast);
    let lowered = lowerer::lower(&folded);
    let (ssa_instrs, ssa_constants) = ssa::optimized_ssa_round_trip(lowered.instrs, lowered.constants);
    let ranges = liveness::analyze(&ssa_instrs);
    let alloc = register_alloc::allocate(ranges, lowered.arity);
    let resolved = spill_insert::insert(ssa_instrs, alloc, ranges);
    let chunk = codegen::compile(resolved, ssa_constants);
    SerialScript {
        name: name.to_string(),
        chunk,
        config_definitions: todo!(),
    }
}
```

- [ ] **Step 5: Run tests**

Run: `cargo build`
Expected: Builds successfully

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: implement code generation and JSON serialization"
```

---

## Chunk 9: Integration + Round-Trip Testing

**Goal:** Verify Rust compiler produces output compatible with Kotlin VM.

- [ ] **Step 1: Compile a simple script with Kotlin compiler**

```bash
cd /c/Users/justi/dev/ink
./gradlew :ink:compile 2>/dev/null
# or find an existing .ink test file
```

- [ ] **Step 2: Manually compile same script with Rust compiler**

Add a test script to `printing_press/tests/round_trip.rs`:

```rust
#[test]
fn test_simple_script() {
    let source = r#"
fn main() {
    print("hello world")
}
"#;
    let result = printing_press::compile(source, "test");
    let json = serde_json::to_string(&result).unwrap();
    // Should produce valid JSON matching SerialScript schema
}
```

- [ ] **Step 3: Verify JSON format**

Check that output matches Kotlin format exactly:
- `code` is a list of integers
- `constants` uses `{"t":"...",...}` discriminator format
- `strings` is a flat list
- `functions` is recursive
- `functionUpvalues` uses string keys

- [ ] **Step 4: Load Rust output in Kotlin VM**

Modify Kotlin code temporarily to load Rust JSON:
```kotlin
val rustJson = File("printing_press_output.json").readText()
val script = inkScriptFromJson(rustJson)
script.execute(context)
```

Verify it runs correctly.

- [ ] **Step 5: Run full test suite**

Run: `cargo test`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add round-trip tests and integration"
```

---

## Chunk 10: CLI Polish

**Goal:** Final CLI ergonomics.

- [ ] **Step 1: Add error handling**

Make errors user-friendly:
```rust
match printing_press::compile(&source, &name) {
    Ok(script) => { ... }
    Err(e) => {
        eprintln!("error: {}", e);
        std::process::exit(1);
    }
}
```

- [ ] **Step 2: Add `--debug` flag for verbose output**

```rust
#[derive(Parser, Debug)]
struct CompileArgs {
    #[arg(value_name = "INPUT")]
    input: String,
    #[arg(short, long, value_name = "OUTPUT")]
    output: String,
    #[arg(short, long)]
    debug: bool,
}
```

- [ ] **Step 3: Add version**

```rust
#[derive(Parser, Debug)]
#[command(version = "0.1.0")]
struct Args { ... }
```

- [ ] **Step 4: Test CLI**

```bash
printing_press compile test.ink -o test.ink.json
printing_press --help
printing_press --version
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: polish CLI"
```

---

## Final Verification

After all chunks:

1. `cargo build --release` succeeds
2. `cargo test` all pass
3. A simple `.ink` file compiles to JSON matching the Kotlin compiler's output format
4. The JSON can be loaded by the Kotlin VM (via `inkScriptFromJson()`)

---

## Notes

- **cstTable**: Always set to `[]` in Rust compiler output (bukkit-specific)
- **functionUpvalues**: Key must be string (funcIdx as string) per Kotlin `serde` serialization
- **Spill slot count**: Must be accurate or VM will misread stack
- **Floating point**: Kotlin `Float` = f32, `Double` = f64 — serde must serialize as JSON number
