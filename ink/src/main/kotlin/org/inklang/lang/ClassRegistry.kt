package org.inklang.lang

object ClassRegistry {
    private val globals = mutableMapOf<String, ClassDescriptor>()

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