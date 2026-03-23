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
        chunk.labelOffsets.putAll(labelOffsets)

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
                    // Compile each method as a nested function chunk
                    val methodFuncIndices = mutableMapOf<String, Int>()
                    for ((methodName, methodInfo) in instr.methods) {
                        // SSA round-trip on method body
                        val methodSsa = SsaBuilder.build(methodInfo.instrs, methodInfo.constants, methodInfo.arity)
                        val methodDeconstructed = SsaDeconstructor.deconstruct(methodSsa)

                        val funcRanges = LivenessAnalyzer().analyze(methodDeconstructed)
                        val methodAllocResult = RegisterAllocator().allocate(funcRanges, methodInfo.arity)
                        val methodResolved = SpillInserter().insert(methodDeconstructed, methodAllocResult, funcRanges)
                        val funcResult = AstLowerer.LoweredResult(methodResolved, methodInfo.constants)
                        val funcChunk = IrCompiler().compile(funcResult)
                        funcChunk.spillSlotCount = methodAllocResult.spillSlotCount
                        val funcIdx = chunk.functions.size
                        chunk.functions.add(funcChunk)
                        methodFuncIndices[methodName] = funcIdx
                    }
                    // Add class info to chunk
                    val classIdx = chunk.classes.size
                    chunk.classes.add(ClassInfo(instr.name, instr.superClass, methodFuncIndices))
                    chunk.write(OpCode.BUILD_CLASS, dst = instr.dst, imm = classIdx)
                }
                is IrInstr.Spill   -> chunk.write(OpCode.SPILL, imm = instr.slot, src1 = instr.src)
                is IrInstr.Unspill -> chunk.write(OpCode.UNSPILL, dst = instr.dst, imm = instr.slot)
                is IrInstr.CallHandler -> {
                    val cstIdx = chunk.cstTable.size
                    chunk.cstTable.add(instr.cst)
                    chunk.write(OpCode.CALL_HANDLER, imm = cstIdx)
                }
                is IrInstr.TryStart -> {
                    val finallyPc = instr.finallyLabelIdx?.let { labelOffsets[it] } ?: 0xF
                    val catchPc = instr.catchLabelIdx?.let { labelOffsets[it] } ?: 0xFFF
                    chunk.write(OpCode.TRY_START, dst = finallyPc, imm = catchPc)
                }
                is IrInstr.TryEnd -> {
                    chunk.write(OpCode.TRY_END)
                }
                is IrInstr.ThrowInstr -> {
                    chunk.write(OpCode.THROW, src1 = instr.src)
                }
                is IrInstr.ExitTry -> {
                    chunk.write(OpCode.EXIT_TRY, imm = instr.returnDst)
                }
            }
        }

        return chunk
    }
}