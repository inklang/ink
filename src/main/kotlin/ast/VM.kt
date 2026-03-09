package org.aincraft.lang

import org.aincraft.lang.Value.*

class VM {
    val globals = mutableMapOf<String, Value>()

    data class CallFrame(
        val chunk: Chunk,
        var ip: Int = 0,
        val regs: Array<Value?> = arrayOfNulls(16),
        var returnDst: Int = 0  // Where to store the return value in caller
    )

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
                OpCode.PUSH_CONST  -> frame.regs[dst] = frame.chunk.constants[imm]
                OpCode.PUSH_NULL   -> frame.regs[dst] = Value.Null
                OpCode.PUSH_TRUE   -> frame.regs[dst] = Value.Boolean.TRUE
                OpCode.PUSH_FALSE  -> frame.regs[dst] = Value.Boolean.FALSE

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
                    // Read args from subsequent ARG instructions
                    val passedArgCount = imm
                    val args = (0 until passedArgCount).map { i ->
                        val argWord = frame.chunk.code[frame.ip + i]
                        val argSrc1 = (argWord shr 12) and 0x0F
                        frame.regs[argSrc1] ?: error("Null arg at reg $argSrc1")
                    }
                    frame.ip += passedArgCount  // Skip the ARG instructions
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
                OpCode.ARG -> { /* consumed by CALL, should never execute standalone */ }
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
                        val argWord = frame.chunk.code[frame.ip++]
                        val argSrc1 = (argWord shr 12) and 0x0F
                        frame.regs[argSrc1] ?: error("Null array element at reg $argSrc1")
                    }
                    frame.regs[dst] = Value.List(elements.toMutableList())
                }
                OpCode.GET_FIELD -> {
                    val obj = frame.regs[src1] ?: error("Cannot get field on null")
                    val fieldName = frame.chunk.strings[imm]
                    frame.regs[dst] = when (obj) {
                        is Value.Range -> when (fieldName) {
                            "iter" -> Value.NativeFunction { _ ->
                                Value.Iterator(obj)
                            }
                            else -> error("Range has no field '$fieldName'")
                        }
                        is Value.Iterator -> when (fieldName) {
                            "hasNext" -> Value.NativeFunction { _ ->
                                val hasNext = when (val iterable = obj.iterable) {
                                    is Value.Range -> obj.index < (iterable.end - iterable.start)
                                    is Value.List -> obj.index < iterable.value.size
                                    else -> error("Cannot iterate: $iterable")
                                }
                                if (hasNext) Value.Boolean.TRUE else Value.Boolean.FALSE
                            }
                            "next" -> Value.NativeFunction { _ ->
                                val value = when (val iterable = obj.iterable) {
                                    is Value.Range -> Value.Int(iterable.start + obj.index)
                                    is Value.List -> iterable.value[obj.index]
                                    else -> error("Cannot get value: $iterable")
                                }
                                obj.index++
                                value
                            }
                            else -> error("Iterator has no field '$fieldName'")
                        }
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
                    val fieldName = frame.chunk.strings[imm]
                    obj.fields[fieldName] = frame.regs[src2] ?: Value.Null
                }
                OpCode.NEW_INSTANCE -> {
                    val classVal = frame.regs[src1] as? Value.Class
                        ?: error("Cannot create instance of non-class: ${frame.regs[src1]}")
                    // Read constructor args from subsequent ARG instructions
                    val args = (0 until imm).map { i ->
                        val argWord = frame.chunk.code[frame.ip + i]
                        val argSrc1 = (argWord shr 12) and 0x0F
                        frame.regs[argSrc1] ?: error("Null arg at reg $argSrc1")
                    }
                    frame.ip += imm  // Skip the ARG instructions

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
                    frame.regs[dst] = Value.Range(start, end)
                }
                OpCode.GET_INDEX -> {
                    val list = frame.regs[src1] as? Value.List
                        ?: error("Cannot index non-list: ${frame.regs[src1]}")
                    val idx = (frame.regs[src2] as? Value.Int)?.value
                        ?: error("Index must be an integer: ${frame.regs[src2]}")
                    frame.regs[dst] = list.value.getOrElse(idx) { Value.Null }
                }
                OpCode.SET_INDEX -> {
                    val list = frame.regs[src1] as? Value.List
                        ?: error("Cannot index non-list: ${frame.regs[src1]}")
                    val idx = (frame.regs[src2] as? Value.Int)?.value
                        ?: error("Index must be an integer: ${frame.regs[src2]}")
                    val value = frame.regs[imm] ?: Value.Null
                    if (idx >= 0 && idx < list.value.size) {
                        list.value[idx] = value
                    }
                }
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
                OpCode.PUSH_CONST -> frame.regs[dst] = frame.chunk.constants[imm]
                OpCode.PUSH_NULL -> frame.regs[dst] = Value.Null
                OpCode.PUSH_TRUE -> frame.regs[dst] = Value.Boolean.TRUE
                OpCode.PUSH_FALSE -> frame.regs[dst] = Value.Boolean.FALSE
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
                else -> error("Unsupported opcode in default value: $opcode")
            }
        }
    }
}