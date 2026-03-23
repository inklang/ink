package org.inklang.lang

sealed class IrInstr {
    data class LoadImm(val dst: Int, val index: Int) : IrInstr()
    data class LoadGlobal(val dst: Int, val name: String) : IrInstr()
    data class StoreGlobal(val name: String, val src: Int) : IrInstr()
    data class BinaryOp(val dst: Int, val op: TokenType, val src1: Int, val src2: Int) : IrInstr()
    data class UnaryOp(val dst: Int, val op: TokenType, val src: Int) : IrInstr()
    data class Jump(val target: IrLabel) : IrInstr()
    data class JumpIfFalse(val src: Int, val target: IrLabel) : IrInstr()
    data class Label(val label: IrLabel) : IrInstr()
    data class LoadFunc(
        val dst: Int,
        val name: String,
        val arity: Int,
        val instrs: List<IrInstr>,
        val constants: List<Value>,
        val defaultValues: List<DefaultValueInfo?> = emptyList()  // Default value IR for each param
    ) : IrInstr()
    data class Call(val dst: Int, val func: Int, val args: List<Int>) : IrInstr()
    data class Return(val src: Int) : IrInstr()
    data class Move(val dst: Int, val src: Int): IrInstr()
    data class GetIndex(val dst: Int, val obj: Int, val index: Int): IrInstr()
    data class SetIndex(val obj: Int, val index: Int, val src: Int): IrInstr()
    data class NewArray(val dst: Int, val elements: List<Int>): IrInstr()
    data class GetField(val dst: Int, val obj: Int, val name: String) : IrInstr()
    data class SetField(val obj: Int, val name: String, val src: Int) : IrInstr()
    data class NewInstance(val dst: Int, val classReg: Int, val args: List<Int>) : IrInstr()
    data class IsType(val dst: Int, val src: Int, val typeName: String) : IrInstr()
    data class HasCheck(val dst: Int, val obj: Int, val fieldName: String) : IrInstr()  // new — fieldName is a compile-time string constant
    data class LoadClass(
        val dst: Int,
        val name: String,
        val superClass: String?,  // name of superclass, resolved at runtime from globals
        val methods: Map<String, MethodInfo>  // methodName -> method info
    ) : IrInstr()
    object Break : IrInstr()
    object Next : IrInstr()
    data class Spill(val slot: Int, val src: Int) : IrInstr()    // spills[slot] = regs[src]
    data class Unspill(val dst: Int, val slot: Int) : IrInstr()  // regs[dst] = spills[slot]
    data class CallHandler(val handlerName: String, val cst: org.inklang.grammar.CstNode) : IrInstr()
}

data class MethodInfo(
    val arity: Int,  // includes implicit self parameter
    val instrs: List<IrInstr>,
    val constants: List<Value>,
    val defaultValues: List<DefaultValueInfo?> = emptyList()  // One per param, null if no default
)

/**
 * Represents a default value expression for a parameter.
 * The instrs and constants represent the lowered expression that computes the default value.
 * This is executed at call time if the argument is not provided.
 */
data class DefaultValueInfo(
    val instrs: List<IrInstr>,
    val constants: List<Value>
)

data class IrLabel(val id: Int)