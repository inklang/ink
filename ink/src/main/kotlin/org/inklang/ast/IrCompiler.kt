package org.inklang.lang

import org.inklang.ast.AstLowerer
import org.inklang.ast.LivenessAnalyzer
import org.inklang.ast.RegisterAllocator
import org.inklang.ast.SpillInserter
import org.inklang.opt.OptimizationPipeline
import org.inklang.opt.passes.InductionVariablePass
import org.inklang.opt.passes.ConstantFoldingPass
import org.inklang.opt.passes.CopyPropagationPass
import org.inklang.opt.passes.DeadCodeEliminationPass
import org.inklang.opt.passes.LoopInvariantCodeMotionPass
import org.inklang.opt.passes.StrengthReductionPass
import org.inklang.opt.passes.BranchOptimizationPass
import org.inklang.ssa.SsaBuilder
import org.inklang.ssa.SsaDeconstructor
import org.inklang.ssa.passes.SsaConstantPropagationPass
import org.inklang.ssa.passes.SsaDeadCodeEliminationPass
import org.inklang.ssa.passes.SsaGlobalValueNumberingPass
import org.inklang.ssa.passes.SsaCrossBlockGvnPass

import java.util.concurrent.ForkJoinPool

class IrCompiler {
    companion object {
        fun optimizedSsaRoundTrip(
            instrs: List<IrInstr>,
            constants: List<Value>
        ): Pair<List<IrInstr>, List<Value>> = OptimizationPipeline.optimizeWithSsa(
            instrs,
            constants,
            ssaPasses = listOf(
                SsaConstantPropagationPass(),
                SsaGlobalValueNumberingPass(),
                SsaCrossBlockGvnPass(),
                SsaDeadCodeEliminationPass()
            ),
            preSsaPasses = listOf(
                ConstantFoldingPass(),
                InductionVariablePass()
            ),
            postSsaPasses = listOf(
                DeadCodeEliminationPass(),
                CopyPropagationPass(),
                StrengthReductionPass(),
                LoopInvariantCodeMotionPass(),
                BranchOptimizationPass(),
                DeadCodeEliminationPass()
            )
        )

        /**
         * Result of compiling a single method.
         */
        private data class CompiledMethod(
            val chunk: Chunk,
            val spillSlotCount: Int
        )

        /**
         * Compile a single method's IR through the full pipeline.
         * This is a pure function — no shared mutable state.
         */
        private fun compileMethod(methodInfo: MethodInfo): CompiledMethod {
            val ssa = SsaBuilder.build(methodInfo.instrs, methodInfo.constants, methodInfo.arity)
            val deconstructed = SsaDeconstructor.deconstruct(ssa)
            val ranges = LivenessAnalyzer().analyze(deconstructed)
            val alloc = RegisterAllocator().allocate(ranges, methodInfo.arity)
            val resolved = SpillInserter().insert(deconstructed, alloc, ranges)
            val result = AstLowerer.LoweredResult(resolved, methodInfo.constants)
            val chunk = IrCompiler().compile(result)
            return CompiledMethod(chunk, alloc.spillSlotCount)
        }
    }

