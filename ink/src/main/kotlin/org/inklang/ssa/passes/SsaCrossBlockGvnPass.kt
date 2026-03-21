package org.inklang.ssa.passes

import org.inklang.lang.TokenType
import org.inklang.lang.Value
import org.inklang.ssa.*

/**
 * Global Value Numbering across basic blocks.
 *
 * Extends intra-block GVN to propagate value equivalence across blocks
 * using domination information. When a block A dominates block B, any
 * expression computed in A is available in B (unless invalidated by side effects).
 *
 * Algorithm:
 * 1. Process blocks in topological domination order (dominators before dependents)
 * 2. Maintain a global value table: ExprHash -> canonical SsaValue
 * 3. When entering a block, inherit the table from its immediate dominator
 * 4. Process instructions within the block (same as intra-block GVN)
 * 5. After side effects, invalidate matching hashes for dominated blocks
 * 6. Add block's definitions to the table for downstream use
 *
 * At merge points (blocks with 2+ predecessors), we invalidate all hashes
 * conservatively since different paths may have different definitions.
 */
class SsaCrossBlockGvnPass : SsaOptPass {
    override val name = "CrossBlockGVN"

    private lateinit var currentFunc: SsaFunction

    override fun run(ssaFunc: SsaFunction): SsaOptResult {
        currentFunc = ssaFunc

        if (ssaFunc.blocks.isEmpty()) return SsaOptResult(ssaFunc, false)

        val cfg = ssaFunc.cfg
        val idoms = cfg.immediateDominators()

        // Sort blocks by domination: process dominators before their dependents
        val sortedBlocks = topologicalSortByDom(ssaFunc.blocks.toList(), idoms)

        // Global value table: shared across all processed blocks
        val globalTable = mutableMapOf<ExprHash, SsaValue>()

        // Track which blocks were changed
        var changed = false

        for (block in sortedBlocks) {
            // Determine invalidated hashes for this block
            val invalidated = mutableSetOf<ExprHash>()

            // At merge points (2+ predecessors), invalidate all hashes
            // This is conservative but correct - different paths may define different values
            if (block.predecessors.size > 1) {
                invalidated.addAll(globalTable.keys)
            }

            // Process the block
            val blockChanged = processBlock(block, globalTable, invalidated)
            if (blockChanged) changed = true
        }

        return SsaOptResult(ssaFunc, changed)
    }

    /**
     * Topological sort of blocks by domination order.
     * Ensures that when we process a block, all its dominators have been processed.
     */
    private fun topologicalSortByDom(blocks: List<SsaBlock>, idoms: Map<Int, Int?>): List<SsaBlock> {
        val result = mutableListOf<SsaBlock>()
        val processed = mutableSetOf<Int>()
        val blockMap = blocks.associateBy { it.id }

        fun processBlock(blockId: Int) {
            if (blockId in processed) return
            val idom = idoms[blockId]
            if (idom != null && idom !in processed) {
                processBlock(idom)
            }
            processed.add(blockId)
            blockMap[blockId]?.let { result.add(it) }
        }

        for (block in blocks) {
            processBlock(block.id)
        }

        return result
    }

    /**
     * Process a single block for GVN.
     */
    private fun processBlock(
        block: SsaBlock,
        table: MutableMap<ExprHash, SsaValue>,
        invalidated: Set<ExprHash>
    ): Boolean {
        var changed = false

        // Remove invalidated entries
        for (hash in invalidated) {
            table.remove(hash)
        }

        // Process phi functions first - they don't have side effects
        for (phi in block.phiFunctions) {
            val hash = ExprHash.Phi(phi.result)
            if (hash !in invalidated) {
                table.putIfAbsent(hash, phi.result)
            }
        }

        // Process all instructions in order
        for (instr in block.instrs) {
            // Track side-effecting instructions
            if (hasSideEffects(instr)) {
                val hash = computeHash(instr)
                if (hash != null) {
                    table.remove(hash)
                }
            }

            // Try to find a canonical value for this expression
            val replacement = tryGvn(instr, table)
            if (replacement != null) {
                // Replace instruction with Move
                val idx = block.instrs.indexOf(instr)
                if (idx >= 0) {
                    block.instrs[idx] = replacement
                    changed = true
                }
                continue
            }

            // No replacement - add this instruction's result to the value table
            val defined = instr.definedValue
            if (defined != null) {
                val hash = computeHash(instr)
                if (hash != null && hash !in invalidated && !hasSideEffects(instr)) {
                    table.putIfAbsent(hash, defined)
                }
            }
        }

        return changed
    }

