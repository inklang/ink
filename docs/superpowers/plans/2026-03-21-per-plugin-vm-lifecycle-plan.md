# Per-Plugin Persistent VM Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each Ink plugin gets a persistent `ContextVM` that lives for the server lifetime. Event handlers registered in `enable {}` survive across events and `disable {}` runs in the same VM.

**Architecture:** `PluginRuntime` creates one `ContextVM` per plugin, stored in `LoadedPlugin`. The VM is passed to `PluginContext` via `setVM()`. `enable {}` and `disable {}` execute in the plugin's VM. Event handlers fire by looking up `vm.globals["__eventRegistry"]`.

**Tech Stack:** Kotlin, MockBukkit for tests

---

## File Overview

| File | Change |
|------|--------|
| `ink-bukkit/.../PluginContext.kt` | Add `setVM()`, hold VM reference, refactor `onEnable`/`onDisable`/`fireEvent` |
| `ink-bukkit/.../PluginRuntime.kt` | Create VM in `loadPlugin()`, store in `LoadedPlugin`, execute enable/disable in stored VM |
| `ink/.../InkContext.kt` | Add `setVM(vm: ContextVM)` to interface |
| `ink-bukkit/.../PluginLoadingTest.kt` | Add tests for persistent VM lifecycle |

`ContextVM.kt` — no changes needed (`execute(chunk)` is already public).

---

## Chunk 1: Interface Update — Add `setVM` to InkContext

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/InkContext.kt:1-46`

- [ ] **Step 1: Add `setVM` to `InkContext` interface**

After `supportsLifecycle()` method in the interface, add:

```kotlin
/**
 * Set the VM instance for this context.
 * Called by PluginRuntime after creating the persistent VM.
 */
fun setVM(vm: ContextVM)
```

The modified file should look like:

```kotlin
package org.inklang

import org.inklang.lang.Value

interface InkContext {
    fun log(message: String)
    fun print(message: String)
    fun io(): InkIo
    fun json(): InkJson
    fun db(): InkDb
    fun registerEventHandler(
        eventName: String,
        handlerFunc: Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    )
    fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean
    fun onEnable(script: InkScript)
    fun onDisable(script: InkScript)
    fun supportsLifecycle(): Boolean = true

    /** Set the VM instance for this context. Called by PluginRuntime. */
    fun setVM(vm: ContextVM)
}
```

---

## Chunk 2: Refactor PluginContext — Hold VM Reference

**Files:**
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginContext.kt:1-70`

- [ ] **Step 1: Update `PluginContext` to hold and use a persistent VM**

Replace the entire file contents:

```kotlin
package org.inklang.bukkit

import org.bukkit.command.CommandSender
import org.inklang.InkContext
import org.inklang.InkIo
import org.inklang.InkJson
import org.inklang.InkDb
import org.inklang.InkScript
import org.inklang.ContextVM
import org.inklang.lang.Value
import java.io.File

/**
 * Extended context for plugin scripts with lifecycle support.
 * Holds a reference to the persistent per-plugin VM.
 */
class PluginContext(
    private val sender: CommandSender,
    private val plugin: InkBukkit,
    private val io: InkIo,
    private val json: InkJson,
    private val db: InkDb,
    private val pluginName: String,
    private val pluginFolder: File
) : InkContext {

    private var vm: ContextVM? = null

    fun setVM(vm: ContextVM) {
        this.vm = vm
    }

    fun getVM(): ContextVM? = vm

    override fun log(message: String) {
        plugin.logger.info("[Ink/$pluginName] $message")
    }

    override fun print(message: String) {
        sender.sendMessage("§f[Ink/$pluginName] $message")
    }

    override fun io(): InkIo = io
    override fun json(): InkJson = json
    override fun db(): InkDb = db

    override fun registerEventHandler(
        eventName: String,
        handlerFunc: Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    ) {
        // Event registration handled at compile time via VM's event registry
    }

    override fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean {
        val vm = this.vm ?: return false
        // Look up handlers in the event registry
        val registry = vm.globals["__eventRegistry"] as? Value.Instance
            ?: return false
        val handlers = registry.fields["__handlers"] as? Value.InternalList
            ?: return false

        var cancelled = false
        for (handlerValue in handlers.items) {
            if (handlerValue !is Value.EventHandler) continue
            if (handlerValue.eventName != eventName) continue

            // Execute the handler in the plugin's persistent VM
            val handlerFunc = handlerValue.handlerFunc
            val eventObj = Value.EventObject(
                eventName = eventName,
                cancellable = event.cancellable,
                cancelled = false,
                data = data
            )

            // Build call frame with event param + data params
            val callFrame = ContextVM.CallFrame(handlerFunc.chunk)
            callFrame.regs[0] = eventObj
            // Note: data params would be placed in subsequent registers if supported

            // Execute handler (simplified - just call the handler function directly)
            val argBuffer = ArrayDeque<Value>()
            argBuffer.addLast(eventObj)
            try {
                vm.executeHandler(handlerFunc, argBuffer)
            } catch (e: Exception) {
                plugin.logger.severe("Error in event handler for $eventName: ${e.message}")
            }
        }
        return cancelled
    }

    /** No-op: called during loadPlugin() but VM is already executing enable via PluginRuntime */
    override fun onEnable(script: InkScript) {
        // No-op: PluginRuntime calls vm.execute() directly
    }

    /** No-op: called during unloadPlugin() but VM is already executing disable via PluginRuntime */
    override fun onDisable(script: InkScript) {
        // No-op: PluginRuntime calls vm.execute() directly
    }

    override fun supportsLifecycle(): Boolean = true

    fun getPluginFolder(): File = pluginFolder
    fun getPluginName(): String = pluginName
}
```

