package org.aincraft.ast

import org.aincraft.lang.*
import org.aincraft.lang.IrInstr.*

class AstLowerer {
    private val instrs = mutableListOf<IrInstr>()
    private var labelCounter = 0
    private val constants = mutableListOf<Value>()
    private var regCounter = 0
    private val locals = mutableMapOf<String, Int>()
    private var breakLabel: IrLabel? = null
    private var nextLabel: IrLabel? = null

    private fun freshReg(): Int = regCounter++
    private fun freshLabel(): IrLabel = IrLabel(labelCounter++)

    private fun addConstant(value: Value): Int {
        constants.add(value)
        return constants.lastIndex
    }

    private fun emit(instr: IrInstr) {
        instrs.add(instr)
    }

    data class LoweredResult(val instrs: List<IrInstr>, val constants: List<Value>)

    fun lower(stmts: List<Stmt>): LoweredResult {
        for (s in stmts) lowerStmt(s)
        return LoweredResult(instrs, constants)
    }

    private fun lowerStmt(stmt: Stmt): Unit = when (stmt) {
        is Stmt.VarStmt -> lowerVar(stmt)
        is Stmt.ExprStmt -> {
            val dst = freshReg()
            lowerExpr(stmt.expr, dst)
            Unit
        }
        is Stmt.BlockStmt -> lowerBlock(stmt)
        is Stmt.FuncStmt -> lowerFunc(stmt)
        is Stmt.IfStmt -> lowerIf(stmt)
        is Stmt.WhileStmt -> lowerWhile(stmt)
        is Stmt.ForRangeStmt -> lowerForRange(stmt)
        is Stmt.ReturnStmt -> lowerReturn(stmt)
        Stmt.BreakStmt -> emit(IrInstr.Jump(breakLabel ?: error("break outside loop")))
        Stmt.NextStmt -> emit(IrInstr.Jump(nextLabel ?: error("next outside loop")))
        is Stmt.ClassStmt -> lowerClass(stmt)
        is Stmt.EnumStmt -> TODO()
        is Stmt.ImportFromStmt -> TODO()
        is Stmt.ImportStmt -> TODO()
    }

    private fun lowerVar(stmt: Stmt.VarStmt) {
        val dst = freshReg()
        locals[stmt.name.lexeme] = dst
        if (stmt.value != null) {
            lowerExpr(stmt.value, dst)
        } else {
            emit(IrInstr.LoadNull(dst))
        }
    }

    private fun lowerBlock(stmt: Stmt.BlockStmt) {
        val before = locals.toMap()
        for (s in stmt.stmts) lowerStmt(s)
        locals.keys.retainAll(before.keys)
    }

    private fun lowerIf(stmt: Stmt.IfStmt) {
        val elseLabel = freshLabel()
        val endLabel = freshLabel()
        val condReg = freshReg()
        lowerExpr(stmt.condition, condReg)
        emit(IrInstr.JumpIfFalse(condReg, elseLabel))
        lowerBlock(stmt.then)
        emit(IrInstr.Jump(endLabel))
        emit(IrInstr.Label(elseLabel))
        when (val e = stmt.elseBranch) {
            is Stmt.ElseBranch.Else -> lowerBlock(e.block)
            is Stmt.ElseBranch.ElseIf -> lowerIf(e.stmt)
            null -> {}
        }
        emit(IrInstr.Label(endLabel))
    }

    private fun lowerWhile(stmt: Stmt.WhileStmt) {
        val topLabel = freshLabel()
        val endLabel = freshLabel()
        val prevBreak = breakLabel
        val prevNext = nextLabel
        breakLabel = endLabel
        nextLabel = topLabel

        emit(IrInstr.Label(topLabel))
        val condReg = freshReg()
        lowerExpr(stmt.condition, condReg)
        emit(IrInstr.JumpIfFalse(condReg, endLabel))
        lowerBlock(stmt.body)
        emit(IrInstr.Jump(topLabel))
        emit(IrInstr.Label(endLabel))

        breakLabel = prevBreak
        nextLabel = prevNext
    }

    private fun lowerForRange(stmt: Stmt.ForRangeStmt) {
        // Generic for loop: for x in expr { body }
        // Desugars to:
        //   let __iter = expr.iter()
        //   while (__iter.hasNext()) {
        //     let x = __iter.next()
        //     body
        //   }

        val topLabel = freshLabel()
        val endLabel = freshLabel()
        val prevBreak = breakLabel
        val prevNext = nextLabel
        breakLabel = endLabel
        nextLabel = topLabel

        // Evaluate iterable and call .iter()
        val iterableReg = freshReg()
        lowerExpr(stmt.iterable, iterableReg)

        // __iter = iterable.iter()
        val iterReg = freshReg()
        emit(IrInstr.GetField(iterReg, iterableReg, "iter"))
        emit(IrInstr.Call(iterReg, iterReg, emptyList()))
        locals["__iter"] = iterReg

        // while (__iter.hasNext())
        emit(IrInstr.Label(topLabel))
        val condReg = freshReg()
        emit(IrInstr.GetField(condReg, iterReg, "hasNext"))
        emit(IrInstr.Call(condReg, condReg, emptyList()))
        emit(IrInstr.JumpIfFalse(condReg, endLabel))

        // let x = __iter.next()
        val valueReg = freshReg()
        emit(IrInstr.GetField(valueReg, iterReg, "next"))
        emit(IrInstr.Call(valueReg, valueReg, emptyList()))
        locals[stmt.variable.lexeme] = valueReg

        // body
        lowerBlock(stmt.body)

        emit(IrInstr.Jump(topLabel))
        emit(IrInstr.Label(endLabel))

        // Clean up loop variable from locals
        locals.remove("__iter")
        locals.remove(stmt.variable.lexeme)

        breakLabel = prevBreak
        nextLabel = prevNext
    }

