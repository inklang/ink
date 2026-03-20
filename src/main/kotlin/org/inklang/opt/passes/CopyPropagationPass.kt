package org.inklang.opt.passes

import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.IrInstr
import org.inklang.lang.Value
import org.inklang.opt.OptPass
import org.inklang.opt.OptResult

/**
 * Copy propagation pass.
 * If a register is copied (Move r2, r1), replace uses of r2 with r1
 * until r1 is redefined.
 *
 * Example:
 *   LoadImm r0, 5
 *   Move r1, r0        ; r1 = r0
 *   BinaryOp r2, PLUS, r1, r0   ; use r1
 *
 * Becomes:
 *   LoadImm r0, 5
 *   Move r1, r0        ; might be removed by DCE later
 *   BinaryOp r2, PLUS, r0, r0   ; r1 replaced with r0
 */
class CopyPropagationPass : OptPass {
    override val name = "CopyPropagation"

    override fun run(
        instrs: List<IrInstr>,
        cfg: ControlFlowGraph,
        constants: List<Value>
    ): OptResult {
        // Track copy relationships: dst -> src
        val copies = mutableMapOf<Int, Int>()
        var changed = false
        val newInstrs = mutableListOf<IrInstr>()

        // Track which registers have been redefined (invalidates copies)
        val invalidated = mutableSetOf<Int>()

        for (instr in instrs) {
            // Invalidate copies when source is redefined
            when (instr) {
                is IrInstr.LoadImm -> invalidated.add(instr.dst)
                is IrInstr.LoadGlobal -> invalidated.add(instr.dst)
                is IrInstr.LoadFunc -> invalidated.add(instr.dst)
                is IrInstr.BinaryOp -> invalidated.add(instr.dst)
                is IrInstr.UnaryOp -> invalidated.add(instr.dst)
                is IrInstr.Call -> invalidated.add(instr.dst)
                is IrInstr.NewArray -> invalidated.add(instr.dst)
                is IrInstr.GetIndex -> invalidated.add(instr.dst)
                is IrInstr.GetField -> invalidated.add(instr.dst)
                is IrInstr.NewInstance -> invalidated.add(instr.dst)
                is IrInstr.IsType -> invalidated.add(instr.dst)
                is IrInstr.LoadClass -> invalidated.add(instr.dst)
                is IrInstr.Move -> invalidated.add(instr.dst)
                else -> {}
            }

            // Remove invalidated copies
            copies.keys.retainAll { it !in invalidated }

            // Process instruction with copy propagation
            when (instr) {
                is IrInstr.BinaryOp -> {
                    val newSrc1 = propagateReg(instr.src1, copies)
                    val newSrc2 = propagateReg(instr.src2, copies)
                    if (newSrc1 != instr.src1 || newSrc2 != instr.src2) {
                        changed = true
                    }
                    newInstrs.add(IrInstr.BinaryOp(instr.dst, instr.op, newSrc1, newSrc2))
                }
                is IrInstr.UnaryOp -> {
                    val newSrc = propagateReg(instr.src, copies)
                    if (newSrc != instr.src) changed = true
                    newInstrs.add(IrInstr.UnaryOp(instr.dst, instr.op, newSrc))
                }
                is IrInstr.Call -> {
                    val newFunc = propagateReg(instr.func, copies)
                    val newArgs = instr.args.map { propagateReg(it, copies) }
                    if (newFunc != instr.func || newArgs != instr.args) changed = true
                    newInstrs.add(IrInstr.Call(instr.dst, newFunc, newArgs))
                }
                is IrInstr.Return -> {
                    val newSrc = propagateReg(instr.src, copies)
                    if (newSrc != instr.src) changed = true
                    newInstrs.add(IrInstr.Return(newSrc))
                }
                is IrInstr.JumpIfFalse -> {
                    val newSrc = propagateReg(instr.src, copies)
                    if (newSrc != instr.src) changed = true
                    newInstrs.add(IrInstr.JumpIfFalse(newSrc, instr.target))
                }
                is IrInstr.StoreGlobal -> {
                    val newSrc = propagateReg(instr.src, copies)
                    if (newSrc != instr.src) changed = true
                    newInstrs.add(IrInstr.StoreGlobal(instr.name, newSrc))
                }
                is IrInstr.Move -> {
                    val newSrc = propagateReg(instr.src, copies)
                    if (newSrc != instr.src) changed = true
                    // Record this copy relationship
                    copies[instr.dst] = newSrc
                    newInstrs.add(IrInstr.Move(instr.dst, newSrc))
                }
                is IrInstr.NewArray -> {
                    val newElements = instr.elements.map { propagateReg(it, copies) }
                    if (newElements != instr.elements) changed = true
                    newInstrs.add(IrInstr.NewArray(instr.dst, newElements))
                }
                is IrInstr.GetIndex -> {
                    val newObj = propagateReg(instr.obj, copies)
                    val newIndex = propagateReg(instr.index, copies)
                    if (newObj != instr.obj || newIndex != instr.index) changed = true
                    newInstrs.add(IrInstr.GetIndex(instr.dst, newObj, newIndex))
                }
                is IrInstr.SetIndex -> {
                    val newObj = propagateReg(instr.obj, copies)
                    val newIndex = propagateReg(instr.index, copies)
                    val newSrc = propagateReg(instr.src, copies)
                    if (newObj != instr.obj || newIndex != instr.index || newSrc != instr.src) {
                        changed = true
                    }
                    newInstrs.add(IrInstr.SetIndex(newObj, newIndex, newSrc))
                }
                is IrInstr.GetField -> {
                    val newObj = propagateReg(instr.obj, copies)
                    if (newObj != instr.obj) changed = true
                    newInstrs.add(IrInstr.GetField(instr.dst, newObj, instr.name))
                }
                is IrInstr.SetField -> {
                    val newObj = propagateReg(instr.obj, copies)
                    val newSrc = propagateReg(instr.src, copies)
                    if (newObj != instr.obj || newSrc != instr.src) changed = true
                    newInstrs.add(IrInstr.SetField(newObj, instr.name, newSrc))
                }
                is IrInstr.NewInstance -> {
                    val newClassReg = propagateReg(instr.classReg, copies)
                    val newArgs = instr.args.map { propagateReg(it, copies) }
                    if (newClassReg != instr.classReg || newArgs != instr.args) changed = true
                    newInstrs.add(IrInstr.NewInstance(instr.dst, newClassReg, newArgs))
                }
                is IrInstr.IsType -> {
                    val newSrc = propagateReg(instr.src, copies)
                    if (newSrc != instr.src) changed = true
                    newInstrs.add(IrInstr.IsType(instr.dst, newSrc, instr.typeName))
                }
                else -> newInstrs.add(instr)
            }

            // Clear invalidation set after each instruction
            invalidated.clear()
        }

        return OptResult(newInstrs, constants, changed)
    }

    private fun propagateReg(reg: Int, copies: Map<Int, Int>): Int {
        // Follow copy chain (but avoid infinite loops)
        var current = reg
        var visited = mutableSetOf<Int>()
        while (current in copies && current !in visited) {
            visited.add(current)
            current = copies[current]!!
        }
        return current
    }
}