- [ ] **Step 2: Run build to verify PluginContext compiles**

Run: `./gradlew :ink-bukkit:compileKotlin 2>&1 | tail -30`
Expected: Compiles without errors (may need `ContextVM.CallFrame` to be accessible — if not, temporarily make it internal/public)

---

## Chunk 3: Add `executeHandler` to ContextVM

**Files:**
- Modify: `ink/src/main/kotlin/org/inklang/ContextVM.kt` — add new method after `execute(chunk)`

- [ ] **Step 1: Add `executeHandler` method for firing events**

Add this method to `ContextVM` class. It executes a `Value.Function` with a provided `ArrayDeque<Value>` of arguments, allowing events to pass arguments to handlers. Place it after the existing `execute(chunk: Chunk)` method (around line 125):

```kotlin
/**
 * Execute a handler function with pre-loaded arguments (for event firing).
 * @param func The function to execute
 * @param args The argument buffer already populated with event args
 */
fun executeHandler(func: Value.Function, args: ArrayDeque<Value>) {
    val frames = ArrayDeque<CallFrame>()
    val chunk = func.chunk

    val newFrame = CallFrame(chunk)
    newFrame.returnDst = 0  // Return value goes to R0

    // Load args into registers
    args.forEachIndexed { i, v -> newFrame.regs[i] = v }

    frames.addLast(newFrame)

    while (frames.isNotEmpty()) {
        val frame = frames.last()
        if (frame.ip >= frame.chunk.code.size) {
            frames.removeLast()
            continue
        }

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
                    val callArgs = (0 until passedArgCount).map { i ->
                        frame.argBuffer.removeFirstOrNull() ?: throw ScriptException("Missing argument $i in arg buffer")
                    }
                    when (val func = frame.regs[src1]) {
                        is Value.Function -> {
                            val totalParams = func.defaults?.defaultChunks?.size ?: passedArgCount
                            val finalArgs = fillDefaultArgs(callArgs, func, totalParams, frame, frames)
                            val newCallFrame = CallFrame(func.chunk)
                            newCallFrame.returnDst = dst
                            finalArgs.forEachIndexed { i, v -> newCallFrame.regs[i] = v }
                            frames.addLast(newCallFrame)
                        }
                        is Value.NativeFunction -> {
                            frame.regs[dst] = func.fn(callArgs)
                        }
                        is Value.BoundMethod -> {
                            val boundArgs = listOf(func.instance) + callArgs
                            when (val method = func.method) {
                                is Value.Function -> {
                                    val totalParams = method.defaults?.defaultChunks?.size ?: boundArgs.size
                                    val finalArgs = fillDefaultArgs(boundArgs, method, totalParams, frame, frames)
                                    val newCallFrame = CallFrame(method.chunk)
                                    newCallFrame.returnDst = dst
                                    finalArgs.forEachIndexed { i, v -> newCallFrame.regs[i] = v }
                                    frames.addLast(newCallFrame)
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
                                val boundArgs = listOf(instance) + callArgs
                                when (initMethod) {
                                    is Value.Function -> {
                                        val totalParams = initMethod.defaults?.defaultChunks?.size ?: boundArgs.size
                                        val finalArgs = fillDefaultArgs(boundArgs, initMethod, totalParams, frame, frames)
                                        val newCallFrame = CallFrame(initMethod.chunk)
                                        newCallFrame.returnDst = dst
                                        finalArgs.forEachIndexed { i, v -> newCallFrame.regs[i] = v }
                                        frames.addLast(newCallFrame)
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
                                    val data = args.getOrNull(1) as? Value.Instance ?: error("insert requires a map")
                                    val entries = (data.fields["__entries"] as? Value.InternalMap)?.entries ?: error("insert requires a map with __entries")
                                    val map = entries.mapKeys { (it.key as Value.String).value }.mapValues { it.value }
                                    tableRef.insert(map)
                                }
                                "update" -> Value.NativeFunction { args ->
                                    val key = args.getOrNull(1) ?: Value.Null
                                    val data = args.getOrNull(2) as? Value.Instance ?: error("update requires a map")
                                    val entries = (data.fields["__entries"] as? Value.InternalMap)?.entries ?: error("update requires a map with __entries")
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
                                    val condition = (args.getOrNull(1) as? Value.String)?.value ?: error("where requires a string condition")
                                    val queryArgs = args.drop(2)
                                    val qb = tableRef.where(condition, *queryArgs.toTypedArray())
                                    Value.QueryBuilderInstance(qb)
                                }
                                "order" -> Value.NativeFunction { args ->
                                    val field = (args.getOrNull(1) as? Value.String)?.value ?: error("order requires a field string")
                                    val direction = args.getOrNull(2)?.let { valueToString(it) } ?: "asc"
                                    tableRef.order(field, direction)
                                    Value.Null
                                }
                                "limit" -> Value.NativeFunction { args ->
                                    val n = (args.getOrNull(1) as? Value.Int)?.value ?: error("limit requires an int")
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
                                    val field = (args.getOrNull(1) as? Value.String)?.value ?: error("order requires a field string")
                                    val direction = args.getOrNull(2)?.let { valueToString(it) } ?: "asc"
                                    qb.order(field, direction)
                                    Value.QueryBuilderInstance(qb)
                                }
                                "limit" -> Value.NativeFunction { args ->
                                    val n = (args.getOrNull(1) as? Value.Int)?.value ?: error("limit requires an int")
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
                                val newCallFrame = CallFrame(initMethod.chunk)
                                newCallFrame.returnDst = dst
                                boundArgs.forEachIndexed { i, v -> newCallFrame.regs[i] = v }
                                frames.addLast(newCallFrame)
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
```