    private fun lowerReturn(stmt: Stmt.ReturnStmt) {
        if (stmt.value != null) {
            val src = lowerExpr(stmt.value, freshReg())
            emit(IrInstr.Return(src))
        } else {
            val dst = freshReg()
            emit(IrInstr.LoadNull(dst))
            emit(IrInstr.Return(dst))
        }
    }

    private fun lowerFunc(stmt: Stmt.FuncStmt) {
        val lowerer = AstLowerer()
        for ((i, param) in stmt.params.withIndex()) {
            lowerer.locals[param.name.lexeme] = i
        }
        lowerer.regCounter = stmt.params.size
        val result = lowerer.lower(stmt.body.stmts)

        // Lower default value expressions
        // Each default value is lowered as a standalone expression that produces its result in register 0
        val defaultValues = stmt.params.map { param ->
            param.defaultValue?.let { defaultValue ->
                val defaultLowerer = AstLowerer()
                // Default values are evaluated in the caller's context
                // They can access globals but not the function's locals
                val defaultDst = defaultLowerer.freshReg()
                defaultLowerer.lowerExpr(defaultValue, defaultDst)
                val defaultResult = defaultLowerer.lower(emptyList())
                DefaultValueInfo(defaultResult.instrs, defaultResult.constants)
            }
        }

        val dst = freshReg()
        locals[stmt.name.lexeme] = dst
        emit(IrInstr.LoadFunc(dst, stmt.name.lexeme, stmt.params.size, result.instrs, result.constants, defaultValues))
        // Also store in globals so other functions can call it
        emit(IrInstr.StoreGlobal(stmt.name.lexeme, dst))
    }

    private fun lowerClass(stmt: Stmt.ClassStmt) {
        val className = stmt.name.lexeme

        // Lower each method with self as implicit first parameter
        val methods = mutableMapOf<String, MethodInfo>()
        for (member in stmt.body.stmts) {
            if (member is Stmt.FuncStmt) {
                val methodLowerer = AstLowerer()
                // self is at index 0
                methodLowerer.locals["self"] = 0
                // Regular params start at index 1
                for ((i, param) in member.params.withIndex()) {
                    methodLowerer.locals[param.name.lexeme] = i + 1
                }
                methodLowerer.regCounter = member.params.size + 1  // +1 for self
                val result = methodLowerer.lower(member.body.stmts)

                // Lower default value expressions for method params
                val defaultValues = member.params.map { param ->
                    param.defaultValue?.let { defaultValue ->
                        val defaultLowerer = AstLowerer()
                        val defaultDst = defaultLowerer.freshReg()
                        defaultLowerer.lowerExpr(defaultValue, defaultDst)
                        val defaultResult = defaultLowerer.lower(emptyList())
                        DefaultValueInfo(defaultResult.instrs, defaultResult.constants)
                    }
                }

                methods[member.name.lexeme] = MethodInfo(
                    arity = member.params.size + 1,  // includes self
                    instrs = result.instrs,
                    constants = result.constants,
                    defaultValues = defaultValues
                )
            }
        }

        val dst = freshReg()
        locals[className] = dst
        emit(IrInstr.LoadClass(dst, className, stmt.superClass?.lexeme, methods))
        emit(IrInstr.StoreGlobal(className, dst))
    }

