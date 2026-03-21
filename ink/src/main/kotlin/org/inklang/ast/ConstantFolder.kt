package org.inklang.ast

import org.inklang.lang.Expr
import org.inklang.lang.Stmt
import org.inklang.lang.TokenType
import org.inklang.lang.Value

class ConstantFolder {
    fun fold(expr: Expr): Expr = when (expr) {
        is Expr.BinaryExpr -> {
            val left = fold(expr.left)
            val right = fold(expr.right)
            if (left is Expr.LiteralExpr && right is Expr.LiteralExpr) {
                val l = left.literal
                val r = right.literal
                when (expr.op.type) {
                    TokenType.PLUS  -> foldArith(l, r) { a, b -> a + b }
                    TokenType.MINUS -> foldArith(l, r) { a, b -> a - b }
                    TokenType.STAR  -> foldArith(l, r) { a, b -> a * b }
                    TokenType.SLASH -> foldArith(l, r) { a, b -> a / b }
                    else -> null
                } ?: Expr.BinaryExpr(left, expr.op, right)
            } else {
                Expr.BinaryExpr(left, expr.op, right)
            }
        }
        is Expr.GroupExpr -> {
            val inner = fold(expr.expr)
            inner as? Expr.LiteralExpr ?: Expr.GroupExpr(inner)
        }
        else -> expr
    }

    fun foldStmt(stmt: Stmt): Stmt = when (stmt) {
        is Stmt.VarStmt    -> stmt.copy(value = stmt.value?.let { fold(it) })
        is Stmt.ExprStmt   -> stmt.copy(expr = fold(stmt.expr))
        is Stmt.ReturnStmt -> stmt.copy(value = stmt.value?.let { fold(it) })
        is Stmt.IfStmt     -> stmt.copy(
            condition = fold(stmt.condition),
            then = foldBlock(stmt.then),
            elseBranch = stmt.elseBranch?.let { foldElse(it) }
        )
        is Stmt.WhileStmt  -> stmt.copy(
            condition = fold(stmt.condition),
            body = foldBlock(stmt.body)
        )
        is Stmt.BlockStmt  -> foldBlock(stmt)
        is Stmt.FuncStmt   -> stmt.copy(body = foldBlock(stmt.body))
        else -> stmt
    }

    private fun foldBlock(block: Stmt.BlockStmt): Stmt.BlockStmt =
        block.copy(stmts = block.stmts.map { foldStmt(it) })

    private fun foldElse(branch: Stmt.ElseBranch): Stmt.ElseBranch = when (branch) {
        is Stmt.ElseBranch.Else   -> branch.copy(block = foldBlock(branch.block))
        is Stmt.ElseBranch.ElseIf -> branch.copy(stmt = foldStmt(branch.stmt) as Stmt.IfStmt)
    }

    private fun foldArith(l: Value, r: Value, op: (Double, Double) -> Double): Expr.LiteralExpr? {
        return when {
            l is Value.Int && r is Value.Int -> {
                val result = op(l.value.toDouble(), r.value.toDouble())
                Expr.LiteralExpr(Value.Int(result.toInt()))
            }
            l is Value.Float && r is Value.Float -> {
                val result = op(l.value.toDouble(), r.value.toDouble())
                Expr.LiteralExpr(Value.Float(result.toFloat()))
            }
            l is Value.Double || r is Value.Double -> {
                val a = toDouble(l) ?: return null
                val b = toDouble(r) ?: return null
                Expr.LiteralExpr(Value.Double(op(a, b)))
            }
            l is Value.Float || r is Value.Float -> {
                val a = toDouble(l) ?: return null
                val b = toDouble(r) ?: return null
                Expr.LiteralExpr(Value.Float(op(a, b).toFloat()))
            }
            else -> null
        }
    }

    private fun toDouble(v: Value) = when (v) {
        is Value.Int    -> v.value.toDouble()
        is Value.Double -> v.value
        is Value.Float  -> v.value.toDouble()
        else -> null
    }
}