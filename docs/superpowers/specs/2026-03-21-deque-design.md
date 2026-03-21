# Standard Library: Deque — Design

**Date:** 2026-03-21
**Status:** Approved

## Overview

Add a `Deque<T>` (double-ended queue) type to Inklang — a collection with O(1) insertion and removal at both ends. Backed by Kotlin's `ArrayDeque` (ring buffer).

## Syntax

| Construction | Syntax |
|--------------|--------|
| Factory | `Deque(item1, item2, ...)` |
| Empty | `Deque()` |

No literal syntax. Construction via factory only.

## Implementation

### 1. Storage Wrapper (`Value.kt`)

```kotlin
data class InternalDeque(val items: ArrayDeque<Value> = ArrayDeque()) : Value() {
    override fun toString() = items.joinToString(", ", "Deque(", ")")
}
```

### 2. Iterator Class

```kotlin
DequeIteratorClass:
  fields:
    __items (InternalList)    — snapshot as ArrayIteratorClass uses
    current (Int)
  hasNext → current < __items.items.size
  next    → __items.items[current++]
```

### 3. DequeClass Methods

| Method | Signature | Behavior |
|--------|-----------|----------|
| `init` | `init(self, ...args)` | `__deque = InternalDeque()`; add all `args[1..]` to right |
| `push_left` | `push_left(item)` | `__deque.items.addFirst(item)`; return `Null` |
| `push_right` | `push_right(item)` | `__deque.items.addLast(item)`; return `Null` |
| `pop_left` | `pop_left()` | `__deque.items.removeFirst()`; `Value.Null` if empty |
| `pop_right` | `pop_right()` | `__deque.items.removeLast()`; `Value.Null` if empty |
| `peek_left` | `peek_left()` | `__deque.items.first()`; `Value.Null` if empty |
| `peek_right` | `peek_right()` | `__deque.items.last()`; `Value.Null` if empty |
| `size` | `size()` | `Int(__deque.items.size)` |
| `is_empty` | `is_empty()` | `Boolean(__deque.items.isEmpty())` |
| `has` | `has(item)` | `Boolean(__deque.items.contains(item))` |
| `clear` | `clear()` | `__deque.items.clear()`; return `Null` |
| `iter` | `iter()` | `DequeIteratorClass` instance (snapshot current state) |

### 4. VM Globals

```kotlin
globals = mutableMapOf(
    "Deque" to Value.Class(Builtins.DequeClass),
    ...
)
```

### 5. Lowering

`Deque(expr1, expr2, ...)` uses existing `IrInstr.NewInstance` — no new IR instruction needed. Same pattern as `Set` and `Tuple`.

### 6. Error Behavior

| Operation | Invalid Input | Behavior |
|-----------|--------------|----------|
| `pop_left` | empty deque | returns `Value.Null` |
| `pop_right` | empty deque | returns `Value.Null` |
| `peek_left` | empty deque | returns `Value.Null` |
| `peek_right` | empty deque | returns `Value.Null` |

No exceptions thrown — consistent with existing collection behavior.

## Files Touched

- `ink/src/main/kotlin/org/inklang/lang/Value.kt` — `InternalDeque`, `DequeIteratorClass`, `DequeClass`
- `ink/src/main/kotlin/org/inklang/ast/VM.kt` — register `Deque` in globals

## Out of Scope

- Literal syntax `{...}` (reserved for potential future Set)
- Random access `get(index)` / `set(index)` — use `List` for that
- Capacity management
- `to_list()` / `to_set()` conversion methods
