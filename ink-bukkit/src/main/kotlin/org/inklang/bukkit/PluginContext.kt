package org.inklang.bukkit

import org.bukkit.command.CommandSender
import org.inklang.ContextVM
import org.inklang.InkContext
import org.inklang.InkIo
import org.inklang.InkJson
import org.inklang.InkDb
import org.inklang.InkScript
import org.inklang.lang.Value
import org.inklang.lang.ClassRegistry
import org.inklang.bukkit.runtime.BukkitRuntimeRegistrar
import java.io.File

/**
 * Extended context for plugin scripts with lifecycle support.
 */
class PluginContext(
    private val sender: CommandSender,
    private val plugin: InkBukkit,
    private val io: InkIo,
    private val json: InkJson,
    private val db: InkDb,
    private val pluginName: String,
    private val pluginFolder: File
) : InkContext {

    private var vm: ContextVM? = null

    override fun setVM(vm: ContextVM) {
        this.vm = vm
    }

    override fun log(message: String) {
        plugin.logger.info("[Ink/$pluginName] $message")
    }

    override fun print(message: String) {
        sender.sendMessage("§f[Ink/$pluginName] $message")
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
        // Event registration handled at compile time via VM's event registry
    }

    override fun fireEvent(eventName: String, event: Value.EventObject, data: List<Value?>): Boolean {
        return false
    }

    override fun onEnable(script: InkScript) {
        val vm = org.inklang.ContextVM(this)
        // Register Bukkit globals
        BukkitRuntimeRegistrar.register(sender, plugin.server)
        vm.setGlobals(ClassRegistry.getAllGlobals())
        vm.execute(script.getChunk())
    }

    override fun onDisable(script: InkScript) {
        val vm = org.inklang.ContextVM(this)
        // Register Bukkit globals
        BukkitRuntimeRegistrar.register(sender, plugin.server)
        vm.setGlobals(ClassRegistry.getAllGlobals())
        vm.execute(script.getChunk())
    }

    override fun supportsLifecycle(): Boolean = true

    fun getPluginFolder(): File = pluginFolder
    fun getPluginName(): String = pluginName
}
