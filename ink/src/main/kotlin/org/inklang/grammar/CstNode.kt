package org.inklang.grammar

@kotlinx.serialization.Serializable
sealed class CstNode {
    @kotlinx.serialization.Serializable
    data class Declaration(
        val keyword: String,
        val name: String,
        val body: List<CstNode>
    ) : CstNode()

    @kotlinx.serialization.Serializable
    data class RuleMatch(
        val ruleName: String,
        val children: List<CstNode>
    ) : CstNode()

    @kotlinx.serialization.Serializable
    data class KeywordNode(val value: String) : CstNode()
    @kotlinx.serialization.Serializable
    data class IdentifierNode(val value: String) : CstNode()
    @kotlinx.serialization.Serializable
    data class IntLiteral(val value: String) : CstNode()
    @kotlinx.serialization.Serializable
    data class FloatLiteral(val value: String) : CstNode()
    @kotlinx.serialization.Serializable
    data class StringLiteral(val value: String) : CstNode()
    @kotlinx.serialization.Serializable
    data class Block(val scope: String?, val children: List<CstNode>) : CstNode()

    @kotlinx.serialization.Serializable
    data class FunctionBlock(val funcIdx: Int) : CstNode()
}
