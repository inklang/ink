package org.inklang.bukkit

import org.bukkit.command.CommandSender
import org.inklang.InkContext
import org.inklang.InkIo
import org.inklang.InkJson
import org.inklang.InkDb
import org.inklang.InkScript
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

    override fun log(message: String) {
        plugin.logger.info("[Ink/$pluginName] $message")
    }

    override fun print(message: String) {
        sender.sendMessage("§f[Ink/$pluginName] $message")
    }

    override fun io(): InkIo = io
    override fun json(): InkJson = json
    override fun db(): InkDb = db

    override fun onEnable(script: InkScript) {
        script.execute(this)
    }

    override fun onDisable(script: InkScript) {
        script.execute(this)
    }

    override fun supportsLifecycle(): Boolean = true

    fun getPluginFolder(): File = pluginFolder
    fun getPluginName(): String = pluginName
}
