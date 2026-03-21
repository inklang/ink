package org.inklang.bukkit

import org.bukkit.command.CommandSender
import org.inklang.InkContext
import org.inklang.InkIo
import org.inklang.InkJson
import org.inklang.InkDb
import org.inklang.InkScript
import org.inklang.lang.Value
import java.io.File

/**
 * Context for dynamic scripts — no lifecycle, isolated execution.
 */
class ScriptContext(
    private val sender: CommandSender,
    private val plugin: InkBukkit,
    private val io: InkIo,
    private val json: InkJson,
    private val db: InkDb
) : InkContext {

    override fun log(message: String) {
        plugin.logger.info("[Ink] $message")
    }

    override fun print(message: String) {
        sender.sendMessage("§f[Ink] $message")
    }

    override fun io(): InkIo = io
    override fun json(): InkJson = json
    override fun db(): InkDb = db

    override fun supportsLifecycle(): Boolean = false

    // Dynamic scripts cannot register events — throw UnsupportedOperationException
    override fun registerEventHandler(
        eventName: String,
        handlerFunc: Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    ) {
        throw UnsupportedOperationException(
            "Dynamic scripts cannot register events. Place event handlers in a plugin in plugins/ink/plugins/"
        )
    }

    override fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean {
        return false
    }

    override fun onEnable(script: InkScript) {
        throw UnsupportedOperationException("Dynamic scripts do not support lifecycle")
    }

    override fun onDisable(script: InkScript) {
        throw UnsupportedOperationException("Dynamic scripts do not support lifecycle")
    }
}