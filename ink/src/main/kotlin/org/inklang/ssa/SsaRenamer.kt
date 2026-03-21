package org.inklang.ssa

import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.IrLabel

/**
 * Renames variables in SSA form.
 *
 * Algorithm:
 * 1. Maintain a counter and stack for each register
 * 2. Walk dominator tree in preorder
 * 3. For each instruction:
 *    - Replace used registers with top of stack
 *    - For definitions, push new version onto stack
 * 4. Update phi operands in successor blocks
 * 5. Pop stacks when leaving blocks
 */
class SsaRenamer(
    private val blocks: List<SsaBlock>,
    private val cfg: ControlFlowGraph,
    private val domFrontier: DominanceFrontier,
    private val globalRegs: Set<Int>
) {
    // Counter for each register (next version to assign)
    private val counters = mutableMapOf<Int, Int>()

    // Stack of current versions for each register
    private val stacks = mutableMapOf<Int, MutableList<Int>>()

    // Map from block ID to SSA block
    private val blockMap: Map<Int, SsaBlock> = blocks.associateBy { it.id }

    // Map from (baseReg, version) to the new SsaValue after renaming
    // Used to track which SsaValue corresponds to which version

    init {
        // Initialize counters and stacks for global registers
        for (reg in globalRegs) {
            counters[reg] = 0
            stacks[reg] = mutableListOf()
        }
    }

    /**
     * Perform the renaming pass.
     */
    fun rename() {
        if (blocks.isEmpty()) return

        val domTree = domFrontier.dominatorTree()
        val entryBlockId = cfg.entryBlock
        val entryBlock = blockMap[entryBlockId] ?: return
        renameBlock(entryBlock, domTree)
    }

    /**
     * Rename variables in a single block, then recursively process
     * dominated children before popping the stacks.
     */
    private fun renameBlock(block: SsaBlock, domTree: Map<Int, List<Int>>) {
        // Track how many pushes we do to pop them later
        val pushCounts = mutableMapOf<Int, Int>()

        // Process phi functions first
        for (i in block.phiFunctions.indices) {
            val phi = block.phiFunctions[i]
            val baseReg = phi.result.baseReg
            val newVersion = newVersion(baseReg)
            block.phiFunctions[i] = PhiFunction(
                result = SsaValue(baseReg, newVersion),
                operands = phi.operands
            )
            pushCounts[baseReg] = (pushCounts[baseReg] ?: 0) + 1
        }

        // Process instructions
        val newInstrs = mutableListOf<SsaInstr>()
        for (instr in block.instrs) {
            val newInstr = renameInstr(instr, pushCounts)
            newInstrs.add(newInstr)
        }
        block.instrs.clear()
        block.instrs.addAll(newInstrs)

        // Update phi operands in successor blocks
        for (succId in block.successors) {
            val succBlock = blockMap[succId] ?: continue
            updatePhiOperands(succBlock, block.id)
        }

        // Recursively process children in dominator tree
        // (stacks remain valid for children)
        for (childId in domTree[block.id] ?: emptyList()) {
            val childBlock = blockMap[childId] ?: continue
            renameBlock(childBlock, domTree)
        }

        // Pop stacks after all dominated children have been processed
        for ((reg, count) in pushCounts) {
            val stack = stacks[reg] ?: continue
            repeat(count) {
                if (stack.isNotEmpty()) {
                    stack.removeLast()
                }
            }
        }
    }

    /**
     * Rename operands in an instruction.
     */
    private fun renameInstr(instr: SsaInstr, pushCounts: MutableMap<Int, Int>): SsaInstr {
        return when (instr) {
            is SsaInstr.LoadImm -> {
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.LoadImm(SsaValue(instr.definedValue.baseReg, newVersion), instr.constIndex)
            }
            is SsaInstr.LoadGlobal -> {
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.LoadGlobal(SsaValue(instr.definedValue.baseReg, newVersion), instr.name)
            }
            is SsaInstr.StoreGlobal -> {
                val src = getCurrentValue(instr.src.baseReg)
                SsaInstr.StoreGlobal(instr.name, src)
            }
            is SsaInstr.BinaryOp -> {
                val src1 = getCurrentValue(instr.src1.baseReg)
                val src2 = getCurrentValue(instr.src2.baseReg)
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.BinaryOp(SsaValue(instr.definedValue.baseReg, newVersion), instr.op, src1, src2)
            }
            is SsaInstr.UnaryOp -> {
                val src = getCurrentValue(instr.src.baseReg)
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.UnaryOp(SsaValue(instr.definedValue.baseReg, newVersion), instr.op, src)
            }
            is SsaInstr.Jump -> instr
            is SsaInstr.JumpIfFalse -> {
                val src = getCurrentValue(instr.src.baseReg)
                SsaInstr.JumpIfFalse(src, instr.target)
            }
            is SsaInstr.Label -> instr
            is SsaInstr.LoadFunc -> {
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.LoadFunc(
                    SsaValue(instr.definedValue.baseReg, newVersion),
                    instr.name, instr.arity, instr.instrs, instr.constants, instr.defaultValues
                )
            }
            is SsaInstr.Call -> {
                val func = getCurrentValue(instr.func.baseReg)
                val args = instr.args.map { getCurrentValue(it.baseReg) }
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.Call(SsaValue(instr.definedValue.baseReg, newVersion), func, args)
            }
            is SsaInstr.Return -> {
                val src = getCurrentValue(instr.src.baseReg)
                SsaInstr.Return(src)
            }
            is SsaInstr.Move -> {
                val src = getCurrentValue(instr.src.baseReg)
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.Move(SsaValue(instr.definedValue.baseReg, newVersion), src)
            }
            is SsaInstr.GetIndex -> {
                val obj = getCurrentValue(instr.obj.baseReg)
                val index = getCurrentValue(instr.index.baseReg)
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.GetIndex(SsaValue(instr.definedValue.baseReg, newVersion), obj, index)
            }
            is SsaInstr.SetIndex -> {
                val obj = getCurrentValue(instr.obj.baseReg)
                val index = getCurrentValue(instr.index.baseReg)
                val src = getCurrentValue(instr.src.baseReg)
                SsaInstr.SetIndex(obj, index, src)
            }
            is SsaInstr.NewArray -> {
                val elements = instr.elements.map { getCurrentValue(it.baseReg) }
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.NewArray(SsaValue(instr.definedValue.baseReg, newVersion), elements)
            }
            is SsaInstr.GetField -> {
                val obj = getCurrentValue(instr.obj.baseReg)
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.GetField(SsaValue(instr.definedValue.baseReg, newVersion), obj, instr.name)
            }
            is SsaInstr.SetField -> {
                val obj = getCurrentValue(instr.obj.baseReg)
                val src = getCurrentValue(instr.src.baseReg)
                SsaInstr.SetField(obj, instr.name, src)
            }
            is SsaInstr.NewInstance -> {
                val classReg = getCurrentValue(instr.classReg.baseReg)
                val args = instr.args.map { getCurrentValue(it.baseReg) }
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.NewInstance(SsaValue(instr.definedValue.baseReg, newVersion), classReg, args)
            }
            is SsaInstr.IsType -> {
                val src = getCurrentValue(instr.src.baseReg)
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.IsType(SsaValue(instr.definedValue.baseReg, newVersion), src, instr.typeName)
            }
            is SsaInstr.LoadClass -> {
                val newVersion = newVersion(instr.definedValue.baseReg)
                pushCounts[instr.definedValue.baseReg] = (pushCounts[instr.definedValue.baseReg] ?: 0) + 1
                SsaInstr.LoadClass(SsaValue(instr.definedValue.baseReg, newVersion), instr.name, instr.superClass, instr.methods)
            }
            is SsaInstr.Break -> instr
            is SsaInstr.Next -> instr
        }
    }

    /**
     * Update phi operands in a successor block with current values.
     */
    private fun updatePhiOperands(succBlock: SsaBlock, predBlockId: Int) {
        val newPhis = mutableListOf<PhiFunction>()
        for (phi in succBlock.phiFunctions) {
            val baseReg = phi.result.baseReg
            val currentValue = getCurrentValue(baseReg)
            val newOperands = phi.operands + (predBlockId to currentValue)
            newPhis.add(PhiFunction(phi.result, newOperands))
        }
        succBlock.phiFunctions.clear()
        succBlock.phiFunctions.addAll(newPhis)
    }

    /**
     * Get the current value (version) for a register.
     */
    private fun getCurrentValue(baseReg: Int): SsaValue {
        val stack = stacks[baseReg]
        return if (stack != null && stack.isNotEmpty()) {
            SsaValue(baseReg, stack.last())
        } else {
            // Register hasn't been defined yet - use version 0
            // This can happen for function parameters or undefined values
            SsaValue(baseReg, 0)
        }
    }

    /**
     * Generate a new version for a register and push it onto the stack.
     */
    private fun newVersion(baseReg: Int): Int {
        val counter = counters[baseReg] ?: 0
        counters[baseReg] = counter + 1
        stacks.getOrPut(baseReg) { mutableListOf() }.add(counter)
        return counter
    }
}
