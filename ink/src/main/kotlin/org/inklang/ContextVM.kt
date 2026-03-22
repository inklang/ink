package org.inklang

import org.inklang.lang.*

/**
 * Exception thrown when a script execution exceeds its instruction limit.
 */
class ScriptTimeoutException(
    val instructionCount: Long,
    val limit: Int
) : RuntimeException("Script exceeded instruction limit: $instructionCount > $limit")

/**
 * Exception thrown when a script encounters a runtime error.
 */
class ScriptException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * VM that uses a InkContext for output and supports instruction counting.
 * This is the runtime used by InkScript.execute().
 */
class ContextVM(
    private val context: InkContext,
    private val maxInstructions: Int = 0  // 0 = no limit
) {
    private var instructionCount: Long = 0

    val globals = mutableMapOf<String, Value>(
        "Array" to Value.Class(Builtins.ArrayClass),
        "Map" to Value.Class(Builtins.MapClass),
        "Set" to Value.Class(Builtins.SetClass),
        "Tuple" to Value.Class(Builtins.TupleClass),
        "EnumValue" to Value.Class(Builtins.EnumValueClass),
        "EnumNamespace" to Value.Class(Builtins.EnumNamespaceClass),
        "print" to Value.NativeFunction { args ->
            context.print(args.joinToString(" ") { valueToString(it) })
            Value.Null
        },
        "log" to Value.NativeFunction { args ->
            context.log(args.joinToString(" ") { valueToString(it) })
            Value.Null
        },
        "io" to Value.Instance(ClassDescriptor(
            name = "IoModule",
            superClass = null,
            methods = mapOf(
                "read" to Value.NativeFunction { args ->
                    val path = (args.getOrNull(0) as? Value.String)?.value
                        ?: error("io.read requires a string path")
                    Value.String(context.io().read(path))
                },
                "write" to Value.NativeFunction { args ->
                    val path = (args.getOrNull(0) as? Value.String)?.value
                        ?: error("io.write requires a string path")
                    val content = (args.getOrNull(1) as? Value.String)?.value
                        ?: error("io.write requires a string content")
                    context.io().write(path, content)
                    Value.Null
                }
            )
        )),
        "json" to Value.Instance(ClassDescriptor(
            name = "JsonModule",
            superClass = null,
            methods = mapOf(
                "parse" to Value.NativeFunction { args ->
                    val str = (args.getOrNull(0) as? Value.String)?.value
                        ?: error("json.parse requires a string")
                    context.json().parse(str)
                },
                "stringify" to Value.NativeFunction { args ->
                    val value = args.getOrNull(0)
                        ?: error("json.stringify requires a value")
                    Value.String(context.json().stringify(value))
                }
            )
        )),
        "db" to Value.Instance(ClassDescriptor(
            name = "DbModule",
            superClass = null,
            methods = mapOf(
                "from" to Value.NativeFunction { args ->
                    val tableName = (args.getOrNull(0) as? Value.String)?.value
                        ?: error("db.from requires a table name string")
                    val tableRef = context.db().from(tableName)
                    Value.TableRefInstance(tableRef)
                },
                "registerTable" to Value.NativeFunction { args ->
                    val tableName = (args.getOrNull(0) as? Value.String)?.value
                        ?: error("registerTable requires a table name")
                    val fieldsArr = args.getOrNull(1) as? Value.Instance
                        ?: error("registerTable requires a fields array")
                    val items = (fieldsArr.fields["__items"] as? Value.InternalList)?.items
                        ?: error("registerTable requires a fields array")
                    val fieldNames = items.map { (it as Value.String).value }
                    val keyIdx = (args.getOrNull(2) as? Value.Int)?.value ?: 0
                    context.db().registerTable(tableName, fieldNames, keyIdx)
                    Value.Null
                }
            )
        ))
    )

    /**
     * Merge pre-loaded global values (e.g. from config files) into the VM's globals.
     * Must be called before execute().
     */
    fun setGlobals(overrides: Map<String, Value>) {
        globals.putAll(overrides)
    }

    data class CallFrame(
        val chunk: Chunk,
        var ip: Int = 0,
        val regs: Array<Value?> = arrayOfNulls(16),
        var returnDst: Int = 0,
        val argBuffer: ArrayDeque<Value> = ArrayDeque()
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

            // Instruction counting for timeout protection
            if (maxInstructions > 0) {
                instructionCount++
                if (instructionCount > maxInstructions) {
                    throw ScriptTimeoutException(instructionCount, maxInstructions)
                }
            }

            val word = frame.chunk.code[frame.ip++]
            val opcode = OpCode.entries.find { it.code == (word and 0xFF).toByte() }
                ?: throw ScriptException("Unknown opcode: ${word and 0xFF}")
            val dst = (word shr 8) and 0x0F
            val src1 = (word shr 12) and 0x0F
            val src2 = (word shr 16) and 0x0F
            val imm = (word shr 20) and 0xFFF

            try {
                when (opcode) {
                    OpCode.LOAD_IMM -> frame.regs[dst] = frame.chunk.constants[imm]
                    OpCode.LOAD_GLOBAL -> frame.regs[dst] = globals[frame.chunk.strings[imm]]
                        ?: throw ScriptException("Undefined global: ${frame.chunk.strings[imm]}")
                    OpCode.STORE_GLOBAL -> globals[frame.chunk.strings[imm]] = frame.regs[src1]!!
                    OpCode.MOVE -> frame.regs[dst] = frame.regs[src1]

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

                    OpCode.JUMP -> frame.ip = imm
                    OpCode.JUMP_IF_FALSE -> if (isFalsy(frame.regs[src1]!!)) frame.ip = imm

                    OpCode.LOAD_FUNC -> {
                        val funcChunk = frame.chunk.functions[imm]
                        val defaults = if (imm < frame.chunk.functionDefaults.size) {
                            frame.chunk.functionDefaults[imm]
                        } else {
                            null
                        }
                        val requiredArity: kotlin.Int = defaults?.defaultChunks?.count { it == null } ?: 0
                        frame.regs[dst] = Value.Function(funcChunk, requiredArity, defaults)
                    }
                    OpCode.CALL -> {
                        val passedArgCount = imm
                        val args = (0 until passedArgCount).map { i ->
                            frame.argBuffer.removeFirstOrNull() ?: throw ScriptException("Missing argument $i in arg buffer")
                        }
                        when (val func = frame.regs[src1]) {
                            is Value.Function -> {
                                val totalParams = func.defaults?.defaultChunks?.size ?: passedArgCount
                                val finalArgs = fillDefaultArgs(args, func, totalParams, frame, frames)
                                val newFrame = CallFrame(func.chunk)
                                newFrame.returnDst = dst
                                finalArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
                                frames.addLast(newFrame)
                            }
                            is Value.NativeFunction -> {
                                frame.regs[dst] = func.fn(args)
                            }
                            is Value.BoundMethod -> {
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
                                    else -> throw ScriptException("BoundMethod wraps non-callable: $method")
                                }
                            }
                            is Value.Class -> {
                                val instance = Value.Instance(func.descriptor)
                                frame.regs[dst] = instance
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
                                        else -> throw ScriptException("init is not callable: $initMethod")
                                    }
                                }
                            }
                            else -> throw ScriptException("Cannot call non-function: ${frame.regs[src1]}")
                        }
                    }
                    OpCode.PUSH_ARG -> {
                        frame.argBuffer.addLast(frame.regs[src1] ?: throw ScriptException("Null value in PUSH_ARG at reg $src1"))
                    }
                    OpCode.RETURN -> {
                        val returnVal = frame.regs[src1]
                        val returnDst = frame.returnDst
                        frames.removeLast()
                        if (frames.isNotEmpty()) {
                            frames.last().regs[returnDst] = returnVal
                        }
                    }

                    OpCode.POP -> { /* no-op in register VM */ }
                    OpCode.BREAK -> throw ScriptException("BREAK outside loop")
                    OpCode.NEXT -> throw ScriptException("NEXT outside loop")
                    OpCode.NEW_ARRAY -> {
                        val count = imm
                        val elements = (0 until count).map { i ->
                            frame.argBuffer.removeFirstOrNull() ?: throw ScriptException("Missing array element $i")
                        }
                        frame.regs[dst] = Builtins.newArray(elements.toMutableList())
                    }
                    OpCode.GET_FIELD -> {
                        val obj = frame.regs[src1] ?: throw ScriptException("Cannot get field on null")
                        val fieldName = frame.chunk.strings[imm]
                        frame.regs[dst] = when (obj) {
                            is Value.Instance -> {
                                obj.fields[fieldName]?.let { it }
                                    ?: lookupMethod(obj, fieldName)
                                        ?.let { Value.BoundMethod(obj, it) }
                                    ?: throw ScriptException("Instance has no field '$fieldName'")
                            }
                            is Value.TableRefInstance -> {
                                val tableRef = obj.tableRef
                                when (fieldName) {
                                    "all" -> Value.NativeFunction { tableRef.all() }
                                    "find" -> Value.NativeFunction { args -> tableRef.find(args.getOrNull(1) ?: Value.Null) ?: Value.Null }
                                    "insert" -> Value.NativeFunction { args ->
                                        val data = args.getOrNull(1) as? Value.Instance
                                            ?: error("insert requires a map")
                                        val entries = (data.fields["__entries"] as? Value.InternalMap)?.entries
                                            ?: error("insert requires a map with __entries")
                                        val map = entries.mapKeys { (it.key as Value.String).value }.mapValues { it.value }
                                        tableRef.insert(map)
                                    }
                                    "update" -> Value.NativeFunction { args ->
                                        val key = args.getOrNull(1) ?: Value.Null
                                        val data = args.getOrNull(2) as? Value.Instance
                                            ?: error("update requires a map")
                                        val entries = (data.fields["__entries"] as? Value.InternalMap)?.entries
                                            ?: error("update requires a map with __entries")
                                        val map = entries.mapKeys { (it.key as Value.String).value }.mapValues { it.value }
                                        tableRef.update(key, map)
                                        Value.Null
                                    }
                                    "delete" -> Value.NativeFunction { args ->
                                        val key = args.getOrNull(1) ?: Value.Null
                                        tableRef.delete(key)
                                        Value.Null
                                    }
                                    "where" -> Value.NativeFunction { args ->
                                        val condition = (args.getOrNull(1) as? Value.String)?.value
                                            ?: error("where requires a string condition")
                                        val queryArgs = args.drop(2)
                                        val qb = tableRef.where(condition, *queryArgs.toTypedArray())
                                        Value.QueryBuilderInstance(qb)
                                    }
                                    "order" -> Value.NativeFunction { args ->
                                        val field = (args.getOrNull(1) as? Value.String)?.value
                                            ?: error("order requires a field string")
                                        val direction = (args.getOrNull(2) as? Value.String)?.value ?: "asc"
                                        tableRef.order(field, direction)
                                        Value.Null
                                    }
                                    "limit" -> Value.NativeFunction { args ->
                                        val n = (args.getOrNull(1) as? Value.Int)?.value
                                            ?: error("limit requires an int")
                                        tableRef.limit(n)
                                        Value.Null
                                    }
                                    else -> throw ScriptException("TableRef has no method '$fieldName'")
                                }
                            }
                            is Value.QueryBuilderInstance -> {
                                val qb = obj.queryBuilder
                                when (fieldName) {
                                    "order" -> Value.NativeFunction { args ->
                                        val field = (args.getOrNull(1) as? Value.String)?.value
                                            ?: error("order requires a field string")
                                        val direction = (args.getOrNull(2) as? Value.String)?.value ?: "asc"
                                        qb.order(field, direction)
                                        Value.QueryBuilderInstance(qb)
                                    }
                                    "limit" -> Value.NativeFunction { args ->
                                        val n = (args.getOrNull(1) as? Value.Int)?.value
                                            ?: error("limit requires an int")
                                        qb.limit(n)
                                        Value.QueryBuilderInstance(qb)
                                    }
                                    "all" -> Value.NativeFunction { qb.all() }
                                    "first" -> Value.NativeFunction { qb.first() ?: Value.Null }
                                    else -> throw ScriptException("QueryBuilder has no method '$fieldName'")
                                }
                            }
                            else -> throw ScriptException("Cannot get field on ${obj::class.simpleName}")
                        }
                    }
                    OpCode.SET_FIELD -> {
                        val obj = frame.regs[src1] as? Value.Instance
                            ?: throw ScriptException("Cannot set field on non-instance")
                        if (obj.clazz.readOnly) {
                            throw ScriptException("Cannot modify read-only ${obj.clazz.name} field")
                        }
                        val fieldName = frame.chunk.strings[imm]
                        obj.fields[fieldName] = frame.regs[src2] ?: Value.Null
                    }
                    OpCode.NEW_INSTANCE -> {
                        val classVal = frame.regs[src1] as? Value.Class
                            ?: throw ScriptException("Cannot create instance of non-class: ${frame.regs[src1]}")
                        val args = (0 until imm).map { i ->
                            frame.argBuffer.removeFirstOrNull() ?: throw ScriptException("Missing argument $i")
                        }
                        val instance = Value.Instance(classVal.descriptor)
                        frame.regs[dst] = instance
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
                                else -> throw ScriptException("init is not callable: $initMethod")
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
                    OpCode.HAS -> {
                        val obj = frame.regs[src1] ?: throw ScriptException("Cannot has on null")
                        val fieldName = frame.chunk.strings[imm]
                        val result = when (obj) {
                            is Value.Instance -> {
                                if (obj.clazz == Builtins.MapClass) {
                                    val entriesVal = obj.fields["__entries"]
                                    if (entriesVal is Value.InternalMap) {
                                        Value.Boolean(entriesVal.entries.containsKey(Value.String(fieldName)))
                                    } else {
                                        Value.Boolean(false)
                                    }
                                } else if (obj.clazz == Builtins.ArrayClass) {
                                    Value.Boolean(false)
                                } else {
                                    Value.Boolean(obj.fields.containsKey(fieldName))
                                }
                            }
                            else -> Value.Boolean(false)
                        }
                        frame.regs[dst] = result
                    }
                    OpCode.BUILD_CLASS -> {
                        val classInfo = frame.chunk.classes[imm]
                        val superClassDescriptor = classInfo.superClass?.let { superName ->
                            (globals[superName] as? Value.Class)?.descriptor
                        }
                        val methods = classInfo.methods.mapValues { (_, funcIdx) ->
                            Value.Function(frame.chunk.functions[funcIdx])
                        }
                        val descriptor = ClassDescriptor(classInfo.name, superClassDescriptor, methods)
                        frame.regs[dst] = Value.Class(descriptor)
                    }
                    OpCode.RANGE -> {
                        val start = (frame.regs[src1] as? Value.Int)?.value
                            ?: throw ScriptException("Range start must be int: ${frame.regs[src1]}")
                        val end = (frame.regs[src2] as? Value.Int)?.value
                            ?: throw ScriptException("Range end must be int: ${frame.regs[src2]}")
                        frame.regs[dst] = Builtins.newRange(start, end)
                    }
                    OpCode.GET_INDEX -> {
                        val obj = frame.regs[src1]!!
                        when (obj) {
                            is Value.Instance -> {
                                val getMethod = lookupMethod(obj, "get")
                                    ?: throw ScriptException("Instance has no 'get' method for indexing")
                                val index = frame.regs[src2]!!
                                when (getMethod) {
                                    is Value.NativeFunction -> frame.regs[dst] = getMethod.fn(listOf(obj, index))
                                    else -> throw ScriptException("get method is not a native function")
                                }
                            }
                            else -> throw ScriptException("Cannot index: ${obj::class.simpleName}")
                        }
                    }
                    OpCode.SET_INDEX -> {
                        val obj = frame.regs[src1]!!
                        when (obj) {
                            is Value.Instance -> {
                                val setMethod = lookupMethod(obj, "set")
                                    ?: throw ScriptException("Instance has no 'set' method for indexing")
                                val index = frame.regs[src2]!!
                                val value = frame.regs[imm] ?: Value.Null
                                when (setMethod) {
                                    is Value.NativeFunction -> setMethod.fn(listOf(obj, index, value))
                                    else -> throw ScriptException("set method is not a native function")
                                }
                            }
                            else -> throw ScriptException("Cannot index: ${obj::class.simpleName}")
                        }
                    }
                    OpCode.SPILL -> frame.spills[imm] = frame.regs[src1]
                    OpCode.UNSPILL -> frame.regs[dst] = frame.spills[imm]!!
                    OpCode.THROW -> {
                        val throwable = frame.regs[src1] ?: throw ScriptException("Cannot throw null")
                        val message = when (throwable) {
                            is Value.String -> throwable.value
                            else -> throwable.toString()
                        }
                        throw ScriptException("Uncaught exception: $message")
                    }
                    OpCode.REGISTER_EVENT -> {
                        // Event handlers are registered at compile time via context.registerEventHandler()
                        // At runtime this is a no-op
                    }
                }
            } catch (e: ScriptException) {
                throw e
            } catch (e: ScriptTimeoutException) {
                throw e
            } catch (e: Exception) {
                throw ScriptException("Runtime error: ${e.message}", e)
            }
        }
    }

    /**
     * Execute a handler function with pre-loaded arguments.
     * Used for event handler invocation.
     */
    fun executeHandler(func: Value.Function, args: ArrayDeque<Value>) {
        val frames = ArrayDeque<CallFrame>()
        val frame = CallFrame(func.chunk)
        frame.returnDst = 0
        args.forEachIndexed { i, v -> frame.regs[i] = v }
        frames.addLast(frame)

        while (frames.isNotEmpty()) {
            val currentFrame = frames.last()
            if (currentFrame.ip >= currentFrame.chunk.code.size) {
                frames.removeLast()
                continue
            }

            if (maxInstructions > 0) {
                instructionCount++
                if (instructionCount > maxInstructions) {
                    throw ScriptTimeoutException(instructionCount, maxInstructions)
                }
            }

            val word = currentFrame.chunk.code[currentFrame.ip++]
            val opcode = OpCode.entries.find { it.code == (word and 0xFF).toByte() }
                ?: throw ScriptException("Unknown opcode: ${word and 0xFF}")
            val dst = (word shr 8) and 0x0F
            val src1 = (word shr 12) and 0x0F
            val src2 = (word shr 16) and 0x0F
            val imm = (word shr 20) and 0xFFF

            try {
                when (opcode) {
                    OpCode.LOAD_IMM -> currentFrame.regs[dst] = currentFrame.chunk.constants[imm]
                    OpCode.LOAD_GLOBAL -> currentFrame.regs[dst] = globals[currentFrame.chunk.strings[imm]]
                        ?: throw ScriptException("Undefined global: ${currentFrame.chunk.strings[imm]}")
                    OpCode.STORE_GLOBAL -> globals[currentFrame.chunk.strings[imm]] = currentFrame.regs[src1]!!
                    OpCode.MOVE -> currentFrame.regs[dst] = currentFrame.regs[src1]

                    OpCode.ADD -> {
                        val a = currentFrame.regs[src1]!!
                        val b = currentFrame.regs[src2]!!
                        if (a is Value.String || b is Value.String) {
                            currentFrame.regs[dst] = Value.String(a.toString() + b.toString())
                        } else {
                            currentFrame.regs[dst] = binop(a, b) { x, y -> x + y }
                        }
                    }
                    OpCode.SUB -> currentFrame.regs[dst] = binop(currentFrame.regs[src1]!!, currentFrame.regs[src2]!!) { a, b -> a - b }
                    OpCode.MUL -> currentFrame.regs[dst] = binop(currentFrame.regs[src1]!!, currentFrame.regs[src2]!!) { a, b -> a * b }
                    OpCode.DIV -> currentFrame.regs[dst] = binop(currentFrame.regs[src1]!!, currentFrame.regs[src2]!!) { a, b -> a / b }
                    OpCode.MOD -> currentFrame.regs[dst] = binop(currentFrame.regs[src1]!!, currentFrame.regs[src2]!!) { a, b -> a % b }
                    OpCode.POW -> currentFrame.regs[dst] = binop(currentFrame.regs[src1]!!, currentFrame.regs[src2]!!) { a, b -> Math.pow(a, b) }
                    OpCode.NEG -> currentFrame.regs[dst] = negate(currentFrame.regs[src1]!!)

                    OpCode.NOT -> currentFrame.regs[dst] = if (isFalsy(currentFrame.regs[src1]!!)) Value.Boolean.TRUE else Value.Boolean.FALSE
                    OpCode.EQ -> currentFrame.regs[dst] = if (currentFrame.regs[src1] == currentFrame.regs[src2]) Value.Boolean.TRUE else Value.Boolean.FALSE
                    OpCode.NEQ -> currentFrame.regs[dst] = if (currentFrame.regs[src1] != currentFrame.regs[src2]) Value.Boolean.TRUE else Value.Boolean.FALSE
                    OpCode.LT -> currentFrame.regs[dst] = cmp(currentFrame.regs[src1]!!, currentFrame.regs[src2]!!) { a, b -> a < b }
                    OpCode.LTE -> currentFrame.regs[dst] = cmp(currentFrame.regs[src1]!!, currentFrame.regs[src2]!!) { a, b -> a <= b }
                    OpCode.GT -> currentFrame.regs[dst] = cmp(currentFrame.regs[src1]!!, currentFrame.regs[src2]!!) { a, b -> a > b }
                    OpCode.GTE -> currentFrame.regs[dst] = cmp(currentFrame.regs[src1]!!, currentFrame.regs[src2]!!) { a, b -> a >= b }

                    OpCode.JUMP -> currentFrame.ip = imm
                    OpCode.JUMP_IF_FALSE -> if (isFalsy(currentFrame.regs[src1]!!)) currentFrame.ip = imm

                    OpCode.LOAD_FUNC -> {
                        val funcChunk = currentFrame.chunk.functions[imm]
                        val defaults = if (imm < currentFrame.chunk.functionDefaults.size) {
                            currentFrame.chunk.functionDefaults[imm]
                        } else {
                            null
                        }
                        val requiredArity: kotlin.Int = defaults?.defaultChunks?.count { it == null } ?: 0
                        currentFrame.regs[dst] = Value.Function(funcChunk, requiredArity, defaults)
                    }
                    OpCode.CALL -> {
                        val passedArgCount = imm
                        val callArgs = (0 until passedArgCount).map { i ->
                            currentFrame.argBuffer.removeFirstOrNull() ?: throw ScriptException("Missing argument $i in arg buffer")
                        }
                        when (val funcVal = currentFrame.regs[src1]) {
                            is Value.Function -> {
                                val totalParams = funcVal.defaults?.defaultChunks?.size ?: passedArgCount
                                val finalArgs = fillDefaultArgs(callArgs, funcVal, totalParams, currentFrame, frames)
                                val newFrame = CallFrame(funcVal.chunk)
                                newFrame.returnDst = dst
                                finalArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
                                frames.addLast(newFrame)
                            }
                            is Value.NativeFunction -> {
                                currentFrame.regs[dst] = funcVal.fn(callArgs)
                            }
                            is Value.BoundMethod -> {
                                val boundArgs = listOf(funcVal.instance) + callArgs
                                when (val method = funcVal.method) {
                                    is Value.Function -> {
                                        val totalParams = method.defaults?.defaultChunks?.size ?: boundArgs.size
                                        val finalArgs = fillDefaultArgs(boundArgs, method, totalParams, currentFrame, frames)
                                        val newFrame = CallFrame(method.chunk)
                                        newFrame.returnDst = dst
                                        finalArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
                                        frames.addLast(newFrame)
                                    }
                                    is Value.NativeFunction -> {
                                        currentFrame.regs[dst] = method.fn(boundArgs)
                                    }
                                    else -> throw ScriptException("BoundMethod wraps non-callable: $method")
                                }
                            }
                            is Value.Class -> {
                                val instance = Value.Instance(funcVal.descriptor)
                                currentFrame.regs[dst] = instance
                                val initMethod = lookupMethod(instance, "init")
                                if (initMethod != null) {
                                    val boundArgs = listOf(instance) + callArgs
                                    when (initMethod) {
                                        is Value.Function -> {
                                            val totalParams = initMethod.defaults?.defaultChunks?.size ?: boundArgs.size
                                            val finalArgs = fillDefaultArgs(boundArgs, initMethod, totalParams, currentFrame, frames)
                                            val newFrame = CallFrame(initMethod.chunk)
                                            newFrame.returnDst = dst
                                            finalArgs.forEachIndexed { i, v -> newFrame.regs[i] = v }
                                            frames.addLast(newFrame)
                                        }
                                        is Value.NativeFunction -> {
                                            initMethod.fn(boundArgs)
                                        }
                                        else -> throw ScriptException("init is not callable: $initMethod")
                                    }
                                }
                            }
                            else -> throw ScriptException("Cannot call non-function: $funcVal")
                        }
                    }
                    OpCode.PUSH_ARG -> {
                        currentFrame.argBuffer.addLast(currentFrame.regs[src1] ?: throw ScriptException("Null value in PUSH_ARG at reg $src1"))
                    }
                    OpCode.RETURN -> {
                        val returnVal = currentFrame.regs[src1]
                        val returnDst = currentFrame.returnDst
                        frames.removeLast()
                        if (frames.isNotEmpty()) {
                            frames.last().regs[returnDst] = returnVal
                        }
                    }

                    OpCode.POP -> { /* no-op in register VM */ }
                    OpCode.BREAK -> throw ScriptException("BREAK outside loop")
                    OpCode.NEXT -> throw ScriptException("NEXT outside loop")
                    OpCode.NEW_ARRAY -> {
                        val count = imm
                        val elements = (0 until count).map { i ->
                            currentFrame.argBuffer.removeFirstOrNull() ?: throw ScriptException("Missing array element $i")
                        }
                        currentFrame.regs[dst] = Builtins.newArray(elements.toMutableList())
                    }
                    OpCode.GET_FIELD -> {
                        val obj = currentFrame.regs[src1] ?: throw ScriptException("Cannot get field on null")
                        val fieldName = currentFrame.chunk.strings[imm]
                        currentFrame.regs[dst] = when (obj) {
                            is Value.Instance -> {
                                obj.fields[fieldName]?.let { it }
                                    ?: lookupMethod(obj, fieldName)
                                        ?.let { Value.BoundMethod(obj, it) }
                                    ?: throw ScriptException("Instance has no field '$fieldName'")
                            }
                            is Value.TableRefInstance -> {
                                val tableRef = obj.tableRef
                                when (fieldName) {
                                    "all" -> Value.NativeFunction { tableRef.all() }
                                    "find" -> Value.NativeFunction { args -> tableRef.find(args.getOrNull(1) ?: Value.Null) ?: Value.Null }
                                    "insert" -> Value.NativeFunction { args ->
                                        val data = args.getOrNull(1) as? Value.Instance
                                            ?: error("insert requires a map")
                                        val entries = (data.fields["__entries"] as? Value.InternalMap)?.entries
                                            ?: error("insert requires a map with __entries")
                                        val map = entries.mapKeys { (it.key as Value.String).value }.mapValues { it.value }
                                        tableRef.insert(map)
                                    }
                                    "update" -> Value.NativeFunction { args ->
                                        val key = args.getOrNull(1) ?: Value.Null
                                        val data = args.getOrNull(2) as? Value.Instance
                                            ?: error("update requires a map")
                                        val entries = (data.fields["__entries"] as? Value.InternalMap)?.entries
                                            ?: error("update requires a map with __entries")
                                        val map = entries.mapKeys { (it.key as Value.String).value }.mapValues { it.value }
                                        tableRef.update(key, map)
                                        Value.Null
                                    }
                                    "delete" -> Value.NativeFunction { args ->
                                        val key = args.getOrNull(1) ?: Value.Null
                                        tableRef.delete(key)
                                        Value.Null
                                    }
                                    "where" -> Value.NativeFunction { args ->
                                        val condition = (args.getOrNull(1) as? Value.String)?.value
                                            ?: error("where requires a string condition")
                                        val queryArgs = args.drop(2)
                                        val qb = tableRef.where(condition, *queryArgs.toTypedArray())
                                        Value.QueryBuilderInstance(qb)
                                    }
                                    "order" -> Value.NativeFunction { args ->
                                        val field = (args.getOrNull(1) as? Value.String)?.value
                                            ?: error("order requires a field string")
                                        val direction = (args.getOrNull(2) as? Value.String)?.value ?: "asc"
                                        tableRef.order(field, direction)
                                        Value.Null
                                    }
                                    "limit" -> Value.NativeFunction { args ->
                                        val n = (args.getOrNull(1) as? Value.Int)?.value
                                            ?: error("limit requires an int")
                                        tableRef.limit(n)
                                        Value.Null
                                    }
                                    else -> throw ScriptException("TableRef has no method '$fieldName'")
                                }
                            }
                            is Value.QueryBuilderInstance -> {
                                val qb = obj.queryBuilder
                                when (fieldName) {
                                    "order" -> Value.NativeFunction { args ->
                                        val field = (args.getOrNull(1) as? Value.String)?.value
                                            ?: error("order requires a field string")
                                        val direction = (args.getOrNull(2) as? Value.String)?.value ?: "asc"
                                        qb.order(field, direction)
                                        Value.QueryBuilderInstance(qb)
                                    }
                                    "limit" -> Value.NativeFunction { args ->
                                        val n = (args.getOrNull(1) as? Value.Int)?.value
                                            ?: error("limit requires an int")
                                        qb.limit(n)
                                        Value.QueryBuilderInstance(qb)
                                    }
                                    "all" -> Value.NativeFunction { qb.all() }
                                    "first" -> Value.NativeFunction { qb.first() ?: Value.Null }
                                    else -> throw ScriptException("QueryBuilder has no method '$fieldName'")
                                }
                            }
                            else -> throw ScriptException("Cannot get field on ${obj::class.simpleName}")
                        }
                    }
                    OpCode.SET_FIELD -> {
                        val obj = currentFrame.regs[src1] as? Value.Instance
                            ?: throw ScriptException("Cannot set field on non-instance")
                        if (obj.clazz.readOnly) {
                            throw ScriptException("Cannot modify read-only ${obj.clazz.name} field")
                        }
                        val fieldName = currentFrame.chunk.strings[imm]
                        obj.fields[fieldName] = currentFrame.regs[src2] ?: Value.Null
                    }
                    OpCode.NEW_INSTANCE -> {
                        val classVal = currentFrame.regs[src1] as? Value.Class
                            ?: throw ScriptException("Cannot create instance of non-class: ${currentFrame.regs[src1]}")
                        val instanceArgs = (0 until imm).map { i ->
                            currentFrame.argBuffer.removeFirstOrNull() ?: throw ScriptException("Missing argument $i")
                        }
                        val instance = Value.Instance(classVal.descriptor)
                        currentFrame.regs[dst] = instance
                        val initMethod = lookupMethod(instance, "init")
                        if (initMethod != null) {
                            val boundArgs = listOf(instance) + instanceArgs
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
                                else -> throw ScriptException("init is not callable: $initMethod")
                            }
                        }
                    }
                    OpCode.IS_TYPE -> {
                        val value = currentFrame.regs[src1]
                        val typeName = currentFrame.chunk.strings[imm]
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
                        currentFrame.regs[dst] = if (result) Value.Boolean.TRUE else Value.Boolean.FALSE
                    }
                    OpCode.HAS -> {
                        val obj = currentFrame.regs[src1] ?: throw ScriptException("Cannot has on null")
                        val fieldName = currentFrame.chunk.strings[imm]
                        val result = when (obj) {
                            is Value.Instance -> {
                                if (obj.clazz == Builtins.MapClass) {
                                    val entriesVal = obj.fields["__entries"]
                                    if (entriesVal is Value.InternalMap) {
                                        Value.Boolean(entriesVal.entries.containsKey(Value.String(fieldName)))
                                    } else {
                                        Value.Boolean(false)
                                    }
                                } else if (obj.clazz == Builtins.ArrayClass) {
                                    Value.Boolean(false)
                                } else {
                                    Value.Boolean(obj.fields.containsKey(fieldName))
                                }
                            }
                            else -> Value.Boolean(false)
                        }
                        currentFrame.regs[dst] = result
                    }
                    OpCode.BUILD_CLASS -> {
                        val classInfo = currentFrame.chunk.classes[imm]
                        val superClassDescriptor = classInfo.superClass?.let { superName ->
                            (globals[superName] as? Value.Class)?.descriptor
                        }
                        val methods = classInfo.methods.mapValues { (_, funcIdx) ->
                            Value.Function(currentFrame.chunk.functions[funcIdx])
                        }
                        val descriptor = ClassDescriptor(classInfo.name, superClassDescriptor, methods)
                        currentFrame.regs[dst] = Value.Class(descriptor)
                    }
                    OpCode.RANGE -> {
                        val start = (currentFrame.regs[src1] as? Value.Int)?.value
                            ?: throw ScriptException("Range start must be int: ${currentFrame.regs[src1]}")
                        val end = (currentFrame.regs[src2] as? Value.Int)?.value
                            ?: throw ScriptException("Range end must be int: ${currentFrame.regs[src2]}")
                        currentFrame.regs[dst] = Builtins.newRange(start, end)
                    }
                    OpCode.GET_INDEX -> {
                        val obj = currentFrame.regs[src1]!!
                        when (obj) {
                            is Value.Instance -> {
                                val getMethod = lookupMethod(obj, "get")
                                    ?: throw ScriptException("Instance has no 'get' method for indexing")
                                val index = currentFrame.regs[src2]!!
                                when (getMethod) {
                                    is Value.NativeFunction -> currentFrame.regs[dst] = getMethod.fn(listOf(obj, index))
                                    else -> throw ScriptException("get method is not a native function")
                                }
                            }
                            else -> throw ScriptException("Cannot index: ${obj::class.simpleName}")
                        }
                    }
                    OpCode.SET_INDEX -> {
                        val obj = currentFrame.regs[src1]!!
                        when (obj) {
                            is Value.Instance -> {
                                val setMethod = lookupMethod(obj, "set")
                                    ?: throw ScriptException("Instance has no 'set' method for indexing")
                                val index = currentFrame.regs[src2]!!
                                val value = currentFrame.regs[imm] ?: Value.Null
                                when (setMethod) {
                                    is Value.NativeFunction -> setMethod.fn(listOf(obj, index, value))
                                    else -> throw ScriptException("set method is not a native function")
                                }
                            }
                            else -> throw ScriptException("Cannot index: ${obj::class.simpleName}")
                        }
                    }
                    OpCode.SPILL -> currentFrame.spills[imm] = currentFrame.regs[src1]
                    OpCode.UNSPILL -> currentFrame.regs[dst] = currentFrame.spills[imm]!!
                    OpCode.THROW -> {
                        val throwable = currentFrame.regs[src1] ?: throw ScriptException("Cannot throw null")
                        val message = when (throwable) {
                            is Value.String -> throwable.value
                            else -> throwable.toString()
                        }
                        throw ScriptException("Uncaught exception: $message")
                    }
                    OpCode.REGISTER_EVENT -> {
                        // Event handlers are registered at compile time - no-op at runtime
                    }
                }
            } catch (e: ScriptException) {
                throw e
            } catch (e: ScriptTimeoutException) {
                throw e
            } catch (e: Exception) {
                throw ScriptException("Runtime error: ${e.message}", e)
            }
        }
    }

    private fun isFalsy(v: Value): Boolean = when (v) {
        is Value.Boolean -> !v.value
        is Value.Null -> true
        else -> false
    }

    private fun toDouble(v: Value): Double = when (v) {
        is Value.Int -> v.value.toDouble()
        is Value.Float -> v.value.toDouble()
        is Value.Double -> v.value
        else -> throw ScriptException("Expected number, got $v")
    }

    private fun binop(a: Value, b: Value, op: (Double, Double) -> Double): Value {
        val result = op(toDouble(a), toDouble(b))
        return when {
            a is Value.Double || b is Value.Double -> Value.Double(result)
            a is Value.Float || b is Value.Float -> Value.Float(result.toFloat())
            else -> Value.Int(result.toInt())
        }
    }

    private fun cmp(a: Value, b: Value, op: (Double, Double) -> Boolean): Value {
        return if (op(toDouble(a), toDouble(b))) Value.Boolean.TRUE else Value.Boolean.FALSE
    }

    private fun negate(a: Value): Value = when (a) {
        is Value.Int -> Value.Int(-a.value)
        is Value.Float -> Value.Float(-a.value)
        is Value.Double -> Value.Double(-a.value)
        else -> throw ScriptException("Expected number, got $a")
    }

    private fun lookupMethod(instance: Value.Instance, name: String): Value? {
        var descriptor: ClassDescriptor? = instance.clazz
        while (descriptor != null) {
            descriptor.methods[name]?.let { return it }
            descriptor = descriptor.superClass
        }
        return null
    }

    private fun isInTypeChain(descriptor: ClassDescriptor, typeName: String): Boolean {
        var current: ClassDescriptor? = descriptor
        while (current != null) {
            if (current.name == typeName) return true
            current = current.superClass
        }
        return false
    }

    private fun fillDefaultArgs(
        args: List<Value>,
        func: Value.Function,
        totalParams: Int,
        callerFrame: CallFrame,
        frames: ArrayDeque<CallFrame>
    ): List<Value> {
        val defaults = func.defaults

        if (defaults == null || args.size == totalParams) {
            if (args.size < totalParams) {
                throw ScriptException("Expected $totalParams arguments but got ${args.size}")
            }
            return args
        }

        if (args.size > totalParams) {
            throw ScriptException("Expected at most $totalParams arguments but got ${args.size}")
        }

        val finalArgs = args.toMutableList()

        for (i in args.size until totalParams) {
            val defaultChunkIdx = defaults.defaultChunks.getOrNull(i)
            if (defaultChunkIdx != null) {
                val defaultChunk = callerFrame.chunk.functions[defaultChunkIdx]
                val defaultFrame = CallFrame(defaultChunk)
                executeDefaultChunk(defaultFrame, frames)
                val defaultValue = defaultFrame.regs[0] ?: Value.Null
                finalArgs.add(defaultValue)
            } else {
                throw ScriptException("Missing required argument at position $i (expected $totalParams arguments, got ${args.size})")
            }
        }

        return finalArgs
    }

    private fun executeDefaultChunk(frame: CallFrame, frames: ArrayDeque<CallFrame>) {
        while (frame.ip < frame.chunk.code.size) {
            // Count instructions in default chunks too
            if (maxInstructions > 0) {
                instructionCount++
                if (instructionCount > maxInstructions) {
                    throw ScriptTimeoutException(instructionCount, maxInstructions)
                }
            }

            val word = frame.chunk.code[frame.ip++]
            val opcode = OpCode.entries.find { it.code == (word and 0xFF).toByte() }
                ?: throw ScriptException("Unknown opcode in default value: ${word and 0xFF}")
            val dst = (word shr 8) and 0x0F
            val src1 = (word shr 12) and 0x0F
            val src2 = (word shr 16) and 0x0F
            val imm = (word shr 20) and 0xFFF

            when (opcode) {
                OpCode.LOAD_IMM -> frame.regs[dst] = frame.chunk.constants[imm]
                OpCode.LOAD_GLOBAL -> frame.regs[dst] = globals[frame.chunk.strings[imm]]
                    ?: throw ScriptException("Undefined global in default value: ${frame.chunk.strings[imm]}")
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
                OpCode.RETURN -> return
                OpCode.SPILL -> frame.spills[imm] = frame.regs[src1]
                OpCode.UNSPILL -> frame.regs[dst] = frame.spills[imm]!!
                OpCode.THROW -> {
                    val throwable = frame.regs[src1] ?: throw ScriptException("Cannot throw null")
                    val message = when (throwable) {
                        is Value.String -> throwable.value
                        else -> throwable.toString()
                    }
                    throw ScriptException("Uncaught exception: $message")
                }
                OpCode.HAS -> {
                    val obj = frame.regs[src1] ?: throw ScriptException("Cannot has on null")
                    val fieldName = frame.chunk.strings[imm]
                    val result = when (obj) {
                        is Value.Instance -> {
                            if (obj.clazz == Builtins.MapClass) {
                                val entriesVal = obj.fields["__entries"]
                                if (entriesVal is Value.InternalMap) {
                                    Value.Boolean(entriesVal.entries.containsKey(Value.String(fieldName)))
                                } else {
                                    Value.Boolean(false)
                                }
                            } else if (obj.clazz == Builtins.ArrayClass) {
                                Value.Boolean(false)
                            } else {
                                Value.Boolean(obj.fields.containsKey(fieldName))
                            }
                        }
                        else -> Value.Boolean(false)
                    }
                    frame.regs[dst] = result
                }
                else -> throw ScriptException("Unsupported opcode in default value: $opcode")
            }
        }
    }
}
