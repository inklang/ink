# Inklang VM Internals

> Register-based bytecode virtual machine execution engine.

## VM Architecture

### Call Frame
```kotlin
data class CallFrame(
    val function: InkFunction,
    val chunk: Chunk,
    val ip: Int,                      // instruction pointer
    val registers: Array<Value>,       // 16 physical registers
    val spillSlots: Array<Value?>,    // spilled register values
    val locals: Array<Value?>          // local variables
)
```

### Registers
- 16 physical registers: R0-R15
- R0 typically holds return value
- R1-R14 general purpose
- R15 reserved for special purposes (e.g., instance in method calls)

### Stack
- Separate operand stack for certain operations
- Used for: function arguments, array elements, temporary values

## Instruction Execution

### Main Loop

```kotlin
fun execute(chunk: Chunk, context: InkContext): Value {
    var frames = listOf(CallFrame(...))
    while (frames.isNotEmpty()) {
        val frame = frames.last()
        val opcode = frame.chunk.code[frame.ip]
        frame.ip++
        when (opcode) {
            OpCode.LOAD_IMM -> loadImm(frame)
            OpCode.MOVE -> move(frame)
            OpCode.ADD -> add(frame)
            OpCode.CALL -> call(frame, context)
            // ... etc
        }
    }
}
```

## Opcodes Detail

### Loading and Moving

| Opcode | Args | Description |
|--------|------|-------------|
| LOAD_IMM | dst, constIdx | Load constant at constIdx into dst |
| MOVE | dst, src | Copy src value to dst |
| LOAD_GLOBAL | dst, nameIdx | Load global variable |
| STORE_GLOBAL | nameIdx, src | Store to global variable |
| POP | - | Pop top of stack into discarded value |

### Arithmetic

| Opcode | Args | Description |
|--------|------|-------------|
| ADD | dst, a, b | dst = a + b |
| SUB | dst, a, b | dst = a - b |
| MUL | dst, a, b | dst = a * b |
| DIV | dst, a, b | dst = a / b |
| NEG | dst, a | dst = -a |
| MOD | dst, a, b | dst = a % b |
| POW | dst, a, b | dst = a ^ b |

### Comparison

| Opcode | Args | Description |
|--------|------|-------------|
| EQ | dst, a, b | dst = a == b |
| NEQ | dst, a, b | dst = a != b |
| LT | dst, a, b | dst = a < b |
| LTE | dst, a, b | dst = a <= b |
| GT | dst, a, b | dst = a > b |
| GTE | dst, a, b | dst = a >= b |
| NOT | dst, a | dst = !a |

### Control Flow

| Opcode | Args | Description |
|--------|------|-------------|
| JUMP | offset | ip += offset |
| JUMP_IF_FALSE | cond, offset | if (!cond) ip += offset |
| RETURN | value? | Return from function |
| BREAK | - | Exit loop |
| NEXT | - | Continue to next iteration |

### Function Calls

| Opcode | Args | Description |
|--------|------|-------------|
| LOAD_FUNC | dst, funcIdx | Load function at idx into dst |
| CALL | func, argc | Call func with argc arguments |
| PUSH_ARG | value | Push argument for next call |

### Object Operations

| Opcode | Args | Description |
|--------|------|-------------|
| GET_FIELD | dst, obj, fieldIdx | dst = obj.fields[fieldIdx] |
| SET_FIELD | obj, fieldIdx, value | obj.fields[fieldIdx] = value |
| NEW_INSTANCE | dst, classIdx, argc | dst = new Class(argc) |
| IS_TYPE | dst, value, typeIdx | dst = value is Type |
| HAS | dst, obj, fieldIdx | dst = obj has field |

### Collections

| Opcode | Args | Description |
|--------|------|-------------|
| NEW_ARRAY | dst, size | dst = new Array(size) |
| GET_INDEX | dst, arr, idx | dst = arr[idx] |
| SET_INDEX | arr, idx, value | arr[idx] = value |
| RANGE | dst, start, end | dst = start..end |

### Class Building

| Opcode | Args | Description |
|--------|------|-------------|
| BUILD_CLASS | dst, name, superclass, methods | dst = Class(name, superclass, methods) |

### Spilling

| Opcode | Args | Description |
|--------|------|-------------|
| SPILL | slot, reg | spillSlots[slot] = registers[reg] |
| UNSPILL | dst, slot | registers[dst] = spillSlots[slot] |

### Exception Handling

| Opcode | Args | Description |
|--------|------|-------------|
| THROW | value | throw value |

## Class System

### Method Dispatch
- Methods are stored in class metadata
- `NEW_INSTANCE` creates instance with method references
- Method call sets R15 to instance (self)

### Inheritance
- Single inheritance only
- Superclass lookup on method/field access
- `IS_TYPE` checks inheritance chain

### Initializer
- `init` method called automatically on `NEW_INSTANCE`
- Can have multiple init methods with different arities

## Built-in Types (Value.kt)

```kotlin
sealed class Value {
    data class IntVal(val value: kotlin.Int) : Value()
    data class FloatVal(val value: kotlin.Float) : Value()
    data class DoubleVal(val value: kotlin.Double) : Value()
    data class StringVal(val value: kotlin.String) : Value()
    data class BoolVal(val value: kotlin.Boolean) : Value()
    object Null : Value()
    data class ArrayVal(val elements: MutableList<Value>) : Value()
    data class SetVal(val elements: MutableSet<Value>) : Value()
    data class MapVal(val entries: MutableMap<Value, Value>) : Value()
    data class RangeVal(val start: Int, val end: Int) : Value()
    data class InstanceVal(val klass: ClassInfo, val fields: MutableMap<String, Value>) : Value()
    data class BoundMethod(val receiver: InstanceVal, val method: FunctionInfo) : Value()
    data class FunctionVal(val chunk: Chunk, val name: String) : Value()
    // Internal use
    data class BreakVal(val targetIp: Int) : Value()  // for break in nested loops
    data class NextVal(val targetIp: Int) : Value()   // for next in nested loops
}
```

## Iterator Protocol

For loop desugars to:
```
let __iter = collection.iter()
while (__iter.hasNext()) {
    let x = __iter.next()
    // body
}
```

Classes must implement:
- `iter()` → returns iterator
- `hasNext()` → bool
- `next()` → next value

## String Interpolation

Compiled form:
```
"Hello ${name}!" → "Hello " + name + "!"
```

Lexer produces `INTERPOLATION_START`, `INTERPOLATION_END` tokens.
Parser desugars to binary `+` operations at parse time.

## String Escape Sequences

Handled during lexing:
- `\n` → newline
- `\t` → tab
- `\\` → backslash
- `\"` → double quote

## Example Execution

For script:
```ink
let a = 5;
let b = 3;
print(a + b);
```

Bytecode:
```
LOAD_IMM R0, const_5     ; R0 = 5
STORE_GLOBAL "a", R0
LOAD_IMM R0, const_3      ; R0 = 3
STORE_GLOBAL "b", R0
LOAD_GLOBAL R1, "a"       ; R1 = a
LOAD_GLOBAL R2, "b"       ; R2 = b
ADD R0, R1, R2            ; R0 = R1 + R2
CALL @print, 1            ; print(R0)
RETURN
```

## Error Handling

- `THROW` opcode throws Value as exception
- Try/catch implemented via stack of exception handlers
- Uncaught exceptions propagate up call frames
- VM catches top-level exceptions for clean exit
