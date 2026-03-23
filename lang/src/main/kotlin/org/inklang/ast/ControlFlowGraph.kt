package org.inklang.ast

import org.inklang.lang.IrInstr
import org.inklang.lang.IrLabel

/**
 * Represents a natural loop in the CFG.
 * A natural loop has a single entry point (the loop header) and
 * back edges from the loop body back to the header.
 */
data class Loop(
    val header: Int,              // Block ID of loop header
    val body: Set<Int>,           // Block IDs in the loop body (including header)
    val backEdges: Set<Int>       // Block IDs that have back edges to header
)

/**
 * Control Flow Graph (CFG) for a sequence of IR instructions.
 * Provides structure for dataflow analysis and optimization.
 */
class ControlFlowGraph(
    val blocks: List<BasicBlock>,
    val entryBlock: Int,          // ID of entry block (always 0)
    val exitBlocks: Set<Int>      // IDs of exit blocks (those ending with Return or no successors)
) {
    private val blockMap: Map<Int, BasicBlock> = blocks.associateBy { it.id }

    /**
     * Get a basic block by its ID
     */
    fun getBlock(id: Int): BasicBlock? = blockMap[id]

    /**
     * Find all blocks reachable from the entry block
     */
    fun reachable(): Set<Int> {
        val visited = mutableSetOf<Int>()
        val worklist = mutableListOf(entryBlock)

        while (worklist.isNotEmpty()) {
            val blockId = worklist.removeLast()
            if (blockId in visited) continue
            visited.add(blockId)

            val block = getBlock(blockId) ?: continue
            worklist.addAll(block.successors)
        }

        return visited
    }

    /**
     * Find unreachable blocks (dead code)
     */
    fun unreachable(): Set<Int> {
        val reachableBlocks = reachable()
        return blocks.map { it.id }.toSet() - reachableBlocks
    }

    /**
     * Compute dominators for each block.
     * A block D dominates block B if every path from entry to B goes through D.
     * Returns a map from block ID to set of block IDs that dominate it.
     */
    fun dominators(): Map<Int, Set<Int>> {
        val allBlocks = blocks.map { it.id }.toSet()
        val dominators = mutableMapOf<Int, MutableSet<Int>>()

        // Initialize: entry block is dominated only by itself
        dominators[entryBlock] = mutableSetOf(entryBlock)

        // All other blocks are initially dominated by all blocks
        for (block in blocks) {
            if (block.id != entryBlock) {
                dominators[block.id] = allBlocks.toMutableSet()
            }
        }

        // Iterate until fixed point
        var changed = true
        while (changed) {
            changed = false
            for (block in blocks) {
                if (block.id == entryBlock) continue

                val predDominators = block.predecessors
                    .map { dominators[it] ?: emptySet() }

                if (predDominators.isEmpty()) continue

                // New dominators = block itself U intersection of all predecessor dominators
                val intersection = predDominators.reduce { a, b -> a.intersect(b) }
                val newDominators = intersection + block.id

                if (dominators[block.id] != newDominators) {
                    dominators[block.id] = newDominators.toMutableSet()
                    changed = true
                }
            }
        }

        return dominators
    }

    /**
     * Compute immediate dominators for each block.
     * The immediate dominator of B is the unique block that strictly dominates B
     * and is strictly dominated by all other dominators of B.
     */
    fun immediateDominators(): Map<Int, Int?> {
        val doms = dominators()
        val idoms = mutableMapOf<Int, Int?>()

        for (blockId in blocks.map { it.id }) {
            val strictDoms = (doms[blockId] ?: emptySet()) - blockId
            if (strictDoms.isEmpty()) {
                idoms[blockId] = null
                continue
            }

            // Find the immediate dominator: the strict dominator that doesn't dominate any other strict dominator
            val immediate = strictDoms.first { candidate ->
                strictDoms.all { other ->
                    candidate == other || (doms[other]?.contains(candidate) == false)
                }
            }
            idoms[blockId] = immediate
        }

        return idoms
    }

    /**
     * Compute post-dominators for each block.
     * A block P post-dominates block B if every path from B to exit goes through P.
     */
    fun postDominators(): Map<Int, Set<Int>> {
        val allBlocks = blocks.map { it.id }.toSet()
        val postDominators = mutableMapOf<Int, MutableSet<Int>>()

        // Initialize: exit blocks are post-dominated only by themselves
        for (exitId in exitBlocks) {
            postDominators[exitId] = mutableSetOf(exitId)
        }

        // All other blocks are initially post-dominated by all blocks
        for (block in blocks) {
            if (block.id !in exitBlocks) {
                postDominators[block.id] = allBlocks.toMutableSet()
            }
        }

        // Iterate until fixed point
        var changed = true
        while (changed) {
            changed = false
            for (block in blocks) {
                if (block.id in exitBlocks) continue

                val succPostDominators = block.successors
                    .map { postDominators[it] ?: emptySet() }

                if (succPostDominators.isEmpty()) continue

                // New post-dominators = block itself U intersection of all successor post-dominators
                val intersection = succPostDominators.reduce { a, b -> a.intersect(b) }
                val newPostDominators = intersection + block.id

                if (postDominators[block.id] != newPostDominators) {
                    postDominators[block.id] = newPostDominators.toMutableSet()
                    changed = true
                }
            }
        }

        return postDominators
    }

    /**
     * Detect natural loops in the CFG.
     * A natural loop is defined by a back edge: an edge from block B to block H
     * where H dominates B. The loop consists of H and all blocks that can reach B
     * without going through H.
     */
    fun naturalLoops(): List<Loop> {
        val doms = dominators()
        val loops = mutableListOf<Loop>()

        // Find back edges: edges where target dominates source
        for (block in blocks) {
            for (succId in block.successors) {
                // Check if this is a back edge (succ dominates block)
                if (doms[block.id]?.contains(succId) == true) {
                    // Found a back edge: block -> succId
                    // succId is the loop header
                    val headerId = succId
                    val backEdgeSource = block.id

                    // Find all blocks in the loop body
                    val loopBody = mutableSetOf(headerId)
                    val worklist = mutableListOf(backEdgeSource)

                    while (worklist.isNotEmpty()) {
                        val currentId = worklist.removeLast()
                        if (currentId in loopBody) continue
                        loopBody.add(currentId)

                        // Add predecessors that are not the header
                        val currentBlock = getBlock(currentId) ?: continue
                        for (predId in currentBlock.predecessors) {
                            if (predId != headerId) {
                                worklist.add(predId)
                            }
                        }
                    }

                    // Find all back edges to this header
                    val allBackEdges = blocks
                        .filter { headerId in it.successors && doms[it.id]?.contains(headerId) == true }
                        .map { it.id }
                        .toSet()

                    loops.add(Loop(headerId, loopBody, allBackEdges))
                }
            }
        }

        return loops
    }

    /**
     * Find the loop that contains a given block (if any)
     */
    fun containingLoop(blockId: Int): Loop? {
        return naturalLoops().firstOrNull { blockId in it.body }
    }

    /**
     * Pretty print the CFG for debugging
     */
    fun dump(): String {
        val sb = StringBuilder()
        sb.appendLine("Control Flow Graph:")
        sb.appendLine("  Entry: Block$entryBlock")
        sb.appendLine("  Exits: ${exitBlocks.map { "Block$it" }}")
        sb.appendLine()

        for (block in blocks) {
            val reachable = if (block.id in reachable()) "" else " [UNREACHABLE]"
            sb.appendLine("  Block${block.id}${reachable}:")
            sb.appendLine("    Label: ${block.label?.let { "L${it.id}" } ?: "none"}")
            sb.appendLine("    Range: [${block.startIndex}, ${block.endIndex}]")
            sb.appendLine("    Instructions: ${block.instrs.size}")
            sb.appendLine("    Successors: ${block.successors.map { "Block$it" }}")
            sb.appendLine("    Predecessors: ${block.predecessors.map { "Block$it" }}")
        }

        val loops = naturalLoops()
        if (loops.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("  Loops:")
            for ((i, loop) in loops.withIndex()) {
                sb.appendLine("    Loop $i: header=Block${loop.header}, body=${loop.body.map { "Block$it" }}")
            }
        }

        return sb.toString()
    }

    companion object {
        /**
         * Build a CFG from a list of IR instructions.
         */
        fun build(instrs: List<IrInstr>): ControlFlowGraph {
            if (instrs.isEmpty()) {
                return ControlFlowGraph(emptyList(), 0, emptySet())
            }

            // Step 1: Find leaders (instructions that start basic blocks)
            val leaders = findLeaders(instrs)

            // Step 2: Build label-to-index map
            val labelToIndex = mutableMapOf<Int, Int>()
            for ((idx, instr) in instrs.withIndex()) {
                if (instr is IrInstr.Label) {
                    labelToIndex[instr.label.id] = idx
                }
            }

            // Step 3: Build basic blocks
            val blocks = buildBlocks(instrs, leaders)

            // Step 4: Add edges between blocks
            addEdges(blocks, instrs, labelToIndex)

            // Step 5: Find exit blocks
            val exitBlocks = blocks
                .filter { it.successors.isEmpty() || it.instrs.lastOrNull() is IrInstr.Return }
                .map { it.id }
                .toSet()

            return ControlFlowGraph(blocks, blocks.firstOrNull()?.id ?: 0, exitBlocks)
        }

        /**
         * Find leader instructions (instructions that start basic blocks)
         */
        private fun findLeaders(instrs: List<IrInstr>): Set<Int> {
            val leaders = mutableSetOf(0) // First instruction is always a leader

            for ((idx, instr) in instrs.withIndex()) {
                when (instr) {
                    // Labels are leaders
                    is IrInstr.Label -> leaders.add(idx)

                    // TRY_START is a basic block boundary (handler stack change)
                    is IrInstr.TryStart -> leaders.add(idx)

                    // Instructions after terminals are leaders
                    is IrInstr.Jump, is IrInstr.JumpIfFalse, is IrInstr.Return,
                    is IrInstr.Break, is IrInstr.Next -> {
                        if (idx + 1 < instrs.size) {
                            leaders.add(idx + 1)
                        }
                    }
                    else -> {}
                }
            }

            return leaders
        }

        /**
         * Build basic blocks from instructions and leaders
         */
        private fun buildBlocks(instrs: List<IrInstr>, leaders: Set<Int>): List<BasicBlock> {
            val sortedLeaders = leaders.sorted()
            val blocks = mutableListOf<BasicBlock>()

            for ((i, leaderIdx) in sortedLeaders.withIndex()) {
                // Find end of this block (one before next leader, or end of instructions)
                val endIdx = if (i + 1 < sortedLeaders.size) {
                    sortedLeaders[i + 1] - 1
                } else {
                    instrs.lastIndex
                }

                // Extract instructions for this block
                val blockInstrs = instrs.subList(leaderIdx, endIdx + 1)

                // Get label if block starts with one
                val label = blockInstrs.firstOrNull() as? IrInstr.Label

                // Filter out the label instruction for the instruction list
                val actualInstrs = if (label != null) {
                    blockInstrs.drop(1)
                } else {
                    blockInstrs
                }

                blocks.add(BasicBlock(
                    id = i,
                    label = label?.label,
                    instrs = actualInstrs,
                    startIndex = leaderIdx,
                    endIndex = endIdx
                ))
            }

            return blocks
        }

        /**
         * Add control flow edges between blocks
         */
        private fun addEdges(
            blocks: List<BasicBlock>,
            instrs: List<IrInstr>,
            labelToIndex: Map<Int, Int>
        ) {
            // Build a map from instruction index to block ID
            val indexToBlockId = mutableMapOf<Int, Int>()
            for (block in blocks) {
                for (idx in block.startIndex..block.endIndex) {
                    indexToBlockId[idx] = block.id
                }
            }

            // Find loop headers and loop ends for Break/Next resolution
            // Pattern: JumpIfFalse -> label (loop condition), Jump back -> loop
            val loopExits = findLoopExits(instrs, labelToIndex, indexToBlockId)

            for (block in blocks) {
                val lastInstr = block.instrs.lastOrNull()

                when {
                    // Return: no successors
                    lastInstr is IrInstr.Return -> {
                        // No successors
                    }

                    // Unconditional jump
                    lastInstr is IrInstr.Jump -> {
                        val targetIdx = labelToIndex[lastInstr.target.id]
                        val targetBlockId = indexToBlockId[targetIdx]
                        if (targetBlockId != null) {
                            block.successors.add(targetBlockId)
                            blocks[targetBlockId].predecessors.add(block.id)
                        }
                    }

                    // Conditional jump: both fall-through and jump target
                    lastInstr is IrInstr.JumpIfFalse -> {
                        // Fall-through to next block
                        if (block.id + 1 < blocks.size) {
                            block.successors.add(block.id + 1)
                            blocks[block.id + 1].predecessors.add(block.id)
                        }

                        // Jump target
                        val targetIdx = labelToIndex[lastInstr.target.id]
                        val targetBlockId = indexToBlockId[targetIdx]
                        if (targetBlockId != null) {
                            block.successors.add(targetBlockId)
                            blocks[targetBlockId].predecessors.add(block.id)
                        }
                    }

                    // Break: jumps to loop exit
                    lastInstr is IrInstr.Break -> {
                        val exitBlockId = loopExits[block.id]
                        if (exitBlockId != null) {
                            block.successors.add(exitBlockId)
                            blocks[exitBlockId].predecessors.add(block.id)
                        }
                    }

                    // Next: jumps to loop header (for next iteration)
                    lastInstr is IrInstr.Next -> {
                        val headerBlockId = findLoopHeader(block.id, instrs, labelToIndex, indexToBlockId, blocks)
                        if (headerBlockId != null) {
                            block.successors.add(headerBlockId)
                            blocks[headerBlockId].predecessors.add(block.id)
                        }
                    }

                    // Fall-through to next block
                    block.id + 1 < blocks.size -> {
                        block.successors.add(block.id + 1)
                        blocks[block.id + 1].predecessors.add(block.id)
                    }
                }
            }
        }

        /**
         * Find loop exits for Break statements.
         * Returns a map from block ID containing Break to the block ID after the loop.
         */
        private fun findLoopExits(
            instrs: List<IrInstr>,
            labelToIndex: Map<Int, Int>,
            indexToBlockId: Map<Int, Int>
        ): Map<Int, Int> {
            val loopExits = mutableMapOf<Int, Int>()

            // Find all backward jumps (loop back edges)
            for ((idx, instr) in instrs.withIndex()) {
                if (instr is IrInstr.Jump) {
                    val targetIdx = labelToIndex[instr.target.id]
                    if (targetIdx != null && targetIdx < idx) {
                        // This is a loop: targetIdx is loop start, instruction after idx is loop exit
                        val loopStartIdx = targetIdx
                        val loopEndIdx = idx
                        val exitIdx = idx + 1

                        val exitBlockId = indexToBlockId[exitIdx]

                        // Find all Break instructions in this loop
                        for (i in loopStartIdx..loopEndIdx) {
                            if (instrs[i] is IrInstr.Break) {
                                val breakBlockId = indexToBlockId[i]
                                if (breakBlockId != null && exitBlockId != null) {
                                    loopExits[breakBlockId] = exitBlockId
                                }
                            }
                        }
                    }
                }
            }

            return loopExits
        }

        /**
         * Find the loop header for a block containing Next
         */
        private fun findLoopHeader(
            blockId: Int,
            instrs: List<IrInstr>,
            labelToIndex: Map<Int, Int>,
            indexToBlockId: Map<Int, Int>,
            blocks: List<BasicBlock>
        ): Int? {
            val block = blocks.getOrNull(blockId) ?: return null

            // Look for backward jumps that could be loop back edges
            // The Next should jump to the loop header (the target of the back-edge jump)
            for ((idx, instr) in instrs.withIndex()) {
                if (instr is IrInstr.Jump) {
                    val targetIdx = labelToIndex[instr.target.id]
                    if (targetIdx != null && targetIdx < idx) {
                        // This is a loop back edge
                        val loopStartIdx = targetIdx
                        val loopEndIdx = idx

                        // Check if our block is within this loop
                        if (block.startIndex in loopStartIdx..loopEndIdx) {
                            // Find the block that contains the loop header (target of back edge)
                            return indexToBlockId[loopStartIdx]
                        }
                    }
                }
            }

            return null
        }
    }
}
