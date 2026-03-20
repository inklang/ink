# Register Spilling Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement correct register spilling so quill functions with more than 16 simultaneously live virtual registers compile and execute correctly.

**Architecture:** `SpillInserter` is a new pass that runs after `RegisterAllocator` and fully resolves all virtual registers to physical registers — replacing `rewrite()` / `rewriteRegisters()` entirely. For spilled virtuals it injects `Unspill`/`Spill` IR instructions around each use/def. The VM's `CallFrame` gains a `spills` array sized at frame creation from `Chunk.spillSlotCount`.

**Tech Stack:** Kotlin 2.2.21, JVM 21, kotlin.test, Gradle (`./gradlew test`)

---

## Chunk 1: Foundation types

### Task 1: Add Spill/Unspill IR instructions and opcodes

**Files:**
- Modify: `src/main/kotlin/org/quill/lang/IR.kt`
- Modify: `src/main/kotlin/org/quill/lang/OpCode.kt`
- Modify: `src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt`

- [ ] **Step 1: Add `Spill` and `Unspill` to `IR.kt`**

  Add after `object Next : IrInstr()` (line 37):

  ```kotlin
      data class Spill(val slot: Int, val src: Int) : IrInstr()    // spills[slot] = regs[src]
      data class Unspill(val dst: Int, val slot: Int) : IrInstr()  // regs[dst] = spills[slot]
  ```

- [ ] **Step 2: Add `SPILL` and `UNSPILL` to `OpCode.kt`**

  Add after `BUILD_CLASS(0x25)` (line 53):

  ```kotlin
      SPILL(0x26),    // imm=slot, src1=physical_reg
      UNSPILL(0x27),  // dst=physical_reg, imm=slot
  ```

- [ ] **Step 3: Update `LivenessAnalyzer.kt` to handle the new IR types**

  The `when (instr)` block at line 44 is an exhaustive sealed-class match. Add two cases before the closing `}` of the `when` at line 106:

  ```kotlin
                  is IrInstr.Spill   -> use(instr.src, idx)
                  is IrInstr.Unspill -> define(instr.dst, idx)
  ```

- [ ] **Step 4: Verify it compiles**

  ```
  ./gradlew compileKotlin
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/kotlin/org/quill/lang/IR.kt \
          src/main/kotlin/org/quill/lang/OpCode.kt \
          src/main/kotlin/org/quill/ast/LivenessAnalyzer.kt
  git commit -m "feat: add Spill/Unspill IR instructions and SPILL/UNSPILL opcodes"
  ```

---

### Task 2: Add `spillSlotCount` to `Chunk`

**Files:**
- Modify: `src/main/kotlin/org/quill/lang/Chunk.kt`

- [ ] **Step 1: Add `spillSlotCount` field to `Chunk`**

  Add after `val functionDefaults = ...` (line 28):

  ```kotlin
      var spillSlotCount: Int = 0
  ```

  Must be `var` — `IrCompiler` sets it after constructing the chunk.

