package org.aincraft.lang

import org.aincraft.ast.AstLowerer
import org.aincraft.ast.LivenessAnalyzer
import org.aincraft.ast.RegisterAllocator

class IrCompiler {
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
                // CALL, NEW_ARRAY, and NEW_INSTANCE emit extra ARG instructions for each argument
                if (instr is IrInstr.Call) {
                    offset += instr.args.size
                }
                if (instr is IrInstr.NewArray) {
                    offset += instr.elements.size
                }
                if (instr is IrInstr.NewInstance) {
                    offset += instr.args.size
                }
            }
        }

        // second pass: emit bytecode
        for (instr in result.instrs) {
            when (instr) {
                is IrInstr.LoadConst -> chunk.write(OpCode.PUSH_CONST, dst = instr.dst, imm = instr.index)
                is IrInstr.LoadNull -> chunk.write(OpCode.PUSH_NULL, dst = instr.dst)
                is IrInstr.LoadTrue -> chunk.write(OpCode.PUSH_TRUE, dst = instr.dst)
                is IrInstr.LoadFalse -> chunk.write(OpCode.PUSH_FALSE, dst = instr.dst)
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
                        else -> error("Unknown binary op: ${instr.op}")
                    }
                    chunk.write(op, dst = instr.dst, src1 = instr.src1, src2 = instr.src2)
                }
                is IrInstr.UnaryOp -> {
                    val op = when (instr.op) {
                        TokenType.MINUS -> OpCode.NEG
                        TokenType.BANG -> OpCode.NOT
                        else -> error("Unknown unary op: ${instr.op}")
                    }
                    chunk.write(op, dst = instr.dst, src1 = instr.src)
                }
                is IrInstr.Jump -> chunk.write(OpCode.JUMP, imm = labelOffsets[instr.target.id]!!)
                is IrInstr.JumpIfFalse -> chunk.write(OpCode.JUMP_IF_FALSE, src1 = instr.src, imm = labelOffsets[instr.target.id]!!)
                is IrInstr.Call -> {
                    chunk.write(OpCode.CALL, dst = instr.dst, src1 = instr.func, imm = instr.args.size)
                    // encode each arg reg as a subsequent word
                    for (arg in instr.args) {
                        chunk.write(OpCode.ARG, src1 = arg)
                    }
                }
                is IrInstr.LoadFunc -> {
                    // Run register allocation on the function body
                    val funcRanges = LivenessAnalyzer().analyze(instr.instrs)
                    val funcAllocation = RegisterAllocator().allocate(funcRanges, instr.arity)
                    val funcRewritten = rewriteRegisters(instr.instrs, funcAllocation)
                    val funcResult = AstLowerer.LoweredResult(funcRewritten, instr.constants)
                    val funcChunk = IrCompiler().compile(funcResult)
                    val idx = chunk.functions.size
                    chunk.functions.add(funcChunk)

                    // Compile default value expressions
                    val defaultChunkIndices = instr.defaultValues.map { defaultInfo ->
                        if (defaultInfo != null) {
                            // Compile the default value expression
                            val defaultRanges = LivenessAnalyzer().analyze(defaultInfo.instrs)
                            val defaultAllocation = RegisterAllocator().allocate(defaultRanges, 0)
                            val defaultRewritten = rewriteRegisters(defaultInfo.instrs, defaultAllocation)
                            val defaultResult = AstLowerer.LoweredResult(defaultRewritten, defaultInfo.constants)
                            val defaultChunk = IrCompiler().compile(defaultResult)
                            val defaultIdx = chunk.functions.size
                            chunk.functions.add(defaultChunk)
                            defaultIdx
                        } else {
                            null
                        }
                    }
                    chunk.functionDefaults.add(FunctionDefaults(defaultChunkIndices))

                    chunk.write(OpCode.LOAD_FUNC, dst = instr.dst, imm = idx)
                }
                is IrInstr.Return -> chunk.write(OpCode.RETURN, src1 = instr.src)
                is IrInstr.Break -> chunk.write(OpCode.BREAK)
                is IrInstr.Next -> chunk.write(OpCode.NEXT)
                is IrInstr.Label -> { /* skip, resolved in first pass */ }
                is IrInstr.NewArray -> {
                    chunk.write(OpCode.NEW_ARRAY, dst = instr.dst, imm = instr.elements.size)
                    for (elem in instr.elements) {
                        chunk.write(OpCode.ARG, src1 = elem)
                    }
                }
                is IrInstr.GetIndex -> chunk.write(OpCode.GET_INDEX, dst = instr.dst, src1 = instr.obj, src2 = instr.index)
                is IrInstr.SetIndex -> chunk.write(OpCode.SET_INDEX, src1 = instr.obj, src2 = instr.index, imm = instr.src)
                is IrInstr.GetField -> chunk.write(OpCode.GET_FIELD, dst = instr.dst, src1 = instr.obj, imm = chunk.addString(instr.name))
                is IrInstr.SetField -> chunk.write(OpCode.SET_FIELD, src1 = instr.obj, src2 = instr.src, imm = chunk.addString(instr.name))
                is IrInstr.NewInstance -> {
                    chunk.write(OpCode.NEW_INSTANCE, dst = instr.dst, src1 = instr.classReg, imm = instr.args.size)
                    for (arg in instr.args) {
                        chunk.write(OpCode.ARG, src1 = arg)
                    }
                }
                is IrInstr.IsType -> chunk.write(OpCode.IS_TYPE, dst = instr.dst, src1 = instr.src, imm = chunk.addString(instr.typeName))
                is IrInstr.LoadClass -> {
                    // Compile each method as a nested function chunk
                    val methodFuncIndices = mutableMapOf<String, Int>()
                    for ((methodName, methodInfo) in instr.methods) {
                        val funcRanges = LivenessAnalyzer().analyze(methodInfo.instrs)
                        val funcAllocation = RegisterAllocator().allocate(funcRanges, methodInfo.arity)
                        val funcRewritten = rewriteRegisters(methodInfo.instrs, funcAllocation)
                        val funcResult = AstLowerer.LoweredResult(funcRewritten, methodInfo.constants)
                        val funcChunk = IrCompiler().compile(funcResult)
                        val funcIdx = chunk.functions.size
                        chunk.functions.add(funcChunk)
                        methodFuncIndices[methodName] = funcIdx
                    }
                    // Add class info to chunk
                    val classIdx = chunk.classes.size
                    chunk.classes.add(ClassInfo(instr.name, instr.superClass, methodFuncIndices))
                    chunk.write(OpCode.BUILD_CLASS, dst = instr.dst, imm = classIdx)
                }
            }
        }

        return chunk
    }

    private fun rewriteRegisters(instrs: List<IrInstr>, allocation: Map<Int, Int>): List<IrInstr> {
        fun r(reg: Int) = allocation[reg] ?: error("v$reg not allocated — needs spill handling")
        return instrs.map { instr ->
            when (instr) {
                is IrInstr.LoadConst -> instr.copy(dst = r(instr.dst))
                is IrInstr.LoadNull -> instr.copy(dst = r(instr.dst))
                is IrInstr.LoadTrue -> instr.copy(dst = r(instr.dst))
                is IrInstr.LoadFalse -> instr.copy(dst = r(instr.dst))
                is IrInstr.LoadGlobal -> instr.copy(dst = r(instr.dst))
                is IrInstr.StoreGlobal -> instr.copy(src = r(instr.src))
                is IrInstr.Move -> instr.copy(dst = r(instr.dst), src = r(instr.src))
                is IrInstr.BinaryOp -> instr.copy(dst = r(instr.dst), src1 = r(instr.src1), src2 = r(instr.src2))
                is IrInstr.UnaryOp -> instr.copy(dst = r(instr.dst), src = r(instr.src))
                is IrInstr.Call -> instr.copy(dst = r(instr.dst), func = r(instr.func), args = instr.args.map { r(it) })
                is IrInstr.NewArray -> instr.copy(dst = r(instr.dst), elements = instr.elements.map { r(it) })
                is IrInstr.GetIndex -> instr.copy(dst = r(instr.dst), obj = r(instr.obj), index = r(instr.index))
                is IrInstr.SetIndex -> instr.copy(obj = r(instr.obj), index = r(instr.index), src = r(instr.src))
                is IrInstr.GetField -> instr.copy(dst = r(instr.dst), obj = r(instr.obj))
                is IrInstr.SetField -> instr.copy(obj = r(instr.obj), src = r(instr.src))
                is IrInstr.NewInstance -> instr.copy(dst = r(instr.dst), classReg = r(instr.classReg), args = instr.args.map { r(it) })
                is IrInstr.IsType -> instr.copy(dst = r(instr.dst), src = r(instr.src))
                is IrInstr.LoadClass -> instr.copy(dst = r(instr.dst))
                is IrInstr.Return -> instr.copy(src = r(instr.src))
                is IrInstr.JumpIfFalse -> instr.copy(src = r(instr.src))
                is IrInstr.LoadFunc -> instr.copy(dst = r(instr.dst))
                else -> instr
            }
        }
    }
}