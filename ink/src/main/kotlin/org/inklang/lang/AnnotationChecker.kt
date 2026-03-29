package org.inklang.lang

import org.inklang.grammar.CstNode

/**
 * Type checker for validating async/await expressions at compile time.
 *
 * Validates:
 * - `await` only works on Task types (compile error otherwise)
 * - `spawn` target is callable
 * - Type inference for async function calls (returning Task)
 *
 * This checker performs static analysis on bytecode chunks to infer types
 * and validate async/await constraints before execution.
 */
class AnnotationChecker {
    private val typeErrors = mutableListOf<TypeError>()

    /**
     * Validates a chunk for type errors, particularly for async/await expressions.
     * @param chunk The chunk to validate
     * @return List of type errors found (empty if validation passes)
     */
    fun validate(chunk: Chunk): List<TypeError> {
        typeErrors.clear()

        // Validate the main chunk
        validateChunkBody(chunk, isAsync = false)

        // Validate all function chunks
        chunk.functionDefaults.forEachIndexed { idx, defaults ->
            if (idx < chunk.functions.size) {
                val funcChunk = chunk.functions[idx]
                // Functions are synchronous by default, async functions would need metadata
                // For now, we'll detect async functions by looking for ASYNC_CALL/AWAIT/SPAWN opcodes
                val isAsync = hasAsyncOpcodes(funcChunk)
                validateChunkBody(funcChunk, isAsync)
            }
        }

        return typeErrors.toList()
    }

    /**
     * Validates a chunk body by inferring types and checking constraints.
     */
    private fun validateChunkBody(chunk: Chunk, isAsync: Boolean) {
        val typeEnv = TypeEnvironment()

        var ip = 0
        while (ip < chunk.code.size) {
            val word = chunk.code[ip++]
            val opcode = OpCode.fromByte((word and 0xFF).toByte())
            val dst = (word shr 8) and 0x0F
            val src1 = (word shr 12) and 0x0F
            val src2 = (word shr 16) and 0x0F
            val imm = (word shr 20) and 0xFFF

            when (opcode) {
                OpCode.LOAD_IMM -> {
                    val constant = chunk.constants.getOrNull(imm)
                    val type = when (constant) {
                        is Value.Int -> Type.Int
                        is Value.Float -> Type.Float
                        is Value.Double -> Type.Double
                        is Value.String -> Type.Str
                        is Value.Boolean -> Type.Bool
                        is Value.Null -> Type.Null
                        else -> Type.Unknown
                    }
                    typeEnv.setRegisterType(dst, type)
                }

                OpCode.LOAD_GLOBAL -> {
                    // We can't infer global types without additional metadata
                    // For now, mark as Unknown
                    typeEnv.setRegisterType(dst, Type.Unknown)
                }

                OpCode.STORE_GLOBAL -> {
                    // No type checking needed for stores
                }

                OpCode.MOVE -> {
                    typeEnv.copyRegisterType(src1, dst)
                }

                OpCode.ADD, OpCode.SUB, OpCode.MUL, OpCode.DIV, OpCode.MOD, OpCode.POW -> {
                    val type1 = typeEnv.getRegisterType(src1)
                    val type2 = typeEnv.getRegisterType(src2)
                    val resultType = inferNumericType(type1, type2)
                    typeEnv.setRegisterType(dst, resultType)
                }

                OpCode.NEG -> {
                    val type1 = typeEnv.getRegisterType(src1)
                    typeEnv.setRegisterType(dst, type1)
                }

                OpCode.NOT -> {
                    typeEnv.setRegisterType(dst, Type.Bool)
                }

                OpCode.EQ, OpCode.NEQ, OpCode.LT, OpCode.LTE, OpCode.GT, OpCode.GTE -> {
                    typeEnv.setRegisterType(dst, Type.Bool)
                }

                OpCode.LOAD_FUNC -> {
                    val funcChunk = chunk.functions.getOrNull(imm)
                    if (funcChunk != null) {
                        // For now, we can't infer function parameter/return types
                        // This would require additional metadata from the compiler
                        val funcType = Type.Function(emptyList(), Type.Unknown)
                        typeEnv.setRegisterType(dst, funcType)
                    }
                }

                OpCode.CALL -> {
                    // We can't infer much about the return type without metadata
                    // Mark as Unknown, unless we know the function being called
                    val funcType = typeEnv.getRegisterType(src1)
                    val returnType = if (funcType is Type.Function) {
                        funcType.returnType
                    } else {
                        Type.Unknown
                    }
                    typeEnv.setRegisterType(dst, returnType)
                }

                OpCode.ASYNC_CALL -> {
                    val funcType = typeEnv.getRegisterType(src1)
                    // Async calls return Task<T> where T is the function's return type
                    val innerType = if (funcType is Type.Function) {
                        funcType.returnType
                    } else {
                        Type.Unknown
                    }
                    typeEnv.setRegisterType(dst, Type.Task(innerType))
                }

                OpCode.AWAIT -> {
                    val taskType = typeEnv.getRegisterType(src1)
                    if (taskType != Type.Unknown && !taskType.isTask()) {
                        typeErrors.add(
                            TypeError.AwaitNonTask(
                                instruction = ip - 1,
                                actualType = taskType
                            )
                        )
                    }
                    // Unwrap the Task type to get the inner type
                    val innerType = taskType.unwrapTask()
                    typeEnv.setRegisterType(dst, innerType)
                }

                OpCode.SPAWN, OpCode.SPAWN_VIRTUAL -> {
                    val funcType = typeEnv.getRegisterType(src1)
                    if (funcType != Type.Unknown && !funcType.isCallable()) {
                        typeErrors.add(
                            TypeError.SpawnNonCallable(
                                instruction = ip - 1,
                                actualType = funcType,
                                isVirtual = opcode == OpCode.SPAWN_VIRTUAL
                            )
                        )
                    }
                    // Spawn returns Task<void> (null) - fire and forget
                    typeEnv.setRegisterType(dst, Type.Task(Type.Null))
                }

                OpCode.NEW_ARRAY -> {
                    typeEnv.setRegisterType(dst, Type.Array(Type.Unknown))
                }

                OpCode.NEW_INSTANCE -> {
                    val classVal = typeEnv.getRegisterType(src1)
                    if (classVal is Type.Class) {
                        typeEnv.setRegisterType(dst, classVal)
                    } else {
                        typeEnv.setRegisterType(dst, Type.Unknown)
                    }
                }

                OpCode.GET_INDEX -> {
                    typeEnv.setRegisterType(dst, Type.Unknown)
                }

                OpCode.GET_FIELD -> {
                    typeEnv.setRegisterType(dst, Type.Unknown)
                }

                OpCode.BUILD_CLASS -> {
                    typeEnv.setRegisterType(dst, Type.Unknown)
                }

                OpCode.RANGE -> {
                    typeEnv.setRegisterType(dst, Type.Class("Range"))
                }

                OpCode.JUMP, OpCode.JUMP_IF_FALSE -> {
                    // Control flow - no type inference needed
                }

                OpCode.PUSH_ARG -> {
                    // Argument pushing - no type inference needed
                }

                OpCode.RETURN -> {
                    // Return - no type inference needed
                }

                OpCode.POP, OpCode.BREAK, OpCode.NEXT -> {
                    // No type inference needed
                }

                OpCode.SET_FIELD, OpCode.SET_INDEX -> {
                    // No type inference needed
                }

                OpCode.IS_TYPE, OpCode.HAS -> {
                    typeEnv.setRegisterType(dst, Type.Bool)
                }

                OpCode.SPILL, OpCode.UNSPILL -> {
                    // No type inference needed
                }

                OpCode.THROW -> {
                    // No type inference needed
                }

                OpCode.REGISTER_EVENT -> {
                    // No type inference needed
                }

                OpCode.GET_UPVALUE -> {
                    typeEnv.setRegisterType(dst, Type.Unknown)
                }

                OpCode.CALL_HANDLER -> {
                    // No type inference needed
                }

                null -> {
                    // Invalid opcode - will be caught at runtime
                }
            }
        }
    }

