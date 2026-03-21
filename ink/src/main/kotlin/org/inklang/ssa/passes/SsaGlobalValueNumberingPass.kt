package org.inklang.ssa.passes

import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.ssa.*

/**
 * Global Value Numbering (GVN) pass.
 *
 * GVN detects when two expressions compute the same value and eliminates
 * redundant computations by reusing previously computed values.
 *
 * This implementation works per-block (intra-block GVN) for correctness.
 * In SSA form, each value is defined exactly once within a block, making
 * the hash-based approach straightforward and safe:
 * - Within a block, instructions execute in order, so we see definitions before uses
 * - No aliasing concerns within a block (SSA register versions are unique)
 * - Single-def guarantee: no need to track multiple definitions
 *
 * Example (within a block):
 *   r0.0 = LoadImm #1
 *   r1.0 = LoadImm #2
 *   r2.0 = r0.0 + r1.0    ; hash = (PLUS, r0.0, r1.0), canonical = r2.0
 *   r3.0 = r0.0 + r1.0    ; hash matches! replace with r3.0 = Move(r2.0)
 *
 * GVN is particularly powerful after copy propagation, which unifies
 * equivalent moves and makes more expressions identical.
 */
class SsaGlobalValueNumberingPass : SsaOptPass {
    override val name = "GlobalValueNumbering"

    // Current SSA function being processed
    private lateinit var currentFunc: SsaFunction

    override fun run(ssaFunc: SsaFunction): SsaOptResult {
        currentFunc = ssaFunc

        var changed = false

        for (block in ssaFunc.blocks) {
            changed = processBlock(block) || changed
        }

        return SsaOptResult(ssaFunc, changed)
    }

    /**
     * Process a single block for GVN.
     * Returns true if any changes were made.
     */
    private fun processBlock(block: SsaBlock): Boolean {
        // Value table for this block: maps expression hash -> canonical SsaValue
        val valueTable = mutableMapOf<ExprHash, SsaValue>()

        // Track if we've seen a side-effecting instruction since a given hash
        // This is conservative: after a side effect, we invalidate matching hashes
        val invalidatedBySideEffect = mutableSetOf<ExprHash>()

        var changed = false

        // Process all instructions in order
        for (instr in block.instrs) {
            // Track side-effecting instructions - they invalidate matching hashes
            if (hasSideEffects(instr)) {
                val hash = computeHash(instr)
                if (hash != null) {
                    invalidatedBySideEffect.add(hash)
                }
            }

            // Try to GVN this instruction
            val replacement = tryGvn(instr, valueTable, invalidatedBySideEffect)
            if (replacement != null) {
                val idx = block.instrs.indexOf(instr)
                if (idx >= 0) {
                    block.instrs[idx] = replacement
                    changed = true
                }
                // Don't add to value table - the replacement (Move) will be processed
                // and will add the canonical value instead
                continue
            }

            // No replacement - add this instruction's result to the value table
            val defined = instr.definedValue
            if (defined != null) {
                val hash = computeHash(instr)
                if (hash != null && hash !in invalidatedBySideEffect) {
                    // First occurrence wins (canonical representation)
                    valueTable.putIfAbsent(hash, defined)
                }
            }
        }

        return changed
    }

    /**
     * Try to find an existing canonical value for this expression.
     * Returns a Move instruction if we can reuse a value, null otherwise.
     */
    private fun tryGvn(
        instr: SsaInstr,
        valueTable: Map<ExprHash, SsaValue>,
        invalidated: Set<ExprHash>
    ): SsaInstr.Move? {
        // Can only GVN instructions that define a value
        val defined = instr.definedValue ?: return null

        // Skip instructions with side effects
        if (hasSideEffects(instr)) return null

        val hash = computeHash(instr) ?: return null

        // Can't reuse if invalidated by side effect
        if (hash in invalidated) return null

        // Check if we've seen this expression before in this block
        val canonicalValue = valueTable[hash] ?: return null

        // Don't replace with self
        if (canonicalValue == defined) return null

        return SsaInstr.Move(defined, canonicalValue)
    }