    private fun lowerExpr(expr: Expr, dst: Int): Int = when (expr) {
        is Expr.LiteralExpr -> {
            when (val v = expr.literal) {
                is Value.Null -> { emit(LoadNull(dst)); dst }
                is Value.Boolean -> {
                    if (v.value) emit(LoadTrue(dst)) else emit(LoadFalse(dst))
                    dst
                }
                else -> {
                    val index = addConstant(v)
                    emit(LoadConst(dst, index))
                    dst
                }
            }
        }
        is Expr.VariableExpr -> {
            val reg = locals[expr.name.lexeme]
            if (reg != null) {
                reg  // already in a register
            } else {
                emit(LoadGlobal(dst, expr.name.lexeme))
                dst
            }
        }
        is Expr.BinaryExpr -> {
            val src1 = lowerExpr(expr.left, freshReg())
            val src2 = lowerExpr(expr.right, freshReg())
            emit(BinaryOp(dst, expr.op.type, src1, src2))
            dst
        }
        is Expr.UnaryExpr -> {
            val src = lowerExpr(expr.right, freshReg())
            emit(UnaryOp(dst, expr.op.type, src))
            dst
        }
        is Expr.AssignExpr -> {
            // Check if this is a compound assignment (+=, -=, etc.)
            if (expr.op.type != TokenType.ASSIGN) {
                // Compound assignment: desugar target op= value to target = target op value
                val binaryOp = when (expr.op.type) {
                    TokenType.ADD_EQUALS -> TokenType.PLUS
                    TokenType.SUB_EQUALS -> TokenType.MINUS
                    TokenType.MUL_EQUALS -> TokenType.STAR
                    TokenType.DIV_EQUALS -> TokenType.SLASH
                    TokenType.MOD_EQUALS -> TokenType.PERCENT
                    else -> error("Unknown compound operator: ${expr.op.type}")
                }
                when (expr.target) {
                    is Expr.VariableExpr -> {
                        val reg = locals[expr.target.name.lexeme]
                        if (reg != null) {
                            // Compute reg = reg op value
                            val valueReg = lowerExpr(expr.value, freshReg())
                            emit(BinaryOp(reg, binaryOp, reg, valueReg))
                            reg
                        } else {
                            // Global variable - load, op, store
                            val tmpReg = freshReg()
                            emit(LoadGlobal(tmpReg, expr.target.name.lexeme))
                            val valueReg = lowerExpr(expr.value, freshReg())
                            emit(BinaryOp(tmpReg, binaryOp, tmpReg, valueReg))
                            emit(StoreGlobal(expr.target.name.lexeme, tmpReg))
                            tmpReg
                        }
                    }
                    is Expr.IndexExpr -> {
                        val objReg = lowerExpr(expr.target.obj, freshReg())
                        val indexReg = lowerExpr(expr.target.index, freshReg())
                        // Load current value
                        val currentReg = freshReg()
                        emit(GetIndex(currentReg, objReg, indexReg))
                        // Compute new value
                        val valueReg = lowerExpr(expr.value, freshReg())
                        emit(BinaryOp(currentReg, binaryOp, currentReg, valueReg))
                        // Store back
                        emit(SetIndex(objReg, indexReg, currentReg))
                        currentReg
                    }
                    else -> error("Invalid compound assignment target")
                }
            } else {
                // Simple assignment
                when (expr.target) {
                    is Expr.GetExpr -> {
                        // p.name = value
                        val objReg = lowerExpr(expr.target.obj, freshReg())
                        val srcReg = lowerExpr(expr.value, freshReg())
                        emit(IrInstr.SetField(objReg, expr.target.name.lexeme, srcReg))
                        srcReg
                    }
                    is Expr.IndexExpr -> {
                        // arr[index] = value
                        val objReg = lowerExpr(expr.target.obj, freshReg())
                        val indexReg = lowerExpr(expr.target.index, freshReg())
                        val srcReg = lowerExpr(expr.value, freshReg())
                        emit(SetIndex(objReg, indexReg, srcReg))
                        srcReg
                    }
                    is Expr.VariableExpr -> {
                        val reg = locals[expr.target.name.lexeme]
                        if (reg != null) {
                            lowerExpr(expr.value, reg)
                            reg
                        } else {
                            val src = lowerExpr(expr.value, freshReg())
                            emit(StoreGlobal(expr.target.name.lexeme, src))
                            src
                        }
                    }
                    else -> error("Invalid assignment target")
                }
            }
        }
        is Expr.CallExpr -> {
            // Regular function/method/constructor call
            // The VM handles Value.Class by creating a new instance
            val funcReg = lowerExpr(expr.callee, freshReg())
            val argRegs = expr.arguments.map { lowerExpr(it, freshReg()) }
            emit(Call(dst, funcReg, argRegs))
            dst
        }
        is Expr.GroupExpr -> lowerExpr(expr.expr, dst)
        is Expr.GetExpr -> {
            val objReg = lowerExpr(expr.obj, freshReg())
            emit(IrInstr.GetField(dst, objReg, expr.name.lexeme))
            dst
        }
        is Expr.IndexExpr -> {
            val objReg = lowerExpr(expr.obj, freshReg())
            val indexReg = lowerExpr(expr.index, freshReg())
            emit(GetIndex(dst, objReg, indexReg))
            dst
        }
        is Expr.IsExpr -> {
            val srcReg = lowerExpr(expr.expr, freshReg())
            emit(IrInstr.IsType(dst, srcReg, expr.type.lexeme))
            dst
        }
        is Expr.LambdaExpr -> TODO()
        is Expr.ListExpr -> {
            val elementRegs = expr.elements.map { lowerExpr(it, freshReg())}
            emit(NewArray(dst, elementRegs))
            dst
        }
        is Expr.MapExpr -> TODO()
        is Expr.TernaryExpr -> TODO()
    }
}