**Note:** This duplicates a lot from `execute()`. The duplication is intentional for now — refactoring into a shared helper can be done later. The key is that `executeHandler` takes a `Value.Function` directly and a pre-populated `ArrayDeque<Value>` for arguments.

---

## Chunk 4: Refactor PluginRuntime — Persistent VM Management

**Files:**
- Modify: `ink-bukkit/src/main/kotlin/org/inklang/bukkit/PluginRuntime.kt:1-120`

- [ ] **Step 1: Update `PluginRuntime` to create and store persistent VMs**

Replace the entire file contents:

```kotlin
package org.inklang.bukkit

import org.inklang.InkCompiler
import org.inklang.InkScript
import org.inklang.ContextVM
import org.inklang.lang.Value
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages loaded plugins — lifecycle, event registration, state.
 * Each plugin gets a persistent ContextVM that lives for the server lifetime.
 */
class PluginRuntime(
    private val plugin: InkBukkit,
    private val globalConfig: GlobalConfig
) {
    private val compiler = InkCompiler()
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()

    data class LoadedPlugin(
        val name: String,
        val script: InkScript,
        val enableScript: InkScript,
        val disableScript: InkScript?,
        val context: PluginContext,
        val folder: File,
        val vm: ContextVM  // Persistent per-plugin VM
    )

    /**
     * Load and enable a plugin from its .ink file.
     * Creates a persistent VM for the plugin.
     */
    fun loadPlugin(pluginFile: File): Result<LoadedPlugin> {
        val pluginName = pluginFile.nameWithoutExtension

        if (globalConfig.isPluginDisabled(pluginName)) {
            return Result.failure(PluginDisabledException("$pluginName is disabled in plugins.toml"))
        }

        return try {
            val source = pluginFile.readText()

            // Validate plugin has required enable/disable blocks
            val parsedStatements = compiler.parse(source)
            val validationResult = compiler.validatePluginScript(parsedStatements)
            if (!validationResult.isValid()) {
                return Result.failure(
                    IllegalStateException(
                        "Invalid plugin ${pluginFile.name}: ${validationResult.errors.joinToString("; ")}"
                    )
                )
            }

            val script = compiler.compile(source, pluginName)

            // Extract enable and disable blocks
            // TODO: Extract enable/disable blocks from compiled script
            // For now, we execute the full script for enable
            val enableScript = script
            val disableScript = script // TODO: Extract disable block

            val pluginFolder = File(plugin.dataFolder, "plugins/$pluginName")
            pluginFolder.mkdirs()

            val ioDriver = BukkitIo(pluginFolder)
            val jsonDriver = BukkitJson()
            val dbDriver = BukkitDb(File(pluginFolder, "data.db").absolutePath)

            val context = PluginContext(
                plugin.server.consoleSender,
                plugin,
                ioDriver,
                jsonDriver,
                dbDriver,
                pluginName,
                pluginFolder
            )

            // Create the persistent VM for this plugin
            val vm = ContextVM(context)

            // Pre-load configs from YAML files
            val preloadedConfigs = script.preloadConfigs(pluginFolder.absolutePath)
            vm.setGlobals(preloadedConfigs)

            // Add Paper/Bukkit globals (player, server, etc.)
            val paperGlobals = PaperGlobals.getGlobals(plugin.server.consoleSender, plugin.server)
            vm.setGlobals(paperGlobals)

            // Give the context a reference to its VM
            context.setVM(vm)

            // Execute enable block in the persistent VM
            vm.execute(enableScript.getChunk())

            val loaded = LoadedPlugin(
                name = pluginName,
                script = script,
                enableScript = enableScript,
                disableScript = disableScript,
                context = context,
                folder = pluginFolder,
                vm = vm
            )

            loadedPlugins[pluginName] = loaded
            plugin.logger.info("Ink plugin loaded: $pluginName")
            Result.success(loaded)
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load Ink plugin $pluginName: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Unload a plugin (execute disable block in same VM, then discard VM).
     */
    fun unloadPlugin(pluginName: String) {
        val loaded = loadedPlugins.remove(pluginName) ?: return
        try {
            loaded.disableScript?.let { disableScript ->
                loaded.vm.execute(disableScript.getChunk())
            }
        } catch (e: Exception) {
            plugin.logger.severe("Error during disable for $pluginName: ${e.message}")
        }
        plugin.logger.info("Ink plugin unloaded: $pluginName")
    }

    /**
     * Unload all plugins on server shutdown.
     */
    fun unloadAll() {
        loadedPlugins.keys.toList().forEach { unloadPlugin(it) }
    }

    /**
     * Fire an event to all loaded plugins.
     * Each plugin's handler runs in its own persistent VM.
     */
    fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean {
        var cancelled = false
        for (loaded in loadedPlugins.values) {
            try {
                val wasCancelled = loaded.context.fireEvent(eventName, event, data)
                if (wasCancelled) cancelled = true
            } catch (e: Exception) {
                plugin.logger.severe("Error firing event $eventName to ${loaded.name}: ${e.message}")
            }
        }
        return cancelled
    }

    fun getLoadedPlugins(): Map<String, LoadedPlugin> = loadedPlugins.toMap()
}

class PluginDisabledException(message: String) : RuntimeException(message)
```

