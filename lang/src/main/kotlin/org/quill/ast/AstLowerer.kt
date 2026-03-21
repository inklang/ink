package org.quill.ast

import org.quill.lang.*
import org.quill.lang.IrInstr.*

class AstLowerer {
    private val instrs = mutableListOf<IrInstr>()
    private var labelCounter = 0
    private val constants = mutableListOf<Value>()
    private val functions = mutableListOf<List<IrInstr>>()
    private var regCounter = 0
    private val locals = mutableMapOf<String, Int>()
    private val constLocals = mutableSetOf<String>()
    private var breakLabel: IrLabel? = null
    private var nextLabel: IrLabel? = null
    private var lambdaCounter = 0

    private fun freshReg(): Int = regCounter++
    private fun freshLabel(): IrLabel = IrLabel(labelCounter++)

    private fun addConstant(value: Value): Int {
        constants.add(value)
        return constants.lastIndex
    }

    private fun emit(instr: IrInstr) {
        instrs.add(instr)
    }

    data class LoweredResult(val instrs: List<IrInstr>, val constants: List<Value>, val functions: MutableList<List<IrInstr>> = mutableListOf())

    fun lower(stmts: List<Stmt>): LoweredResult {
        for (s in stmts) lowerStmt(s)
        return LoweredResult(instrs, constants, functions)
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
        is Stmt.EnumStmt -> {
            val nsClassReg = freshReg()
            emit(IrInstr.LoadGlobal(nsClassReg, "EnumNamespace"))
            val nsReg = freshReg()
            emit(IrInstr.NewInstance(nsReg, nsClassReg, emptyList()))

            val evClassReg = freshReg()
            emit(IrInstr.LoadGlobal(evClassReg, "EnumValue"))

            for ((ordinal, valueTok) in stmt.values.withIndex()) {
                val valReg = freshReg()
                emit(IrInstr.NewInstance(valReg, evClassReg, emptyList()))
                val nameReg = freshReg()
                val nameIdx = addConstant(Value.String(valueTok.lexeme))
                emit(IrInstr.LoadImm(nameReg, nameIdx))
                emit(IrInstr.SetField(valReg, "name", nameReg))
                val ordReg = freshReg()
                val ordIdx = addConstant(Value.Int(ordinal))
                emit(IrInstr.LoadImm(ordReg, ordIdx))
                emit(IrInstr.SetField(valReg, "ordinal", ordReg))
                emit(IrInstr.SetField(nsReg, valueTok.lexeme, valReg))
            }

            locals[stmt.name.lexeme] = nsReg
            emit(IrInstr.StoreGlobal(stmt.name.lexeme, nsReg))
        }
        is Stmt.ImportStmt -> {
            val dst = freshReg()
            locals[stmt.namespace.lexeme] = dst
            val markerIdx = addConstant(Value.String("__import__${stmt.namespace.lexeme}"))
            emit(IrInstr.LoadImm(dst, markerIdx))
            emit(IrInstr.StoreGlobal(stmt.namespace.lexeme, dst))
        }
        is Stmt.ImportFromStmt -> {
            for (tok in stmt.tokens) {
                val dst = freshReg()
                locals[tok.lexeme] = dst
                val markerIdx = addConstant(Value.String("__import_from__${stmt.namespace.lexeme}__${tok.lexeme}"))
                emit(IrInstr.LoadImm(dst, markerIdx))
                emit(IrInstr.StoreGlobal(tok.lexeme, dst))
            }
        }
        is Stmt.ConfigStmt -> {
            val dst = freshReg()
            locals[stmt.name.lexeme] = dst
            val configMarkerIdx = addConstant(Value.String("__config__${stmt.name.lexeme}"))
            emit(IrInstr.LoadImm(dst, configMarkerIdx))
            emit(IrInstr.StoreGlobal(stmt.name.lexeme, dst))
        }
        is Stmt.TableStmt -> {
            val dst = freshReg()
            locals[stmt.name.lexeme] = dst
            val tableClassIdx = addConstant(Value.String("__table__${stmt.name.lexeme}"))
            emit(IrInstr.LoadImm(dst, tableClassIdx))
            emit(IrInstr.StoreGlobal(stmt.name.lexeme, dst))
        }
        is Stmt.EventDeclStmt -> {
            // Event declarations are type-only at this stage
            // Store event metadata in the constants table
            val eventInfo = Value.EventInfo(
                stmt.name.lexeme,
                stmt.params.map { it.name.lexeme to it.type.lexeme }
            )
            // Register with the event registry at compile time
            constants.add(eventInfo)
            // Nothing to emit - event info is registered via stdlib
            Unit
        }
        is Stmt.OnHandlerStmt -> {
            // Compile the handler body to a function chunk
            val handlerLowerer = AstLowerer()
            for ((i, param) in stmt.dataParams.withIndex()) {
                handlerLowerer.locals[param.name.lexeme] = i
            }
            handlerLowerer.regCounter = stmt.dataParams.size
            val handlerResult = handlerLowerer.lower(stmt.body.stmts)

            // Track handler function index before adding
            val handlerFuncIndex = functions.size
            functions.add(handlerResult.instrs)
            constants.addAll(handlerResult.constants)

            // Emit RegisterEventHandler instruction
            val instr = IrInstr.RegisterEventHandler(
                eventName = stmt.eventName.lexeme,
                handlerFuncIndex = handlerFuncIndex,
                eventParamName = stmt.eventParam.lexeme,
                dataParamNames = stmt.dataParams.map { it.name.lexeme }
            )
            emit(instr)
        }
    }

