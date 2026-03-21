package org.inklang.ssa

/**
 * Represents a versioned SSA register.
 * In SSA form, each register has a base number and a version.
 * For example, r0.0 is the first definition of r0, r0.1 is the second, etc.
 */
data class SsaValue(val baseReg: Int, val version: Int) {
    override fun toString(): String = "r$baseReg.$version"

    companion object {
        /**
         * Represents an undefined/placeholder SSA value.
         * Used during phi placement before renaming.
         */
        val UNDEFINED = SsaValue(-1, -1)
    }
}
