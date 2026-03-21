package org.inklang

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
}
