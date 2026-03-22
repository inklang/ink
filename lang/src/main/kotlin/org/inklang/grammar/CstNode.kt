package org.inklang.grammar

sealed class CstNode {
    data class Declaration(
        val keyword: String,
        val name: String,
        val body: List<CstNode>
    ) : CstNode()

    data class RuleMatch(
        val ruleName: String,
        val children: List<CstNode>
    ) : CstNode()

    data class KeywordNode(val value: String) : CstNode()
    data class IdentifierNode(val value: String) : CstNode()
    data class IntLiteral(val value: String) : CstNode()
    data class FloatLiteral(val value: String) : CstNode()
    data class StringLiteral(val value: String) : CstNode()
    data class Block(val scope: String?, val children: List<CstNode>) : CstNode()
}
