// Simple Teleport Plugin
// Teleport players to saved locations

class TeleportPoint {
    fn init(name, world, x, y, z) {
        self.name = name
        self.world = world
        self.x = x
        self.y = y
        self.z = z
    }

    fn toString() {
        return "${self.name} (${self.world}: ${self.x}, ${self.y}, ${self.z})"
    }
}

// Create some warp points
let warps = {}
warps["spawn"] = TeleportPoint("Spawn", "world", 0, 64, 0)
warps["farm"] = TeleportPoint("Farm", "world", 100, 64, 200)
warps["nether"] = TeleportPoint("Nether Portal", "world_nether", 50, 64, 50)

// List available warps
fn listWarps() {
    print("Available warps:")
    let i = 0
    for key in warps {
        let point = warps[key]
        print("  - ${point.toString()}")
        i = i + 1
    }
    print("Total: ${i} warps")
}

// Find warp by name
fn getWarp(name) {
    if warps has name {
        return warps[name]
    }
    return null
}

// Example usage:
listWarps()

let warp = getWarp("spawn")
if warp != null {
    print("Found warp: ${warp.toString()}")
} else {
    print("Warp not found!")
}
