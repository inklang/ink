package org.inklang.lang

import java.util.concurrent.ConcurrentHashMap

object ClassRegistry {
    private val globals = ConcurrentHashMap<String, ClassDescriptor>()
    private val globalFunctions = ConcurrentHashMap<String, Value>()

    fun registerGlobal(name: String, descriptor: ClassDescriptor) {
        globals[name] = descriptor
    }

    fun registerGlobalFunction(name: String, fn: Value) {
        globalFunctions[name] = fn
    }

    fun getGlobal(name: String): ClassDescriptor? = globals[name]

    fun getAllGlobals(): Map<String, Value> {
        val classGlobals = globals.mapValues { (_, desc) -> Value.Instance(desc) }
        return classGlobals + globalFunctions
    }

    fun clear() {
        globals.clear()
        globalFunctions.clear()
    }

    fun hasGlobal(name: String): Boolean = globals.containsKey(name) || globalFunctions.containsKey(name)
}