- [ ] **Step 2: Verify it compiles**

  ```
  ./gradlew compileKotlin
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

  ```bash
  git add src/main/kotlin/org/quill/lang/Chunk.kt
  git commit -m "feat: add spillSlotCount field to Chunk"
  ```

---

## Chunk 2: RegisterAllocator — AllocResult

### Task 3: Return `AllocResult` from `RegisterAllocator` and update callers

**Files:**
- Modify: `src/main/kotlin/org/quill/ast/RegisterAllocator.kt`
- Modify: `src/main/kotlin/org/quill/ast/IrCompiler.kt` (three call sites)
- Modify: `src/main/kotlin/org/quill/Main.kt` (one call site)

- [ ] **Step 1: Rewrite `RegisterAllocator.kt`**

  Replace the entire file contents:

  ```kotlin
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
                      allocation[range.reg] = numParams
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
  ```

- [ ] **Step 2: Update `IrCompiler.kt` — three call sites to extract `.allocation`**

  At line 74, change:
  ```kotlin
  val funcAllocation = RegisterAllocator().allocate(funcRanges, instr.arity)
  val funcRewritten = rewriteRegisters(funcDeconstructed, funcAllocation)
  ```
  To:
  ```kotlin
  val funcAllocResult = RegisterAllocator().allocate(funcRanges, instr.arity)
  val funcRewritten = rewriteRegisters(funcDeconstructed, funcAllocResult.allocation)
  ```

  At line 86, change:
  ```kotlin
  val defaultAllocation = RegisterAllocator().allocate(defaultRanges, 0)
  val defaultRewritten = rewriteRegisters(defaultInfo.instrs, defaultAllocation)
  ```
  To:
  ```kotlin
  val defaultAllocResult = RegisterAllocator().allocate(defaultRanges, 0)
  val defaultRewritten = rewriteRegisters(defaultInfo.instrs, defaultAllocResult.allocation)
  ```

  At line 137, change:
  ```kotlin
  val funcAllocation = RegisterAllocator().allocate(funcRanges, methodInfo.arity)
  val funcRewritten = rewriteRegisters(methodDeconstructed, funcAllocation)
  ```
  To:
  ```kotlin
  val methodAllocResult = RegisterAllocator().allocate(funcRanges, methodInfo.arity)
  val funcRewritten = rewriteRegisters(methodDeconstructed, methodAllocResult.allocation)
  ```

- [ ] **Step 3: Update `Main.kt` — extract `.allocation` from allocate result**

  At line 46, change:
  ```kotlin
  val allocation = RegisterAllocator().allocate(ranges)
  val rewritten = rewrite(ssaResult.instrs, allocation)
  ```
  To:
  ```kotlin
  val allocResult = RegisterAllocator().allocate(ranges)
  val rewritten = rewrite(ssaResult.instrs, allocResult.allocation)
  ```

- [ ] **Step 4: Verify all tests still pass**

  ```
  ./gradlew test
  ```
  Expected: BUILD SUCCESSFUL (all existing tests pass)

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/kotlin/org/quill/ast/RegisterAllocator.kt \
          src/main/kotlin/org/quill/ast/IrCompiler.kt \
          src/main/kotlin/org/quill/Main.kt
  git commit -m "refactor: RegisterAllocator returns AllocResult with spills map"
  ```

---

## Chunk 3: SpillInserter

### Task 4: Implement SpillInserter with unit tests

**Files:**
- Create: `src/main/kotlin/org/quill/ast/SpillInserter.kt`
- Create: `src/test/kotlin/org/quill/ast/SpillInserterTest.kt`

`SpillInserter` fully resolves all virtual registers to physical registers. For spilled virtuals it injects `Unspill`/`Spill` wrapper instructions. It replaces `rewrite()` and `rewriteRegisters()` — those functions are removed in Task 6.

