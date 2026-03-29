package org.inklang.lang

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CompletableFuture

class AnnotationCheckerTest {
    private lateinit var checker: AnnotationChecker

    @BeforeEach
    fun setup() {
        checker = AnnotationChecker()
    }

    @Test
    fun `await on Task type should pass validation`() {
        val chunk = Chunk().apply {
            // r0 = LOAD_IMM (Value.Task)
            addConstant(Value.Task(CompletableFuture.completedFuture(Value.Int(42))))
            write(OpCode.LOAD_IMM, dst = 0, imm = 0)

            // r1 = AWAIT r0
            write(OpCode.AWAIT, dst = 1, src1 = 0)
        }

        val errors = checker.validate(chunk)
        assertTrue(errors.isEmpty(), "Should have no errors, but got: $errors")
    }

    @Test
    fun `await on non-Task type should produce error`() {
        val chunk = Chunk().apply {
            // r0 = LOAD_IMM (Value.Int)
            addConstant(Value.Int(42))
            write(OpCode.LOAD_IMM, dst = 0, imm = 0)

            // r1 = AWAIT r0 - ERROR: await on non-Task
            write(OpCode.AWAIT, dst = 1, src1 = 0)
        }

        val errors = checker.validate(chunk)
        assertEquals(1, errors.size, "Should have exactly one error")
        assertTrue(errors[0] is TypeError.AwaitNonTask)
        val error = errors[0] as TypeError.AwaitNonTask
        assertEquals(Type.Int, error.actualType)
        assertTrue(error.message.contains("await requires a Task value"))
    }

    @Test
    fun `spawn on function type should pass validation`() {
        val chunk = Chunk().apply {
            // Create a simple function chunk
            val funcChunk = Chunk().apply {
                write(OpCode.LOAD_IMM, dst = 0, imm = 0)
                write(OpCode.RETURN, src1 = 0)
            }
            addConstant(Value.Int(42))
            functions.add(funcChunk)

            // r0 = LOAD_FUNC
            write(OpCode.LOAD_FUNC, dst = 0, imm = 0)

            // r1 = SPAWN r0
            write(OpCode.SPAWN, dst = 1, src1 = 0)
        }

        val errors = checker.validate(chunk)
        assertTrue(errors.isEmpty(), "Should have no errors, but got: $errors")
    }

    @Test
    fun `spawn on non-callable type should produce error`() {
        val chunk = Chunk().apply {
            // r0 = LOAD_IMM (Value.Int)
            addConstant(Value.Int(42))
            write(OpCode.LOAD_IMM, dst = 0, imm = 0)

            // r1 = SPAWN r0 - ERROR: spawn on non-callable
            write(OpCode.SPAWN, dst = 1, src1 = 0)
        }

        val errors = checker.validate(chunk)
        assertEquals(1, errors.size, "Should have exactly one error")
        assertTrue(errors[0] is TypeError.SpawnNonCallable)
        val error = errors[0] as TypeError.SpawnNonCallable
        assertEquals(Type.Int, error.actualType)
        assertFalse(error.isVirtual)
        assertTrue(error.message.contains("spawn requires a callable value"))
    }

    @Test
    fun `spawn virtual on non-callable type should produce error`() {
        val chunk = Chunk().apply {
            // r0 = LOAD_IMM (Value.String)
            addConstant(Value.String("not a function"))
            write(OpCode.LOAD_IMM, dst = 0, imm = 0)

            // r1 = SPAWN_VIRTUAL r0 - ERROR: spawn virtual on non-callable
            write(OpCode.SPAWN_VIRTUAL, dst = 1, src1 = 0)
        }

        val errors = checker.validate(chunk)
        assertEquals(1, errors.size, "Should have exactly one error")
        assertTrue(errors[0] is TypeError.SpawnNonCallable)
        val error = errors[0] as TypeError.SpawnNonCallable
        assertEquals(Type.Str, error.actualType)
        assertTrue(error.isVirtual)
        assertTrue(error.message.contains("spawn virtual"))
    }

    @Test
    fun `async call should produce Task type`() {
        val chunk = Chunk().apply {
            // Create a function chunk
            val funcChunk = Chunk().apply {
                write(OpCode.LOAD_IMM, dst = 0, imm = 0)
                write(OpCode.RETURN, src1 = 0)
            }
            addConstant(Value.Int(42))
            functions.add(funcChunk)

            // r0 = LOAD_FUNC
            write(OpCode.LOAD_FUNC, dst = 0, imm = 0)

            // r1 = ASYNC_CALL r0
            write(OpCode.ASYNC_CALL, dst = 1, src1 = 0)
        }

        val errors = checker.validate(chunk)
        assertTrue(errors.isEmpty(), "Should have no errors, but got: $errors")
    }

