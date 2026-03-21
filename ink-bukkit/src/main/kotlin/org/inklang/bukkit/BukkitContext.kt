package org.inklang.bukkit

import org.bukkit.command.CommandSender
import org.inklang.InkContext
import org.inklang.InkIo
import org.inklang.InkJson
import org.inklang.InkDb
import org.inklang.InkScript
import org.inklang.lang.Value

class BukkitContext(
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

    override fun registerEventHandler(
        eventName: String,
        handlerFunc: Value.Function,
        eventParamName: String,
        dataParamNames: List<String>
    ) {
        // Event registration is handled at compile time via the VM's event registry
    }

    override fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean {
        return false
    }

    override fun onEnable(script: InkScript) {
        // Lifecycle hooks not yet implemented in bukkit context
    }

    override fun onDisable(script: InkScript) {
        // Lifecycle hooks not yet implemented in bukkit context
    }
}
