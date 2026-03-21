package org.inklang.bukkit.runtime

import org.bukkit.entity.Player
import org.bukkit.Server
import org.inklang.lang.ClassDescriptor
import org.inklang.lang.Value

object InventoryClass {
    fun createDescriptor(player: Player, server: Server): ClassDescriptor {
        return ClassDescriptor(
            name = "Inventory",
            superClass = null,
            methods = mapOf(
                "title" to Value.NativeFunction { Value.String(player.openInventory.title) },
                "close" to Value.NativeFunction { args ->
                    player.closeInventory()
                    Value.Null
                }
            )
        )
    }
}