- [ ] **Step 1: Write the failing test**

  Create `src/test/kotlin/org/quill/ast/SpillInserterTest.kt`:

  ```kotlin
  package org.inklang.ast

  import org.inklang.lang.IrInstr
  import org.inklang.lang.TokenType
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertFailsWith

  class SpillInserterTest {

      /**
       * Non-spill path: two virtual regs, simple BinaryOp, no spilling needed.
       * SpillInserter should just map virtuals → physicals via allocation.
       */
      @Test
      fun testNoSpill() {
          val instrs = listOf(
              IrInstr.LoadImm(0, 0),
              IrInstr.LoadImm(1, 1),
              IrInstr.BinaryOp(2, TokenType.PLUS, 0, 1),
              IrInstr.Return(2)
          )
          val ranges = LivenessAnalyzer().analyze(instrs)
          val allocResult = RegisterAllocator().allocate(ranges)
          assertEquals(emptyMap(), allocResult.spills)

          val resolved = SpillInserter().insert(instrs, allocResult, ranges)

          assertEquals(instrs.size, resolved.size)
          assert(resolved.none { it is IrInstr.Spill || it is IrInstr.Unspill })
      }

      /**
       * Spill path: v17 is defined early (long range) so the allocator spills it.
       * v0-v15 are all used together at the NewArray instruction — v17 is NOT used there.
       * At v17's use point (BinaryOp), v0-v15 are all dead → free temp registers exist.
       * SpillInserter must succeed and inject Unspill/Spill around the BinaryOp.
       *
       * Instruction layout:
       *   idx 0:    LoadImm(v17, 0)             — v17 defined early, long range
       *   idx 1-16: LoadImm(v0..v15, 0)         — v0-v15 defined
       *   idx 17:   NewArray(v16, [v0..v15])     — uses v0-v15 (they all die); v17 NOT used here
       *   idx 18:   BinaryOp(v18, +, v16, v17)  — v17 finally used; only v16 is also live → free temp
       *   idx 19:   Return(v18)
       */
      @Test
      fun testSpillInjected() {
          val instrs = buildList {
              add(IrInstr.LoadImm(17, 0))                        // v17 at idx 0 (long range)
              for (i in 0..15) add(IrInstr.LoadImm(i, 0))       // v0-v15 at idx 1-16
              add(IrInstr.NewArray(16, (0..15).toList()))        // idx 17: uses v0-v15
              add(IrInstr.BinaryOp(18, TokenType.PLUS, 16, 17)) // idx 18: uses v16 + spilled v17
              add(IrInstr.Return(18))
          }
          val ranges = LivenessAnalyzer().analyze(instrs)
          val allocResult = RegisterAllocator().allocate(ranges)

          // v17 must be spilled (longest range when all 16 regs are occupied at NewArray)
          assert(allocResult.spills.isNotEmpty()) { "Expected at least one spill" }
          assert(17 in allocResult.spills) { "Expected v17 to be spilled" }

          // SpillInserter must succeed — v17's use point (BinaryOp) has free temp regs
          val resolved = SpillInserter().insert(instrs, allocResult, ranges)

          // Must contain Unspill (before BinaryOp) and Spill (after BinaryOp) for v17
          assert(resolved.any { it is IrInstr.Unspill }) { "Expected Unspill for spilled v17" }
          assert(resolved.any { it is IrInstr.Spill })   { "Expected Spill for spilled v17" }

          // All register operands in the resolved list must be physical (0-15)
          resolved.forEach { instr ->
              when (instr) {
                  is IrInstr.LoadImm    -> assert(instr.dst in 0..15)
                  is IrInstr.NewArray   -> {
                      assert(instr.dst in 0..15)
                      instr.elements.forEach { e -> assert(e in 0..15) }
                  }
                  is IrInstr.BinaryOp   -> {
                      assert(instr.dst in 0..15)
                      assert(instr.src1 in 0..15)
                      assert(instr.src2 in 0..15)
                  }
                  is IrInstr.Return     -> assert(instr.src in 0..15)
                  is IrInstr.Unspill    -> assert(instr.dst in 0..15)
                  is IrInstr.Spill      -> assert(instr.src in 0..15)
                  else -> {}
              }
          }
      }

      /**
       * Pressure error: a spilled virtual is used at an instruction where all 16
       * physical registers are simultaneously occupied → SpillInserter must throw.
       *
       * Instruction layout:
       *   idx 0-15:  LoadImm(v0..v15, 0)         — 16 virtuals defined
       *   idx 16:    NewArray(v16, [v0..v15])     — uses v0-v15 (all 16 physicals live);
       *                                              allocator spills one of v0-v15 to free a
       *                                              register for v16. At idx 16, livePhysAt = {0..15}.
       *   idx 17:    Return(v16)
       *
       * The spilled virtual (one of v0-v15) is used as a NewArray element at idx 16.
       * At that point all 16 physical registers are occupied → no free temp → error.
       */
      @Test
      fun testRegisterPressureError() {
          val instrs = buildList {
              for (i in 0..15) add(IrInstr.LoadImm(i, 0))
              add(IrInstr.NewArray(16, (0..15).toList()))
              add(IrInstr.Return(16))
          }
          val ranges = LivenessAnalyzer().analyze(instrs)
          val allocResult = RegisterAllocator().allocate(ranges)
          assert(allocResult.spills.isNotEmpty()) { "Expected spilling for 17 simultaneously live virtuals" }

          assertFailsWith<IllegalStateException> {
              SpillInserter().insert(instrs, allocResult, ranges)
          }
      }
  }
  ```

- [ ] **Step 2: Run test to verify it fails**

  ```
  ./gradlew test --tests "org.inklang.ast.SpillInserterTest"
  ```
  Expected: FAIL — `SpillInserter` class does not exist yet.

