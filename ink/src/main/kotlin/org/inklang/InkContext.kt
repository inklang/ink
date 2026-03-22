package org.inklang

import org.inklang.lang.Value
import org.inklang.ContextVM

/**
 * Context interface that runtime hosts implement.
 * Scripts never access platform APIs directly - they call log/print,
 * and the runtime decides where output goes.
 */
interface InkContext {
    /** Info-level log output (server console in Paper, stdout in CLI) */
    fun log(message: String)

    /** User-facing output (command sender in Paper, stdout in CLI) */
    fun print(message: String)

    /** File I/O driver provided by the host runtime */
    fun io(): InkIo

    /** JSON parse/stringify driver provided by the host runtime */
    fun json(): InkJson

    /** Database driver provided by the host runtime */
    fun db(): InkDb

    /** Register an event handler for the given event */
    fun registerEventHandler(
        eventName: String,
        handlerFunc: Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    )

    /** Fire an event and return whether it was cancelled */
    fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean

    /** Called when a plugin is enabled — returns the enable block's result, or throws */
    fun onEnable(script: InkScript)

    /** Called when a plugin is disabled — returns the disable block's result, or throws */
    fun onDisable(script: InkScript)

    /** Check if this context supports plugins (has lifecycle) */
    fun supportsLifecycle(): Boolean = true

    /** Set the VM instance for this context. Called by PluginRuntime. */
    fun setVM(vm: ContextVM)
}