    @Test
    fun `await after async call should pass validation`() {
        val chunk = Chunk().apply {
            // Create a function chunk
            val funcChunk = Chunk().apply {
                write(OpCode.LOAD_IMM, dst = 0, imm = 0)
                write(OpCode.RETURN, src1 = 0)
            }
            addConstant(Value.Int(42))
            functions.add(funcChunk)

            // r0 = LOAD_FUNC
            write(OpCode.LOAD_FUNC, dst = 0, imm = 0)

            // r1 = ASYNC_CALL r0 (returns Task<int>)
            write(OpCode.ASYNC_CALL, dst = 1, src1 = 0)

            // r2 = AWAIT r1
            write(OpCode.AWAIT, dst = 2, src1 = 1)
        }

        val errors = checker.validate(chunk)
        assertTrue(errors.isEmpty(), "Should have no errors, but got: $errors")
    }

    @Test
    fun `multiple errors should all be reported`() {
        val chunk = Chunk().apply {
            // r0 = LOAD_IMM (Value.Int)
            addConstant(Value.Int(42))
            write(OpCode.LOAD_IMM, dst = 0, imm = 0)

            // r1 = AWAIT r0 - ERROR 1
            write(OpCode.AWAIT, dst = 1, src1 = 0)

            // r2 = SPAWN r0 - ERROR 2
            write(OpCode.SPAWN, dst = 2, src1 = 0)

            // r3 = SPAWN_VIRTUAL r0 - ERROR 3
            write(OpCode.SPAWN_VIRTUAL, dst = 3, src1 = 0)
        }

        val errors = checker.validate(chunk)
        assertEquals(3, errors.size, "Should have exactly 3 errors")
        assertTrue(errors.any { it is TypeError.AwaitNonTask })
        assertTrue(errors.any { it is TypeError.SpawnNonCallable && !it.isVirtual })
        assertTrue(errors.any { it is TypeError.SpawnNonCallable && it.isVirtual })
    }

    @Test
    fun `numeric operations should infer correct types`() {
        val chunk = Chunk().apply {
            addConstant(Value.Int(10))
            addConstant(Value.Double(3.14))

            // r0 = LOAD_IMM 10 (int)
            write(OpCode.LOAD_IMM, dst = 0, imm = 0)

            // r1 = LOAD_IMM 3.14 (double)
            write(OpCode.LOAD_IMM, dst = 1, imm = 1)

            // r2 = ADD r0, r1 (int + double = double)
            write(OpCode.ADD, dst = 2, src1 = 0, src2 = 1)
        }

        val errors = checker.validate(chunk)
        assertTrue(errors.isEmpty(), "Should have no errors for valid numeric operations")
    }

    @Test
    fun `empty chunk should pass validation`() {
        val chunk = Chunk()
        val errors = checker.validate(chunk)
        assertTrue(errors.isEmpty(), "Empty chunk should have no errors")
    }

    @Test
    fun `chunk with only async operations should pass validation`() {
        val chunk = Chunk().apply {
            // Create a function chunk
            val funcChunk = Chunk().apply {
                write(OpCode.LOAD_IMM, dst = 0, imm = 0)
                write(OpCode.RETURN, src1 = 0)
            }
            addConstant(Value.Int(42))
            functions.add(funcChunk)

            // r0 = LOAD_FUNC
            write(OpCode.LOAD_FUNC, dst = 0, imm = 0)

            // r1 = ASYNC_CALL r0
            write(OpCode.ASYNC_CALL, dst = 1, src1 = 0)

            // r2 = AWAIT r1
            write(OpCode.AWAIT, dst = 2, src1 = 1)

            // r3 = SPAWN r0
            write(OpCode.SPAWN, dst = 3, src1 = 0)

            // r4 = SPAWN_VIRTUAL r0
            write(OpCode.SPAWN_VIRTUAL, dst = 4, src1 = 0)
        }

        val errors = checker.validate(chunk)
        assertTrue(errors.isEmpty(), "Should have no errors for valid async operations")
    }
}
