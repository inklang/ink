package org.inklang.lang

enum class OpCode(val code: Byte) {
    // stack
    LOAD_IMM(0x00),
    POP(0x01),

    // locals/globals
    LOAD_GLOBAL(0x05),
    STORE_GLOBAL(0x06),

    // move
    MOVE(0x07),

    // arithmetic
    ADD(0x08),
    SUB(0x09),
    MUL(0x0A),
    DIV(0x0B),
    NEG(0x0C),

    // logic
    NOT(0x0D),
    EQ(0x0E),
    NEQ(0x0F),
    LT(0x10),
    LTE(0x11),
    GT(0x12),
    GTE(0x13),

    // control flow
    JUMP(0x14),
    JUMP_IF_FALSE(0x15),

    // functions
    LOAD_FUNC(0x16),
    CALL(0x17),
    RETURN(0x18),

    // loop
    BREAK(0x19),
    NEXT(0x1A),
    MOD(0x1B),
    PUSH_ARG(0x1C),
    GET_FIELD(0x1D),   // dst = src1.field_name_idx
    SET_FIELD(0x1E),   // src1.field_name_idx = src2
    NEW_INSTANCE(0x1F), // dst = new instance of class in src1, argc in imm
    IS_TYPE(0x20),
    NEW_ARRAY(0x21),
    GET_INDEX(0x22),
    SET_INDEX(0x23),
    RANGE(0x24),
    BUILD_CLASS(0x25),  // dst = BUILD_CLASS name_idx, superclass_idx, method_count; followed by method entries
    SPILL(0x26),    // imm=slot, src1=physical_reg
    UNSPILL(0x27),  // dst=physical_reg, imm=slot
    POW(0x28),      // dst = src1 ^ src2
    HAS(0x29),      // dst = obj.has(field) — true if field exists
    THROW(0x2A),    // throw value in src1
    REGISTER_EVENT(0x2B),
}
