package org.inklang.lang

sealed class Expr {

    companion object {
        val BINARY_OPS = setOf(
            TokenType.PLUS, TokenType.MINUS,
            TokenType.STAR, TokenType.SLASH, TokenType.PERCENT,
            TokenType.POW,
            TokenType.EQ_EQ, TokenType.BANG_EQ,
            TokenType.LT, TokenType.LTE,
            TokenType.GT, TokenType.GTE,
            TokenType.KW_AND, TokenType.KW_OR,
            TokenType.DOT_DOT
        )

        val UNARY_OPS = setOf(
            TokenType.MINUS,
            TokenType.BANG,
            TokenType.KW_NOT,
            TokenType.INCREMENT,
            TokenType.DECREMENT
        )

        val COMPOUND_OPS = setOf(
            TokenType.ADD_EQUALS,
            TokenType.SUB_EQUALS,
            TokenType.MUL_EQUALS,
            TokenType.DIV_EQUALS,
            TokenType.MOD_EQUALS
        )
    }

    data class LiteralExpr(val literal: Value) : Expr()

    data class ListExpr(val elements: List<Expr>) : Expr() {
        init { require(elements.isNotEmpty()) { "List literal must have at least one element." } }
    }

    data class SetExpr(val elements: List<Expr>) : Expr()

    data class TupleExpr(val elements: List<Expr>) : Expr()

    data class MapExpr(val entries: List<Pair<Expr, Expr>>) : Expr()

    data class VariableExpr(val name: Token) : Expr()

    data class AssignExpr(val target: Expr, val op: Token, val value: Expr) : Expr() {
        init {
            require(target is VariableExpr || target is GetExpr || target is IndexExpr) {
                "Invalid assignment target: ${target::class.simpleName}"
            }
            require(op.type == TokenType.ASSIGN || op.type in COMPOUND_OPS) {
                "Invalid assignment operator: ${op.type}"
            }
        }
    }

    data class BinaryExpr(val left: Expr, val op: Token, val right: Expr) : Expr() {
        init { require(op.type in BINARY_OPS) { "Invalid binary operator: ${op.type}" } }
    }

    data class UnaryExpr(val op: Token, val right: Expr) : Expr() {
        init { require(op.type in UNARY_OPS) { "Invalid unary operator: ${op.type}" } }
    }

    data class TernaryExpr(val condition: Expr, val thenBranch: Expr, val elseBranch: Expr) : Expr()

    data class GroupExpr(val expr: Expr) : Expr()

    data class CallExpr(val callee: Expr, val paren: Token, val arguments: List<Expr>) : Expr()

    data class LambdaExpr(val params: List<Param>, val body: Stmt.BlockStmt) : Expr()

    data class GetExpr(val obj: Expr, val name: Token) : Expr()

    data class IndexExpr(val obj: Expr, val index: Expr) : Expr()

    data class IsExpr(val expr: Expr, val type: Token) : Expr()
    data class HasExpr(val target: Expr, val field: Expr) : Expr()  // new

    data class SafeCallExpr(val obj: Expr, val name: Token) : Expr()

    data class ElvisExpr(val left: Expr, val right: Expr) : Expr()

    data class ThrowExpr(val value: Expr) : Expr()

    // Annotation expression: @annotationName(arg1=value1, arg2=value2)
    data class AnnotationExpr(
        val name: String,
        val args: Map<String, Expr>
    ) : Expr()
}

data class Param(
    val annotations: List<Expr.AnnotationExpr>,
    val name: Token,
    val type: Token?,
    val defaultValue: Expr? = null
)

sealed class Stmt {
    data class ImportStmt(val namespace: Token) : Stmt()
    data class ImportFromStmt(val namespace: Token, val tokens: List<Token>) : Stmt() {
        init {
            require(tokens.isNotEmpty()) { "Import must have at least one identifier defined." }
        }
    }

        data class ClassStmt(
            val annotations: List<Expr.AnnotationExpr>,
            val name: Token,
            val superClass: Token?,
            val body: BlockStmt
        ) : Stmt()
    data class ExprStmt(val expr: Expr) : Stmt()
    data class EnumStmt(val name: Token, val values: List<Token>) : Stmt()
    data class VarStmt(
        val annotations: List<Expr.AnnotationExpr>,
        val keyword: Token,
        val name: Token,
        val value: Expr?
    ) : Stmt()
    data class BlockStmt(val stmts: List<Stmt>) : Stmt()
    data class IfStmt(val condition: Expr, val then: BlockStmt, val elseBranch: ElseBranch?) :
        Stmt()

    data class FuncStmt(
        val annotations: List<Expr.AnnotationExpr>,
        val name: Token,
        val params: List<Param>,
        val returnType: Token?,
        val body: BlockStmt
    ) : Stmt()

    data class ReturnStmt(val value: Expr?) : Stmt()
    data class WhileStmt(val condition: Expr, val body: BlockStmt) : Stmt()
    data class ForRangeStmt(val variable: Token, val iterable: Expr, val body: BlockStmt) : Stmt()
    object BreakStmt : Stmt()
    object NextStmt : Stmt()

    sealed class ElseBranch {
        data class Else(val block: BlockStmt) : ElseBranch()
        data class ElseIf(val stmt: IfStmt) : ElseBranch()
    }

    data class ConfigField(val name: Token, val type: Token, val defaultValue: Expr?)
    data class ConfigStmt(val name: Token, val fields: List<ConfigField>) : Stmt()

    data class TableField(val name: Token, val type: String?, val isKey: Boolean, val defaultValue: Expr?)
    data class TableStmt(val name: Token, val fields: List<TableField>) : Stmt()

    // Annotation types
    data class AnnotationField(val name: Token, val type: Token, val defaultValue: Expr?)
    data class AnnotationDeclStmt(val name: Token, val fields: List<AnnotationField>) : Stmt()

    // Parameter in an event declaration: player: Player
    data class EventParam(
        val name: Token,
        val type: Token
    )

    // Event declaration: event player_join(player: Player)
    data class EventDeclStmt(
        val name: Token,
        val params: List<EventParam>
    ) : Stmt()

    // On-handler registration: on player_join(event, player) { ... }
    data class OnHandlerStmt(
        val eventName: Token,
        val handlerName: Token?,  // null if not named
        val eventParam: Token,   // user-named first param (the event object)
        val dataParams: List<EventParam>,
        val body: Stmt.BlockStmt
    ) : Stmt()

    // Lifecycle blocks for plugins
    data class EnableStmt(val block: BlockStmt) : Stmt()
    data class DisableStmt(val block: BlockStmt) : Stmt()

    // Mob declaration: mob Zombie { on_spawn { ... }, equipment { ... }, drops { ... }, experience: 5, skills { ... } }
    data class MobEquipmentSlot(val slot: String, val item: Token)  // head: DIAMOND_HELMET
    data class MobDropEntry(val item: Token, val chance: Token?, val amount: Token?)  // ROTTEN_FLESH 50
    data class MobSkillEvent(val eventName: String, val threshold: Pair<String, Int>?, val body: Stmt.BlockStmt)
    data class MobStmt(
        val name: Token,
        val equipment: List<MobEquipmentSlot>,
        val drops: List<MobDropEntry>,
        val experience: Int?,
        val skills: List<MobSkillEvent>
    ) : Stmt()

}
