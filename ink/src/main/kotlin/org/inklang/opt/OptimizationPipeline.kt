package org.inklang.opt

import org.inklang.ast.ControlFlowGraph
import org.inklang.lang.IrInstr
import org.inklang.lang.Value
import org.inklang.ssa.SsaBuilder
import org.inklang.ssa.SsaDeconstructor
import org.inklang.ssa.SsaFunction
import org.inklang.ssa.SsaOptPass

/**
 * Pipeline that runs a sequence of optimization passes.
 * Supports running passes to fixed point.
 * Can optionally use SSA-based optimization passes.
 */
class OptimizationPipeline(
    private val passes: List<OptPass>,
    private val maxIterations: Int = 10
) {
    /**
     * Run all optimization passes on the IR.
     * @param instrs Initial IR instructions
     * @param constants Initial constant pool
     * @return Optimized instructions and constants
     */
    fun optimize(
        instrs: List<IrInstr>,
        constants: List<Value>
    ): Pair<List<IrInstr>, List<Value>> {
        var currentInstrs = instrs
        var currentConstants = constants

        for (pass in passes) {
            var iteration = 0
            var passChanged = true

            while (passChanged && iteration < maxIterations) {
                val cfg = ControlFlowGraph.build(currentInstrs)
                val result = pass.run(currentInstrs, cfg, currentConstants)

                currentInstrs = result.instrs
                currentConstants = result.constants
                passChanged = result.changed && pass.shouldRerunIfChanged()
                iteration++

                if (iteration > 1 && passChanged) {
                    // Log re-runs for debugging
                    println("[OptPipeline] Pass '${pass.name}' re-running (iteration $iteration)")
                }
            }
        }

        return Pair(currentInstrs, currentConstants)
    }

    /**
     * Optimize with detailed logging.
     */
    fun optimizeWithLogging(
        instrs: List<IrInstr>,
        constants: List<Value>
    ): Pair<List<IrInstr>, List<Value>> {
        var currentInstrs = instrs
        var currentConstants = constants

        println("[OptPipeline] Starting optimization with ${instrs.size} instructions")

        for (pass in passes) {
            println("[OptPipeline] Running pass: ${pass.name}")
            var iteration = 0
            var passChanged = true

            while (passChanged && iteration < maxIterations) {
                val cfg = ControlFlowGraph.build(currentInstrs)
                val result = pass.run(currentInstrs, cfg, currentConstants)

                if (result.changed) {
                    println("[OptPipeline]   - ${pass.name} made changes (iteration ${iteration + 1})")
                }

                currentInstrs = result.instrs
                currentConstants = result.constants
                passChanged = result.changed && pass.shouldRerunIfChanged()
                iteration++
            }
        }

        println("[OptPipeline] Optimization complete: ${currentInstrs.size} instructions")
        return Pair(currentInstrs, currentConstants)
    }

    companion object {
        /**
         * Run SSA-based optimization passes.
         * Converts to SSA, runs passes, then deconstructs back to IR.
         *
         * @param instrs Initial IR instructions
         * @param constants Initial constant pool
         * @param ssaPasses SSA-based optimization passes to run
         * @param preSsaPasses Regular passes to run before SSA conversion
         * @param postSsaPasses Regular passes to run after SSA deconstruction
         * @param maxIterations Maximum iterations per pass
         * @return Optimized instructions and constants
         */
        fun optimizeWithSsa(
            instrs: List<IrInstr>,
            constants: List<Value>,
            ssaPasses: List<SsaOptPass>,
            preSsaPasses: List<OptPass> = emptyList(),
            postSsaPasses: List<OptPass> = emptyList(),
            maxIterations: Int = 10
        ): Pair<List<IrInstr>, List<Value>> {
            var currentInstrs = instrs
            var currentConstants = constants

            // Run pre-SSA passes
            if (preSsaPasses.isNotEmpty()) {
                val prePipeline = OptimizationPipeline(preSsaPasses, maxIterations)
                val (newInstrs, newConstants) = prePipeline.optimize(currentInstrs, currentConstants)
                currentInstrs = newInstrs
                currentConstants = newConstants
            }

            // Convert to SSA
            if (ssaPasses.isNotEmpty()) {
                var ssaFunc = SsaBuilder.build(currentInstrs, currentConstants)

                // Run SSA passes
                for (pass in ssaPasses) {
                    var iteration = 0
                    var passChanged = true

                    while (passChanged && iteration < maxIterations) {
                        val result = pass.run(ssaFunc)
                        ssaFunc = result.ssaFunc
                        passChanged = result.changed && pass.shouldRerunIfChanged()
                        iteration++
                    }
                }

                // Deconstruct SSA back to IR
                currentInstrs = SsaDeconstructor.deconstruct(ssaFunc)
                // Constants may have been extended during SSA passes
                currentConstants = ssaFunc.constants
            }

            // Run post-SSA passes
            if (postSsaPasses.isNotEmpty()) {
                val postPipeline = OptimizationPipeline(postSsaPasses, maxIterations)
                val (newInstrs, newConstants) = postPipeline.optimize(currentInstrs, currentConstants)
                currentInstrs = newInstrs
                currentConstants = newConstants
            }

            return Pair(currentInstrs, currentConstants)
        }

        /**
         * Run SSA-based optimization with detailed logging.
         */
        fun optimizeWithSsaLogging(
            instrs: List<IrInstr>,
            constants: List<Value>,
            ssaPasses: List<SsaOptPass>,
            preSsaPasses: List<OptPass> = emptyList(),
            postSsaPasses: List<OptPass> = emptyList(),
            maxIterations: Int = 10
        ): Pair<List<IrInstr>, List<Value>> {
            var currentInstrs = instrs
            var currentConstants = constants

            println("[SsaPipeline] Starting optimization with ${instrs.size} instructions")

            // Run pre-SSA passes
            if (preSsaPasses.isNotEmpty()) {
                println("[SsaPipeline] Running pre-SSA passes")
                val prePipeline = OptimizationPipeline(preSsaPasses, maxIterations)
                val (newInstrs, newConstants) = prePipeline.optimizeWithLogging(currentInstrs, currentConstants)
                currentInstrs = newInstrs
                currentConstants = newConstants
            }

            // Convert to SSA
            if (ssaPasses.isNotEmpty()) {
                println("[SsaPipeline] Converting to SSA form")
                var ssaFunc = SsaBuilder.build(currentInstrs, currentConstants)
                println("[SsaPipeline] SSA form: ${ssaFunc.blocks.size} blocks, ${ssaFunc.allPhiFunctions().size} phi functions")

                // Run SSA passes
                for (pass in ssaPasses) {
                    println("[SsaPipeline] Running SSA pass: ${pass.name}")
                    var iteration = 0
                    var passChanged = true

                    while (passChanged && iteration < maxIterations) {
                        val result = pass.run(ssaFunc)
                        if (result.changed) {
                            println("[SsaPipeline]   - ${pass.name} made changes (iteration ${iteration + 1})")
                        }
                        ssaFunc = result.ssaFunc
                        passChanged = result.changed && pass.shouldRerunIfChanged()
                        iteration++
                    }
                }

                // Deconstruct SSA back to IR
                println("[SsaPipeline] Deconstructing SSA form")
                currentInstrs = SsaDeconstructor.deconstruct(ssaFunc)
                currentConstants = ssaFunc.constants
                println("[SsaPipeline] After SSA: ${currentInstrs.size} instructions")
            }

            // Run post-SSA passes
            if (postSsaPasses.isNotEmpty()) {
                println("[SsaPipeline] Running post-SSA passes")
                val postPipeline = OptimizationPipeline(postSsaPasses, maxIterations)
                val (newInstrs, newConstants) = postPipeline.optimizeWithLogging(currentInstrs, currentConstants)
                currentInstrs = newInstrs
                currentConstants = newConstants
            }

            println("[SsaPipeline] Optimization complete: ${currentInstrs.size} instructions")
            return Pair(currentInstrs, currentConstants)
        }
    }
}
