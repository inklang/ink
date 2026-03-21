# Unicode Escape Sequences — Design Spec

**Date:** 2026-03-21
**Status:** Design approved

## Overview

Add `\uXXXX` Unicode escape sequence support to string literals in Inklang.

## Current State

The Parser already has an `unescape()` function that handles escape sequences in string literals:
- `\n` → newline
- `\t` → tab
- `\r` → carriage return
- `\"` → double quote
- `\\` → backslash
- `\$` → literal dollar (in interpolated strings)

The Lexer passes raw characters through as-is, and `unescape()` processes them after tokenization.

## Design

### Implementation

Add `\uXXXX` handling to the existing `unescape()` function in `Parser.kt`:

```kotlin
'u'  -> {
    val hex = s.substring(i + 2, i + 6)
    if (hex.all { it in "0123456789abcdefABCDEF" }) {
        append(hex.toInt(16).toChar())
        i += 6
    } else {
        append(s[i]); i++
    }
}
```

**Behavior:**
- Reads exactly 4 hex digits after `\u`
- Converts to the corresponding Unicode character
- Advances cursor past the full 6-character sequence
- If hex digits are invalid or insufficient, passes through literally (or could error — following Kotlin's lenient approach here)

### Syntax Examples

```ink
print("hello\u0020world")   // "hello world"
print("\u4e2d\u6587")         // "中文"
print("emoji:\u1F600")        // "emoji 😀"
print("mixed:\n\u0041")       // newline + "A"
```

### Error Handling

| Input | Behavior |
|-------|----------|
| `\uXXXX` (valid) | Replaced with Unicode character |
| `\uGGGG` (invalid hex) | Passed through literally as `\uGGGG` |
| `\u` (truncated) | Passed through literally as `\u` + remaining chars |
| `\u1` (short) | Passed through literally |

This matches Kotlin's lenient approach for invalid escapes rather than hard erroring.

## Files to Modify

1. **`ink/src/main/kotlin/org/inklang/lang/Parser.kt`** — Add one `when` case to `unescape()`

## Testing

- Valid `\uXXXX` sequences (basic Latin, CJK, emoji, rare characters)
- Invalid hex digits (`\uGGGG`)
- Truncated sequences (`\u`, `\u1`, `\u12`)
- Mixed with other escapes (`"hello\n\u0041"`)
- In interpolated strings (`"hello ${name}\u0020suffix"`)
- In empty strings (`""` with `\u` inside)
