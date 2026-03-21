# Inklang Compiler Internals

> Detailed compiler implementation reference.

## Entry Point

### `InkCompiler.kt`

```kotlin
class InkCompiler {
    fun compile(source: String, name: String = "main"): InkScript {
        // 1. Tokenize
        // 2. Parse
        // 3. Constant fold
        // 4. Lower to IR
        // 5. SSA round-trip optimization
        // 6. Liveness analysis
        // 7. Register allocation
        // 8. Spill insertion
        // 9. Bytecode compilation
    }
}
```

## Token Types (`Token.kt`)

```kotlin
enum class TokenType {
    // Keywords
    KW_CLASS, KW_FN, KW_IF, KW_WHILE, KW_FOR, KW_IN,
    KW_RETURN, KW_LET, KW_CONST, KW_TRUE, KW_FALSE, KW_NULL,
    KW_AND, KW_OR, KW_NOT, KW_BREAK, KW_NEXT, KW_IS, KW_HAS,
    KW_TRY, KW_CATCH, KW_FINALLY, KW_THROW,
    // Types
    KW_BOOL, KW_INT, KW_FLOAT, KW_DOUBLE, KW_STRING,
    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT, POW,
    EQ_EQ, BANG_EQ, LT, GT, LTE, GTE,
    ASSIGN, ARROW, INCREMENT, DECREMENT,
    ADD_EQUALS, SUB_EQUALS, MUL_EQUALS, DIV_EQUALS, MOD_EQUALS,
    DOT, DOT_DOT, COMMA, COLON, SEMICOLON, BANG,
    QUESTION, QUESTION_DOT, QUESTION_QUESTION,
    // Literals
    IDENTIFIER, INTERPOLATION_START, INTERPOLATION_END, DOLLAR, EOF
}
```

## AST Nodes (`AST.kt`)

### Expressions (sealed class `Expr`)

```kotlin
// Literals
LiteralExpr(Value)           // int, float, string, bool, null
ListExpr(List<Expr>)         // [a, b, c]
SetExpr(List<Expr>)          // #{a, b, c}
TupleExpr(List<Expr>)        // (a, b)
MapExpr(List<Pair<Expr,Expr>>) // {a: 1, b: 2}

// Variables and assignment
VariableExpr(name: Token)
AssignExpr(target, op, value)

// Operators
BinaryExpr(left, op: Token, right)   // a + b, a and b, etc.
UnaryExpr(op: Token, right)           // -a, not a, ++a
TernaryExpr(condition, then, else)    // a ? b : c
IsExpr(expr, type)                    // a is String
HasExpr(target, field)               // obj has field

// Field/index access
GetExpr(obj, name: Token)            // obj.field
IndexExpr(obj, index)                // arr[0]
SafeCallExpr(obj, name: Token)       // obj?.field
ElvisExpr(left, right)               // left ?? right

// Function calls
CallExpr(callee, paren, arguments)
LambdaExpr(params, body)

// Other
GroupExpr(expr)                      // (a + b)
ThrowExpr(value)
```

### Statements (sealed class `Stmt`)

```kotlin
// Declarations
ClassStmt(name, superClass?, body)
FuncStmt(name, params, returnType?, body)
VarStmt(keyword, name, value?)
EnumStmt(name, values)
ImportStmt(namespace)
ImportFromStmt(namespace, tokens)

// Config/Table (stubs)
ConfigStmt(name, fields)
TableStmt(name, fields)

// Imperative
ExprStmt(expr)
BlockStmt(stmts)
IfStmt(condition, then, elseBranch)
WhileStmt(condition, body)
ForRangeStmt(variable, iterable, body)
ReturnStmt(value?)
BreakStmt
NextStmt
```

## Parser (`Parser.kt`)

### Operator Precedence (weights map)

Lower weight = binds tighter:

```kotlin
companion object {
    val weights = mapOf(
        // Tighter binding first
        TokenType.OR to 1,
        TokenType.KW_AND to 2,
        TokenType.EQ_EQ to 5, TokenType.BANG_EQ to 5,
        TokenType.LT to 7, TokenType.GT to 7, TokenType.LTE to 7, TokenType.GTE to 7,
        TokenType.PLUS to 10, TokenType.MINUS to 10,
        TokenType.STAR to 20, TokenType.SLASH to 20, TokenType.PERCENT to 20,
        TokenType.POW to 30,
        TokenType.INCREMENT to 35, TokenType.DECREMENT to 35,
        TokenType.NOT to 40, TokenType.BANG to 40,
        TokenType.DOT to 50, TokenType.QUESTION_DOT to 50,  // postfix
        TokenType.L_SQUARE to 55,  // postfix index
        TokenType.QUESTION to 15,  // ternary
        TokenType.QUESTION_QUESTION to 15,  // elvis
    )
}
```

