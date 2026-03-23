package org.inklang.grammar

import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Sealed class hierarchy representing grammar rule types in the IR.
 * Discriminated by the "type" field in JSON.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
sealed class Rule {
    @Serializable
    @SerialName("seq")
    data class Seq(val items: List<Rule>) : Rule()

    @Serializable
    @SerialName("choice")
    data class Choice(val items: List<Rule>) : Rule()

    @Serializable
    @SerialName("many")
    data class Many(val item: Rule) : Rule()

    @Serializable
    @SerialName("many1")
    data class Many1(val item: Rule) : Rule()

    @Serializable
    @SerialName("optional")
    data class Optional(val item: Rule) : Rule()

    @Serializable
    @SerialName("ref")
    data class Ref(val rule: String) : Rule()

    @Serializable
    @SerialName("keyword")
    data class Keyword(val value: String) : Rule()

    @Serializable
    @SerialName("literal")
    data class Literal(val value: String) : Rule()

    @Serializable
    @SerialName("block")
    data class Block(val scope: String? = null) : Rule()

    @Serializable
    @SerialName("identifier")
    data object Identifier : Rule()

    @Serializable
    @SerialName("int")
    data object Int : Rule()

    @Serializable
    @SerialName("float")
    data object Float : Rule()

    @Serializable
    @SerialName("string")
    data object StringRule : Rule()
}

/**
 * A named rule entry in the grammar. Contains the rule definition
 * and an optional handler class name.
 */
@Serializable
data class RuleEntry(
    val rule: Rule,
    val handler: String? = null
)

/**
 * A declaration definition — a top-level construct introduced by a keyword.
 */
@Serializable
data class DeclarationDef(
    val keyword: String,
    val nameRule: Rule,
    val scopeRules: List<String> = emptyList(),
    val inheritsBase: Boolean = false,
    val handler: String? = null
)

/**
 * The top-level grammar IR structure produced by the Quill build system.
 */
@Serializable
data class GrammarPackage(
    val version: Int,
    @SerialName("package")
    val packageName: String,
    val keywords: List<String> = emptyList(),
    val rules: Map<String, RuleEntry> = emptyMap(),
    val declarations: List<DeclarationDef> = emptyList()
)

/**
 * Runtime descriptor within the manifest (optional).
 */
@Serializable
data class RuntimeDescriptor(
    val jar: String,
    val entry: String
)

/**
 * The ink-manifest.json file that describes a package distribution.
 */
@Serializable
data class InkManifest(
    val name: String,
    val version: String,
    val grammar: String? = null,
    val runtime: RuntimeDescriptor? = null
)
