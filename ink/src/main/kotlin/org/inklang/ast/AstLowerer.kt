package org.inklang.ast

import org.inklang.CompilationException
import org.inklang.lang.*
import org.inklang.lang.IrInstr.*

open class AstLowerer {
    protected val instrs = mutableListOf<IrInstr>()
    protected var labelCounter = 0
    protected val constants = mutableListOf<Value>()
    protected val functions = mutableListOf<List<IrInstr>>()
    protected var regCounter = 0
    protected val locals = mutableMapOf<String, Int>()
    protected val constLocals = mutableSetOf<String>()
    protected var breakLabel: IrLabel? = null
    protected var nextLabel: IrLabel? = null
    protected var lambdaCounter = 0
    // Field names for class methods - used to resolve bare identifiers to self.fieldName
    protected open var fieldNames: Set<String> = emptySet()

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
            // For stdlib imports (math, random, io, json), load the actual instance from globals
            if (stmt.namespace.lexeme == "math" || stmt.namespace.lexeme == "random" ||
                stmt.namespace.lexeme == "io" || stmt.namespace.lexeme == "json") {
                emit(IrInstr.LoadGlobal(dst, stmt.namespace.lexeme))
            } else {
                // For other imports, keep the marker string behavior
                val markerIdx = addConstant(Value.String("__import__${stmt.namespace.lexeme}"))
                emit(IrInstr.LoadImm(dst, markerIdx))
            }
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
            // Config values are loaded at runtime via InkScript.preloadConfigs()
            // No IR emitted here — globals["${stmt.name.lexeme}"] is pre-populated
            locals[stmt.name.lexeme] = freshReg()
        }
        is Stmt.TableStmt -> {
            val tableName = stmt.name.lexeme
            val fieldNames = stmt.fields.map { it.name.lexeme }
            val keyFieldIdx = stmt.fields.indexOfFirst { it.isKey }.takeIf { it >= 0 } ?: 0

            // Step 1: db.registerTable("Player", ["id", "name", "score"], 0)
            val dbReg = freshReg()
            emit(IrInstr.LoadGlobal(dbReg, "db"))

            val tableNameIdx = addConstant(Value.String(tableName))
            val tableNameReg = freshReg()
            emit(IrInstr.LoadImm(tableNameReg, tableNameIdx))

            // Build array of field name strings
            val fieldRegs = fieldNames.map { name ->
                val r = freshReg()
                val idx = addConstant(Value.String(name))
                emit(IrInstr.LoadImm(r, idx))
                r
            }
            val fieldsReg = freshReg()
            emit(IrInstr.NewArray(fieldsReg, fieldRegs))

            val keyIdxIdx = addConstant(Value.Int(keyFieldIdx))
            val keyIdxReg = freshReg()
            emit(IrInstr.LoadImm(keyIdxReg, keyIdxIdx))

            // Get the registerTable method from db
            val registerTableMethodReg = freshReg()
            emit(IrInstr.GetField(registerTableMethodReg, dbReg, "registerTable"))

            // Call db.registerTable(tableName, fields, keyIndex)
            emit(IrInstr.Call(freshReg(), registerTableMethodReg, listOf(tableNameReg, fieldsReg, keyIdxReg)))

            // Step 2: Player = db("Player")
            // Re-load db since it might have been clobbered
            val dbReg2 = freshReg()
            emit(IrInstr.LoadGlobal(dbReg2, "db"))
            val tableNameReg2 = freshReg()
            emit(IrInstr.LoadImm(tableNameReg2, tableNameIdx))

            // Get the from method from db
            val fromMethodReg = freshReg()
            emit(IrInstr.GetField(fromMethodReg, dbReg2, "from"))

            // Call db.from(tableName)
            val playerReg = freshReg()
            emit(IrInstr.Call(playerReg, fromMethodReg, listOf(tableNameReg2)))

            locals[tableName] = playerReg
            emit(IrInstr.StoreGlobal(tableName, playerReg))
        }
        // Annotation declarations are compile-time only - no IR emitted
        is Stmt.AnnotationDeclStmt -> { /* no-op */ }
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
            val eventName = stmt.eventName.lexeme
            val knownEvents = setOf("player_join", "player_quit", "chat_message",
                "block_break", "block_place", "entity_death", "entity_damage",
                "server_start", "server_stop")
            if (eventName !in knownEvents) {
                throw CompilationException(
                    "RuntimeError: Unknown event '$eventName'. Available: ${knownEvents.joinToString(", ")}"
                )
            }

            // Validate parameter count
            val expectedParams = when (eventName) {
                "player_join", "player_quit" -> 2  // event + player
                "block_break", "block_place" -> 3   // event + block + player
                "chat_message" -> 3                 // event + player + message
                "entity_death" -> 3                 // event + entity + killer
                "entity_damage" -> 3                // event + entity + amount
                else -> 2
            }
            val actualParams = 1 + stmt.dataParams.size  // event + data params
            if (actualParams != expectedParams) {
                throw CompilationException(
                    "RuntimeError: Event '$eventName' expects $expectedParams parameters (including event), got $actualParams"
                )
            }

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
        is Stmt.EnableStmt -> lowerBlock(stmt.block)
        is Stmt.DisableStmt -> lowerBlock(stmt.block)
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

        // Collect field declarations for implicit init method and field resolution
        val fieldDeclarations = mutableListOf<Stmt.VarStmt>()
        val fieldNames = mutableSetOf<String>()

        for (member in stmt.body.stmts) {
            when (member) {
                is Stmt.FuncStmt -> {
                    val methodLowerer = AstLowerer()
                    methodLowerer.fieldNames = fieldNames
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
                is Stmt.VarStmt -> {
                    fieldDeclarations.add(member)
                    fieldNames.add(member.name.lexeme)
                }
                else -> {}
            }
        }

        // Create implicit init method that initializes fields
        if (fieldDeclarations.isNotEmpty()) {
            val initLowerer = AstLowerer()
            initLowerer.fieldNames = fieldNames
            // self is at index 0
            initLowerer.locals["self"] = 0
            initLowerer.regCounter = 1

            for (field in fieldDeclarations) {
                val fieldName = field.name.lexeme
                val valueDst = initLowerer.freshReg()
                initLowerer.lowerExpr(field.value ?: Expr.LiteralExpr(Value.Null), valueDst)
                initLowerer.emit(IrInstr.SetField(0, fieldName, valueDst))
            }

            val initResult = initLowerer.lower(emptyList())
            methods["init"] = MethodInfo(
                arity = 1,  // just self
                instrs = initResult.instrs,
                constants = initResult.constants,
                defaultValues = emptyList()
            )
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
                // Local variable: move to dst as per lowerExpr contract
                if (reg != dst) {
                    emit(IrInstr.Move(dst, reg))
                }
                dst
            } else if (expr.name.lexeme in fieldNames) {
                // Field access: self.fieldName
                emit(IrInstr.GetField(dst, 0, expr.name.lexeme))
                dst
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
                        } else if (expr.target.name.lexeme in fieldNames) {
                            // Field compound assignment: self.field op= value
                            val currentReg = freshReg()
                            emit(IrInstr.GetField(currentReg, 0, expr.target.name.lexeme))
                            val valueReg = lowerExpr(expr.value, freshReg())
                            emit(BinaryOp(currentReg, binaryOp, currentReg, valueReg))
                            emit(IrInstr.SetField(0, expr.target.name.lexeme, currentReg))
                            currentReg
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
                        } else if (expr.target.name.lexeme in fieldNames) {
                            // Field assignment: self.fieldName = value
                            val src = lowerExpr(expr.value, freshReg())
                            emit(IrInstr.SetField(0, expr.target.name.lexeme, src))
                            src
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
        is Expr.ElvisExpr -> {
            // left ?? right desugars to:
            // temp = left
            // if temp != null goto use_left  (skip evaluating right)
            // temp = right
            // use_left:
            // result = temp
            val useLeftLabel = freshLabel()
            val endLabel = freshLabel()
            val tempReg = freshReg()
            lowerExpr(expr.left, tempReg)
            // Check if NOT null: compare tempReg to null constant
            val nullConstIdx = addConstant(Value.Null)
            val nullTempReg = freshReg()
            emit(LoadImm(nullTempReg, nullConstIdx))
            val cmpReg = freshReg()
            emit(BinaryOp(cmpReg, TokenType.EQ_EQ, tempReg, nullTempReg)) // cmpReg = (tempReg == null)
            emit(JumpIfFalse(cmpReg, useLeftLabel)) // if NOT null (cmpReg=false), goto useLeft
            // Null case: evaluate right into tempReg
            lowerExpr(expr.right, tempReg)
            emit(Jump(endLabel))
            emit(Label(useLeftLabel))
            // Not null: keep tempReg (left value), fall through to Move
            emit(Label(endLabel))
            emit(Move(dst, tempReg))
            dst
        }
        is Expr.SafeCallExpr -> {
            // obj?.name desugars to:
            // temp = obj
            // if (temp == null) goto null_label
            // result = temp.name
            // goto end_label
            // null_label:
            // result = null
            // end_label:
            val nullLabel = freshLabel()
            val endLabel = freshLabel()
            val objReg = freshReg()
            lowerExpr(expr.obj, objReg)
            // Check if null
            val nullConstIdx = addConstant(Value.Null)
            val nullTempReg = freshReg()
            emit(LoadImm(nullTempReg, nullConstIdx))
            val cmpReg = freshReg()
            emit(BinaryOp(cmpReg, TokenType.BANG_EQ, objReg, nullTempReg))
            emit(JumpIfFalse(cmpReg, nullLabel))
            // Not null: do field access
            emit(IrInstr.GetField(dst, objReg, expr.name.lexeme))
            emit(Jump(endLabel))
            emit(Label(nullLabel))
            // Null: load null into dst
            emit(LoadImm(dst, nullConstIdx))
            emit(Label(endLabel))
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
        is Expr.ThrowExpr -> {
            val valueReg = lowerExpr(expr.value, freshReg())
            emit(IrInstr.Throw(valueReg))
            dst  // unreachable, but for type consistency
        }
        // AnnotationExpr should not appear at runtime - annotations are compile-time only
        is Expr.AnnotationExpr -> error("AnnotationExpr should not appear in lowered IR")
    }
}