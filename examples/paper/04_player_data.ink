// Player Data Plugin
// Uses the database builtin to persist player data

// Register a players table with fields
db.registerTable("players", ["name", "score", "level"], 0)

// Save player data
fn savePlayer(name, score, level) {
    let player = {}
    player["name"] = name
    player["score"] = score
    player["level"] = level

    let existing = db.from("players").find(name)
    if existing != null {
        db.from("players").update(name, player)
    } else {
        db.from("players").insert(player)
    }
    log("Saved data for ${name}")
}

// Load player data
fn loadPlayer(name) {
    let player = db.from("players").find(name)
    if player != null {
        print("Found player: ${player["name"]} - Score: ${player["score"]}, Level: ${player["level"]}")
    } else {
        print("Player ${name} not found")
    }
    return player
}

// Example usage:
savePlayer("Steve", 100, 5)
loadPlayer("Steve")
