package org.inklang.ssa

import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.Value

/**
 * Represents a function in SSA form.
 * Contains SSA blocks, constants, and metadata.
 */
class SsaFunction(
    val blocks: List<SsaBlock>,
    val constants: List<Value>,
    val entryBlock: Int,
    val exitBlocks: Set<Int>,
    val cfg: ControlFlowGraph,  // Reference to original CFG for dominator info
    val arity: Int = 0  // Number of function parameters (registers 0..arity-1)
) {
    private val blockMap: Map<Int, SsaBlock> = blocks.associateBy { it.id }

    /**
     * Get an SSA block by its ID.
     */
    fun getBlock(id: Int): SsaBlock? = blockMap[id]

    /**
     * Get all phi functions in the function.
     */
    fun allPhiFunctions(): List<Pair<Int, PhiFunction>> {
        return blocks.flatMap { block ->
            block.phiFunctions.map { phi -> Pair(block.id, phi) }
        }
    }

    /**
     * Build a def-use map: for each SsaValue, list all instructions that use it.
     */
    fun buildUseMap(): Map<SsaValue, MutableList<SsaInstr>> {
        val useMap = mutableMapOf<SsaValue, MutableList<SsaInstr>>()

        for (block in blocks) {
            // Phi uses
            for (phi in block.phiFunctions) {
                for (used in phi.usedValues) {
                    useMap.getOrPut(used) { mutableListOf() }
                }
            }
            // Regular instruction uses
            for (instr in block.instrs) {
                for (used in instr.usedValues) {
                    useMap.getOrPut(used) { mutableListOf() }.add(instr)
                }
            }
        }

        return useMap
    }

    /**
     * Build a def map: for each SsaValue, find the instruction/block that defines it.
     */
    fun buildDefMap(): Map<SsaValue, Pair<Int, SsaInstr?>> {
        val defMap = mutableMapOf<SsaValue, Pair<Int, SsaInstr?>>()

        for (block in blocks) {
            // Phi definitions
            for (phi in block.phiFunctions) {
                defMap[phi.result] = Pair(block.id, null)  // Phis don't have a single defining instruction
            }
            // Regular instruction definitions
            for (instr in block.instrs) {
                instr.definedValue?.let { defMap[it] = Pair(block.id, instr) }
            }
        }

        return defMap
    }

    /**
     * Dump the SSA function for debugging.
     */
    fun dump(): String {
        val sb = StringBuilder()
        sb.appendLine("SSA Function:")
        sb.appendLine("  Entry: Block$entryBlock")
        sb.appendLine("  Exits: ${exitBlocks.map { "Block$it" }}")
        sb.appendLine()

        for (block in blocks) {
            sb.appendLine("  Block${block.id}:")
            if (block.label != null) {
                sb.appendLine("    Label: L${block.label.id}")
            }
            sb.appendLine("    Predecessors: ${block.predecessors.toList().sorted()}")
            sb.appendLine("    Successors: ${block.successors.toList().sorted()}")

            if (block.phiFunctions.isNotEmpty()) {
                sb.appendLine("    Phi Functions:")
                for (phi in block.phiFunctions) {
                    sb.appendLine("      $phi")
                }
            }

            sb.appendLine("    Instructions:")
            for (instr in block.instrs) {
                sb.appendLine("      ${formatInstr(instr)}")
            }
        }

        return sb.toString()
    }

    private fun formatInstr(instr: SsaInstr): String = when (instr) {
        is SsaInstr.LoadImm -> "${instr.definedValue} = LoadImm #${instr.constIndex}"
        is SsaInstr.LoadGlobal -> "${instr.definedValue} = LoadGlobal ${instr.name}"
        is SsaInstr.StoreGlobal -> "StoreGlobal ${instr.name}, ${instr.src}"
        is SsaInstr.BinaryOp -> "${instr.definedValue} = ${instr.src1} ${instr.op} ${instr.src2}"
        is SsaInstr.UnaryOp -> "${instr.definedValue} = ${instr.op} ${instr.src}"
        is SsaInstr.Jump -> "Jump L${instr.target.id}"
        is SsaInstr.JumpIfFalse -> "JumpIfFalse ${instr.src}, L${instr.target.id}"
        is SsaInstr.Label -> "Label L${instr.label.id}"
        is SsaInstr.LoadFunc -> "${instr.definedValue} = LoadFunc ${instr.name}/${instr.arity}"
        is SsaInstr.Call -> "${instr.definedValue} = Call ${instr.func}(${instr.args.joinToString(", ")})"
        is SsaInstr.Return -> "Return ${instr.src}"
        is SsaInstr.Move -> "${instr.definedValue} = Move ${instr.src}"
        is SsaInstr.GetIndex -> "${instr.definedValue} = ${instr.obj}[${instr.index}]"
        is SsaInstr.SetIndex -> "${instr.obj}[${instr.index}] = ${instr.src}"
        is SsaInstr.NewArray -> "${instr.definedValue} = NewArray [${instr.elements.joinToString(", ")}]"
        is SsaInstr.GetField -> "${instr.definedValue} = ${instr.obj}.${instr.name}"
        is SsaInstr.SetField -> "${instr.obj}.${instr.name} = ${instr.src}"
        is SsaInstr.NewInstance -> "${instr.definedValue} = NewInstance ${instr.classReg}(${instr.args.joinToString(", ")})"
        is SsaInstr.IsType -> "${instr.definedValue} = ${instr.src} is ${instr.typeName}"
        is SsaInstr.LoadClass -> "${instr.definedValue} = LoadClass ${instr.name}"
        is SsaInstr.Break -> "Break"
        is SsaInstr.Next -> "Next"
        is SsaInstr.CallHandler -> "CallHandler ${instr.handlerName}"
    }
}
