package org.inklang.lang

/*
| bits 0-7  | bits 8-11  | bits 12-15 | bits 16-19 | bits 20-31 |
| opcode    | dst (4-bit)| src1(4-bit)| src2(4-bit)| immediate  |
*/

data class ClassInfo(
    val name: String,
    val superClass: String?,  // name of superclass, resolved at runtime
    val methods: Map<String, Int>  // methodName -> function index in chunk.functions
)

/**
 * Stores information about a function's default parameter values.
 * Each element is either null (no default) or a ChunkIndex pointing to a chunk that computes the default.
 */
data class FunctionDefaults(
    val defaultChunks: List<Int?>  // Index into chunk.functions for each param's default chunk, or null
)

class Chunk {
    val code = mutableListOf<Int>()
    val constants = mutableListOf<Value>()
    val strings = mutableListOf<String>()
    val functions = mutableListOf<Chunk>()
    val classes = mutableListOf<ClassInfo>()
    val functionDefaults = mutableListOf<FunctionDefaults>()  // Parallel to functions - defaults for each function
    val cstTable = mutableListOf<org.inklang.grammar.CstNode>()  // CST nodes for plugin handler dispatch
    var spillSlotCount: Int = 0
    val labelOffsets = mutableMapOf<Int, Int>()  // IrLabel.id -> bytecode offset

    fun addConstant(value: Value): Int {
        constants.add(value)
        return constants.lastIndex
    }

    fun addString(string: String): Int {
        val existing = strings.indexOf(string)
        if (existing != -1) return existing
        strings.add(string)
        return strings.lastIndex
    }

    fun write(opcode: OpCode, dst: Int = 0, src1: Int = 0, src2: Int = 0, imm: Int = 0) {
        val word = (opcode.code.toInt() and 0xFF) or
                ((dst  and 0x0F) shl 8)  or
                ((src1 and 0x0F) shl 12) or
                ((src2 and 0x0F) shl 16) or
                ((imm  and 0xFFF) shl 20)
        code.add(word)
    }

    fun disassemble() {
        println("Constants: $constants")
        println("Strings: $strings")
        code.forEachIndexed { idx, word ->
            val opcode = word and 0xFF
            val dst    = (word shr 8)  and 0x0F
            val src1   = (word shr 12) and 0x0F
            val src2   = (word shr 16) and 0x0F
            val imm    = (word shr 20) and 0xFFF
            println("$idx: word=$word opcode=$opcode dst=r$dst src1=r$src1 src2=r$src2 imm=$imm")
        }
    }
}