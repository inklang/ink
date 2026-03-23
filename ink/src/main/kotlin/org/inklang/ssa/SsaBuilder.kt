package org.inklang.ssa

import org.inklang.ast.BasicBlock
import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.*

/**
 * Builds SSA form from IR instructions.
 *
 * Algorithm (Cytron et al.):
 * 1. Build CFG from IR
 * 2. Compute dominance frontiers
 * 3. Find all registers that are defined
 * 4. Place phi functions at iterated dominance frontiers
 * 5. Rename variables using dominator tree traversal
 */
class SsaBuilder(
    private val instrs: List<IrInstr>,
    private val constants: List<Value>,
    private val arity: Int = 0
) {
    // CFG built from IR
    private val cfg: ControlFlowGraph = ControlFlowGraph.build(instrs)

    // Dominance frontier computation
    private val domFrontier: DominanceFrontier = DominanceFrontier.compute(cfg)

    // SSA blocks being built
    private val ssaBlocks: MutableList<SsaBlock> = mutableListOf()

    // Map from IR block ID to SSA block
    private val blockMap: MutableMap<Int, SsaBlock> = mutableMapOf()

    // Set of registers that are defined (need phi functions)
    private val globalRegs: MutableSet<Int> = mutableSetOf()

    // Blocks where each register is defined
    private val defBlocks: MutableMap<Int, MutableSet<Int>> = mutableMapOf()

    /**
     * Build SSA form.
     */
    fun build(): SsaFunction {
        if (cfg.blocks.isEmpty()) {
            return SsaFunction(emptyList(), constants, 0, emptySet(), cfg)
        }

        // Step 1: Create SSA blocks with empty phi lists
        createSsaBlocks()

        // Step 2: Find global registers and their definition blocks
        analyzeDefinitions()

        // Step 3: Place phi functions
        placePhiFunctions()

        // Step 4: Convert instructions (with placeholder registers)
        convertInstructions()

        // Step 5: Rename variables
        val renamer = SsaRenamer(ssaBlocks, cfg, domFrontier, globalRegs)
        renamer.rename()

        // Find entry and exit blocks
        val entryBlock = cfg.entryBlock
        val exitBlocks = cfg.exitBlocks

        return SsaFunction(ssaBlocks, constants, entryBlock, exitBlocks, cfg, arity)
    }

    /**
     * Create SSA blocks corresponding to IR blocks.
     */
    private fun createSsaBlocks() {
        for (block in cfg.blocks) {
            val ssaBlock = SsaBlock(block.id, block.label)
            ssaBlocks.add(ssaBlock)
            blockMap[block.id] = ssaBlock
        }

        // Set up predecessors and successors
        for (block in cfg.blocks) {
            val ssaBlock = blockMap[block.id]!!
            ssaBlock.predecessors.addAll(block.predecessors)
            ssaBlock.successors.addAll(block.successors)
        }
    }

    /**
     * Analyze which registers are defined in which blocks.
     * A register is "global" if it is defined in multiple blocks or
     * used in a block different from where it's defined.
     */
    private fun analyzeDefinitions() {
        // Track where each register is defined
        for (block in cfg.blocks) {
            for (instr in block.instrs) {
                val defReg = getDefinedReg(instr)
                if (defReg != null) {
                    globalRegs.add(defReg)
                    defBlocks.getOrPut(defReg) { mutableSetOf() }.add(block.id)
                }
            }
        }

        // Also track which registers are used across blocks
        // For phi placement, we need registers defined in multiple blocks
        // or that cross block boundaries
        val usedInBlock = mutableMapOf<Int, MutableSet<Int>>()

        for (block in cfg.blocks) {
            val used = mutableSetOf<Int>()
            for (instr in block.instrs) {
                used.addAll(getUsedRegs(instr))
            }
            usedInBlock[block.id] = used
        }

        // A register is global if it's used in a different block than where it's defined
        // or defined in multiple blocks
        for (reg in globalRegs.toList()) {
            val defBlockSet = defBlocks[reg] ?: emptySet()
            if (defBlockSet.size <= 1) {
                // Check if used in a different block
                val defBlockId = defBlockSet.firstOrNull()
                var isGlobal = false
                for ((blockId, used) in usedInBlock) {
                    if (reg in used && blockId != defBlockId) {
                        isGlobal = true
                        break
                    }
                }
                if (!isGlobal) {
                    globalRegs.remove(reg)
                }
            }
        }
    }

    /**
     * Place phi functions at iterated dominance frontiers for each global register.
     */
    private fun placePhiFunctions() {
        for (reg in globalRegs) {
            val defBlockSet = defBlocks[reg] ?: emptySet()
            val idf = domFrontier.iteratedFrontier(defBlockSet)

            for (blockId in idf) {
                val ssaBlock = blockMap[blockId] ?: continue
                // Only add phi if block has multiple predecessors
                if (ssaBlock.predecessors.size >= 2) {
                    // Create phi with placeholder operands (will be filled during renaming)
                    val phi = PhiFunction(
                        result = SsaValue(reg, 0),  // Placeholder version
                        operands = ssaBlock.predecessors.associateWith { SsaValue.UNDEFINED }
                    )
                    ssaBlock.phiFunctions.add(phi)
                }
            }
        }
    }

    /**
     * Convert IR instructions to SSA instructions with placeholder registers.
     * The actual versioning is done by the renamer.
     */
    private fun convertInstructions() {
        for (block in cfg.blocks) {
            val ssaBlock = blockMap[block.id] ?: continue

            for (instr in block.instrs) {
                val ssaInstr = convertInstr(instr)
                if (ssaInstr != null) {
                    ssaBlock.instrs.add(ssaInstr)
                }
            }
        }
    }

    /**
     * Convert a single IR instruction to SSA form.
     */
    private fun convertInstr(instr: IrInstr): SsaInstr? = when (instr) {
        is IrInstr.LoadImm -> SsaInstr.LoadImm(SsaValue(instr.dst, 0), instr.index)
        is IrInstr.LoadGlobal -> SsaInstr.LoadGlobal(SsaValue(instr.dst, 0), instr.name)
        is IrInstr.StoreGlobal -> SsaInstr.StoreGlobal(instr.name, SsaValue(instr.src, 0))
        is IrInstr.BinaryOp -> SsaInstr.BinaryOp(
            SsaValue(instr.dst, 0), instr.op,
            SsaValue(instr.src1, 0), SsaValue(instr.src2, 0)
        )
        is IrInstr.UnaryOp -> SsaInstr.UnaryOp(SsaValue(instr.dst, 0), instr.op, SsaValue(instr.src, 0))
        is IrInstr.Jump -> SsaInstr.Jump(instr.target)
        is IrInstr.JumpIfFalse -> SsaInstr.JumpIfFalse(SsaValue(instr.src, 0), instr.target)
        is IrInstr.Label -> SsaInstr.Label(instr.label)
        is IrInstr.LoadFunc -> {
            // Keep nested functions in IR form (they'll be converted when executed)
            SsaInstr.LoadFunc(
                SsaValue(instr.dst, 0), instr.name, instr.arity,
                instr.instrs, instr.constants, instr.defaultValues
            )
        }
        is IrInstr.Call -> SsaInstr.Call(
            SsaValue(instr.dst, 0), SsaValue(instr.func, 0),
            instr.args.map { SsaValue(it, 0) }
        )
        is IrInstr.Return -> SsaInstr.Return(SsaValue(instr.src, 0))
        is IrInstr.Move -> SsaInstr.Move(SsaValue(instr.dst, 0), SsaValue(instr.src, 0))
        is IrInstr.GetIndex -> SsaInstr.GetIndex(
            SsaValue(instr.dst, 0), SsaValue(instr.obj, 0), SsaValue(instr.index, 0)
        )
        is IrInstr.SetIndex -> SsaInstr.SetIndex(
            SsaValue(instr.obj, 0), SsaValue(instr.index, 0), SsaValue(instr.src, 0)
        )
        is IrInstr.NewArray -> SsaInstr.NewArray(
            SsaValue(instr.dst, 0), instr.elements.map { SsaValue(it, 0) }
        )
        is IrInstr.GetField -> SsaInstr.GetField(
            SsaValue(instr.dst, 0), SsaValue(instr.obj, 0), instr.name
        )
        is IrInstr.SetField -> SsaInstr.SetField(
            SsaValue(instr.obj, 0), instr.name, SsaValue(instr.src, 0)
        )
        is IrInstr.NewInstance -> SsaInstr.NewInstance(
            SsaValue(instr.dst, 0), SsaValue(instr.classReg, 0),
            instr.args.map { SsaValue(it, 0) }
        )
        is IrInstr.IsType -> SsaInstr.IsType(
            SsaValue(instr.dst, 0), SsaValue(instr.src, 0), instr.typeName
        )
        is IrInstr.HasCheck -> error("HasCheck not yet implemented")
        is IrInstr.LoadClass -> SsaInstr.LoadClass(
            SsaValue(instr.dst, 0), instr.name, instr.superClass, instr.methods
        )
        is IrInstr.Break -> SsaInstr.Break
        is IrInstr.Next -> SsaInstr.Next
        is IrInstr.Spill   -> null  // Not converted to SSA
        is IrInstr.Unspill -> null  // Not converted to SSA
        is IrInstr.Throw -> null
        is IrInstr.RegisterEventHandler -> null
        is IrInstr.InvokeEventHandler -> null
        is IrInstr.GetUpvalue -> SsaInstr.Move(SsaValue(instr.dst, 0), SsaValue(0, 0))  // dst defined, no src register
        is IrInstr.AwaitInstr -> null  // implemented in VM
        is IrInstr.SpawnInstr -> null  // implemented in VM
        is IrInstr.AsyncCallInstr -> null  // implemented in VM
        is IrInstr.CallHandler -> SsaInstr.CallHandler(instr.handlerName, instr.cst)
    }

    /**
     * Get the register defined by an instruction, if any.
     */
    private fun getDefinedReg(instr: IrInstr): Int? = when (instr) {
        is IrInstr.LoadImm -> instr.dst
        is IrInstr.LoadGlobal -> instr.dst
        is IrInstr.BinaryOp -> instr.dst
        is IrInstr.UnaryOp -> instr.dst
        is IrInstr.LoadFunc -> instr.dst
        is IrInstr.Call -> instr.dst
        is IrInstr.Move -> instr.dst
        is IrInstr.GetIndex -> instr.dst
        is IrInstr.NewArray -> instr.dst
        is IrInstr.GetField -> instr.dst
        is IrInstr.NewInstance -> instr.dst
        is IrInstr.IsType -> instr.dst
        is IrInstr.HasCheck -> instr.dst
        is IrInstr.LoadClass -> instr.dst
        is IrInstr.GetUpvalue -> instr.dst
        else -> null
    }

    /**
     * Get all registers used by an instruction.
     */
    private fun getUsedRegs(instr: IrInstr): List<Int> = when (instr) {
        is IrInstr.BinaryOp -> listOf(instr.src1, instr.src2)
        is IrInstr.UnaryOp -> listOf(instr.src)
        is IrInstr.Call -> listOf(instr.func) + instr.args
        is IrInstr.Return -> listOf(instr.src)
        is IrInstr.JumpIfFalse -> listOf(instr.src)
        is IrInstr.StoreGlobal -> listOf(instr.src)
        is IrInstr.Move -> listOf(instr.src)
        is IrInstr.NewArray -> instr.elements
        is IrInstr.GetIndex -> listOf(instr.obj, instr.index)
        is IrInstr.SetIndex -> listOf(instr.obj, instr.index, instr.src)
        is IrInstr.GetField -> listOf(instr.obj)
        is IrInstr.SetField -> listOf(instr.obj, instr.src)
        is IrInstr.NewInstance -> listOf(instr.classReg) + instr.args
        is IrInstr.IsType -> listOf(instr.src)
        is IrInstr.HasCheck -> listOf(instr.obj)
        else -> emptyList()
    }

    companion object {
        /**
         * Build SSA form from IR instructions.
         */
        fun build(instrs: List<IrInstr>, constants: List<Value>, arity: Int = 0): SsaFunction {
            return SsaBuilder(instrs, constants, arity).build()
        }
    }
}
