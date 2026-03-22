package org.inklang.peg

import org.inklang.lang.Expr
import org.inklang.lang.Stmt
import org.inklang.lang.Value

/**
 * Represents a field definition in a declaration extension.
 */
data class FieldDef(val name: String, val type: String)

/**
 * Extension for a statement rule.
 * @param name The keyword/name of the statement
 * @param pattern The parser pattern to match
 * @param lower Function to convert parse results to AST statements
 */
data class StatementExtension(
    val name: String,
    val pattern: Parser<*>,
    val lower: (List<Any?>) -> Stmt
)

/**
 * Extension for a declaration rule.
 * @param name The keyword/name of the declaration
 * @param pattern The parser pattern to match
 * @param fields The field definitions for this declaration
 * @param blocks The block names associated with this declaration
 * @param lower Function to convert parse results to AST statements
 */
data class DeclarationExtension(
    val name: String,
    val pattern: Parser<*>,
    val fields: List<FieldDef>,
    val blocks: List<String>,
    val lower: (List<Any?>) -> Stmt
)

/**
 * The grammar contributed by a single package.
 * Contains statements, declarations, and custom rules.
 */
data class PackageGrammar(
    val name: String,
    val statements: Map<String, StatementExtension>,
    val declarations: Map<String, DeclarationExtension>,
    val rules: Map<String, Parser<*>>
)

/**
 * Builder for statement extensions.
 * Allows defining the pattern and lowering function.
 */
class StatementExtensionBuilder {
    lateinit var pattern: Parser<*>
    var lower: (List<Any?>) -> Stmt = { _ -> Stmt.ExprStmt(Expr.LiteralExpr(Value.Null)) }

    fun build(name: String): StatementExtension {
        return StatementExtension(name, pattern, lower)
    }
}

/**
 * Builder for declaration extensions.
 * Allows defining the pattern, fields, blocks, and lowering function.
 */
class DeclarationExtensionBuilder {
    lateinit var pattern: Parser<*>
    val fields = mutableListOf<FieldDef>()
    val blocks = mutableListOf<String>()
    var lower: (List<Any?>) -> Stmt = { _ -> Stmt.ExprStmt(Expr.LiteralExpr(Value.Null)) }

    fun fields(vararg f: FieldDef) {
        fields.addAll(f)
    }

    fun blocks(vararg b: String) {
        blocks.addAll(b)
    }

    fun build(name: String): DeclarationExtension {
        return DeclarationExtension(name, pattern, fields.toList(), blocks.toList(), lower)
    }
}

/**
 * Context for registering grammar extensions from a package.
 * Packages use this to register custom statements, declarations, and rules.
 */
class InkExtensionContext {
    var name: String = "<unknown>"
    private val statements = mutableMapOf<String, StatementExtension>()
    private val declarations = mutableMapOf<String, DeclarationExtension>()
    private val rules = mutableMapOf<String, Parser<*>>()

    /**
     * Registers a custom statement extension.
     * @param name The keyword that introduces this statement
     * @param block DSL block to configure the statement pattern and lowering
     */
    fun registerStatement(name: String, block: StatementExtensionBuilder.() -> Unit) {
        val builder = StatementExtensionBuilder()
        block(builder)
        statements[name] = builder.build(name)
    }

    /**
     * Registers a custom declaration extension.
     * @param name The keyword that introduces this declaration
     * @param block DSL block to configure the declaration pattern, fields, blocks, and lowering
     */
    fun registerDeclaration(name: String, block: DeclarationExtensionBuilder.() -> Unit) {
        val builder = DeclarationExtensionBuilder()
        block(builder)
        declarations[name] = builder.build(name)
    }

    /**
     * Registers a custom parsing rule.
     * @param name The name of the rule
     * @param parser The parser to use for this rule
     */
    fun registerRule(name: String, parser: Parser<*>) {
        rules[name] = parser
    }

    /**
     * Builds a PackageGrammar from all registered extensions.
     * @return A PackageGrammar containing all registered statements, declarations, and rules
     */
    fun build(): PackageGrammar {
        return PackageGrammar(
            name = name,
            statements = statements.toMap(),
            declarations = declarations.toMap(),
            rules = rules.toMap()
        )
    }
}
