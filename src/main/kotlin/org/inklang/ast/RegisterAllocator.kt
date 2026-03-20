package org.inklang.ast

class RegisterAllocator(private val numRegs: Int = 16) {

    data class AllocResult(
        val allocation: Map<Int, Int>,  // virtual → physical (non-spilled)
        val spills: Map<Int, Int>,      // virtual → spill slot index
        val spillSlotCount: Int
    )

    fun allocate(ranges: Map<Int, LiveRange>, numParams: Int = 0): AllocResult {
        val allocation = mutableMapOf<Int, Int>()
        val spills = mutableMapOf<Int, Int>()
        var spillSlot = 0

        for (i in 0 until numParams) {
            allocation[i] = i
        }

        val sorted = ranges.values.sortedBy { it.start }
        val active = mutableMapOf<Int, LiveRange>()
        val freeRegs = ArrayDeque((numParams until numRegs).toList())

        for (i in 0 until numParams) {
            ranges[i]?.let { active[i] = it }
        }

        for (range in sorted) {
            if (range.reg < numParams) continue

            val expired = active.entries.filter { (_, r) -> r.end < range.start }
            for ((physReg, _) in expired) {
                if (physReg >= numParams) {
                    active.remove(physReg)
                    freeRegs.addFirst(physReg)
                }
            }

            if (freeRegs.isEmpty()) {
                val spillCandidate = active.entries
                    .filter { it.key >= numParams }
                    .maxByOrNull { (_, r) -> r.end }

                if (spillCandidate != null) {
                    val physReg = spillCandidate.key
                    active.remove(physReg)
                    spills[spillCandidate.value.reg] = spillSlot++
                    allocation[range.reg] = physReg
                    active[physReg] = range
                } else {
                    error("RegisterAllocator: no spill candidate and no free registers — this should be unreachable")
                }
            } else {
                val physReg = freeRegs.removeFirst()
                allocation[range.reg] = physReg
                active[physReg] = range
            }
        }

        return AllocResult(allocation, spills, spillSlot)
    }
}