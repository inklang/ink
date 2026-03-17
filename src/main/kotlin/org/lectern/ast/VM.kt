package org.lectern.lang

import org.lectern.lang.Value.*

fun valueToString(v: Value): String = when (v) {
    is Value.Instance -> {
        val items = v.fields["__items"]
        val entries = v.fields["__entries"]
        when {
            items is Value.InternalList -> items.toString()
            entries is Value.InternalMap -> entries.toString()
            v.fields.containsKey("name") && v.clazz.name == "EnumValue" -> v.fields["name"].toString()
            else -> v.toString()
        }
    }
    else -> v.toString()
}

class VM {
    val globals = mutableMapOf<String, Value>(
        "Array" to Value.Class(Builtins.ArrayClass),
        "Map" to Value.Class(Builtins.MapClass),
        "EnumValue" to Value.Class(Builtins.EnumValueClass),
        "EnumNamespace" to Value.Class(Builtins.EnumNamespaceClass),
        "print" to Value.NativeFunction { args ->
            println(args.joinToString(" ") { valueToString(it) })
            Value.Null
        },
    )

    data class CallFrame(
        val chunk: Chunk,
        var ip: Int = 0,
        val regs: Array<Value?> = arrayOfNulls(16),
        var returnDst: Int = 0,  // Where to store the return value in caller
        val argBuffer: ArrayDeque<Value> = ArrayDeque()  // Staging buffer for PUSH_ARG
    ) {
        val spills: Array<Value?> = arrayOfNulls(chunk.spillSlotCount)
    }

