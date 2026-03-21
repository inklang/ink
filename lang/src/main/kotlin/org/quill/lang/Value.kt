package org.quill.lang

data class ClassDescriptor(
    val name: String,
    val superClass: ClassDescriptor?,
    val methods: Map<String, Value>,  // Value.NativeFunction or Value.Function
    val readOnly: Boolean = false
)

sealed class Value {

    data class Instance(
        val clazz: ClassDescriptor,
        val fields: MutableMap<kotlin.String, Value> = mutableMapOf()
    ) : Value()

    data class Class(val descriptor: ClassDescriptor) : Value()

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

    /** Wrapper for internal MutableList storage (used by Array class) */
    data class InternalList(val items: MutableList<Value>) : Value() {
        override fun toString() = items.joinToString(", ", "[", "]")
    }

    /** Wrapper for internal MutableMap storage (used by Map class) */
    data class InternalMap(val entries: MutableMap<Value, Value> = mutableMapOf()) : Value() {
        override fun toString() = entries.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }
    }

    /** Wrapper for internal MutableSet storage (used by Set class) */
    data class InternalSet(val entries: MutableSet<Value> = mutableSetOf()) : Value() {
        override fun toString() = entries.joinToString(", ", "{", "}")
    }

    /** Wrapper for internal List storage (used by Tuple class) — immutable */
    data class InternalTuple(val items: List<Value>) : Value() {
        override fun toString() = items.joinToString(", ", "(", ")")
    }

    /** Event object passed to handlers */
    data class EventObject(
        val eventName: String,
        val cancellable: kotlin.Boolean,
        var cancelled: kotlin.Boolean = false,
        val data: List<Value?>
    ) : Value()

    /** Event handler registered via `on` */
    data class EventHandler(
        val eventName: String,
        val handlerFunc: Value.Function,
        val eventParamName: String,
        val dataParamNames: List<String>
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

    val ArrayClass = ClassDescriptor(
        name = "Array",
        superClass = null,
        methods = mapOf(
            "size" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__items"] as Value.InternalList).items
                Value.Int(items.size)
            },
            "push" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__items"] as Value.InternalList).items
                items.add(args[1])
                Value.Null
            },
            "get" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__items"] as Value.InternalList).items
                val idx = (args[1] as Value.Int).value
                items.getOrElse(idx) { Value.Null }
            },
            "set" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__items"] as Value.InternalList).items
                val idx = (args[1] as Value.Int).value
                if (idx >= 0 && idx < items.size) {
                    items[idx] = args[2]
                }
                Value.Null
            },
            "iter" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val internalList = self.fields["__items"] as Value.InternalList
                Value.Instance(
                    ArrayIteratorClass,
                    mutableMapOf(
                        "__items" to internalList,
                        "current" to Value.Int(0)
                    )
                )
            }
        )
    )

    val ArrayIteratorClass = ClassDescriptor(
        name = "ArrayIterator",
        superClass = null,
        methods = mapOf(
            "hasNext" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__items"] as Value.InternalList).items
                val current = (self.fields["current"] as Value.Int).value
                if (current < items.size) Value.Boolean.TRUE else Value.Boolean.FALSE
            },
            "next" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__items"] as Value.InternalList).items
                val current = (self.fields["current"] as Value.Int).value
                self.fields["current"] = Value.Int(current + 1)
                items.getOrElse(current) { Value.Null }
            }
        )
    )

    fun newArray(elements: MutableList<Value>): Value.Instance =
        Value.Instance(
            ArrayClass,
            mutableMapOf("__items" to Value.InternalList(elements))
        )

    val MapClass = ClassDescriptor(
        name = "Map",
        superClass = null,
        methods = mapOf(
            "init" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                self.fields["__entries"] = Value.InternalMap()
                Value.Null
            },
            "get" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalMap).entries
                entries[args[1]] ?: Value.Null
            },
            "set" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalMap).entries
                entries[args[1]] = args[2]
                Value.Null
            },
            "size" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalMap).entries
                Value.Int(entries.size)
            },
            "keys" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalMap).entries
                newArray(entries.keys.toMutableList())
            },
            "values" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalMap).entries
                newArray(entries.values.toMutableList())
            },
            "delete" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalMap).entries
                entries.remove(args[1])
                Value.Null
            }
        )
    )

    fun newMap(entries: MutableMap<Value, Value> = mutableMapOf()): Value.Instance =
        Value.Instance(
            MapClass,
            mutableMapOf("__entries" to Value.InternalMap(entries))
        )

    val EnumValueClass = ClassDescriptor(
        name = "EnumValue",
        superClass = null,
        methods = emptyMap()
    )

    val EnumNamespaceClass = ClassDescriptor(
        name = "EnumNamespace",
        superClass = null,
        methods = emptyMap()
    )

    val SetIteratorClass = ClassDescriptor(
        name = "SetIterator",
        superClass = null,
        methods = mapOf(
            "hasNext" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__items"] as Value.InternalList).items
                val current = (self.fields["current"] as Value.Int).value
                if (current < items.size) Value.Boolean.TRUE else Value.Boolean.FALSE
            },
            "next" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__items"] as Value.InternalList).items
                val current = (self.fields["current"] as Value.Int).value
                self.fields["current"] = Value.Int(current + 1)
                items.getOrElse(current) { Value.Null }
            }
        )
    )

    val SetClass = ClassDescriptor(
        name = "Set",
        superClass = null,
        methods = mapOf(
            "init" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                self.fields["__entries"] = Value.InternalSet()
                // args[1..] are constructor arguments to add to the set
                val entries = (self.fields["__entries"] as Value.InternalSet).entries
                for (i in 1 until args.size) {
                    entries.add(args[i])
                }
                Value.Null
            },
            "add" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalSet).entries
                entries.add(args[1])
                Value.Null
            },
            "has" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalSet).entries
                if (entries.contains(args[1])) Value.Boolean.TRUE else Value.Boolean.FALSE
            },
            "remove" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalSet).entries
                entries.remove(args[1])
                Value.Null
            },
            "size" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalSet).entries
                Value.Int(entries.size)
            },
            "clear" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalSet).entries
                entries.clear()
                Value.Null
            },
            "delete" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val entries = (self.fields["__entries"] as Value.InternalSet).entries
                entries.remove(args[1])
                Value.Null
            },
            "iter" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val internalSet = self.fields["__entries"] as Value.InternalSet
                Value.Instance(
                    SetIteratorClass,
                    mutableMapOf(
                        "__entries" to internalSet,
                        "__items" to Value.InternalList(internalSet.entries.toMutableList()),
                        "current" to Value.Int(0)
                    )
                )
            }
        )
    )

    fun newSet(entries: MutableSet<Value> = mutableSetOf()): Value.Instance =
        Value.Instance(
            SetClass,
            mutableMapOf("__entries" to Value.InternalSet(entries))
        )

    val TupleClass = ClassDescriptor(
        name = "Tuple",
        superClass = null,
        methods = mapOf(
            "init" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                // args[1..] are constructor arguments
                val items = if (args.size > 1) {
                    args.subList(1, args.size).toList()
                } else {
                    emptyList()
                }
                self.fields["__tuple"] = Value.InternalTuple(items)
                Value.Null
            },
            "size" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__tuple"] as Value.InternalTuple).items
                Value.Int(items.size)
            },
            "get" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__tuple"] as Value.InternalTuple).items
                val idx = (args[1] as Value.Int).value
                items.getOrElse(idx) { Value.Null }
            },
            "has" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val items = (self.fields["__tuple"] as Value.InternalTuple).items
                if (items.contains(args[1])) Value.Boolean.TRUE else Value.Boolean.FALSE
            },
            "iter" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val internalTuple = self.fields["__tuple"] as Value.InternalTuple
                Value.Instance(
                    ArrayIteratorClass,
                    mutableMapOf(
                        "__items" to Value.InternalList(internalTuple.items.toMutableList()),
                        "current" to Value.Int(0)
                    )
                )
            }
        )
    )

    fun newTuple(items: List<Value>): Value.Instance =
        Value.Instance(
            TupleClass,
            mutableMapOf("__tuple" to Value.InternalTuple(items))
        )
}