- [ ] **Step 3: Implement `SpillInserter.kt`**

  Create `src/main/kotlin/org/quill/ast/SpillInserter.kt`:

  ```kotlin
  package org.inklang.ast

  import org.inklang.lang.IrInstr

  /**
   * Resolves all virtual registers to physical registers, injecting Spill/Unspill
   * instructions for spilled virtuals. Replaces rewrite() and rewriteRegisters().
   *
   * After insert() returns, no virtual register numbers remain in the instruction list.
   */
  class SpillInserter {

      fun insert(
          instrs: List<IrInstr>,
          allocResult: RegisterAllocator.AllocResult,
          ranges: Map<Int, LiveRange>
      ): List<IrInstr> {
          val allocation = allocResult.allocation
          val spills = allocResult.spills

          // Precompute which physical registers are live at each original instruction index.
          // livePhysAt[i] = set of physical regs whose virtual is live at instruction i.
          val livePhysAt: Array<Set<Int>> = Array(instrs.size) { i ->
              ranges.values
                  .filter { it.start <= i && i <= it.end }
                  .mapNotNull { allocation[it.reg] }
                  .toSet()
          }

          val result = mutableListOf<IrInstr>()

          for ((i, instr) in instrs.withIndex()) {
              // Per-instruction: track which temp regs have been claimed this instruction
              // to prevent two spilled operands from picking the same temp.
              val claimedTemps = mutableSetOf<Int>()
              val preInstrs  = mutableListOf<IrInstr>()
              val postInstrs = mutableListOf<IrInstr>()

              fun pickTemp(): Int =
                  (0..15).firstOrNull { it !in livePhysAt[i] && it !in claimedTemps }
                      ?.also { claimedTemps.add(it) }
                      ?: error("Function exceeds register pressure: all 16 registers live simultaneously at instruction $i")

              // Resolve a source register to a physical register.
              // If spilled: insert Unspill before this instruction.
              fun resolveSrc(reg: Int): Int {
                  if (reg in spills) {
                      val temp = pickTemp()
                      preInstrs.add(IrInstr.Unspill(temp, spills[reg]!!))
                      return temp
                  }
                  return allocation[reg] ?: error("Virtual register v$reg has no physical allocation")
              }

              // Resolve a destination register to a physical register.
              // If spilled: insert Spill after this instruction.
              fun resolveDst(reg: Int): Int {
                  if (reg in spills) {
                      val temp = pickTemp()
                      postInstrs.add(IrInstr.Spill(spills[reg]!!, temp))
                      return temp
                  }
                  return allocation[reg] ?: error("Virtual register v$reg has no physical allocation")
              }

              val rewritten: IrInstr = when (instr) {
                  is IrInstr.LoadImm     -> instr.copy(dst = resolveDst(instr.dst))
                  is IrInstr.LoadGlobal  -> instr.copy(dst = resolveDst(instr.dst))
                  is IrInstr.StoreGlobal -> instr.copy(src = resolveSrc(instr.src))
                  is IrInstr.Move        -> instr.copy(src = resolveSrc(instr.src), dst = resolveDst(instr.dst))
                  is IrInstr.BinaryOp    -> instr.copy(
                      src1 = resolveSrc(instr.src1),
                      src2 = resolveSrc(instr.src2),
                      dst  = resolveDst(instr.dst)
                  )
                  is IrInstr.UnaryOp     -> instr.copy(src = resolveSrc(instr.src), dst = resolveDst(instr.dst))
                  is IrInstr.Call        -> instr.copy(
                      func = resolveSrc(instr.func),
                      args = instr.args.map { resolveSrc(it) },
                      dst  = resolveDst(instr.dst)
                  )
                  is IrInstr.NewArray    -> instr.copy(
                      elements = instr.elements.map { resolveSrc(it) },
                      dst      = resolveDst(instr.dst)
                  )
                  is IrInstr.GetIndex    -> instr.copy(
                      obj   = resolveSrc(instr.obj),
                      index = resolveSrc(instr.index),
                      dst   = resolveDst(instr.dst)
                  )
                  is IrInstr.SetIndex    -> instr.copy(
                      obj   = resolveSrc(instr.obj),
                      index = resolveSrc(instr.index),
                      src   = resolveSrc(instr.src)
                  )
                  is IrInstr.GetField    -> instr.copy(obj = resolveSrc(instr.obj), dst = resolveDst(instr.dst))
                  is IrInstr.SetField    -> instr.copy(obj = resolveSrc(instr.obj), src = resolveSrc(instr.src))
                  is IrInstr.NewInstance -> instr.copy(
                      classReg = resolveSrc(instr.classReg),
                      args     = instr.args.map { resolveSrc(it) },
                      dst      = resolveDst(instr.dst)
                  )
                  is IrInstr.IsType      -> instr.copy(src = resolveSrc(instr.src), dst = resolveDst(instr.dst))
                  is IrInstr.LoadClass   -> instr.copy(dst = resolveDst(instr.dst))
                  is IrInstr.Return      -> instr.copy(src = resolveSrc(instr.src))
                  is IrInstr.JumpIfFalse -> instr.copy(src = resolveSrc(instr.src))
                  is IrInstr.LoadFunc    -> instr.copy(dst = resolveDst(instr.dst))
                  else                   -> instr  // Label, Jump, Break, Next, Spill, Unspill — no virtual regs
              }

              result.addAll(preInstrs)
              result.add(rewritten)
              result.addAll(postInstrs)
          }

          return result
      }
  }
  ```

