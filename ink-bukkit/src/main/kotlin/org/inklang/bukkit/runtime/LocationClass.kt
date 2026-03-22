package org.inklang.bukkit.runtime

import org.bukkit.Location
import org.bukkit.Server
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value

object LocationClass {
    fun createDescriptor(location: Location, server: Server): ClassDescriptor {
        return ClassDescriptor(
            name = "Location",
            superClass = null,
            methods = mapOf(
                "x" to Value.NativeFunction { Value.Double(location.x) },
                "y" to Value.NativeFunction { Value.Double(location.y) },
                "z" to Value.NativeFunction { Value.Double(location.z) },
                "yaw" to Value.NativeFunction { Value.Double(location.yaw.toDouble()) },
                "pitch" to Value.NativeFunction { Value.Double(location.pitch.toDouble()) },
                "block_x" to Value.NativeFunction { Value.Int(location.blockX) },
                "block_y" to Value.NativeFunction { Value.Int(location.blockY) },
                "block_z" to Value.NativeFunction { Value.Int(location.blockZ) },
                "world" to Value.NativeFunction { Value.String(location.world.name) }
            )
        )
    }
}