    /**
     * Infers the result type of a numeric operation.
     */
    private fun inferNumericType(type1: Type, type2: Type): Type {
        // If either is Double, result is Double
        if (type1 == Type.Double || type2 == Type.Double) return Type.Double
        // If either is Float, result is Float
        if (type1 == Type.Float || type2 == Type.Float) return Type.Float
        // Otherwise, Int
        return Type.Int
    }

    /**
     * Checks if a chunk contains async-related opcodes.
     */
    private fun hasAsyncOpcodes(chunk: Chunk): Boolean {
        return chunk.code.any { word ->
            val opcode = OpCode.fromByte((word and 0xFF).toByte())
            opcode == OpCode.ASYNC_CALL || opcode == OpCode.AWAIT ||
                    opcode == OpCode.SPAWN || opcode == OpCode.SPAWN_VIRTUAL
        }
    }

    /**
     * Type environment for tracking register types during analysis.
     */
    private class TypeEnvironment {
        private val registerTypes = mutableMapOf<Int, Type>()

        fun getRegisterType(reg: Int): Type {
            return registerTypes[reg] ?: Type.Unknown
        }

        fun setRegisterType(reg: Int, type: Type) {
            registerTypes[reg] = type
        }

        fun copyRegisterType(src: Int, dst: Int) {
            registerTypes[dst] = registerTypes[src]
        }
    }
}

/**
 * Type errors that can be detected by AnnotationChecker.
 */
sealed class TypeError {
    abstract val message: String

    /**
     * Error: await called on non-Task value.
     */
    data class AwaitNonTask(
        val instruction: Int,
        val actualType: Type
    ) : TypeError() {
        override val message: String =
            "await requires a Task value, but got type: $actualType"
    }

    /**
     * Error: spawn called on non-callable value.
     */
    data class SpawnNonCallable(
        val instruction: Int,
        val actualType: Type,
        val isVirtual: Boolean
    ) : TypeError() {
        override val message: String =
            "spawn${if (isVirtual) " virtual" else ""} requires a callable value (function), but got type: $actualType"
    }
}
