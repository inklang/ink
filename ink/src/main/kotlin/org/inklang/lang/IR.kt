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
        val defaultValues: List<DefaultValueInfo?> = emptyList(),  // Default value IR for each param
        val capturedVars: List<String> = emptyList(),  // Names of captured variables (closures)
        val upvalueRegs: List<Int> = emptyList()  // Register indices of captured variables in enclosing scope
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
    data class Throw(val src: Int) : IrInstr()
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
    data class GetUpvalue(val dst: Int, val upvalueIndex: Int) : IrInstr()  // Load captured variable
    // Register an event handler with the runtime event bus
    data class RegisterEventHandler(
        val eventName: String,
        val handlerFuncIndex: Int,
        val eventParamName: String,
        val dataParamNames: List<String>
    ) : IrInstr()
    // Invoke a registered handler (used at runtime when events fire)
    data class InvokeEventHandler(
        val eventName: String,
        val handlerIndex: Int,
        val eventObjectReg: Int,
        val dataArgRegs: List<Int>
    ) : IrInstr()
    // Await a task - suspend until complete, store result in dst
    data class AwaitInstr(val dst: Int, val task: Int) : IrInstr()
    // Spawn a function on a thread pool - store Task in dst
    data class SpawnInstr(val dst: Int, val func: Int, val args: List<Int>, val virtual: Boolean) : IrInstr()
    // Async call - launch async function, store Task in dst
    data class AsyncCallInstr(val dst: Int, val func: Int, val args: List<Int>) : IrInstr()
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