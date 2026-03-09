package org.aincraft.lang

data class ClassDescriptor(
    val name: String,
    val superClass: ClassDescriptor?,
    val methods: Map<String, Value>  // Value.NativeFunction or Value.Function
)

sealed class Value {

    data class Instance(
        val clazz: ClassDescriptor,
        val fields: MutableMap<kotlin.String, Value> = mutableMapOf()
    ) : Value()

    data class Class(val descriptor: ClassDescriptor) : Value()

    data class List(val value: MutableList<Value>) : Value()

    data class Range(val start: kotlin.Int, val end: kotlin.Int) : Value()

    data class Iterator(val iterable: Value) : Value() {
        var index: kotlin.Int = 0
    }

    data class Map(val entries: MutableMap<Value, Value> = mutableMapOf()) : Value()

    data class Boolean(val value: kotlin.Boolean) : Value() {
        companion object {
            val TRUE = Boolean(true)
            val FALSE = Boolean(false)
        }
    }

    data class Int(val value: kotlin.Int) : Value() {
        override fun toString() = value.toString()
    }

    data class Float(val value: kotlin.Float) : Value() {
        override fun toString() = value.toString()
    }

    data class Double(val value: kotlin.Double) : Value() {
        override fun toString() = value.toString()
    }

    data class String(val value: kotlin.String) : Value() {
        override fun toString() = value
    }

    object Null : Value() {
        override fun toString() = "null"
    }

    data class Function(
        val chunk: Chunk,
        val arity: kotlin.Int = 0,  // Number of required parameters (without defaults)
        val defaults: FunctionDefaults? = null  // Default value chunk indices
    ) : Value()
    data class NativeFunction(val fn: (kotlin.collections.List<Value>) -> Value) : Value()

    /** A method bound to an instance - the instance is prepended as the first argument on CALL */
    data class BoundMethod(
        val instance: Instance,
        val method: Value  // Either Function or NativeFunction
    ) : Value()
}

object Builtins {
    val RangeClass = ClassDescriptor(
        name = "Range",
        superClass = null,
        methods = mapOf(
            "iter" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val start = (self.fields["start"] as Value.Int).value
                val end = (self.fields["end"] as Value.Int).value
                Value.Instance(
                    IteratorClass,
                    mutableMapOf(
                        "current" to Value.Int(start),
                        "end" to Value.Int(end)
                    )
                )
            }
        )
    )

    val IteratorClass = ClassDescriptor(
        name = "Iterator",
        superClass = null,
        methods = mapOf(
            "hasNext" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val current = (self.fields["current"] as Value.Int).value
                val end = (self.fields["end"] as Value.Int).value
                if (current <= end) Value.Boolean.TRUE else Value.Boolean.FALSE
            },
            "next" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val current = (self.fields["current"] as Value.Int).value
                self.fields["current"] = Value.Int(current + 1)
                Value.Int(current)
            }
        )
    )

    fun newRange(start: kotlin.Int, end: kotlin.Int): Value.Instance =
        Value.Instance(
            RangeClass,
            mutableMapOf(
                "start" to Value.Int(start),
                "end" to Value.Int(end)
            )
        )
}