    /**
     * Compute a hash key for an instruction based on its opcode and operands.
     * Returns null for instructions we can't GVN.
     */
    private fun computeHash(instr: SsaInstr): ExprHash? {
        return when (instr) {
            is SsaInstr.LoadImm -> ExprHash.Const(currentFunc.constants.getOrNull(instr.constIndex))
            is SsaInstr.UnaryOp -> ExprHash.Unary(instr.op, instr.src)
            is SsaInstr.BinaryOp -> ExprHash.Binary(instr.op, instr.src1, instr.src2)
            is SsaInstr.Move -> ExprHash.Move(instr.src)
            is SsaInstr.GetIndex -> ExprHash.GetIndex(instr.obj, instr.index)
            is SsaInstr.GetField -> ExprHash.GetField(instr.obj, instr.name)
            is SsaInstr.IsType -> ExprHash.IsType(instr.src, instr.typeName)
            // Can't GVN: calls, loads, stores, allocations
            else -> null
        }
    }

    /**
     * Check if an instruction has side effects that prevent GVN.
     * Instructions with side effects can't be reordered or eliminated.
     */
    private fun hasSideEffects(instr: SsaInstr): Boolean {
        return when (instr) {
            is SsaInstr.Call -> true  // Could call arbitrary functions
            is SsaInstr.StoreGlobal -> true
            is SsaInstr.SetIndex -> true
            is SsaInstr.SetField -> true
            is SsaInstr.NewArray -> true  // Allocates new memory
            is SsaInstr.NewInstance -> true
            is SsaInstr.LoadGlobal -> true  // Could alias with stores
            is SsaInstr.LoadFunc -> false  // Purely loads a function reference
            is SsaInstr.LoadClass -> false
            is SsaInstr.Break, is SsaInstr.Next -> true  // Control flow effects
            is SsaInstr.Return -> true
            else -> false
        }
    }

    /**
     * Expression hash types - captures the essential structure of each expression
     * for equality comparison in GVN.
     */
    private sealed class ExprHash {
        abstract override fun equals(other: Any?): Boolean
        abstract override fun hashCode(): Int

        data class Const(val value: Value?) : ExprHash() {
            override fun equals(other: Any?): Boolean = other is Const && this.value == other.value
            override fun hashCode(): Int = 31 + (value?.hashCode() ?: 0)
        }

        data class Move(val src: SsaValue) : ExprHash() {
            override fun equals(other: Any?): Boolean = other is Move && this.src == other.src
            override fun hashCode(): Int = 32 + src.hashCode()
        }

        data class Unary(val op: TokenType, val src: SsaValue) : ExprHash() {
            override fun equals(other: Any?): Boolean = other is Unary && this.op == other.op && this.src == other.src
            override fun hashCode(): Int = 33 * op.hashCode() + src.hashCode()
        }

        data class Binary(val op: TokenType, val src1: SsaValue, val src2: SsaValue) : ExprHash() {
            override fun equals(other: Any?): Boolean = other is Binary &&
                this.op == other.op && this.src1 == other.src1 && this.src2 == other.src2
            override fun hashCode(): Int = 34 * op.hashCode() + 17 * src1.hashCode() + src2.hashCode()
        }

        data class GetIndex(val obj: SsaValue, val index: SsaValue) : ExprHash() {
            override fun equals(other: Any?): Boolean = other is GetIndex &&
                this.obj == other.obj && this.index == other.index
            override fun hashCode(): Int = 35 * obj.hashCode() + index.hashCode()
        }

        data class GetField(val obj: SsaValue, val name: String) : ExprHash() {
            override fun equals(other: Any?): Boolean = other is GetField &&
                this.obj == other.obj && this.name == other.name
            override fun hashCode(): Int = 36 * obj.hashCode() + name.hashCode()
        }

        data class IsType(val src: SsaValue, val typeName: String) : ExprHash() {
            override fun equals(other: Any?): Boolean = other is IsType &&
                this.src == other.src && this.typeName == other.typeName
            override fun hashCode(): Int = 37 * src.hashCode() + typeName.hashCode()
        }
    }
}