    fun compile(result: AstLowerer.LoweredResult): Chunk {
        val chunk = Chunk()
        chunk.constants.addAll(result.constants)

        // first pass: resolve label positions (skip label instrs since they emit no code)
        val labelOffsets = mutableMapOf<Int, Int>()
        var offset = 0
        for (instr in result.instrs) {
            if (instr is IrInstr.Label) {
                labelOffsets[instr.label.id] = offset
            } else {
                offset++
            }
        }

        // second pass: emit bytecode
        for (instr in result.instrs) {
            when (instr) {
                is IrInstr.LoadImm -> chunk.write(OpCode.LOAD_IMM, dst = instr.dst, imm = instr.index)
                is IrInstr.LoadGlobal -> chunk.write(OpCode.LOAD_GLOBAL, dst = instr.dst, imm = chunk.addString(instr.name))
                is IrInstr.StoreGlobal -> chunk.write(OpCode.STORE_GLOBAL, src1 = instr.src, imm = chunk.addString(instr.name))
                is IrInstr.Move -> chunk.write(OpCode.MOVE, dst = instr.dst, src1 = instr.src)
                is IrInstr.BinaryOp -> {
                    val op = when (instr.op) {
                        TokenType.PLUS -> OpCode.ADD
                        TokenType.MINUS -> OpCode.SUB
                        TokenType.STAR -> OpCode.MUL
                        TokenType.SLASH -> OpCode.DIV
                        TokenType.EQ_EQ -> OpCode.EQ
                        TokenType.BANG_EQ -> OpCode.NEQ
                        TokenType.LT -> OpCode.LT
                        TokenType.LTE -> OpCode.LTE
                        TokenType.GT -> OpCode.GT
                        TokenType.GTE -> OpCode.GTE
                        TokenType.PERCENT -> OpCode.MOD
                        TokenType.DOT_DOT -> OpCode.RANGE
                        TokenType.POW -> OpCode.POW
                        else -> error("Unknown binary op: ${instr.op}")
                    }
                    chunk.write(op, dst = instr.dst, src1 = instr.src1, src2 = instr.src2)
                }
                is IrInstr.UnaryOp -> {
                    val op = when (instr.op) {
                        TokenType.MINUS -> OpCode.NEG
                        TokenType.BANG, TokenType.KW_NOT -> OpCode.NOT
                        else -> error("Unknown unary op: ${instr.op}")
                    }
                    chunk.write(op, dst = instr.dst, src1 = instr.src)
                }
                is IrInstr.Jump -> chunk.write(OpCode.JUMP, imm = labelOffsets[instr.target.id]!!)
                is IrInstr.JumpIfFalse -> chunk.write(OpCode.JUMP_IF_FALSE, src1 = instr.src, imm = labelOffsets[instr.target.id]!!)
                is IrInstr.Call -> {
                    // First push all arguments
                    for (arg in instr.args) {
                        chunk.write(OpCode.PUSH_ARG, src1 = arg)
                    }
                    chunk.write(OpCode.CALL, dst = instr.dst, src1 = instr.func, imm = instr.args.size)
                }
                is IrInstr.LoadFunc -> {
                    // SSA round-trip on function body
                    val funcSsa = SsaBuilder.build(instr.instrs, instr.constants, instr.arity)
                    val funcDeconstructed = SsaDeconstructor.deconstruct(funcSsa)

                    // Run register allocation on the function body
                    val funcRanges = LivenessAnalyzer().analyze(funcDeconstructed)
                    val funcAllocResult = RegisterAllocator().allocate(funcRanges, instr.arity)
                    val funcResolved = SpillInserter().insert(funcDeconstructed, funcAllocResult, funcRanges)
                    val funcResult = AstLowerer.LoweredResult(funcResolved, instr.constants)
                    val funcChunk = IrCompiler().compile(funcResult)
                    funcChunk.spillSlotCount = funcAllocResult.spillSlotCount
                    val idx = chunk.functions.size
                    chunk.functions.add(funcChunk)

                    // Compile default value expressions
                    val defaultChunkIndices = instr.defaultValues.map { defaultInfo ->
                        if (defaultInfo != null) {
                            // Compile the default value expression
                            val defaultRanges = LivenessAnalyzer().analyze(defaultInfo.instrs)
                            val defaultAllocResult = RegisterAllocator().allocate(defaultRanges, 0)
                            val defaultResolved = SpillInserter().insert(defaultInfo.instrs, defaultAllocResult, defaultRanges)
                            val defaultResult = AstLowerer.LoweredResult(defaultResolved, defaultInfo.constants)
                            val defaultChunk = IrCompiler().compile(defaultResult)
                            defaultChunk.spillSlotCount = defaultAllocResult.spillSlotCount
                            val defaultIdx = chunk.functions.size
                            chunk.functions.add(defaultChunk)
                            defaultIdx
                        } else {
                            null
                        }
                    }
                    // Ensure functionDefaults has enough entries - functionDefaults[i] must correspond to functions[i]
                    while (chunk.functionDefaults.size <= idx) {
                        chunk.functionDefaults.add(FunctionDefaults(emptyList()))
                    }
                    chunk.functionDefaults[idx] = FunctionDefaults(defaultChunkIndices)

                    chunk.write(OpCode.LOAD_FUNC, dst = instr.dst, imm = idx)
                }
                is IrInstr.Return -> chunk.write(OpCode.RETURN, src1 = instr.src)
                is IrInstr.Break -> chunk.write(OpCode.BREAK)
                is IrInstr.Next -> chunk.write(OpCode.NEXT)
                is IrInstr.Label -> { /* skip, resolved in first pass */ }
                is IrInstr.NewArray -> {
                    // First push all elements
                    for (elem in instr.elements) {
                        chunk.write(OpCode.PUSH_ARG, src1 = elem)
                    }
                    chunk.write(OpCode.NEW_ARRAY, dst = instr.dst, imm = instr.elements.size)
                }
                is IrInstr.GetIndex -> chunk.write(OpCode.GET_INDEX, dst = instr.dst, src1 = instr.obj, src2 = instr.index)
                is IrInstr.SetIndex -> chunk.write(OpCode.SET_INDEX, src1 = instr.obj, src2 = instr.index, imm = instr.src)
                is IrInstr.GetField -> chunk.write(OpCode.GET_FIELD, dst = instr.dst, src1 = instr.obj, imm = chunk.addString(instr.name))
                is IrInstr.SetField -> chunk.write(OpCode.SET_FIELD, src1 = instr.obj, src2 = instr.src, imm = chunk.addString(instr.name))
                is IrInstr.NewInstance -> {
                    // First push all arguments
                    for (arg in instr.args) {
                        chunk.write(OpCode.PUSH_ARG, src1 = arg)
                    }
                    chunk.write(OpCode.NEW_INSTANCE, dst = instr.dst, src1 = instr.classReg, imm = instr.args.size)
                }
                is IrInstr.IsType -> chunk.write(OpCode.IS_TYPE, dst = instr.dst, src1 = instr.src, imm = chunk.addString(instr.typeName))
                is IrInstr.HasCheck -> chunk.write(OpCode.HAS, dst = instr.dst, src1 = instr.obj, imm = chunk.addString(instr.fieldName))
                is IrInstr.LoadClass -> {
                    // Pre-allocate function slots so indices are pre-determined and unique
                    // Store as index -> methodName mapping
                    val methodNameList = instr.methods.keys.toList()
                    val preAllocatedIndices = mutableMapOf<Int, String>()  // slot index -> method name
                    val methodStartIndex = chunk.functions.size
                    for (methodName in methodNameList) {
                        preAllocatedIndices[chunk.functions.size] = methodName
                        chunk.functions.add(Chunk())  // placeholder, replaced below
                    }

                    // Compile all methods in parallel using ForkJoinPool
                    val results: Map<String, CompiledMethod> = try {
                        val pool = ForkJoinPool.commonPool()
                        val tasks = instr.methods.mapValues { (_, methodInfo) ->
                            pool.submit<CompiledMethod> { compileMethod(methodInfo) }
                        }
                        tasks.mapValues { (_, future) -> future.get() }
                    } catch (e: Exception) {
                        // Fallback to sequential compilation on any pool failure
                        instr.methods.mapValues { (_, methodInfo) -> compileMethod(methodInfo) }
                    }

                    // Replace placeholders with compiled chunks and build method index
                    val methodFuncIndices = mutableMapOf<String, Int>()
                    for ((slotIdx, methodName) in preAllocatedIndices) {
                        val compiled = results[methodName]!!
                        chunk.functions[slotIdx] = compiled.chunk
                        chunk.functions[slotIdx].spillSlotCount = compiled.spillSlotCount
                        methodFuncIndices[methodName] = slotIdx
                    }

                    // Add class info to chunk
                    val classIdx = chunk.classes.size
                    chunk.classes.add(ClassInfo(instr.name, instr.superClass, methodFuncIndices))
                    chunk.write(OpCode.BUILD_CLASS, dst = instr.dst, imm = classIdx)
                }
                is IrInstr.Spill   -> chunk.write(OpCode.SPILL, imm = instr.slot, src1 = instr.src)
                is IrInstr.Unspill -> chunk.write(OpCode.UNSPILL, dst = instr.dst, imm = instr.slot)
                is IrInstr.Throw -> chunk.write(OpCode.THROW, src1 = instr.src)
                is IrInstr.RegisterEventHandler -> { /* registered at runtime, no bytecode */ }
                is IrInstr.InvokeEventHandler -> { /* invoked at runtime, no bytecode */ }
                is IrInstr.AwaitInstr -> { /* implemented in VM */ }
                is IrInstr.SpawnInstr -> { /* implemented in VM */ }
                is IrInstr.AsyncCallInstr -> { /* implemented in VM */ }
            }
        }

        return chunk
    }
}