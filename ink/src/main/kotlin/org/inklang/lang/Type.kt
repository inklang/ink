package org.inklang.lang

/**
 * Type system for compile-time type checking.
 * Used by AnnotationChecker to validate async/await expressions.
 */
sealed class Type {
    /** Unknown or inferred type */
    object Unknown : Type()

    /** Integer type */
    object Int : Type()

    /** Float type */
    object Float : Type()

    /** Double type */
    object Double : Type()

    /** String type */
    object String : Type()

    /** Boolean type */
    object Bool : Type()

    /** Null type */
    object Null : Type()

    /** Function type with parameters and return type */
    data class Function(
        val params: List<Type>,
        val returnType: Type
    ) : Type()

    /** Class type */
    data class Class(val name: String) : Type()

    /**
     * Task type - represents an async operation that will produce a value of type T.
     * Used by async functions, await, and spawn expressions.
     */
    data class Task(val innerType: Type) : Type()

    /** Array type */
    data class Array(val elementType: Type) : Type()

    /** Map type */
    data class Map(val keyType: Type, val valueType: Type) : Type()

    /** Tuple type */
    data class Tuple(val elementTypes: List<Type>) : Type()

    /** Set type */
    data class Set(val elementType: Type) : Type()

    /** Deque type */
    data class Deque(val elementType: Type) : Type()

    /** Event object type */
    object Event : Type()

    /**
     * Checks if this type is a Task type.
     */
    fun isTask(): Boolean = this is Task

    /**
     * Checks if this type is callable (Function or NativeFunction).
     */
    fun isCallable(): Boolean = this is Function

    /**
     * Unwraps a Task type to get its inner type.
     * Returns Unknown if not a Task.
     */
    fun unwrapTask(): Type = if (this is Task) innerType else Unknown

    override fun toString(): String = when (this) {
        is Unknown -> "unknown"
        is Int -> "int"
        is Float -> "float"
        is Double -> "double"
        is String -> "string"
        is Bool -> "bool"
        is Null -> "null"
        is Function -> "fn(${params.joinToString(", ") { it.toString() }}) -> $returnType"
        is Class -> name
        is Task -> "Task<$innerType>"
        is Array -> "Array<$elementType>"
        is Map -> "Map<$keyType, $valueType>"
        is Tuple -> "(${elementTypes.joinToString(", ")})"
        is Set -> "Set<$elementType>"
        is Deque -> "Deque<$elementType>"
        is Event -> "Event"
    }
}