    /**
     * Try to find an existing canonical value for this expression.
     */
    private fun tryGvn(instr: SsaInstr, table: Map<ExprHash, SsaValue>): SsaInstr.Move? {
        val defined = instr.definedValue ?: return null
        if (hasSideEffects(instr)) return null

        val hash = computeHash(instr) ?: return null
        val canonicalValue = table[hash] ?: return null

        // Don't replace with self
        if (canonicalValue == defined) return null

        return SsaInstr.Move(defined, canonicalValue)
    }

    /**
     * Compute a hash key for an instruction.
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
            else -> null
        }
    }

    private fun hasSideEffects(instr: SsaInstr): Boolean {
        return when (instr) {
            is SsaInstr.Call -> true
            is SsaInstr.StoreGlobal -> true
            is SsaInstr.SetIndex -> true
            is SsaInstr.SetField -> true
            is SsaInstr.NewArray -> true
            is SsaInstr.NewInstance -> true
            is SsaInstr.LoadGlobal -> true  // Conservative: could alias with stores
            is SsaInstr.Break, is SsaInstr.Next -> true
            is SsaInstr.Return -> true
            else -> false
        }
    }

    /**
     * Expression hash types for GVN.
     */
    private sealed class ExprHash {
        abstract override fun equals(other: Any?): Boolean
        abstract override fun hashCode(): Int

        data class Const(val value: Value?) : ExprHash() {
            override fun equals(other: Any?) = other is Const && this.value == other.value
            override fun hashCode() = 31 + (value?.hashCode() ?: 0)
        }

        data class Move(val src: SsaValue) : ExprHash() {
            override fun equals(other: Any?) = other is Move && this.src == other.src
            override fun hashCode() = 32 + src.hashCode()
        }

        data class Unary(val op: TokenType, val src: SsaValue) : ExprHash() {
            override fun equals(other: Any?) = other is Unary && this.op == other.op && this.src == other.src
            override fun hashCode() = 33 * op.hashCode() + src.hashCode()
        }

        data class Binary(val op: TokenType, val src1: SsaValue, val src2: SsaValue) : ExprHash() {
            override fun equals(other: Any?) = other is Binary &&
                this.op == other.op && this.src1 == other.src1 && this.src2 == other.src2
            override fun hashCode() = 34 * op.hashCode() + 17 * src1.hashCode() + src2.hashCode()
        }

        data class GetIndex(val obj: SsaValue, val index: SsaValue) : ExprHash() {
            override fun equals(other: Any?) = other is GetIndex &&
                this.obj == other.obj && this.index == other.index
            override fun hashCode() = 35 * obj.hashCode() + index.hashCode()
        }

        data class GetField(val obj: SsaValue, val name: String) : ExprHash() {
            override fun equals(other: Any?) = other is GetField &&
                this.obj == other.obj && this.name == other.name
            override fun hashCode() = 36 * obj.hashCode() + name.hashCode()
        }

        data class IsType(val src: SsaValue, val typeName: String) : ExprHash() {
            override fun equals(other: Any?) = other is IsType &&
                this.src == other.src && this.typeName == other.typeName
            override fun hashCode() = 37 * src.hashCode() + typeName.hashCode()
        }

        data class Phi(val result: SsaValue) : ExprHash() {
            override fun equals(other: Any?) = other is Phi && this.result == other.result
            override fun hashCode() = 38 + result.hashCode()
        }
    }
}
