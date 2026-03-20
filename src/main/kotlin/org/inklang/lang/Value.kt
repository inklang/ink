package org.inklang.lang

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
}

// === String method helpers ===

private fun expectString(v: Value): String = (v as? Value.String)?.value
    ?: error("Expected string, got $v")

private fun expectInt(v: Value): Int = (v as? Value.Int)?.value
    ?: error("Expected int, got $v")

private fun stringSplit(self: String, delim: String?): Value.Instance {
    val parts = if (delim == null || delim.isEmpty()) {
        self.map { it.toString() }
    } else {
        self.split(delim)
    }
    return Builtins.newArray(parts.map { Value.String(it) }.toMutableList())
}

fun getStringMethod(self: Value.String, name: String): Value? {
    return when (name) {
        "split" -> Value.NativeFunction { args ->
            stringSplit(self.value, args.getOrNull(0)?.let { expectString(it) })
        }
        "trim" -> Value.NativeFunction { _ ->
            Value.String(self.value.trim())
        }
        "contains" -> Value.NativeFunction { args ->
            Value.Boolean(self.value.contains(expectString(args[0])))
        }
        "replace" -> Value.NativeFunction { args ->
            Value.String(self.value.replace(expectString(args[0]), expectString(args[1]), false))
        }
        "replaceAll" -> Value.NativeFunction { args ->
            Value.String(self.value.replace(expectString(args[0]), expectString(args[1]), true))
        }
        "toUpperCase" -> Value.NativeFunction { _ ->
            Value.String(self.value.uppercase())
        }
        "toLowerCase" -> Value.NativeFunction { _ ->
            Value.String(self.value.lowercase())
        }
        "startsWith" -> Value.NativeFunction { args ->
            Value.Boolean(self.value.startsWith(expectString(args[0])))
        }
        "endsWith" -> Value.NativeFunction { args ->
            Value.Boolean(self.value.endsWith(expectString(args[0])))
        }
        "indexOf" -> Value.NativeFunction { args ->
            Value.Int(self.value.indexOf(expectString(args[0])).also { if (it < 0) return@NativeFunction Value.Int(-1) })
        }
        "length" -> Value.NativeFunction { _ ->
            Value.Int(self.value.length)
        }
        "isEmpty" -> Value.NativeFunction { _ ->
            if (self.value.isEmpty()) Value.Boolean.TRUE else Value.Boolean.FALSE
        }
        "isBlank" -> Value.NativeFunction { _ ->
            if (self.value.isBlank()) Value.Boolean.TRUE else Value.Boolean.FALSE
        }
        "chars" -> Value.NativeFunction { _ ->
            Builtins.newArray(self.value.map { Value.String(it.toString()) }.toMutableList())
        }
        "get" -> Value.NativeFunction { args ->
            val idx = expectInt(args[0])
            if (idx >= 0 && idx < self.value.length) {
                Value.String(self.value[idx].toString())
            } else {
                // TODO: throw StringIndexOutOfBoundsError instead of returning null
                Value.Null
            }
        }
        else -> null
    }
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

    private fun toDouble(v: Value): Double = when (v) {
        is Value.Int -> v.value.toDouble()
        is Value.Float -> v.value.toDouble()
        is Value.Double -> v.value
        else -> error("Expected number, got $v")
    }

    val MathClass = ClassDescriptor(
        name = "Math",
        superClass = null,
        methods = mapOf(
            "abs" to Value.NativeFunction { args ->
                val v = toDouble(args[1])  // args[0] is self
                Value.Double(kotlin.math.abs(v))
            },
            "min" to Value.NativeFunction { args ->
                val a = toDouble(args[1])  // args[0] is self
                val b = toDouble(args[2])
                Value.Double(kotlin.math.min(a, b))
            },
            "max" to Value.NativeFunction { args ->
                val a = toDouble(args[1])  // args[0] is self
                val b = toDouble(args[2])
                Value.Double(kotlin.math.max(a, b))
            },
            "pow" to Value.NativeFunction { args ->
                val base = toDouble(args[1])  // args[0] is self
                val exp = toDouble(args[2])
                Value.Double(Math.pow(base, exp))
            },
            "floor" to Value.NativeFunction { args ->
                val v = toDouble(args[1])  // args[0] is self
                Value.Double(kotlin.math.floor(v))
            },
            "ceil" to Value.NativeFunction { args ->
                val v = toDouble(args[1])  // args[0] is self
                Value.Double(kotlin.math.ceil(v))
            },
            "round" to Value.NativeFunction { args ->
                val v = toDouble(args[1])  // args[0] is self
                Value.Double(kotlin.math.round(v))
            }
        )
    )

    val RandomClass = ClassDescriptor(
        name = "Random",
        superClass = null,
        methods = mapOf(
            "random" to Value.NativeFunction { _ ->
                Value.Double(Math.random())
            }
        )
    )

    val ExceptionClass = ClassDescriptor(
        name = "Exception",
        superClass = null,
        methods = mapOf(
            "init" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                self.fields["message"] = args[1]
                Value.Null
            }
        )
    )

    val IoClass = ClassDescriptor(
        name = "Io",
        superClass = null,
        methods = mapOf(
            "print" to Value.NativeFunction { args ->
                // args[0] is self (io instance), args[1..] are the values to print
                val values = args.drop(1).map { valueToString(it) }
                print(values.joinToString(" "))
                Value.Null
            },
            "println" to Value.NativeFunction { args ->
                // args[0] is self (io instance), args[1..] are the values to print
                val values = args.drop(1).map { valueToString(it) }
                println(values.joinToString(" "))
                Value.Null
            }
        )
    )

    val DurationClass = ClassDescriptor(
        name = "Duration",
        superClass = null,
        methods = mapOf(
            "ofMillis" to Value.NativeFunction { args ->
                val millis = (args[1] as Value.Int).value
                Value.Instance(DurationClass, mutableMapOf("millis" to Value.Int(millis)))
            },
            "ofSeconds" to Value.NativeFunction { args ->
                val seconds = (args[1] as Value.Int).value
                Value.Instance(DurationClass, mutableMapOf("millis" to Value.Int(seconds * 1000)))
            },
            "millis" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                self.fields["millis"] as Value
            },
            "seconds" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val millis = (self.fields["millis"] as Value.Int).value
                Value.Int(millis / 1000)
            },
            "toMillis" to Value.NativeFunction { args ->
                val self = args[0] as Value.Instance
                val millis = (self.fields["millis"] as Value.Int).value
                Value.Int(millis)
            }
        )
    )

    val TimeClass = ClassDescriptor(
        name = "Time",
        superClass = null,
        methods = mapOf(
            "now" to Value.NativeFunction { _ ->
                // Returns current time in milliseconds
                Value.Int(System.currentTimeMillis().toInt())
            },
            "sleep" to Value.NativeFunction { args ->
                val millis = (args[1] as Value.Int).value.toLong()
                Thread.sleep(millis)
                Value.Null
            }
        )
    )

    fun newMath(): Value.Instance = Value.Instance(MathClass, mutableMapOf())
    fun newRandom(): Value.Instance = Value.Instance(RandomClass, mutableMapOf())
    fun newIo(): Value.Instance = Value.Instance(IoClass, mutableMapOf())
    fun newTime(): Value.Instance = Value.Instance(TimeClass, mutableMapOf())
}