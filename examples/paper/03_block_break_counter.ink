// Block Break Counter Plugin
// Counts how many blocks each player breaks

let blockCounts = {}

on player_join(player) {
    let name = player.name
    if blockCounts has name {
        // Already exists, do nothing
    } else {
        blockCounts[name] = 0
    }
    print("You have broken ${blockCounts[name]} blocks total.")
}

on block_break(block, player) {
    let name = player.name
    let current = blockCounts[name] ?? 0
    blockCounts[name] = current + 1

    if blockCounts[name] % 10 == 0 {
        print("Achievement! You've broken ${blockCounts[name]} blocks!")
    }
}
