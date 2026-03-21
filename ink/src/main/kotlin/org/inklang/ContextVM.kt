package org.inklang

import org.inklang.lang.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool

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

    // Thread pools for async operations
    private val virtualThreadPool = ForkJoinPool.commonPool()
    private val blockingThreadPool = ForkJoinPool.commonPool()

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
                    OpCode.ASYNC_CALL -> {
                        // Launch async function on virtual thread pool, return Task
                        val passedArgCount = imm
                        val args = (0 until passedArgCount).map { frame.argBuffer.removeFirstOrNull() }
                        val func = frame.regs[src1] as? Value.Function
                            ?: throw ScriptException("ASYNC_CALL requires a Function")

                        val future = CompletableFuture<Value>()
                        virtualThreadPool.submit {
                            try {
                                val asyncFrames = ArrayDeque<CallFrame>()
                                val asyncFrame = CallFrame(func.chunk)
                                asyncFrame.returnDst = 0
                                args.forEachIndexed { i, v -> asyncFrame.regs[i] = v }
                                asyncFrames.addLast(asyncFrame)

                                while (asyncFrames.isNotEmpty()) {
                                    val af = asyncFrames.last()
                                    if (af.ip >= af.chunk.code.size) {
                                        asyncFrames.removeLast()
                                        continue
                                    }
                                    val aword = af.chunk.code[af.ip++]
                                    val aopcode = OpCode.entries.find { it.code == (aword and 0xFF).toByte() }
                                        ?: throw RuntimeException("Unknown opcode in async: ${aword and 0xFF}")
                                    executeAsyncInstr(af, aopcode, aword, asyncFrames, globals)
                                }
                                val result = asyncFrames.lastOrNull()?.regs?.get(0) ?: Value.Null
                                future.complete(result)
                            } catch (e: Exception) {
                                future.completeExceptionally(e)
                            }
                        }
                        frame.regs[dst] = Value.Task(future)
                    }
                    OpCode.AWAIT -> {
                        // Block until the task completes
                        val task = frame.regs[src1] as? Value.Task
                            ?: throw ScriptException("AWAIT requires a Task")
                        try {
                            frame.regs[dst] = task.deferred.get()
                        } catch (e: java.util.concurrent.ExecutionException) {
                            val cause = e.cause ?: e
                            throw ScriptException("Async operation failed: ${cause.message}")
                        }
                    }
                    OpCode.SPAWN -> {
                        // Spawn on blocking thread pool (fire and forget)
                        val passedArgCount = imm
                        val args = (0 until passedArgCount).map { frame.argBuffer.removeFirstOrNull() }
                        val func = frame.regs[src1] as? Value.Function
                            ?: throw ScriptException("SPAWN requires a Function")

                        blockingThreadPool.submit {
                            try {
                                val spawnFrames = ArrayDeque<CallFrame>()
                                val spawnFrame = CallFrame(func.chunk)
                                spawnFrame.returnDst = 0
                                args.forEachIndexed { i, v -> spawnFrame.regs[i] = v }
                                spawnFrames.addLast(spawnFrame)

                                while (spawnFrames.isNotEmpty()) {
                                    val sf = spawnFrames.last()
                                    if (sf.ip >= sf.chunk.code.size) {
                                        spawnFrames.removeLast()
                                        continue
                                    }
                                    val sword = sf.chunk.code[sf.ip++]
                                    val sopcode = OpCode.entries.find { it.code == (sword and 0xFF).toByte() }
                                        ?: throw RuntimeException("Unknown opcode in spawn: ${sword and 0xFF}")
                                    executeAsyncInstr(sf, sopcode, sword, spawnFrames, globals)
                                }
                            } catch (e: Exception) {
                                System.err.println("Spawned task failed: ${e.message}")
                            }
                        }
                        frame.regs[dst] = Value.Null
                    }
                    OpCode.SPAWN_VIRTUAL -> {
                        // Spawn on virtual thread pool (fire and forget)
                        val passedArgCount = imm
                        val args = (0 until passedArgCount).map { frame.argBuffer.removeFirstOrNull() }
                        val func = frame.regs[src1] as? Value.Function
                            ?: throw ScriptException("SPAWN_VIRTUAL requires a Function")

                        virtualThreadPool.submit {
                            try {
                                val spawnFrames = ArrayDeque<CallFrame>()
                                val spawnFrame = CallFrame(func.chunk)
                                spawnFrame.returnDst = 0
                                args.forEachIndexed { i, v -> spawnFrame.regs[i] = v }
                                spawnFrames.addLast(spawnFrame)

                                while (spawnFrames.isNotEmpty()) {
                                    val sf = spawnFrames.last()
                                    if (sf.ip >= sf.chunk.code.size) {
                                        spawnFrames.removeLast()
                                        continue
                                    }
                                    val sword = sf.chunk.code[sf.ip++]
                                    val sopcode = OpCode.entries.find { it.code == (sword and 0xFF).toByte() }
                                        ?: throw RuntimeException("Unknown opcode in spawn: ${sword and 0xFF}")
                                    executeAsyncInstr(sf, sopcode, sword, spawnFrames, globals)
                                }
                            } catch (e: Exception) {
                                System.err.println("Spawned task failed: ${e.message}")
                            }
                        }
                        frame.regs[dst] = Value.Null
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
     * Execute a single instruction for async/spawn operations.
     * This is a simplified executor that handles the basic opcodes needed for async function bodies.
     */
    private fun executeAsyncInstr(
        frame: CallFrame,
        opcode: OpCode,
        word: Int,
        frames: ArrayDeque<CallFrame>,
        globals: MutableMap<String, Value>
    ) {
        val dst = (word shr 8) and 0x0F
        val src1 = (word shr 12) and 0x0F
        val src2 = (word shr 16) and 0x0F
        val imm = (word shr 20) and 0xFFF

        when (opcode) {
            OpCode.LOAD_IMM -> frame.regs[dst] = frame.chunk.constants[imm]
            OpCode.LOAD_GLOBAL -> frame.regs[dst] = globals[frame.chunk.strings[imm]]
                ?: throw ScriptException("Undefined global: ${frame.chunk.strings[imm]}")
            OpCode.STORE_GLOBAL -> globals.put(frame.chunk.strings[imm], frame.regs[src1]!!)
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
                frame.regs[dst] = Value.Function(funcChunk)
            }
            OpCode.PUSH_ARG -> {
                frame.argBuffer.addLast(frame.regs[src1] ?: throw ScriptException("Null in PUSH_ARG"))
            }
            OpCode.CALL -> {
                val passedArgCount = imm
                val args = (0 until passedArgCount).mapNotNull { frame.argBuffer.removeFirstOrNull() }
                val func = frame.regs[src1]
                when (func) {
                    is Value.Function -> {
                        val newFrame = CallFrame(func.chunk)
                        newFrame.returnDst = dst
                        args.forEachIndexed { i, v -> newFrame.regs[i] = v }
                        frames.addLast(newFrame)
                    }
                    is Value.NativeFunction -> {
                        frame.regs[dst] = func.fn(args)
                    }
                    else -> throw ScriptException("Cannot call non-function in async: $func")
                }
            }
            OpCode.RETURN -> {
                val returnVal = frame.regs[src1]
                val returnDst = frame.returnDst
                frames.removeLast()
                if (frames.isNotEmpty()) {
                    frames.last().regs[returnDst] = returnVal
                }
            }
            OpCode.AWAIT -> {
                // In async function body, await blocks the virtual thread (cheap with Loom)
                val task = frame.regs[src1] as? Value.Task
                    ?: throw ScriptException("Cannot await non-Task value")
                try {
                    frame.regs[dst] = task.deferred.join()
                } catch (e: Throwable) {
                    frame.regs[dst] = Value.String(e.message ?: "Unknown error")
                }
            }
            OpCode.NEW_ARRAY -> {
                val count = imm
                val elements = (0 until count).mapNotNull { frame.argBuffer.removeFirstOrNull() }
                frame.regs[dst] = Builtins.newArray(elements.toMutableList())
            }
            OpCode.GET_FIELD -> {
                val obj = frame.regs[src1]!!
                val fieldName = frame.chunk.strings[imm]
                frame.regs[dst] = when (obj) {
                    is Value.Instance -> obj.fields[fieldName] ?: Value.Null
                    is Value.String -> getStringMethod(obj, fieldName) ?: Value.Null
                    else -> Value.Null
                }
            }
            OpCode.SET_FIELD -> {
                val obj = frame.regs[src1]!!
                val fieldName = frame.chunk.strings[imm]
                if (obj is Value.Instance) {
                    obj.fields[fieldName] = frame.regs[src2] ?: Value.Null
                }
            }
            OpCode.NEW_INSTANCE -> {
                val classVal = frame.regs[src1] as? Value.Class
                    ?: throw ScriptException("NEW_INSTANCE requires a Class")
                frame.regs[dst] = Value.Instance(classVal.descriptor)
            }
            OpCode.GET_INDEX -> {
                val obj = frame.regs[src1]!!
                val index = (frame.regs[src2] as? Value.Int)?.value ?: 0
                frame.regs[dst] = when (obj) {
                    is Value.Instance -> {
                        val items = obj.fields["__items"] as? Value.InternalList
                            ?: obj.fields["__tuple"] as? Value.InternalTuple
                        when {
                            items is Value.InternalList -> items.items.getOrElse(index) { Value.Null }
                            items is Value.InternalTuple -> items.items.getOrElse(index) { Value.Null }
                            else -> Value.Null
                        }
                    }
                    else -> Value.Null
                }
            }
            OpCode.SET_INDEX -> {
                val obj = frame.regs[src1]!!
                val index = (frame.regs[src2] as? Value.Int)?.value ?: 0
                val value = frame.regs[dst] ?: Value.Null
                if (obj is Value.Instance) {
                    val items = obj.fields["__items"] as? Value.InternalList
                    if (items != null && index >= 0 && index < items.items.size) {
                        items.items[index] = value
                    }
                }
            }
            OpCode.MOVE -> frame.regs[dst] = frame.regs[src1]
            OpCode.POP -> { /* no-op */ }
            else -> throw ScriptException("Unsupported opcode in async: $opcode")
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
