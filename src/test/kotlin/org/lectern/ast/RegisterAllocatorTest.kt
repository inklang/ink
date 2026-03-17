package org.lectern.ast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegisterAllocatorTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a LiveRange map from varargs of (virtualReg, start, end) triples. */
    private fun ranges(vararg entries: Triple<Int, Int, Int>): Map<Int, LiveRange> =
        entries.associate { (reg, start, end) -> reg to LiveRange(reg, start, end) }

    // -------------------------------------------------------------------------
    // 1. Simple allocation — non-overlapping ranges all fit without spilling
    // -------------------------------------------------------------------------

    @Test
    fun `simple allocation three non-overlapping ranges get distinct physical regs`() {
        // r0: [0,1], r1: [2,3], r2: [4,5] — none overlap
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 1),
                Triple(1, 2, 3),
                Triple(2, 4, 5)
            )
        )

        // All three must be allocated (no spills)
        assertTrue(result.spills.isEmpty(), "Expected no spills for non-overlapping ranges")

        // Physical registers may be reused since ranges don't overlap
        val physRegs = result.allocation.values.toList()
        assertTrue(physRegs.size == 3, "All 3 virtual registers should be allocated")

        // Every virtual register must be present in the allocation
        assertTrue(result.allocation.containsKey(0))
        assertTrue(result.allocation.containsKey(1))
        assertTrue(result.allocation.containsKey(2))
    }

    // -------------------------------------------------------------------------
    // 2. Overlapping ranges — all live at the same time get distinct physical regs
    // -------------------------------------------------------------------------

    @Test
    fun `overlapping ranges three simultaneous live ranges get distinct physical regs`() {
        // r0, r1, r2 are all live over [0,5]
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 5),
                Triple(1, 0, 5),
                Triple(2, 0, 5)
            )
        )

        // All three must be in allocation
        assertEquals(3, result.allocation.size, "Expected all 3 virtual regs allocated")
        assertTrue(result.spills.isEmpty(), "Expected no spills with 16 regs available")

        // Physical registers must all differ
        val physRegs = result.allocation.values.toList()
        assertEquals(3, physRegs.distinct().size, "Expected 3 distinct physical regs for overlapping ranges")
    }

    // -------------------------------------------------------------------------
    // 3. Register reuse — sequential ranges can share a physical register
    // -------------------------------------------------------------------------

    @Test
    fun `register reuse sequential ranges share same physical register`() {
        // r0: [0,1] ends before r1: [2,3] starts — they can share a physical reg
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 1),
                Triple(1, 2, 3)
            )
        )

        assertTrue(result.spills.isEmpty(), "Expected no spills")
        assertTrue(result.allocation.containsKey(0), "r0 must be allocated")
        assertTrue(result.allocation.containsKey(1), "r1 must be allocated")

        // r0 ends at 1, r1 starts at 2: the allocator should reuse the freed register
        assertEquals(
            result.allocation[0], result.allocation[1],
            "Sequential non-overlapping ranges should reuse the same physical register"
        )
    }

    @Test
    fun `register reuse three sequential ranges all reuse same physical register`() {
        // r0: [0,0], r1: [1,1], r2: [2,2] — each ends before the next starts
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 0),
                Triple(1, 1, 1),
                Triple(2, 2, 2)
            )
        )

        assertTrue(result.spills.isEmpty(), "Expected no spills")
        // All three should map to the same physical register
        assertEquals(result.allocation[0], result.allocation[1], "r0 and r1 should share a physical reg")
        assertEquals(result.allocation[1], result.allocation[2], "r1 and r2 should share a physical reg")
    }

    // -------------------------------------------------------------------------
    // 4. Parameter pre-allocation
    // -------------------------------------------------------------------------

    @Test
    fun `parameters are pre-allocated to physical regs 0 and 1`() {
        // numParams=2: virtual 0 -> physical 0, virtual 1 -> physical 1
        // virtual 2 must land on physical 2 or higher
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 5),
                Triple(1, 0, 5),
                Triple(2, 0, 5)
            ),
            numParams = 2
        )

        assertEquals(0, result.allocation[0], "Parameter 0 must map to physical reg 0")
        assertEquals(1, result.allocation[1], "Parameter 1 must map to physical reg 1")
        assertTrue((result.allocation[2] ?: -1) >= 2, "Non-parameter virtual reg must not steal a param register")
    }

    @Test
    fun `single parameter pre-allocated to physical reg 0 non-param gets higher reg`() {
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 10),
                Triple(1, 0, 10)
            ),
            numParams = 1
        )

        assertEquals(0, result.allocation[0], "Parameter must be at physical reg 0")
        assertTrue((result.allocation[1] ?: -1) >= 1, "Non-parameter reg must be >= 1")
        assertTrue(result.allocation[0] != result.allocation[1], "Parameter and non-parameter must not share a physical reg")
    }

    @Test
    fun `parameters with no additional virtuals allocation is exactly the params`() {
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 3),
                Triple(1, 0, 3)
            ),
            numParams = 2
        )

        assertEquals(0, result.allocation[0])
        assertEquals(1, result.allocation[1])
        assertTrue(result.spills.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 5. Spilling — more simultaneous live ranges than registers causes spills
    // -------------------------------------------------------------------------

    @Test
    fun `spilling occurs when simultaneous live ranges exceed register count`() {
        // 17 virtual regs all live at [0,10] with only 16 physical regs
        val allocator = RegisterAllocator(numRegs = 16)
        val triples = (0 until 17).map { Triple(it, 0, 10) }.toTypedArray()
        val result = allocator.allocate(ranges(*triples))

        // At least one virtual reg must have been spilled
        assertTrue(
            result.spills.isNotEmpty(),
            "Expected at least one spill when 17 ranges compete for 16 registers"
        )

        // All 17 virtual regs should have an allocation (spilled ones keep their allocation too)
        assertEquals(17, result.allocation.size, "All 17 virtual regs must be in the allocation map")
    }

    @Test
    fun `spilled ranges get unique stack slots`() {
        val allocator = RegisterAllocator(numRegs = 4)
        // 6 overlapping ranges for 4 registers => 2 spills
        val triples = (0 until 6).map { Triple(it, 0, 10) }.toTypedArray()
        val result = allocator.allocate(ranges(*triples))

        val spillValues = result.spills.values.toList()
        assertEquals(
            spillValues.distinct().size, spillValues.size,
            "Each spilled virtual reg must get a unique stack slot"
        )
    }

    // -------------------------------------------------------------------------
    // 6. Small register file — RegisterAllocator(numRegs=4) with 5 overlapping ranges
    // -------------------------------------------------------------------------

    @Test
    fun `small register file four regs five overlapping ranges causes at least one spill`() {
        val allocator = RegisterAllocator(numRegs = 4)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 10),
                Triple(1, 0, 10),
                Triple(2, 0, 10),
                Triple(3, 0, 10),
                Triple(4, 0, 10)
            )
        )

        // 5 simultaneously live ranges into 4 registers → at least 1 spill
        assertTrue(
            result.spills.isNotEmpty(),
            "Expected at least 1 spill with 5 overlapping ranges and only 4 registers"
        )

        // Allocated physical registers must all be in [0, 3]
        for ((_, physReg) in result.allocation) {
            assertTrue(physReg in 0..3, "Physical reg $physReg is out of range for a 4-register file")
        }
    }

    @Test
    fun `small register file two regs three overlapping ranges causes spills`() {
        val allocator = RegisterAllocator(numRegs = 2)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 5),
                Triple(1, 0, 5),
                Triple(2, 0, 5)
            )
        )

        assertTrue(
            result.spills.isNotEmpty(),
            "Expected spills with 3 overlapping ranges and only 2 registers"
        )
    }

    // -------------------------------------------------------------------------
    // 7. Empty ranges — nothing to allocate
    // -------------------------------------------------------------------------

    @Test
    fun `empty ranges produces empty allocation`() {
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(emptyMap())

        assertTrue(result.allocation.isEmpty(), "Expected empty allocation for empty ranges")
        assertTrue(result.spills.isEmpty(), "Expected no spills for empty ranges")
    }

    @Test
    fun `empty ranges with numParams zero still empty`() {
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(emptyMap(), numParams = 0)

        assertTrue(result.allocation.isEmpty())
        assertTrue(result.spills.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 8. Single register — allocates to physical reg 0 (or numParams)
    // -------------------------------------------------------------------------

    @Test
    fun `single virtual register with no params allocates to physical reg 0`() {
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(ranges(Triple(0, 0, 5)))

        assertTrue(result.allocation.containsKey(0), "Virtual reg 0 must be in allocation")
        assertEquals(0, result.allocation[0], "Single virtual reg with no params must get physical reg 0")
        assertTrue(result.spills.isEmpty())
    }

    @Test
    fun `single non-param virtual register with numParams=1 allocates to physical reg 1`() {
        // virtual 0 is a parameter -> physical 0
        // virtual 1 is the only non-param -> should get physical 1
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 5),
                Triple(1, 0, 5)
            ),
            numParams = 1
        )

        assertEquals(0, result.allocation[0], "Param virtual reg 0 must map to physical reg 0")
        assertEquals(1, result.allocation[1], "First non-param virtual reg must map to physical reg 1 (numParams offset)")
        assertTrue(result.spills.isEmpty())
    }

    @Test
    fun `single virtual register with numParams=2 and no param ranges allocates to physical reg 2`() {
        // numParams=2 means physical 0 and 1 are reserved for params,
        // but no param ranges exist in the map. Virtual reg 2 is the only non-param.
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(Triple(2, 0, 5)),
            numParams = 2
        )

        assertTrue(result.allocation.containsKey(2), "Virtual reg 2 must be allocated")
        assertEquals(2, result.allocation[2], "With numParams=2, first free reg is physical 2")
        assertTrue(result.spills.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Additional edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `allocation map contains exactly one entry for each non-spilled virtual reg`() {
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 2),
                Triple(1, 1, 4),
                Triple(2, 3, 6),
                Triple(3, 5, 8)
            )
        )

        // With 16 regs and only 4 ranges, none should spill
        assertTrue(result.spills.isEmpty(), "No spills expected with 4 ranges and 16 registers")
        assertEquals(4, result.allocation.size, "All 4 virtual regs must appear in allocation")

        // All physical regs must be valid (within [0, 15])
        for ((_, physReg) in result.allocation) {
            assertTrue(physReg in 0..15, "Physical reg $physReg out of [0,15]")
        }
    }

    @Test
    fun `allocated physical registers for non-overlapping chain are all valid`() {
        // A chain: r0[0,1], r1[2,3], r2[4,5], r3[6,7], r4[8,9]
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 1),
                Triple(1, 2, 3),
                Triple(2, 4, 5),
                Triple(3, 6, 7),
                Triple(4, 8, 9)
            )
        )

        assertTrue(result.spills.isEmpty())
        assertEquals(5, result.allocation.size)
        for ((_, physReg) in result.allocation) {
            assertTrue(physReg in 0..15)
        }
    }

    @Test
    fun `spill candidate is the range ending latest`() {
        // With numRegs=1: virtual 0 lives [0,10] (long), virtual 1 lives [0,2] (short).
        // When virtual 1 arrives and the single reg is taken by virtual 0,
        // the allocator must spill the one ending latest — that is virtual 0.
        val allocator = RegisterAllocator(numRegs = 1)
        val result = allocator.allocate(
            ranges(
                Triple(0, 0, 10),
                Triple(1, 0, 2)
            )
        )

        // virtual 0 (ends at 10) should be the spill candidate
        assertTrue(
            result.spills.containsKey(0),
            "Virtual reg 0 (ending latest) should be spilled in favour of shorter-lived reg"
        )
    }

    @Test
    fun `return value of allocate contains the allocation map`() {
        val allocator = RegisterAllocator(numRegs = 16)
        val result = allocator.allocate(ranges(Triple(0, 0, 3)))

        // The result must contain an allocation map with the entry
        assertTrue(result.allocation.containsKey(0), "Allocation should contain virtual reg 0")
    }
}