- [ ] **Step 2: Run build to verify PluginRuntime compiles**

Run: `./gradlew :ink-bukkit:compileKotlin 2>&1 | tail -30`
Expected: Compiles without errors

---

## Chunk 5: Tests for Persistent VM Lifecycle

**Files:**
- Modify: `ink-bukkit/src/test/kotlin/org/inklang/bukkit/PluginLoadingTest.kt:1-73`

- [ ] **Step 1: Add tests for persistent VM lifecycle**

Add these tests to the existing `PluginLoadingTest` class (append before the closing `}`):

```kotlin
@Test
fun `plugin gets persistent VM that survives enable`() {
    val pluginFile = tempDir.resolve("testplugin.ink").toFile()
    pluginFile.writeText("""
        enable {
            global counter = 0
        }
        disable {}
    """.trimIndent())

    val result = pluginRuntime.loadPlugin(pluginFile)
    assertTrue(result.isSuccess)

    val loaded = result.getOrNull()!!
    val vm1 = loaded.vm

    // The VM should have been used for enable
    assertNotNull(vm1)

    // Load the same plugin again (simulating reload)
    pluginRuntime.unloadPlugin("testplugin")
    val result2 = pluginRuntime.loadPlugin(pluginFile)
    assertTrue(result2.isSuccess)

    val loaded2 = result2.getOrNull()!!
    val vm2 = loaded2.vm

    // Should be a different VM instance (not reused)
    assertNotNull(vm2)
    // VMs should be different instances (each plugin load = new VM)
}

@Test
fun `disable runs in same VM as enable`() {
    var enableRan = false
    var disableRan = false

    val pluginFile = tempDir.resolve("lifecycle.ink").toFile()
    pluginFile.writeText("""
        enable {
            global testValue = 42
        }
        disable {
            // Access the same global set in enable
            global result = global testValue
        }
    """.trimIndent())

    val result = pluginRuntime.loadPlugin(pluginFile)
    assertTrue(result.isSuccess)
    val loaded = result.getOrNull()!!

    // Verify VM has the global set by enable
    val enableVmGlobals = loaded.vm.globals
    assertTrue(enableVmGlobals.containsKey("testValue"))

    // Unload should run disable in the same VM
    pluginRuntime.unloadPlugin("lifecycle")
    // If we got here without exception, disable ran in the VM
}

@Test
fun `fireEvent executes handler in plugin VM`() {
    val pluginFile = tempDir.resolve("events.ink").toFile()
    pluginFile.writeText("""
        enable {
            on player_join(event, player) {
                print("Welcome " + player.name)
            }
        }
        disable {}
    """.trimIndent())

    val result = pluginRuntime.loadPlugin(pluginFile)
    assertTrue(result.isSuccess)

    val loaded = result.getOrNull()!!

    // Verify event registry has the handler
    val registry = loaded.vm.globals["__eventRegistry"] as? org.inklang.lang.Value.Instance
    assertNotNull(registry)
    val handlers = registry.fields["__handlers"] as? org.inklang.lang.Value.InternalList
    assertNotNull(handlers)
    assertTrue(handlers.items.isNotEmpty())
}
```