### Key Methods

```kotlin
parse(): List<Stmt>
parseExpression(prec: Int = 0): Expr
parsePostfix(expr: Expr): Expr  // handles . ?[] calls
```

## IR Instructions (`IR.kt`)

```kotlin
sealed class IrInstr
Move(dst, src)
LoadImm(dst, constantIdx)
BinaryOp(dst, op, left, right)
UnaryOp(dst, op, right)
Label(name)
Jump(label)
JumpIfFalse(cond, label)
Return(value?)
Call(dst, func, argc)
LoadFunc(dst, funcIdx)
GetField(dst, obj, fieldName)
SetField(obj, fieldName, value)
NewInstance(dst, classIdx, argc)
GetIndex(dst, obj, index)
SetIndex(obj, index, value)
NewArray(dst, size)
BuildClass(dst, nameIdx, superclassIdx, methodCount)
// ... more
```

## AstLowerer (`AstLowerer.kt`)

```kotlin
class AstLowerer {
    fun lower(stmts: List<Stmt>): LoweredResult
    fun lowerStmt(stmt: Stmt)
    fun lowerExpr(expr: Expr, dst: IrTemp)
}

data class LoweredResult(
    val instrs: List<IrInstr>,
    val constants: List<Value>,
    val functions: List<FunctionChunk>,
    val classes: List<ClassChunk>
)
```

### Lowering Key Expressions

```kotlin
// SafeCallExpr
is Expr.SafeCallExpr -> {
    val nullLabel = freshLabel()
    val endLabel = freshLabel()
    val objReg = freshReg()
    lowerExpr(expr.obj, objReg)
    val nullConstIdx = addConstant(Value.Null)
    val cmpReg = freshReg()
    emit(LoadImm(cmpReg, nullConstIdx))
    emit(BinaryOp(cmpReg, TokenType.BANG_EQ, objReg, cmpReg))
    emit(JumpIfFalse(cmpReg, nullLabel))
    emit(GetField(dst, objReg, expr.name.lexeme))
    emit(Jump(endLabel))
    emit(Label(nullLabel))
    emit(LoadImm(dst, nullConstIdx))
    emit(Label(endLabel))
}

// ElvisExpr
is Expr.ElvisExpr -> {
    val endLabel = freshLabel()
    val tempReg = freshReg()
    lowerExpr(expr.left, tempReg)
    val nullConstIdx = addConstant(Value.Null)
    val cmpReg = freshReg()
    emit(LoadImm(cmpReg, nullConstIdx))
    emit(BinaryOp(cmpReg, TokenType.BANG_EQ, tempReg, cmpReg))
    emit(JumpIfFalse(cmpReg, endLabel))
    lowerExpr(expr.right, tempReg)
    emit(Label(endLabel))
    emit(Move(dst, tempReg))
}
```

## Register Allocation

### Liveness Analysis

```kotlin
class LivenessAnalyzer {
    fun analyze(instrs: List<IrInstr>): Map<IrTemp, List<Interval>>
}
```

### Linear Scan

```kotlin
class RegisterAllocator {
    fun allocate(ranges: Map<IrTemp, List<Interval>>): AllocResult
}
```

Returns:
```kotlin
data class AllocResult(
    val mapping: Map<IrTemp, Int>,  // temp → physical reg
    val spillSlotCount: Int
)
```

## Spill Insertion

```kotlin
class SpillInserter {
    fun insert(
        instrs: List<IrInstr>,
        alloc: AllocResult,
        ranges: Map<IrTemp, List<Interval>>
    ): List<IrInstr>
}
```

Inserts `SPILL(reg, slot)` before register pressure exceeds 16 and `UNSPILL(reg, slot)` after.

## Bytecode Compilation

```kotlin
class IrCompiler {
    fun compile(result: LoweredResult): Chunk
    companion object {
        fun optimizedSsaRoundTrip(instrs: List<IrInstr>, constants: List<Value>): LoweredResult
    }
}
```

Two-pass compilation:
1. Resolve all label offsets
2. Emit packed bytecode words

## InkScript (`InkScript.kt`)

```kotlin
class InkScript(
    val name: String,
    val chunk: Chunk
) {
    fun execute(context: InkContext): Value
}
```

## InkContext (`InkContext.kt`)

Runtime context interface providing built-in functionality:
- `prints: MutableList<String>` - accumulated print output
- `readInput(): String`
- `readFile(path: String): String`
- `writeFile(path: String, content: String)`
- `getJson(): JsonContext`
- `getDb(): DbContext`