    private fun lowerVar(stmt: Stmt.VarStmt) {
        val dst = freshReg()
        locals[stmt.name.lexeme] = dst
        if (stmt.value != null) {
            lowerExpr(stmt.value, dst)
        } else {
            val index = addConstant(Value.Null)
            emit(IrInstr.LoadImm(dst, index))
        }
        if (stmt.keyword.type == TokenType.KW_CONST) {
            constLocals.add(stmt.name.lexeme)
        }
    }

    private fun lowerBlock(stmt: Stmt.BlockStmt) {
        val beforeLocals = locals.toMap()
        val beforeConsts = constLocals.toSet()
        for (s in stmt.stmts) lowerStmt(s)
        locals.keys.retainAll(beforeLocals.keys)
        constLocals.retainAll(beforeConsts)
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
            val index = addConstant(Value.Null)
            emit(IrInstr.LoadImm(dst, index))
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
            val index = addConstant(expr.literal)
            emit(LoadImm(dst, index))
            dst
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
            when (expr.op.type) {
                TokenType.KW_AND -> {
                    // Short-circuit AND:
                    // evaluate a; if falsy → result = a; else → result = b
                    val shortCircuit = freshLabel()
                    val end = freshLabel()
                    val aReg = lowerExpr(expr.left, freshReg())
                    emit(IrInstr.JumpIfFalse(aReg, shortCircuit))
                    lowerExpr(expr.right, dst)
                    emit(IrInstr.Jump(end))
                    emit(IrInstr.Label(shortCircuit))
                    emit(IrInstr.Move(dst, aReg))
                    emit(IrInstr.Label(end))
                }
                TokenType.KW_OR -> {
                    // Short-circuit OR:
                    // evaluate a; if truthy → result = a; else → result = b
                    val orFalse = freshLabel()
                    val end = freshLabel()
                    val aReg = lowerExpr(expr.left, freshReg())
                    emit(IrInstr.JumpIfFalse(aReg, orFalse))
                    emit(IrInstr.Move(dst, aReg))
                    emit(IrInstr.Jump(end))
                    emit(IrInstr.Label(orFalse))
                    lowerExpr(expr.right, dst)
                    emit(IrInstr.Label(end))
                }
                else -> {
                    val src1 = lowerExpr(expr.left, freshReg())
                    val src2 = lowerExpr(expr.right, freshReg())
                    emit(BinaryOp(dst, expr.op.type, src1, src2))
                }
            }
            dst
        }
        is Expr.UnaryExpr -> {
            if (expr.op.type == TokenType.INCREMENT || expr.op.type == TokenType.DECREMENT) {
                // Prefix ++/--: mutate the variable and return the new value
                val target = expr.right as? Expr.VariableExpr
                    ?: error("++/-- can only be applied to simple variables")
                val delta = if (expr.op.type == TokenType.INCREMENT) TokenType.PLUS else TokenType.MINUS
                val oneIdx = addConstant(Value.Int(1))
                val oneReg = freshReg()
                emit(LoadImm(oneReg, oneIdx))
                val srcReg = lowerExpr(expr.right, freshReg())
                emit(BinaryOp(dst, delta, srcReg, oneReg))
                // Write back
                val reg = locals[target.name.lexeme]
                if (reg != null) {
                    emit(IrInstr.Move(reg, dst))
                } else {
                    emit(StoreGlobal(target.name.lexeme, dst))
                }
            } else {
                val src = lowerExpr(expr.right, freshReg())
                emit(UnaryOp(dst, expr.op.type, src))
            }
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
                        if (expr.target.name.lexeme in constLocals) {
                            error("Cannot reassign const '${expr.target.name.lexeme}'")
                        }
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
                        if (expr.target.name.lexeme in constLocals) {
                            error("Cannot reassign const '${expr.target.name.lexeme}'")
                        }
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
        is Expr.HasExpr -> {
            val objReg = lowerExpr(expr.target, freshReg())
            val fieldExpr = expr.field
            // field must be a string literal (compile-time known)
            val fieldName = (fieldExpr as? Expr.LiteralExpr)
                ?.literal as? Value.String
                ?: error("has: field name must be a string literal")
            emit(IrInstr.HasCheck(dst, objReg, fieldName.value))
            dst
        }
        is Expr.LambdaExpr -> {
            val lambdaName = "__lambda_${lambdaCounter++}"
            val lowerer = AstLowerer()
            for ((i, param) in expr.params.withIndex()) {
                lowerer.locals[param.name.lexeme] = i
            }
            lowerer.regCounter = expr.params.size
            val result = lowerer.lower(expr.body.stmts)

            val defaultValues = expr.params.map { param ->
                param.defaultValue?.let { defaultValue ->
                    val defaultLowerer = AstLowerer()
                    val defaultDst = defaultLowerer.freshReg()
                    defaultLowerer.lowerExpr(defaultValue, defaultDst)
                    val defaultResult = defaultLowerer.lower(emptyList())
                    DefaultValueInfo(defaultResult.instrs, defaultResult.constants)
                }
            }

            emit(IrInstr.LoadFunc(dst, lambdaName, expr.params.size, result.instrs, result.constants, defaultValues))
            dst
        }
        is Expr.ListExpr -> {
            val elementRegs = expr.elements.map { lowerExpr(it, freshReg())}
            emit(NewArray(dst, elementRegs))
            dst
        }
        is Expr.SetExpr -> {
            // Desugar to Set(element1, element2, ...)
            val elementRegs = expr.elements.map { lowerExpr(it, freshReg()) }
            val setClassReg = freshReg()
            emit(IrInstr.LoadGlobal(setClassReg, "Set"))
            emit(IrInstr.NewInstance(dst, setClassReg, elementRegs))
            dst
        }
        is Expr.TupleExpr -> {
            // Desugar to Tuple(element1, element2, ...)
            val elementRegs = expr.elements.map { lowerExpr(it, freshReg()) }
            val tupleClassReg = freshReg()
            emit(IrInstr.LoadGlobal(tupleClassReg, "Tuple"))
            emit(IrInstr.NewInstance(dst, tupleClassReg, elementRegs))
            dst
        }
        is Expr.MapExpr -> {
            val mapClassReg = freshReg()
            emit(IrInstr.LoadGlobal(mapClassReg, "Map"))
            emit(IrInstr.NewInstance(dst, mapClassReg, emptyList()))
            for ((key, value) in expr.entries) {
                val keyReg = lowerExpr(key, freshReg())
                val valueReg = lowerExpr(value, freshReg())
                val setMethodReg = freshReg()
                emit(IrInstr.GetField(setMethodReg, dst, "set"))
                emit(IrInstr.Call(freshReg(), setMethodReg, listOf(keyReg, valueReg)))
            }
            dst
        }
        is Expr.TernaryExpr -> {
            val elseLabel = freshLabel()
            val endLabel = freshLabel()
            val condReg = freshReg()
            lowerExpr(expr.condition, condReg)
            emit(IrInstr.JumpIfFalse(condReg, elseLabel))
            lowerExpr(expr.thenBranch, dst)
            emit(IrInstr.Jump(endLabel))
            emit(IrInstr.Label(elseLabel))
            lowerExpr(expr.elseBranch, dst)
            emit(IrInstr.Label(endLabel))
            dst
        }
    }
}