    fun execute(chunk: Chunk) {
        val frames = ArrayDeque<CallFrame>()
        frames.addLast(CallFrame(chunk))

        while (frames.isNotEmpty()) {
            val frame = frames.last()
            if (frame.ip >= frame.chunk.code.size) {
                frames.removeLast()
                continue
            }

            val word   = frame.chunk.code[frame.ip++]
            val opcode = OpCode.entries.find { it.code == (word and 0xFF).toByte() }
                ?: error("Unknown opcode: ${word and 0xFF}")
            val dst    = (word shr 8)  and 0x0F
            val src1   = (word shr 12) and 0x0F
            val src2   = (word shr 16) and 0x0F
            val imm    = (word shr 20) and 0xFFF

            when (opcode) {
                OpCode.LOAD_IMM  -> frame.regs[dst] = frame.chunk.constants[imm]

                OpCode.LOAD_GLOBAL  -> frame.regs[dst] = globals[frame.chunk.strings[imm]]
                    ?: error("Undefined global: ${frame.chunk.strings[imm]}")
                OpCode.STORE_GLOBAL -> globals[frame.chunk.strings[imm]] = frame.regs[src1]!!

                OpCode.MOVE -> frame.regs[dst] = frame.regs[src1]

                OpCode.ADD -> {
                    val a = frame.regs[src1]!!
                    val b = frame.regs[src2]!!
                    // String concatenation if either operand is a string
                    if (a is Value.String || b is Value.String) {
                        frame.regs[dst] = Value.String(a.toString() + b.toString())
                    } else {
                        frame.regs[dst] = binop(a, b) { x, y -> x + y }
                    }
                }
                OpCode.SUB -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a - b }
                OpCode.MUL -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a * b }
                OpCode.DIV -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a / b }
                OpCode.MOD -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a % b }
                OpCode.POW -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> Math.pow(a, b) }
                OpCode.NEG -> frame.regs[dst] = negate(frame.regs[src1]!!)

                OpCode.NOT -> frame.regs[dst] = if (isFalsy(frame.regs[src1]!!)) Value.Boolean.TRUE else Value.Boolean.FALSE
                OpCode.EQ  -> frame.regs[dst] = if (frame.regs[src1] == frame.regs[src2]) Value.Boolean.TRUE else Value.Boolean.FALSE
                OpCode.NEQ -> frame.regs[dst] = if (frame.regs[src1] != frame.regs[src2]) Value.Boolean.TRUE else Value.Boolean.FALSE
                OpCode.LT  -> frame.regs[dst] = cmp(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a < b }
                OpCode.LTE -> frame.regs[dst] = cmp(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a <= b }
                OpCode.GT  -> frame.regs[dst] = cmp(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a > b }
                OpCode.GTE -> frame.regs[dst] = cmp(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a >= b }

                OpCode.JUMP          -> frame.ip = imm
                OpCode.JUMP_IF_FALSE -> if (isFalsy(frame.regs[src1]!!)) frame.ip = imm

                OpCode.LOAD_FUNC -> {
                    val funcChunk = frame.chunk.functions[imm]
                    val defaults = if (imm < frame.chunk.functionDefaults.size) {
                        frame.chunk.functionDefaults[imm]
                    } else {
                        null
                    }
                    // Count required params (those without defaults)
                    val requiredArity: kotlin.Int = defaults?.defaultChunks?.count { it == null } ?: 0
                    frame.regs[dst] = Function(funcChunk, requiredArity, defaults)
                }
                OpCode.CALL -> {
                    // Drain args from the arg buffer
                    val passedArgCount = imm
                    val args = (0 until passedArgCount).map { i ->
                        frame.argBuffer.removeFirstOrNull() ?: error("Missing argument $i in arg buffer")
                    }
                    when (val func = frame.regs[src1]) {
                        is Value.Function -> {
                            val totalParams = func.defaults?.defaultChunks?.size ?: passedArgCount
                            val finalArgs = fillDefaultArgs(args, func, totalParams, frame, frames)
                            val newFrame = CallFrame(func.chunk)
                            newFrame.returnDst = dst  // Store where to put return value
                            finalArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
                            frames.addLast(newFrame)
                        }
                        is Value.NativeFunction -> {
                            frame.regs[dst] = func.fn(args)
                        }
                        is Value.BoundMethod -> {
                            // Prepend the bound instance as the first argument
                            val boundArgs = listOf(func.instance) + args
                            when (val method = func.method) {
                                is Value.Function -> {
                                    val totalParams = method.defaults?.defaultChunks?.size ?: boundArgs.size
                                    val finalArgs = fillDefaultArgs(boundArgs, method, totalParams, frame, frames)
                                    val newFrame = CallFrame(method.chunk)
                                    newFrame.returnDst = dst
                                    finalArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
                                    frames.addLast(newFrame)
                                }
                                is Value.NativeFunction -> {
                                    frame.regs[dst] = method.fn(boundArgs)
                                }
                                else -> error("BoundMethod wraps non-callable: $method")
                            }
                        }
                        is Value.Class -> {
                            // Calling a Class value creates a new instance (constructor call)
                            val instance = Value.Instance(func.descriptor)
                            frame.regs[dst] = instance

                            // Look up and call init if it exists
                            val initMethod = lookupMethod(instance, "init")
                            if (initMethod != null) {
                                val boundArgs = listOf(instance) + args
                                when (initMethod) {
                                    is Value.Function -> {
                                        val totalParams = initMethod.defaults?.defaultChunks?.size ?: boundArgs.size
                                        val finalArgs = fillDefaultArgs(boundArgs, initMethod, totalParams, frame, frames)
                                        val newFrame = CallFrame(initMethod.chunk)
                                        newFrame.returnDst = dst
                                        finalArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
                                        frames.addLast(newFrame)
                                    }
                                    is Value.NativeFunction -> {
                                        initMethod.fn(boundArgs)
                                    }
                                    else -> error("init is not callable: $initMethod")
                                }
                            }
                        }
                        else -> error("Cannot call non-function: ${frame.regs[src1]}")
                    }
                }
                OpCode.PUSH_ARG -> {
                    frame.argBuffer.addLast(frame.regs[src1] ?: error("Null value in PUSH_ARG at reg $src1 (ip=${frame.ip - 1}, chunk.constants=${frame.chunk.constants.take(5)}, strings=${frame.chunk.strings.take(3)})"))
                }
                OpCode.RETURN -> {
                    val returnVal = frame.regs[src1]
                    val returnDst = frame.returnDst
                    frames.removeLast()
                    if (frames.isNotEmpty()) {
                        val caller = frames.last()
                        caller.regs[returnDst] = returnVal
                    }
                }

                OpCode.POP   -> { /* no-op in register VM */ }
                OpCode.BREAK -> error("BREAK outside loop")
                OpCode.NEXT  -> error("NEXT outside loop")
                OpCode.NEW_ARRAY -> {
                    val count = imm
                    val elements = (0 until count).map { i ->
                        frame.argBuffer.removeFirstOrNull() ?: error("Missing array element $i in arg buffer")
                    }
                    frame.regs[dst] = Builtins.newArray(elements.toMutableList())
                }
                OpCode.GET_FIELD -> {
                    val obj = frame.regs[src1] ?: error("Cannot get field on null")
                    val fieldName = frame.chunk.strings[imm]
                    frame.regs[dst] = when (obj) {
                        is Value.Instance -> {
                            // Check fields first
                            obj.fields[fieldName]?.let { it }
                                // Then walk the class hierarchy for methods
                                ?: lookupMethod(obj, fieldName)
                                    ?.let { Value.BoundMethod(obj, it) }
                                ?: error("Instance has no field '$fieldName'")
                        }
                        else -> error("Cannot get field on ${obj::class.simpleName}")
                    }
                }
                OpCode.SET_FIELD -> {
                    val obj = frame.regs[src1] as? Value.Instance
                        ?: error("Cannot set field on non-instance")
                    if (obj.clazz.readOnly) {
                        error("Cannot modify read-only ${obj.clazz.name} field")
                    }
                    val fieldName = frame.chunk.strings[imm]
                    obj.fields[fieldName] = frame.regs[src2] ?: Value.Null
                }
                OpCode.NEW_INSTANCE -> {
                    val classVal = frame.regs[src1] as? Value.Class
                        ?: error("Cannot create instance of non-class: ${frame.regs[src1]}")
                    // Drain constructor args from arg buffer
                    val args = (0 until imm).map { i ->
                        frame.argBuffer.removeFirstOrNull() ?: error("Missing argument $i in arg buffer")
                    }

                    // Allocate the instance
                    val instance = Value.Instance(classVal.descriptor)
                    frame.regs[dst] = instance

                    // Look up and call init if it exists
                    val initMethod = lookupMethod(instance, "init")
                    if (initMethod != null) {
                        val boundArgs = listOf(instance) + args
                        when (initMethod) {
                            is Value.Function -> {
                                val newFrame = CallFrame(initMethod.chunk)
                                newFrame.returnDst = dst
                                boundArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
                                frames.addLast(newFrame)
                            }
                            is Value.NativeFunction -> {
                                initMethod.fn(boundArgs)
                            }
                            else -> error("init is not callable: $initMethod")
                        }
                    }
                }
                OpCode.IS_TYPE -> {
                    val value = frame.regs[src1]
                    val typeName = frame.chunk.strings[imm]
                    val result = when (value) {
                        is Value.Int -> typeName == "int"
                        is Value.Float -> typeName == "float"
                        is Value.Double -> typeName == "double"
                        is Value.String -> typeName == "string"
                        is Value.Boolean -> typeName == "bool"
                        is Value.Instance -> isInTypeChain(value.clazz, typeName)
                        is Value.Class -> value.descriptor.name == typeName
                        else -> false
                    }
                    frame.regs[dst] = if (result) Value.Boolean.TRUE else Value.Boolean.FALSE
                }
                OpCode.BUILD_CLASS -> {
                    val classInfo = frame.chunk.classes[imm]
                    // Resolve superclass from globals if specified
                    val superClassDescriptor = classInfo.superClass?.let { superName ->
                        (globals[superName] as? Value.Class)?.descriptor
                    }
                    // Build method map with Function values
                    val methods = classInfo.methods.mapValues { (_, funcIdx) ->
                        Value.Function(frame.chunk.functions[funcIdx])
                    }
                    val descriptor = ClassDescriptor(classInfo.name, superClassDescriptor, methods)
                    frame.regs[dst] = Value.Class(descriptor)
                }
                OpCode.RANGE -> {
                    val start = (frame.regs[src1] as? Value.Int)?.value
                        ?: error("Range start must be int: ${frame.regs[src1]}")
                    val end = (frame.regs[src2] as? Value.Int)?.value
                        ?: error("Range end must be int: ${frame.regs[src2]}")
                    frame.regs[dst] = Builtins.newRange(start, end)
                }
                OpCode.GET_INDEX -> {
                    val obj = frame.regs[src1]!!
                    when (obj) {
                        is Value.Instance -> {
                            val getMethod = lookupMethod(obj, "get")
                                ?: error("Instance has no 'get' method for indexing")
                            val index = frame.regs[src2]!!
                            when (getMethod) {
                                is Value.NativeFunction -> frame.regs[dst] = getMethod.fn(listOf(obj, index))
                                else -> error("get method is not a native function")
                            }
                        }
                        else -> error("Cannot index: ${obj::class.simpleName}")
                    }
                }
                OpCode.SET_INDEX -> {
                    val obj = frame.regs[src1]!!
                    when (obj) {
                        is Value.Instance -> {
                            val setMethod = lookupMethod(obj, "set")
                                ?: error("Instance has no 'set' method for indexing")
                            val index = frame.regs[src2]!!
                            val value = frame.regs[imm] ?: Value.Null
                            when (setMethod) {
                                is Value.NativeFunction -> setMethod.fn(listOf(obj, index, value))
                                else -> error("set method is not a native function")
                            }
                        }
                        else -> error("Cannot index: ${obj::class.simpleName}")
                    }
                }
                OpCode.SPILL   -> frame.spills[imm] = frame.regs[src1]
                OpCode.UNSPILL -> frame.regs[dst] = frame.spills[imm]!!
            }
        }
    }

    private fun isFalsy(v: Value): Boolean = when (v) {
        is Value.Boolean -> !v.value
        is Value.Null  -> true
        else           -> false
    }

    private fun toDouble(v: Value): Double = when (v) {
        is Value.Int    -> v.value.toDouble()
        is Value.Float  -> v.value.toDouble()
        is Value.Double -> v.value
        else -> error("Expected number, got $v")
    }

    private fun binop(a: Value, b: Value, op: (Double, Double) -> Double): Value {
        val result = op(toDouble(a), toDouble(b))
        return when {
            a is Value.Double || b is Value.Double -> Value.Double(result)
            a is Value.Float  || b is Value.Float  -> Value.Float(result.toFloat())
            else -> Value.Int(result.toInt())
        }
    }

    private fun cmp(a: Value, b: Value, op: (Double, Double) -> Boolean): Value {
        return if (op(toDouble(a), toDouble(b))) Value.Boolean.TRUE else Value.Boolean.FALSE
    }

    private fun negate(a: Value): Value = when (a) {
        is Value.Int    -> Value.Int(-a.value)
        is Value.Float  -> Value.Float(-a.value)
        is Value.Double -> Value.Double(-a.value)
        else -> error("Expected number, got $a")
    }

    /** Walk the class hierarchy to find a method by name */
    private fun lookupMethod(instance: Value.Instance, name: String): Value? {
        var descriptor: ClassDescriptor? = instance.clazz
        while (descriptor != null) {
            descriptor.methods[name]?.let { return it }
            descriptor = descriptor.superClass
        }
        return null
    }

    /** Check if a class or any of its superclasses matches the type name */
    private fun isInTypeChain(descriptor: ClassDescriptor, typeName: String): Boolean {
        var current: ClassDescriptor? = descriptor
        while (current != null) {
            if (current.name == typeName) return true
            current = current.superClass
        }
        return false
    }

    /**
     * Fill in default argument values for a function call.
     * @param args The arguments passed by the caller
     * @param func The function being called
     * @param totalParams The total number of parameters the function expects
     * @param callerFrame The caller's frame (for evaluating defaults in caller's context)
     * @param frames The call frame stack
     * @return The final argument list with defaults filled in
     */
    private fun fillDefaultArgs(
        args: List<Value>,
        func: Value.Function,
        totalParams: Int,
        callerFrame: CallFrame,
        frames: ArrayDeque<CallFrame>
    ): List<Value> {
        val defaults = func.defaults

        // If no defaults or exact arg count, return as-is
        if (defaults == null || args.size == totalParams) {
            if (args.size < totalParams) {
                error("Expected $totalParams arguments but got ${args.size}")
            }
            return args
        }

        // Too many args
        if (args.size > totalParams) {
            error("Expected at most $totalParams arguments but got ${args.size}")
        }

        // Need to fill in defaults for missing args
        val finalArgs = args.toMutableList()

        for (i in args.size until totalParams) {
            val defaultChunkIdx = defaults.defaultChunks.getOrNull(i)
            if (defaultChunkIdx != null) {
                // Evaluate the default value in the caller's context
                val defaultChunk = callerFrame.chunk.functions[defaultChunkIdx]
                val defaultFrame = CallFrame(defaultChunk)
                // Execute the default value chunk
                executeDefaultChunk(defaultFrame, frames)
                // The result is in register 0 of the default frame
                val defaultValue = defaultFrame.regs[0] ?: Value.Null
                finalArgs.add(defaultValue)
            } else {
                // No default for this parameter - it's required
                error("Missing required argument at position $i (expected $totalParams arguments, got ${args.size})")
            }
        }

        return finalArgs
    }

    /**
     * Execute a default value chunk to compute its value.
     * This runs a small chunk that should produce a single value in register 0.
     */
    private fun executeDefaultChunk(frame: CallFrame, frames: ArrayDeque<CallFrame>) {
        while (frame.ip < frame.chunk.code.size) {
            val word = frame.chunk.code[frame.ip++]
            val opcode = OpCode.entries.find { it.code == (word and 0xFF).toByte() }
                ?: error("Unknown opcode in default value: ${word and 0xFF}")
            val dst = (word shr 8) and 0x0F
            val src1 = (word shr 12) and 0x0F
            val src2 = (word shr 16) and 0x0F
            val imm = (word shr 20) and 0xFFF

            when (opcode) {
                OpCode.LOAD_IMM -> frame.regs[dst] = frame.chunk.constants[imm]
                OpCode.LOAD_GLOBAL -> frame.regs[dst] = globals[frame.chunk.strings[imm]]
                    ?: error("Undefined global in default value: ${frame.chunk.strings[imm]}")
                OpCode.ADD -> {
                    val a = frame.regs[src1]!!
                    val b = frame.regs[src2]!!
                    if (a is Value.String || b is Value.String) {
                        frame.regs[dst] = Value.String(a.toString() + b.toString())
                    } else {
                        frame.regs[dst] = binop(a, b) { x, y -> x + y }
                    }
                }
                OpCode.SUB -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a - b }
                OpCode.MUL -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a * b }
                OpCode.DIV -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a / b }
                OpCode.MOD -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a % b }
                OpCode.POW -> frame.regs[dst] = binop(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> Math.pow(a, b) }
                OpCode.NEG -> frame.regs[dst] = negate(frame.regs[src1]!!)
                OpCode.NOT -> frame.regs[dst] = if (isFalsy(frame.regs[src1]!!)) Value.Boolean.TRUE else Value.Boolean.FALSE
                OpCode.EQ -> frame.regs[dst] = if (frame.regs[src1] == frame.regs[src2]) Value.Boolean.TRUE else Value.Boolean.FALSE
                OpCode.NEQ -> frame.regs[dst] = if (frame.regs[src1] != frame.regs[src2]) Value.Boolean.TRUE else Value.Boolean.FALSE
                OpCode.LT -> frame.regs[dst] = cmp(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a < b }
                OpCode.LTE -> frame.regs[dst] = cmp(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a <= b }
                OpCode.GT -> frame.regs[dst] = cmp(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a > b }
                OpCode.GTE -> frame.regs[dst] = cmp(frame.regs[src1]!!, frame.regs[src2]!!) { a, b -> a >= b }
                OpCode.RETURN -> {
                    // Default value computation complete
                    return
                }
                OpCode.SPILL   -> frame.spills[imm] = frame.regs[src1]
                OpCode.UNSPILL -> frame.regs[dst] = frame.spills[imm]!!
                else -> error("Unsupported opcode in default value: $opcode")
            }
        }
    }
}