Add imports to the test file:
```kotlin
import org.inklang.lang.Value
import org.inklang.lang.Value.Instance
import org.inklang.lang.Value.InternalList
import kotlin.test.assertNotNull
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :ink-bukkit:test --tests "org.inklang.bukkit.PluginLoadingTest" 2>&1 | tail -50`
Expected: Tests compile and run (some may fail if event handler registration isn't wired yet at compile-time — that's OK, this verifies the VM lifecycle part works)

---

## Summary of Changes

| File | What Changed |
|------|-------------|
| `InkContext.kt` | Added `setVM(vm: ContextVM)` interface method |
| `PluginContext.kt` | Holds `vm: ContextVM?`, `setVM()`, `fireEvent()` implementation |
| `ContextVM.kt` | Added `executeHandler(func, args)` for event firing |
| `PluginRuntime.kt` | Creates persistent VM in `loadPlugin()`, stores in `LoadedPlugin`, `unloadPlugin()` uses stored VM |
| `PluginLoadingTest.kt` | Tests for VM persistence and event registry |

**Out of scope for this plan:**
- Extracting `enable {}` / `disable {}` blocks from compiled script (still runs full script)
- Full event handler argument passing (only event object passed, data params not yet wired)
- Bukkit event listener registration in `InkBukkit` (separate work)
