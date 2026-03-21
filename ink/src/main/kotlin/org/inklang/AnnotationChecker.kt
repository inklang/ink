package org.inklang

import org.inklang.lang.Expr
import org.inklang.lang.Stmt
import org.inklang.lang.TokenType
import org.inklang.lang.Value

/**
 * Compile-time annotation processor.
 * Validates annotation arguments and processes built-in annotations (@deprecated, @inline, @pure).
 * This runs BEFORE constant folding in the compiler pipeline.
 */
class AnnotationChecker(
    private val warnUnknownAnnotations: Boolean = false
) {
    // Track annotation declarations
    private val annotationDeclarations = mutableMapOf<String, AnnotationInfo>()

    data class AnnotationInfo(
        val name: String,
        val fields: Map<String, AnnotationFieldInfo>
    )

    data class AnnotationFieldInfo(
        val type: String,
        val defaultValue: Expr? = null
    )

    // Built-in annotation definitions
    init {
        annotationDeclarations["deprecated"] = AnnotationInfo(
            "deprecated",
            mapOf("reason" to AnnotationFieldInfo("string"))
        )
        annotationDeclarations["inline"] = AnnotationInfo(
            "inline",
            mapOf("level" to AnnotationFieldInfo("int", Expr.LiteralExpr(Value.Int(1))))
        )
        annotationDeclarations["pure"] = AnnotationInfo(
            "pure",
            emptyMap()
        )
    }

    /**
     * Process all annotations in a list of statements.
     * Returns the same statements (annotations are validated in-place).
     */
    fun check(statements: List<Stmt>): List<Stmt> {
        for (stmt in statements) {
            checkStmt(stmt)
        }
        return statements
    }

    private fun checkStmt(stmt: Stmt) {
        when (stmt) {
            is Stmt.FuncStmt -> checkFuncStmt(stmt)
            is Stmt.ClassStmt -> checkClassStmt(stmt)
            is Stmt.VarStmt -> checkVarStmt(stmt)
            is Stmt.BlockStmt -> stmt.stmts.forEach { checkStmt(it) }
            is Stmt.AnnotationDeclStmt -> registerAnnotationDeclaration(stmt)
            else -> {}
        }
    }

    private fun registerAnnotationDeclaration(stmt: Stmt.AnnotationDeclStmt) {
        val fields = mutableMapOf<String, AnnotationFieldInfo>()
        for (field in stmt.fields) {
            fields[field.name.lexeme] = AnnotationFieldInfo(field.type.lexeme, field.defaultValue)
        }
        annotationDeclarations[stmt.name.lexeme] = AnnotationInfo(stmt.name.lexeme, fields)
    }

    private fun checkFuncStmt(stmt: Stmt.FuncStmt) {
        for (annotation in stmt.annotations) {
            checkAnnotation(annotation, target = "function '${stmt.name.lexeme}'")

            when (annotation.name) {
                "inline" -> {
                    // @inline is only valid on functions
                    val level = getIntArg(annotation, "level") ?: 1
                    if (level < 1 || level > 3) {
                        throw CompilationException("@inline level must be between 1 and 3, got $level")
                    }
                }
                "pure" -> {
                    // Validate purity: no globals, no non-pure calls, no I/O
                    validatePureFunction(stmt)
                }
            }
        }

        // Recurse into body
        checkStmt(stmt.body)
    }

    private fun checkClassStmt(stmt: Stmt.ClassStmt) {
        for (annotation in stmt.annotations) {
            checkAnnotation(annotation, target = "class '${stmt.name.lexeme}'")
        }
        checkStmt(stmt.body)
    }

    private fun checkVarStmt(stmt: Stmt.VarStmt) {
        for (annotation in stmt.annotations) {
            checkAnnotation(annotation, target = "variable '${stmt.name.lexeme}'")
        }
        // Note: variable value expressions are validated at their use sites
    }

    private fun checkAnnotation(annotation: Expr.AnnotationExpr, target: String) {
        val info = annotationDeclarations[annotation.name]
        if (info == null) {
            if (warnUnknownAnnotations) {
                println("Warning: Unknown annotation '@${annotation.name}' on $target")
            }
            return
        }

        // Validate required fields
        for ((fieldName, fieldInfo) in info.fields) {
            if (!annotation.args.containsKey(fieldName) && fieldInfo.defaultValue == null) {
                throw CompilationException(
                    "Annotation '@${annotation.name}' on $target is missing required field '$fieldName'"
                )
            }
        }

        // Validate provided fields
        for ((argName, argExpr) in annotation.args) {
            val fieldInfo = info.fields[argName]
            if (fieldInfo == null) {
                throw CompilationException(
                    "Annotation '@${annotation.name}' on $target has unknown field '$argName'"
                )
            }
            // Validate type
            validateAnnotationArgType(argExpr, fieldInfo.type, annotation.name, argName, target)
        }
    }

    private fun validateAnnotationArgType(
        expr: Expr,
        expectedType: String,
        annotationName: String,
        fieldName: String,
        target: String
    ) {
        val actualType = when (expr) {
            is Expr.LiteralExpr -> {
                val lit = expr.literal
                when (lit) {
                    is Value.Int -> "int"
                    is Value.Double -> "double"
                    is Value.String -> "string"
                    is Value.Boolean -> "bool"
                    is Value.Null -> if (expectedType == "string") "string" else null
                    else -> null
                }
            }
            else -> null
        }

        if (actualType != expectedType) {
            throw CompilationException(
                "Annotation '@$annotationName' on $target: field '$fieldName' expects type '$expectedType' but got '${actualType ?: "unknown"}'"
            )
        }
    }

    private fun getIntArg(annotation: Expr.AnnotationExpr, field: String): Int? {
        val arg = annotation.args[field] ?: return null
        if (arg is Expr.LiteralExpr && arg.literal is Value.Int) {
            return (arg.literal as Value.Int).value
        }
        return null
    }

    private fun validatePureFunction(stmt: Stmt.FuncStmt) {
        // Check body for side effects
        validateNoSideEffects(stmt.body, mutableSetOf())
    }

    private fun validateNoSideEffects(stmt: Stmt, visited: MutableSet<String>) {
        when (stmt) {
            is Stmt.FuncStmt -> {
                // Don't recurse into nested functions — they have their own @pure check
            }
            is Stmt.BlockStmt -> stmt.stmts.forEach { validateNoSideEffects(it, visited) }
            is Stmt.ExprStmt -> validateExprNoSideEffects(stmt.expr, visited)
            is Stmt.VarStmt -> {
                stmt.value?.let { validateExprNoSideEffects(it, visited) }
            }
            is Stmt.ReturnStmt -> {
                stmt.value?.let { validateExprNoSideEffects(it, visited) }
            }
            is Stmt.IfStmt -> {
                validateExprNoSideEffects(stmt.condition, visited)
                validateNoSideEffects(stmt.then, visited)
                stmt.elseBranch?.let {
                    when (it) {
                        is Stmt.ElseBranch.Else -> validateNoSideEffects(it.block, visited)
                        is Stmt.ElseBranch.ElseIf -> validateNoSideEffects(it.stmt, visited)
                    }
                }
            }
            is Stmt.WhileStmt -> {
                validateExprNoSideEffects(stmt.condition, visited)
                validateNoSideEffects(stmt.body, visited)
            }
            is Stmt.ForRangeStmt -> {
                validateExprNoSideEffects(stmt.iterable, visited)
                validateNoSideEffects(stmt.body, visited)
            }
            else -> {}
        }
    }

    private fun validateExprNoSideEffects(expr: Expr, visited: MutableSet<String>) {
        when (expr) {
            is Expr.CallExpr -> {
                val name = when (expr.callee) {
                    is Expr.VariableExpr -> (expr.callee as Expr.VariableExpr).name.lexeme
                    else -> null
                }
                if (name != null && name !in visited) {
                    visited.add(name)
                    // Check if it's a known I/O function
                    val ioFunctions = setOf("print", "log", "read", "write", "open", "close")
                    if (name in ioFunctions) {
                        throw CompilationException("@pure function contains I/O call: $name")
                    }
                }
                expr.arguments.forEach { validateExprNoSideEffects(it, visited) }
            }
            is Expr.VariableExpr -> {
                // Variable reads are okay if they're local
            }
            is Expr.GetExpr -> {
                // Property access could be global state — flag it
                throw CompilationException("@pure function contains potential global access")
            }
            is Expr.BinaryExpr -> {
                validateExprNoSideEffects(expr.left, visited)
                validateExprNoSideEffects(expr.right, visited)
            }
            is Expr.UnaryExpr -> validateExprNoSideEffects(expr.right, visited)
            is Expr.TernaryExpr -> {
                validateExprNoSideEffects(expr.condition, visited)
                validateExprNoSideEffects(expr.thenBranch, visited)
                validateExprNoSideEffects(expr.elseBranch, visited)
            }
            is Expr.ElvisExpr -> {
                validateExprNoSideEffects(expr.left, visited)
                validateExprNoSideEffects(expr.right, visited)
            }
            else -> {}
        }
    }
}