- [ ] **Step 4: Run tests to verify they pass**

  ```
  ./gradlew test --tests "org.inklang.ast.SpillInserterTest"
  ```
  Expected: All 3 tests PASS.

- [ ] **Step 5: Run full test suite to verify nothing broken**

  ```
  ./gradlew test
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

  ```bash
  git add src/main/kotlin/org/quill/ast/SpillInserter.kt \
          src/test/kotlin/org/quill/ast/SpillInserterTest.kt
  git commit -m "feat: implement SpillInserter pass for register spilling"
  ```

---

## Chunk 4: VM spill support

### Task 5: Add spill array to CallFrame and dispatch SPILL/UNSPILL

**Files:**
- Modify: `src/main/kotlin/org/quill/ast/VM.kt`

- [ ] **Step 1: Add `spills` array to `CallFrame`**

  The `CallFrame` is a `data class`. Add a property initializer (class body) that references `chunk` from the primary constructor. Replace lines 27–33:

  ```kotlin
      data class CallFrame(
          val chunk: Chunk,
          var ip: Int = 0,
          val regs: Array<Value?> = arrayOfNulls(16),
          var returnDst: Int = 0,
          val argBuffer: ArrayDeque<Value> = ArrayDeque()
      ) {
          val spills: Array<Value?> = arrayOfNulls(chunk.spillSlotCount)
      }
  ```

- [ ] **Step 2: Add `SPILL`/`UNSPILL` to the main VM dispatch `when (opcode)` block**

  Add before the closing `}` of the `when (opcode)` block (before line 308):

  ```kotlin
                  OpCode.SPILL   -> frame.spills[imm] = frame.regs[src1]
                  OpCode.UNSPILL -> frame.regs[dst] = frame.spills[imm]!!
  ```

- [ ] **Step 3: Add `SPILL`/`UNSPILL` to `executeDefaultChunk()`**

  Inside `executeDefaultChunk()`, add before the `else ->` line (before line 462):

  ```kotlin
                  OpCode.SPILL   -> frame.spills[imm] = frame.regs[src1]
                  OpCode.UNSPILL -> frame.regs[dst] = frame.spills[imm]!!
  ```

- [ ] **Step 4: Verify tests still pass**

  ```
  ./gradlew test
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/kotlin/org/quill/ast/VM.kt
  git commit -m "feat: add spills array to CallFrame and dispatch SPILL/UNSPILL in VM"
  ```

---

## Chunk 5: IrCompiler + pipeline wiring

### Task 6: Add SPILL/UNSPILL compilation and wire SpillInserter into all sub-pipelines

**Files:**
- Modify: `src/main/kotlin/org/quill/ast/IrCompiler.kt`
- Modify: `src/main/kotlin/org/quill/Main.kt`

`SpillInserter` replaces `rewrite()` / `rewriteRegisters()`. After this task, all virtual register resolution happens in `SpillInserter`. Both `rewrite()` (in `Main.kt`) and `rewriteRegisters()` (in `IrCompiler`) are removed.

- [ ] **Step 1: Add `Spill`/`Unspill` compile cases to `IrCompiler.compile()`**

  In the `when (instr)` block of `compile()`, add before the closing `}` (before line 150):

  ```kotlin
                  is IrInstr.Spill   -> chunk.write(OpCode.SPILL,   src1 = instr.src, imm = instr.slot)
                  is IrInstr.Unspill -> chunk.write(OpCode.UNSPILL, dst  = instr.dst, imm = instr.slot)
  ```

- [ ] **Step 2: Add `SpillInserter` import to `IrCompiler.kt`**

  Add to the imports at the top:

  ```kotlin
  import org.inklang.ast.SpillInserter
  ```

- [ ] **Step 3: Replace `LoadFunc` sub-pipeline to use `SpillInserter`**

  Replace lines 73–77:
  ```kotlin
                      val funcRanges = LivenessAnalyzer().analyze(funcDeconstructed)
                      val funcAllocation = RegisterAllocator().allocate(funcRanges, instr.arity)
                      val funcRewritten = rewriteRegisters(funcDeconstructed, funcAllocation)
                      val funcResult = AstLowerer.LoweredResult(funcRewritten, instr.constants)
                      val funcChunk = IrCompiler().compile(funcResult)
  ```
  With:
  ```kotlin
                      val funcRanges = LivenessAnalyzer().analyze(funcDeconstructed)
                      val funcAllocResult = RegisterAllocator().allocate(funcRanges, instr.arity)
                      val funcResolved = SpillInserter().insert(funcDeconstructed, funcAllocResult, funcRanges)
                      val funcResult = AstLowerer.LoweredResult(funcResolved, instr.constants)
                      val funcChunk = IrCompiler().compile(funcResult)
                      funcChunk.spillSlotCount = funcAllocResult.spillSlotCount
  ```

- [ ] **Step 4: Replace default-value sub-pipeline to use `SpillInserter`**

  Replace lines 85–89:
  ```kotlin
                          val defaultRanges = LivenessAnalyzer().analyze(defaultInfo.instrs)
                          val defaultAllocation = RegisterAllocator().allocate(defaultRanges, 0)
                          val defaultRewritten = rewriteRegisters(defaultInfo.instrs, defaultAllocation)
                          val defaultResult = AstLowerer.LoweredResult(defaultRewritten, defaultInfo.constants)
                          val defaultChunk = IrCompiler().compile(defaultResult)
  ```
  With:
  ```kotlin
                          val defaultRanges = LivenessAnalyzer().analyze(defaultInfo.instrs)
                          val defaultAllocResult = RegisterAllocator().allocate(defaultRanges, 0)
                          val defaultResolved = SpillInserter().insert(defaultInfo.instrs, defaultAllocResult, defaultRanges)
                          val defaultResult = AstLowerer.LoweredResult(defaultResolved, defaultInfo.constants)
                          val defaultChunk = IrCompiler().compile(defaultResult)
                          defaultChunk.spillSlotCount = defaultAllocResult.spillSlotCount
  ```

- [ ] **Step 5: Replace `LoadClass` method sub-pipeline to use `SpillInserter`**

  Replace lines 136–140:
  ```kotlin
                          val funcRanges = LivenessAnalyzer().analyze(methodDeconstructed)
                          val funcAllocation = RegisterAllocator().allocate(funcRanges, methodInfo.arity)
                          val funcRewritten = rewriteRegisters(methodDeconstructed, funcAllocation)
                          val funcResult = AstLowerer.LoweredResult(funcRewritten, methodInfo.constants)
                          val funcChunk = IrCompiler().compile(funcResult)
  ```
  With:
  ```kotlin
                          val funcRanges = LivenessAnalyzer().analyze(methodDeconstructed)
                          val methodAllocResult = RegisterAllocator().allocate(funcRanges, methodInfo.arity)
                          val methodResolved = SpillInserter().insert(methodDeconstructed, methodAllocResult, funcRanges)
                          val funcResult = AstLowerer.LoweredResult(methodResolved, methodInfo.constants)
                          val funcChunk = IrCompiler().compile(funcResult)
                          funcChunk.spillSlotCount = methodAllocResult.spillSlotCount
  ```

- [ ] **Step 6: Delete `rewriteRegisters()` from `IrCompiler.kt`**

  Delete lines 156–181 (the entire `rewriteRegisters` private function). It is now replaced by `SpillInserter`.

- [ ] **Step 7: Replace `Main.kt` pipeline to use `SpillInserter`**

  In `Main.kt`, replace lines 45–48:
  ```kotlin
      val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
      val allocation = RegisterAllocator().allocate(ranges)
      val rewritten = rewrite(ssaResult.instrs, allocation)
      val chunk = IrCompiler().compile(AstLowerer.LoweredResult(rewritten, ssaResult.constants))
  ```
  With:
  ```kotlin
      val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
      val allocResult = RegisterAllocator().allocate(ranges)
      val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)
      val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaResult.constants))
      chunk.spillSlotCount = allocResult.spillSlotCount
  ```

  Also add the import at the top of `Main.kt`:
  ```kotlin
  import org.inklang.ast.SpillInserter
  ```

- [ ] **Step 8: Delete `rewrite()` from `Main.kt` and remove its unused import**

  Delete lines 61–86 (the entire `rewrite` function at the bottom of `Main.kt`). It is replaced by `SpillInserter`.

  Also remove the now-orphaned import on line 10. `rewrite()` is the only place in `Main.kt` that names `IrInstr` explicitly; `main()` only uses it via type inference (no explicit annotation). After deletion the import is unused and the compiler will emit a warning:
  ```kotlin
  import org.inklang.lang.IrInstr
  ```

- [ ] **Step 9: Verify the full test suite passes**

  ```
  ./gradlew test
  ```
  Expected: BUILD SUCCESSFUL (all tests pass)

- [ ] **Step 10: Commit**

  ```bash
  git add src/main/kotlin/org/quill/ast/IrCompiler.kt \
          src/main/kotlin/org/quill/Main.kt
  git commit -m "feat: wire SpillInserter into all compilation sub-pipelines, remove rewrite()"
  ```

---

## Chunk 6: Integration tests

### Task 7: End-to-end register spill tests

**Files:**
- Create: `src/test/kotlin/org/quill/ast/RegisterSpillTest.kt`

These tests compile and execute real quill programs that exercise spilling through the full pipeline.

- [ ] **Step 1: Write the failing tests**

  Create `src/test/kotlin/org/quill/ast/RegisterSpillTest.kt`:

  ```kotlin
  package org.inklang.ast

  import org.inklang.lang.*
  import org.inklang.ssa.SsaBuilder
  import org.inklang.ssa.SsaDeconstructor
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertFailsWith

  class RegisterSpillTest {

      /** Compile and run a quill source string, capturing print() output. */
      private fun run(source: String): String {
          val output = StringBuilder()
          val tokens = tokenize(source)
          val parser = Parser(tokens)
          val stmts = parser.parse()
          val folder = ConstantFolder()
          val folded = stmts.map { folder.foldStmt(it) }
          val result = AstLowerer().lower(folded)
          val ssaFunc = SsaBuilder.build(result.instrs, result.constants)
          val ssaDeconstructed = SsaDeconstructor.deconstruct(ssaFunc)
          val ssaResult = AstLowerer.LoweredResult(ssaDeconstructed, result.constants)
          val ranges = LivenessAnalyzer().analyze(ssaResult.instrs)
          val allocResult = RegisterAllocator().allocate(ranges)
          val resolved = SpillInserter().insert(ssaResult.instrs, allocResult, ranges)
          val chunk = IrCompiler().compile(AstLowerer.LoweredResult(resolved, ssaResult.constants))
          chunk.spillSlotCount = allocResult.spillSlotCount
          val vm = VM()
          vm.globals["print"] = Value.NativeFunction { args ->
              output.appendLine(args.joinToString(" ") { it.toString() })
              Value.Null
          }
          vm.execute(chunk)
          return output.toString().trimEnd()
      }

      /**
       * Test 1: Basic spill.
       * 17 variables (a–q) all live simultaneously in a function.
       * With 16 physical registers, at least one must be spilled.
       * Expected result: 1+2+...+17 = 153.
       */
      @Test
      fun testBasicSpill() {
          val source = """
              fun heavy() {
                  let a = 1
                  let b = 2
                  let c = 3
                  let d = 4
                  let e = 5
                  let f = 6
                  let g = 7
                  let h = 8
                  let i = 9
                  let j = 10
                  let k = 11
                  let l = 12
                  let m = 13
                  let n = 14
                  let o = 15
                  let p = 16
                  let q = 17
                  return a + b + c + d + e + f + g + h + i + j + k + l + m + n + o + p + q
              }
              print(heavy())
          """.trimIndent()

          assertEquals("153", run(source))
      }

      /**
       * Test 2: Spill across a branch.
       * Variables declared before a branch must remain correct on both paths.
       * branchSpill(true) → 153, branchSpill(false) → 17.
       */
      @Test
      fun testSpillAcrossBranch() {
          val source = """
              fun branchSpill(flag) {
                  let a = 1
                  let b = 2
                  let c = 3
                  let d = 4
                  let e = 5
                  let f = 6
                  let g = 7
                  let h = 8
                  let i = 9
                  let j = 10
                  let k = 11
                  let l = 12
                  let m = 13
                  let n = 14
                  let o = 15
                  let p = 16
                  let q = 17
                  if (flag) {
                      return a + b + c + d + e + f + g + h + i + j + k + l + m + n + o + p + q
                  }
                  return q
              }
              print(branchSpill(true))
              print(branchSpill(false))
          """.trimIndent()

          val output = run(source).lines()
          assertEquals("153", output[0])
          assertEquals("17",  output[1])
      }

      /**
       * Test 3: Pressure error.
       * 17 variables all used as elements of a single array literal forces the
       * compiler to hold all 17 simultaneously live at the NewArray instruction.
       * With only 16 physical registers, the spilled virtual needs a temp at the
       * exact point where all 16 physicals are occupied → SpillInserter must throw.
       */
      @Test
      fun testPressureError() {
          val source = """
              fun cramped() {
                  let a = 1
                  let b = 2
                  let c = 3
                  let d = 4
                  let e = 5
                  let f = 6
                  let g = 7
                  let h = 8
                  let i = 9
                  let j = 10
                  let k = 11
                  let l = 12
                  let m = 13
                  let n = 14
                  let o = 15
                  let p = 16
                  let q = 17
                  return [a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q]
              }
              cramped()
          """.trimIndent()

          assertFailsWith<IllegalStateException> {
              run(source)
          }
      }
  }
  ```

- [ ] **Step 2: Run tests to verify they fail (or pass — both outcomes are acceptable)**

  ```
  ./gradlew test --tests "org.inklang.ast.RegisterSpillTest"
  ```

  If they already pass (because the pipeline now handles spilling): proceed to Step 4.
  If they fail with a meaningful error: investigate and fix before proceeding.

- [ ] **Step 3: Run the full test suite**

  ```
  ./gradlew test
  ```
  Expected: BUILD SUCCESSFUL (all tests pass including the new register spill tests)

- [ ] **Step 4: Commit**

  ```bash
  git add src/test/kotlin/org/quill/ast/RegisterSpillTest.kt
  git commit -m "test: add register spill integration tests (basic spill + spill across branch)"
  ```

---

## Summary of files changed

| File | Change |
|------|--------|
| `lang/IR.kt` | Add `Spill`, `Unspill` sealed subclasses |
| `lang/OpCode.kt` | Add `SPILL(0x26)`, `UNSPILL(0x27)` |
| `lang/Chunk.kt` | Add `var spillSlotCount: Int = 0` |
| `ast/LivenessAnalyzer.kt` | Add `Spill`/`Unspill` cases to exhaustive `when` |
| `ast/RegisterAllocator.kt` | Add `AllocResult` data class; return it from `allocate()`; remove instance-level `spills`/`spillSlot` fields |
| `ast/SpillInserter.kt` | **New** — full virtual→physical resolution + Unspill/Spill injection |
| `ast/IrCompiler.kt` | Add `Spill`/`Unspill` compile cases; replace three sub-pipelines to use `SpillInserter`; delete `rewriteRegisters()`; set `spillSlotCount` on each sub-chunk |
| `ast/VM.kt` | Add `spills` array to `CallFrame`; dispatch `SPILL`/`UNSPILL` in main loop and `executeDefaultChunk()` |
| `Main.kt` | Wire `SpillInserter`; set top-level `chunk.spillSlotCount`; delete `rewrite()` |
| `test/.../SpillInserterTest.kt` | **New** — unit tests for SpillInserter (no-spill, spill injected, pressure error) |
| `test/.../RegisterSpillTest.kt` | **New** — integration tests (basic spill, spill across branch) |
