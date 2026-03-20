package org.inklang.ssa

import org.inklang.ast.BasicBlock
import org.inklang.ast.ControlFlowGraph

/**
 * Computes dominance frontiers for SSA construction.
 *
 * The dominance frontier of a block B (DF(B)) is the set of all blocks Y such that:
 * - B dominates a predecessor of Y
 * - B does not strictly dominate Y
 *
 * Intuitively, DF(B) is where B's definitions "stop dominating" - places where
 * a phi function is needed for any variable defined in B.
 */
class DominanceFrontier(private val cfg: ControlFlowGraph) {
    // Immediate dominators
    private val idoms: Map<Int, Int?> = cfg.immediateDominators()

    // Dominance frontiers for each block
    private val frontiers: Map<Int, Set<Int>> = computeFrontiers()

    /**
     * Get the dominance frontier for a block.
     */
    fun frontier(blockId: Int): Set<Int> = frontiers[blockId] ?: emptySet()

    /**
     * Get all dominance frontiers.
     */
    fun allFrontiers(): Map<Int, Set<Int>> = frontiers

    /**
     * Compute the iterated dominance frontier (IDF) for a set of blocks.
     * This is used to determine where phi functions need to be placed.
     *
     * IDF(S) = DF(S) U DF(DF(S)) U DF(DF(DF(S))) U ...
     * Until a fixed point is reached.
     */
    fun iteratedFrontier(blocks: Set<Int>): Set<Int> {
        val result = mutableSetOf<Int>()
        var worklist = blocks.toMutableSet()
        val processed = mutableSetOf<Int>()

        while (worklist.isNotEmpty()) {
            val blockId = worklist.first()
            worklist.remove(blockId)

            if (blockId in processed) continue
            processed.add(blockId)

            val df = frontier(blockId)
            for (y in df) {
                if (y !in result) {
                    result.add(y)
                    // If Y is newly added to the result, its DF needs to be processed too
                    if (y !in blocks && y !in processed) {
                        worklist.add(y)
                    }
                }
            }
        }

        return result
    }

    /**
     * Compute dominance frontiers for all blocks using the Cytron algorithm.
     */
    private fun computeFrontiers(): Map<Int, Set<Int>> {
        val frontiers = mutableMapOf<Int, MutableSet<Int>>()

        // Initialize empty frontiers
        for (block in cfg.blocks) {
            frontiers[block.id] = mutableSetOf()
        }

        // Build dominator tree children map
        val domTreeChildren = mutableMapOf<Int, MutableList<Int>>()
        for (block in cfg.blocks) {
            domTreeChildren[block.id] = mutableListOf()
        }
        for (block in cfg.blocks) {
            val idom = idoms[block.id]
            if (idom != null) {
                domTreeChildren[idom]?.add(block.id)
            }
        }

        // For each block, compute DF
        // DF(B) = { Y | B dominates a predecessor of Y, but B does not strictly dominate Y }
        for (block in cfg.blocks) {
            // If block has multiple predecessors, check each predecessor's dominators
            if (block.predecessors.size >= 2) {
                for (predId in block.predecessors) {
                    // Walk up the dominator tree from pred until we reach B's immediate dominator
                    var runner = predId
                    while (runner != idoms[block.id] && runner != block.id) {
                        frontiers[runner]?.add(block.id)
                        runner = idoms[runner] ?: break
                    }
                }
            }
        }

        return frontiers
    }

    /**
     * Get the dominator tree as a map from block ID to children block IDs.
     */
    fun dominatorTree(): Map<Int, List<Int>> {
        val children = mutableMapOf<Int, MutableList<Int>>()
        for (block in cfg.blocks) {
            children[block.id] = mutableListOf()
        }
        for (block in cfg.blocks) {
            val idom = idoms[block.id]
            if (idom != null) {
                children[idom]?.add(block.id)
            }
        }
        return children
    }

    /**
     * Get blocks in dominator tree preorder (for renaming).
     */
    fun dominatorTreePreorder(): List<Int> {
        val result = mutableListOf<Int>()
        val domTree = dominatorTree()

        fun visit(blockId: Int) {
            result.add(blockId)
            for (child in domTree[blockId] ?: emptyList()) {
                visit(child)
            }
        }

        visit(cfg.entryBlock)
        return result
    }

    companion object {
        /**
         * Compute dominance frontiers for a CFG.
         */
        fun compute(cfg: ControlFlowGraph): DominanceFrontier {
            return DominanceFrontier(cfg)
        }
    }
}
