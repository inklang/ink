package org.inklang

/**
 * Context interface that runtime hosts implement.
 * Scripts never access platform APIs directly - they call log/print,
 * and the runtime decides where output goes.
 */
interface QuillContext {
    /** Info-level log output (server console in Paper, stdout in CLI) */
    fun log(message: String)

    /** User-facing output (command sender in Paper, stdout in CLI) */
    fun print(message: String)
}
