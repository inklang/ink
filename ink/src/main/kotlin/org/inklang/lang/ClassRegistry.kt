package org.inklang.lang

import java.util.concurrent.ConcurrentHashMap

object ClassRegistry {
    private val globals = ConcurrentHashMap<String, ClassDescriptor>()

    fun registerGlobal(name: String, descriptor: ClassDescriptor) {
        globals[name] = descriptor
    }

    fun getGlobal(name: String): ClassDescriptor? = globals[name]

    fun getAllGlobals(): Map<String, Value> = globals.mapValues { (_, desc) ->
        Value.Instance(desc)
    }

    fun clear() = globals.clear()

    fun hasGlobal(name: String): Boolean = globals.containsKey(name)
}